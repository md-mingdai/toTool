package com.mingdai.toTool.barcode

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 给界面用的一条轨迹的快照。所有坐标都是整帧像素（转正后的分析帧）。 */
class TrackSnap(
    val id: Long,
    /** 四个角，顺时针；是快照那一帧时刻的位置。 */
    val quad: FloatArray,
    /** 速度，像素/秒；界面据此外推到「此刻」。 */
    val vx: Float,
    val vy: Float,
    /** 解出内容之后才有；只定位到的候选为 null（只画框，不出「查看」按钮）。 */
    val result: ScanResult?,
    /** 开始可见的时刻，用来做框的弹出动画。 */
    val shownNs: Long,
    /** 解码成功的时刻，用来做按钮的弹出动画；还没解出来为 0。 */
    val confirmedNs: Long,
    /** 0 = 正常；否则是消失的截止时刻，界面据此淡出。 */
    val deathNs: Long,
)

class ScanSnapshot(
    /** 这一帧到达分析线程的时刻（SystemClock.elapsedRealtimeNanos）。 */
    val frameNs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val tracks: List<TrackSnap>,
)

const val TRACK_FADE_NS = 160_000_000L

private const val HIST = 40
private const val MAX_TRACKS = 12
private const val MOTION_MIN_CONF = 0.3f
private const val COAST_TAU_S = 0.25f
private const val POS_GAIN_MIN = 0.25f
private const val POS_GAIN_SLOPE = 0.6f
private const val POS_GAIN_MAX = 0.85f
private const val SHAPE_GAIN = 0.4f
private const val SLOW_GAIN_MIN = 0.6f
private const val GATE_FAST = 2.0f
private const val GATE_SLOW = 3.0f
private const val FAST_OWNS_NS = 400_000_000L
private const val COAST_BLIND_NS = 350_000_000L
private const val COAST_MOVING_NS = 1_200_000_000L
private const val COAST_STILL_NS = 400_000_000L
private const val COAST_SLOW_NS = 1_800_000_000L
private const val MOTION_RECENT_NS = 200_000_000L
private const val STILL_SPEED_PX_S = 30f
private const val MERGE_IOU = 0.55f
private const val TENTATIVE_LOSS_NS = 300_000_000L
private const val TENTATIVE_MAX_NS = 6_000_000_000L
private const val SUPPRESS_NS = 4_000_000_000L
private const val SHOW_PRESENCE = 0.6f
private const val SHOW_MIN_HITS = 3
private const val CANDIDATE_REFRESH_AFTER_NS = 250_000_000L

