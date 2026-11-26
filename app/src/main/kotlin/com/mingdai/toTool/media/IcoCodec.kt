package com.mingdai.toTool.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 最小的 ICO 读写实现。
 *
 * 解码：优先读取目录里最大的条目；PNG 压缩条目直接交给 BitmapFactory，
 * 传统 DIB 条目支持 BI_RGB 的 32/24/16/8/4/1 位以及 1 位 AND 透明掩码。
 * 编码：生成 16/24/32/48/64/128/256 七个尺寸，统一使用 PNG 压缩条目（Vista+ 支持）。
 */
internal object IcoCodec {
    private const val MAX_FILE_BYTES = 32 * 1024 * 1024
    private const val MAX_DIMENSION = 4096
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
    )
    private val ICON_SIZES = listOf(16, 24, 32, 48, 64, 128, 256)

    internal data class IcoSize(val width: Int, val height: Int)

    private data class Entry(
        val width: Int,
        val height: Int,
        val bytesInRes: Int,
        val imageOffset: Int,
    )

    fun probe(context: Context, uri: Uri): IcoSize? {
        val bytes = readAll(context, uri) ?: return null
        val best = bestEntry(bytes) ?: return null
        return IcoSize(best.width, best.height)
    }

    fun decode(context: Context, uri: Uri): Bitmap? {
        val bytes = readAll(context, uri) ?: return null
        val entry = bestEntry(bytes) ?: return null
        if (entry.imageOffset < 0 || entry.bytesInRes <= 0 ||
            entry.imageOffset.toLong() + entry.bytesInRes > bytes.size
        ) {
            return null
        }
        val data = bytes.copyOfRange(entry.imageOffset, entry.imageOffset + entry.bytesInRes)
        return if (isPng(data)) {
            decodePng(data)
        } else {
            decodeDib(data)
        }
    }

    fun encode(bitmap: Bitmap, out: OutputStream, canceled: AtomicBoolean) {
        val payloads = ArrayList<Pair<Int, ByteArray>>(ICON_SIZES.size)
        for (size in ICON_SIZES) {
            if (canceled.get()) throw ConvertCanceledException()
            val icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(icon)
                canvas.drawColor(Color.TRANSPARENT)
                val scale = min(
                    size.toFloat() / bitmap.width.coerceAtLeast(1),
                    size.toFloat() / bitmap.height.coerceAtLeast(1),
                )
                val drawWidth = (bitmap.width * scale).roundToInt().coerceIn(1, size)
                val drawHeight = (bitmap.height * scale).roundToInt().coerceIn(1, size)
                val left = (size - drawWidth) / 2
                val top = (size - drawHeight) / 2
                canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(left, top, left + drawWidth, top + drawHeight),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                val bytes = ByteArrayOutputStream()
                if (!icon.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                    throw IOException("ICO 编码失败")
                }
                payloads += size to bytes.toByteArray()
            } finally {
                icon.recycle()
            }
        }

        val headerSize = 6 + 16 * payloads.size
        val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0.toShort())
        header.putShort(1.toShort())
        header.putShort(payloads.size.toShort())
        var offset = headerSize
        for ((size, png) in payloads) {
            val sizeByte = if (size >= 256) 0.toByte() else size.toByte()
            header.put(sizeByte)
            header.put(sizeByte)
            header.put(0.toByte())
            header.put(0.toByte())
            header.putShort(1.toShort())
            header.putShort(32.toShort())
            header.putInt(png.size)
            header.putInt(offset)
            offset += png.size
        }
        out.write(header.array())
        payloads.forEach { (_, png) -> out.write(png) }
    }

    private fun readAll(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_FILE_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun bestEntry(bytes: ByteArray): Entry? {
        if (bytes.size < 6) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val reserved = buffer.short.toInt()
        val type = buffer.short.toInt()
        val count = buffer.short.toInt() and 0xFFFF
        if (reserved != 0 || type != 1 || count <= 0) return null
        if (6 + count * 16 > bytes.size) return null
        var best: Entry? = null
        for (i in 0 until count) {
            val base = 6 + i * 16
            val width = (bytes[base].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val height = (bytes[base + 1].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val bytesInRes = buffer.getInt(base + 8)
            val imageOffset = buffer.getInt(base + 12)
            if (bytesInRes <= 0 || imageOffset < 0) continue
            if (imageOffset.toLong() + bytesInRes > bytes.size) continue
            val entry = Entry(width, height, bytesInRes, imageOffset)
            if (best == null || width.toLong() * height > best.width.toLong() * best.height) {
                best = entry
            }
        }
        return best
    }

    private fun isPng(data: ByteArray): Boolean =
        data.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { data[it] == PNG_SIGNATURE[it] }

    private fun decodePng(data: ByteArray): Bitmap? {
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        if (decoded.config == Bitmap.Config.ARGB_8888) return decoded
        val converted = decoded.copy(Bitmap.Config.ARGB_8888, false)
        if (converted !== decoded) decoded.recycle()
        return converted
    }

    private fun decodeDib(data: ByteArray): Bitmap? {
        if (data.size < 40) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buffer.getInt(0)
        if (headerSize < 40 || headerSize > data.size) return null
        val width = buffer.getInt(4)
        val rawHeight = buffer.getInt(8)
        val planes = buffer.getShort(12).toInt() and 0xFFFF
        val bitCount = buffer.getShort(14).toInt() and 0xFFFF
        val compression = buffer.getInt(16)
        if (width <= 0 || width > MAX_DIMENSION || rawHeight == 0) return null
        if (planes != 1 || compression != 0) return null
        if (bitCount !in setOf(1, 4, 8, 16, 24, 32)) return null
        val topDown = rawHeight < 0
        val height = abs(rawHeight) / 2
        if (height <= 0 || height > MAX_DIMENSION) return null

        val colorsUsed = if (headerSize >= 36) buffer.getInt(32) else 0
        val paletteCount = if (bitCount <= 8) {
            if (colorsUsed in 1..256) colorsUsed else 1 shl bitCount
        } else {
            0
        }
        val paletteStart = headerSize
        val pixelStart = paletteStart + paletteCount * 4
        val xorStride = ((width * bitCount + 31) / 32) * 4
        val andStride = ((width + 31) / 32) * 4
        val xorEnd = pixelStart.toLong() + xorStride.toLong() * height
        if (xorEnd > data.size) return null
        val andStart = xorEnd.toInt()
        val hasMask = andStart.toLong() + andStride.toLong() * height <= data.size

        val palette = IntArray(paletteCount)
        for (i in palette.indices) {
            val p = paletteStart + i * 4
            if (p + 3 >= data.size) return null
            val b = data[p].toInt() and 0xFF
            val g = data[p + 1].toInt() and 0xFF
            val r = data[p + 2].toInt() and 0xFF
            palette[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val pixels = IntArray(width * height)
        var anyAlpha = false
        for (row in 0 until height) {
            val sourceRow = if (topDown) row else height - 1 - row
            val rowStart = pixelStart + sourceRow * xorStride
            val maskRow = andStart + sourceRow * andStride
            for (x in 0 until width) {
                val pixel = when (bitCount) {
                    32 -> {
                        val p = rowStart + x * 4
                        val b = data[p].toInt() and 0xFF
                        val g = data[p + 1].toInt() and 0xFF
                        val r = data[p + 2].toInt() and 0xFF
                        val a = data[p + 3].toInt() and 0xFF
                        if (a != 0) anyAlpha = true
                        (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    24 -> {
                        val p = rowStart + x * 3
                        val b = data[p].toInt() and 0xFF
                        val g = data[p + 1].toInt() and 0xFF
                        val r = data[p + 2].toInt() and 0xFF
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    16 -> {
                        val p = rowStart + x * 2
                        val v = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
                        val r = ((v shr 10) and 0x1F) * 255 / 31
                        val g = ((v shr 5) and 0x1F) * 255 / 31
                        val b = (v and 0x1F) * 255 / 31
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    8 -> {
                        val index = data[rowStart + x].toInt() and 0xFF
                        palette.getOrElse(index) { 0xFF000000.toInt() }
                    }
                    4 -> {
                        val byte = data[rowStart + x / 2].toInt() and 0xFF
                        val index = if (x % 2 == 0) (byte shr 4) and 0x0F else byte and 0x0F
                        palette.getOrElse(index) { 0xFF000000.toInt() }
                    }
                    else -> {
                        val byte = data[rowStart + x / 8].toInt() and 0xFF
                        val bit = (byte shr (7 - (x % 8))) and 1
                        palette.getOrElse(bit) { 0xFF000000.toInt() }
                    }
                }
                val transparent = hasMask &&
                    (((data[maskRow + x / 8].toInt() and 0xFF) shr (7 - (x % 8))) and 1) == 1
                pixels[row * width + x] = when {
                    transparent -> pixel and 0x00FFFFFF
                    bitCount == 32 -> pixel
                    else -> pixel
                }
            }
        }

        // 32 位 DIB 有时 alpha 全为 0，只靠 AND 掩码表达透明；此时把非透明像素补成不透明。
        if (bitCount == 32 && !anyAlpha) {
            for (row in 0 until height) {
                val sourceRow = if (topDown) row else height - 1 - row
                val maskRow = andStart + sourceRow * andStride
                for (x in 0 until width) {
                    val maskBit = if (!hasMask) 0 else
                        ((data[maskRow + x / 8].toInt() and 0xFF) shr (7 - (x % 8))) and 1
                    if (maskBit == 0) {
                        val index = row * width + x
                        pixels[index] = pixels[index] or (0xFF shl 24)
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
