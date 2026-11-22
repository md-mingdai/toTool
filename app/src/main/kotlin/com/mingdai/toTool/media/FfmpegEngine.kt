package com.mingdai.toTool.media

import android.content.Context
import android.os.Build
import com.mingdai.toTool.R
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/** 安装包里带了内置 ffmpeg 的 CPU 架构，和 ffmpeg/build.sh 里的 ALL_ABIS 保持一致。 */
internal val FFMPEG_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

internal data class FfmpegCapabilities(
    val encoders: Set<String>?,
    val filters: Set<String>?,
)

internal fun ffmpegBinary(context: Context): File? {
    val f = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
    return if (f.exists() && f.canExecute()) f else null
}

internal fun ffmpegUnavailableMessage(context: Context): String {
    val abis = Build.SUPPORTED_ABIS.joinToString(", ").ifEmpty { "未知" }
    val supported = Build.SUPPORTED_ABIS.any { it in FFMPEG_ABIS }
    return context.getString(
        if (supported) R.string.format_extract_fail else R.string.format_unsupported_abi,
        abis,
    )
}

internal fun probeCapabilities(ffmpeg: File): FfmpegCapabilities {
    val encoders = runCatching {
        val out = runProbe(ffmpeg, listOf("-hide_banner", "-encoders")) ?: return@runCatching null
        Regex("""^\s*[VAS][A-Z.]{5}\s+(\S+)""", RegexOption.MULTILINE)
            .findAll(out)
            .map { it.groupValues[1] }
            .toSet()
            .ifEmpty { null }
    }.getOrNull()
    val filters = runCatching {
        val out = runProbe(ffmpeg, listOf("-hide_banner", "-filters")) ?: return@runCatching null
        Regex("""^\s*[TSC.]{3}\s+(\S+)""", RegexOption.MULTILINE)
            .findAll(out)
            .map { it.groupValues[1] }
            .toSet()
            .ifEmpty { null }
    }.getOrNull()
    return FfmpegCapabilities(encoders, filters)
}

private fun runProbe(ffmpeg: File, args: List<String>): String? = try {
    val process = ProcessBuilder(listOf(ffmpeg.absolutePath) + args)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor(12, TimeUnit.SECONDS)) text else {
        process.destroyForcibly()
        null
    }
} catch (e: Exception) {
    null
}

/** 取消标志 + 当前进程；UI 和后台线程共享。 */
internal class ConversionControl {
    private val process = AtomicReference<Process?>(null)
    val flag = AtomicBoolean(false)

    val canceled: Boolean get() = flag.get()

    fun attach(target: Process) {
        process.set(target)
        if (flag.get()) target.destroyForcibly()
    }

    fun detach(target: Process) {
        process.compareAndSet(target, null)
    }

    fun cancel() {
        flag.set(true)
        process.get()?.destroyForcibly()
    }

    fun reset() {
        flag.set(false)
        process.set(null)
    }
}

internal sealed interface ConvertResult {
    data object Success : ConvertResult
    data object Canceled : ConvertResult
    data class Failure(val message: String) : ConvertResult
}

private val DURATION_PATTERN = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+)\\.(\\d+)")
private val TIME_PATTERN = Pattern.compile("time=\\s*(\\d+):(\\d+):(\\d+)\\.(\\d+)")

private fun parseSeconds(m: java.util.regex.Matcher): Double {
    val h = m.group(1)?.toIntOrNull() ?: 0
    val min = m.group(2)?.toIntOrNull() ?: 0
    val s = m.group(3)?.toIntOrNull() ?: 0
    val frac = m.group(4)?.let { ("0.$it").toDoubleOrNull() } ?: 0.0
    return h * 3600.0 + min * 60.0 + s + frac
}

private fun forEachBoundedLine(reader: java.io.Reader, maxLen: Int, onLine: (String) -> Unit) {
    val buf = CharArray(8192)
    val sb = StringBuilder()
    while (true) {
        val n = reader.read(buf)
        if (n < 0) break
        for (i in 0 until n) {
            val c = buf[i]
            if (c == '\n' || c == '\r') {
                if (sb.isNotEmpty()) {
                    onLine(sb.toString())
                    sb.setLength(0)
                }
            } else if (sb.length < maxLen) {
                sb.append(c)
            }
        }
    }
    if (sb.isNotEmpty()) onLine(sb.toString())
}

internal fun describeFailure(exit: Int, tail: List<String>): String {
    val text = tail.joinToString("\n")
    return when {
        text.contains("No space left", ignoreCase = true) -> "存储空间不足，转换中断。请清理空间后重试。"
        text.contains("Cannot allocate memory", ignoreCase = true) ||
            text.contains("Out of memory", ignoreCase = true) -> "设备内存不足，无法处理这个文件。"
        text.contains("moov atom not found", ignoreCase = true) ||
            text.contains("Invalid data found", ignoreCase = true) -> "文件已损坏或不完整，无法读取。"
        text.contains("does not contain any stream", ignoreCase = true) ||
            text.contains("Stream map '0:a:0' matches no streams", ignoreCase = true) ->
            "这个文件里没有可用的音轨。"
        exit == 137 || exit == -9 -> "转换进程被系统终止（通常是内存不足）。"
        else -> "退出代码：$exit\n$text"
    }
}

/**
 * 运行 ffmpeg。`expectedDurationSec` 用于图片循环这类 ffmpeg 无法从输入读出 Duration 的任务；
 * 其它任务留空，仍从 ffmpeg 的 Duration 行解析。
 */
internal fun runFfmpeg(
    cmd: List<String>,
    control: ConversionControl,
    expectedDurationSec: Double?,
    onProgress: (Float?) -> Unit,
): ConvertResult {
    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
    control.attach(process)
    if (control.canceled) process.destroyForcibly()
    val tail = ArrayDeque<String>()
    var parsedDuration = 0.0
    var progressDuration = expectedDurationSec ?: 0.0
    try {
        process.inputStream.bufferedReader().use { reader ->
            forEachBoundedLine(reader, 4096) { line ->
                val dm = DURATION_PATTERN.matcher(line)
                if (parsedDuration <= 0.0 && dm.find()) {
                    parsedDuration = parseSeconds(dm)
                    if (progressDuration <= 0.0) progressDuration = parsedDuration
                }
                val tm = TIME_PATTERN.matcher(line)
                if (tm.find()) {
                    val seconds = parseSeconds(tm)
                    onProgress(
                        if (progressDuration > 0.0) {
                            (seconds / progressDuration).toFloat().coerceIn(0f, 0.99f)
                        } else {
                            null
                        },
                    )
                } else if (line.isNotBlank()) {
                    tail.addLast(line.trim())
                    if (tail.size > 8) tail.removeFirst()
                }
            }
        }
        val exit = process.waitFor()
        if (control.canceled) return ConvertResult.Canceled
        return if (exit == 0) ConvertResult.Success else ConvertResult.Failure(describeFailure(exit, tail))
    } finally {
        control.detach(process)
        process.destroyForcibly()
    }
}
