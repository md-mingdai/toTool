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
import com.mingdai.toTool.ui.screens.tools.ImageOps
import java.io.ByteArrayOutputStream
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class FileKind { VIDEO, AUDIO, IMAGE, UNKNOWN }

private val DURATION_PATTERN = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+)\\.(\\d+)")
private val TIME_PATTERN = Pattern.compile("time=\\s*(\\d+):(\\d+):(\\d+)\\.(\\d+)")

/* ----------------------------- 目标格式 → 编码方案 ----------------------------- */

/**
 * 一个目标格式需要的编码器（按优先级排列，取 ffmpeg 里第一个可用的）。
 * 内置的 ffmpeg 是不含 GPL/外部库的精简构建，没有 libmp3lame / libx264 / libvpx，
 * 所以这里只在“真的有对应编码器”时才提供该格式。
 */
private class FormatSpec(
    val audioOnly: Boolean,
    val videoEncoders: List<String> = emptyList(),
    val audioEncoders: List<String> = emptyList(),
    val mime: String,
)

private val FORMAT_SPECS: Map<String, FormatSpec> = mapOf(
    "MP3" to FormatSpec(true, audioEncoders = listOf("libmp3lame", "libshine"), mime = "audio/mpeg"),
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
    "libshine", "aac", "ac3" -> listOf("-b:a", "192k")
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

@Composable
fun FormatConversionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var inputName by remember { mutableStateOf<String?>(null) }
    var fileType by remember { mutableStateOf(FileKind.UNKNOWN) }
    var targetFormat by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(0f) }
    var encoders by remember { mutableStateOf<Set<String>?>(null) }
    val runningProcess = remember { arrayOfNulls<Process>(1) }

    // 离开页面（例如切换底部标签）时终止仍在运行的 ffmpeg
    DisposableEffect(Unit) { onDispose { runningProcess[0]?.destroy() } }

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
            fileType = detectFileKind(inputName, context.contentResolver.getType(uri))
            targetFormat = null
            resultMsg = null
            errorMsg = null
            progress = 0f
        }
    }

    val sourceExt = (inputName ?: "").substringAfterLast('.', "").lowercase(Locale.ROOT)
        .let { if (it == "jpeg") "jpg" else it } // jpeg 和 jpg 是同一种格式

    fun startConversion() {
        val uri = inputUri ?: return
        val fmt = targetFormat ?: return
        if (isRunning) return

        // 图片：用系统编解码器转换，不需要 ffmpeg
        if (fileType == FileKind.IMAGE) {
            val imageBase = (inputName ?: "image").substringBeforeLast('.')
            isRunning = true
            resultMsg = null
            errorMsg = null
            progress = null // 图片转换很快，用不确定进度条即可
            scope.launch {
                try {
                    val where = withContext(Dispatchers.IO) { convertImage(context, uri, fmt, imageBase) }
                    resultMsg = context.getString(R.string.format_saved_to, where)
                } catch (e: OutOfMemoryError) {
                    errorMsg = "内存不足：这张图片太大了"
                } catch (e: Exception) {
                    errorMsg = e.message ?: e.javaClass.simpleName
                } finally {
                    isRunning = false
                }
            }
            return
        }

        val ffmpeg = ffmpegBinary(context)
        if (ffmpeg == null) {
            errorMsg = context.getString(
                if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) R.string.format_extract_fail
                else R.string.format_unsupported_abi
            )
            return
        }
        val baseName = (inputName ?: "output").substringBeforeLast('.')
        val outDir = context.getExternalFilesDir("conversions") ?: context.filesDir
        val outFile = File(outDir, "$baseName.${fmt.lowercase(Locale.ROOT)}")

        isRunning = true
        resultMsg = null
        errorMsg = null
        progress = 0f

        scope.launch {
            var tempInput: File? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    tempInput = copyUriToTemp(context, uri)
                    val input = tempInput ?: return@withContext ConvertResult.Failure(context.getString(R.string.format_copy_fail))
                    val attempts = buildAttempts(fmt, sourceExt, encoders)
                    if (attempts.isEmpty()) return@withContext ConvertResult.Failure("没有可用的 $fmt 编码器")
                    var last: ConvertResult.Failure? = null
                    for (attempt in attempts) {
                        outFile.delete()
                        val cmd = listOf(ffmpeg.absolutePath, "-hide_banner", "-nostdin", "-i", input.absolutePath) +
                            attempt + listOf("-y", outFile.absolutePath)
                        val r = runFfmpeg(cmd, runningProcess) { p -> progress = p }
                        if (r is ConvertResult.Success) return@withContext r
                        last = r as ConvertResult.Failure
                    }
                    last!!
                }
                when (result) {
                    is ConvertResult.Success -> {
                        progress = 1f
                        val published = withContext(Dispatchers.IO) {
                            publishToDownloads(context, outFile, FORMAT_SPECS[fmt]?.mime ?: "application/octet-stream")
                        }
                        resultMsg = published?.let { context.getString(R.string.format_saved_to, it) }
                            ?: outFile.absolutePath
                    }
                    is ConvertResult.Failure -> errorMsg = result.message
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: e.javaClass.simpleName
            } finally {
                tempInput?.delete()
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
                        Text(
                            text = when (fileType) {
                                FileKind.VIDEO -> stringResource(R.string.format_type_video)
                                FileKind.AUDIO -> stringResource(R.string.format_type_audio)
                                FileKind.IMAGE -> "图片"
                                else -> stringResource(R.string.format_type_other)
                            },
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
            Text(
                text = stringResource(R.string.format_converting),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        resultMsg?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.primaryContainer,
                    contentColor = MiuixTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text(
                    stringResource(R.string.format_done),
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    msg,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        errorMsg?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.errorContainer,
                    contentColor = MiuixTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(
                    stringResource(R.string.format_error),
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    msg,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                )
            }
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

/* --------------------------------- ffmpeg 调用 --------------------------------- */

private sealed interface ConvertResult {
    data object Success : ConvertResult
    data class Failure(val message: String) : ConvertResult
}

/**
 * 内置 ffmpeg 以 libffmpeg.so 的名字放在 jniLibs/arm64-v8a 下，安装时被解压到 nativeLibraryDir。
 * Android 10+ 禁止执行应用私有目录（filesDir）里的文件，只有 nativeLibraryDir 里的可执行。
 */
private fun ffmpegBinary(context: Context): File? {
    val f = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
    return if (f.exists() && f.canExecute()) f else null
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

private fun runFfmpeg(
    cmd: List<String>,
    holder: Array<Process?>,
    onProgress: (Float?) -> Unit,
): ConvertResult {
    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
    holder[0] = process
    val tail = ArrayDeque<String>()
    var duration = 0.0
    try {
        process.inputStream.bufferedReader().forEachLine { line ->
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
        val exit = process.waitFor()
        return if (exit == 0) ConvertResult.Success
        else ConvertResult.Failure("退出代码：$exit\n" + tail.joinToString("\n"))
    } finally {
        process.destroy()
        holder[0] = null
    }
}

/**
 * 把成品复制到系统“下载/toTool”目录（Android 10+），用户才能在文件管理器里找到。
 * 应用私有目录 Android/data 在 Android 11+ 基本无法访问。
 */
private fun publishToDownloads(context: Context, file: File, mime: String): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return try {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/toTool")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
            true
        } ?: false
        if (!ok) { resolver.delete(uri, null, null); return null }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        file.delete()
        Environment.DIRECTORY_DOWNLOADS + "/toTool/" + file.name
    } catch (e: Exception) {
        null
    }
}

private fun copyUriToTemp(context: Context, uri: Uri): File? {
    val name = queryFileName(context, uri)
    val ext = name.substringAfterLast('.', "bin")
    val tempFile = File(context.cacheDir, "conv_in_${System.currentTimeMillis()}.$ext")
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: run { tempFile.delete(); return null }
        tempFile
    } catch (e: Exception) {
        tempFile.delete()
        null
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

/** 解码整张图（ImageDecoder 会自动应用 EXIF 旋转）；内存不够时逐级降采样重试。 */
private fun decodeImage(context: Context, uri: Uri): Bitmap {
    var sample = 1
    while (true) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (sample > 1) decoder.setTargetSampleSize(sample)
            }
        } catch (e: OutOfMemoryError) {
            if (sample >= 8) throw e
            sample *= 2
        } catch (e: java.io.IOException) {
            throw IllegalStateException("无法解码这张图片（格式不受支持或文件已损坏）")
        }
    }
}

/** JPEG / BMP 不支持透明：把透明区域铺在白底上，否则会变成黑色。 */
private fun flattenOnWhite(src: Bitmap): Bitmap {
    if (!src.hasAlpha()) return src
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.drawBitmap(src, 0f, 0f, null)
    return out
}

/** 24 位无压缩 BMP（Android 的 Bitmap.compress 不支持 BMP，这里手写）。 */
private fun encodeBmp(src: Bitmap): ByteArray {
    val bmp = flattenOnWhite(src)
    val w = bmp.width
    val h = bmp.height
    val rowSize = (w * 3 + 3) / 4 * 4
    val dataSize = rowSize * h
    val fileSize = 54 + dataSize
    val buf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
    buf.put('B'.code.toByte()).put('M'.code.toByte())
    buf.putInt(fileSize).putInt(0).putInt(54)
    buf.putInt(40).putInt(w).putInt(h).putShort(1).putShort(24)
    buf.putInt(0).putInt(dataSize).putInt(2835).putInt(2835).putInt(0).putInt(0)
    val pixels = IntArray(w)
    val pad = ByteArray(rowSize - w * 3)
    for (y in h - 1 downTo 0) { // BMP 从最后一行开始存
        bmp.getPixels(pixels, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            val c = pixels[x]
            buf.put(c.toByte())            // B
            buf.put((c shr 8).toByte())    // G
            buf.put((c shr 16).toByte())   // R
        }
        buf.put(pad)
    }
    if (bmp !== src) bmp.recycle()
    return buf.array()
}

private fun encodeImage(bitmap: Bitmap, fmt: String): ByteArray {
    if (fmt == "BMP") return encodeBmp(bitmap)
    val out = ByteArrayOutputStream()
    val ok = when (fmt) {
        "JPG" -> {
            val flat = flattenOnWhite(bitmap)
            val r = flat.compress(Bitmap.CompressFormat.JPEG, 95, out)
            if (flat !== bitmap) flat.recycle()
            r
        }
        "PNG" -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        "WEBP" -> {
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
                         else Bitmap.CompressFormat.WEBP
            bitmap.compress(format, 95, out)
        }
        else -> false
    }
    if (!ok) throw IllegalStateException("编码 $fmt 失败")
    return out.toByteArray()
}

/** 转换一张图片并保存到相册（Pictures/toTool），返回给用户看的保存位置。 */
private fun convertImage(context: Context, uri: Uri, fmt: String, baseName: String): String {
    val bitmap = decodeImage(context, uri)
    try {
        val bytes = encodeImage(bitmap, fmt)
        val displayName = "$baseName.${fmt.lowercase(Locale.ROOT)}"
        ImageOps.saveBytesToGallery(context, bytes, imageMime(fmt), displayName)
            ?: throw IllegalStateException("保存到相册失败")
        return "Pictures/toTool/$displayName（${ImageOps.sizeLabel(bytes.size.toLong())}）"
    } finally {
        bitmap.recycle()
    }
}

private fun queryFileName(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else "file"
        } ?: "file"
    } catch (e: Exception) {
        "file"
    }
}
