package com.mingdai.toTool.barcode.hanxin

import com.google.zxing.common.BitMatrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 从二值图片里定位并采样汉信码。
 *
 * 汉信码四个角是 7×7 定位图形，符号的暗像素外接框就是码的边界；
 * 利用左上角定位图的第一条 7 模块暗边估算模块大小，再按版本采样成 N×N 矩阵。
 * 对正拍 / 旋转 90° 的图片效果最好；严重透视畸变需要先手动裁正。
 */
object HanXinImageDecoder {

    fun decode(binary: BitMatrix): String? {
        return decodeOrientation(binary) ?: decodeOrientation(invert(binary))
    }

    private fun decodeOrientation(binary: BitMatrix): String? {
        val bounds = darkBounds(binary) ?: return null
        val width = bounds.right - bounds.left + 1
        val height = bounds.bottom - bounds.top + 1
        if (width < 23 || height < 23) return null
        val aspect = width.toDouble() / height.toDouble()
        if (aspect < 0.6 || aspect > 1.65) return null

        val module = estimateModuleSize(binary, bounds)
        val candidates = LinkedHashSet<Int>()
        if (module > 0.5) {
            val n = (width / module).roundToInt()
            listOf(0, -2, 2, -4, 4, -6, 6).forEach { delta ->
                val candidate = n + delta
                if (candidate in 23..189 && (candidate - 21) % 2 == 0) candidates.add(candidate)
            }
        }
        if (candidates.isEmpty()) {
            for (version in 1..84) candidates.add(version * 2 + 21)
        }

        for (n in candidates) {
            val sampled = sample(binary, bounds, n)
            var rotated = sampled
            repeat(4) {
                HanXinCodec.decode(rotated)?.let { return it }
                rotated = rotate90(rotated)
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
        if (right - left + 1 < 20 || bottom - top + 1 < 20) return null
        return Bounds(left, top, right, bottom)
    }

    /** 左上角定位图形的第一条边固定为 7 个模块，用它的像素长度估算模块大小。 */
    private fun estimateModuleSize(matrix: BitMatrix, bounds: Bounds): Double {
        var verticalRun = 0
        var y = bounds.top
        while (y <= bounds.bottom && matrix[bounds.left, y]) {
            verticalRun++
            y++
        }
        var horizontalRun = 0
        var x = bounds.left
        while (x <= bounds.right && matrix[x, bounds.top]) {
            horizontalRun++
            x++
        }
        val run = max(verticalRun, horizontalRun)
        return if (run >= 5) run / 7.0 else 0.0
    }

    private fun sample(matrix: BitMatrix, bounds: Bounds, size: Int): BitMatrix {
        val result = BitMatrix(size, size)
        val width = bounds.right - bounds.left + 1
        val height = bounds.bottom - bounds.top + 1
        val cellWidth = width.toDouble() / size
        val cellHeight = height.toDouble() / size
        val radiusX = max(1, (cellWidth / 4).toInt())
        val radiusY = max(1, (cellHeight / 4).toInt())
        for (moduleY in 0 until size) {
            for (moduleX in 0 until size) {
                val centerX = bounds.left + (moduleX + 0.5) * cellWidth
                val centerY = bounds.top + (moduleY + 0.5) * cellHeight
                var dark = 0
                var total = 0
                for (dy in -radiusY..radiusY) {
                    for (dx in -radiusX..radiusX) {
                        val px = (centerX + dx).toInt().coerceIn(0, matrix.width - 1)
                        val py = (centerY + dy).toInt().coerceIn(0, matrix.height - 1)
                        total++
                        if (matrix[px, py]) dark++
                    }
                }
                if (dark * 2 >= total) result.set(moduleX, moduleY)
            }
        }
        return result
    }

    private fun rotate90(matrix: BitMatrix): BitMatrix {
        val result = BitMatrix(matrix.height, matrix.width)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) result.set(matrix.height - 1 - y, x)
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
