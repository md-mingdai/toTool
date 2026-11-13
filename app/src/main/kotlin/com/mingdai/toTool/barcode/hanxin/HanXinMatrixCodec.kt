package com.mingdai.toTool.barcode.hanxin

import com.google.zxing.common.BitMatrix
import com.google.zxing.common.reedsolomon.GenericGF
import com.google.zxing.common.reedsolomon.ReedSolomonDecoder
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder
import com.google.zxing.common.reedsolomon.ReedSolomonException

/**
 * 汉信码矩阵的排布、掩码和 Reed-Solomon 纠错。
 * 依据 Zint 的 hanxin.c（BSD-3-Clause）移植，行/列坐标与参考实现保持一致。
 */
internal object HanXinMatrixCodec {

    private val dataField = GenericGF(0x163, 256, 1)
    private const val FUNCTION = 0x10

    data class Decoded(
        val data: ByteArray,
        val version: Int,
        val eccLevel: Int,
        val mask: Int,
    )

    fun encode(data: ByteArray, version: Int, eccLevel: Int, mask: Int): BitMatrix {
        val size = version * 2 + 21
        val grid = setupGrid(version)
        val capacity = HanXinTables.dataCodewords(version, eccLevel)
        val datastream = ByteArray(capacity)
        System.arraycopy(data, 0, datastream, 0, minOf(data.size, capacity))
        val fullstream = addEcc(datastream, version, eccLevel)
        val picket = makePicketFence(fullstream)

        var bit = 0
        for (i in grid.indices) {
            if (grid[i] != 0) continue
            if (bit >= picket.size * 8) break
            if ((picket[bit shr 3].toInt() and (0x80 shr (bit and 7))) != 0) {
                grid[i] = 0x01
            }
            bit++
        }
        applyMask(grid, size, mask)
        setFunctionInfo(grid, size, version, eccLevel, mask)
        return toMatrix(grid, size)
    }

    fun evaluate(matrix: BitMatrix): Int {
        val size = matrix.width
        var result = 0
        for (x in 0 until size) {
            var y = 0
            while (y <= size - 7) {
                if (matrix[x, y] && matrix[x, y + 1] != matrix[x, y + 5] &&
                    matrix[x, y + 2] && !matrix[x, y + 3] && matrix[x, y + 4] && matrix[x, y + 6]
                ) {
                    if (hasThreeLightBefore(matrix, x, y, true) ||
                        hasThreeLightAfter(matrix, x, y + 7, true)
                    ) {
                        result += 50
                    }
                    y++
                }
                y++
            }
        }
        for (y in 0 until size) {
            var x = 0
            while (x <= size - 7) {
                if (matchesFinderLike(matrix, x, y)) {
                    if (hasThreeLightBefore(matrix, x, y, false) ||
                        hasThreeLightAfter(matrix, x + 7, y, false)
                    ) {
                        result += 50
                    }
                    x++
                }
                x++
            }
        }
        for (x in 0 until size) result += runPenalty(matrix, x, true)
        for (y in 0 until size) result += runPenalty(matrix, y, false)
        return result
    }

    fun tryDecode(modules: BitMatrix): Decoded? {
        if (modules.width != modules.height) return null
        val size = modules.width
        if (size < 23 || size > 189 || ((size - 21) and 1) != 0) return null
        val version = (size - 21) / 2
        val info = tryReadFunctionInfo(modules, version) ?: return null

        val grid = setupGrid(version)
        val unmasked = BitMatrix(size, size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (modules[x, y]) unmasked.set(x, y)
            }
        }
        applyMask(unmasked, grid, info.second)

        val total = HanXinTables.totalCodewords(version)
        val picket = ByteArray(total)
        var bit = 0
        for (i in grid.indices) {
            if (grid[i] != 0) continue
            if (bit >= total * 8) break
            if (unmasked[i % size, i / size]) {
                val index = bit shr 3
                picket[index] = (picket[index].toInt() or (0x80 shr (bit and 7))).toByte()
            }
            bit++
        }
        if (bit != total * 8) return null

