package com.mingdai.toTool.ui.screens.tools

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import com.mingdai.toTool.media.ConvertCanceledException
import com.mingdai.toTool.media.ConvertException
import com.mingdai.toTool.media.ConvertResult
import com.mingdai.toTool.media.FfmpegCapabilities
import com.mingdai.toTool.media.humanSize
import com.mingdai.toTool.media.baseNameOf
import com.mingdai.toTool.media.firstAvailable
import com.mingdai.toTool.media.normalizedExtension
import com.mingdai.toTool.media.probeVideo
import com.mingdai.toTool.media.runFfmpeg
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.LabeledPickerCard
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun MediaToolScreen(toolId: String, padding: PaddingValues) {
    when (toolId) {
        "media_video_reverse" -> VideoReverseTool(padding)
        "media_audio_reverse" -> AudioReverseTool(padding)
        "media_pip" -> PictureInPictureTool(padding)
        "media_subtitle" -> SubtitleBurnTool(padding)
        "media_trim" -> VideoTrimTool(padding)
        "media_watermark" -> VideoWatermarkTool(padding)
        "media_compress" -> VideoCompressTool(padding)
        "media_rotate" -> VideoRotateTool(padding)
        "media_speed" -> VideoSpeedTool(padding)
        else -> ToolBody(padding) { Text("未实现的媒体工具") }
    }
}

@Composable
private fun MediaSourceCard(
    title: String,
    name: String?,
    supporting: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    LabeledPickerCard(
        title = title,
        value = name ?: "选择文件…",
        supporting = supporting?.takeIf { it.isNotBlank() },
        icon = icon,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

private enum class AudioReverseTarget(val label: String) {
    ORIGINAL("跟随原格式"),
    M4A("M4A（AAC）"),
    MP3("MP3"),
}

private data class AudioReverseOutput(
    val ext: String,
    val mime: String,
    val codec: String,
    val codecArgs: List<String> = emptyList(),
    val containerArgs: List<String> = emptyList(),
    val formatArgs: List<String> = emptyList(),
)

private fun m4aOutput(): AudioReverseOutput = AudioReverseOutput(
    ext = "m4a",
    mime = "audio/mp4",
    codec = "aac",
    codecArgs = listOf("-b:a", "192k"),
    containerArgs = listOf("-movflags", "+faststart"),
)

private fun mp3Output(): AudioReverseOutput = AudioReverseOutput(
    ext = "mp3",
    mime = "audio/mpeg",
    codec = "libmp3lame",
    codecArgs = listOf("-q:a", "2"),
)

private fun AudioReverseOutput.availableOr(fallback: AudioReverseOutput, encoders: Set<String>?): AudioReverseOutput =
    if (encoders == null || codec in encoders) this else fallback

private fun resolveAudioReverseOutput(
    target: AudioReverseTarget,
    sourceExt: String,
    capabilities: FfmpegCapabilities?,
): AudioReverseOutput {
    if (target == AudioReverseTarget.M4A) return m4aOutput()
    if (target == AudioReverseTarget.MP3) return mp3Output()
    val encoders = capabilities?.encoders
    val fallback = m4aOutput()
    return when (sourceExt.lowercase(Locale.ROOT)) {
        "mp3" -> mp3Output().availableOr(fallback, encoders)
        "wav", "wave" -> AudioReverseOutput("wav", "audio/x-wav", "pcm_s16le")
            .availableOr(fallback, encoders)
        "flac" -> AudioReverseOutput("flac", "audio/flac", "flac")
            .availableOr(fallback, encoders)
        "aac" -> AudioReverseOutput(
            "aac", "audio/aac", "aac",
            formatArgs = listOf("-f", "adts"),
        ).availableOr(fallback, encoders)
        "m4a", "m4b" -> m4aOutput()
        "ogg", "oga" -> {
            val codec = firstAvailable(listOf("libvorbis", "libopus", "vorbis"), encoders)
            when (codec) {
                null -> fallback
                "libvorbis" -> AudioReverseOutput("ogg", "audio/ogg", codec, listOf("-q:a", "5"))
                "libopus" -> AudioReverseOutput("ogg", "audio/ogg", codec, listOf("-b:a", "128k"))
                else -> AudioReverseOutput(
                    "ogg", "audio/ogg", codec,
                    listOf("-strict", "-2", "-q:a", "5"),
                )
            }
        }
        "opus" -> AudioReverseOutput(
            "opus", "audio/opus", "libopus",
            codecArgs = listOf("-b:a", "128k"),
            formatArgs = listOf("-f", "opus"),
        ).availableOr(fallback, encoders)
        "aif", "aiff" -> AudioReverseOutput("aiff", "audio/aiff", "pcm_s16be")
            .availableOr(fallback, encoders)
        "wma" -> AudioReverseOutput("wma", "audio/x-ms-wma", "wmav2", listOf("-b:a", "192k"))
            .availableOr(fallback, encoders)
        else -> fallback
    }
}

@Composable
private fun VideoReverseTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberMediaToolState("media_video_reverse", "视频倒放")
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(-1L) }
    val info = rememberVideoProbe(uri)
    val picker = rememberMediaPicker("video/*") { picked, pickedName, pickedSize ->
        uri = picked; name = pickedName; size = pickedSize; state.resetMessages()
    }
    ToolBody(padding) {
        MediaSourceCard(
            "视频文件", name, mediaInfoLabel(name ?: "", size, info),
            Icons.Filled.VideoLibrary, picker, !state.running,
        )
        if (uri != null) {
            val previewTime = representativePreviewTime(info)
            MediaPreviewCard(
                rememberVideoFrame(uri, previewTime, 640),
                caption = "倒放预览（静帧） · ${mediaTimeLabel(previewTime)}",
            )
        }
        HintText("倒放会同时反转视频和音轨。反转变换需要把整段视频读入内存，长视频可能很慢或占用大量内存。")
        if ((info?.durationMs ?: 0L) > 5 * 60 * 1000L) {
            HintText("当前视频超过 5 分钟，倒放可能因内存不足失败。")
        }
        PrimaryButton(
            onClick = {
                val selected = uri ?: return@PrimaryButton
                state.launch(scope) {
                    runMediaJob(
                        context, state, listOf(selected),
                        "${baseNameOf(name ?: "video")}_reverse.mp4", "video/mp4",
                        info?.durationMs?.takeIf { it > 0 }?.div(1000.0),
                    ) { files, output, _, ffmpeg, caps ->
                        val hasAudio = info?.hasAudio != false
                        buildList {
                            add(ffmpeg.absolutePath)
                            addAll(listOf("-hide_banner", "-nostdin", "-i", files[0].absolutePath))
                            addAll(listOf("-vf", "reverse"))
                            if (hasAudio) addAll(listOf("-af", "areverse")) else add("-an")
                            addAll(videoEncodeArgs(caps))
                            if (hasAudio) addAll(standardAudioArgs)
                            addAll(listOf("-movflags", "+faststart", "-max_muxing_queue_size", "4096", "-y", output.absolutePath))
                        }
                    }
                }
            },
            enabled = uri != null && !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("开始倒放") }
        MediaToolStatus(state)
    }
}

