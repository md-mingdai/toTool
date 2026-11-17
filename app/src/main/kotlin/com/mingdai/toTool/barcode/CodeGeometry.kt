package com.mingdai.toTool.barcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.detector.FinderPattern
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 把各种格式的解码结果换算成「码的四个角」：顺时针、帧像素坐标，FloatArray(8) = x0,y0,…,x3,y3。
 *
 * ZXing 给的 resultPoints 并不是码的轮廓：
 * - QR 是三个定位点的中心（离码的边缘还差 3.5 个模块），版本 1 的小码里这一圈占边长的 25%；
 * - 一维码只有扫描行上的两个端点，没有高度；
 * - PDF417 是 8 个点，其中可能有 null。
 * 旧代码直接取这些点的外接矩形再加 6% 边距，所以小 QR 的框会缩在码里面，旋转的码框也偏大。
 */
internal object CodeGeometry {

    fun quadFromResult(result: Result, matrix: BitMatrix?): FloatArray? {
        val points: Array<out ResultPoint?> = result.resultPoints ?: return null
        val raw = when (result.barcodeFormat) {
            BarcodeFormat.QR_CODE -> qrQuad(points)
            BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC -> cornersOf(points) ?: hullQuad(points)
            BarcodeFormat.PDF_417 -> hullQuad(points)
            else -> if (points.count { it != null } <= 2) linearQuad(points, matrix) else hullQuad(points)
        }
        return raw?.let { canonical(it) }
    }

    fun rectQuad(left: Float, top: Float, right: Float, bottom: Float): FloatArray =
        floatArrayOf(left, top, right, top, right, bottom, left, bottom)