        val fullstream = undoPicketFence(picket)
        val data = correctAndExtract(fullstream, version, info.first) ?: return null
        return Decoded(data, version, info.first, info.second)
    }

    /* ---------------------------- 数据区与纠错 ---------------------------- */

    private fun addEcc(datastream: ByteArray, version: Int, eccLevel: Int): ByteArray {
        val full = ByteArray(HanXinTables.totalCodewords(version))
        var input = 0
        var output = 0
        for (batch in 0 until 3) {
            val (count, dataLength, eccLength) = HanXinTables.blockBatch(version, eccLevel, batch)
            repeat(count) {
                val block = IntArray(dataLength + eccLength)
                for (i in 0 until dataLength) {
                    block[i] = if (input < datastream.size) datastream[input].toInt() and 0xFF else 0
                    full[output++] = block[i].toByte()
                    input++
                }
                ReedSolomonEncoder(dataField).encode(block, eccLength)
                for (i in 0 until eccLength) {
                    full[output++] = block[dataLength + i].toByte()
                }
            }
        }
        return full
    }

    private fun correctAndExtract(fullstream: ByteArray, version: Int, eccLevel: Int): ByteArray? {
        val data = ByteArray(HanXinTables.dataCodewords(version, eccLevel))
        var input = 0
        var output = 0
        return try {
            for (batch in 0 until 3) {
                val (count, dataLength, eccLength) = HanXinTables.blockBatch(version, eccLevel, batch)
                repeat(count) {
                    val block = IntArray(dataLength + eccLength)
                    for (i in block.indices) block[i] = fullstream[input++].toInt() and 0xFF
                    ReedSolomonDecoder(dataField).decode(block, eccLength)
                    for (i in 0 until dataLength) {
                        if (output < data.size) data[output++] = block[i].toByte()
                    }
                }
            }
            if (input == fullstream.size && output == data.size) data else null
        } catch (e: ReedSolomonException) {
            null
        }
    }

    private fun makePicketFence(full: ByteArray): ByteArray {
        val result = ByteArray(full.size)
        var output = 0
        for (start in 0 until 13) {
            var i = start
            while (i < full.size) {
                result[output++] = full[i]
                i += 13
            }
        }
        return result
    }

    private fun undoPicketFence(picket: ByteArray): ByteArray {
        val result = ByteArray(picket.size)
        var input = 0
        for (start in 0 until 13) {
            var i = start
            while (i < result.size) {
                result[i] = picket[input++]
                i += 13
            }
        }
        return result
    }

    /* ------------------------------- 矩阵布局 ------------------------------- */

    private fun setupGrid(version: Int): IntArray {
        val size = version * 2 + 21
        val grid = IntArray(size * size)
        placeTopLeft(grid, size)
        placeFinder(grid, size, 0, size - 7)
        placeFinder(grid, size, size - 7, 0)
        placeBottomRight(grid, size)
        for (i in 0 until 8) {
            function(grid, size, i, 7)
            function(grid, size, 7, i)
            function(grid, size, size - i - 1, 7)
            function(grid, size, 7, size - i - 1)
            function(grid, size, size - 8, i)
            function(grid, size, i, size - 8)
            function(grid, size, size - i - 1, size - 8)
            function(grid, size, size - 8, size - i - 1)
        }
        for (i in 0 until 9) {
            function(grid, size, i, 8)
            function(grid, size, 8, i)
            function(grid, size, size - i - 1, 8)
            function(grid, size, 8, size - i - 1)
            function(grid, size, size - 9, i)
            function(grid, size, i, size - 9)
            function(grid, size, size - i - 1, size - 9)
            function(grid, size, size - 9, size - i - 1)
        }
        if (version > 3) addAlignment(grid, size, version)
        return grid
    }

    private fun addAlignment(grid: IntArray, size: Int, version: Int) {
        val k = HanXinTables.moduleK(version)
        val r = HanXinTables.moduleR(version)
        val m = HanXinTables.moduleM(version)

        var y = 0
        var moduleY = 0
        do {
            val height = if (moduleY < m) k else r - 1
            if ((moduleY and 1) == 0) {
                if ((m and 1) == 1) plotAssistant(grid, size, 0, y)
            } else {
                if ((m and 1) == 0) plotAssistant(grid, size, 0, y)
                plotAssistant(grid, size, size - 1, y)
            }
            moduleY++
            y += height
        } while (y < size)

        var x = size - 1
        var moduleX = 0
        do {
            val width = if (moduleX < m) k else r - 1
            if ((moduleX and 1) == 0) {
                if ((m and 1) == 1) plotAssistant(grid, size, x, size - 1)
            } else {
                if ((m and 1) == 0) plotAssistant(grid, size, x, size - 1)
                plotAssistant(grid, size, x, 0)
            }
            moduleX++
            x -= width
        } while (x >= 0)

        var columnSwitch = true
        y = 0
        moduleY = 0
        do {
            val height = if (moduleY < m) k else r - 1
            var rowSwitch = columnSwitch
            columnSwitch = !columnSwitch
            x = size - 1
            moduleX = 0
            do {
                val width = if (moduleX < m) k else r - 1
                if (rowSwitch && !(y == 0 && x == size - 1)) {
                    plotAlignment(grid, size, x, y, width, height)
                }
                rowSwitch = !rowSwitch
                moduleX++
                x -= width
            } while (x >= 0)
            moduleY++
            y += height
        } while (y < size)
    }

    private fun placeTopLeft(grid: IntArray, size: Int) =
        placePattern(grid, size, 0, 0, intArrayOf(0x7F, 0x40, 0x5F, 0x50, 0x57, 0x57, 0x57))

    private fun placeFinder(grid: IntArray, size: Int, x: Int, y: Int) =
        placePattern(grid, size, x, y, intArrayOf(0x7F, 0x01, 0x7D, 0x05, 0x75, 0x75, 0x75))

    private fun placeBottomRight(grid: IntArray, size: Int) =
        placePattern(grid, size, size - 7, size - 7, intArrayOf(0x75, 0x75, 0x75, 0x05, 0x7D, 0x01, 0x7F))

    private fun placePattern(grid: IntArray, size: Int, x: Int, y: Int, rows: IntArray) {
        for (py in 0 until 7) {
            for (px in 0 until 7) {
                val dark = (rows[py] and (0x40 shr px)) != 0
                grid[(y + py) * size + x + px] = FUNCTION or if (dark) 1 else 0
            }
        }
    }

    private fun plotAlignment(grid: IntArray, size: Int, x: Int, y: Int, width: Int, height: Int) {
        safePlot(grid, size, x, y, true)
        safePlot(grid, size, x - 1, y + 1, false)
        for (i in 1..width) {
            safePlot(grid, size, x - i, y, true)
            safePlot(grid, size, x - i - 1, y + 1, false)
        }
        for (i in 1 until height) {
            safePlot(grid, size, x, y + i, true)
            safePlot(grid, size, x - 1, y + i + 1, false)
        }
    }

    private fun plotAssistant(grid: IntArray, size: Int, x: Int, y: Int) {
        for (dy in -1..1) {
            for (dx in -1..1) {
                safePlot(grid, size, x + dx, y + dy, dx == 0 && dy == 0)
            }
        }
    }

    private fun safePlot(grid: IntArray, size: Int, x: Int, y: Int, dark: Boolean) {
        if (x < 0 || y < 0 || x >= size || y >= size) return
        val index = y * size + x
        if (grid[index] != 0) return
        grid[index] = FUNCTION or if (dark) 1 else 0
    }

    private fun function(grid: IntArray, size: Int, x: Int, y: Int) {
        if (x < 0 || y < 0 || x >= size || y >= size) return
        grid[y * size + x] = FUNCTION
    }

    private fun setFunctionInfo(grid: IntArray, size: Int, version: Int, eccLevel: Int, mask: Int) {
        val value = ((version + 20) shl 4) or ((eccLevel - 1) shl 2) or mask
        val words = intArrayOf(
            (value shr 8) and 0x0F,
            (value shr 4) and 0x0F,
            value and 0x0F,
            0,
            0,
            0,
            0,
        )
        ReedSolomonEncoder(GenericGF.AZTEC_PARAM).encode(words, 4)
        val bits = BooleanArray(34)
        var position = 0
        for (word in words) {
            for (bit in 3 downTo 0) {
                bits[position++] = (word and (1 shl bit)) != 0
            }
        }
        for (i in 0 until 9) {
            setFunction(grid, size, i, 8, bits[i])
            setFunction(grid, size, size - i - 1, size - 9, bits[i])
            setFunction(grid, size, 8, 8 - i, bits[i + 8])
            setFunction(grid, size, size - 9, size - 9 + i, bits[i + 8])
            setFunction(grid, size, size - 9, i, bits[i + 17])
            setFunction(grid, size, 8, size - 1 - i, bits[i + 17])
            setFunction(grid, size, size - 9 + i, 8, bits[i + 25])
            setFunction(grid, size, 8 - i, size - 9, bits[i + 25])
        }
    }

    private fun setFunction(grid: IntArray, size: Int, x: Int, y: Int, dark: Boolean) {
        if (x < 0 || y < 0 || x >= size || y >= size) return
        grid[y * size + x] = FUNCTION or if (dark) 1 else 0
    }

    private fun tryReadFunctionInfo(matrix: BitMatrix, expectedVersion: Int): Pair<Int, Int>? {
        val size = matrix.width
        val primary = BooleanArray(28)
        val secondary = BooleanArray(28)
        for (i in 0 until 9) {
            primary[i] = matrix[i, 8]
            primary[i + 8] = matrix[8, 8 - i]
            primary[i + 17] = matrix[size - 9, i]
            secondary[i] = matrix[size - i - 1, size - 9]
            secondary[i + 8] = matrix[size - 9, size - 9 + i]
            secondary[i + 17] = matrix[8, size - 1 - i]
        }
        primary[26] = matrix[size - 8, 8]
        primary[27] = matrix[size - 7, 8]
        secondary[26] = matrix[7, size - 9]
        secondary[27] = matrix[6, size - 9]

        val primaryInfo = tryDecodeFunctionInfo(primary, expectedVersion)
        val secondaryInfo = tryDecodeFunctionInfo(secondary, expectedVersion)
        if (primaryInfo == null && secondaryInfo == null) return null
        if (primaryInfo != null && secondaryInfo != null && primaryInfo != secondaryInfo) return null
        return primaryInfo ?: secondaryInfo
    }

    private fun tryDecodeFunctionInfo(bits: BooleanArray, expectedVersion: Int): Pair<Int, Int>? {
        val words = IntArray(7)
        for (i in 0 until 28) {
            if (bits[i]) words[i shr 2] = words[i shr 2] or (1 shl (3 - (i and 3)))
        }
        return try {
            ReedSolomonDecoder(GenericGF.AZTEC_PARAM).decode(words, 4)
            val value = (words[0] shl 8) or (words[1] shl 4) or words[2]
            val version = (value shr 4) - 20
            val eccLevel = ((value shr 2) and 0x03) + 1
            val mask = value and 0x03
            if (version == expectedVersion) eccLevel to mask else null
        } catch (e: ReedSolomonException) {
            null
        }
    }

    private fun applyMask(grid: IntArray, size: Int, mask: Int) {
        if (mask == 0) return
        for (y in 0 until size) {
            for (x in 0 until size) {
                val index = y * size + x
                if ((grid[index] and 0xF0) != 0 && grid[index] != 0x01) continue
                if (grid[index] >= 0x10 || !maskApplies(x, y, mask)) continue
                grid[index] = grid[index] xor 1
            }
        }
    }

    private fun applyMask(matrix: BitMatrix, functionGrid: IntArray, mask: Int) {
        if (mask == 0) return
        val size = matrix.width
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (functionGrid[y * size + x] == 0 && maskApplies(x, y, mask)) {
                    matrix[x, y] = !matrix[x, y]
                }
            }
        }
    }

    private fun maskApplies(x: Int, y: Int, mask: Int): Boolean {
        val i = y + 1
        val j = x + 1
        return when (mask) {
            1 -> ((i + j) and 1) == 0
            2 -> (((i + j) % 3 + j % 3) and 1) == 0
            else -> ((i % j + j % i + i % 3 + j % 3) and 1) == 0
        }
    }

    private fun toMatrix(grid: IntArray, size: Int): BitMatrix {
        val result = BitMatrix(size, size)
        for (i in grid.indices) {
            if ((grid[i] and 1) != 0) result[i % size, i / size] = true
        }
        return result
    }

    /* ------------------------------- 评估函数 ------------------------------- */

    private fun matchesFinderLike(matrix: BitMatrix, x: Int, y: Int): Boolean =
        matrix[x, y] && matrix[x + 2, y] && !matrix[x + 3, y] && matrix[x + 4, y] &&
            matrix[x + 6, y] && matrix[x + 1, y] != matrix[x + 5, y]

    private fun hasThreeLightBefore(matrix: BitMatrix, x: Int, y: Int, vertical: Boolean): Boolean {
        for (offset in 1..3) {
            val position = (if (vertical) y else x) - offset
            if (position < 0) return true
            if (if (vertical) matrix[x, position] else matrix[position, y]) return false
        }
        return true
    }

    private fun hasThreeLightAfter(matrix: BitMatrix, x: Int, y: Int, vertical: Boolean): Boolean {
        for (offset in 0 until 3) {
            val position = (if (vertical) y else x) + offset
            if (position >= matrix.width) return true
            if (if (vertical) matrix[x, position] else matrix[position, y]) return false
        }
        return true
    }

    private fun runPenalty(matrix: BitMatrix, fixedPosition: Int, vertical: Boolean): Int {
        var state = false
        var block = 0
        var penalty = 0
        for (i in 0 until matrix.width) {
            val value = if (vertical) matrix[fixedPosition, i] else matrix[i, fixedPosition]
            if (value == state) {
                block++
            } else {
                if (block >= 3) penalty += block * 4
                block = 1
                state = value
            }
        }
        if (block >= 3) penalty += block * 4
        return penalty
    }
}
