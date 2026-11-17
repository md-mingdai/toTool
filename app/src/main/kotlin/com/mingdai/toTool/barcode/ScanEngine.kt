package com.mingdai.toTool.barcode

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

private const val SLOW_MIN_GAP_NS = 80_000_000L
private const val SLOW_BUDGET_NS = 700_000_000L

/**
 * 实时扫描引擎：把相机帧变成「每个码一条平滑、不丢的轨迹」，界面只管读 [snapshot] 画出来。
 *
 * 线程模型：
 * - 分析线程（CameraX 的 Analyzer）：转灰度 → 位移估计 → 快路径解码 + 定位 → 跟踪器 → 发布快照。
 *   跟踪器只在这个线程里读写，不需要锁；
 * - 慢路径线程：汉信码 / Code 49、反色、竖放一维码这些要几百毫秒的活，单飞（上一轮没结束不开下一轮），
 *   结果放进队列，下一帧由分析线程取走。慢路径再也不会拖住 QR / 一维码的结果了；
 * - 主线程：每个 vsync 读快照、外推到此刻、画出来。
 *
 * 一个引擎对应一次相机绑定：重新绑定时丢掉旧的、新建一个，不跨次复用状态。
 */
class ScanEngine {

    /** 最近一帧处理完的轨迹快照；不可变，任何线程都可以读。 */
    @Volatile
    var snapshot: ScanSnapshot? = null
        private set

    private val tracker = CodeTracker()
    private val motion = MotionEstimator()
    private val locator = BarcodeLocator()
    private val slowQueue = ConcurrentLinkedQueue<SlowBatch>()
    private val slowBusy = AtomicBoolean(false)

    @Volatile
    private var running = false

    @Volatile
    private var nextSlowPhase = SlowPhase.ZXING
    private var lastSlowStartNs = 0L

    private var analysisExecutor: ExecutorService? = null
    private var slowExecutor: ExecutorService? = null

    private class SlowBatch(val results: List<ScanResult>, val frameNs: Long)

    /** 启动，返回给 ImageAnalysis.setAnalyzer 用的线程。 */
    fun start(): ExecutorService {
        stop()
        running = true
        val analysis = Executors.newSingleThreadExecutor()
        analysisExecutor = analysis
        slowExecutor = Executors.newSingleThreadExecutor()
        return analysis
    }

    fun stop() {
        running = false
        analysisExecutor?.shutdown()
        slowExecutor?.shutdown()
        analysisExecutor = null
        slowExecutor = null
        slowQueue.clear()
        snapshot = null
    }

    /**
     * 处理一帧。无论发生什么都会关掉 [image]（不关会让相机管线停住）。
     * [rotationProvider] 给出让这一帧转正所需的顺时针角度。
     */
    fun analyze(image: ImageProxy, rotationProvider: (ImageProxy) -> Int) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        var frame: LumaFrame? = null
        try {
            if (running) {
                val rotation = try {
                    rotationProvider(image)
                } catch (e: Throwable) {
                    image.imageInfo.rotationDegrees
                }
                frame = LumaFrame.fromImage(image, rotation, nowNs)
            }
        } catch (e: Throwable) {
            frame = null
        } finally {
            image.close()
        }
        if (frame == null) return

        var handedOver = false
        try {
            handedOver = process(frame, nowNs)
        } catch (e: Throwable) {
            // 单帧出错不影响后面的帧
        } finally {
            if (!handedOver) frame.release()
        }
    }

    /** 返回 true 表示这帧交给了慢路径线程，由它负责释放。 */
    private fun process(frame: LumaFrame, nowNs: Long): Boolean {
        if (frame.width != tracker.frameW || frame.height != tracker.frameH) {
            tracker.reset(frame.width, frame.height)
            motion.reset()
        }
        motion.begin(frame)
        tracker.advance(nowNs, motion)

        while (true) {
            val batch = slowQueue.poll() ?: break
            tracker.onSlow(batch.results, batch.frameNs, nowNs)
        }

        val fast = runCatching { BarcodeScanner.scanFast(frame) }.getOrNull()
        val located = runCatching { locator.locate(frame) }.getOrNull().orEmpty()
        if (fast != null) tracker.onFast(fast.results, nowNs)
        tracker.onCandidates((fast?.candidates.orEmpty()) + located, nowNs)
        tracker.finish(nowNs)
        motion.end()
        if (running) snapshot = tracker.snapshot(nowNs)

        return startSlow(frame, nowNs)
    }

    private fun startSlow(frame: LumaFrame, nowNs: Long): Boolean {
        val executor = slowExecutor ?: return false
        if (nowNs - lastSlowStartNs < SLOW_MIN_GAP_NS) return false
        if (!slowBusy.compareAndSet(false, true)) return false
        lastSlowStartNs = nowNs
        val phase = nextSlowPhase
        try {
            executor.execute {
                var customHit = false
                try {
                    val deadline = System.nanoTime() + SLOW_BUDGET_NS
                    val results = BarcodeScanner.scanSlow(frame, phase, deadline)
                    if (running && results.isNotEmpty()) slowQueue.add(SlowBatch(results, frame.timeNs))
                    customHit = phase == SlowPhase.CUSTOM && results.isNotEmpty()
                } catch (e: Throwable) {
                    // 慢路径失败不影响实时路径
                } finally {
                    frame.release()
                    // 汉信码刚命中就继续盯着它，否则两种活轮流做
                    nextSlowPhase = when {
                        phase == SlowPhase.CUSTOM && customHit -> SlowPhase.CUSTOM
                        phase == SlowPhase.ZXING -> SlowPhase.CUSTOM
                        else -> SlowPhase.ZXING
                    }
                    slowBusy.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            slowBusy.set(false)
            return false
        }
        return true
    }
}
