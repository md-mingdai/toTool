package com.mingdai.toTool.ui.screens.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.min

private enum class RecordMode(val label: String) {
    ENCODE("生成唱片"),
    DECODE("还原音频"),
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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        sourceUri = uri
        sourceName = queryFileName(context, uri)
        sourceSize = queryFileSize(context, uri)
        resultText = null
        noticeText = null
        errorText = null
        decodedMeta = null
        preview = null
    }

    LaunchedEffect(sourceUri, mode) {
        val uri = sourceUri ?: return@LaunchedEffect
        if (mode == RecordMode.DECODE) {
            preview = withContext(Dispatchers.IO) { ImageOps.uriToBitmap(context, uri, 720) }
        }
    }

    DisposableEffect(Unit) {
        onDispose { canceled.set(true) }
    }
    LaunchedEffect(isRunning) { canceled.set(false) }
    KeepScreenOn(isRunning)

    fun switchMode(next: RecordMode) {
        if (isRunning || mode == next) return
        mode = next
        sourceUri = null
        sourceName = null
        sourceSize = -1L
        preview = null
        decodedMeta = null
        resultText = null
        noticeText = null
        errorText = null
    }

    fun startEncode() {
        val uri = sourceUri ?: return
        val name = sourceName ?: "audio"
        if (isRunning) return
        canceled.set(false)
        isRunning = true
        resultText = null
        noticeText = null
        errorText = null
        decodedMeta = null
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val bytes = readLimitedBytes(context, uri, AudioRecordCodec.MAX_AUDIO_BYTES, canceled)
                    val mime = mimeOf(context, uri, name)
                    val maxPixels = minOf(
                        AudioRecordCodec.MAX_RECORD_PIXELS,
                        MemoryBudget.maxPixels(context, 12.0),
                    )
                    val encoded = AudioRecordCodec.encode(bytes, name, mime, maxPixels)
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
        canceled.set(false)
        isRunning = true
        resultText = null
        noticeText = null
        errorText = null
        decodedMeta = null
        scope.launch {
            var temp: File? = null
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    val pixelCount = bounds.outWidth.toLong() * bounds.outHeight
                    if (bounds.outWidth > AudioRecordCodec.MAX_DIMENSION ||
                        bounds.outHeight > AudioRecordCodec.MAX_DIMENSION ||
                        pixelCount > AudioRecordCodec.MAX_RECORD_PIXELS ||
                        pixelCount > MemoryBudget.maxPixels(context, 8.0)
                    ) {
                        throw ConvertException("图片尺寸过大或超出当前内存预算，拒绝解码")
                    }
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
                        val mime = decoded.meta.mime.ifBlank { mimeForName(name) }
                        val output = File(context.cacheDir, "conv_record_${System.nanoTime()}_$name")
                        output.outputStream().use { it.write(decoded.audioBytes) }
                        temp = output
                        val published = publishToDownloads(context, output, mime, canceled) {}
                        val result = if (published.path != null) {
                            "已保存到：${published.path}"
                        } else {
                            throw ConvertException(
                                "无法保存音频" + (published.error?.let { "（$it）" } ?: ""),
                            )
                        }
                        Triple(result, decoded.meta, decoded.meta.originalSize)
                    } finally {
                        bitmap.recycle()
                    }
                }
                temp = null
                decodedMeta = outcome.second
                resultText = outcome.first
                noticeText = "已还原 ${outcome.second.name}（原始大小 ${humanSize(outcome.third)}）。"
            } catch (e: ConvertCanceledException) {
                noticeText = "已取消"
            } catch (e: Exception) {
                errorText = friendlyError(e)
            } finally {
                temp?.delete()
                isRunning = false
            }
        }
    }

    val isEncode = mode == RecordMode.ENCODE
    val tooLarge = isEncode && sourceSize > AudioRecordCodec.MAX_AUDIO_BYTES
    val canRun = sourceUri != null && !isRunning && !tooLarge
    val expected = if (isEncode && sourceSize in 1..AudioRecordCodec.MAX_AUDIO_BYTES.toLong()) {
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
                    "音频超过 ${AudioRecordCodec.MAX_AUDIO_BYTES / (1024 * 1024)} MB，请先压缩或换一个文件。",
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
            onClick = { if (isEncode) startEncode() else startDecode() },
            enabled = canRun,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    isRunning -> "处理中…"
                    isEncode -> "生成图片唱片"
                    else -> "提取音频"
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
}

private fun readLimitedBytes(
    context: Context,
    uri: Uri,
    limit: Int,
    canceled: AtomicBoolean,
): ByteArray {
    val known = queryFileSize(context, uri)
    if (known > limit) throw ConvertException("音频太大（最大 ${limit / (1024 * 1024)} MB）")
    val output = ByteArrayOutputStream()
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            if (canceled.get()) throw ConvertCanceledException()
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw ConvertException("音频太大（最大 ${limit / (1024 * 1024)} MB）")
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
