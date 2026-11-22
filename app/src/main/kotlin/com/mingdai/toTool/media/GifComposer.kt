package com.mingdai.toTool.media

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Locale

/** 把图片列表合成为 GIF。静态图片按停留帧重复；GIF / APNG 按自身帧展开。 */
internal fun composeGif(
    context: Context,
    plan: ConversionPlan,
    workDir: File,
    control: ConversionControl,
    onStage: (String) -> Unit,
    onProgress: (Float?) -> Unit,
): File {
    if (plan.gifItems.isEmpty()) throw ConvertException("GIF 图片列表是空的")
    val ffmpeg = ffmpegBinary(context) ?: throw ConvertException(ffmpegUnavailableMessage(context))
    val totalFrames = plan.gifTotalFrames.coerceIn(1, 10000)
    val root = File(workDir, "gif_${System.nanoTime()}")
    val sourceDir = File(root, "src").apply { mkdirs() }
    val baseFrames = mutableListOf<File>()
    try {
        plan.gifItems.forEachIndexed { index, item ->
            if (control.canceled) throw ConvertCanceledException()
            if (item.animated) {
                onStage("正在展开 ${item.name} 的动画帧…")
                onProgress(0.05f + index.toFloat() / plan.gifItems.size * 0.1f)
                val animInput = copyUriToTemp(
                    context = context,
                    uri = item.uri,
                    dir = sourceDir,
                    totalBytes = queryFileSize(context, item.uri),
                    canceled = control.flag,
                    prefix = "gif_src_",
                ) { }
                val animDir = File(sourceDir, "anim_$index").apply { mkdirs() }
                val pattern = File(animDir, "frame_%05d.png").absolutePath
                val command = listOf(
                    ffmpeg.absolutePath,
                    "-hide_banner", "-nostdin",
                    "-i", animInput.absolutePath,
                    "-vsync", "0",
                    "-y", pattern,
                )
                val result = runFfmpeg(command, control, null) { }
                animInput.delete()
                if (result is ConvertResult.Canceled) throw ConvertCanceledException()
                if (result is ConvertResult.Failure) {
                    throw ConvertException("展开动图失败：${result.message}")
                }
                val frames = animDir.listFiles { file -> file.isFile && file.name.endsWith(".png", true) }
                    ?.sortedBy { it.name }
                    .orEmpty()
                if (frames.isEmpty()) throw ConvertException("${item.name} 没有可用的动画帧")
                baseFrames.addAll(frames)
            } else {
                onStage("正在准备 ${item.name}…")
                onProgress(0.05f + index.toFloat() / plan.gifItems.size * 0.1f)
                val png = prepareLoopImage(context, item.uri, sourceDir, 1920, control.flag)
                repeat(item.holdFrames.coerceIn(1, 1000)) {
                    baseFrames.add(png)
                }
            }
        }
        if (baseFrames.isEmpty()) throw ConvertException("GIF 没有可用的画面")

        onStage("正在编排 $totalFrames 帧…")
        onProgress(0.18f)
        val timeline = ArrayList<File>(totalFrames)
        for (index in 0 until totalFrames) {
            if (control.canceled) throw ConvertCanceledException()
            timeline.add(baseFrames[index % baseFrames.size])
        }
        // 连续的同一张图合并成一个 GIF 帧 + 更长的延迟，保证“停留帧数”真正生效。
        val runs = ArrayList<Pair<File, Int>>()
        timeline.forEach { frame ->
            val last = runs.lastOrNull()
            if (last != null && last.first == frame) {
                runs[runs.lastIndex] = last.first to (last.second + 1)
            } else {
                runs.add(frame to 1)
            }
        }
        onProgress(0.35f)

        onStage("正在合成 GIF…")
        onProgress(0.52f)
        val concatFile = File(root, "concat.txt")
        concatFile.bufferedWriter().use { writer ->
            runs.forEach { (file, frames) ->
                writer.write("file '${file.absolutePath}'\n")
                writer.write("duration ${String.format(Locale.US, "%.3f", frames / 10.0)}\n")
            }
            // concat demuxer 会忽略最后一行的 duration；再写一次末图，让最后一帧也有完整停留时间。
            runs.lastOrNull()?.let { (file, _) ->
                writer.write("file '${file.absolutePath}'\n")
            }
        }

        val output = File(root, plan.outputName)
        val command = listOf(
            ffmpeg.absolutePath,
            "-hide_banner", "-nostdin",
            "-f", "concat",
            "-safe", "0",
            "-i", concatFile.absolutePath,
            "-vf", "scale=480:480:force_original_aspect_ratio=decrease," +
                "pad=480:480:(ow-iw)/2:(oh-ih)/2:color=black," +
                "split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse=dither=sierra2_4a",
            "-vsync", "vfr",
            "-loop", "0",
            "-max_muxing_queue_size", "4096",
            "-y", output.absolutePath,
        )
        val result = runFfmpeg(command, control, totalFrames / 10.0) { p ->
            onProgress(0.52f + (p ?: 0f) * 0.45f)
        }
        if (result is ConvertResult.Canceled) throw ConvertCanceledException()
        if (result is ConvertResult.Failure) throw ConvertException("GIF 合成失败：${result.message}")
        if (!output.exists() || output.length() == 0L) throw ConvertException("GIF 合成后没有生成文件")
        onProgress(1f)
        return output
    } finally {
        sourceDir.deleteRecursively()
    }
}
