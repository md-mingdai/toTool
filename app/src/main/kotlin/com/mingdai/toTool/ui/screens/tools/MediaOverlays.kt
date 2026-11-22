package com.mingdai.toTool.ui.screens.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** ASS \an 1..9；SRT 默认底部居中。 */
    val alignment: Int = 2,
)

/** 字幕文件多为 UTF-8；遇到 GBK / GB18030 老字幕时回退解码，避免中文乱码。 */
internal fun decodeSubtitleText(bytes: ByteArray): String {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
        .getOrElse { String(bytes, Charset.forName("GB18030")) }
}

/** 解析 SRT / ASS 文本。复杂 ASS 样式会降级为白色描边文字。 */
internal fun parseSubtitle(content: String, fileName: String): List<SubtitleCue> {
    val normalized = content.removePrefix("﻿")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    val isAss = fileName.endsWith(".ass", true) ||
        normalized.contains("[Events]", true) ||
        normalized.contains("Dialogue:", true)
    return if (isAss) parseAss(normalized) else parseSrt(normalized)
}

private val SRT_TIME = Regex(
    """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""",
)

private fun parseSrt(text: String): List<SubtitleCue> {
    val result = mutableListOf<SubtitleCue>()
    val blocks = text.split(Regex("""\n\s*\n"""))
    for (block in blocks) {
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) continue
        val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
        if (timeLineIndex < 0) continue
        val match = SRT_TIME.find(lines[timeLineIndex]) ?: continue
        val start = srtMillis(match.groupValues, 0)
        val end = srtMillis(match.groupValues, 4)
        val body = lines.drop(timeLineIndex + 1).joinToString("\n").stripSubtitleTags()
        if (body.isNotBlank() && end > start) {
            result += SubtitleCue(start, end, body, 2)
        }
    }
    return result.sortedBy { it.startMs }
}

private fun srtMillis(g: List<String>, offset: Int): Long {
    val h = g[offset].toLongOrNull() ?: 0
    val m = g[offset + 1].toLongOrNull() ?: 0
    val s = g[offset + 2].toLongOrNull() ?: 0
    val ms = (g[offset + 3] + "000").take(3).toLongOrNull() ?: 0
    return ((h * 60 + m) * 60 + s) * 1000 + ms
}

private val ASS_TIME = Regex("""(\d+):(\d{2}):(\d{2})[.:](\d{1,3})""")

private fun assMillis(value: String): Long {
    val m = ASS_TIME.find(value.trim()) ?: return 0
    val h = m.groupValues[1].toLongOrNull() ?: 0
    val min = m.groupValues[2].toLongOrNull() ?: 0
    val s = m.groupValues[3].toLongOrNull() ?: 0
    val cs = (m.groupValues[4] + "00").take(2).toLongOrNull() ?: 0
    return ((h * 60 + min) * 60 + s) * 1000 + cs * 10
}

private fun parseAss(text: String): List<SubtitleCue> {
    val lines = text.lines()
    var inEvents = false
    var fields = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
    val result = mutableListOf<SubtitleCue>()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            inEvents = trimmed.equals("[Events]", true)
            continue
        }
        if (!inEvents) continue
        if (trimmed.startsWith("Format:", true)) {
            fields = trimmed.substringAfter(':').split(',').map { it.trim() }
            continue
        }
        if (!trimmed.startsWith("Dialogue:", true)) continue
        val values = trimmed.substringAfter(':').split(',', limit = fields.size)
        if (values.size < fields.size) continue
        val map = fields.mapIndexed { index, name -> name.trim().lowercase() to values[index].trim() }.toMap()
        val start = assMillis(map["start"].orEmpty())
        val end = assMillis(map["end"].orEmpty())
        val raw = map["text"].orEmpty()
        val alignment = Regex("""\\an([1-9])""").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 2
        val body = raw
            .replace(Regex("""\{[^}]*}"""), "")
            .replace("\\N", "\n", ignoreCase = true)
            .replace("\\n", "\n", ignoreCase = true)
            .replace("\\h", " ")
            .stripSubtitleTags()
            .trim()
        if (body.isNotBlank() && end > start) {
            result += SubtitleCue(start, end, body, alignment)
        }
    }
    return result.sortedBy { it.startMs }
}

private fun String.stripSubtitleTags(): String = this
    .replace(Regex("""<[^>]*>"""), "")
    .replace(Regex("""\{[^}]*}"""), "")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .trim()

