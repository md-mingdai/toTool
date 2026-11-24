package com.mingdai.toTool.weaknet

import kotlin.math.max

private const val MAX_BURST_BYTES = 64 * 1024

/** 共享令牌桶；每 1 KB/s 对应 1024 字节/秒。0 表示不限速。 */
internal class RateLimiter {
    private val lock = Any()
    private var bytesPerSecond = 0.0
    private var tokens = 0.0
    private var lastNs = System.nanoTime()

    fun setRate(kilobytesPerSecond: Int) {
        synchronized(lock) {
            bytesPerSecond = max(0, kilobytesPerSecond) * 1024.0
            if (bytesPerSecond <= 0) tokens = 0.0
            refillLocked()
        }
    }

    fun acquire(bytes: Int) {
        while (true) {
            val waitMs = synchronized(lock) {
                if (bytesPerSecond <= 0) return
                refillLocked()
                if (tokens >= bytes) {
                    tokens -= bytes
                    return
                }
                ((bytes - tokens) / bytesPerSecond * 1000.0).toLong().coerceIn(1, 200)
            }
            try {
                // 睡眠时不持有锁，暂停/停止或更新限速不会被等待中的连接卡住。
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun refillLocked() {
        val now = System.nanoTime()
        val elapsed = (now - lastNs) / 1_000_000_000.0
        lastNs = now
        if (bytesPerSecond <= 0) return
        // 桶容量至少覆盖一次 64KB 读取；否则 1KB/s 等低速档永远攒不够一块，连接会永久等待。
        val capacity = maxOf(bytesPerSecond, MAX_BURST_BYTES.toDouble())
        tokens = (tokens + elapsed * bytesPerSecond).coerceAtMost(capacity)
    }
}
