package com.mingdai.toTool.ui.screens.tools

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import kotlin.concurrent.thread
import rikka.shizuku.Shizuku

/*
 * 「执行 Shell 指令」的后台部分：检查 Shizuku 状态、在 Shizuku 的 shell 进程里执行命令、
 * 把输出整理成一行一行的日志。界面在 ShellTool.kt。
 *
 * 命令是怎么跑起来的：向 Shizuku 要一个远程进程，执行 `sh -c <命令或脚本全文>`，
 * 所以管道、重定向、&&、多行脚本都可以直接用。进程以 Shizuku 服务的身份运行：
 * 用无线调试 / adb 启动的是 shell（uid 2000），用 root 启动的是 root（uid 0）。
 */

/** Shizuku 管理器的包名。 */
internal const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/** 申请授权时用的请求码，回调里靠它认出是自己发的请求。 */
internal const val SHIZUKU_REQUEST_CODE = 7001

/** 脚本文件的大小上限。整份脚本是作为 `sh -c` 的一个参数传过去的，单个参数不能超过约 128 KB。 */
internal const val SHELL_SCRIPT_MAX_BYTES = 100 * 1024

/** 日志最多保留的行数，超过的从最早的开始丢。 */
private const val SHELL_LOG_MAX_LINES = 20000

/** 单行最多保留的字符数。没有换行的超长输出（比如二进制）会按这个长度硬折行，免得撑爆内存。 */
private const val SHELL_LINE_MAX_CHARS = 4000

/* ───────────────────────── Shizuku 状态 ───────────────────────── */

/** Shizuku 现在能为我们做什么。 */
internal enum class ShizukuState {
    /** 没有安装 Shizuku，也没有 Sui。 */
    NOT_INSTALLED,

    /** 装了，但服务没有启动。 */
    NOT_RUNNING,

    /** 服务版本太旧（v11 之前），不支持授权流程。 */
    UNSUPPORTED,

    /** 服务在运行，本应用还没被授权。 */
    NEEDS_PERMISSION,

    /** 用户选过「拒绝且不再询问」，只能去 Shizuku 里手动打开。 */
    DENIED,

    /** 已授权，可以执行命令。 */
    READY,
}

internal fun isShizukuInstalled(context: Context): Boolean =
    try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

/** 读取当前状态。Shizuku 的接口只能在 Binder 活着的时候调用，所以先 ping。 */
internal fun shizukuState(context: Context): ShizukuState =
    try {
        if (!Shizuku.pingBinder()) {
            if (isShizukuInstalled(context)) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
        } else if (Shizuku.isPreV11()) {
            ShizukuState.UNSUPPORTED
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuState.READY
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            ShizukuState.DENIED
        } else {
            ShizukuState.NEEDS_PERMISSION
        }
    } catch (e: Throwable) {
        // 服务恰好在这一刻停掉之类的极端情况：当作没有运行
        ShizukuState.NOT_RUNNING
    }

/** Shizuku 服务以什么身份运行：adb 启动是 shell（2000），root 启动是 root（0）。 */
internal fun shizukuIdentity(): String? =
    try {
        when (val uid = Shizuku.getUid()) {
            0 -> "root（uid 0）"
            2000 -> "shell（uid 2000）"
            else -> "uid $uid"
        }
    } catch (e: Throwable) {
        null
    }

/* ───────────────────────── 日志 ───────────────────────── */

internal enum class ShellLineKind {
    /** 命令的标准输出。 */
    OUT,

    /** 命令的标准错误。 */
    ERR,

    /** 工具自己打的提示：正在执行的命令、已停止等。 */
    INFO,

    /** 工具自己打的提示：成功结束。 */
    OK,

    /** 工具自己打的提示：失败。 */
    FAIL,
}

/** [id] 单调递增，列表用它当 key：从头部丢旧行时，正在看的那一行不会跳。 */
internal class ShellLine(val id: Long, val kind: ShellLineKind, val text: String)

