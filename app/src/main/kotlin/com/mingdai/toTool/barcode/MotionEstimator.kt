package com.mingdai.toTool.barcode

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 一次位移估计：[dx]、[dy] 是整帧像素下，内容从上一帧到这一帧移动了多少；[confidence] 0..1。 */
class MotionResult(val dx: Float, val dy: Float, val confidence: Float)

/**
 * 帧间位移跟踪：解码只能隔几帧给一次位置，帧与帧之间框靠它「粘」在码上。
 *
 * 做法：整帧缩到 1/4，取上一帧里码所在区域当模板，在当前帧里按预测位移附近 ±R 格做块匹配（SAD），
 * 再用抛物线拟合到亚像素。只估平移——两次解码之间的缩放、旋转很小，下一次解码会把形状纠正过来。
 * 同时给出置信度（模板得有纹理、最优位置要明显好于别处），空白纸面、码被拿走、运动太快都会被判为不可信，
 * 这时跟踪器改用速度滑行。
 *
 * 只在分析线程里用，不是线程安全的。
 */
class MotionEstimator {

    private var prev = ByteArray(0)
    private var prevW = 0
    private var prevH = 0
    private var hasPrev = false

    private var cur = ByteArray(0)
    private var curW = 0
    private var curH = 0

    private var template = IntArray(0)
    private var surface = IntArray(0)

    fun reset() {
        hasPrev = false
        curW = 0
    }

    /** 给新到的一帧建缩略图（每个 4×4 块取中间 2×2 的平均）。 */
    fun begin(frame: LumaFrame) {
        val sw = frame.width / SCALE
        val sh = frame.height / SCALE
        if (sw < 16 || sh < 16) {
            curW = 0
            return
        }
        if (cur.size != sw * sh) cur = ByteArray(sw * sh)
        val src = frame.data
        val fw = frame.width
        var o = 0
        for (j in 0 until sh) {
            val r0 = (j * SCALE + 1) * fw
            val r1 = r0 + fw
            var x = 1
            for (i in 0 until sw) {
                val s = (src[r0 + x].toInt() and 0xFF) + (src[r0 + x + 1].toInt() and 0xFF) +
                    (src[r1 + x].toInt() and 0xFF) + (src[r1 + x + 1].toInt() and 0xFF)
                cur[o++] = ((s + 2) shr 2).toByte()
                x += SCALE
            }
        }
        curW = sw
        curH = sh
    }

    /** 这一帧处理完：它成为下一帧的「上一帧」。 */
    fun end() {
        val t = prev
        prev = cur
        cur = t
        prevW = curW
        prevH = curH
        hasPrev = curW > 0
    }

