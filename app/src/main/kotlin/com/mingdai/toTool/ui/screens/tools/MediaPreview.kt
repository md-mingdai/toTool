package com.mingdai.toTool.ui.screens.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.media.VideoFrameInfo
import com.mingdai.toTool.media.extractAudioCover
import com.mingdai.toTool.media.extractVideoFrameAt
import com.mingdai.toTool.media.queryFileName
import com.mingdai.toTool.ui.components.BitmapPreviewCard
import com.mingdai.toTool.ui.components.SecondaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class PreviewFrame(val bitmap: Bitmap?, val loading: Boolean = false)

internal data class SubtitleCuePreview(
    val first: SubtitleCue?,
    val loading: Boolean,
)

@Composable
internal fun rememberVideoFrame(
    uri: Uri?,
    timeMs: Long = 0L,
    maxSide: Int = 720,
    debounceMs: Long = 0L,
): PreviewFrame {
    val context = LocalContext.current
    var frame by remember(uri) { mutableStateOf(PreviewFrame(null, uri != null)) }
    LaunchedEffect(uri, timeMs, maxSide, debounceMs) {
        if (uri == null) {
            frame = PreviewFrame(null, false)
            return@LaunchedEffect
        }
        frame = frame.copy(loading = true)
        if (debounceMs > 0) delay(debounceMs)
        frame = PreviewFrame(
            withContext(Dispatchers.IO) { extractVideoFrameAt(context, uri, timeMs, maxSide) },
            loading = false,
        )
    }
    return frame
}

@Composable
internal fun rememberAudioCover(uri: Uri?): PreviewFrame {
    val context = LocalContext.current
    var frame by remember(uri) { mutableStateOf(PreviewFrame(null, uri != null)) }
    LaunchedEffect(uri) {
        if (uri == null) {
            frame = PreviewFrame(null, false)
            return@LaunchedEffect
        }
        frame = PreviewFrame(null, true)
        frame = PreviewFrame(
            withContext(Dispatchers.IO) { extractAudioCover(context, uri) },
            loading = false,
        )
    }
    return frame
}

/** 首帧经常是黑场，取 10% 位置作为代表性预览帧。 */
internal fun representativePreviewTime(info: VideoFrameInfo?): Long {
    val duration = info?.durationMs ?: 0L
    if (duration <= 1L) return 0L
    return (duration / 10).coerceAtMost(3000L).coerceAtMost(duration - 1L).coerceAtLeast(0L)
}