@Composable
private fun AudioReverseTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberMediaToolState("media_audio_reverse", "音频倒放")
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(-1L) }
    var target by remember { mutableStateOf(AudioReverseTarget.ORIGINAL) }
    val info = rememberAudioProbe(uri)
    val capabilities = rememberFfmpegCapabilities()
    val sourceExt = normalizedExtension(name)
    val output = resolveAudioReverseOutput(target, sourceExt, capabilities)
    val picker = rememberMediaPicker("audio/*") { picked, pickedName, pickedSize ->
        uri = picked; name = pickedName; size = pickedSize; state.resetMessages()
    }
    ToolBody(padding) {
        MediaSourceCard(
            "音频文件", name,
            listOfNotNull(
                humanSize(size).takeIf { size > 0 },
                info?.durationMs?.takeIf { it > 0 }?.let { mediaTimeLabel(it) },
            ).joinToString(" · ").ifBlank { "选择音频文件" },
            Icons.Filled.AudioFile, picker, !state.running,
        )
        if (uri != null) {
            MediaPreviewCard(
                rememberAudioCover(uri),
                caption = "试听原音频",
                height = 140.dp,
                emptyText = "音频没有内嵌封面",
            )
            AudioPreviewButton(uri, !state.running)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioReverseTarget.entries.forEach { option ->
                SelectChip(
                    selected = target == option,
                    onClick = { target = option; state.resetMessages() },
                    label = if (option == AudioReverseTarget.ORIGINAL) {
                        "跟随原格式（${output.ext.uppercase(Locale.ROOT)}）"
                    } else {
                        option.label
                    },
                    enabled = !state.running,
                )
            }
        }
        HintText(
            "输出格式：${output.ext.uppercase(Locale.ROOT)} · ${output.codec}" +
                if (target == AudioReverseTarget.ORIGINAL) "。不支持的源格式会自动回退到 M4A。" else "。",
        )
        PrimaryButton(
            onClick = {
                val selected = uri ?: return@PrimaryButton
                state.launch(scope) {
                    runMediaJob(
                        context, state, listOf(selected),
                        "${baseNameOf(name ?: "audio")}_reverse.${output.ext}", output.mime,
                        info?.durationMs?.takeIf { it > 0 }?.div(1000.0),
                    ) { files, jobOutput, _, ffmpeg, caps ->
                        if (caps.encoders?.contains(output.codec) == false) {
                            throw ConvertException("内置 FFmpeg 没有 ${output.codec} 编码器，请改选其它格式")
                        }
                        buildList {
                            add(ffmpeg.absolutePath)
                            addAll(listOf(
                                "-hide_banner", "-nostdin", "-i", files[0].absolutePath,
                                "-vn", "-af", "areverse", "-c:a", output.codec,
                            ))
                            addAll(output.codecArgs)
                            addAll(output.containerArgs)
                            addAll(output.formatArgs)
                            addAll(listOf("-max_muxing_queue_size", "4096", "-y", jobOutput.absolutePath))
                        }
                    }
                }
            },
            enabled = uri != null && !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("开始倒放") }
        MediaToolStatus(state)
    }
}

private fun Long.toSecondsOrNull(): Double? = takeIf { it > 0L }?.div(1000.0)

