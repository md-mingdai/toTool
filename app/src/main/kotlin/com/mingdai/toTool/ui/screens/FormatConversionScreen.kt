package com.mingdai.toTool.ui.screens

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.R
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SelectChip
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import android.graphics.PorterDuff
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import com.mingdai.toTool.ui.screens.tools.MemoryBudget
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

private enum class FileKind { VIDEO, AUDIO, IMAGE, UNKNOWN }

private val DURATION_PATTERN = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+)\\.(\\d+)")
private val TIME_PATTERN = Pattern.compile("time=\\s*(\\d+):(\\d+):(\\d+)\\.(\\d+)")

/* ----------------------------- 目标格式 → 编码方案 ----------------------------- */

/**
 * 一个目标格式需要的编码器（按优先级排列，取 ffmpeg 里第一个可用的）。
 * 内置的 ffmpeg 是从源码编译的纯 LGPL 构建（见项目里的 ffmpeg/ 目录）：带 libmp3lame（MP3）、
 * libvorbis / libopus（OGG），但没有 GPL 的 libx264，也没有 libvpx，所以视频只能用自带的 mpeg4，
 * WebM 暂时不可用。libx264 / libvpx 这些名字留在列表里，只是为了自己编了带它们的版本时能被自动用上。
 * 这里只在“真的有对应编码器”时才提供该格式。
 */
private class FormatSpec(
    val audioOnly: Boolean,
    val videoEncoders: List<String> = emptyList(),
    val audioEncoders: List<String> = emptyList(),
    val mime: String,
)

private val FORMAT_SPECS: Map<String, FormatSpec> = mapOf(
    "MP3" to FormatSpec(true, audioEncoders = listOf("libmp3lame"), mime = "audio/mpeg"),
    "WAV" to FormatSpec(true, audioEncoders = listOf("pcm_s16le"), mime = "audio/x-wav"),
    "AAC" to FormatSpec(true, audioEncoders = listOf("aac"), mime = "audio/aac"),
    "M4A" to FormatSpec(true, audioEncoders = listOf("aac"), mime = "audio/mp4"),
    "FLAC" to FormatSpec(true, audioEncoders = listOf("flac"), mime = "audio/flac"),
    "OGG" to FormatSpec(true, audioEncoders = listOf("libvorbis", "libopus", "vorbis"), mime = "audio/ogg"),
    "MP4" to FormatSpec(false, listOf("libx264", "mpeg4"), listOf("aac"), "video/mp4"),
    "MOV" to FormatSpec(false, listOf("libx264", "mpeg4"), listOf("aac"), "video/quicktime"),
    "MKV" to FormatSpec(false, listOf("libx264", "mpeg4"), listOf("aac"), "video/x-matroska"),
    "AVI" to FormatSpec(false, listOf("mpeg4"), listOf("libmp3lame", "ac3", "aac"), "video/x-msvideo"),
    "WEBM" to FormatSpec(false, listOf("libvpx-vp9", "libvpx"), listOf("libopus", "libvorbis"), "video/webm"),
)

/** 图片格式交给系统自带的编解码器处理（不依赖 ffmpeg，所有设备都能用）。 */
private val IMAGE_FORMATS = listOf("JPG", "PNG", "WEBP", "BMP")

/** 这些容器之间互转时，手机拍的 H.264/AAC 视频可以直接“换壳”，不必重新编码。 */
private val REMUX_FRIENDLY = setOf("mp4", "m4v", "mov", "mkv")

private fun encoderArgs(name: String): List<String> = when (name) {
    "libmp3lame" -> listOf("-q:a", "2")
    "aac", "ac3" -> listOf("-b:a", "192k")
    "vorbis" -> listOf("-strict", "-2", "-q:a", "5")
    "libvorbis" -> listOf("-q:a", "5")
    "libopus" -> listOf("-b:a", "128k")
    "mpeg4" -> listOf("-q:v", "3", "-pix_fmt", "yuv420p")
    "libx264" -> listOf("-crf", "23", "-preset", "veryfast", "-pix_fmt", "yuv420p")
    else -> emptyList()
}

private fun firstAvailable(candidates: List<String>, available: Set<String>?): String? =
    candidates.firstOrNull { available == null || it in available }

private fun isFormatSupported(fmt: String, available: Set<String>?): Boolean {
    val spec = FORMAT_SPECS[fmt] ?: return false
    return if (spec.audioOnly) firstAvailable(spec.audioEncoders, available) != null
    else firstAvailable(spec.videoEncoders, available) != null
}

