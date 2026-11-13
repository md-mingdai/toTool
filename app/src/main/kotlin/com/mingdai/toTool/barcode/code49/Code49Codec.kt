package com.mingdai.toTool.barcode.code49

import com.google.zxing.common.BitMatrix

/** Code 49 的生成与解码（ASCII / 数字压缩，自动选择行数）。 */
object Code49Codec {

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. \$/+%"

    fun encode(value: String): BitMatrix {
        if (value.isEmpty()) throw IllegalArgumentException("内容不能为空")
        val (data, mode) = encodeData(value)
        if (data.size > 49) throw IllegalArgumentException("内容过长，Code 49 放不下")

        var minimum = (data.size + 6) / 7
        val padding = minimum * 7 - data.size
        if (minimum == 1 || minimum > 6 || padding < 5) minimum++
        if (minimum < 2 || minimum > 8) throw IllegalArgumentException("内容过长，Code 49 放不下")
        val count = minimum

        val words = Array(count) { IntArray(8) }
        for (row in 0 until count) {
            for (at in 0 until 7) {
                val index = row * 7 + at
                words[row][at] = if (index < data.size) data[index] else 48
            }
        }
        words[count - 1][6] = (count - 2) * 7 + mode
        applyChecks(words, count)

        val modules = BitMatrix(70, count * 9 + 1)
        for (y in 0 until modules.height step 9) {
            for (x in 0 until 70) modules.set(x, y)
        }
        for (row in 0 until count) {
            for (repeat in 0 until 8) {
                val y = row * 9 + 1 + repeat
                modules.set(0, y)
                for (pair in 0 until 4) {
                    val valuePair = words[row][pair * 2] * 49 + words[row][pair * 2 + 1]
                    val even = row == count - 1 || Code49Tables.ROW_PARITY[row][pair] == 'E'
                    val pattern = (if (even) Code49Tables.EVEN else Code49Tables.ODD)[valuePair]
                    for (bit in 0 until 16) {
                        if ((pattern and (1 shl (15 - bit))) != 0) {
                            modules.set(2 + pair * 16 + bit, y)
                        }
                    }
                }
                for (x in 66 until 70) modules.set(x, y)
            }
        }
        return modules
    }

    fun decode(modules: BitMatrix): String? {
        if (modules.width != 70 && modules.width != 81) return null
        if (modules.height < 2) return null
        val offset = if (modules.width == 81) 10 else 0

        val rows = ArrayList<Int>()
        for (y in 0 until modules.height) {
            if (y > 0 && sameRow(modules, y, y - 1)) continue
            var separator = true
            for (x in 0 until 70) {
                if (!modules[offset + x, y]) {
                    separator = false
                    break
                }
            }
            if (separator) continue
            // 采样时可能把分隔行混进来；找不到起始 / 终止守卫就跳过这一行
            if (!modules[offset, y] || modules[offset + 1, y]) continue
            var stopOk = true
            for (x in 66 until 70) if (!modules[offset + x, y]) stopOk = false
            if (!stopOk) continue
            rows.add(y)
            if (rows.size > 8) return null
        }
        if (rows.size < 2) return null

        val words = Array(rows.size) { IntArray(8) }
        for (row in rows.indices) {
            val y = rows[row]
            for (pair in 0 until 4) {
                var pattern = 0
                for (bit in 0 until 16) {
                    pattern = (pattern shl 1) or
                        if (modules[offset + 2 + pair * 16 + bit, y]) 1 else 0
                }
                val even = row == rows.size - 1 || Code49Tables.ROW_PARITY[row][pair] == 'E'
                val table = if (even) Code49Tables.EVEN else Code49Tables.ODD
                val value = table.indexOf(pattern)
                if (value < 0) return null
                words[row][pair * 2] = value / 49
                words[row][pair * 2 + 1] = value % 49
            }
        }

        val mode = words[rows.size - 1][6] - (rows.size - 2) * 7
        if (mode !in 0..5) return null
        val expected = Array(rows.size) { words[it].copyOf() }
        applyChecks(expected, rows.size)
        for (row in rows.indices) {
            for (at in 0 until 8) {
                if (words[row][at] != expected[row][at]) return null
            }
        }

        val data = ArrayList<Int>()
        for (row in 0 until rows.size - 1) {
            for (at in 0 until 7) data.add(words[row][at])
        }
        if (rows.size <= 6) {
            data.add(words[rows.size - 1][0])
            data.add(words[rows.size - 1][1])
        }
        while (data.isNotEmpty() && data.last() == 48) data.removeAt(data.size - 1)
        if (mode == 3) return null // 本工具不生成结构化追加
        return decodeData(data, mode)
    }

    private fun sameRow(modules: BitMatrix, first: Int, second: Int): Boolean {
        for (x in 0 until modules.width) {
            if (modules[x, first] != modules[x, second]) return false
        }
        return true
    }

