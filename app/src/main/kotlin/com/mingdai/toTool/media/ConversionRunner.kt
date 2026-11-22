package com.mingdai.toTool.media

import android.content.Context
import java.io.File

internal data class ConversionOutcome(
    val result: String,
    val notice: String? = null,
)

private fun outputEstimate(plan: ConversionPlan, sourceDurationMs: Long): Long {
    val durationSec = (sourceDurationMs / 1000.0).coerceAtLeast(0.0)
    return when (plan.branch) {
        ConversionBranch.IMAGE_VIDEO ->
            ((plan.durationSeconds ?: 5.0) * 4_000_000L).toLong() + 64L * 1024 * 1024
        ConversionBranch.VIDEO_GIF,
        ConversionBranch.IMAGE_GIF -> plan.source.sizeBytes + 64L * 1024 * 1024
        ConversionBranch.AUDIO_VIDEO ->
            (durationSec * 2_500_000L / 8L).toLong() + 64L * 1024 * 1024
        ConversionBranch.VIDEO_AUDIO -> plan.source.sizeBytes / 6
        else -> plan.source.sizeBytes
    }
}

/**
 * 执行一次转换。所有临时文件都在 finally 里清理；成功保存到 MediaStore 后不保留应用目录副本。
 * 这个函数必须在 IO 线程调用。
 */