    /** 把任意顺序的四个点整理成顺时针（屏幕坐标，y 向下）；退化的四边形返回 null。 */
    fun canonical(q: FloatArray): FloatArray? {
        if (q.size < 8) return null
        for (v in q) if (v.isNaN() || v.isInfinite()) return null
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val angle = FloatArray(4) { atan2(q[it * 2 + 1] - cy, q[it * 2] - cx) }
        val order = intArrayOf(0, 1, 2, 3)
        for (i in 1 until 4) {
            val k = order[i]
            var j = i - 1
            while (j >= 0 && angle[order[j]] > angle[k]) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = k
        }
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[i * 2] = q[order[i] * 2]
            out[i * 2 + 1] = q[order[i] * 2 + 1]
        }
        var area2 = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area2 += out[i * 2] * out[j * 2 + 1] - out[j * 2] * out[i * 2 + 1]
        }
        if (abs(area2) < 32f) return null
        return out
    }

    /** 四边形面积的平方根，当作「码有多大」。 */
    fun size(q: FloatArray): Float {
        var area2 = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area2 += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
        }
        return sqrt(abs(area2) / 2f)
    }

    /* ------------------------------- QR ------------------------------- */

    fun qrQuad(p: Array<out ResultPoint?>): FloatArray? {
        if (p.size < 3) return null
        // ZXing 的顺序：[0] 左下、[1] 左上（直角那个）、[2] 右上、[3] 对齐图形（版本 ≥ 2 才有）
        val bl = p[0] ?: return null
        val tl = p[1] ?: return null
        val tr = p[2] ?: return null
        val ux = tr.x - tl.x
        val uy = tr.y - tl.y
        val vx = bl.x - tl.x
        val vy = bl.y - tl.y
        val lu = hypot(ux, uy)
        val lv = hypot(vx, vy)
        if (lu < 6f || lv < 6f) return null

        // 定位点自带模块大小估计；两个定位点中心之间隔着 (边长 - 7) 个模块
        var sum = 0f
        var count = 0
        for (i in 0 until 3) {
            val fp = p[i] as? FinderPattern ?: continue
            sum += fp.estimatedModuleSize
            count++
        }
        val moduleEst = if (count == 3) sum / 3f else 0f
        val nu: Float
        val nv: Float
        if (moduleEst > 0.5f) {
            nu = max(14f, (lu / moduleEst).roundToInt().toFloat())
            nv = max(14f, (lv / moduleEst).roundToInt().toFloat())
        } else {
            nu = 28f
            nv = 28f
        }

        // 有对齐图形时用 4 个已知点解单应，透视下比平行四边形外推准得多（合成数据：角误差 6% → 1%）
        val alignment = if (p.size >= 4) p[3] else null
        if (alignment != null && moduleEst > 0.5f) {
            val dim = snapDimension((nu + nv) / 2f + 7f)
            if (dim >= 25) {
                homographyCorners(tl, tr, bl, alignment, dim)?.let { return it }
            }
        }

        // 仿射外推：定位点中心沿两条边向外 3.5 个模块；第 4 个角用平行四边形补全
        val uxh = ux / lu
        val uyh = uy / lu
        val vxh = vx / lv
        val vyh = vy / lv
        val a = 3.5f * lu / nu
        val b = 3.5f * lv / nv
        return floatArrayOf(
            tl.x - a * uxh - b * vxh, tl.y - a * uyh - b * vyh,
            tr.x + a * uxh - b * vxh, tr.y + a * uyh - b * vyh,
            tr.x + bl.x - tl.x + a * uxh + b * vxh, tr.y + bl.y - tl.y + a * uyh + b * vyh,
            bl.x - a * uxh + b * vxh, bl.y - a * uyh + b * vyh,
        )
    }

    /** QR 的边长（模块数）只能是 4·版本 + 17。 */
    private fun snapDimension(x: Float): Int {
        val version = ((x - 17f) / 4f).roundToInt().coerceAtLeast(1)
        return 4 * version + 17
    }

    private fun homographyCorners(
        tl: ResultPoint,
        tr: ResultPoint,
        bl: ResultPoint,
        alignment: ResultPoint,
        dim: Int,
    ): FloatArray? {
        val d = dim.toDouble()
        // 模块坐标（以码的左上角为原点）→ 图像坐标。最后一个对齐图形的中心在 (dim-6.5, dim-6.5)
        val sx = doubleArrayOf(3.5, d - 3.5, 3.5, d - 6.5)
        val sy = doubleArrayOf(3.5, 3.5, d - 3.5, d - 6.5)
        val dx = doubleArrayOf(tl.x.toDouble(), tr.x.toDouble(), bl.x.toDouble(), alignment.x.toDouble())
        val dy = doubleArrayOf(tl.y.toDouble(), tr.y.toDouble(), bl.y.toDouble(), alignment.y.toDouble())
        val a = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)
        for (i in 0 until 4) {
            val x = sx[i]
            val y = sy[i]
            val u = dx[i]
            val v = dy[i]
            a[2 * i][0] = x; a[2 * i][1] = y; a[2 * i][2] = 1.0
            a[2 * i][6] = -u * x; a[2 * i][7] = -u * y
            b[2 * i] = u
            a[2 * i + 1][3] = x; a[2 * i + 1][4] = y; a[2 * i + 1][5] = 1.0
            a[2 * i + 1][6] = -v * x; a[2 * i + 1][7] = -v * y
            b[2 * i + 1] = v
        }
        val h = solve8(a, b) ?: return null
        val cornersX = doubleArrayOf(0.0, d, d, 0.0)
        val cornersY = doubleArrayOf(0.0, 0.0, d, d)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val x = cornersX[i]
            val y = cornersY[i]
            val w = h[6] * x + h[7] * y + 1.0
            // 分母异常说明解出来的透视不合理，退回仿射外推
            if (w < 0.3 || w > 3.0) return null
            out[i * 2] = ((h[0] * x + h[1] * y + h[2]) / w).toFloat()
            out[i * 2 + 1] = ((h[3] * x + h[4] * y + h[5]) / w).toFloat()
        }
        return out
    }

    /** 8×8 线性方程组，高斯消元 + 列主元。 */
    private fun solve8(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = 8
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-12) return null
            if (pivot != col) {
                val tmp = a[pivot]
                a[pivot] = a[col]
                a[col] = tmp
                val tb = b[pivot]
                b[pivot] = b[col]
                b[col] = tb
            }
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = s / a[r][r]
        }
        return x
    }

    /* --------------------- Data Matrix / Aztec / PDF417 --------------------- */

    /** Data Matrix、Aztec 的 resultPoints 本来就是四个角。 */
    private fun cornersOf(p: Array<out ResultPoint?>): FloatArray? {
        if (p.size < 4) return null
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val q = p[i] ?: return null
            out[i * 2] = q.x
            out[i * 2 + 1] = q.y
        }
        return out
    }

    /** 所有非空点的最小面积外接矩形（PDF417 的 8 个点里可能有 null，旧代码在这里会 NPE）。 */
    private fun hullQuad(p: Array<out ResultPoint?>): FloatArray? {
        val xs = FloatArray(p.size)
        val ys = FloatArray(p.size)
        var n = 0
        for (q in p) {
            if (q == null) continue
            xs[n] = q.x
            ys[n] = q.y
            n++
        }
        if (n < 3) return null
        return minAreaRect(xs, ys, n)
    }

    private fun minAreaRect(xs: FloatArray, ys: FloatArray, n: Int): FloatArray? {
        var bestArea = Float.MAX_VALUE
        val best = FloatArray(8)
        val deg2rad = (Math.PI / 180.0).toFloat()
        for (deg in 0 until 90) {
            val th = deg * deg2rad
            val c = cos(th)
            val s = sin(th)
            var minU = Float.MAX_VALUE
            var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE
            for (i in 0 until n) {
                val u = xs[i] * c + ys[i] * s
                val v = -xs[i] * s + ys[i] * c
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                best[0] = minU * c - minV * s; best[1] = minU * s + minV * c
                best[2] = maxU * c - minV * s; best[3] = maxU * s + minV * c
                best[4] = maxU * c - maxV * s; best[5] = maxU * s + maxV * c
                best[6] = minU * c - maxV * s; best[7] = minU * s + maxV * c
            }
        }
        return if (bestArea > 1f) best else null
    }

    /* ------------------------------ 一维码 ------------------------------ */

    // 条的方向相对「扫描线法线」的倾斜候选（0.3 ≈ 17°），按偏离程度从小到大
    private val SLOPES = floatArrayOf(0f, 0.1f, -0.1f, 0.2f, -0.2f, 0.3f, -0.3f)

    /**
     * 一维码只有扫描行上的两个端点（起止图形的中心）。
     * 横向：从端点往外走到最后一个黑像素；纵向：沿法线上下移动，直到这一行的黑白图案和扫描行对不上了。
     * 允许条有一点倾斜（挑能走得最远的那个斜率），所以框是平行四边形。量不出来时按长度估一个高度。
     */
    private fun linearQuad(p: Array<out ResultPoint?>, matrix: BitMatrix?): FloatArray? {
        var a: ResultPoint? = null
        var b: ResultPoint? = null
        for (q in p) {
            if (q == null) continue
            if (a == null) a = q else if (b == null) b = q
        }
        if (a == null || b == null) return null
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 12f) return null
        val ux = dx / len
        val uy = dy / len

        var extA = 0.03f * len
        var extB = 0.03f * len
        if (matrix != null) {
            val ma = measureEnd(matrix, a.x, a.y, -ux, -uy, len)
            val mb = measureEnd(matrix, b.x, b.y, ux, uy, len)
            if (ma > 0f) extA = ma
            if (mb > 0f) extB = mb
        }
        val sx = a.x - ux * extA
        val sy = a.y - uy * extA
        val ex = b.x + ux * extB
        val ey = b.y + uy * extB

        var nx = -uy
        var ny = ux
        var up = 0f
        var down = 0f
        if (matrix != null) {
            val n = 16
            val baseX = FloatArray(n)
            val baseY = FloatArray(n)
            val bits = BooleanArray(n)
            for (i in 0 until n) {
                val t = (i + 0.5f) / n
                baseX[i] = a.x + dx * t
                baseY[i] = a.y + dy * t
                bits[i] = dark(matrix, baseX[i], baseY[i])
            }
            val darkCount = bits.count { it }
            if (darkCount in 3..(n - 3)) {
                val maxSteps = min((0.8f * len).toInt(), 400).coerceAtLeast(8)
                var bestTotal = -1
                for (slope in SLOPES) {
                    var tx = -uy + slope * ux
                    var ty = ux + slope * uy
                    val tl = hypot(tx, ty)
                    tx /= tl
                    ty /= tl
                    val plus = extentAlong(matrix, baseX, baseY, bits, tx, ty, maxSteps)
                    val minus = extentAlong(matrix, baseX, baseY, bits, -tx, -ty, maxSteps)
                    if (plus + minus > bestTotal) {
                        bestTotal = plus + minus
                        nx = tx
                        ny = ty
                        down = plus.toFloat()
                        up = minus.toFloat()
                    }
                }
            }
        }
        if (up + down < 6f) {
            nx = -uy
            ny = ux
            val h = (0.22f * len).coerceIn(20f, 160f)
            up = h / 2f
            down = h / 2f
        }
        return floatArrayOf(
            sx - nx * up, sy - ny * up,
            ex - nx * up, ey - ny * up,
            ex + nx * down, ey + ny * down,
            sx + nx * down, sy + ny * down,
        )
    }

    /** 从端点沿 (dx, dy) 往外走，返回最后一个黑像素的距离；连续一小段全白就停。 */
    private fun measureEnd(m: BitMatrix, x0: Float, y0: Float, dx: Float, dy: Float, len: Float): Float {
        val maxSteps = (0.12f * len).toInt().coerceAtLeast(6)
        val gap = max(5, (len / 30f).toInt())
        var last = 0
        for (k in 1..maxSteps) {
            if (dark(m, x0 + dx * k, y0 + dy * k)) last = k else if (k - last > gap) break
        }
        return last.toFloat()
    }

    /** 沿法线方向平移基线图案，返回图案还能对得上的最远距离（允许中间有几步噪声）。 */
    private fun extentAlong(
        m: BitMatrix,
        baseX: FloatArray,
        baseY: FloatArray,
        bits: BooleanArray,
        nx: Float,
        ny: Float,
        maxSteps: Int,
    ): Int {
        var last = 0
        var bad = 0
        val need = 0.78f * bits.size
        for (k in 1..maxSteps) {
            var agree = 0
            for (i in bits.indices) {
                if (dark(m, baseX[i] + nx * k, baseY[i] + ny * k) == bits[i]) agree++
            }
            if (agree >= need) {
                last = k
                bad = 0
            } else {
                bad++
                if (bad >= 4) break
            }
        }
        return last
    }

    private fun dark(m: BitMatrix, x: Float, y: Float): Boolean {
        val xi = x.roundToInt()
        val yi = y.roundToInt()
        if (xi < 0 || yi < 0 || xi >= m.width || yi >= m.height) return false
        return m[xi, yi]
    }
}