private class Track(
    val id: Long,
    var cx: Float,
    var cy: Float,
    /** 四个角相对中心的偏移（8 个数）。位置和形状分开滤波：位置要跟得快，形状变化慢、重度平滑也看不出。 */
    val off: FloatArray,
    var result: ScanResult?,
    val firstNs: Long,
    var lastNs: Long,
) {
    var vx = 0f
    var vy = 0f
    var lastDecodeNs = 0L
    var lastFastNs = 0L
    var lastCandidateNs = 0L
    var lastMotionOkNs = 0L
    var slowOnly = false
    var hits = 0
    var candidateHits = 0
    var presence = 0f
    var shownNs = 0L
    var confirmedNs = 0L
    var deathNs = 0L

    /** 候选放了太久还没被解码确认：到期是单向的，候选证据不能再把它「复活」，淡出要走完。 */
    var expiring = false

    // 当前帧的临时量
    var startX = cx
    var startY = cy
    var frameDt = 0.033f
    var moved = false
    var decoded = false

    var tmpX = 0f
    var tmpY = 0f
    private val histT = LongArray(HIST)
    private val histX = FloatArray(HIST)
    private val histY = FloatArray(HIST)
    private var histN = 0
    private var histHead = 0

    val visible: Boolean get() = result != null || shownNs != 0L

    fun evidenceNs(): Long = max(lastDecodeNs, lastCandidateNs)

    /** 四边形面积的平方根，当作码有多大。 */
    fun size(): Float {
        var area2 = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area2 += off[i * 2] * off[j * 2 + 1] - off[j * 2] * off[i * 2 + 1]
        }
        return sqrt(abs(area2) / 2f)
    }

    /** 外接矩形 [left, top, right, bottom]。 */
    fun bounds(out: FloatArray) {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val x = cx + off[i * 2]
            val y = cy + off[i * 2 + 1]
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        out[0] = l
        out[1] = t
        out[2] = r
        out[3] = b
    }

    /** 记下这一帧结束时的位置，供晚到的慢路径结果回溯：「测量当时这条轨迹在哪」。 */
    fun push(timeNs: Long) {
        val slot: Int
        if (histN < HIST) {
            slot = (histHead + histN) % HIST
            histN++
        } else {
            slot = histHead
            histHead = (histHead + 1) % HIST
        }
        histT[slot] = timeNs
        histX[slot] = cx
        histY[slot] = cy
    }

    /** 把 [frameNs] 时刻的位置（线性插值）写进 tmpX / tmpY；没有历史返回 false。 */
    fun positionAt(frameNs: Long): Boolean {
        if (histN == 0) return false
        var prev = -1
        for (i in 0 until histN) {
            val s = (histHead + i) % HIST
            if (histT[s] >= frameNs) {
                if (prev < 0) {
                    tmpX = histX[s]
                    tmpY = histY[s]
                    return true
                }
                val p = (histHead + prev) % HIST
                val span = (histT[s] - histT[p]).toFloat()
                val f = if (span <= 0f) 1f else ((frameNs - histT[p]).toFloat() / span).coerceIn(0f, 1f)
                tmpX = histX[p] + (histX[s] - histX[p]) * f
                tmpY = histY[p] + (histY[s] - histY[p]) * f
                return true
            }
            prev = i
        }
        val s = (histHead + histN - 1) % HIST
        tmpX = histX[s]
        tmpY = histY[s]
        return true
    }

    /** 慢路径修正位置后，让 [frameNs] 之后的历史一起平移，保证下一次回溯仍然自洽。 */
    fun shiftSince(frameNs: Long, dx: Float, dy: Float) {
        for (i in 0 until histN) {
            val s = (histHead + i) % HIST
            if (histT[s] >= frameNs) {
                histX[s] += dx
                histY[s] += dy
            }
        }
    }
}

private class Link(val c: Int, val t: Int, val cost: Float)

private class Suppression(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val untilNs: Long,
)

/**
 * 多码跟踪：把「解码结果 / 只定位到的候选 / 帧间位移」融合成每个码一条平滑、不丢的轨迹。
 *
 * 每帧的顺序（都在分析线程里）：
 * [advance]（用位移估计或速度把所有轨迹推进到这一帧）→ [onSlow]（慢路径晚到的结果）
 * → [onFast]（这一帧的解码结果）→ [onCandidates]（只定位到的）→ [finish]（速度、生命周期、去重）→ [snapshot]。
 *
 * 位置更新的增益是自适应的：新测量和预测差得越多越相信测量；正常时只轻轻修正，静止时就稳得像钉住一样。
 */
class CodeTracker {

    var frameW = 0
        private set
    var frameH = 0
        private set

    private val tracks = ArrayList<Track>()
    private val suppressions = ArrayList<Suppression>()
    private var nextId = 1L
    private val boundsA = FloatArray(4)
    private val boundsB = FloatArray(4)

    fun reset(width: Int, height: Int) {
        tracks.clear()
        suppressions.clear()
        frameW = width
        frameH = height
    }

    /** 用帧间位移把所有轨迹推进到这一帧；位移不可信就用速度滑行（速度逐渐衰减）。 */
    fun advance(nowNs: Long, motion: MotionEstimator) {
        for (t in tracks) {
            val dt = ((nowNs - t.lastNs) / 1e9f).coerceIn(0.001f, 0.25f)
            t.startX = t.cx
            t.startY = t.cy
            t.frameDt = dt
            t.moved = false
            t.decoded = false
            t.bounds(boundsA)
            val m = motion.estimate(
                boundsA[0], boundsA[1], boundsA[2], boundsA[3],
                t.vx * dt, t.vy * dt, dt > 0.07f,
            )
            if (m != null && m.confidence >= MOTION_MIN_CONF) {
                t.cx += m.dx
                t.cy += m.dy
                t.moved = true
                t.lastMotionOkNs = nowNs
            } else {
                t.cx += t.vx * dt
                t.cy += t.vy * dt
                val decay = exp(-dt / COAST_TAU_S)
                t.vx *= decay
                t.vy *= decay
            }
            t.lastNs = nowNs
        }
    }

