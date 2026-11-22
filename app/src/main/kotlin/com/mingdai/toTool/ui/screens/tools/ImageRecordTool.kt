package com.mingdai.toTool.ui.screens.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.media.AudioRecordCodec
import com.mingdai.toTool.media.ConvertCanceledException
import com.mingdai.toTool.media.ConvertException
import com.mingdai.toTool.media.baseNameOf
import com.mingdai.toTool.media.friendlyError
import com.mingdai.toTool.media.humanSize
import com.mingdai.toTool.media.publishToDownloads
import com.mingdai.toTool.media.queryFileName
import com.mingdai.toTool.media.queryFileSize
import com.mingdai.toTool.media.sanitizeFileName
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.BitmapPreviewCard
import com.mingdai.toTool.ui.components.KeepScreenOn
import com.mingdai.toTool.ui.components.LabeledPickerCard
import com.mingdai.toTool.ui.components.MessageCard
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.ResultBox
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.min

private enum class RecordMode(val label: String) {
    ENCODE("生成唱片"),
    DECODE("还原音频"),
}

private enum class PendingRecordAction { ENCODE, DECODE }

private enum class PlaybackState(val label: String) {
    IDLE("未开始"),
    PREPARING("准备中…"),
    PLAYING("播放中"),
    PAUSED("已暂停"),
    COMPLETED("播放完毕"),
}

