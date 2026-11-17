package com.mingdai.toTool.barcode

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 不依赖解码的一维码 / 堆叠码定位。
 *
 * Google 智能镜头在画面已经糊成一片、条码根本解不出来的时候仍然框得住它，因为它是「先检测、后识别」；
 * 旧代码只有 QR 有定位阶段（靠定位点），其它格式必须等解码成功才有框，而运动模糊时恰恰最难解码。
 *
 * 做法（经典方法，没有模型）：
 * 1. 缩到 1/2，算梯度，按 8×8 分块统计结构张量；条码是一堆平行的条，梯度方向高度一致（一致性 > 0.8）；
 * 2. 方向相近的相邻块并成连通域，每个连通域按主方向算一个旋转外接矩形；
 * 3. 沿条码长度方向取三条线，检查条宽：跳变足够多、宽度有变化（排除均匀条纹）、
 *    没有过长的游程（排除木纹 / 栅栏），至少两条通过才算数。
 *
 * 合成数据：召回 95–100%（9px 运动模糊 + 高斯 1.2 仍有 96%，15px 时 78%），
 * 文字、条纹、木纹、网格背景和真实截图里零误检。只在分析线程里用，不是线程安全的。
 */
class BarcodeLocator {

    private var half = ByteArray(0)
    private var hw = 0
    private var hh = 0
    private var nbx = 0
    private var nby = 0

    private var jxx = IntArray(0)
    private var jyy = IntArray(0)
    private var jxy = IntArray(0)
    private var theta = FloatArray(0)
    private var energy = FloatArray(0)
    private var coherence = FloatArray(0)
    private var mask = BooleanArray(0)
    private var parent = IntArray(0)

    // 以连通域根节点为下标的统计量
    private var count = IntArray(0)
    private var sumCos = FloatArray(0)
    private var sumSin = FloatArray(0)
    private var sumCoh = FloatArray(0)
    private var axisX = FloatArray(0)
    private var axisY = FloatArray(0)
    private var uMin = FloatArray(0)
    private var uMax = FloatArray(0)
    private var vMin = FloatArray(0)
    private var vMax = FloatArray(0)

    // 条宽检查用的缓冲
    private var profile = FloatArray(0)
    private var crossings = IntArray(0)
    private var runs = IntArray(0)
    private var sortedRuns = IntArray(0)
    private val histogram = IntArray(256)

    fun locate(frame: LumaFrame): List<Candidate> {
        hw = frame.width / 2
        hh = frame.height / 2
        nbx = (hw - 2) / B
        nby = (hh - 2) / B
        if (nbx < 8 || nby < 8) return emptyList()
        buildHalf(frame)

        val nb = nbx * nby
        ensureArrays(nb)
        accumulateBlocks()

        for (k in 0 until nb) {
            val xx = jxx[k].toDouble()
            val yy = jyy[k].toDouble()
            val xy = jxy[k].toDouble()
            val e = xx + yy
            energy[k] = e.toFloat()
            // 中心差分是真实梯度的 2 倍，所以除以 4
            val magnitude = sqrt(e / (B * B) / 4.0)
            val dd = xx - yy
            val coh = sqrt(dd * dd + 4.0 * xy * xy) / (e + 1e-6)
            coherence[k] = coh.toFloat()
            theta[k] = (0.5 * atan2(2.0 * xy, dd)).toFloat()
            mask[k] = magnitude >= T_GRAD && coh >= T_COH
        }

        // 并查集：8 邻域里方向相近的块并到一起
        for (k in 0 until nb) parent[k] = k
        for (blockY in 0 until nby) {
            for (blockX in 0 until nbx) {
                val k = blockY * nbx + blockX
                if (!mask[k]) continue
                link(k, blockY, blockX + 1)
                link(k, blockY + 1, blockX)
                link(k, blockY + 1, blockX + 1)
                link(k, blockY + 1, blockX - 1)
            }
        }

        // 第一遍：每个连通域的块数、能量加权的主方向（倍角圆均值）、平均一致性
        Arrays.fill(count, 0)
        Arrays.fill(sumCos, 0f)
        Arrays.fill(sumSin, 0f)
        Arrays.fill(sumCoh, 0f)
        for (k in 0 until nb) {
            if (!mask[k]) continue
            val r = find(k)
            count[r]++
            val w = energy[k]
            sumCos[r] += w * cos(2f * theta[k])
            sumSin[r] += w * sin(2f * theta[k])
            sumCoh[r] += coherence[k]
        }
        for (r in 0 until nb) {
            uMin[r] = Float.MAX_VALUE
            uMax[r] = -Float.MAX_VALUE
            vMin[r] = Float.MAX_VALUE
            vMax[r] = -Float.MAX_VALUE
            if (count[r] >= MIN_BLOCKS) {
                val t0 = 0.5f * atan2(sumSin[r], sumCos[r])
                axisX[r] = cos(t0)
                axisY[r] = sin(t0)
            }
        }
        // 第二遍：沿主方向（梯度方向 = 垂直于条 = 码的长度方向）和它的法线方向投影，得到旋转外接矩形
        for (blockY in 0 until nby) {
            for (blockX in 0 until nbx) {
                val k = blockY * nbx + blockX
                if (!mask[k]) continue
                val r = find(k)
                if (count[r] < MIN_BLOCKS) continue
                val ux = axisX[r]
                val uy = axisY[r]
                val cx = (blockX + 0.5f) * B + 1f
                val cy = (blockY + 0.5f) * B + 1f
                val pu = cx * ux + cy * uy
                val pv = -cx * uy + cy * ux
                if (pu < uMin[r]) uMin[r] = pu
                if (pu > uMax[r]) uMax[r] = pu
                if (pv < vMin[r]) vMin[r] = pv
                if (pv > vMax[r]) vMax[r] = pv
            }
        }

        val found = ArrayList<Candidate>(4)
        for (r in 0 until nb) {
            val n = count[r]
            if (n < MIN_BLOCKS) continue
            val ux = axisX[r]
            val uy = axisY[r]
            val vx = -uy
            val vy = ux
            val u0 = uMin[r] - B / 2f
            val u1 = uMax[r] + B / 2f
            val v0 = vMin[r] - B / 2f
            val v1 = vMax[r] + B / 2f
            val length = u1 - u0
            val height = v1 - v0
            if (length < MIN_LENGTH || height < MIN_HEIGHT) continue
            val aspect = length / height
            if (aspect < 0.9f || aspect > 12f) continue
            if (n * B * B / (length * height) < MIN_FILL) continue
            if (!looksLikeBars(u0, u1, v0, v1, ux, uy, vx, vy)) continue

            val quad = FloatArray(8)
            for (i in 0 until 4) {
                val u = if (i == 0 || i == 3) u0 else u1
                val v = if (i < 2) v0 else v1
                // 半尺度坐标 → 整帧坐标
                quad[i * 2] = (u * ux + v * vx) * 2f
                quad[i * 2 + 1] = (u * uy + v * vy) * 2f
            }
            val canonical = CodeGeometry.canonical(quad) ?: continue
            // 得分 = 块数 × 平均一致性
            found.add(Candidate(canonical, sumCoh[r]))
        }
        found.sortByDescending { it.score }
        return if (found.size > MAX_CANDIDATES) found.subList(0, MAX_CANDIDATES).toList() else found
    }