@Composable
private fun PictureInPictureTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberMediaToolState("media_pip", "画中画")
    var mainUri by remember { mutableStateOf<Uri?>(null) }
    var mainName by remember { mutableStateOf<String?>(null) }
    var mainSize by remember { mutableStateOf(-1L) }
    var insetUri by remember { mutableStateOf<Uri?>(null) }
    var insetName by remember { mutableStateOf<String?>(null) }
    var insetSize by remember { mutableStateOf(-1L) }
    var corner by remember { mutableStateOf(OverlayCorner.TOP_RIGHT) }
    val mainInfo = rememberVideoProbe(mainUri)
    val insetInfo = rememberVideoProbe(insetUri)
    val mainPicker = rememberMediaPicker("video/*") { picked, pickedName, pickedSize ->
        mainUri = picked; mainName = pickedName; mainSize = pickedSize; state.resetMessages()
    }
    val insetPicker = rememberMediaPicker("video/*") { picked, pickedName, pickedSize ->
        insetUri = picked; insetName = pickedName; insetSize = pickedSize; state.resetMessages()
    }
    ToolBody(padding) {
        MediaSourceCard("主视频", mainName, mediaInfoLabel(mainName ?: "", mainSize, mainInfo), Icons.Filled.VideoLibrary, mainPicker, !state.running)
        MediaSourceCard("画中画视频", insetName, mediaInfoLabel(insetName ?: "", insetSize, insetInfo), Icons.Filled.PictureInPictureAlt, insetPicker, !state.running)
        if (mainUri != null || insetUri != null) {
            val mainFrame = rememberVideoFrame(mainUri, representativePreviewTime(mainInfo), 640)
            val insetFrame = rememberVideoFrame(insetUri, representativePreviewTime(insetInfo), 360)
            MediaPreviewCard(
                PreviewFrame(
                    rememberPipPreview(mainFrame.bitmap, insetFrame.bitmap, corner),
                    mainFrame.loading || insetFrame.loading,
                ),
                caption = if (mainUri == null || insetUri == null) {
                    "选择两段视频后可预览画中画"
                } else {
                    "画中画预览 · ${corner.label}"
                },
                emptyText = "选择视频后可预览",
            )
        }
        Text("画中画位置", style = MiuixTheme.textStyles.body2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayCorner.entries.forEach { option ->
                SelectChip(
                    selected = corner == option,
                    onClick = { corner = option; state.resetMessages() },
                    label = option.label,
                    enabled = !state.running,
                )
            }
        }
        HintText("画中画缩放为主视频宽度的 28%，使用主视频的音轨。")
        PrimaryButton(
            onClick = {
                val main = mainUri ?: return@PrimaryButton
                val inset = insetUri ?: return@PrimaryButton
                state.launch(scope) {
                    runMediaJob(
                        context, state, listOf(main, inset),
                        "${baseNameOf(mainName ?: "video")}_pip.mp4", "video/mp4",
                        mainInfo?.durationMs?.toSecondsOrNull(),
                    ) { files, output, _, ffmpeg, caps ->
                        val filter = "[1:v][0:v]scale2ref=w=main_w*0.28:h=-2[pip][base];" +
                            "[base][pip]overlay=${corner.expression}:eof_action=pass:format=auto[outv]"
                        buildList {
                            add(ffmpeg.absolutePath)
                            addAll(listOf("-hide_banner", "-nostdin", "-i", files[0].absolutePath, "-i", files[1].absolutePath))
                            addAll(listOf("-filter_complex", filter, "-map", "[outv]", "-map", "0:a:0?"))
                            addAll(videoEncodeArgs(caps))
                            addAll(standardAudioArgs)
                            addAll(listOf("-movflags", "+faststart", "-max_muxing_queue_size", "4096", "-y", output.absolutePath))
                        }
                    }
                }
            },
            enabled = mainUri != null && insetUri != null && !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("生成画中画") }
        MediaToolStatus(state)
    }
}


private enum class SubtitleSource(val label: String) {
    FILE("字幕文件"),
    EMBEDDED("视频内嵌字幕"),
}

private enum class WatermarkMode(val label: String) {
    TEXT("文字"),
    IMAGE("图片"),
}

private enum class RotateAngle(val degrees: Int) {
    ROTATE_90(90),
    ROTATE_180(180),
    ROTATE_270(270),
}