/** 依次尝试的 ffmpeg 参数方案：先“换壳”(快且无损)，不行再转码。 */
private fun buildAttempts(fmt: String, sourceExt: String, available: Set<String>?): List<List<String>> {
    val spec = FORMAT_SPECS[fmt] ?: return emptyList()
    val attempts = mutableListOf<List<String>>()
    if (!spec.audioOnly && sourceExt in REMUX_FRIENDLY && fmt.lowercase(Locale.ROOT) in REMUX_FRIENDLY) {
        attempts += listOf("-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy")
    }
    val audio = firstAvailable(spec.audioEncoders, available)
    if (spec.audioOnly) {
        if (audio != null) attempts += listOf("-vn", "-map", "0:a:0", "-c:a", audio) + encoderArgs(audio)
    } else {
        val video = firstAvailable(spec.videoEncoders, available)
        if (video != null) {
            val args = mutableListOf("-map", "0:v:0?", "-map", "0:a:0?", "-c:v", video)
            args += encoderArgs(video)
            if (audio != null) { args += listOf("-c:a", audio); args += encoderArgs(audio) }
            attempts += args
        }
    }
    return attempts
}

/* ----------------------------------- 界面 ----------------------------------- */

/**
 * 格式转换页。顶部可以在两个模式之间切换：
 * - 格式转换：选文件、选目标格式，一键转换；
 * - FFmpeg 命令：直接执行原始 ffmpeg 命令（见 FfmpegCommandPane.kt）。
 */
@Composable
fun FormatConversionScreen(modifier: Modifier = Modifier) {
    var mode by rememberSaveable { mutableStateOf(0) }
    // 任意一个模式正在忙（转换 / 读取文件 / 运行命令）时锁住切换：
    // 切走会销毁那个模式的页面，也就等于把正在跑的任务终止了。
    var busy by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        TabRowWithContour(
            tabs = listOf("格式转换", "FFmpeg 命令"),
            selectedTabIndex = mode,
            onTabSelected = { if (!busy) mode = it },
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
        )
        val paneModifier = Modifier.weight(1f).fillMaxWidth()
        if (mode == 0) {
            QuickConvertPane(modifier = paneModifier, onBusyChange = { busy = it })
        } else {
            FfmpegCommandPane(modifier = paneModifier, onBusyChange = { busy = it })
        }
    }
}