    /** 快路径这一帧解出来的码。 */
    fun onFast(results: List<ScanResult>, nowNs: Long) {
        val valid = results.filter { it.quad != null }
        if (valid.isEmpty()) return
        val assignment = assign(valid, GATE_FAST)
        // 先处理配上的，再新建：新建时轨迹满了会挤掉旧的，会让上面按下标配好的结果错位
        for (i in valid.indices) {
            val ti = assignment[i]
            if (ti >= 0) applyFast(tracks[ti], valid[i], nowNs)
        }
        for (i in valid.indices) {
            if (assignment[i] < 0) createConfirmed(valid[i], nowNs, false)
        }
    }

    /**
     * 慢路径的结果：它们是几百毫秒前那一帧的测量。
     * 已有轨迹：回溯「那一帧时轨迹在哪」，用差值修正现在的位置；新轨迹：只能用过时的位置先放着。
     */
    fun onSlow(results: List<ScanResult>, frameNs: Long, nowNs: Long) {
        val valid = results.filter { it.quad != null }
        if (valid.isEmpty()) return
        val assignment = assign(valid, GATE_SLOW)
        for (i in valid.indices) {
            val ti = assignment[i]
            if (ti < 0) continue
            val t = tracks[ti]
            if (t.result != null && t.lastFastNs != 0L && nowNs - t.lastFastNs < FAST_OWNS_NS) {
                // 快路径正在驱动这条轨迹的几何，慢路径只负责续命
                t.result = valid[i]
                t.lastDecodeNs = nowNs
                t.deathNs = 0L
            } else {
                applySlow(t, valid[i], frameNs, nowNs)
            }
        }
        for (i in valid.indices) {
            if (assignment[i] < 0) createConfirmed(valid[i], nowNs, true)
        }
    }

    /** 只定位到、这一帧没解出内容的码（QR 定位点、一维码区域）。 */
    fun onCandidates(candidates: List<Candidate>, nowNs: Long) {
        if (candidates.isEmpty()) return
        val links = ArrayList<Link>()
        for (ci in candidates.indices) {
            quadBounds(candidates[ci].quad, boundsA)
            for (ti in tracks.indices) {
                tracks[ti].bounds(boundsB)
                val iou = boundsIou(boundsA, boundsB)
                if (iou >= 0.3f) links.add(Link(ci, ti, 1f - iou))
            }
        }
        links.sortBy { it.cost }
        val usedC = BooleanArray(candidates.size)
        val usedT = BooleanArray(tracks.size)
        for (link in links) {
            if (usedC[link.c] || usedT[link.t]) continue
            usedC[link.c] = true
            usedT[link.t] = true
            applyCandidate(tracks[link.t], candidates[link.c], nowNs)
        }
        for (ci in candidates.indices) {
            if (!usedC[ci]) createTentative(candidates[ci], nowNs)
        }
    }