@Composable
private fun SubtitleBurnTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberMediaToolState("media_subtitle", "字幕烧录")
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var videoName by remember { mutableStateOf<String?>(null) }
    var videoSize by remember { mutableStateOf(-1L) }
    var subtitleUri by remember { mutableStateOf<Uri?>(null) }
    var subtitleName by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf(SubtitleSource.FILE) }
    val info = rememberVideoProbe(videoUri)
    val videoPicker = rememberMediaPicker("video/*") { picked, pickedName, pickedSize ->
        videoUri = picked; videoName = pickedName; videoSize = pickedSize; state.resetMessages()
    }
    val subtitlePicker = rememberMediaPicker("*/*") { picked, pickedName, _ ->
        subtitleUri = picked; subtitleName = pickedName; state.resetMessages()
    }
    ToolBody(padding) {
        MediaSourceCard("视频文件", videoName, mediaInfoLabel(videoName ?: "", videoSize, info), Icons.Filled.VideoLibrary, videoPicker, !state.running)
        Text("字幕来源", style = MiuixTheme.textStyles.body2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleSource.entries.forEach { option ->
                SelectChip(
                    selected = source == option,
                    onClick = { source = option; state.resetMessages() },
                    label = option.label,
                    enabled = !state.running,
                )
            }
        }
        if (source == SubtitleSource.FILE) {
            MediaSourceCard("字幕文件（SRT / ASS）", subtitleName, subtitleName?.let { "支持 SRT、ASS" } ?: "选择字幕文件", Icons.Filled.Subtitles, subtitlePicker, !state.running)
        } else {
            HintText("从视频容器中读取第一条内嵌字幕轨（通常是 MKV）；如果没有字幕轨会提示失败。")
        }
        HintText("ASS 的复杂样式会简化为白色文字 + 黑色描边；最多烧录前 300 条字幕。输出为 MP4。")
        val subtitlePreview = rememberSubtitleCues(if (source == SubtitleSource.FILE) subtitleUri else null)
        if (videoUri != null) {
            val firstCue = subtitlePreview.first
            val previewTime = firstCue?.startMs ?: representativePreviewTime(info)
            val frame = rememberVideoFrame(videoUri, previewTime, 640)
            val previewBitmap = rememberSubtitlePreview(frame.bitmap, firstCue)
            val caption = when {
                source == SubtitleSource.EMBEDDED -> "内嵌字幕将在处理时读取 · 显示视频画面"
                subtitleUri == null -> "选择字幕后可预览第一条字幕"
                subtitlePreview.loading -> "正在解析字幕…"
                firstCue == null -> "未解析到字幕 · 显示视频画面"
                else -> "字幕预览 · 第 1 条 · ${mediaTimeLabel(firstCue.startMs)}"
            }
            MediaPreviewCard(
                PreviewFrame(previewBitmap, frame.loading || subtitlePreview.loading),
                caption = caption,
            )
        }
        PrimaryButton(
            onClick = {
                val video = videoUri ?: return@PrimaryButton
                if (source == SubtitleSource.FILE && subtitleUri == null) return@PrimaryButton
                state.launch(scope) {
                    runMediaJob(
                        context, state, listOf(video),
                        "${baseNameOf(videoName ?: "video")}_subtitled.mp4", "video/mp4",
                        info?.durationMs?.toSecondsOrNull(),
                    ) { files, output, jobDir, ffmpeg, caps ->
                        val subtitleText = if (source == SubtitleSource.EMBEDDED) {
                            state.stage = "正在读取内嵌字幕…"
                            val extracted = File(jobDir, "subtitle.ass")
                            val cmd = listOf(
                                ffmpeg.absolutePath, "-hide_banner", "-nostdin",
                                "-i", files[0].absolutePath,
                                "-map", "0:s:0", "-c:s", "ass", "-y", extracted.absolutePath,
                            )
                            when (val r = runFfmpeg(cmd, state.control, null) { }) {
                                ConvertResult.Success -> Unit
                                ConvertResult.Canceled -> throw ConvertCanceledException()
                                is ConvertResult.Failure -> throw ConvertException("没有可转换的文本内嵌字幕轨（PGS 等图形字幕不支持）")
                            }
                            decodeSubtitleText(extracted.readBytes())
                        } else {
                            val subtitle = subtitleUri ?: throw ConvertException("请选择字幕文件")
                            context.contentResolver.openInputStream(subtitle)?.use { decodeSubtitleText(it.readBytes()) }
                                ?: throw ConvertException("无法读取字幕文件")
                        }
                        val allCues = parseSubtitle(subtitleText, subtitleName.orEmpty())
                        if (allCues.isEmpty()) throw ConvertException("没有解析到可用的字幕")
                        val cues = allCues.take(300)
                        if (allCues.size > cues.size) {
                            state.notice = "字幕共 ${allCues.size} 条，本次只烧录前 ${cues.size} 条。"
                        }
                        val videoInfo = info ?: probeVideo(context, video)
                        val rawW = videoInfo?.width?.takeIf { it > 0 } ?: 1920
                        val rawH = videoInfo?.height?.takeIf { it > 0 } ?: 1080
                        val rotation = videoInfo?.rotationDegrees ?: 0
                        val shownW = if (rotation % 180 != 0) rawH else rawW
                        val shownH = if (rotation % 180 != 0) rawW else rawH
                        val renderScale = min(1.0, 1280.0 / max(shownW, shownH))
                        val overlayW = (shownW * renderScale).roundToInt().coerceAtLeast(2)
                        val overlayH = (shownH * renderScale).roundToInt().coerceAtLeast(2)
                        val cueFiles = cues.mapIndexed { index, cue ->
                            if (state.control.canceled) throw ConvertCanceledException()
                            state.stage = "正在生成字幕画面 ${index + 1}/${cues.size}…"
                            state.progress = 0.15f + index.toFloat() / cues.size * 0.15f
                            val file = File(jobDir, "cue_${index}.png")
                            savePng(renderSubtitleCue(cue, overlayW, overlayH), file)
                            file
                        }
                        val gapFile = File(jobDir, "gap.png")
                        saveTransparentFrame(gapFile, overlayW, overlayH)
                        val concatFile = File(jobDir, "subtitles.txt")
                        var cursorSec = 0.0
                        var lastFrame: File? = null
                        concatFile.bufferedWriter().use { writer ->
                            fun writeFrame(file: File, durationSec: Double) {
                                if (durationSec < 0.001) return
                                writer.write("file '${file.absolutePath}'\n")
                                writer.write("duration ${formatSecondsForFfmpeg(durationSec)}\n")
                                lastFrame = file
                            }
                            cues.forEachIndexed { index, cue ->
                                val start = cue.startMs / 1000.0
                                val end = cue.endMs / 1000.0
                                if (start > cursorSec) {
                                    writeFrame(gapFile, start - cursorSec)
                                    cursorSec = start
                                }
                                if (end > cursorSec) {
                                    writeFrame(cueFiles[index], end - maxOf(start, cursorSec))
                                    cursorSec = end
                                }
                            }
                            // 末尾补透明帧到 24 小时，配合 shortest=1 让输出严格跟随主视频时长。
                            writeFrame(gapFile, 86_400.0)
                            // concat demuxer 会忽略最后一行的 duration，再写一次末帧。
                            lastFrame?.let { writer.write("file '${it.absolutePath}'\n") }
                        }
                        val subtitleFilter = if (shownW == overlayW && shownH == overlayH) {
                            "[1:v]null[sub]"
                        } else {
                            "[1:v]scale=$shownW:$shownH[sub]"
                        }
                        val filter = "$subtitleFilter;[0:v][sub]overlay=0:0:format=auto:eof_action=pass:shortest=1[outv]"
                        val hasAudio = videoInfo?.hasAudio != false
                        buildList {
                            add(ffmpeg.absolutePath)
                            addAll(listOf(
                                "-hide_banner", "-nostdin",
                                "-i", files[0].absolutePath,
                                "-f", "concat", "-safe", "0", "-i", concatFile.absolutePath,
                                "-filter_complex", filter, "-map", "[outv]",
                            ))
                            if (hasAudio) addAll(listOf("-map", "0:a:0?"))
                            addAll(videoEncodeArgs(caps))
                            if (hasAudio) addAll(standardAudioArgs)
                            addAll(listOf("-movflags", "+faststart", "-max_muxing_queue_size", "4096", "-y", output.absolutePath))
                        }
                    }
                }
            },
            enabled = videoUri != null &&
                (source == SubtitleSource.EMBEDDED || subtitleUri != null) &&
                !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("烧录字幕并输出 MP4") }
        MediaToolStatus(state)
    }
}