internal fun runConversion(
    context: Context,
    plan: ConversionPlan,
    capabilities: FfmpegCapabilities?,
    videoInfo: VideoFrameInfo?,
    audioInfo: AudioProbeInfo?,
    control: ConversionControl,
    onStage: (String) -> Unit,
    onProgress: (Float?) -> Unit,
): ConversionOutcome {
    when (plan.branch) {
        ConversionBranch.IMAGE_IMAGE -> {
            onStage("正在转换图片…")
            onProgress(null)
            val result = convertImageToFormat(
                context = context,
                uri = plan.source.uri,
                fmt = plan.target.id,
                baseName = plan.baseName,
                canceled = control.flag,
            )
            return ConversionOutcome("已保存到：${result.where}", result.notice)
        }

        ConversionBranch.VIDEO_IMAGE -> {
            onStage("正在提取视频帧…")
            onProgress(null)
            val index = plan.frameIndex ?: 1
            val frame = extractVideoFrame(context, plan.source.uri, index)
                ?: throw ConvertException("无法读取第 $index 帧，可能是视频编码不受支持或文件已损坏")
            try {
                val where = saveBitmapToGallery(
                    context = context,
                    bitmap = frame,
                    fmt = plan.target.id,
                    displayName = plan.outputName,
                    canceled = control.flag,
                )
                return ConversionOutcome("已保存到：$where")
            } finally {
                frame.recycle()
            }
        }

        else -> Unit
    }

    if (plan.branch == ConversionBranch.IMAGE_GIF) {
        val itemBytes = plan.gifItems.sumOf { queryFileSize(context, it.uri).coerceAtLeast(0L) }
        val estimatedFrames = plan.gifItems.sumOf { it.holdFrames.toLong().coerceAtLeast(1L) } +
            plan.gifItems.count { it.animated } * 30L
        val need = itemBytes + estimatedFrames * 128L * 1024L + MIN_FREE_HEADROOM_BYTES
        val workDir = pickWorkDir(context, need)
        cleanupStaleFiles(context)
        onStage("准备 GIF 合成…")
        onProgress(0f)
        val composition = composeGif(context, plan, workDir, control, onStage, onProgress)
        val output = composition.file
        try {
            onStage("正在保存动图到相册…")
            onProgress(0.96f)
            val (where, bytes) = saveImageToGallery(context, plan.outputName, "image/gif") { sink ->
                output.inputStream().use { it.copyTo(sink) }
            }
            onProgress(1f)
            return ConversionOutcome("已保存到：$where（${humanSize(bytes)}，${composition.frames} 帧时间线）")
        } finally {
            output.delete()
        }
    }

    val caps = capabilities ?: throw ConvertException(ffmpegUnavailableMessage(context))
    val ffmpeg = ffmpegBinary(context) ?: throw ConvertException(ffmpegUnavailableMessage(context))
    val sourceDurationMs = expectedSourceDurationMs(videoInfo, audioInfo)
    val musicSize = if (plan.branch == ConversionBranch.IMAGE_VIDEO && plan.music != null) {
        queryFileSize(context, plan.music).coerceAtLeast(0L)
    } else {
        0L
    }
    val need = plan.source.sizeBytes.coerceAtLeast(0L) + musicSize +
        outputEstimate(plan, sourceDurationMs) +
        MIN_FREE_HEADROOM_BYTES
    val workDir = pickWorkDir(context, need)
    cleanupStaleFiles(context)
    val outDir = context.getExternalFilesDir("conversions") ?: context.filesDir
    outDir.mkdirs()
    val out = File(outDir, plan.outputName)

    var input: File? = null
    var cover: File? = null
    var music: File? = null
    var keepOutput = false
    try {
        when (plan.branch) {
            ConversionBranch.IMAGE_VIDEO -> {
                onStage(if (plan.source.animated) "正在读取动图…" else "正在准备图片…")
                onProgress(0f)
                input = if (plan.source.animated) {
                    copyUriToTemp(
                        context, plan.source.uri, workDir, plan.source.sizeBytes, control.flag,
                        prefix = "conv_anim_",
                    ) { p -> onProgress(p?.let { it * 0.12f }) }
                } else {
                    prepareLoopImage(
                        context = context,
                        uri = plan.source.uri,
                        dir = workDir,
                        maxSide = 4096,
                        canceled = control.flag,
                    )
                }
                if (plan.music != null) {
                    onStage("正在读取配乐…")
                    music = copyUriToTemp(
                        context, plan.music, workDir, musicSize, control.flag,
                        prefix = "conv_music_",
                    ) { p -> onProgress(0.12f + (p ?: 0f) * 0.08f) }
                }
            }
            ConversionBranch.IMAGE_GIF -> {
                onStage("正在读取动图…")
                onProgress(0f)
                input = copyUriToTemp(
                    context, plan.source.uri, workDir, plan.source.sizeBytes, control.flag,
                    prefix = "conv_anim_",
                ) { p -> onProgress(p?.let { it * 0.15f }) }
            }
            ConversionBranch.AUDIO_VIDEO -> {
                onStage("正在读取音频…")
                onProgress(0f)
                input = copyUriToTemp(
                    context, plan.source.uri, workDir, plan.source.sizeBytes, control.flag,
                    prefix = "conv_audio_",
                ) { p -> onProgress(p?.let { it * 0.15f }) }
                if (plan.visualMode != VisualMode.WAVEFORM) {
                    onStage("正在准备画面…")
                    onProgress(0.16f)
                    cover = prepareArtwork(
                        context = context,
                        audioUri = plan.source.uri,
                        mode = plan.visualMode,
                        customImage = plan.customImage,
                        solidColor = plan.solidColor,
                        audioTitle = audioInfo?.title?.ifBlank { null } ?: plan.baseName,
                        audioDurationMs = audioInfo?.durationMs ?: 0L,
                        width = plan.resolution.width,
                        height = plan.resolution.height,
                        dir = workDir,
                    )
                }
            }
            else -> {
                onStage("正在读取文件…")
                onProgress(0f)
                input = copyUriToTemp(
                    context, plan.source.uri, workDir, plan.source.sizeBytes, control.flag,
                    prefix = "conv_in_",
                ) { p -> onProgress(p?.let { it * 0.15f }) }
            }
        }

        val inputFile = input ?: throw ConvertException("无法准备输入文件")
        val attempts = buildFfmpegAttempts(
            plan = plan,
            capabilities = caps,
            inputPath = inputFile.absolutePath,
            coverPath = cover?.absolutePath,
            sourceDurationMs = sourceDurationMs,
            musicPath = music?.absolutePath,
        )
        if (attempts.isEmpty()) throw ConvertException("没有可用的 ${plan.target.id} 编码器")

        val expectedDurationSec = when {
            plan.branch == ConversionBranch.IMAGE_VIDEO -> plan.durationSeconds
            else -> sourceDurationMs.takeIf { it > 0L }?.div(1000.0)
        }

        onStage("正在转换…")
        onProgress(0.18f)
        var done = false
        var lastFailure: ConvertResult.Failure? = null
        for (attempt in attempts) {
            if (control.canceled) throw ConvertCanceledException()
            out.delete()
            val cmd = listOf(ffmpeg.absolutePath, "-hide_banner", "-nostdin") +
                attempt +
                listOf("-max_muxing_queue_size", "4096", "-y", out.absolutePath)
            when (val result = runFfmpeg(cmd, control, expectedDurationSec) { p ->
                onProgress(0.18f + (p ?: 0f) * 0.68f)
            }) {
                is ConvertResult.Success -> done = true
                is ConvertResult.Canceled -> throw ConvertCanceledException()
                is ConvertResult.Failure -> lastFailure = result
            }
            if (done) break
        }
        if (!done) throw ConvertException(lastFailure?.message ?: "转换失败")

        input?.delete()
        input = null
        cover?.delete()
        cover = null

        if (plan.branch == ConversionBranch.VIDEO_GIF || plan.branch == ConversionBranch.IMAGE_GIF) {
            onStage("正在保存动图到相册…")
            onProgress(0.9f)
            val (where, bytes) = saveImageToGallery(context, plan.outputName, "image/gif") { sink ->
                out.inputStream().use { inputStream -> inputStream.copyTo(sink) }
            }
            onProgress(1f)
            return ConversionOutcome("已保存到：$where（${humanSize(bytes)}）")
        }

        onStage("正在保存到下载目录…")
        onProgress(0.88f)
        val published = publishToDownloads(
            context, out, plan.target.mime, control.flag,
        ) { p -> onProgress(0.88f + (p ?: 0f) * 0.12f) }
        if (published.path != null) {
            onProgress(1f)
            return ConversionOutcome("已保存到：${published.path}")
        }

        keepOutput = true
        return ConversionOutcome(
            result = out.absolutePath,
            notice = "未能保存到下载目录" +
                (published.error?.let { "（$it）" } ?: "") +
                "，文件保留在应用目录。",
        )
    } finally {
        input?.delete()
        cover?.delete()
        music?.delete()
        if (!keepOutput) out.delete()
    }
}
