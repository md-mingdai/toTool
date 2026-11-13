package com.mingdai.toTool.barcode.hanxin

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * 汉信码的数据段编码 / 解码。
 * 本实现使用数字、文本和 UTF-8 二进制三种模式（中文按 UTF-8 + ECI 26 存储），
 * 足够覆盖工具里的生成与扫描；完整的 GB18030 压缩模式不在范围内。
 */
internal object HanXinPayloadCodec {

    fun encodeText(text: String): ByteArray {
        if (text.isEmpty()) throw IllegalArgumentException("内容不能为空")
        val numeric = text.all { it in '0'..'9' }
        val textMode = !numeric && text.all { it.code <= 127 && isTextCharacter(it.code) }
        return when {
            numeric -> encodeNumeric(text)
            textMode -> encodeTextMode(text)
            else -> encodeBinary(text.toByteArray(Charsets.UTF_8))
        }
    }

    fun decode(data: ByteArray): String? {
        val reader = BitReader(data)
        val pending = ByteArrayOutputStream()
        val result = StringBuilder()
        var charset: Charset = Charsets.ISO_8859_1

        fun flush() {
            if (pending.size() == 0) return
            val bytes = pending.toByteArray()
            pending.reset()
            result.append(String(bytes, charset))
        }

        while (reader.remaining >= 4) {
            val mode = reader.read(4) ?: return null
            when (mode) {
                0 -> break
                8 -> {
                    val eci = readEci(reader) ?: return null
                    flush()
                    charset = charsetForEci(eci)
                }

                1 -> if (!decodeNumeric(reader, pending)) return null
                2 -> if (!decodeText(reader, pending)) return null
                3 -> if (!decodeBinary(reader, pending)) return null
                else -> return null
            }
        }
        flush()
        return result.toString()
    }

    private class BitWriter {
        private val values = ArrayList<Boolean>()

        fun append(value: Int, count: Int) {
            for (bit in count - 1 downTo 0) {
                values.add((value and (1 shl bit)) != 0)
            }
        }

        fun toBytes(): ByteArray {
            val result = ByteArray((values.size + 7) / 8)
            for (i in values.indices) {
                if (values[i]) {
                    result[i shr 3] = (result[i shr 3].toInt() or (0x80 shr (i and 7))).toByte()
                }
            }
            return result
        }
    }

    private class BitReader(private val bytes: ByteArray) {
        private var position = 0
        val remaining: Int get() = bytes.size * 8 - position

        fun read(count: Int): Int? {
            if (count < 0 || count > 24 || remaining < count) return null
            var value = 0
            repeat(count) {
                val bit = (bytes[position shr 3].toInt() shr (7 - (position and 7))) and 1
                value = (value shl 1) or bit
                position++
            }
            return value
        }
    }

    private fun appendEci(writer: BitWriter, assignment: Int) {
        writer.append(8, 4)
        when {
            assignment <= 127 -> writer.append(assignment, 8)
            assignment <= 16383 -> {
                writer.append(2, 2)
                writer.append(assignment, 14)
            }

            else -> {
                writer.append(6, 3)
                writer.append(assignment, 21)
            }
        }
    }

    private fun encodeNumeric(text: String): ByteArray {
        val writer = BitWriter()
        writer.append(1, 4)
        var lastCount = 0
        var i = 0
        while (i < text.length) {
            lastCount = minOf(3, text.length - i)
            var value = 0
            for (j in 0 until lastCount) value = value * 10 + (text[i + j] - '0')
            writer.append(value, 10)
            i += lastCount
        }
        writer.append(1020 + lastCount, 10)
        return writer.toBytes()
    }

    private fun encodeTextMode(text: String): ByteArray {
        val writer = BitWriter()
        writer.append(2, 4)
        var submode = 1
        for (ch in text) {
            val value = ch.code
            val next = if (isText1(value)) 1 else 2
            if (next != submode) {
                writer.append(62, 6)
                submode = next
            }
            writer.append(if (submode == 1) text1(value) else text2(value), 6)
        }
        writer.append(63, 6)
        return writer.toBytes()
    }