@Composable
private fun VideoTrimTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberMediaToolState("media_trim", "视频裁剪")
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(-1L) }
    var startSec by remember { mutableStateOf(0f) }
    var endSec by remember { mutableStateOf(0f) }
    val info = rememberVideoProbe(uri)
    val duration = (info?.durationMs ?: 0L) / 1000f
    LaunchedEffect(uri, info?.durationMs) {
        if (duration > 0f) {
            startSec = 0f
            endSec = duration
        }
    }
    val picker = rememberMediaPicker("video/*") { picked, pickedName, pickedSize ->
        uri = picked; name = pickedName; size = pickedSize; state.resetMessages()
    }
    ToolBody(padding) {
        MediaSourceCard("视频文件", name, mediaInfoLabel(name ?: "", size, info), Icons.Filled.VideoLibrary, picker, !state.running)
        if (uri != null) {
            val previewMs = (startSec * 1000).toLong()
            MediaPreviewCard(
                rememberVideoFrame(uri, previewMs, 640, debounceMs = 150),
                caption = "起始位置预览 · ${mediaTimeLabel(previewMs)}",
            )
        }
        if (duration > 0f) {
            Text("起始位置：${mediaTimeLabel((startSec * 1000).toLong())}", style = MiuixTheme.textStyles.body2)
            Slider(
                value = startSec,
                onValueChange = {
                    startSec = it.coerceIn(0f, (endSec - 0.1f).coerceAtLeast(0f))
                    state.resetMessages()
                },
                valueRange = 0f..duration,
                enabled = !state.running,
            )
            Text("结束位置：${mediaTimeLabel((endSec * 1000).toLong())}", style = MiuixTheme.textStyles.body2)
            Slider(
                value = endSec,
                onValueChange = {
                    val minEnd = (startSec + 0.1f).coerceAtMost(duration)
                    endSec = it.coerceIn(minEnd, duration)
                    state.resetMessages()
                },
                valueRange = 0f..duration,
                enabled = !state.running,
            )
            Text(
                "裁剪后时长：${mediaTimeLabel(((endSec - startSec) * 1000).toLong())}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            HintText("选择视频后会读取时长，然后可以拖动起始和结束位置。")
        }
        PrimaryButton(
            onClick = {
                val selected = uri ?: return@PrimaryButton
                if (endSec <= startSec) return@PrimaryButton
                state.launch(scope) {
                    runMediaJob(
                        context, state, listOf(selected),
                        "${baseNameOf(name ?: "video")}_trim.mp4", "video/mp4",
                        (endSec - startSec).toDouble(),
                    ) { files, output, _, ffmpeg, caps ->
                        buildList {
                            add(ffmpeg.absolutePath)
                            addAll(listOf(
                                "-hide_banner", "-nostdin",
                                "-ss", formatSecondsForFfmpeg(startSec.toDouble()),
                                "-i", files[0].absolutePath,
                                "-t", formatSecondsForFfmpeg((endSec - startSec).toDouble()),
                                "-map", "0:v:0", "-map", "0:a:0?",
                            ))
                            addAll(videoEncodeArgs(caps, crf = 20, preset = "veryfast"))
                            addAll(standardAudioArgs)
                            addAll(listOf("-movflags", "+faststart", "-max_muxing_queue_size", "4096", "-y", output.absolutePath))
                        }
                    }
                }
            },
            enabled = uri != null && duration > 0f && endSec > startSec && !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("裁剪并输出 MP4") }
        MediaToolStatus(state)
    }
}

