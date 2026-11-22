package com.mingdai.toTool.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mingdai.toTool.ui.screens.tools.ImageOps
import com.mingdai.toTool.ui.screens.tools.MemoryBudget
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

private fun peakBytesPerPixel(fmt: String): Double = when (fmt) {
    "WEBP" -> 10.0
    "PNG" -> 5.5
    "JPG" -> 5.0
    "BMP" -> 4.5
    else -> 6.0
}

private class DecodedImage(val bitmap: Bitmap, val srcW: Int, val srcH: Int, val scaled: Boolean)

private fun decodeImage(
    context: Context,
    uri: Uri,
    fmt: String,
    canceled: AtomicBoolean,
): DecodedImage {
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

private fun flattenOnWhiteInPlace(bitmap: Bitmap) {
    if (!bitmap.hasAlpha()) return
    if (!bitmap.isMutable) return
    Canvas(bitmap).drawColor(android.graphics.Color.WHITE, PorterDuff.Mode.DST_OVER)
}

private fun writeBmp(bitmap: Bitmap, out: OutputStream, canceled: AtomicBoolean) {
    val w = bitmap.width
    val h = bitmap.height
    val rowSize = (w * 3 + 3) / 4 * 4
    val dataSize = rowSize.toLong() * h
    val fileSize = 54L + dataSize
    if (fileSize > Int.MAX_VALUE) {
        throw ConvertException("这张图转成 BMP 会超过 2 GB，请改选 PNG 或 JPG。")
    }
    val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
    header.put('B'.code.toByte()).put('M'.code.toByte())
    header.putInt(fileSize.toInt()).putInt(0).putInt(54)
    header.putInt(40).putInt(w).putInt(h).putShort(1).putShort(24)
    header.putInt(0).putInt(dataSize.toInt()).putInt(2835).putInt(2835).putInt(0).putInt(0)
    out.write(header.array())

    val pixels = IntArray(w)
    val row = ByteArray(rowSize)
    for (y in h - 1 downTo 0) {
        if (canceled.get()) throw ConvertCanceledException()
        bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
        var p = 0
        for (x in 0 until w) {
            val c = pixels[x]
            row[p++] = c.toByte()
            row[p++] = (c shr 8).toByte()
            row[p++] = (c shr 16).toByte()
        }
        out.write(row)
    }
}

internal fun writeImage(bitmap: Bitmap, fmt: String, out: OutputStream, canceled: AtomicBoolean) {
    when (fmt) {
        "BMP" -> writeBmp(bitmap, out, canceled)
        "JPG" -> if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw IOException("编码 JPG 失败")
        "PNG" -> if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("编码 PNG 失败")
        "WEBP" -> {
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            if (!bitmap.compress(format, 95, out)) throw IOException("编码 WEBP 失败")
        }
        else -> throw IOException("不支持的格式：$fmt")
    }
}

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

internal fun saveImageToGallery(
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
        runCatching { resolver.delete(uri, null, null) }
        throw e
    }
}

internal class ImageResult(val where: String, val notice: String?)

internal fun convertImageToFormat(
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
            "原图 ${decoded.srcW}×${decoded.srcH} 超出当前可用内存，已缩小到 ${bitmap.width}×${bitmap.height} 后转换。"
        } else {
            null
        }
        return ImageResult("$where（${humanSize(bytes)}）", notice)
    } finally {
        bitmap.recycle()
    }
}

/** 把图片规范成 FFmpeg 一定能读取的 PNG 临时文件；同时应用 EXIF 旋转并限制超大图。 */
internal fun prepareLoopImage(
    context: Context,
    uri: Uri,
    dir: File,
    maxSide: Int,
    canceled: AtomicBoolean,
): File {
    if (canceled.get()) throw ConvertCanceledException()
    var bitmap = ImageOps.uriToBitmap(context, uri, maxSide)
        ?: throw ConvertException("无法解码这张图片，或图片格式不受系统支持")
    val out = File(dir, "conv_img_${System.nanoTime()}.png")
    try {
        if (canceled.get()) throw ConvertCanceledException()
        if (bitmap.hasAlpha()) {
            val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(flattened).apply {
                drawColor(android.graphics.Color.WHITE)
                drawBitmap(bitmap, 0f, 0f, null)
            }
            bitmap.recycle()
            bitmap = flattened
        }
        out.outputStream().use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IOException("PNG 编码失败")
            }
        }
        return out
    } catch (e: Exception) {
        out.delete()
        throw e
    } finally {
        bitmap.recycle()
    }
}

internal fun saveBitmapToGallery(
    context: Context,
    bitmap: Bitmap,
    fmt: String,
    displayName: String,
    canceled: AtomicBoolean,
): String {
    if (fmt == "JPG" || fmt == "BMP") flattenOnWhiteInPlace(bitmap)
    val (where, bytes) = saveImageToGallery(context, displayName, imageMime(fmt)) { out ->
        writeImage(bitmap, fmt, out, canceled)
    }
    return "$where（${humanSize(bytes)}）"
}