@Composable
private fun QuickConvertPane(modifier: Modifier, onBusyChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var inputName by remember { mutableStateOf<String?>(null) }
    var inputSize by remember { mutableStateOf(-1L) }
    var fileType by remember { mutableStateOf(FileKind.UNKNOWN) }
    var targetFormat by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var noticeMsg by remember { mutableStateOf<String?>(null) }
    var stageText by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(0f) }
    var encoders by remember { mutableStateOf<Set<String>?>(null) }
    val runningProcess = remember { arrayOfNulls<Process>(1) }
    val canceled = remember { AtomicBoolean(false) }

    // 把“是否正在转换”告诉外层：转换期间锁住模式切换
    LaunchedEffect(isRunning) { onBusyChange(isRunning) }
    DisposableEffect(Unit) { onDispose { onBusyChange(false) } }

    // 转换期间保持屏幕常亮：大文件要转很久，息屏后系统可能把进程挂起或杀掉
    val view = LocalView.current
    DisposableEffect(isRunning) {
        view.keepScreenOn = isRunning
        onDispose { view.keepScreenOn = false }
    }

    // 离开页面（例如切换底部标签）时终止仍在运行的 ffmpeg
    DisposableEffect(Unit) {
        onDispose {
            canceled.set(true)
            runningProcess[0]?.destroyForcibly()
        }
    }

    // 探测内置 ffmpeg 实际支持哪些编码器，只展示能转的格式
    LaunchedEffect(Unit) {
        encoders = withContext(Dispatchers.IO) {
            ffmpegBinary(context)?.let { probeEncoders(it) }
        }
    }

    val videoFormats = listOf("MP4", "MKV", "MOV", "AVI", "WEBM")
    val audioFormats = listOf("MP3", "WAV", "AAC", "M4A", "FLAC", "OGG")
    val genericFormats = listOf("MP4", "MKV", "MP3", "WAV", "M4A", "AAC")

    val formatOptions: List<String> = when (fileType) {
        FileKind.IMAGE -> IMAGE_FORMATS
        FileKind.VIDEO -> videoFormats.filter { isFormatSupported(it, encoders) }
        FileKind.AUDIO -> audioFormats.filter { isFormatSupported(it, encoders) }
        FileKind.UNKNOWN -> genericFormats.filter { isFormatSupported(it, encoders) }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            inputUri = uri
            inputName = queryFileName(context, uri)
            inputSize = queryFileSize(context, uri)
            fileType = detectFileKind(inputName, context.contentResolver.getType(uri))
            targetFormat = null
            resultMsg = null
            errorMsg = null
            noticeMsg = null
            progress = 0f
        }
    }

    val sourceExt = (inputName ?: "").substringAfterLast('.', "").lowercase(Locale.ROOT)
        .let { if (it == "jpeg") "jpg" else it } // jpeg 和 jpg 是同一种格式

    fun cancelConversion() {
        canceled.set(true)
        runningProcess[0]?.destroyForcibly()
    }

    fun startConversion() {
        val uri = inputUri ?: return
        val fmt = targetFormat ?: return
        if (isRunning) return

        val kind = fileType
        val srcSize = inputSize
        val baseName = (inputName ?: "output").substringBeforeLast('.').ifBlank { "output" }

        canceled.set(false)
        isRunning = true
        resultMsg = null
        errorMsg = null
        noticeMsg = null
        stageText = ""
        progress = 0f
        if (kind != FileKind.IMAGE && srcSize >= LARGE_FILE_HINT_BYTES) {
            noticeMsg = "文件较大（${humanSize(srcSize)}），转换可能需要较长时间。请保持屏幕常亮、留在本页面，不要切换到其他应用。"
        }

        scope.launch {
            var tempInput: File? = null
            var outFile: File? = null
            var keepOutput = false
            try {
                if (kind == FileKind.IMAGE) {
                    // ---- 图片：系统编解码器，按内存预算缩小、边编码边写入，不在内存里堆整张图的字节 ----
                    stageText = "正在转换图片…"
                    progress = null
                    val r = withContext(Dispatchers.IO) { convertImage(context, uri, fmt, baseName, canceled) }
                    resultMsg = context.getString(R.string.format_saved_to, r.where)
                    r.notice?.let { noticeMsg = it }
                } else {
                    // ---- 视频 / 音频：ffmpeg 子进程，全程文件流，不进 Java 堆 ----
                    val ffmpeg = ffmpegBinary(context)
                        ?: throw ConvertException(ffmpegUnavailableMessage(context))
                    val spec = FORMAT_SPECS[fmt]
                    val attempts = buildAttempts(fmt, sourceExt, encoders)
                    if (attempts.isEmpty()) throw ConvertException("没有可用的 $fmt 编码器")

                    val outDir = context.getExternalFilesDir("conversions") ?: context.filesDir
                    val out = File(outDir, "$baseName.${fmt.lowercase(Locale.ROOT)}")
                    outFile = out
                    var publishNote: String? = null
                    var publishedPath: String? = null

                    withContext(Dispatchers.IO) {
                        cleanupStaleFiles(context)

                        // ① 空间预检 + 复制到临时文件（分块复制，带进度，可取消）
                        stageText = "正在读取文件…"
                        progress = 0f
                        val outEstimate = when {
                            srcSize <= 0L -> 0L
                            spec?.audioOnly == true -> if (kind == FileKind.AUDIO) 0L else srcSize / 6
                            else -> srcSize
                        }
                        val need = if (srcSize > 0L) srcSize + outEstimate + MIN_FREE_HEADROOM_BYTES else 0L
                        val workDir = pickWorkDir(context, need)
                        val input = copyUriToTemp(context, uri, workDir, srcSize, canceled) { p -> progress = p }
                        tempInput = input

                        // ② 转换
                        stageText = "正在转换…"
                        progress = 0f
                        var done = false
                        var lastFailure: ConvertResult.Failure? = null
                        for (attempt in attempts) {
                            if (canceled.get()) throw ConvertCanceledException()
                            out.delete()
                            val cmd = listOf(ffmpeg.absolutePath, "-hide_banner", "-nostdin", "-i", input.absolutePath) +
                                attempt +
                                // 大文件常见报错 “Too many packets buffered for output stream”，调大复用队列
                                listOf("-max_muxing_queue_size", "4096", "-y", out.absolutePath)
                            when (val r = runFfmpeg(cmd, runningProcess, canceled) { p -> progress = p }) {
                                is ConvertResult.Success -> done = true
                                is ConvertResult.Canceled -> throw ConvertCanceledException()
                                is ConvertResult.Failure -> lastFailure = r
                            }
                            if (done) break
                        }
                        if (!done) throw ConvertException(lastFailure?.message ?: "转换失败")

                        // 输入副本不再需要：先删掉，给下一步“复制到下载目录”腾出磁盘空间
                        input.delete()
                        tempInput = null

                        // ③ 复制到“下载/toTool”
                        stageText = "正在保存到下载目录…"
                        progress = 0f
                        val pub = publishToDownloads(
                            context, out, spec?.mime ?: "application/octet-stream", canceled,
                        ) { p -> progress = p }
                        publishedPath = pub.path
                        publishNote = pub.error
                    }
                    progress = 1f
                    val shown = publishedPath
                    if (shown != null) {
                        resultMsg = context.getString(R.string.format_saved_to, shown)
                    } else {
                        keepOutput = true // 没能复制到下载目录：保留应用目录里的成品，别删
                        resultMsg = out.absolutePath
                        noticeMsg = "未能保存到下载目录" + (publishNote?.let { "（$it）" } ?: "") +
                            "，文件保留在应用目录。"
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ConvertCanceledException) {
                noticeMsg = "已取消转换"
            } catch (e: OutOfMemoryError) {
                errorMsg = "内存不足：文件太大，设备无法处理。可以先关闭其他应用，或换一个更小的文件。"
            } catch (e: Exception) {
                errorMsg = friendlyError(e)
            } finally {
                tempInput?.delete()
                if (!keepOutput) outFile?.delete()
                stageText = ""
                isRunning = false
            }
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SecondaryButton(
            onClick = { picker.launch("*/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRunning,
        ) {
            Icon(Icons.Filled.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.format_pick_file))
        }

        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(16.dp),
        ) {
            if (inputUri == null) {
                Text(
                    stringResource(R.string.format_no_file),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (fileType) {
                            FileKind.VIDEO -> Icons.Filled.VideoLibrary
                            FileKind.AUDIO -> Icons.Filled.AudioFile
                            FileKind.IMAGE -> Icons.Filled.Image
                            else -> Icons.AutoMirrored.Filled.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = inputName ?: "未知文件",
                            style = MiuixTheme.textStyles.body1,
                        )
                        val typeLabel = when (fileType) {
                            FileKind.VIDEO -> stringResource(R.string.format_type_video)
                            FileKind.AUDIO -> stringResource(R.string.format_type_audio)
                            FileKind.IMAGE -> "图片"
                            else -> stringResource(R.string.format_type_other)
                        }
                        Text(
                            text = if (inputSize > 0L) "$typeLabel · ${humanSize(inputSize)}" else typeLabel,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        if (inputUri != null && !isRunning) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.format_select_format),
                style = MiuixTheme.textStyles.body2,
            )
            Spacer(Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                formatOptions.forEach { fmt ->
                    val sameAsSource = fmt.lowercase(Locale.ROOT) == sourceExt
                    SelectChip(
                        selected = targetFormat == fmt,
                        onClick = { targetFormat = fmt },
                        enabled = !sameAsSource,
                        label = fmt,
                    )
                }
            }
        }

        if (isRunning) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            val pct = progress?.let { " ${(it * 100).toInt().coerceIn(0, 100)}%" } ?: ""
            Text(
                text = (stageText.ifEmpty { stringResource(R.string.format_converting) }) + pct,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                onClick = { cancelConversion() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("取消")
            }
        }

        noticeMsg?.let { msg ->
            Spacer(Modifier.height(12.dp))
            MessageCard(
                title = null,
                message = msg,
                container = MiuixTheme.colorScheme.secondaryContainer,
                content = MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }

        resultMsg?.let { msg ->
            Spacer(Modifier.height(12.dp))
            MessageCard(
                title = stringResource(R.string.format_done),
                message = msg,
                container = MiuixTheme.colorScheme.primaryContainer,
                content = MiuixTheme.colorScheme.onPrimaryContainer,
            )
        }

        errorMsg?.let { msg ->
            Spacer(Modifier.height(12.dp))
            MessageCard(
                title = stringResource(R.string.format_error),
                message = msg,
                container = MiuixTheme.colorScheme.errorContainer,
                content = MiuixTheme.colorScheme.onErrorContainer,
            )
        }

        Spacer(Modifier.height(16.dp))

        PrimaryButton(
            onClick = ::startConversion,
            enabled = inputUri != null && targetFormat != null && !isRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isRunning) {
                CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.format_converting))
            } else {
                Icon(Icons.Filled.SwapHoriz, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.format_start))
            }
        }
    }
}