@Composable
private fun VideoWatermarkTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberMediaToolState("media_watermark", "视频水印")
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var videoName by remember { mutableStateOf<String?>(null) }
    var videoSize by remember { mutableStateOf(-1L) }
    var mode by remember { mutableStateOf(WatermarkMode.TEXT) }
    var text by remember { mutableStateOf("toTool") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var imageName by remember { mutableStateOf<String?>(null) }
    var corner by remember { mutableStateOf(OverlayCorner.BOTTOM_RIGHT) }
    var opacity by remember { mutableStateOf(0.7f) }
    val info = rememberVideoProbe(videoUri)
    val videoPicker = rememberMediaPicker("video/*") { picked, pickedName, pickedSize ->
        videoUri = picked; videoName = pickedName; videoSize = pickedSize; state.resetMessages()
    }
    val imagePicker = rememberMediaPicker("image/*") { picked, pickedName, _ ->
        imageUri = picked; imageName = pickedName; state.resetMessages()
    }
    ToolBody(padding) {
        MediaSourceCard("视频文件", videoName, mediaInfoLabel(videoName ?: "", videoSize, info), Icons.Filled.VideoLibrary, videoPicker, !state.running)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatermarkMode.entries.forEach { option ->
                SelectChip(
                    selected = mode == option,
                    onClick = { mode = option; state.resetMessages() },
                    label = option.label,
                    enabled = !state.running,
                )
            }
        }
        if (mode == WatermarkMode.TEXT) {
            TextField(
                value = text,
                onValueChange = { text = it; state.resetMessages() },
                label = "水印文字",
                singleLine = true,
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            MediaSourceCard("水印图片", imageName, imageName?.let { "支持 PNG / JPG" } ?: "选择 PNG / JPG", Icons.Filled.Image, imagePicker, !state.running)
        }
        if (videoUri != null) {
            val baseFrame = rememberVideoFrame(videoUri, representativePreviewTime(info), 640)
            val watermarkPreview = rememberWatermarkPreview(
                base = baseFrame.bitmap,
                baseLoading = baseFrame.loading,
                text = if (mode == WatermarkMode.TEXT) text else null,
                imageUri = if (mode == WatermarkMode.IMAGE) imageUri else null,
                corner = corner,
                opacity = opacity,
            )
            val caption = when {
                mode == WatermarkMode.TEXT && text.isBlank() -> "输入水印文字后预览"
                mode == WatermarkMode.IMAGE && imageUri == null -> "选择水印图片后预览"
                else -> "水印预览 · ${corner.label} · ${(opacity * 100).roundToInt()}%"
            }
            MediaPreviewCard(watermarkPreview, caption = caption)
        }
        Text("水印位置", style = MiuixTheme.textStyles.body2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayCorner.entries.forEach { option ->
                SelectChip(
                    selected = corner == option,
                    onClick = { corner = option; state.resetMessages() },
                    label = option.label,
                    enabled = !state.running,
                )
            }
        }
        Text("不透明度：${(opacity * 100).roundToInt()}%", style = MiuixTheme.textStyles.footnote2)
        Slider(
            value = opacity,
            onValueChange = { opacity = it; state.resetMessages() },
            valueRange = 0.1f..1f,
            enabled = !state.running,
        )
        PrimaryButton(
            onClick = {
                val video = videoUri ?: return@PrimaryButton
                if (mode == WatermarkMode.TEXT && text.isBlank()) return@PrimaryButton
                if (mode == WatermarkMode.IMAGE && imageUri == null) return@PrimaryButton
                state.launch(scope) {
                    runMediaJob(
                        context, state, listOf(video),
                        "${baseNameOf(videoName ?: "video")}_watermark.mp4", "video/mp4",
                        info?.durationMs?.toSecondsOrNull(),
                    ) { files, output, jobDir, ffmpeg, caps ->
                        val videoInfo = info ?: probeVideo(context, video)
                        val rawW = videoInfo?.width?.takeIf { it > 0 } ?: 1920
                        val rawH = videoInfo?.height?.takeIf { it > 0 } ?: 1080
                        val rotation = videoInfo?.rotationDegrees ?: 0
                        val shownW = if (rotation % 180 != 0) rawH else rawW
                        val shownH = if (rotation % 180 != 0) rawW else rawH
                        val renderScale = min(1.0, 1280.0 / max(shownW, shownH))
                        val overlayW = (shownW * renderScale).roundToInt().coerceAtLeast(2)
                        val overlayH = (shownH * renderScale).roundToInt().coerceAtLeast(2)
                        state.stage = "正在生成水印…"
                        val watermark = if (mode == WatermarkMode.TEXT) {
                            renderTextWatermark(text, overlayW, overlayH, corner, opacity)
                        } else {
                            renderImageWatermark(context, imageUri!!, overlayW, overlayH, corner, opacity)
                        }
                        val watermarkFile = File(jobDir, "watermark.png")
                        savePng(watermark, watermarkFile)
                        val filter = "[1:v]scale=$shownW:$shownH[wm];[0:v][wm]overlay=0:0:format=auto[outv]"
                        val hasAudio = videoInfo?.hasAudio != false
                        buildList {
                            add(ffmpeg.absolutePath)
                            addAll(listOf("-hide_banner", "-nostdin", "-i", files[0].absolutePath, "-i", watermarkFile.absolutePath))
                            addAll(listOf("-filter_complex", filter, "-map", "[outv]"))
                            if (hasAudio) addAll(listOf("-map", "0:a:0?"))
                            addAll(videoEncodeArgs(caps))
                            if (hasAudio) addAll(standardAudioArgs)
                            addAll(listOf("-movflags", "+faststart", "-max_muxing_queue_size", "4096", "-y", output.absolutePath))
                        }
                    }
                }
            },
            enabled = videoUri != null && (mode == WatermarkMode.TEXT || imageUri != null) && !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("添加水印并输出 MP4") }
        MediaToolStatus(state)
    }
}

