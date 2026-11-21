package com.mingdai.toTool.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.mingdai.toTool.ui.screens.tools.ImageOps
import java.io.File
import kotlin.math.max
import kotlin.math.min

internal fun loadEmbeddedCover(context: Context, uri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val bytes = retriever.embeddedPicture ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

internal fun createGeneratedCover(
    title: String,
    subtitle: String,
    width: Int,
    height: Int,
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(
        0f, 0f, width.toFloat(), height.toFloat(),
        intArrayOf(Color.rgb(18, 24, 20), Color.rgb(38, 54, 39), Color.rgb(16, 19, 17)),
        null,
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    paint.shader = null

    val accent = Color.rgb(139, 195, 74)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = width * 0.012f
    paint.color = Color.argb(80, 139, 195, 74)
    canvas.drawCircle(width * 0.78f, height * 0.25f, width * 0.34f, paint)
    paint.color = Color.argb(45, 139, 195, 74)
    canvas.drawCircle(width * 0.16f, height * 0.86f, width * 0.28f, paint)

    paint.style = Paint.Style.FILL
    paint.color = accent
    val barLeft = width * 0.09f
    val barTop = height * 0.73f
    val barWidth = width * 0.035f
    val gap = width * 0.025f
    val heights = floatArrayOf(0.10f, 0.22f, 0.16f, 0.36f, 0.24f, 0.46f, 0.30f, 0.18f, 0.28f, 0.12f)
    heights.forEachIndexed { index, h ->
        val left = barLeft + index * (barWidth + gap)
        canvas.drawRoundRect(
            left,
            barTop - height * h,
            left + barWidth,
            barTop,
            barWidth / 2,
            barWidth / 2,
            paint,
        )
    }

    paint.color = Color.WHITE
    paint.textSize = width * 0.072f
    paint.isFakeBoldText = true
    val safeTitle = ellipsize(title.ifBlank { "音频" }, 18)
    canvas.drawText(safeTitle, width * 0.09f, height * 0.20f, paint)
    paint.isFakeBoldText = false
    paint.color = Color.argb(190, 255, 255, 255)
    paint.textSize = width * 0.040f
    canvas.drawText(ellipsize(subtitle.ifBlank { "toTool 音频" }, 26), width * 0.09f, height * 0.28f, paint)
    return bitmap
}

internal fun createSolidCover(width: Int, height: Int, color: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawColor(color)
    return bitmap
}

internal fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
    if (source.width == width && source.height == height) return source
    val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
    val scaledW = max(1, (source.width * scale).toInt())
    val scaledH = max(1, (source.height * scale).toInt())
    val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)
    val x = ((scaledW - width) / 2).coerceAtLeast(0)
    val y = ((scaledH - height) / 2).coerceAtLeast(0)
    val cropped = Bitmap.createBitmap(scaled, x, y, min(width, scaledW), min(height, scaledH))
    if (scaled !== source && scaled !== cropped) scaled.recycle()
    if (source !== scaled && source !== cropped) source.recycle()
    return cropped
}

internal fun prepareArtwork(
    context: Context,
    audioUri: Uri,
    mode: VisualMode,
    customImage: Uri?,
    audioTitle: String,
    audioDurationMs: Long,
    width: Int,
    height: Int,
    dir: File,
): File? {
    if (mode == VisualMode.WAVEFORM) return null
    val bitmap = when (mode) {
        VisualMode.COVER -> {
            val embedded = loadEmbeddedCover(context, audioUri)
            if (embedded != null) {
                centerCrop(embedded, width, height)
            } else {
                createGeneratedCover(audioTitle, durationLabel(audioDurationMs), width, height)
            }
        }
        VisualMode.SOLID -> createSolidCover(width, height, Color.rgb(20, 28, 23))
        VisualMode.CUSTOM -> {
            val uri = customImage ?: throw ConvertException("请选择自定义画面图片")
            val source = ImageOps.uriToBitmap(context, uri, max(width, height))
                ?: throw ConvertException("无法读取自定义画面图片")
            centerCrop(source, width, height)
        }
        VisualMode.WAVEFORM -> null
    } ?: return null
    val out = File(dir, "conv_cover_${System.nanoTime()}.png")
    return try {
        out.outputStream().use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw ConvertException("封面 PNG 编码失败")
            }
        }
        out
    } catch (e: Exception) {
        out.delete()
        throw e
    } finally {
        bitmap.recycle()
    }
}

internal fun durationLabel(durationMs: Long): String {
    if (durationMs <= 0L) return "未知时长"
    val total = durationMs / 1000
    val minutes = total / 60
    val seconds = total % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun ellipsize(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"