/**
 * 线程安全的日志缓冲。标准输出、标准错误由各自的读取线程写入，界面定时取快照显示。
 */
internal class ShellLog(private val maxLines: Int = SHELL_LOG_MAX_LINES) {
    private val lock = Any()
    private val lines = ArrayDeque<ShellLine>()
    private var nextId = 0L
    private var changes = 0L
    private var dropped = 0

    fun add(kind: ShellLineKind, text: String) {
        synchronized(lock) {
            lines.addLast(ShellLine(nextId++, kind, text))
            while (lines.size > maxLines) {
                lines.removeFirst()
                dropped++
            }
            changes++
        }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            dropped = 0
            changes++
        }
    }

    /** 每次内容变化都会加一，界面据此判断要不要刷新。 */
    fun version(): Long = synchronized(lock) { changes }

    fun snapshot(): List<ShellLine> = synchronized(lock) { lines.toList() }

    /** 因为超出上限而被丢掉的最早的行数。 */
    fun droppedCount(): Int = synchronized(lock) { dropped }

    fun isEmpty(): Boolean = synchronized(lock) { lines.isEmpty() }

    /** 整份日志的纯文本，用于复制 / 导出。 */
    fun plainText(): String = synchronized(lock) {
        val note = if (dropped > 0) "（日志过长，已省略最早的 $dropped 行）\n" else ""
        note + lines.joinToString("\n") { it.text }
    }
}

/* ───────────────────────── 执行 ───────────────────────── */

/** 一次执行的句柄：界面用它来中止。 */
internal class ShellRun {
    @Volatile
    var process: Process? = null

    @Volatile
    var stopped = false
        private set

    /**
     * 结束 Shell 进程。它启动的后台子进程不一定会跟着退出。
     * destroy() 是一次 Binder 调用，放到单独的线程里做，免得卡住界面线程。
     */
    fun stop() {
        stopped = true
        val target = process ?: return
        thread(name = "shell-stop", isDaemon = true) {
            try {
                target.destroy()
            } catch (e: Throwable) {
                // 进程已经结束，或者 Shizuku 的连接已经断了
            }
        }
    }
}

/**
 * 通过 Shizuku 启动一个远程进程。
 *
 * Shizuku 把 `Shizuku.newProcess` 标成了 private + 已弃用（计划在 API 14 移除，官方建议改用 UserService），
 * 但 UserService 需要自己写 AIDL 并把服务类跑在 Shizuku 的进程里，对「执行一条命令」来说过重，
 * 所以这里用反射调它。反射失败时（比如以后的版本真的删了）错误会原样抛给调用方，界面上会显示出来。
 */
private fun newRemoteProcess(cmd: Array<String>): Process {
    val method = Shizuku::class.java.getDeclaredMethod(
        "newProcess",
        Array<String>::class.java,
        Array<String>::class.java,
        String::class.java,
    )
    method.isAccessible = true
    try {
        return method.invoke(null, cmd, null, null) as Process
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }
}

/**
 * 把读到的文本切成一行一行：处理 \r\n、单独的 \r（进度条之类）、超长行，并去掉终端控制字符。
 * 每个输出流各用一个实例，只会被它自己的读取线程调用。
 */
private class LineSplitter(private val emit: (String) -> Unit) {
    private val buffer = StringBuilder()

    /** 上一个字符是 \r：紧跟着的 \n 属于同一个换行，不再算一行。 */
    private var skipLineFeed = false

    fun feed(chunk: CharSequence) {
        for (ch in chunk) {
            when (ch) {
                '\n' -> {
                    if (skipLineFeed) skipLineFeed = false else flush()
                }
                '\r' -> {
                    flush()
                    skipLineFeed = true
                }
                else -> {
                    skipLineFeed = false
                    buffer.append(ch)
                    if (buffer.length >= SHELL_LINE_MAX_CHARS) flush()
                }
            }
        }
    }

