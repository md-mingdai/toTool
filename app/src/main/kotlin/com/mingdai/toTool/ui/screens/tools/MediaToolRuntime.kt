package com.mingdai.toTool.ui.screens.tools

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mingdai.toTool.media.AudioProbeInfo
import com.mingdai.toTool.media.ConversionControl
import com.mingdai.toTool.media.ConvertCanceledException
import com.mingdai.toTool.media.ConvertResult
import com.mingdai.toTool.media.FfmpegCapabilities
import com.mingdai.toTool.media.VideoFrameInfo
import com.mingdai.toTool.media.copyUriToTemp
import com.mingdai.toTool.media.ffmpegBinary
import com.mingdai.toTool.media.ffmpegUnavailableMessage
import com.mingdai.toTool.media.friendlyError
import com.mingdai.toTool.media.humanSize
import com.mingdai.toTool.media.pickWorkDir
import com.mingdai.toTool.media.probeAudio
import com.mingdai.toTool.media.probeCapabilities
import com.mingdai.toTool.media.probeVideo
import com.mingdai.toTool.media.publishToDownloads
import com.mingdai.toTool.media.queryFileName
import com.mingdai.toTool.media.queryFileSize
import com.mingdai.toTool.media.runFfmpeg
import com.mingdai.toTool.ui.components.KeepScreenOn
import com.mingdai.toTool.ui.components.MessageCard
import com.mingdai.toTool.ui.components.ResultBox
import com.mingdai.toTool.ui.components.SecondaryButton
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 媒体工具共用的运行状态。 */
internal class MediaToolRunState {
    var running by mutableStateOf(false)
    var stage by mutableStateOf("")
    var progress by mutableStateOf<Float?>(0f)
    var result by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    val control = ConversionControl()

    fun resetMessages() {
        result = null
        error = null
        notice = null
    }
}

@Composable
internal fun rememberMediaToolState(): MediaToolRunState {
    val state = remember { MediaToolRunState() }
    DisposableEffect(Unit) {
        onDispose { state.control.cancel() }
    }
    KeepScreenOn(state.running)
    return state
}

internal fun MediaToolRunState.launch(
    scope: CoroutineScope,
    block: suspend () -> String,
) {
    if (running) return
    control.reset()
    running = true
    stage = "准备中…"
    progress = 0f
    resetMessages()
    scope.launch {
        try {
            result = withContext(Dispatchers.IO) { block() }
        } catch (e: ConvertCanceledException) {
            notice = "已取消"
        } catch (e: Exception) {
            error = friendlyError(e)
        } finally {
            stage = ""
            running = false
        }
    }
}

/** 标准的结果/进度/错误区。 */
@Composable
internal fun MediaToolStatus(state: MediaToolRunState) {
    if (state.running) {
        LinearProgressIndicator(progress = state.progress, modifier = Modifier.fillMaxWidth())
        Text(
            (state.stage.ifBlank { "正在处理…" }) +
                (state.progress?.let { " ${(it * 100).toInt().coerceIn(0, 100)}%" } ?: ""),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        SecondaryButton(
            onClick = { state.control.cancel() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("取消") }
    }
    state.notice?.let {
        MessageCard(
            title = null,
            message = it,
            container = MiuixTheme.colorScheme.secondaryContainer,
            content = MiuixTheme.colorScheme.onSecondaryContainer,
        )
    }
    state.error?.let {
        MessageCard(
            title = "处理失败",
            message = it,
            container = MiuixTheme.colorScheme.errorContainer,
            content = MiuixTheme.colorScheme.onErrorContainer,
        )
    }
    state.result?.let { ResultBox(it, label = "输出文件") }
}

@Composable
internal fun rememberMediaPicker(
    mime: String,
    onPicked: (Uri, String, Long) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            onPicked(uri, queryFileName(context, uri), queryFileSize(context, uri))
        }
    }
    return { launcher.launch(mime) }
}

@Composable
internal fun rememberVideoProbe(uri: Uri?): VideoFrameInfo? {
    val context = LocalContext.current
    var info by remember(uri) { mutableStateOf<VideoFrameInfo?>(null) }
    LaunchedEffect(uri) {
        info = if (uri == null) null else withContext(Dispatchers.IO) { probeVideo(context, uri) }
    }
    return info
}

@Composable
internal fun rememberAudioProbe(uri: Uri?): AudioProbeInfo? {
    val context = LocalContext.current
    var info by remember(uri) { mutableStateOf<AudioProbeInfo?>(null) }
    LaunchedEffect(uri) {
        info = if (uri == null) null else withContext(Dispatchers.IO) { probeAudio(context, uri) }
    }
    return info
}

