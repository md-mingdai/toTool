package com.mingdai.toTool.permission

import android.content.Context
import com.mingdai.toTool.shizuku.ShizukuAccess
import com.mingdai.toTool.shizuku.ShizukuState
import com.mingdai.toTool.ui.screens.tools.ShellRun
import com.mingdai.toTool.ui.screens.tools.pumpStream
import com.mingdai.toTool.ui.screens.tools.runShizukuShell
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.concurrent.thread

enum class HighPermissionStrategy { SHIZUKU, ROOT }

data class HighPermissionStatus(
    val strategy: HighPermissionStrategy,
    val ready: Boolean,
    val title: String,
    val detail: String,
    val identity: String?,
)

class HighPermissionRun {
    @Volatile
    private var stopper: (() -> Unit)? = null

    internal fun bind(stopper: () -> Unit) {
        this.stopper = stopper
    }

    fun stop() {
        runCatching { stopper?.invoke() }
    }
}

/**
 * 高级权限统一入口：设置页选择 Shizuku 或 Root，Shell/米游记录获取等工具只调用这里。
 * 执行接口与具体权限来源无关，返回值是进程退出码。
 */
object HighPermissionManager {
    private const val PREFS = "high_permission"
    private const val KEY_STRATEGY = "strategy"
    private val rootPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
        "/data/adb/magisk/su", "/data/adb/ksu/bin/su",
    )

    fun strategy(context: Context): HighPermissionStrategy {
        val stored = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STRATEGY, null)
        return runCatching { HighPermissionStrategy.valueOf(stored ?: "") }
            .getOrDefault(HighPermissionStrategy.SHIZUKU)
    }

    fun setStrategy(context: Context, strategy: HighPermissionStrategy) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STRATEGY, strategy.name).apply()
    }

    fun rootAvailable(): Boolean =
        rootPaths.any { File(it).exists() } ||
            runCatching { ProcessBuilder("which", "su").start().waitFor() == 0 }.getOrDefault(false)

    fun status(context: Context): HighPermissionStatus =
        when (val strategy = strategy(context)) {
            HighPermissionStrategy.SHIZUKU -> {
                val state = ShizukuAccess.state(context)
                HighPermissionStatus(
                    strategy = strategy,
                    ready = state == ShizukuState.READY,
                    title = "Shizuku",
                    detail = when (state) {
                        ShizukuState.READY -> "已授权，使用 ${ShizukuAccess.identity() ?: "Shizuku 服务"} 执行"
                        ShizukuState.NEEDS_PERMISSION -> "尚未授权 Shizuku"
                        ShizukuState.NOT_INSTALLED -> "未安装 Shizuku"
                        ShizukuState.NOT_RUNNING -> "Shizuku 未运行"
                        ShizukuState.DENIED -> "Shizuku 授权被拒绝"
                        ShizukuState.UNSUPPORTED -> "Shizuku 版本过旧"
                    },
                    identity = if (state == ShizukuState.READY) ShizukuAccess.identity() else null,
                )
            }
            HighPermissionStrategy.ROOT -> {
                val available = rootAvailable()
                HighPermissionStatus(
                    strategy = strategy,
                    ready = available,
                    title = "Root",
                    detail = if (available) {
                        "将使用系统 su 执行；首次执行时 Root 管理器可能弹窗授权"
                    } else {
                        "未找到 su，请确认设备已 Root 且授权管理器可用"
                    },
                    identity = if (available) "root（su）" else null,
                )
            }
        }

    fun requestPermission(context: Context): String? =
        if (strategy(context) == HighPermissionStrategy.SHIZUKU) {
            ShizukuAccess.requestPermission()
        } else {
            null
        }

    fun execute(
        context: Context,
        script: String,
        arguments: List<String> = emptyList(),
        stdinText: String? = null,
        run: HighPermissionRun = HighPermissionRun(),
        onLine: (isError: Boolean, line: String) -> Unit,
    ): Int = when (strategy(context)) {
        HighPermissionStrategy.ROOT -> runRootShell(script, arguments, stdinText, run, onLine)
        HighPermissionStrategy.SHIZUKU -> {
            val shellRun = ShellRun()
            run.bind { shellRun.stop() }
            runShizukuShell(script, shellRun, arguments, stdinText, onLine)
        }
    }

    private fun runRootShell(
        script: String,
        arguments: List<String>,
        stdinText: String?,
        run: HighPermissionRun,
        onLine: (isError: Boolean, line: String) -> Unit,
    ): Int {
        val command = buildString {
            append("sh -c ").append(quote(script)).append(" sh")
            arguments.forEach { append(' ').append(quote(it)) }
        }
        val process = ProcessBuilder("su", "-c", command).start()
        run.bind {
            thread(name = "highpermission-root-stop", isDaemon = true) {
                runCatching { process.destroy() }
            }
        }
        val stdin = process.outputStream
        val stdinThread = if (stdinText != null) {
            thread(name = "highpermission-stdin", isDaemon = true) {
                try {
                    stdin.write(stdinText.toByteArray(Charsets.UTF_8))
                    stdin.flush()
                } catch (_: Exception) {
                } finally {
                    runCatching { stdin.close() }
                }
            }
        } else {
            runCatching { stdin.close() }
            null
        }
        val out = pumpStream(process.inputStream, false, onLine)
        val err = pumpStream(process.errorStream, true, onLine)
        val code = process.waitFor()
        runCatching { stdin.close() }
        stdinThread?.join(800)
        out.join(2000)
        err.join(2000)
        if (out.isAlive || err.isAlive) {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            out.join(500)
            err.join(500)
        }
        return code
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