    /** 流结束时，把最后没有换行的半行也输出。 */
    fun finish() {
        if (buffer.isNotEmpty()) flush()
    }

    private fun flush() {
        emit(cleanTerminalText(buffer.toString()))
        buffer.setLength(0)
    }
}

/** ANSI 转义序列（颜色、光标移动等）。 */
private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

/** 去掉 ANSI 转义序列和其他控制字符（制表符保留）。「FFmpeg 命令」的日志也用它。 */
internal fun cleanTerminalText(line: String): String {
    val noAnsi = if (line.indexOf('\u001B') >= 0) ANSI_ESCAPE.replace(line, "") else line
    if (noAnsi.none { it < ' ' && it != '\t' || it == '\u007F' }) return noAnsi
    return buildString(noAnsi.length) {
        for (ch in noAnsi) {
            if ((ch >= ' ' || ch == '\t') && ch != '\u007F') append(ch)
        }
    }
}

/** 起一个线程读一个输出流，读到结束为止。 */
private fun pumpStream(
    stream: InputStream,
    isError: Boolean,
    onLine: (isError: Boolean, line: String) -> Unit,
): Thread = thread(name = if (isError) "shell-stderr" else "shell-stdout", isDaemon = true) {
    val splitter = LineSplitter { line -> onLine(isError, line) }
    try {
        // InputStreamReader 会正确处理被读取边界切开的多字节字符（比如一个汉字的三个字节分在两次读取里）
        val reader = InputStreamReader(stream, Charsets.UTF_8)
        val buffer = CharArray(4096)
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            if (n > 0) splitter.feed(String(buffer, 0, n))
        }
    } catch (e: Exception) {
        // 流被关掉了（进程结束，或者点了停止），或者 Shizuku 的连接断了：读到这里为止
    } finally {
        splitter.finish()
        try {
            stream.close()
        } catch (e: Exception) {
            // 忽略
        }
    }
}

/**
 * 在 Shizuku 的 shell 里执行 [script]（交给 `sh -c`），输出按行回调 [onLine]。
 * 会一直阻塞到命令结束，返回退出码；请在后台线程调用。进程可以随时用 [run] 的 stop() 结束。
 */
internal fun runShizukuShell(
    script: String,
    run: ShellRun,
    arguments: List<String> = emptyList(),
    stdinText: String? = null,
    onLine: (isError: Boolean, line: String) -> Unit,
): Int {
    // sh -c 后的第一个参数会成为 $0，所以再放一个 "sh"，后面的 arguments 才是 $1、$2…
    val command = arrayOf("sh", "-c", script, "sh") + arguments.toTypedArray()
    val process = newRemoteProcess(command)
    run.process = process
    // 启动的同时刚好点了停止：进程还没登记上，stop() 没能结束它
    if (run.stopped) {
        try {
            process.destroy()
        } catch (e: Throwable) {
            // 忽略
        }
    }
    // 三条管道各取一次、各留一个引用：后面关闭用的就是读取线程正在读的那个流
    val stdin = process.outputStream
    val stdout = process.inputStream
    val stderr = process.errorStream
    // 标准输入：没设置固定值时立刻关掉，命令读到 EOF 而不是一直卡着等；
    // 设置固定值后只写入这份内容并关闭，脚本无法再读到其它输入。
    val stdinThread = if (stdinText != null) {
        thread(name = "shell-stdin", isDaemon = true) {
            try {
                stdin.write(stdinText.toByteArray(Charsets.UTF_8))
                stdin.flush()
            } catch (e: Exception) {
                // 脚本没读输入就退出时管道会断，忽略
            } finally {
                try {
                    stdin.close()
                } catch (e: Exception) {
                    // 忽略
                }
            }
        }
    } else {
        try {
            stdin.close()
        } catch (e: Exception) {
            // 忽略
        }
        null
    }
    val outThread = pumpStream(stdout, false, onLine)
    val errThread = pumpStream(stderr, true, onLine)
    val code = process.waitFor()
    // 进程结束后关闭标准输入，叫醒可能被管道写满卡住的写入线程。
    try {
        stdin.close()
    } catch (e: Exception) {
        // 忽略
    }
    stdinThread?.join(800)
    // 进程结束后两条管道很快会读到 EOF。如果命令留下了仍然握着管道的后台子进程，EOF 迟迟不来，
    // 这里等一会儿就放弃，并关闭流把读取线程叫醒，免得线程一直泄漏。
    outThread.join(2000)
    errThread.join(2000)
    if (outThread.isAlive || errThread.isAlive) {
        try {
            stdout.close()
        } catch (e: Exception) {
            // 忽略
        }
        try {
            stderr.close()
        } catch (e: Exception) {
            // 忽略
        }
        outThread.join(500)
        errThread.join(500)
    }
    return code
}