internal fun mediaTimeLabel(millis: Long): String {
    val total = (millis.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

internal fun mediaInfoLabel(name: String, size: Long, info: VideoFrameInfo?): String {
    val parts = mutableListOf<String>()
    if (size > 0) parts += humanSize(size)
    info?.let {
        if (it.width > 0 && it.height > 0) parts += "${it.width}×${it.height}"
        if (it.durationMs > 0) parts += mediaTimeLabel(it.durationMs)
        if (!it.hasAudio) parts += "无音轨"
    }
    return parts.joinToString(" · ").ifBlank { name }
}

internal fun videoEncodeArgs(
    capabilities: FfmpegCapabilities,
    crf: Int = 20,
    preset: String = "veryfast",
): List<String> {
    val encoders = capabilities.encoders
    return if (encoders == null || "libx264" in encoders) {
        listOf(
            "-c:v", "libx264",
            "-crf", crf.toString(),
            "-preset", preset,
            "-pix_fmt", "yuv420p",
        )
    } else {
        listOf("-c:v", "mpeg4", "-q:v", "3", "-pix_fmt", "yuv420p")
    }
}

internal val standardAudioArgs = listOf("-c:a", "aac", "-b:a", "192k")

/**
 * 复制输入、执行 ffmpeg、保存到下载目录。jobDir 和临时输入在结束后清理；
 * 如果下载目录保存失败，保留应用目录里的成品并返回其路径。
 */
internal suspend fun runMediaJob(
    context: Context,
    state: MediaToolRunState,
    inputUris: List<Uri>,
    outputName: String,
    mime: String,
    expectedDurationSec: Double?,
    build: (files: List<File>, output: File, jobDir: File, ffmpeg: File, caps: FfmpegCapabilities) -> List<String>,
): String {
    val ffmpeg = ffmpegBinary(context) ?: throw com.mingdai.toTool.media.ConvertException(ffmpegUnavailableMessage(context))
    val caps = probeCapabilities(ffmpeg)
    val inputSizes = inputUris.sumOf { queryFileSize(context, it).coerceAtLeast(0L) }
    val workRoot = pickWorkDir(context, inputSizes + 128L * 1024 * 1024)
    val jobDir = File(workRoot, "conv_media_${System.nanoTime()}").apply { mkdirs() }
    val files = mutableListOf<File>()
    val output = File(jobDir, outputName)
    var keepOutput = false
    try {
        state.stage = "正在读取文件…"
        state.progress = 0f
        inputUris.forEachIndexed { index, uri ->
            val size = queryFileSize(context, uri)
            val file = copyUriToTemp(
                context, uri, jobDir, size, state.control.flag,
                prefix = "conv_media_in_${index}_",
            ) { p ->
                state.progress = (index + (p ?: 0f)) / inputUris.size * 0.15f
            }
            files += file
        }

        val command = build(files, output, jobDir, ffmpeg, caps)
        if (command.isEmpty()) throw com.mingdai.toTool.media.ConvertException("没有可执行的处理命令")
        state.stage = "正在处理…"
        state.progress = 0.15f
        when (val result = runFfmpeg(command, state.control, expectedDurationSec) { p ->
            state.progress = 0.15f + (p ?: 0f) * 0.75f
        }) {
            ConvertResult.Success -> Unit
            ConvertResult.Canceled -> throw ConvertCanceledException()
            is ConvertResult.Failure -> throw com.mingdai.toTool.media.ConvertException(result.message)
        }
        if (!output.exists() || output.length() == 0L) {
            throw com.mingdai.toTool.media.ConvertException("处理完成后没有生成文件")
        }

        state.stage = "正在保存到下载目录…"
        state.progress = 0.9f
        val published = publishToDownloads(context, output, mime, state.control.flag) { p ->
            state.progress = 0.9f + (p ?: 0f) * 0.1f
        }
        val path = published.path
        if (path != null) {
            state.progress = 1f
            return "已保存到：$path"
        }
        keepOutput = true
        state.notice = "未能保存到下载目录" +
            (published.error?.let { "（$it）" } ?: "") +
            "，文件保留在应用目录。"
        return output.absolutePath
    } finally {
        files.forEach { it.delete() }
        if (keepOutput) {
            jobDir.listFiles()?.filter { it != output }?.forEach { it.deleteRecursively() }
        } else {
            jobDir.deleteRecursively()
        }
    }
}

internal fun formatSecondsForFfmpeg(seconds: Double): String =
    String.format(Locale.US, "%.3f", seconds.coerceAtLeast(0.0))