    /** 沿长度方向取 3 条线，至少 2 条的条宽分布像条码。 */
    private fun looksLikeBars(
        u0: Float,
        u1: Float,
        v0: Float,
        v1: Float,
        ux: Float,
        uy: Float,
        vx: Float,
        vy: Float,
    ): Boolean {
        var passed = 0
        for (f in LINE_FRACTIONS) {
            val v = v0 + (v1 - v0) * f
            if (lineLooksLikeBars(u0 + B / 2f, u1 - B / 2f, v, ux, uy, vx, vy)) passed++
        }
        return passed >= 2
    }

    private fun lineLooksLikeBars(
        uStart: Float,
        uEnd: Float,
        v: Float,
        ux: Float,
        uy: Float,
        vx: Float,
        vy: Float,
    ): Boolean {
        val steps = ((uEnd - uStart) / 0.5f).toInt()
        if (steps < 20) return false
        if (profile.size < steps + 1) {
            profile = FloatArray(steps + 1)
            crossings = IntArray(steps + 1)
            runs = IntArray(steps + 1)
            sortedRuns = IntArray(steps + 1)
        }
        var n = 0
        for (i in 0..steps) {
            val u = uStart + i * 0.5f
            val px = u * ux + v * vx
            val py = u * uy + v * vy
            if (px < 0f || py < 0f || px >= hw - 1 || py >= hh - 1) continue
            profile[n++] = sample(px, py)
        }
        if (n < 20) return false

        // 10% / 90% 分位当黑白参考，对比度太低不算
        Arrays.fill(histogram, 0)
        for (i in 0 until n) histogram[profile[i].toInt().coerceIn(0, 255)]++
        val lo = percentile(n, 0.10f)
        val hi = percentile(n, 0.90f)
        if (hi - lo < 25) return false
        val mid = (lo + hi) / 2f

        var crossingCount = 0
        var previous = profile[0] > mid
        for (i in 1 until n) {
            val bit = profile[i] > mid
            if (bit != previous) crossings[crossingCount++] = i
            previous = bit
        }
        if (crossingCount < 10) return false

        // 相邻跳变的间隔 = 条宽；去掉两端的静区
        var runCount = crossingCount - 1
        var first = 0
        if (runCount > 4) {
            first = 1
            runCount -= 2
        }
        if (runCount < 8) return false
        var sum = 0L
        for (i in 0 until runCount) {
            val run = crossings[first + i + 1] - crossings[first + i]
            runs[i] = run
            sortedRuns[i] = run
            sum += run
        }
        Arrays.sort(sortedRuns, 0, runCount)
        val median = sortedRuns[runCount / 2].toFloat()
        val mean = sum.toFloat() / runCount
        var variance = 0f
        var regular = 0
        for (i in 0 until runCount) {
            val d = runs[i] - mean
            variance += d * d
            if (runs[i] <= 5f * median) regular++
        }
        val cv = sqrt(variance / runCount) / mean
        // 条宽要有变化（均匀条纹 cv≈0.1），且没有过长的游程（木纹、栅栏）
        return cv >= MIN_WIDTH_CV && regular.toFloat() / runCount >= 0.9f
    }