private data class CompressPreset(val label: String, val height: Int)

private val compressPresets = listOf(
    CompressPreset("480p", 480),
    CompressPreset("540p", 540),
    CompressPreset("720p", 720),
    CompressPreset("1080p", 1080),
)

private val x264Presets = listOf("ultrafast", "fast", "medium", "slow")

@Composable
private fun VideoCompressTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberMediaToolState("media_compress", "视频压缩")
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(-1L) }
    var preset by remember { mutableStateOf(compressPresets[2]) }
    var crf by remember { mutableStateOf(23f) }
    var x264Preset by remember { mutableStateOf("medium") }
    val info = rememberVideoProbe(uri)
    val picker = rememberMediaPicker("video/*") { picked, pickedName, pickedSize ->
        uri = picked; name = pickedName; size = pickedSize; state.resetMessages()
    }
    ToolBody(padding) {
        MediaSourceCard("视频文件", name, mediaInfoLabel(name ?: "", size, info), Icons.Filled.VideoLibrary, picker, !state.running)
        if (uri != null) {
            MediaPreviewCard(
                rememberVideoFrame(uri, representativePreviewTime(info), 640),
                caption = "压缩预览 · ${preset.label} · CRF ${crf.roundToInt()} · $x264Preset",
            )
        }
        Text("分辨率", style = MiuixTheme.textStyles.body2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            compressPresets.forEach { option ->
                SelectChip(
                    selected = preset == option,
                    onClick = { preset = option; state.resetMessages() },
                    label = option.label,
                    enabled = !state.running,
                )
            }
        }
        Text("CRF 质量：${crf.roundToInt()}（数值越低质量越高、体积越大）", style = MiuixTheme.textStyles.footnote2)
        Slider(
            value = crf,
            onValueChange = { crf = it; state.resetMessages() },
            valueRange = 18f..32f,
            enabled = !state.running,
        )
        Text("编码预设", style = MiuixTheme.textStyles.body2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            x264Presets.forEach { option ->
                SelectChip(
                    selected = x264Preset == option,
                    onClick = { x264Preset = option; state.resetMessages() },
                    label = option,
                    enabled = !state.running,
                )
            }
        }
        HintText("使用 H.264 重新编码，音频使用 AAC；分辨率按高度缩放，保持画面比例。")
        PrimaryButton(
            onClick = {
                val selected = uri ?: return@PrimaryButton
                state.launch(scope) {
                    runMediaJob(
                        context, state, listOf(selected),
                        "${baseNameOf(name ?: "video")}_${preset.height}p.mp4", "video/mp4",
                        info?.durationMs?.toSecondsOrNull(),
                    ) { files, output, _, ffmpeg, caps ->
                        val hasAudio = info?.hasAudio != false
                        buildList {
                            add(ffmpeg.absolutePath)
                            addAll(listOf("-hide_banner", "-nostdin", "-i", files[0].absolutePath))
                            addAll(listOf("-vf", "scale=-2:min(${preset.height}\\,ih)"))
                            addAll(videoEncodeArgs(caps, crf.roundToInt(), x264Preset))
                            if (hasAudio) addAll(standardAudioArgs)
                            addAll(listOf("-movflags", "+faststart", "-max_muxing_queue_size", "4096", "-y", output.absolutePath))
                        }
                    }
                }
            },
            enabled = uri != null && !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("压缩并输出 MP4") }
        MediaToolStatus(state)
    }
}