    /** 帧末：更新速度、记录历史、判定生命周期、去重。 */
    fun finish(nowNs: Long) {
        val widthScale = max(frameW, 1) / 960f
        val stillSpeed = STILL_SPEED_PX_S * widthScale
        val maxSpeed = 4f * max(frameW, 1)
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val t = iterator.next()
            if (t.moved || t.decoded) {
                t.vx = 0.5f * t.vx + 0.5f * ((t.cx - t.startX) / t.frameDt)
                t.vy = 0.5f * t.vy + 0.5f * ((t.cy - t.startY) / t.frameDt)
            }
            val speed = hypot(t.vx, t.vy)
            if (speed > maxSpeed) {
                val f = maxSpeed / speed
                t.vx *= f
                t.vy *= f
            }
            t.push(nowNs)

            val outside = t.cx < -0.15f * frameW || t.cx > 1.15f * frameW ||
                t.cy < -0.15f * frameH || t.cy > 1.15f * frameH
            var remove = outside
            if (t.result == null) {
                // 候选：连续几帧没再被定位到、或者很久都没被解码确认，就撤掉
                if (t.lastCandidateNs != nowNs) t.presence *= 0.65f
                val lost = nowNs - t.lastCandidateNs > TENTATIVE_LOSS_NS
                if (nowNs - t.firstNs > TENTATIVE_MAX_NS) t.expiring = true
                if ((lost || t.expiring) && t.deathNs == 0L) {
                    if (t.visible) t.deathNs = nowNs + TRACK_FADE_NS else remove = true
                }
            } else {
                val silence = nowNs - t.evidenceNs()
                val recentMotion = nowNs - t.lastMotionOkNs < MOTION_RECENT_NS
                val limit = when {
                    t.slowOnly -> COAST_SLOW_NS
                    recentMotion && hypot(t.vx, t.vy) > stillSpeed -> COAST_MOVING_NS
                    recentMotion -> COAST_STILL_NS
                    else -> COAST_BLIND_NS
                }
                if (t.deathNs == 0L && silence > limit) t.deathNs = nowNs + TRACK_FADE_NS
            }
            if (t.deathNs != 0L && nowNs >= t.deathNs) remove = true
            if (remove) {
                // 没被确认的候选撤掉后，短时间内不在原地再冒出来，避免误检区域反复闪
                if (t.result == null && (t.visible || t.expiring)) {
                    t.bounds(boundsA)
                    suppressions.add(
                        Suppression(boundsA[0], boundsA[1], boundsA[2], boundsA[3], nowNs + SUPPRESS_NS),
                    )
                }
                iterator.remove()
            }
        }
        suppressions.removeAll { it.untilNs < nowNs }
        mergeDuplicates()
    }

    fun snapshot(nowNs: Long): ScanSnapshot {
        val list = ArrayList<TrackSnap>(tracks.size)
        for (t in tracks) {
            if (!t.visible) continue
            val q = FloatArray(8)
            for (i in 0 until 4) {
                q[i * 2] = t.cx + t.off[i * 2]
                q[i * 2 + 1] = t.cy + t.off[i * 2 + 1]
            }
            list.add(TrackSnap(t.id, q, t.vx, t.vy, t.result, t.shownNs, t.confirmedNs, t.deathNs))
        }
        return ScanSnapshot(nowNs, frameW, frameH, list)
    }

    /* ------------------------------ 内部 ------------------------------ */

    private fun applyFast(t: Track, res: ScanResult, nowNs: Long) {
        val q = res.quad ?: return
        val m = alignTo(q, t)
        val mcx = (m[0] + m[2] + m[4] + m[6]) / 4f
        val mcy = (m[1] + m[3] + m[5] + m[7]) / 4f
        val tentative = t.result == null
        val ex = mcx - t.cx
        val ey = mcy - t.cy
        val err = hypot(ex, ey)
        val size = max(t.size(), 8f)
        var k = (POS_GAIN_MIN + POS_GAIN_SLOPE * err / (0.12f * size)).coerceIn(POS_GAIN_MIN, POS_GAIN_MAX)
        // 候选的框是粗的，解码给的四个角精确得多：第一次确认时直接吸附过去
        if (tentative) k = POS_GAIN_MAX
        t.cx += k * ex
        t.cy += k * ey
        blendShape(t, m, mcx, mcy, if (tentative || k > 0.7f) 0.8f else SHAPE_GAIN)
        t.result = res
        if (tentative) {
            t.confirmedNs = nowNs
            t.expiring = false
            if (t.shownNs == 0L) t.shownNs = nowNs
        }
        t.lastDecodeNs = nowNs
        t.lastFastNs = nowNs
        t.hits++
        t.slowOnly = false
        t.deathNs = 0L
        t.decoded = true
    }

    private fun applySlow(t: Track, res: ScanResult, frameNs: Long, nowNs: Long) {
        val q = res.quad ?: return
        val m = alignTo(q, t)
        val mcx = (m[0] + m[2] + m[4] + m[6]) / 4f
        val mcy = (m[1] + m[3] + m[5] + m[7]) / 4f
        var refX = t.cx
        var refY = t.cy
        if (t.positionAt(frameNs)) {
            refX = t.tmpX
            refY = t.tmpY
        }
        val tentative = t.result == null
        val ex = mcx - refX
        val ey = mcy - refY
        val size = max(t.size(), 8f)
        val k = if (tentative) 1f else (SLOW_GAIN_MIN + 0.4f * hypot(ex, ey) / (0.15f * size)).coerceIn(SLOW_GAIN_MIN, 1f)
        t.cx += k * ex
        t.cy += k * ey
        t.shiftSince(frameNs, k * ex, k * ey)
        blendShape(t, m, mcx, mcy, if (tentative) 0.8f else 0.3f)
        t.result = res
        if (tentative) {
            t.confirmedNs = nowNs
            t.expiring = false
            if (t.shownNs == 0L) t.shownNs = nowNs
        }
        t.lastDecodeNs = nowNs
        t.hits++
        t.slowOnly = t.lastFastNs == 0L || nowNs - t.lastFastNs > 1_500_000_000L
        t.deathNs = 0L
    }

    private fun applyCandidate(t: Track, c: Candidate, nowNs: Long) {
        t.lastCandidateNs = nowNs
        if (!t.expiring) t.deathNs = 0L
        if (t.result == null) {
            t.candidateHits++
            t.presence += 0.4f * (1f - t.presence)
            // 候选框是按 16 像素的块算出来的，每帧都硬拉会让框在静止的码上抖；只轻轻拉，位置交给位移跟踪
            val gain = if (t.candidateHits < 3) 0.3f else 0.12f
            pull(t, c.quad, gain, gain)
            if (t.shownNs == 0L && t.presence >= SHOW_PRESENCE && t.candidateHits >= SHOW_MIN_HITS) {
                t.shownNs = nowNs
            }
        } else if (nowNs - t.lastDecodeNs > CANDIDATE_REFRESH_AFTER_NS) {
            // 已经好一会儿没解出来了（模糊），用定位结果轻轻校正，免得位移跟踪的漂移越积越大
            pull(t, c.quad, 0.15f, 0.1f)
        }
    }

    /** 把轨迹的中心和形状向 [quad] 拉一点。 */
    private fun pull(t: Track, quad: FloatArray, posGain: Float, shapeGain: Float) {
        val m = alignTo(quad, t)
        val mcx = (m[0] + m[2] + m[4] + m[6]) / 4f
        val mcy = (m[1] + m[3] + m[5] + m[7]) / 4f
        t.cx += posGain * (mcx - t.cx)
        t.cy += posGain * (mcy - t.cy)
        blendShape(t, m, mcx, mcy, shapeGain)
    }

    private fun createConfirmed(res: ScanResult, nowNs: Long, slow: Boolean) {
        val q = res.quad ?: return
        makeRoom()
        val t = newTrack(q, res, nowNs)
        t.hits = 1
        t.lastDecodeNs = nowNs
        t.lastFastNs = if (slow) 0L else nowNs
        t.slowOnly = slow
        t.shownNs = nowNs
        t.confirmedNs = nowNs
        t.push(nowNs)
        tracks.add(t)
    }

    private fun createTentative(c: Candidate, nowNs: Long) {
        quadBounds(c.quad, boundsA)
        for (s in suppressions) {
            boundsB[0] = s.left
            boundsB[1] = s.top
            boundsB[2] = s.right
            boundsB[3] = s.bottom
            if (boundsIou(boundsA, boundsB) > 0.4f) return
        }
        if (tracks.size >= MAX_TRACKS) return
        val t = newTrack(c.quad, null, nowNs)
        t.candidateHits = 1
        t.presence = 0.4f
        t.lastCandidateNs = nowNs
        t.push(nowNs)
        tracks.add(t)
    }

    private fun newTrack(q: FloatArray, res: ScanResult?, nowNs: Long): Track {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val off = FloatArray(8)
        for (i in 0 until 4) {
            off[i * 2] = q[i * 2] - cx
            off[i * 2 + 1] = q[i * 2 + 1] - cy
        }
        return Track(nextId++, cx, cy, off, res, nowNs, nowNs)
    }

    /** 轨迹满了就挤掉最久没有证据的那条；全是新鲜的就不收新的。 */
    private fun makeRoom() {
        if (tracks.size < MAX_TRACKS) return
        var worst = 0
        for (i in 1 until tracks.size) {
            if (tracks[i].evidenceNs() < tracks[worst].evidenceNs()) worst = i
        }
        tracks.removeAt(worst)
    }

    /**
     * 解码结果 ↔ 轨迹：内容相同按位置远近配（距离 / 码的大小），内容不同或轨迹还只是候选按外接矩形重叠配。
     * 贪心：代价从小到大，每个结果最多配一条轨迹。返回每个结果配到的轨迹下标，没配上是 -1。
     */
    private fun assign(results: List<ScanResult>, gate: Float): IntArray {
        val out = IntArray(results.size) { -1 }
        if (tracks.isEmpty()) return out
        val links = ArrayList<Link>()
        for (ri in results.indices) {
            val q = results[ri].quad ?: continue
            val rcx = (q[0] + q[2] + q[4] + q[6]) / 4f
            val rcy = (q[1] + q[3] + q[5] + q[7]) / 4f
            val rsize = CodeGeometry.size(q)
            quadBounds(q, boundsA)
            for (ti in tracks.indices) {
                val t = tracks[ti]
                val prior = t.result
                val cost: Float
                if (prior != null && prior.text == results[ri].text && prior.formatLabel == results[ri].formatLabel) {
                    cost = hypot(rcx - t.cx, rcy - t.cy) / max(max(rsize, t.size()), 8f)
                } else {
                    t.bounds(boundsB)
                    val iou = boundsIou(boundsA, boundsB)
                    val need = if (prior == null) 0.25f else 0.35f
                    cost = if (iou >= need) 1f + (1f - iou) else Float.MAX_VALUE
                }
                if (cost < gate) links.add(Link(ri, ti, cost))
            }
        }
        links.sortBy { it.cost }
        val usedT = BooleanArray(tracks.size)
        for (link in links) {
            if (out[link.c] != -1 || usedT[link.t]) continue
            out[link.c] = link.t
            usedT[link.t] = true
        }
        return out
    }

    /** 把测量的四个角重新排序，使它和轨迹当前的四个角一一对应（两者都是顺时针，只差一个循环位移）。 */
    private fun alignTo(q: FloatArray, t: Track): FloatArray {
        var bestShift = 0
        var bestCost = Float.MAX_VALUE
        for (s in 0 until 4) {
            var cost = 0f
            for (i in 0 until 4) {
                val k = (i + s) % 4
                val dx = q[k * 2] - (t.cx + t.off[i * 2])
                val dy = q[k * 2 + 1] - (t.cy + t.off[i * 2 + 1])
                cost += dx * dx + dy * dy
            }
            if (cost < bestCost) {
                bestCost = cost
                bestShift = s
            }
        }
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val k = (i + bestShift) % 4
            out[i * 2] = q[k * 2]
            out[i * 2 + 1] = q[k * 2 + 1]
        }
        return out
    }

    private fun blendShape(t: Track, aligned: FloatArray, mcx: Float, mcy: Float, gain: Float) {
        for (i in 0 until 4) {
            val ox = aligned[i * 2] - mcx
            val oy = aligned[i * 2 + 1] - mcy
            t.off[i * 2] += gain * (ox - t.off[i * 2])
            t.off[i * 2 + 1] += gain * (oy - t.off[i * 2 + 1])
        }
    }

    private fun mergeDuplicates() {
        var i = 0
        while (i < tracks.size) {
            var removedI = false
            var j = i + 1
            while (j < tracks.size) {
                tracks[i].bounds(boundsA)
                tracks[j].bounds(boundsB)
                if (boundsIou(boundsA, boundsB) > MERGE_IOU) {
                    if (rank(tracks[j]) > rank(tracks[i])) {
                        tracks.removeAt(i)
                        removedI = true
                        break
                    } else {
                        tracks.removeAt(j)
                        continue
                    }
                }
                j++
            }
            if (!removedI) i++
        }
    }

    /** 重复时留谁：已确认的优先，其次命中次数多的。 */
    private fun rank(t: Track): Int = if (t.result != null) 1000 + t.hits else t.candidateHits

    private fun quadBounds(q: FloatArray, out: FloatArray) {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val x = q[i * 2]
            val y = q[i * 2 + 1]
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        out[0] = l
        out[1] = t
        out[2] = r
        out[3] = b
    }

    private fun boundsIou(a: FloatArray, b: FloatArray): Float {
        val ix0 = max(a[0], b[0])
        val iy0 = max(a[1], b[1])
        val ix1 = min(a[2], b[2])
        val iy1 = min(a[3], b[3])
        val iw = ix1 - ix0
        val ih = iy1 - iy0
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union <= 0f) 0f else inter / union
    }
}