@Composable
internal fun MessageCard(title: String?, message: String, container: Color, content: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(12.dp),
        colors = CardDefaults.defaultColors(color = container, contentColor = content),
    ) {
        if (title != null) {
            Text(title, style = MiuixTheme.textStyles.subtitle, color = content)
            Spacer(Modifier.height(2.dp))
        }
        Text(message, style = MiuixTheme.textStyles.footnote1, color = content)
    }
}

/* ------------------------------ 大文件处理：常量与小工具 ------------------------------ */

/** 超过这个大小就提示“可能要很久”。 */
private const val LARGE_FILE_HINT_BYTES = 1_500_000_000L

/** 复制 / 转换时额外保留的磁盘余量。 */
internal const val MIN_FREE_HEADROOM_BYTES = 128L * 1024 * 1024

/** 流式复制用的缓冲区：1 MB，内存占用固定，与文件大小无关。 */
private const val COPY_BUFFER_BYTES = 1 shl 20

/** 面向用户的错误（消息可以直接展示）。 */
private class ConvertException(message: String) : Exception(message)

/** 用户点了“取消”。 */
internal class ConvertCanceledException : Exception("canceled")

internal fun humanSize(bytes: Long): String = when {
    bytes < 0 -> "未知"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

internal fun friendlyError(e: Throwable): String {
    val m = e.message ?: e.javaClass.simpleName
    return when {
        m.contains("ENOSPC", ignoreCase = true) || m.contains("No space left", ignoreCase = true) ->
            "存储空间不足，请清理后重试"
        else -> m
    }
}

/** 选一个剩余空间最大的目录放临时文件，并确认放得下。 */
internal fun pickWorkDir(context: Context, needBytes: Long): File {
    val candidates = listOfNotNull(context.externalCacheDir, context.cacheDir)
    val best = candidates.maxByOrNull { it.usableSpace } ?: context.cacheDir
    if (needBytes > 0L && best.usableSpace < needBytes) {
        throw ConvertException(
            "存储空间不足：这次转换大约需要 ${humanSize(needBytes)}，当前只剩 ${humanSize(best.usableSpace)}。" +
                "请清理一些空间后重试。"
        )
    }
    return best
}

/** 清掉上次异常退出（崩溃 / 被杀）时遗留的临时文件和半成品。 */
private fun cleanupStaleFiles(context: Context) {
    listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
        dir.listFiles { f -> f.name.startsWith("conv_in_") }?.forEach { it.delete() }
    }
    val cutoff = System.currentTimeMillis() - 6L * 60 * 60 * 1000
    context.getExternalFilesDir("conversions")?.listFiles()?.forEach { f ->
        if (f.lastModified() < cutoff) f.delete()
    }
}