@Composable
fun ImageRecordTool(padding: androidx.compose.foundation.layout.PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val canceled = remember { AtomicBoolean(false) }

    var mode by remember { mutableStateOf(RecordMode.ENCODE) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var sourceSize by remember { mutableStateOf(-1L) }
    var isRunning by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var noticeText by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var decodedMeta by remember { mutableStateOf<AudioRecordCodec.RecordMeta?>(null) }
    var decodedFile by remember { mutableStateOf<File?>(null) }
    var playbackState by remember { mutableStateOf(PlaybackState.IDLE) }
    var playbackPositionMs by remember { mutableStateOf(0L) }
    var playbackDurationMs by remember { mutableStateOf(0L) }
    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
    val playerRef = remember { arrayOfNulls<MediaPlayer>(1) }
    var warningDialog by remember { mutableStateOf(false) }
    var warningMessage by remember { mutableStateOf("") }
    var warningAction by remember { mutableStateOf(PendingRecordAction.ENCODE) }

    fun releasePlayer() {
        playerRef[0]?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        playerRef[0] = null
        playbackState = PlaybackState.IDLE
        playbackPositionMs = 0L
        playbackDurationMs = 0L
        seekPreviewMs = null
    }

    fun discardDecodedAudio() {
        releasePlayer()
        decodedFile?.delete()
        decodedFile = null
        decodedMeta = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        sourceUri = uri
        sourceName = queryFileName(context, uri)
        sourceSize = queryFileSize(context, uri)
        resultText = null
        noticeText = null
        errorText = null
        discardDecodedAudio()
        preview = null
    }

    LaunchedEffect(sourceUri, mode) {
        val uri = sourceUri ?: return@LaunchedEffect
        if (mode == RecordMode.DECODE) {
            preview = withContext(Dispatchers.IO) { ImageOps.uriToBitmap(context, uri, 720) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            canceled.set(true)
            releasePlayer()
            decodedFile?.delete()
        }
    }
    LaunchedEffect(isRunning) { canceled.set(false) }
    KeepScreenOn(isRunning)

    LaunchedEffect(playbackState) {
        if (playbackState != PlaybackState.PLAYING) return@LaunchedEffect
        while (true) {
            val player = playerRef[0] ?: break
            val position = runCatching { player.currentPosition.toLong() }.getOrNull() ?: break
            if (seekPreviewMs == null) playbackPositionMs = position
            val duration = runCatching { player.duration.toLong() }.getOrNull() ?: 0L
            if (duration > 0L) playbackDurationMs = duration
            delay(250)
        }
    }

    fun switchMode(next: RecordMode) {
        if (isRunning || mode == next) return
        mode = next
        sourceUri = null
        sourceName = null
        sourceSize = -1L
        preview = null
        discardDecodedAudio()
        resultText = null
        noticeText = null
        errorText = null
        warningDialog = false
    }

    fun startEncode() {
        val uri = sourceUri ?: return
        val name = sourceName ?: "audio"
        if (isRunning) return
        discardDecodedAudio()
        canceled.set(false)
        isRunning = true
        resultText = null
        noticeText = null
        errorText = null
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val bytes = readAllBytes(context, uri, canceled)
                    val mime = mimeOf(context, uri, name)
                    val encoded = AudioRecordCodec.encode(bytes, name, mime)
                    if (canceled.get()) throw ConvertCanceledException()
                    val bitmap = Bitmap.createBitmap(encoded.width, encoded.height, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(encoded.pixels, 0, encoded.width, 0, 0, encoded.width, encoded.height)
                    val displayName = "${baseNameOf(name)}_record.png"
                    val saved = ImageOps.savePngToGallery(context, bitmap, displayName)
                        ?: throw ConvertException("无法保存图片唱片到相册")
                    val previewBitmap = if (max(encoded.width, encoded.height) > 720) {
                        val scale = 720f / max(encoded.width, encoded.height)
                        Bitmap.createScaledBitmap(
                            bitmap,
                            max(1, (encoded.width * scale).toInt()),
                            max(1, (encoded.height * scale).toInt()),
                            true,
                        ).also { bitmap.recycle() }
                    } else {
                        bitmap
                    }
                    Triple("Pictures/toTool/$displayName（${encoded.width}×${encoded.height}）", mime, previewBitmap)
                }
                preview = outcome.third
                resultText = "已保存到：${outcome.first}"
                noticeText = "图片唱片是娱乐功能，不是加密；请保留原始 PNG，压缩、转 JPG、截图或编辑都会导致无法还原。"
            } catch (e: ConvertCanceledException) {
                noticeText = "已取消"
            } catch (e: OutOfMemoryError) {
                errorText = "内存不足：文件太大，设备无法完成图片唱片编码。"
            } catch (e: Exception) {
                errorText = friendlyError(e)
            } finally {
                isRunning = false
            }
        }
    }

    fun startDecode() {
        val uri = sourceUri ?: return
        if (isRunning) return
        discardDecodedAudio()
        canceled.set(false)
        isRunning = true
        resultText = null
        noticeText = null
        errorText = null
        scope.launch {
            var created: File? = null
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    }
                    if (canceled.get()) throw ConvertCanceledException()
                    try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val decoded = AudioRecordCodec.decode(pixels, bitmap.width, bitmap.height)
                        val name = sanitizeFileName(decoded.meta.name, "restored_audio")
                        val output = File(context.cacheDir, "conv_record_${System.nanoTime()}_$name")
                        output.outputStream().use { it.write(decoded.audioBytes) }
                        created = output
                        Triple(output, decoded.meta, decoded.meta.originalSize)
                    } finally {
                        bitmap.recycle()
                    }
                }
                created = null
                decodedFile = outcome.first
                decodedMeta = outcome.second
                noticeText = "已读取 ${outcome.second.name}（原始大小 ${humanSize(outcome.third)}）。可以先试听，再决定是否导出。"
            } catch (e: ConvertCanceledException) {
                noticeText = "已取消"
            } catch (e: OutOfMemoryError) {
                errorText = "内存不足：图片太大，设备无法完成解码。"
            } catch (e: Exception) {
                errorText = friendlyError(e)
            } finally {
                created?.delete()
                isRunning = false
            }
        }
    }

    fun createPlayer(file: File) {
        releasePlayer()
        val player = MediaPlayer()
        playerRef[0] = player
        playbackState = PlaybackState.PREPARING
        player.setOnPreparedListener { prepared ->
            if (playerRef[0] !== prepared) {
                runCatching { prepared.release() }
                return@setOnPreparedListener
            }
            playbackDurationMs = runCatching { prepared.duration.toLong() }.getOrDefault(0L)
            runCatching { prepared.start() }
                .onSuccess { playbackState = PlaybackState.PLAYING }
                .onFailure {
                    releasePlayer()
                    errorText = "无法播放这段音频"
                }
        }
        player.setOnCompletionListener {
            playbackPositionMs = playbackDurationMs
            playbackState = PlaybackState.COMPLETED
        }
        player.setOnErrorListener { _, _, _ ->
            releasePlayer()
            errorText = "无法播放这段音频（格式可能不被系统支持）"
            true
        }
        try {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
            errorText = "无法播放这段音频：${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun togglePlayback() {
        val file = decodedFile ?: return
        errorText = null
        when (playbackState) {
            PlaybackState.PREPARING -> return
            PlaybackState.PLAYING -> {
                playerRef[0]?.let { player ->
                    playbackPositionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(playbackPositionMs)
                    runCatching { player.pause() }
                }
                playbackState = PlaybackState.PAUSED
            }
            PlaybackState.PAUSED, PlaybackState.COMPLETED -> {
                val player = playerRef[0]
                if (player != null) {
                    if (playbackState == PlaybackState.COMPLETED) {
                        runCatching { player.seekTo(0) }
                        playbackPositionMs = 0L
                    }
                    runCatching { player.start() }
                        .onSuccess { playbackState = PlaybackState.PLAYING }
                        .onFailure {
                            releasePlayer()
                            errorText = "无法播放这段音频"
                        }
                } else {
                    createPlayer(file)
                }
            }
            PlaybackState.IDLE -> createPlayer(file)
        }
    }

    fun stopPlayback() {
        releasePlayer()
    }

    fun exportDecodedAudio() {
        val file = decodedFile ?: return
        val meta = decodedMeta ?: return
        if (isRunning) return
        releasePlayer()
        canceled.set(false)
        isRunning = true
        resultText = null
        noticeText = null
        errorText = null
        scope.launch {
            try {
                val mime = meta.mime.ifBlank { mimeForName(meta.name) }
                val published = withContext(Dispatchers.IO) {
                    publishToDownloads(context, file, mime, canceled) {}
                }
                val path = published.path
                if (path == null) {
                    throw ConvertException(
                        "无法保存音频" + (published.error?.let { "（$it）" } ?: ""),
                    )
                }
                decodedFile = null
                resultText = "已保存到：$path"
                noticeText = "已导出 ${meta.name}（${humanSize(meta.originalSize)}）。"
            } catch (e: ConvertCanceledException) {
                noticeText = "已取消导出"
            } catch (e: Exception) {
                errorText = friendlyError(e)
            } finally {
                isRunning = false
            }
        }
    }

    fun requestDecode() {
        val uri = sourceUri ?: return
        if (isRunning) return
        scope.launch {
            isRunning = true
            var proceed = false
            try {
                val bounds = withContext(Dispatchers.IO) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                    options
                }
                val pixels = bounds.outWidth.toLong() * bounds.outHeight
                val tooLarge = bounds.outWidth > AudioRecordCodec.MAX_DIMENSION ||
                    bounds.outHeight > AudioRecordCodec.MAX_DIMENSION ||
                    pixels > AudioRecordCodec.MAX_RECORD_PIXELS ||
                    pixels > MemoryBudget.maxPixels(context, 8.0)
                if (tooLarge) {
                    warningMessage = "图片约 ${bounds.outWidth}×${bounds.outHeight}，超过建议的试听/还原尺寸。" +
                        "\n\n继续解码可能会占用大量内存，导致卡顿、内存不足，甚至应用被系统终止。" +
                        "仍可继续，但建议先使用原尺寸较小图片或关闭其他应用。"
                    warningAction = PendingRecordAction.DECODE
                    warningDialog = true
                } else {
                    proceed = true
                }
            } catch (e: Exception) {
                errorText = friendlyError(e)
            } finally {
                isRunning = false
            }
            if (proceed) startDecode()
        }
    }

    val isEncode = mode == RecordMode.ENCODE
    val tooLarge = isEncode && sourceSize > AudioRecordCodec.MAX_AUDIO_BYTES
    val canRun = sourceUri != null && !isRunning
    val expected = if (isEncode && sourceSize in 1..Int.MAX_VALUE.toLong()) {
        runCatching {
            AudioRecordCodec.layoutFor(
                sourceSize.toInt(),
                sourceName ?: "audio",
                sourceUri?.let { mimeOf(context, it, sourceName ?: "audio") } ?: "application/octet-stream",
            )
        }.getOrNull()
    } else {
        null
    }

    fun requestEncode() {
        if (isRunning) return
        if (sourceUri == null) return
        val budget = MemoryBudget.maxPixels(context, 12.0)
        val layout = expected
        val tooLarge = sourceSize > AudioRecordCodec.MAX_AUDIO_BYTES ||
            (layout != null && (
                layout.width > AudioRecordCodec.MAX_DIMENSION ||
                    layout.height > AudioRecordCodec.MAX_DIMENSION ||
                    layout.totalPixels > budget
                ))
        if (tooLarge) {
            warningMessage = buildString {
                append("音频约 ${humanSize(sourceSize)}")
                if (layout != null) append("，生成的图片约 ${layout.width}×${layout.height}")
                append("。\n\n继续可能会占用大量内存，导致卡顿、内存不足，甚至应用被系统终止。")
                append("图片唱片仍会尝试生成，但建议先压缩音频或关闭其他应用。")
            }
            warningAction = PendingRecordAction.ENCODE
            warningDialog = true
            return
        }
        startEncode()
    }

    ToolBody(padding) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordMode.entries.forEach { option ->
                SelectChip(
                    selected = mode == option,
                    onClick = { switchMode(option) },
                    label = option.label,
                    enabled = !isRunning,
                )
            }
        }

        LabeledPickerCard(
            title = if (isEncode) "音频文件" else "图片唱片",
            value = sourceName ?: if (isEncode) "选择音频…" else "选择 PNG 图片…",
            supporting = when {
                sourceSize > 0L -> humanSize(sourceSize)
                isEncode -> "最大 ${AudioRecordCodec.MAX_AUDIO_BYTES / (1024 * 1024)} MB"
                else -> "必须保留原始 PNG"
            },
            icon = if (isEncode) Icons.Filled.AudioFile else Icons.Filled.Image,
            onClick = { picker.launch(if (isEncode) "audio/*" else "image/*") },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
        )

        if (isEncode) {
            Text(
                if (expected != null) {
                    "预计生成 ${expected.width}×${expected.height} 的 PNG；音频会以 Base64 写入 RGB 像素。"
                } else {
                    "音频会以 Base64 写入 PNG 的 RGB 像素。"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (tooLarge) {
                Text(
                    "音频超过建议的 ${AudioRecordCodec.MAX_AUDIO_BYTES / (1024 * 1024)} MB；开始时只弹窗警告，不会强制拒绝，但可能占用大量内存。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }
        } else {
            Text(
                "读取 PNG 头部的 Base64 数据并还原音频。图片被压缩、转成 JPG、截图或缩放后校验会失败。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        preview?.let {
            BitmapPreviewCard(
                bitmap = it,
                modifier = Modifier.fillMaxWidth().height(260.dp),
                placeholder = "预览",
            )
        }

        if (isRunning) {
            LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
            SecondaryButton(
                onClick = { canceled.set(true) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("取消") }
        }

        decodedMeta?.let { meta ->
            Text(
                "文件名：${meta.name} · ${meta.mime.ifBlank { "未知类型" }} · 原始大小 ${humanSize(meta.originalSize)}",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (!isEncode && decodedFile != null) {
            Text(
                "试听状态：${playbackState.label}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            val shownPosition = (seekPreviewMs ?: playbackPositionMs)
                .coerceIn(0L, playbackDurationMs.coerceAtLeast(0L))
            val sliderMax = playbackDurationMs.coerceAtLeast(1L).toFloat()
            Slider(
                value = shownPosition.toFloat(),
                onValueChange = { seekPreviewMs = it.toLong() },
                onValueChangeFinished = {
                    val target = (seekPreviewMs ?: playbackPositionMs).coerceIn(0L, playbackDurationMs.coerceAtLeast(0L))
                    playerRef[0]?.let { player ->
                        runCatching { player.seekTo(target.toInt()) }
                        if (playbackState == PlaybackState.PLAYING) {
                            playbackPositionMs = target
                        } else {
                            playbackState = PlaybackState.PAUSED
                            playbackPositionMs = target
                        }
                    }
                    seekPreviewMs = null
                },
                valueRange = 0f..sliderMax,
                enabled = !isRunning && playbackDurationMs > 0L,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatPlaybackTime(shownPosition),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    formatPlaybackTime(playbackDurationMs),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryButton(
                    onClick = { togglePlayback() },
                    enabled = !isRunning && playbackState != PlaybackState.PREPARING,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when (playbackState) {
                            PlaybackState.PLAYING -> "暂停"
                            PlaybackState.PREPARING -> "准备中…"
                            PlaybackState.COMPLETED -> "重新试听"
                            else -> "试听"
                        },
                        maxLines = 1,
                    )
                }
                SecondaryButton(
                    onClick = { stopPlayback() },
                    enabled = !isRunning && playbackState != PlaybackState.IDLE,
                    modifier = Modifier.weight(1f),
                ) { Text("停止", maxLines = 1) }
                PrimaryButton(
                    onClick = { exportDecodedAudio() },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f),
                ) { Text("导出", maxLines = 1) }
            }
        }

        errorText?.let {
            MessageCard(
                title = "操作失败",
                message = it,
                container = MiuixTheme.colorScheme.errorContainer,
                content = MiuixTheme.colorScheme.onErrorContainer,
            )
        }
        noticeText?.let {
            MessageCard(
                title = null,
                message = it,
                container = MiuixTheme.colorScheme.secondaryContainer,
                content = MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }
        resultText?.let { ResultBox(it, label = "输出") }

        PrimaryButton(
            onClick = { if (isEncode) requestEncode() else requestDecode() },
            enabled = canRun,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    isRunning -> "处理中…"
                    isEncode -> "生成图片唱片"
                    else -> "读取唱片"
                },
            )
        }

        MessageCard(
            title = "注意",
            message = "娱乐功能，不是加密。任何拿到 PNG 的人都能还原音频；请勿用于敏感内容。" +
                "聊天软件、压缩、转 JPG、截图或编辑会破坏像素数据。",
            container = MiuixTheme.colorScheme.secondaryContainer,
            content = MiuixTheme.colorScheme.onSecondaryContainer,
        )
    }

    AppDialog(
        show = warningDialog,
        title = "文件较大",
        summary = null,
        confirmText = "仍然继续",
        dismissText = "取消",
        onDismiss = { warningDialog = false },
        onConfirm = {
            warningDialog = false
            when (warningAction) {
                PendingRecordAction.ENCODE -> startEncode()
                PendingRecordAction.DECODE -> startDecode()
            }
        },
    ) {
        Text(warningMessage, style = MiuixTheme.textStyles.footnote1)
    }
}

private fun formatPlaybackTime(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun readAllBytes(
    context: Context,
    uri: Uri,
    canceled: AtomicBoolean,
): ByteArray {
    val output = ByteArrayOutputStream()
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (canceled.get()) throw ConvertCanceledException()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
        }
    } ?: throw ConvertException("无法读取音频文件")
    return output.toByteArray()
}

private fun mimeOf(context: Context, uri: Uri, name: String): String =
    context.contentResolver.getType(uri)
        ?: MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

private fun mimeForName(name: String): String =
    MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