@Composable
internal fun MediaPreviewCard(
    frame: PreviewFrame,
    caption: String?,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    emptyText: String = "无法生成预览",
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BitmapPreviewCard(
            bitmap = frame.bitmap,
            modifier = Modifier.fillMaxWidth().height(height),
            placeholder = if (frame.loading) "正在生成预览…" else emptyText,
            busy = frame.loading,
        )
        if (!caption.isNullOrBlank()) {
            Text(
                caption,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 音频没有画面，提供试听作为预览。 */
@Composable
internal fun AudioPreviewButton(uri: Uri?, enabled: Boolean) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun release() {
        player?.let { p ->
            runCatching { p.reset() }
            runCatching { p.release() }
        }
        player = null
    }

    DisposableEffect(uri) {
        onDispose { release() }
    }
    LaunchedEffect(uri) {
        error = null
    }
    LaunchedEffect(enabled) {
        if (!enabled) release()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SecondaryButton(
            onClick = {
                error = null
                if (player != null) {
                    release()
                    return@SecondaryButton
                }
                val selected = uri ?: return@SecondaryButton
                val p = MediaPlayer()
                player = p
                p.setOnPreparedListener { prepared ->
                    if (player !== prepared) {
                        runCatching { prepared.release() }
                        return@setOnPreparedListener
                    }
                    runCatching { prepared.start() }
                        .onFailure {
                            error = "无法试听这段音频"
                            release()
                        }
                }
                p.setOnCompletionListener { release() }
                p.setOnErrorListener { _, _, _ ->
                    error = "无法试听这段音频（格式可能不被系统支持）"
                    release()
                    true
                }
                try {
                    p.setDataSource(context, selected)
                    p.prepareAsync()
                } catch (e: Exception) {
                    error = "无法试听这段音频：${e.message ?: e.javaClass.simpleName}"
                    release()
                }
            },
            enabled = uri != null && enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (player != null) "停止试听" else "试听音频") }
        error?.let {
            Text(
                it,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun rememberRotatedPreview(base: Bitmap?, degrees: Int): Bitmap? =
    remember(base, degrees) {
        base?.let { source ->
            val normalized = ((degrees % 360) + 360) % 360
            if (normalized == 0) {
                source
            } else {
                val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            }
        }
    }

@Composable
internal fun rememberPipPreview(main: Bitmap?, inset: Bitmap?, corner: OverlayCorner): Bitmap? =
    remember(main, inset, corner) {
        if (main == null || inset == null) {
            main
        } else {
            composePipPreview(main, inset, corner)
        }
    }

private fun composePipPreview(main: Bitmap, inset: Bitmap, corner: OverlayCorner): Bitmap {
    val out = main.copy(Bitmap.Config.ARGB_8888, true) ?: return main
    val canvas = Canvas(out)
    val margin = maxOf(4, (out.height * 0.025f).toInt())
    var width = (out.width * 0.28f).toInt().coerceAtLeast(1)
    var height = (inset.height.toFloat() / inset.width * width).toInt().coerceAtLeast(1)
    val maxHeight = (out.height * 0.6f).toInt().coerceAtLeast(1)
    if (height > maxHeight) {
        height = maxHeight
        width = (inset.width.toFloat() / inset.height * height).toInt().coerceAtLeast(1)
    }
    val scaled = Bitmap.createScaledBitmap(inset, width, height, true)
    val x = if (corner == OverlayCorner.TOP_RIGHT || corner == OverlayCorner.BOTTOM_RIGHT) {
        out.width - margin - width
    } else {
        margin
    }
    val y = if (corner == OverlayCorner.BOTTOM_LEFT || corner == OverlayCorner.BOTTOM_RIGHT) {
        out.height - margin - height
    } else {
        margin
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(scaled, x.toFloat(), y.toFloat(), paint)
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, out.width * 0.003f)
        color = 0x80FFFFFF.toInt()
    }
    canvas.drawRect(
        x.toFloat(),
        y.toFloat(),
        (x + width).toFloat(),
        (y + height).toFloat(),
        border,
    )
    if (scaled !== inset) scaled.recycle()
    return out
}

@Composable
internal fun rememberWatermarkPreview(
    base: Bitmap?,
    baseLoading: Boolean,
    text: String?,
    imageUri: Uri?,
    corner: OverlayCorner,
    opacity: Float,
): PreviewFrame {
    val context = LocalContext.current
    val state by produceState(
        PreviewFrame(base, baseLoading),
        base,
        baseLoading,
        text,
        imageUri,
        corner,
        opacity,
    ) {
        if (base == null) {
            value = PreviewFrame(null, baseLoading)
            return@produceState
        }
        value = PreviewFrame(base, baseLoading)
        if (text.isNullOrBlank() && imageUri == null) {
            value = PreviewFrame(base, false)
            return@produceState
        }
        value = PreviewFrame(base, true)
        delay(120)
        value = withContext(Dispatchers.IO) {
            val overlay = runCatching {
                if (text != null) {
                    renderTextWatermark(text, base.width, base.height, corner, opacity)
                } else {
                    renderImageWatermark(context, imageUri!!, base.width, base.height, corner, opacity)
                }
            }.getOrNull()
            if (overlay == null) {
                PreviewFrame(base, false)
            } else {
                PreviewFrame(overlayOnFrame(base, overlay), false)
            }
        }
    }
    return state
}

@Composable
internal fun rememberSubtitlePreview(base: Bitmap?, cue: SubtitleCue?): Bitmap? =
    remember(base, cue) {
        if (base == null || cue == null) {
            base
        } else {
            overlayOnFrame(base, renderSubtitleCue(cue, base.width, base.height))
        }
    }

@Composable
internal fun rememberSubtitleCues(uri: Uri?): SubtitleCuePreview {
    val context = LocalContext.current
    var state by remember(uri) {
        mutableStateOf(SubtitleCuePreview(first = null, loading = uri != null))
    }
    LaunchedEffect(uri) {
        if (uri == null) {
            state = SubtitleCuePreview(first = null, loading = false)
            return@LaunchedEffect
        }
        state = SubtitleCuePreview(first = null, loading = true)
        val cues = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    parseSubtitle(decodeSubtitleText(it.readBytes()), queryFileName(context, uri))
                }
            }.getOrNull()
        }
        state = SubtitleCuePreview(first = cues?.firstOrNull(), loading = false)
    }
    return state
}

private fun overlayOnFrame(base: Bitmap, overlay: Bitmap): Bitmap {
    val out = base.copy(Bitmap.Config.ARGB_8888, true)
    if (out == null) {
        overlay.recycle()
        return base
    }
    Canvas(out).drawBitmap(overlay, 0f, 0f, null)
    overlay.recycle()
    return out
}