/* --------------------------------- ffmpeg 调用 --------------------------------- */

private sealed interface ConvertResult {
    data object Success : ConvertResult
    data object Canceled : ConvertResult
    data class Failure(val message: String) : ConvertResult
}

/** 安装包里带了内置 ffmpeg 的 CPU 架构，和 ffmpeg/build.sh 里的 ALL_ABIS 保持一致。 */
internal val FFMPEG_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

/**
 * 内置 ffmpeg 是从源码编译的（见项目里的 ffmpeg/ 目录），每个架构一份，
 * 以 libffmpeg.so 的名字放在 jniLibs/<架构>/ 下。安装时系统按本机架构挑一份解压到 nativeLibraryDir。
 * Android 10+ 禁止执行应用私有目录（filesDir）里的文件，只有 nativeLibraryDir 里的可执行。
 */
internal fun ffmpegBinary(context: Context): File? {
    val f = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
    return if (f.exists() && f.canExecute()) f else null
}

/** [ffmpegBinary] 返回 null 时，给用户看的原因：本机架构不在支持范围内，还是这个安装包里缺了对应架构的那一份。 */
internal fun ffmpegUnavailableMessage(context: Context): String {
    val abis = Build.SUPPORTED_ABIS.joinToString(", ").ifEmpty { "未知" }
    val supported = Build.SUPPORTED_ABIS.any { it in FFMPEG_ABIS }
    return context.getString(
        if (supported) R.string.format_extract_fail else R.string.format_unsupported_abi,
        abis,
    )
}

private fun probeEncoders(ffmpeg: File): Set<String>? = try {
    val p = ProcessBuilder(ffmpeg.absolutePath, "-hide_banner", "-encoders").redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    val names = Regex("""^\s*[VAS][A-Z.]{5}\s+(\S+)""", RegexOption.MULTILINE).findAll(out).map { it.groupValues[1] }.toSet()
    names.ifEmpty { null }
} catch (e: Exception) {
    null
}

private fun parseSeconds(m: java.util.regex.Matcher): Double {
    val h = m.group(1)?.toIntOrNull() ?: 0
    val min = m.group(2)?.toIntOrNull() ?: 0
    val s = m.group(3)?.toIntOrNull() ?: 0
    val frac = m.group(4)?.let { ("0.$it").toDoubleOrNull() } ?: 0.0
    return h * 3600.0 + min * 60.0 + s + frac
}

/**
 * 按行读取，但每行最多保留 [maxLen] 个字符，多出来的丢弃。
 * ffmpeg 会把文件里的元数据（歌词、封面描述等）原样打印出来，个别文件一行就有几 MB，
 * 直接 readLine() 会把整行读进内存。
 */
private fun forEachBoundedLine(reader: java.io.Reader, maxLen: Int, onLine: (String) -> Unit) {
    val buf = CharArray(8192)
    val sb = StringBuilder()
    while (true) {
        val n = reader.read(buf)
        if (n < 0) break
        for (i in 0 until n) {
            val c = buf[i]
            if (c == '\n' || c == '\r') { // ffmpeg 的进度行用 \r 结尾
                if (sb.isNotEmpty()) {
                    onLine(sb.toString())
                    sb.setLength(0)
                }
            } else if (sb.length < maxLen) {
                sb.append(c)
            }
        }
    }
    if (sb.isNotEmpty()) onLine(sb.toString())
}

