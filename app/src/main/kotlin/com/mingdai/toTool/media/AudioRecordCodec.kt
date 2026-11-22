package com.mingdai.toTool.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 「图片唱片」：把音频字节 Base64 后藏进 PNG 的 RGB 通道。
 *
 * 这是娱乐功能，不是加密；任何人拿到 PNG 都能还原音频。
 * 只支持 PNG，JPEG/WebP/截图/缩放都会破坏像素数据。
 */
internal object AudioRecordCodec {
    const val MAX_AUDIO_BYTES = 8 * 1024 * 1024
    const val MAX_RECORD_PIXELS = 12_000_000L
    const val MAX_DIMENSION = 8192

    private const val VERSION = 1
    private const val FLAG_BASE64 = 1
    private const val HEADER_FIXED_BYTES = 76
    private const val NAME_LIMIT = 255
    private const val MIME_LIMIT = 64
    private const val MAX_BITMAP_DIMENSION = 32766
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'T'.code.toByte(), 'R'.code.toByte(), 'C'.code.toByte())

    data class Layout(val width: Int, val height: Int, val headerBytes: Int, val totalPixels: Int)

    data class RecordMeta(val name: String, val mime: String, val originalSize: Long)

    class EncodedRecord(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    )

    class DecodedRecord(val meta: RecordMeta, val audioBytes: ByteArray)

    class RecordException(message: String) : Exception(message)

    fun layoutFor(audioBytes: Int, name: String, mime: String): Layout {
        if (audioBytes <= 0) throw RecordException("音频文件是空的")
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val mimeBytes = mime.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > NAME_LIMIT) throw RecordException("文件名太长（最多 $NAME_LIMIT 字节）")
        if (mimeBytes.size > MIME_LIMIT) throw RecordException("音频 MIME 类型太长")
        val headerBytes = align3(HEADER_FIXED_BYTES + nameBytes.size + mimeBytes.size)
        val base64Chars = 4L * ((audioBytes + 2L) / 3L)
        val totalPixels = headerBytes / 3L + ((base64Chars + 2L) / 3L)
        if (totalPixels > Int.MAX_VALUE) {
            throw RecordException("音频太大，生成的图片像素数超出 Android Bitmap 上限")
        }
        val width = ceil(sqrt(totalPixels.toDouble())).toInt().coerceAtLeast(1)
        val height = ceil(totalPixels.toDouble() / width).toInt().coerceAtLeast(1)
        if (width > MAX_BITMAP_DIMENSION || height > MAX_BITMAP_DIMENSION ||
            width.toLong() * height > Int.MAX_VALUE
        ) {
            throw RecordException("生成的图片尺寸超出 Android Bitmap 上限")
        }
        return Layout(width, height, headerBytes, totalPixels.toInt())
    }

    fun encode(
        audioBytes: ByteArray,
        name: String,
        mime: String,
    ): EncodedRecord {
        val layout = layoutFor(audioBytes.size, name, mime)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val mimeBytes = mime.toByteArray(Charsets.UTF_8)
        val base64 = Base64.getEncoder().encode(audioBytes)
        val header = ByteArray(layout.headerBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(audioBytes)
        ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC)
            put(VERSION.toByte())
            put(FLAG_BASE64.toByte())
            putShort(layout.headerBytes.toShort())
            putInt(layout.width)
            putInt(layout.height)
            putLong(audioBytes.size.toLong())
            putLong(base64.size.toLong())
            put(digest)
            putShort(nameBytes.size.toShort())
            putShort(mimeBytes.size.toShort())
            putLong(audioBytes.size.toLong())
            put(nameBytes)
            put(mimeBytes)
        }

        val pixels = IntArray(layout.width * layout.height) { OPAQUE_WHITE }
        val headerPixels = layout.headerBytes / 3
        for (i in 0 until headerPixels) {
            val offset = i * 3
            pixels[i] = argb(header[offset].toInt(), header[offset + 1].toInt(), header[offset + 2].toInt())
        }
        val payloadStart = headerPixels
        var source = 0
        var pixelIndex = payloadStart
        while (source < base64.size) {
            val b0 = base64[source].toInt() and 0xFF
            val b1 = if (source + 1 < base64.size) base64[source + 1].toInt() and 0xFF else 0
            val b2 = if (source + 2 < base64.size) base64[source + 2].toInt() and 0xFF else 0
            pixels[pixelIndex++] = argb(b0, b1, b2)
            source += 3
        }
        return EncodedRecord(layout.width, layout.height, pixels)
    }

    fun decode(pixels: IntArray, width: Int, height: Int): DecodedRecord {
        if (width <= 0 || height <= 0 || width.toLong() * height > Int.MAX_VALUE) {
            throw RecordException("图片尺寸超出 Android Bitmap 上限")
        }
        if (pixels.size < width * height || pixels.size < 3) throw RecordException("图片数据不完整")
        val firstPixels = readBytes(pixels, 0, 8)
        if (firstPixels.size < 8) throw RecordException("图片唱片头部不完整")
        val headerBytes = ((firstPixels[6].toInt() and 0xFF) shl 8) or (firstPixels[7].toInt() and 0xFF)
        if (headerBytes < HEADER_FIXED_BYTES || headerBytes % 3 != 0 || headerBytes > 65535) {
            throw RecordException("图片唱片头部无效")
        }
        val headerPixels = headerBytes / 3
        if (headerBytes > pixels.size * 3 || headerPixels > pixels.size) {
            throw RecordException("图片唱片头部超出图片范围")
        }
        val header = readBytes(pixels, 0, headerBytes)
        if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw RecordException("这不是图片唱片（缺少 TTRC 标记）")
        }
        if (header[4].toInt() != VERSION) throw RecordException("不支持的图片唱片版本")
        if ((header[5].toInt() and FLAG_BASE64) == 0) throw RecordException("图片唱片数据格式无效")

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buffer.position(8)
        val storedWidth = buffer.int
        val storedHeight = buffer.int
        val audioBytes = buffer.long
        val base64Chars = buffer.long
        val digest = ByteArray(32).also { buffer.get(it) }
        val nameLen = buffer.short.toInt() and 0xFFFF
        val mimeLen = buffer.short.toInt() and 0xFFFF
        val originalSize = buffer.long
        if (storedWidth != width || storedHeight != height) {
            throw RecordException("图片尺寸与唱片记录不一致")
        }
        if (audioBytes <= 0L || audioBytes > Int.MAX_VALUE) {
            throw RecordException("图片唱片里的音频长度无效")
        }
        if (base64Chars <= 0L || base64Chars > Int.MAX_VALUE ||
            nameLen > NAME_LIMIT || mimeLen > MIME_LIMIT) {
            throw RecordException("图片唱片头部无效")
        }
        val stringStart = 76
        val stringEnd = stringStart + nameLen + mimeLen
        if (stringEnd > header.size) throw RecordException("图片唱片头部无效")
        val name = header.copyOfRange(stringStart, stringStart + nameLen).toString(Charsets.UTF_8)
        val mime = header.copyOfRange(stringStart + nameLen, stringEnd).toString(Charsets.UTF_8)

        val neededPixels = headerPixels + ((base64Chars + 2L) / 3L)
        if (neededPixels > pixels.size) throw RecordException("图片唱片数据不完整")
        val base64Bytes = ByteArray(base64Chars.toInt())
        var target = 0
        var pixelIndex = headerPixels
        while (target < base64Bytes.size) {
            val pixel = pixels[pixelIndex++]
            base64Bytes[target++] = ((pixel shr 16) and 0xFF).toByte()
            if (target < base64Bytes.size) base64Bytes[target++] = ((pixel shr 8) and 0xFF).toByte()
            if (target < base64Bytes.size) base64Bytes[target++] = (pixel and 0xFF).toByte()
        }
        val decoded = try {
            Base64.getDecoder().decode(base64Bytes)
        } catch (e: IllegalArgumentException) {
            throw RecordException("图片唱片数据不完整")
        }
        if (decoded.size.toLong() != audioBytes) {
            throw RecordException("图片唱片数据长度不一致")
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(decoded)
        if (!actual.contentEquals(digest)) {
            throw RecordException("图片被压缩或编辑过，音频无法完整还原")
        }
        return DecodedRecord(RecordMeta(name, mime, originalSize), decoded)
    }

    private fun readBytes(pixels: IntArray, startPixel: Int, count: Int): ByteArray {
        val result = ByteArray(count)
        for (i in 0 until count) {
            val pixel = pixels[startPixel + i / 3]
            result[i] = when (i % 3) {
                0 -> ((pixel shr 16) and 0xFF).toByte()
                1 -> ((pixel shr 8) and 0xFF).toByte()
                else -> (pixel and 0xFF).toByte()
            }
        }
        return result
    }

    private fun align3(value: Int): Int = (value + 2) / 3 * 3

    private fun argb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
}