/**
 * 把「脚本参数」输入框拆成真正的 argv：支持单引号、双引号和反斜杠转义，
 * 不做变量展开，也不会把参数拼回 shell 命令里执行。
 */
internal fun parseShellArguments(input: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote = 0 // 0 = 无引号，1 = 单引号，2 = 双引号
    var started = false
    var index = 0
    while (index < input.length) {
        val ch = input[index]
        if (quote == 0) {
            when {
                ch == '\'' -> { quote = 1; started = true }
                ch == '"' -> { quote = 2; started = true }
                ch == '\\' -> {
                    started = true
                    if (index + 1 < input.length) current.append(input[++index]) else current.append(ch)
                }
                ch.isWhitespace() -> {
                    if (started) {
                        result.add(current.toString())
                        current.setLength(0)
                        started = false
                    }
                }
                else -> { current.append(ch); started = true }
            }
        } else if (quote == 1) {
            if (ch == '\'') quote = 0 else current.append(ch)
        } else {
            when {
                ch == '"' -> quote = 0
                ch == '\\' -> {
                    if (index + 1 < input.length) current.append(input[++index]) else current.append(ch)
                }
                else -> current.append(ch)
            }
        }
        index += 1
    }
    if (started) result.add(current.toString())
    return result
}

/* ───────────────────────── 脚本文件 ───────────────────────── */

/** 导入的 .sh 脚本：已经统一成 \n 换行，去掉了 BOM。 */
internal class ShellScript(val name: String, val text: String, val bytes: Int) {
    val lineCount: Int = text.trimEnd('\n').count { it == '\n' } + 1
}

private fun readUpTo(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (total < limit) {
        val n = input.read(buffer, 0, minOf(buffer.size, limit - total))
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    return out.toByteArray()
}

/** 读入用户选的脚本文件；文件不合适时抛出 [IOException]，消息可以直接给用户看。 */
internal fun readShellScript(context: Context, uri: Uri): ShellScript {
    var name: String? = null
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !c.isNull(index)) name = c.getString(index)
            }
        }
    } catch (e: Exception) {
        // 查不到文件名也能继续
    }
    val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("无法打开所选文件")
    val bytes = stream.use { readUpTo(it, SHELL_SCRIPT_MAX_BYTES + 1) }
    if (bytes.size > SHELL_SCRIPT_MAX_BYTES) {
        throw IOException("脚本太大了（超过 ${SHELL_SCRIPT_MAX_BYTES / 1024} KB），不能直接执行")
    }
    if (bytes.any { it == 0.toByte() }) throw IOException("这个文件里有二进制内容，不是文本脚本")
    var text = String(bytes, Charsets.UTF_8)
    if (text.startsWith("\uFEFF")) text = text.substring(1)
    // Windows 编辑的脚本是 \r\n 换行，行尾多出来的 \r 会让命令找不到
    text = text.replace("\r\n", "\n").replace('\r', '\n')
    if (text.isBlank()) throw IOException("脚本是空的")
    return ShellScript(name ?: uri.lastPathSegment ?: "script.sh", text, bytes.size)
}