internal fun describeFailure(exit: Int, tail: List<String>): String {
    val text = tail.joinToString("\n")
    return when {
        text.contains("No space left", ignoreCase = true) -> "存储空间不足，转换中断。请清理空间后重试。"
        text.contains("Cannot allocate memory", ignoreCase = true) ||
            text.contains("Out of memory", ignoreCase = true) -> "设备内存不足，无法处理这个文件。"
        text.contains("moov atom not found", ignoreCase = true) ||
            text.contains("Invalid data found", ignoreCase = true) -> "文件已损坏或不完整，无法读取。"
        // 被系统直接杀掉（SIGKILL）：几乎都是内存不足
        exit == 137 || exit == -9 -> "转换进程被系统终止（通常是内存不足）。"
        else -> "退出代码：$exit\n$text"
    }
}

private fun runFfmpeg(
    cmd: List<String>,
    holder: Array<Process?>,
    canceled: AtomicBoolean,
    onProgress: (Float?) -> Unit,
): ConvertResult {
    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
    holder[0] = process
    if (canceled.get()) process.destroyForcibly()
    val tail = ArrayDeque<String>()
    var duration = 0.0
    try {
        process.inputStream.bufferedReader().use { reader ->
            forEachBoundedLine(reader, 4096) { line ->
                val dm = DURATION_PATTERN.matcher(line)
                if (duration <= 0.0 && dm.find()) duration = parseSeconds(dm)
                val tm = TIME_PATTERN.matcher(line)
                if (tm.find()) {
                    onProgress(if (duration > 0.0) (parseSeconds(tm) / duration).toFloat().coerceIn(0f, 0.99f) else null)
                } else if (line.isNotBlank()) {
                    tail.addLast(line.trim())
                    if (tail.size > 6) tail.removeFirst()
                }
            }
        }
        val exit = process.waitFor()
        if (canceled.get()) return ConvertResult.Canceled
        return if (exit == 0) ConvertResult.Success else ConvertResult.Failure(describeFailure(exit, tail))
    } finally {
        process.destroyForcibly()
        holder[0] = null
    }
}

internal class PublishResult(val path: String?, val error: String?)

/**
 * 把成品复制到系统“下载/toTool”目录（Android 10+），用户才能在文件管理器里找到。
 * 应用私有目录 Android/data 在 Android 11+ 基本无法访问。
 * 分块流式复制，带进度，可取消；取消 / 失败时会把写到一半的条目删掉。
 */