    /**
     * 估计 [left, top, right, bottom]（整帧像素）这块内容从上一帧到当前帧的位移。
     * [guessDx]、[guessDy] 是预测位移（速度 × 时间），搜索窗以它为中心；[boost] 用于帧间隔较长时放大搜索窗。
     * 无法估计（没有上一帧、模板太小、窗口全在画面外）返回 null。
     */
    fun estimate(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        guessDx: Float,
        guessDy: Float,
        boost: Boolean,
    ): MotionResult? {
        if (!hasPrev || curW == 0 || prevW != curW || prevH != curH) return null
        val sw = curW
        val sh = curH
        val w = right - left
        val h = bottom - top
        var tx0 = ((left + w * SHRINK) / SCALE).toInt()
        var tx1 = ((right - w * SHRINK) / SCALE).toInt()
        var ty0 = ((top + h * SHRINK) / SCALE).toInt()
        var ty1 = ((bottom - h * SHRINK) / SCALE).toInt()
        tx0 = max(tx0, 0)
        ty0 = max(ty0, 0)
        tx1 = min(tx1, sw - 1)
        ty1 = min(ty1, sh - 1)
        val tw = tx1 - tx0 + 1
        val th = ty1 - ty0 + 1
        if (tw < 6 || th < 4 || tw * th < 48) return null

        // 模板最多 36×36 个采样点，大码隔点取
        val stride = max(1, ceil(max(tw, th) / 36f).toInt())
        val count = ((tw + stride - 1) / stride) * ((th + stride - 1) / stride)
        if (template.size < count) template = IntArray(count)
        var sum = 0
        var idx = 0
        var j = 0
        while (j < th) {
            val row = (ty0 + j) * sw + tx0
            var i = 0
            while (i < tw) {
                val v = prev[row + i].toInt() and 0xFF
                template[idx++] = v
                sum += v
                i += stride
            }
            j += stride
        }
        val mean = sum.toFloat() / count
        var devSum = 0f
        for (k in 0 until count) devSum += abs(template[k] - mean)
        val dev = devSum / count
        // 没有纹理（空白纸面、纯色背景）没法靠匹配定位
        if (dev < MIN_TEXTURE) return MotionResult(0f, 0f, 0f)

        val r = if (boost) RADIUS_BOOST else RADIUS
        val side = 2 * r + 1
        if (surface.size < side * side) surface = IntArray(side * side)
        val gx = (guessDx / SCALE).roundToInt()
        val gy = (guessDy / SCALE).roundToInt()

        var best = Int.MAX_VALUE
        var bestIx = -1
        var bestIy = -1
        var valid = 0
        var total = 0L
        for (iy in 0 until side) {
            val y0 = ty0 + gy - r + iy
            for (ix in 0 until side) {
                val x0 = tx0 + gx - r + ix
                val slot = iy * side + ix
                if (x0 < 0 || y0 < 0 || x0 + tw > sw || y0 + th > sh) {
                    surface[slot] = -1
                    continue
                }
                var sad = 0
                var k = 0
                var jj = 0
                while (jj < th) {
                    val row = (y0 + jj) * sw + x0
                    var ii = 0
                    while (ii < tw) {
                        sad += abs((cur[row + ii].toInt() and 0xFF) - template[k++])
                        ii += stride
                    }
                    jj += stride
                }
                surface[slot] = sad
                total += sad
                valid++
                if (sad < best) {
                    best = sad
                    bestIx = ix
                    bestIy = iy
                }
            }
        }
        if (valid == 0 || bestIx < 0) return null

        // 离最优位置 ≥ 2 格的次优峰：周期性图案（一维码）会有多个相近的峰，这时位移不可信
        var second = Int.MAX_VALUE
        for (iy in 0 until side) {
            for (ix in 0 until side) {
                val v = surface[iy * side + ix]
                if (v < 0) continue
                if (max(abs(iy - bestIy), abs(ix - bestIx)) >= 2 && v < second) second = v
            }
        }

        val left1 = if (bestIx > 0) surface[bestIy * side + bestIx - 1] else -1
        val right1 = if (bestIx < side - 1) surface[bestIy * side + bestIx + 1] else -1
        val up1 = if (bestIy > 0) surface[(bestIy - 1) * side + bestIx] else -1
        val down1 = if (bestIy < side - 1) surface[(bestIy + 1) * side + bestIx] else -1
        val subX = parabola(left1, best, right1)
        val subY = parabola(up1, best, down1)

        val meanAbs = best.toFloat() / count
        val c1 = ((0.9f - meanAbs / dev) / 0.6f).coerceIn(0f, 1f)
        val meanSurface = total.toFloat() / valid
        val ratio = best.toFloat() / max(meanSurface, 1e-6f)
        var c2 = ((0.85f - ratio) / 0.5f).coerceIn(0f, 1f)
        if (second != Int.MAX_VALUE && second.toFloat() < best.toFloat() * 1.15f) c2 *= 0.5f

        val dx = (gx - r + bestIx + subX) * SCALE
        val dy = (gy - r + bestIy + subY) * SCALE
        return MotionResult(dx, dy, min(c1, c2))
    }

    /** 三点抛物线拟合最小值位置，范围限制在 ±0.5 格；邻居缺失（-1）时不做亚像素。 */
    private fun parabola(minus1: Int, center: Int, plus1: Int): Float {
        if (minus1 < 0 || plus1 < 0) return 0f
        val den = (minus1 - 2 * center + plus1).toFloat()
        if (den <= 1e-3f) return 0f
        return (0.5f * (minus1 - plus1) / den).coerceIn(-0.5f, 0.5f)
    }

    private companion object {
        const val SCALE = 4
        const val SHRINK = 0.08f
        const val RADIUS = 7
        const val RADIUS_BOOST = 10
        const val MIN_TEXTURE = 8f
    }
}
