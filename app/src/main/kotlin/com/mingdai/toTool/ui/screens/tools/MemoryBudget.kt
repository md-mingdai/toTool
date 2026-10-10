package com.mingdai.toTool.ui.screens.tools

import android.app.ActivityManager
import android.content.Context

/**
 * 估算“现在能安全用多少内存处理图片”。
 *
 * 目的：在解码之前就决定要不要缩小，而不是等 OutOfMemoryError 抛出来再补救
 * （OOM 之后进程往往已经处于不稳定状态，捕获它只是兜底）。
 */
object MemoryBudget {
    private const val MB = 1024L * 1024L

    /** 本次图片处理最多可以占用的字节数（已留出余量）。 */
    fun imageBudgetBytes(context: Context): Long {
        val rt = Runtime.getRuntime()
        // Java 堆当前还能增长多少
        val heapRoom = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        // 整机还剩多少可用内存（Bitmap 像素在 native 内存里，受整机内存限制）
        val systemRoom = if (am != null) {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.availMem - info.threshold).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }

        var budget = minOf((heapRoom * 0.7).toLong(), (systemRoom * 0.5).toLong())
        budget = budget.coerceIn(48 * MB, 768 * MB)
        if (am?.isLowRamDevice == true) budget = minOf(budget, 128 * MB)
        return budget
    }

    /**
     * 在 [peakBytesPerPixel]（处理过程中每个像素的峰值内存）下，最多能处理多少像素。
     * 参考值：只解码 + 编码约 5；WEBP 编码约 10；高斯模糊这类要额外 3 个 Int 数组的约 12。
     */
    fun maxPixels(context: Context, peakBytesPerPixel: Double): Long =
        (imageBudgetBytes(context) / peakBytesPerPixel).toLong().coerceAtLeast(1_000_000L)
}