internal fun publishToDownloads(
    context: Context,
    file: File,
    mime: String,
    canceled: AtomicBoolean,
    onProgress: (Float?) -> Unit,
): PublishResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return PublishResult(null, "Android 9 及以下不支持")
    }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/toTool")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = try {
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    } catch (e: Exception) {
        return PublishResult(null, friendlyError(e))
    } ?: return PublishResult(null, "系统拒绝创建文件")

    try {
        val total = file.length()
        val buf = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        var lastReport = 0L
        val out = resolver.openOutputStream(uri) ?: throw IOException("无法写入下载目录")
        out.use { sink ->
            file.inputStream().use { input ->
                while (true) {
                    if (canceled.get()) throw ConvertCanceledException()
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    copied += n
                    if (total > 0 && copied - lastReport >= 4L * 1024 * 1024) {
                        lastReport = copied
                        onProgress((copied.toDouble() / total).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        file.delete()
        return PublishResult(Environment.DIRECTORY_DOWNLOADS + "/toTool/" + file.name, null)
    } catch (e: ConvertCanceledException) {
        runCatching { resolver.delete(uri, null, null) }
        throw e
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        return PublishResult(null, friendlyError(e))
    }
}

/**
 * 把选中的文件复制成应用目录里的临时文件（ffmpeg 需要能随机读取的真实路径，管道对 MP4 不可靠）。
 * 固定 1 MB 缓冲分块复制；按字节汇报进度；可取消；失败 / 取消时删除半截文件。
 */
internal fun copyUriToTemp(
    context: Context,
    uri: Uri,
    dir: File,
    totalBytes: Long,
    canceled: AtomicBoolean,
    prefix: String = "conv_in_",
    onProgress: (Float?) -> Unit,
): File {
    val name = queryFileName(context, uri)
    val ext = name.substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
    // 用 nanoTime 而不是毫秒：连续读取几个小文件时，毫秒时间戳可能相同，临时文件名会撞车
    val tempFile = File(dir, "$prefix${System.nanoTime()}.$ext")
    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ConvertException(context.getString(R.string.format_copy_fail))
        input.use { src ->
            tempFile.outputStream().use { dst ->
                val buf = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                var lastReport = 0L
                onProgress(if (totalBytes > 0) 0f else null)
                while (true) {
                    if (canceled.get()) throw ConvertCanceledException()
                    val n = src.read(buf)
                    if (n < 0) break
                    dst.write(buf, 0, n)
                    copied += n
                    if (copied - lastReport >= 4L * 1024 * 1024) {
                        lastReport = copied
                        onProgress(if (totalBytes > 0) (copied.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else null)
                    }
                }
            }
        }
        return tempFile
    } catch (e: Exception) {
        tempFile.delete()
        throw e
    }
}

private fun detectFileKind(name: String?, mime: String? = null): FileKind {
    val ext = (name ?: "").substringAfterLast('.', "").lowercase(Locale.ROOT)
    val byExt = when (ext) {
        "mp4", "mov", "avi", "mkv", "webm", "flv", "wmv", "mpg", "mpeg", "ts", "m4v", "3gp" -> FileKind.VIDEO
        "mp3", "wav", "aac", "m4a", "flac", "ogg", "wma", "opus", "aiff" -> FileKind.AUDIO
        // 原来没有图片分类，jpg/png 等都被归到“其他”
        "jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp", "gif", "heic", "heif", "avif" -> FileKind.IMAGE
        else -> FileKind.UNKNOWN
    }
    if (byExt != FileKind.UNKNOWN) return byExt
    // 文件名没有扩展名（或扩展名不常见）时，退回用系统给出的 MIME 类型判断
    val m = (mime ?: "").lowercase(Locale.ROOT)
    return when {
        m.startsWith("image/") -> FileKind.IMAGE
        m.startsWith("video/") -> FileKind.VIDEO
        m.startsWith("audio/") -> FileKind.AUDIO
        else -> FileKind.UNKNOWN
    }
}

/* --------------------------------- 图片转换 --------------------------------- */

private fun imageMime(fmt: String): String = when (fmt) {
    "JPG" -> "image/jpeg"
    "PNG" -> "image/png"
    "WEBP" -> "image/webp"
    "BMP" -> "image/bmp"
    else -> "application/octet-stream"
}

/** 各目标格式在“解码 + 编码”过程中每个像素的峰值内存（字节），用来按预算决定要不要缩小。 */
private fun peakBytesPerPixel(fmt: String): Double = when (fmt) {
    "WEBP" -> 10.0 // libwebp 编码时还要额外一份 YUV + ARGB 拷贝
    "PNG" -> 5.5
    "JPG" -> 5.0
    "BMP" -> 4.5   // 逐行写出，几乎只有位图本身
    else -> 6.0
}

private class DecodedImage(val bitmap: Bitmap, val srcW: Int, val srcH: Int, val scaled: Boolean)

/**
 * 解码整张图（ImageDecoder 会自动应用 EXIF 旋转）。
 * 解码前就根据内存预算算出能容纳的最大像素数，超出的在解码阶段直接缩小（不会先解出全尺寸再缩）；
 * 内存仍然不够（OutOfMemoryError）时，再把预算减半重试，最多三次。
 */
private fun decodeImage(context: Context, uri: Uri, fmt: String, canceled: AtomicBoolean): DecodedImage {
    var maxPixels = MemoryBudget.maxPixels(context, peakBytesPerPixel(fmt))
    var attempt = 0
    while (true) {
        if (canceled.get()) throw ConvertCanceledException()
        var srcW = 0
        var srcH = 0
        var scaled = false
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // JPG / BMP 没有透明通道，后面要在原位铺白底，所以要求可变位图（大小不变，不额外占内存）
                if (fmt == "JPG" || fmt == "BMP") decoder.setMutableRequired(true)
                srcW = info.size.width
                srcH = info.size.height
                val px = srcW.toLong() * srcH
                if (px > maxPixels) {
                    val f = sqrt(maxPixels.toDouble() / px)
                    decoder.setTargetSize(max(1, (srcW * f).toInt()), max(1, (srcH * f).toInt()))
                    scaled = true
                }
            }
            return DecodedImage(bmp, srcW, srcH, scaled)
        } catch (e: OutOfMemoryError) {
            attempt++
            if (attempt >= 3) throw e
            maxPixels /= 2
        } catch (e: IOException) {
            throw ConvertException("无法解码这张图片（格式不受支持或文件已损坏）")
        }
    }
}

/** JPEG / BMP 不支持透明：直接在原位图上把透明区域铺到白底上（DST_OVER = 画在已有内容“后面”），不额外分配位图。 */
private fun flattenOnWhiteInPlace(bitmap: Bitmap) {
    if (!bitmap.hasAlpha()) return
    val target = if (bitmap.isMutable) bitmap else return
    Canvas(target).drawColor(android.graphics.Color.WHITE, PorterDuff.Mode.DST_OVER)
}

/** 24 位无压缩 BMP，逐行写出：内存里始终只有一行（Android 的 Bitmap.compress 不支持 BMP）。 */
private fun writeBmp(bitmap: Bitmap, out: OutputStream, canceled: AtomicBoolean) {
    val w = bitmap.width
    val h = bitmap.height
    val rowSize = (w * 3 + 3) / 4 * 4
    val dataSize = rowSize.toLong() * h
    val fileSize = 54L + dataSize
    if (fileSize > Int.MAX_VALUE) {
        throw ConvertException("这张图转成 BMP 会超过 2 GB（BMP 格式的上限），请改选 PNG 或 JPG。")
    }
    val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
    header.put('B'.code.toByte()).put('M'.code.toByte())
    header.putInt(fileSize.toInt()).putInt(0).putInt(54)
    header.putInt(40).putInt(w).putInt(h).putShort(1).putShort(24)
    header.putInt(0).putInt(dataSize.toInt()).putInt(2835).putInt(2835).putInt(0).putInt(0)
    out.write(header.array())

    val pixels = IntArray(w)
    val row = ByteArray(rowSize) // 末尾的补齐字节始终为 0
    for (y in h - 1 downTo 0) { // BMP 从最后一行开始存
        if (canceled.get()) throw ConvertCanceledException()
        bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
        var p = 0
        for (x in 0 until w) {
            val c = pixels[x]
            row[p++] = c.toByte()          // B
            row[p++] = (c shr 8).toByte()  // G
            row[p++] = (c shr 16).toByte() // R
        }
        out.write(row)
    }
}

private fun writeImage(bitmap: Bitmap, fmt: String, out: OutputStream, canceled: AtomicBoolean) {
    when (fmt) {
        "BMP" -> writeBmp(bitmap, out, canceled)
        "JPG" -> if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw IOException("编码 JPG 失败")
        "PNG" -> if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("编码 PNG 失败")
        "WEBP" -> {
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
                         else Bitmap.CompressFormat.WEBP
            if (!bitmap.compress(format, 95, out)) throw IOException("编码 WEBP 失败")
        }
        else -> throw IOException("不支持的格式：$fmt")
    }
}

/** 统计写出的字节数（FilterOutputStream 默认逐字节转发，这里改成整块转发）。 */
private class CountingOutputStream(sink: OutputStream) : FilterOutputStream(sink) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }
}