/** 渲染一条透明字幕到覆盖层尺寸的 Bitmap。 */
internal fun renderSubtitleCue(cue: SubtitleCue, width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.TRANSPARENT)
    val textSize = maxOf(22f, height * 0.055f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        this.textSize = textSize
        textAlign = Paint.Align.LEFT
    }
    val maxWidth = width * 0.9f
    val lines = wrapText(cue.text, paint, maxWidth)
    val lineHeight = textSize * 1.24f
    val totalHeight = lineHeight * lines.size
    val margin = height * 0.045f
    val align = cue.alignment.coerceIn(1, 9)
    val top = when (align) {
        7, 8, 9 -> margin
        4, 5, 6 -> (height - totalHeight) / 2f
        else -> height - margin - totalHeight
    }
    val lineX: (String) -> Float = { line ->
        val textWidth = paint.measureText(line)
        when (align) {
            1, 4, 7 -> margin
            3, 6, 9 -> width - margin - textWidth
            else -> (width - textWidth) / 2f
        }
    }

    val stroke = Paint(paint).apply {
        style = Paint.Style.STROKE
        strokeWidth = textSize * 0.08f
        color = Color.BLACK
    }
    val fill = Paint(paint).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    lines.forEachIndexed { index, line ->
        val baseline = top + textSize + index * lineHeight
        val x = lineX(line)
        canvas.drawText(line, x, baseline, stroke)
        canvas.drawText(line, x, baseline, fill)
    }
    return bitmap
}

internal fun renderTextWatermark(
    text: String,
    width: Int,
    height: Int,
    corner: OverlayCorner,
    opacity: Float,
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.TRANSPARENT)
    val textSize = maxOf(22f, height * 0.045f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        this.textSize = textSize
        color = Color.WHITE
        alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
    }
    val margin = height * 0.04f
    val textWidth = paint.measureText(text)
    val x = if (corner == OverlayCorner.TOP_RIGHT || corner == OverlayCorner.BOTTOM_RIGHT) {
        width - margin - textWidth
    } else {
        margin
    }
    val baseline = if (corner == OverlayCorner.BOTTOM_LEFT || corner == OverlayCorner.BOTTOM_RIGHT) {
        height - margin
    } else {
        margin + textSize
    }
    val stroke = Paint(paint).apply {
        style = Paint.Style.STROKE
        strokeWidth = textSize * 0.07f
        color = Color.BLACK
    }
    canvas.drawText(text, x, baseline, stroke)
    canvas.drawText(text, x, baseline, paint)
    return bitmap
}

internal fun renderImageWatermark(
    context: Context,
    uri: Uri,
    width: Int,
    height: Int,
    corner: OverlayCorner,
    opacity: Float,
): Bitmap {
    val source = com.mingdai.toTool.ui.screens.tools.ImageOps.uriToBitmap(context, uri, maxOf(width, height) / 2)
        ?: throw com.mingdai.toTool.media.ConvertException("无法读取水印图片")
    if (source.width <= 0 || source.height <= 0) {
        source.recycle()
        throw com.mingdai.toTool.media.ConvertException("水印图片尺寸无效")
    }
    val maxHeight = (height * 0.5f).toInt().coerceAtLeast(1)
    var targetWidth = (width * 0.22f).toInt().coerceAtLeast(1)
    var targetHeight = (source.height.toFloat() / source.width * targetWidth)
        .toInt().coerceAtLeast(1)
    if (targetHeight > maxHeight) {
        targetHeight = maxHeight
        targetWidth = (source.width.toFloat() / source.height * targetHeight)
            .toInt().coerceAtLeast(1)
    }
    val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.TRANSPARENT)
    val margin = height * 0.04f
    val x = if (corner == OverlayCorner.TOP_RIGHT || corner == OverlayCorner.BOTTOM_RIGHT) {
        width - margin - targetWidth
    } else {
        margin
    }
    val y = if (corner == OverlayCorner.BOTTOM_LEFT || corner == OverlayCorner.BOTTOM_RIGHT) {
        height - margin - targetHeight
    } else {
        margin
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
    }
    canvas.drawBitmap(scaled, x, y, paint)
    if (scaled !== source) scaled.recycle()
    source.recycle()
    return bitmap
}

internal enum class OverlayCorner(val label: String, val expression: String) {
    TOP_LEFT("左上", "16:16"),
    TOP_RIGHT("右上", "W-w-16:16"),
    BOTTOM_LEFT("左下", "16:H-h-16"),
    BOTTOM_RIGHT("右下", "W-w-16:H-h-16"),
}

internal fun saveTransparentFrame(file: File, width: Int, height: Int) {
    savePng(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), file)
}

internal fun savePng(bitmap: Bitmap, file: File) {
    file.outputStream().use { stream ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
            throw com.mingdai.toTool.media.ConvertException("水印/字幕图片编码失败")
        }
    }
    bitmap.recycle()
}

private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val result = mutableListOf<String>()
    text.split('\n').forEach { paragraph ->
        var remaining = paragraph.trim()
        if (remaining.isEmpty()) {
            result += ""
            return@forEach
        }
        while (remaining.isNotEmpty()) {
            var count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
            if (count < remaining.length) {
                val space = remaining.lastIndexOf(' ', count)
                if (space > 0) count = space
            }
            result += remaining.substring(0, count).trim()
            remaining = remaining.substring(count).trimStart()
        }
    }
    return result
}
