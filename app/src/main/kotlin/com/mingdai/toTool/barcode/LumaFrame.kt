package com.mingdai.toTool.barcode

import androidx.camera.core.ImageProxy
import com.google.zxing.LuminanceSource
import java.nio.Buffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 一帧「方向已摆正」的 8 位灰度图，取自相机 YUV 的 Y 平面，坐标系与屏幕上看到的画面一致
 * （先按 rotationDegrees 顺时针转正，再交给识别和跟踪，后面所有坐标都在这个空间里）。
 *
 * 之前的做法是 RGBA → Bitmap → 旋转 → 缩放 → getPixels，每帧要分配并复制好几份 MB 级的数据，
 * GC 抖动会让帧间隔忽快忽慢，框就跟着一顿一顿。这里只做一次「拷贝 + 旋转」，缓冲区用对象池复用。
 *
 * [data] 用完必须调用 [release]，之后不能再读。
 */
class LumaFrame internal constructor(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    /** 帧到达分析线程的时刻（SystemClock.elapsedRealtimeNanos）。 */
    val timeNs: Long,
) {
    fun release() = LumaPool.release(data)

    companion object {
        /**
         * 从 ImageProxy 拷出一份转正后的灰度图；格式不对或数据异常时返回 null。
         * 调用方负责关闭 [image]（这里不关，方便调用方在 finally 里统一关）。
         */
        fun fromImage(image: ImageProxy, rotationDegrees: Int, timeNs: Long): LumaFrame? {
            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            if (rowStride <= 0 || pixelStride <= 0) return null

            // 正常情况下裁剪框就是整张图；有 ViewPort 时只取可见部分
            var cx = 0
            var cy = 0
            var cw = image.width
            var ch = image.height
            val crop = image.cropRect
            if (crop.width() > 0 && crop.height() > 0 && crop.left >= 0 && crop.top >= 0 &&
                crop.right <= image.width && crop.bottom <= image.height
            ) {
                cx = crop.left
                cy = crop.top
                cw = crop.width()
                ch = crop.height()
            }

            val rot = ((rotationDegrees % 360) + 360) % 360
            val swap = rot == 90 || rot == 270
            val outW = if (swap) ch else cw
            val outH = if (swap) cw else ch
            val out = LumaPool.acquire(outW * outH)
            val row = ByteArray(rowStride)
            val limit = buffer.limit()
            val base = cx * pixelStride
            val need = base + (cw - 1) * pixelStride + 1

            for (y in 0 until ch) {
                val start = (cy + y) * rowStride
                val n = minOf(rowStride, limit - start)
                if (n < need) {
                    LumaPool.release(out)
                    return null
                }
                // 先转成 Buffer 再 position：ByteBuffer.position(int) 的协变返回类型在低版本系统上不存在
                (buffer as Buffer).position(start)
                buffer.get(row, 0, n)
                when (rot) {
                    0 -> {
                        val o = y * outW
                        for (x in 0 until cw) out[o + x] = row[base + x * pixelStride]
                    }
                    90 -> {
                        // 顺时针转 90°：(x, y) → (ch-1-y, x)
                        val xp = ch - 1 - y
                        for (x in 0 until cw) out[x * outW + xp] = row[base + x * pixelStride]
                    }
                    180 -> {
                        val o = (ch - 1 - y) * outW + (cw - 1)
                        for (x in 0 until cw) out[o - x] = row[base + x * pixelStride]
                    }
                    else -> {
                        // 270：(x, y) → (y, cw-1-x)
                        for (x in 0 until cw) out[(cw - 1 - x) * outW + y] = row[base + x * pixelStride]
                    }
                }
            }
            return LumaFrame(out, outW, outH, timeNs)
        }
    }
}

/** 灰度缓冲池：同一尺寸的数组循环使用，尺寸变了（比如转屏）就丢掉旧的。 */
internal object LumaPool {
    private val free = ConcurrentLinkedQueue<ByteArray>()

    fun acquire(size: Int): ByteArray {
        while (true) {
            val buffer = free.poll() ?: return ByteArray(size)
            if (buffer.size == size) return buffer
        }
    }

    fun release(buffer: ByteArray) {
        if (free.size < 4) free.offer(buffer)
    }
}

/**
 * 给 ZXing 用的亮度源，直接读灰度数组：
 * - 支持裁剪：GenericMultipleBarcodeReader 找到一个码后要裁出周围区域再找别的码；
 * - 支持逆时针旋转 90°：一维码解码器在 TRY_HARDER 下靠它去读竖着放的条码。
 *   实时快路径里关掉旋转（[allowRotate] = false），省掉每帧一次整图旋转 + 重新二值化。
 */
class LumaSource private constructor(
    private val pixels: ByteArray,
    private val dataWidth: Int,
    private val dataHeight: Int,
    private val left: Int,
    private val top: Int,
    w: Int,
    h: Int,
    private val allowRotate: Boolean,
) : LuminanceSource(w, h) {

    constructor(frame: LumaFrame, allowRotate: Boolean) :
        this(frame.data, frame.width, frame.height, 0, 0, frame.width, frame.height, allowRotate)

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        require(y in 0 until height) { "Requested row is outside the image: $y" }
        val w = width
        val out = if (row == null || row.size < w) ByteArray(w) else row
        System.arraycopy(pixels, (y + top) * dataWidth + left, out, 0, w)
        return out
    }

    override fun getMatrix(): ByteArray {
        val w = width
        val h = height
        if (w == dataWidth && h == dataHeight) return pixels
        val matrix = ByteArray(w * h)
        var offset = top * dataWidth + left
        if (w == dataWidth) {
            System.arraycopy(pixels, offset, matrix, 0, w * h)
            return matrix
        }
        for (y in 0 until h) {
            System.arraycopy(pixels, offset, matrix, y * w, w)
            offset += dataWidth
        }
        return matrix
    }

    override fun isCropSupported(): Boolean = true

    override fun crop(left: Int, top: Int, width: Int, height: Int): LuminanceSource =
        LumaSource(
            pixels, dataWidth, dataHeight,
            this.left + left, this.top + top, width, height, allowRotate,
        )

    override fun isRotateSupported(): Boolean = allowRotate

    override fun rotateCounterClockwise(): LuminanceSource {
        val w = width
        val h = height
        val rotated = ByteArray(w * h)
        // 逆时针 90°：(x, y) → (y, w-1-x)，新图宽 h、高 w
        for (y in 0 until h) {
            val rowStart = (y + top) * dataWidth + left
            for (x in 0 until w) {
                rotated[(w - 1 - x) * h + y] = pixels[rowStart + x]
            }
        }
        return LumaSource(rotated, h, w, 0, 0, h, w, allowRotate)
    }
}