/**
 * 边编码边写进相册（MediaStore）：压缩后的字节直接流向目标文件，
 * 不再先在内存里攒一整个 ByteArray（大图的 PNG / BMP 动辄上百 MB）。
 * 返回 (显示路径, 写出的字节数)。
 */
private fun saveImageToGallery(
    context: Context,
    displayName: String,
    mime: String,
    writer: (OutputStream) -> Unit,
): Pair<String, Long> {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/toTool")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw ConvertException("无法写入相册（系统拒绝创建文件）")
    try {
        val raw = resolver.openOutputStream(uri) ?: throw IOException("无法写入相册")
        val counter = CountingOutputStream(BufferedOutputStream(raw, 1 shl 16))
        counter.use { writer(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return Pair("Pictures/toTool/$displayName", counter.count)
    } catch (e: Throwable) {
        runCatching { resolver.delete(uri, null, null) } // 失败 / 取消：不留半截文件
        throw e
    }
}

private class ImageResult(val where: String, val notice: String?)

/**
 * 转换一张图片并保存到相册（Pictures/toTool）。
 * 大图处理：按内存预算在解码阶段缩小 → JPG/BMP 在原位图上铺白底 → 边编码边写出 → 及时回收位图。
 */
private fun convertImage(
    context: Context,
    uri: Uri,
    fmt: String,
    baseName: String,
    canceled: AtomicBoolean,
): ImageResult {
    val decoded = decodeImage(context, uri, fmt, canceled)
    val bitmap = decoded.bitmap
    try {
        if (canceled.get()) throw ConvertCanceledException()
        if (fmt == "JPG" || fmt == "BMP") flattenOnWhiteInPlace(bitmap)

        val displayName = "$baseName.${fmt.lowercase(Locale.ROOT)}"
        val (where, bytes) = saveImageToGallery(context, displayName, imageMime(fmt)) { out ->
            writeImage(bitmap, fmt, out, canceled)
        }
        val notice = if (decoded.scaled) {
            "原图 ${decoded.srcW}×${decoded.srcH}（约 ${decoded.srcW.toLong() * decoded.srcH / 10_000} 万像素）" +
                "超出本机当前可用的内存，已缩小到 ${bitmap.width}×${bitmap.height} 后转换，避免闪退。" +
                "想保留原始尺寸：先关闭其他应用再试，或改选 JPG（占用内存最少）。"
        } else null
        return ImageResult("$where（${humanSize(bytes)}）", notice)
    } finally {
        bitmap.recycle()
    }
}

internal fun queryFileName(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else "file"
        } ?: "file"
    } catch (e: Exception) {
        "file"
    }
}

/** 文件大小（字节）；查不到返回 -1。用来做空间预检、显示和进度。 */
internal fun queryFileSize(context: Context, uri: Uri): Long {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) {
                val s = c.getLong(0)
                if (s >= 0) return s
            }
        }
    } catch (e: Exception) {
        // 忽略，走下面的兜底
    }
    return try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (e: Exception) {
        -1L
    }
}