    private fun encodeBinary(bytes: ByteArray): ByteArray {
        if (bytes.size > 8191) throw IllegalArgumentException("内容过长")
        val writer = BitWriter()
        appendEci(writer, 26) // UTF-8
        writer.append(3, 4)
        writer.append(bytes.size, 13)
        for (byte in bytes) writer.append(byte.toInt() and 0xFF, 8)
        return writer.toBytes()
    }

    private fun readEci(reader: BitReader): Int? {
        val first = reader.read(1) ?: return null
        if (first == 0) return reader.read(7)
        val second = reader.read(1) ?: return null
        if (second == 0) return reader.read(14)
        val third = reader.read(1) ?: return null
        if (third != 0) return null
        return reader.read(21)?.takeIf { it <= 999999 }
    }

    private fun charsetForEci(eci: Int): Charset = when (eci) {
        3 -> Charsets.ISO_8859_1
        20 -> runCatching { Charset.forName("Shift_JIS") }.getOrDefault(Charsets.UTF_8)
        25 -> runCatching { Charset.forName("UTF-16BE") }.getOrDefault(Charsets.UTF_8)
        26 -> Charsets.UTF_8
        28 -> runCatching { Charset.forName("Big5") }.getOrDefault(Charsets.UTF_8)
        29 -> runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8)
        else -> Charsets.ISO_8859_1
    }

    private fun decodeNumeric(reader: BitReader, out: ByteArrayOutputStream): Boolean {
        val values = ArrayList<Int>()
        while (true) {
            val value = reader.read(10) ?: return false
            if (value in 1021..1023) {
                if (values.isEmpty()) return false
                for (i in values.indices) {
                    val digits = if (i == values.size - 1) value - 1020 else 3
                    val text = values[i].toString().padStart(digits, '0')
                    if (text.length != digits) return false
                    for (ch in text) out.write(ch.code)
                }
                return true
            }
            if (value > 999) return false
            values.add(value)
        }
    }

    private fun decodeText(reader: BitReader, out: ByteArrayOutputStream): Boolean {
        var submode = 1
        while (true) {
            val value = reader.read(6) ?: return false
            if (value == 63) return true
            if (value == 62) {
                submode = if (submode == 1) 2 else 1
                continue
            }
            val character = if (submode == 1) decodeText1(value) else decodeText2(value)
            if (character < 0) return false
            out.write(character)
        }
    }

    private fun decodeBinary(reader: BitReader, out: ByteArrayOutputStream): Boolean {
        val count = reader.read(13) ?: return false
        if (count < 1 || reader.remaining < count * 8) return false
        for (i in 0 until count) {
            val value = reader.read(8) ?: return false
            out.write(value)
        }
        return true
    }

    private fun isTextCharacter(value: Int): Boolean = isText1(value) || text2(value) >= 0

    private fun isText1(value: Int): Boolean =
        value in '0'.code..'9'.code || value in 'A'.code..'Z'.code || value in 'a'.code..'z'.code

    private fun text1(value: Int): Int = when {
        value <= '9'.code -> value - '0'.code
        value <= 'Z'.code -> value - 'A'.code + 10
        else -> value - 'a'.code + 36
    }

    private fun text2(value: Int): Int = when {
        value <= 27 -> value
        value in 32..47 -> value - 32 + 28
        value in 58..64 -> value - 58 + 44
        value in 91..96 -> value - 91 + 51
        value in 123..127 -> value - 123 + 57
        else -> -1
    }

    private fun decodeText1(value: Int): Int = when {
        value <= 9 -> value + '0'.code
        value <= 35 -> value - 10 + 'A'.code
        value <= 61 -> value - 36 + 'a'.code
        else -> -1
    }

    private fun decodeText2(value: Int): Int = when {
        value <= 27 -> value
        value <= 43 -> value - 28 + 32
        value <= 50 -> value - 44 + 58
        value <= 56 -> value - 51 + 91
        value <= 61 -> value - 57 + 123
        else -> -1
    }
}