    private fun applyChecks(words: Array<IntArray>, rows: Int) {
        for (row in 0 until rows - 1) {
            var sum = 0
            for (at in 0 until 7) sum += words[row][at]
            words[row][7] = sum % 49
        }
        val last = rows - 1
        var x = words[last][6] * 20
        var y = words[last][6] * 16
        var z = words[last][6] * 38
        var position = 0
        for (row in 0 until last) {
            for (pair in 0 until 4) {
                val value = words[row][pair * 2] * 49 + words[row][pair * 2 + 1]
                x += Code49Tables.X_WEIGHTS[position] * value
                y += Code49Tables.Y_WEIGHTS[position] * value
                z += Code49Tables.Z_WEIGHTS[position++] * value
            }
        }
        if (rows > 6) setPair(words, last, 0, z % 2401)
        val first = words[last][0] * 49 + words[last][1]
        x += Code49Tables.X_WEIGHTS[position] * first
        y += Code49Tables.Y_WEIGHTS[position++] * first
        setPair(words, last, 2, y % 2401)
        x += Code49Tables.X_WEIGHTS[position] * (words[last][2] * 49 + words[last][3])
        setPair(words, last, 4, x % 2401)
        var lastSum = 0
        for (at in 0 until 7) lastSum += words[last][at]
        words[last][7] = lastSum % 49
    }

    private fun setPair(words: Array<IntArray>, row: Int, at: Int, value: Int) {
        words[row][at] = value / 49
        words[row][at + 1] = value % 49
    }

    private fun encodeData(text: String): Pair<List<Int>, Int> {
        val words = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch.code > 127) throw IllegalArgumentException("Code 49 只支持 ASCII 字符")
            var digits = 0
            while (i + digits < text.length && text[i + digits] in '0'..'9') digits++
            if (digits >= 5) {
                words.add(48)
                val end = i + digits
                while (end - i >= 5) {
                    if (end - i == 7) {
                        pack(words, 100000 + number(text, i, 4), 3)
                        pack(words, number(text, i + 4, 3), 2)
                        i += 7
                    } else {
                        pack(words, number(text, i, 5), 3)
                        i += 5
                    }
                }
                when (end - i) {
                    1 -> {
                        words.add(text[i] - '0')
                        i++
                    }

                    3 -> {
                        pack(words, number(text, i, 3), 2)
                        i += 3
                    }

                    4 -> {
                        pack(words, 100000 + number(text, i, 4), 3)
                        i += 4
                    }
                }
                if (i < text.length) words.add(48)
                continue
            }
            val pair = Code49Tables.ASCII[ch.code]
            for (character in pair) {
                words.add(
                    when (character) {
                        '!' -> 43
                        '&' -> 44
                        else -> ALPHABET.indexOf(character).also {
                            if (it < 0) throw IllegalArgumentException("Code 49 不支持的字符：$character")
                        }
                    }
                )
            }
            i++
        }
        val mode = when (words.firstOrNull()) {
            48 -> 2
            43 -> 4
            44 -> 5
            else -> 0
        }
        if (mode != 0) words.removeAt(0)
        return words to mode
    }

    private fun decodeData(words: List<Int>, mode: Int): String? {
        var numeric = mode == 2
        var shift = when (mode) {
            4 -> 43
            5 -> 44
            else -> 0
        }
        val result = StringBuilder()
        var i = if (mode == 3) 1 else 0
        while (i < words.size) {
            if (numeric) {
                var end = i
                while (end < words.size && words[end] != 48) end++
                if (!readNumeric(words, i, end, result)) return null
                i = end
                numeric = false
                if (i < words.size) i++
                continue
            }
            val word = words[i++]
            if (shift != 0) {
                if (word >= ALPHABET.length) return null
                val pair = (if (shift == 43) "!" else "&") + ALPHABET[word]
                val found = Code49Tables.ASCII.indexOf(pair)
                if (found < 0) return null
                result.append(found.toChar())
                shift = 0
            } else when {
                word < ALPHABET.length -> result.append(ALPHABET[word])
                word == 43 || word == 44 -> shift = word
                word == 45 -> {
                    if (i != (if (mode == 3) 2 else 1)) result.append('\u001D')
                }

                word == 46 -> result.append('\u001C')
                word == 47 -> return null
                word == 48 -> numeric = true
                else -> return null
            }
        }
        if (shift != 0) return null
        return result.toString()
    }

    private fun readNumeric(words: List<Int>, start: Int, end: Int, out: StringBuilder): Boolean {
        if (start == end) return false
        val count = end - start
        val triples = count / 3
        for (group in 0 until triples) {
            val at = start + group * 3
            val value = words[at] * 2304 + words[at + 1] * 48 + words[at + 2]
            if (value >= 100000) {
                if (value > 109999 || group != triples - 1 || count % 3 == 1) return false
                out.append((value - 100000).toString().padStart(4, '0'))
            } else {
                out.append(value.toString().padStart(5, '0'))
            }
        }
        val remainder = start + triples * 3
        if (end - remainder == 1) {
            if (words[remainder] > 9) return false
            out.append(('0'.code + words[remainder]).toChar())
        } else if (end - remainder == 2) {
            val value = words[remainder] * 48 + words[remainder + 1]
            if (value > 999) return false
            out.append(value.toString().padStart(3, '0'))
        }
        return true
    }

    private fun number(text: String, offset: Int, count: Int): Int {
        var value = 0
        for (i in 0 until count) value = value * 10 + (text[offset + i] - '0')
        return value
    }

    private fun pack(words: MutableList<Int>, value0: Int, count: Int) {
        var value = value0
        if (count == 3) {
            words.add(value / 2304)
            value %= 2304
        }
        words.add(value / 48)
        words.add(value % 48)
    }
}