@Composable
private fun VideoRotateTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberMediaToolState("media_rotate", "视频旋转")
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(-1L) }
    var angle by remember { mutableStateOf(RotateAngle.ROTATE_90) }
    val info = rememberVideoProbe(uri)
    val picker = rememberMediaPicker("video/*") { picked, pickedName, pickedSize ->
        uri = picked; name = pickedName; size = pickedSize; state.resetMessages()
    }
    ToolBody(padding) {
        MediaSourceCard("视频文件", name, mediaInfoLabel(name ?: "", size, info), Icons.Filled.VideoLibrary, picker, !state.running)
        if (uri != null) {
            val baseFrame = rememberVideoFrame(uri, representativePreviewTime(info), 640)
            MediaPreviewCard(
                PreviewFrame(rememberRotatedPreview(baseFrame.bitmap, angle.degrees), baseFrame.loading),
                caption = "旋转预览 · 顺时针 ${angle.degrees}°",
            )
        }
        Text("旋转方向", style = MiuixTheme.textStyles.body2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RotateAngle.entries.forEach { option ->
                SelectChip(
                    selected = angle == option,
                    onClick = { angle = option; state.resetMessages() },
                    label = "顺时针 ${option.degrees}°",
                    enabled = !state.running,
                )
            }
        }
        PrimaryButton(
            onClick = {
                val selected = uri ?: return@PrimaryButton
                state.launch(scope) {
                    runMediaJob(
                        context, state, listOf(selected),
                        "${baseNameOf(name ?: "video")}_rotate_${angle.degrees}.mp4", "video/mp4",
                        info?.durationMs?.toSecondsOrNull(),
                    ) { files, output, _, ffmpeg, caps ->
                        val filter = when (angle) {
                            RotateAngle.ROTATE_90 -> "transpose=1"
                            RotateAngle.ROTATE_180 -> "hflip,vflip"
                            RotateAngle.ROTATE_270 -> "transpose=2"
                        }
                        val hasAudio = info?.hasAudio != false
                        buildList {
                            add(ffmpeg.absolutePath)
                            addAll(listOf("-hide_banner", "-nostdin", "-i", files[0].absolutePath))
                            addAll(listOf("-vf", filter))
                            addAll(videoEncodeArgs(caps))
                            if (hasAudio) addAll(standardAudioArgs)
                            addAll(listOf("-movflags", "+faststart", "-max_muxing_queue_size", "4096", "-y", output.absolutePath))
                        }
                    }
                }
            },
            enabled = uri != null && !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("旋转并输出 MP4") }
        MediaToolStatus(state)
    }
}

private data class SpeedPreset(val factor: Float, val label: String)

private val speedPresets = listOf(
    SpeedPreset(0.5f, "0.5×"),
    SpeedPreset(0.75f, "0.75×"),
    SpeedPreset(1.25f, "1.25×"),
    SpeedPreset(1.5f, "1.5×"),
    SpeedPreset(2f, "2×"),
)

@Composable
private fun VideoSpeedTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberMediaToolState("media_speed", "视频变速")
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(-1L) }
    var speed by remember { mutableStateOf(speedPresets[3]) }
    val info = rememberVideoProbe(uri)
    val picker = rememberMediaPicker("video/*") { picked, pickedName, pickedSize ->
        uri = picked; name = pickedName; size = pickedSize; state.resetMessages()
    }
    ToolBody(padding) {
        MediaSourceCard("视频文件", name, mediaInfoLabel(name ?: "", size, info), Icons.Filled.VideoLibrary, picker, !state.running)
        if (uri != null) {
            val outMs = info?.durationMs?.takeIf { it > 0 }?.let { (it / speed.factor).toLong() }
            MediaPreviewCard(
                rememberVideoFrame(uri, representativePreviewTime(info), 640),
                caption = "变速预览 · ${speed.label}" +
                    (outMs?.let { " · 输出约 ${mediaTimeLabel(it)}" } ?: ""),
            )
        }
        Text("播放速度", style = MiuixTheme.textStyles.body2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            speedPresets.forEach { option ->
                SelectChip(
                    selected = speed == option,
                    onClick = { speed = option; state.resetMessages() },
                    label = option.label,
                    enabled = !state.running,
                )
            }
        }
        HintText("视频与音轨同步变速；支持 0.5×–2×。")
        PrimaryButton(
            onClick = {
                val selected = uri ?: return@PrimaryButton
                state.launch(scope) {
                    runMediaJob(
                        context, state, listOf(selected),
                        "${baseNameOf(name ?: "video")}_speed_${speed.label.replace("×", "x")}.mp4", "video/mp4",
                        info?.durationMs?.toSecondsOrNull()?.div(speed.factor.toDouble()),
                    ) { files, output, _, ffmpeg, caps ->
                        val factor = speed.factor
                        buildList {
                            add(ffmpeg.absolutePath)
                            addAll(listOf(
                                "-hide_banner", "-nostdin", "-i", files[0].absolutePath,
                                "-vf", "setpts=PTS/$factor",
                                "-af", "atempo=$factor",
                                "-map", "0:v:0", "-map", "0:a:0?",
                            ))
                            addAll(videoEncodeArgs(caps))
                            addAll(standardAudioArgs)
                            addAll(listOf("-movflags", "+faststart", "-max_muxing_queue_size", "4096", "-y", output.absolutePath))
                        }
                    }
                }
            },
            enabled = uri != null && !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("变速并输出 MP4") }
        MediaToolStatus(state)
    }
}