    private fun percentile(total: Int, q: Float): Float {
        val target = (total * q).toInt()
        var acc = 0
        for (v in 0 until 256) {
            acc += histogram[v]
            if (acc > target) return v.toFloat()
        }
        return 255f
    }

    private fun sample(x: Float, y: Float): Float {
        val xi = x.toInt()
        val yi = y.toInt()
        val fx = x - xi
        val fy = y - yi
        val i = yi * hw + xi
        val p00 = half[i].toInt() and 0xFF
        val p10 = half[i + 1].toInt() and 0xFF
        val p01 = half[i + hw].toInt() and 0xFF
        val p11 = half[i + hw + 1].toInt() and 0xFF
        return (p00 * (1f - fx) + p10 * fx) * (1f - fy) + (p01 * (1f - fx) + p11 * fx) * fy
    }

    private fun link(k: Int, blockY: Int, blockX: Int) {
        if (blockY < 0 || blockY >= nby || blockX < 0 || blockX >= nbx) return
        val k2 = blockY * nbx + blockX
        if (!mask[k2]) return
        var d = abs(theta[k] - theta[k2]) % PI_F
        if (d > PI_F - d) d = PI_F - d
        if (d >= ANGLE_TOLERANCE) return
        val a = find(k)
        val b = find(k2)
        if (a != b) parent[a] = b
    }

    private fun find(start: Int): Int {
        var a = start
        while (parent[a] != a) {
            parent[a] = parent[parent[a]]
            a = parent[a]
        }
        return a
    }

    private fun buildHalf(frame: LumaFrame) {
        val w = frame.width
        val src = frame.data
        if (half.size != hw * hh) half = ByteArray(hw * hh)
        var o = 0
        for (y in 0 until hh) {
            val r0 = 2 * y * w
            val r1 = r0 + w
            var x = 0
            for (i in 0 until hw) {
                val s = (src[r0 + x].toInt() and 0xFF) + (src[r0 + x + 1].toInt() and 0xFF) +
                    (src[r1 + x].toInt() and 0xFF) + (src[r1 + x + 1].toInt() and 0xFF)
                half[o++] = ((s + 2) shr 2).toByte()
                x += 2
            }
        }
    }

    /** 每个 8×8 块累加 Σgx²、Σgy²、Σgx·gy（中心差分）。 */
    private fun accumulateBlocks() {
        for (blockY in 0 until nby) {
            for (blockX in 0 until nbx) {
                var sxx = 0
                var syy = 0
                var sxy = 0
                val x0 = 1 + blockX * B
                val y0 = 1 + blockY * B
                for (j in 0 until B) {
                    val row = (y0 + j) * hw
                    val up = row - hw
                    val down = row + hw
                    for (i in 0 until B) {
                        val x = x0 + i
                        val gx = (half[row + x + 1].toInt() and 0xFF) - (half[row + x - 1].toInt() and 0xFF)
                        val gy = (half[down + x].toInt() and 0xFF) - (half[up + x].toInt() and 0xFF)
                        sxx += gx * gx
                        syy += gy * gy
                        sxy += gx * gy
                    }
                }
                val k = blockY * nbx + blockX
                jxx[k] = sxx
                jyy[k] = syy
                jxy[k] = sxy
            }
        }
    }

    private fun ensureArrays(nb: Int) {
        if (jxx.size >= nb) return
        jxx = IntArray(nb)
        jyy = IntArray(nb)
        jxy = IntArray(nb)
        theta = FloatArray(nb)
        energy = FloatArray(nb)
        coherence = FloatArray(nb)
        mask = BooleanArray(nb)
        parent = IntArray(nb)
        count = IntArray(nb)
        sumCos = FloatArray(nb)
        sumSin = FloatArray(nb)
        sumCoh = FloatArray(nb)
        axisX = FloatArray(nb)
        axisY = FloatArray(nb)
        uMin = FloatArray(nb)
        uMax = FloatArray(nb)
        vMin = FloatArray(nb)
        vMax = FloatArray(nb)
    }

    private companion object {
        const val B = 8
        const val T_GRAD = 9.0
        const val T_COH = 0.80
        const val MIN_BLOCKS = 5
        const val MIN_LENGTH = 28f
        const val MIN_HEIGHT = 12f
        const val MIN_FILL = 0.45f
        const val MIN_WIDTH_CV = 0.28f
        const val MAX_CANDIDATES = 3
        const val PI_F = 3.14159265f
        const val ANGLE_TOLERANCE = 0.384f // 22°
        val LINE_FRACTIONS = floatArrayOf(0.3f, 0.5f, 0.7f)
    }
}
