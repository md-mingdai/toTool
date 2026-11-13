package com.mingdai.toTool.barcode.code49

import com.google.zxing.common.BitMatrix
import kotlin.math.max
import kotlin.math.min

/**
 * 从二值图片里采样 Code 49。
 *
 * Code 49 每行固定 70 个模块；暗像素外接框的宽度（或旋转后的高度）就是 70 模块的跨度。
 * 逐行采样后交给解码器，重复行和分隔行会被自动忽略。
 */
object Code49ImageDecoder {

    data class Hit(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    fun decode(binary: BitMatrix): String? = decodeDetailed(binary)?.text

    fun decodeDetailed(binary: BitMatrix): Hit? =
        decodeOrientation(binary) ?: decodeOrientation(invert(binary))

    private fun decodeOrientation(binary: BitMatrix): Hit? {
        val bounds = darkBounds(binary) ?: return null
        val width = bounds.right - bounds.left + 1
        val height = bounds.bottom - bounds.top + 1
        if (width < 10 || height < 4) return null

        sampleHorizontal(binary, bounds)?.let { matrix ->
            tryVariants(matrix)?.let {
                return Hit(it, bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        }
        sampleVertical(binary, bounds)?.let { matrix ->
            tryVariants(matrix)?.let {
                return Hit(it, bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        }
        return null
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun darkBounds(matrix: BitMatrix): Bounds? {
        var left = matrix.width
        var top = matrix.height
        var right = -1
        var bottom = -1
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return null
        return Bounds(left, top, right, bottom)
    }

    /** 旋转 180° 或翻转拍摄时，行序 / 列序会反过来；把四种方向都试一遍。 */
    private fun tryVariants(matrix: BitMatrix): String? {
        Code49Codec.decode(matrix)?.let { return it }
        Code49Codec.decode(reverseColumns(matrix))?.let { return it }
        Code49Codec.decode(reverseRows(matrix))?.let { return it }
        Code49Codec.decode(reverseColumns(reverseRows(matrix)))?.let { return it }
        return null
    }

    private fun reverseColumns(matrix: BitMatrix): BitMatrix {
        val result = BitMatrix(matrix.width, matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[matrix.width - 1 - x, y]) result.set(x, y)
            }
        }
        return result
    }

    private fun reverseRows(matrix: BitMatrix): BitMatrix {
        val result = BitMatrix(matrix.width, matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, matrix.height - 1 - y]) result.set(x, y)
            }
        }
        return result
    }

    private fun sampleHorizontal(matrix: BitMatrix, bounds: Bounds): BitMatrix? {
        val width = bounds.right - bounds.left + 1
        val height = bounds.bottom - bounds.top + 1
        val rowCount = min(height, 400)
        if (rowCount < 2) return null
        val result = BitMatrix(70, rowCount)
        for (row in 0 until rowCount) {
            val py = bounds.top + ((row + 0.5) * height / rowCount).toInt()
                .coerceIn(bounds.top, bounds.bottom)
            for (moduleX in 0 until 70) {
                val px = bounds.left + ((moduleX + 0.5) * width / 70).toInt()
                    .coerceIn(bounds.left, bounds.right)
                if (matrix[px, py]) result.set(moduleX, row)
            }
        }
        return result
    }

    private fun sampleVertical(matrix: BitMatrix, bounds: Bounds): BitMatrix? {
        val width = bounds.right - bounds.left + 1
        val height = bounds.bottom - bounds.top + 1
        val rowCount = min(width, 400)
        if (rowCount < 2) return null
        val result = BitMatrix(70, rowCount)
        for (row in 0 until rowCount) {
            val px = bounds.left + ((row + 0.5) * width / rowCount).toInt()
                .coerceIn(bounds.left, bounds.right)
            for (moduleY in 0 until 70) {
                val py = bounds.top + ((moduleY + 0.5) * height / 70).toInt()
                    .coerceIn(bounds.top, bounds.bottom)
                if (matrix[px, py]) result.set(moduleY, row)
            }
        }
        return result
    }

    private fun invert(matrix: BitMatrix): BitMatrix {
        val result = BitMatrix(matrix.width, matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (!matrix[x, y]) result.set(x, y)
            }
        }
        return result
    }
}
