package com.mingdai.toTool.ui.screens

import android.content.Context
import android.webkit.MimeTypeMap
import com.mingdai.toTool.ui.screens.tools.cleanTerminalText
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/*
 * 格式转换页「FFmpeg 命令」模式的后台部分：把输入框里的命令拆成参数、换上输入文件的路径、
 * 运行内置的 ffmpeg、把输出整理成日志，并把生成的文件收集起来保存到「下载/toTool」。
 * 界面在 FfmpegCommandPane.kt。
 *
 * 命令是怎么跑起来的：不经过 shell，参数按命令行的引号规则拆开后直接交给 ffmpeg 进程，
 * 所以管道、变量、通配符这些 shell 语法不能用；引号、反斜杠转义、反斜杠续行都支持。
 * 进程的工作目录是应用的一个专用目录，命令里直接写输出文件名（如 out.mp4）就会生成在那里。
 */

/** 最多同时添加的输入文件数，对应 {in1} … {in8}。 */
internal const val FFMPEG_MAX_INPUTS = 8

/** 日志最多保留的行数，超过的从最早的开始丢。 */
internal const val FFMPEG_LOG_MAX_LINES = 3000

/** 输入文件副本的文件名前缀；进入页面时据此清掉上次异常退出遗留的副本。 */
internal const val FFMPEG_INPUT_PREFIX = "cmd_in_"

/** 生成的文件超过这个数量时，打包成一个 zip 再保存（比如把视频拆成几百张图片）。 */
private const val ZIP_THRESHOLD = 50

/** 单行日志最多保留的字符数；更长的行（元数据、二进制输出）按这个长度硬折行，免得撑爆内存。 */
private const val LINE_MAX_CHARS = 2000

/** 应用目录里没来得及保存的旧输出，超过这个时间就清掉。 */
private const val STALE_OUTPUT_MS = 6L * 60 * 60 * 1000

/* ───────────────────────── 命令解析 ───────────────────────── */

/** 一个已经读进应用目录的输入文件。命令里用 [token]（{in1}、{in2}…）引用它。 */
internal class StagedInput(val slot: Int, val name: String, val size: Long, val file: File) {
    val token: String get() = "{in$slot}"
}

/** 命令本身有问题（引号没闭合、引用了不存在的输入文件…）；消息可以直接给用户看。 */
internal class CommandSyntaxException(message: String) : Exception(message)

/**
 * 手机输入法和聊天软件常把直引号自动换成弯引号，粘贴过来的命令就莫名其妙跑不起来：这里把它们当普通引号。
 * 弯单引号同时也是撇号（It’s），所以只有出现了左单引号 ‘ 才认为它们是引号。
 */
internal fun normalizeQuotes(text: String): String {
    var s = text
        .replace('\u201C', '"')
        .replace('\u201D', '"')
        .replace('\u201E', '"')
        .replace('\u201F', '"')
    if (s.indexOf('\u2018') >= 0) {
        s = s.replace('\u2018', '\'').replace('\u2019', '\'')
    }
    return s
}

/**
 * 按命令行（POSIX shell）的引号规则把文本拆成参数：
 * - 空白分隔参数；
 * - 单引号里一切照字面；
 * - 双引号里只有 \\ \" \$ \` 和 \ 加换行 会被转义，其余反斜杠照字面；
 * - 引号外的反斜杠转义下一个字符，反斜杠加换行是续行；
 * - 以 # 开头的词是注释，一直到行尾。
 * 不展开变量、通配符、管道等 shell 语法。
 */
internal fun tokenizeCommand(text: String): List<String> {
    val tokens = ArrayList<String>()
    val cur = StringBuilder()
    var inToken = false // 正在拼一个参数（'' 这样的空串也算一个参数）
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            c == '\'' -> {
                val end = text.indexOf('\'', i + 1)
                if (end < 0) throw CommandSyntaxException("单引号没有闭合")
                cur.append(text, i + 1, end)
                inToken = true
                i = end + 1
            }
            c == '"' -> {
                inToken = true
                i++
                var closed = false
                while (i < n) {
                    val d = text[i]
                    if (d == '"') {
                        closed = true
                        i++
                        break
                    }
                    if (d == '\\' && i + 1 < n) {
                        val e = text[i + 1]
                        if (e == '"' || e == '\\' || e == '$' || e == '`') {
                            cur.append(e)
                            i += 2
                            continue
                        }
                        if (e == '\n') {
                            i += 2
                            continue
                        }
                        if (e == '\r') {
                            i += if (i + 2 < n && text[i + 2] == '\n') 3 else 2
                            continue
                        }
                    }
                    cur.append(d)
                    i++
                }
                if (!closed) throw CommandSyntaxException("双引号没有闭合")
            }
            c == '\\' -> {
                if (i + 1 >= n) {
                    // 末尾孤零零的反斜杠：当作普通字符
                    cur.append('\\')
                    inToken = true
                    i++
                } else {
                    val e = text[i + 1]
                    when (e) {
                        '\n' -> i += 2
                        '\r' -> i += if (i + 2 < n && text[i + 2] == '\n') 3 else 2
                        else -> {
                            cur.append(e)
                            inToken = true
                            i += 2
                        }
                    }
                }
            }
            c.isWhitespace() -> {
                if (inToken) {
                    tokens.add(cur.toString())
                    cur.setLength(0)
                    inToken = false
                }
                i++
            }
            c == '#' && !inToken -> {
                while (i < n && text[i] != '\n') i++
            }
            else -> {
                cur.append(c)
                inToken = true
                i++
            }
        }
    }
    if (inToken) tokens.add(cur.toString())
    return tokens
}

/** 允许把完整的命令粘贴进来：开头的 ffmpeg（或 /path/to/ffmpeg）不用管。 */
internal fun stripProgramName(tokens: List<String>): List<String> {
    val head = tokens.firstOrNull() ?: return tokens
    return if (head.substringAfterLast('/').equals("ffmpeg", ignoreCase = true)) tokens.drop(1) else tokens
}

private val PLACEHOLDER = Regex("""\{(in\d*|out)\}""")

/**
 * 把参数里的占位符换成真实路径：
 * - {in1}、{in2}…：第 N 个输入文件的副本；{in} 等同于编号最小的那个；
 * - {out}：输出目录（进程的工作目录，直接写文件名也会生成在这里）。
 * 引用了不存在的输入文件时抛出 [CommandSyntaxException]，免得把字面的 "{in3}" 交给 ffmpeg。
 */
internal fun expandPlaceholders(tokens: List<String>, inputs: List<StagedInput>, outDir: File): List<String> {
    val sorted = inputs.sortedBy { it.slot }
    return tokens.map { token ->
        if (token.indexOf('{') < 0) {
            token
        } else {
            PLACEHOLDER.replace(token) { match ->
                val key = match.groupValues[1]
                if (key == "out") {
                    outDir.absolutePath
                } else {
                    val digits = key.substring(2)
                    val input = if (digits.isEmpty()) {
                        sorted.firstOrNull()
                    } else {
                        val slot = digits.toIntOrNull()
                        sorted.firstOrNull { it.slot == slot }
                    }
                    (input ?: throw CommandSyntaxException(missingInputMessage(match.value, sorted))).file.absolutePath
                }
            }
        }
    }
}

private fun missingInputMessage(placeholder: String, inputs: List<StagedInput>): String =
    if (inputs.isEmpty()) {
        "命令里用到了 $placeholder，但还没有添加输入文件。先点「添加输入文件」。"
    } else {
        "命令里用到了 $placeholder，但现有的输入文件只有 ${inputs.joinToString("、") { it.token }}。"
    }

/**
 * 补上几个全局选项，让命令在没有终端的环境里也能正常跑：
 * -nostdin：不读标准输入（否则遇到“文件已存在，是否覆盖？”这类提问会一直卡住）；
 * -hide_banner：不打印那一大段编译配置；
 * -y：没写 -y / -n 时默认覆盖同名输出（反复调整命令重跑时，不会因为上次的输出还在而失败）。
 */
internal fun withGlobalOptions(args: List<String>): List<String> {
    val extra = ArrayList<String>(3)
    extra.add("-nostdin")
    if ("-hide_banner" !in args) extra.add("-hide_banner")
    if ("-y" !in args && "-n" !in args) extra.add("-y")
    return extra + args
}

/** 会改变“输出时长 / 处理范围”的选项：命令里有它们时，用输入文件的时长算进度就不准了。 */
private val RANGE_OPTIONS = setOf(
    "-t", "-to", "-ss", "-sseof", "-frames", "-frames:v", "-frames:a", "-vframes", "-aframes",
    "-itsoffset", "-stream_loop", "-loop",
)

/** 只有一个输入、没有裁剪 / 循环选项时，才用输入文件的时长换算进度条；否则只显示实时输出。 */
internal fun hasPredictableDuration(args: List<String>): Boolean =
    args.count { it == "-i" } == 1 && args.none { it in RANGE_OPTIONS }

/** 日志里“正在执行”那一行显示的命令：多行命令只显示第一行，并注明总行数。 */
internal fun describeFfmpegCommand(text: String): String {
    val all = text.trim().lines()
    val first = all.first().let { if (it.length > 200) it.take(200) + "…" else it }
    return if (all.size > 1) "$first …（共 ${all.size} 行）" else first
}

/* ───────────────────────── 进度解析 ───────────────────────── */

private val CLOCK_DURATION: Pattern = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+)\\.(\\d+)")
private val CLOCK_TIME: Pattern = Pattern.compile("time=\\s*(\\d+):(\\d+):(\\d+)\\.(\\d+)")

private fun clockSeconds(m: Matcher): Double {
    val h = m.group(1)?.toIntOrNull() ?: 0
    val min = m.group(2)?.toIntOrNull() ?: 0
    val s = m.group(3)?.toIntOrNull() ?: 0
    val frac = m.group(4)?.let { ("0.$it").toDoubleOrNull() } ?: 0.0
    return h * 3600.0 + min * 60.0 + s + frac
}

/** 从 “Duration: 00:01:23.45, …” 这样的行里读出总时长（秒）；不是这样的行返回 0。 */
internal fun parseDurationSeconds(line: String): Double {
    val m = CLOCK_DURATION.matcher(line)
    return if (m.find()) clockSeconds(m) else 0.0
}

/** 从进度行 “… time=00:00:12.34 …” 里读出已处理的时长（秒）；不是进度行返回 -1。 */
internal fun parseProgressSeconds(line: String): Double {
    val m = CLOCK_TIME.matcher(line)
    return if (m.find()) clockSeconds(m) else -1.0
}

/** 用时显示成 m:ss，超过一小时显示 h:mm:ss。 */
internal fun formatElapsed(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

/* ───────────────────────── 运行 ───────────────────────── */

/** 一次运行的句柄：界面用它来停止。 */
internal class FfmpegRun {
    @Volatile
    var process: Process? = null

    @Volatile
    var stopped = false
        private set

    /**
     * 先发 SIGTERM，让 ffmpeg 有机会收尾；3 秒后还没退出就强制结束。
     * 等待放在单独的线程里做，免得卡住界面线程。
     */
    fun stop() {
        stopped = true
        val target = process ?: return
        thread(name = "ffmpeg-stop", isDaemon = true) {
            try {
                target.destroy()
                if (!target.waitFor(3, TimeUnit.SECONDS)) target.destroyForcibly()
            } catch (e: Exception) {
                try {
                    target.destroyForcibly()
                } catch (e2: Exception) {
                    // 进程已经结束
                }
            }
        }
    }
}

/**
 * 把 ffmpeg 的输出切成日志行，处理方式像终端：
 * - 以 \n 结尾的内容落成一行（[onLine]）；
 * - 以 \r 结尾的内容是“原地刷新”的进度行，只交给 [onLive]，不进日志；
 *   它后面如果跟着 \n（ffmpeg 的最后一条进度就是这样），再落成一行。
 * 超长行按 [LINE_MAX_CHARS] 硬折行，并去掉终端控制字符。
 */
private class TerminalSplitter(
    private val onLine: (String) -> Unit,
    private val onLive: (String) -> Unit,
) {
    private val buffer = StringBuilder()
    private var live: String? = null
    private var afterCr = false

    fun feed(chunk: CharArray, length: Int) {
        for (i in 0 until length) {
            val c = chunk[i]
            when (c) {
                '\n' -> {
                    val pending = live
                    when {
                        buffer.isNotEmpty() -> onLine(cleanTerminalText(buffer.toString()))
                        afterCr && pending != null -> onLine(pending)
                        else -> onLine("")
                    }
                    buffer.setLength(0)
                    live = null
                    afterCr = false
                }
                '\r' -> {
                    if (buffer.isNotEmpty()) {
                        val text = cleanTerminalText(buffer.toString())
                        buffer.setLength(0)
                        live = text
                        onLive(text)
                    }
                    afterCr = true
                }
                else -> {
                    afterCr = false
                    buffer.append(c)
                    if (buffer.length >= LINE_MAX_CHARS) {
                        onLine(cleanTerminalText(buffer.toString()))
                        buffer.setLength(0)
                        live = null
                    }
                }
            }
        }
    }

    /** 流结束时，把最后没有换行的内容也落成一行。 */
    fun finish() {
        val pending = live
        if (buffer.isNotEmpty()) {
            onLine(cleanTerminalText(buffer.toString()))
        } else if (pending != null) {
            onLine(pending)
        }
        buffer.setLength(0)
        live = null
    }
}

/**
 * 运行 ffmpeg，一直阻塞到它结束，返回退出码；请在后台线程调用。
 * 标准输出和标准错误合并成一路（ffmpeg 的日志在 stderr，-version / -encoders 这类的信息在 stdout）。
 * 进程可以随时用 [run] 的 stop() 结束。启动失败（比如没有执行权限）时抛出 IOException。
 */
internal fun runFfmpegCommand(
    ffmpeg: File,
    args: List<String>,
    workDir: File,
    tmpDir: File?,
    run: FfmpegRun,
    onLine: (String) -> Unit,
    onLive: (String) -> Unit,
): Int {
    val builder = ProcessBuilder(listOf(ffmpeg.absolutePath) + args)
        .directory(workDir)
        .redirectErrorStream(true)
    if (tmpDir != null) builder.environment()["TMPDIR"] = tmpDir.absolutePath
    val process = builder.start()
    run.process = process
    // 启动的同时刚好点了停止：进程还没登记上，stop() 没能结束它
    if (run.stopped) run.stop()
    // 标准输入立刻关掉：ffmpeg 要读输入时拿到的是 EOF，而不是一直卡着等
    try {
        process.outputStream.close()
    } catch (e: Exception) {
        // 忽略
    }
    val splitter = TerminalSplitter(onLine, onLive)
    try {
        // InputStreamReader 会正确处理被读取边界切开的多字节字符
        val reader = InputStreamReader(process.inputStream, Charsets.UTF_8)
        val buffer = CharArray(8192)
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            if (n > 0) splitter.feed(buffer, n)
        }
    } catch (e: Exception) {
        // 管道被关掉了（进程被结束）：读到这里为止
    } finally {
        splitter.finish()
    }
    val code = process.waitFor()
    run.process = null
    return code
}

/* ───────────────────────── 目录与输出文件 ───────────────────────── */

/** 命令的工作目录，也是命令里写的相对输出文件落地的地方。 */
internal fun ffmpegOutputDir(context: Context): File {
    val dir = context.getExternalFilesDir("ffmpeg_cmd") ?: File(context.filesDir, "ffmpeg_cmd")
    dir.mkdirs()
    return dir
}

/** 清掉上次异常退出（崩溃 / 被杀）遗留的输入副本，以及超过 6 小时没人要的旧输出。 */
internal fun cleanupFfmpegLeftovers(context: Context) {
    listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
        dir.listFiles { f -> f.name.startsWith(FFMPEG_INPUT_PREFIX) }?.forEach { it.delete() }
    }
    val cutoff = System.currentTimeMillis() - STALE_OUTPUT_MS
    ffmpegOutputDir(context).listFiles()?.forEach { f ->
        if (f.isFile && f.lastModified() < cutoff) f.delete()
    }
}

/** 记下输出目录里每个文件的 (大小, 修改时间)，运行结束后据此找出这次新生成 / 被改写的文件。 */
internal fun snapshotOutputs(dir: File): Map<String, Pair<Long, Long>> {
    val result = HashMap<String, Pair<Long, Long>>()
    dir.listFiles()?.forEach { f ->
        if (f.isFile) result[f.name] = Pair(f.length(), f.lastModified())
    }
    return result
}

/** 和 [snapshotOutputs] 的快照相比，新生成或被改写过的文件（按文件名排序）。 */
internal fun changedOutputs(dir: File, before: Map<String, Pair<Long, Long>>): List<File> {
    val result = ArrayList<File>()
    dir.listFiles()?.forEach { f ->
        if (f.isFile && before[f.name] != Pair(f.length(), f.lastModified())) result.add(f)
    }
    result.sortBy { it.name }
    return result
}

private val MIME_OVERRIDES = mapOf(
    "mkv" to "video/x-matroska",
    "mka" to "audio/x-matroska",
    "webm" to "video/webm",
    "ts" to "video/mp2t",
    "mp3" to "audio/mpeg",
    "wav" to "audio/x-wav",
    "flac" to "audio/flac",
    "aac" to "audio/aac",
    "m4a" to "audio/mp4",
    "ogg" to "audio/ogg",
    "opus" to "audio/ogg",
    "gif" to "image/gif",
    "srt" to "application/x-subrip",
)

/** 按扩展名猜 MIME 类型，猜不出来就当普通二进制文件。 */
internal fun mimeForFile(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (ext.isEmpty()) return "application/octet-stream"
    return MIME_OVERRIDES[ext]
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: "application/octet-stream"
}

/** [saved]：已保存的文件（显示路径 + 大小）；[kept]：没能保存、仍留在应用目录里的文件；[failure]：第一个失败原因。 */
internal class OutputsSaved(val saved: List<String>, val kept: List<File>, val failure: String?)

/**
 * 把这次命令生成的文件保存到「下载/toTool」。文件很多（超过 [ZIP_THRESHOLD] 个）时先打包成一个 zip。
 * 保存成功的文件会从应用目录里删掉；没能保存的原样留着。用户取消时抛出 [ConvertCanceledException]。
 * [onStage] 报告当前在做什么，[onProgress] 报告当前这一步的进度。
 */
internal fun saveFfmpegOutputs(
    context: Context,
    files: List<File>,
    canceled: AtomicBoolean,
    onStage: (String) -> Unit,
    onProgress: (Float?) -> Unit,
): OutputsSaved {
    val saved = ArrayList<String>()
    val kept = ArrayList<File>()
    var failure: String? = null

    var queue = files
    if (files.size > ZIP_THRESHOLD) {
        onStage("正在把 ${files.size} 个文件打包成 zip…")
        onProgress(0f)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zip = File(files[0].parentFile, "ffmpeg_output_$stamp.zip")
        zipFiles(files, zip, canceled, onProgress)
        // 原文件已经装进 zip：先删掉，给下一步“复制到下载目录”腾出磁盘空间
        files.forEach { it.delete() }
        queue = listOf(zip)
    }

    queue.forEachIndexed { index, file ->
        if (canceled.get()) throw ConvertCanceledException()
        onStage(
            if (queue.size > 1) "正在保存到下载目录（${index + 1}/${queue.size}）…" else "正在保存到下载目录…"
        )
        onProgress(0f)
        val size = file.length() // 保存成功后文件会被删掉，大小要先取
        val result = publishToDownloads(context, file, mimeForFile(file.name), canceled) { p -> onProgress(p) }
        val path = result.path
        if (path != null) {
            saved.add("$path（${humanSize(size)}）")
        } else {
            kept.add(file)
            if (failure == null) failure = result.error
        }
    }
    return OutputsSaved(saved, kept, failure)
}

private fun zipFiles(files: List<File>, zip: File, canceled: AtomicBoolean, onProgress: (Float?) -> Unit) {
    try {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip), 1 shl 16)).use { out ->
            // 图片、音视频本身已经压缩过，再压一遍几乎不会变小，只会更慢：只打包，不压缩
            out.setLevel(Deflater.NO_COMPRESSION)
            val buffer = ByteArray(1 shl 16)
            files.forEachIndexed { index, file ->
                if (canceled.get()) throw ConvertCanceledException()
                out.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
                out.closeEntry()
                onProgress((index + 1).toFloat() / files.size)
            }
        }
    } catch (e: Throwable) {
        zip.delete() // 失败 / 取消：不留半截 zip
        throw e
    }
}

/* ───────────────────────── 示例命令 ───────────────────────── */

internal class FfmpegExample(val label: String, val command: String)

/**
 * 输入框下面的示例，点一下填进去。
 * 内置的是纯 LGPL 构建的 ffmpeg：音频有 libmp3lame / libvorbis / libopus，但没有 GPL 的 libx264 和 libvpx，
 * 所以视频示例用的是自带的 mpeg4 编码器；前几个“查看”类的示例用来看这个版本到底支持什么。
 */
internal val FFMPEG_EXAMPLES = listOf(
    FfmpegExample("版本信息", "-version"),
    FfmpegExample("编码器", "-encoders"),
    FfmpegExample("解码器", "-decoders"),
    FfmpegExample("封装格式", "-formats"),
    FfmpegExample("滤镜", "-filters"),
    FfmpegExample("媒体信息", "-i {in1}"),
    FfmpegExample("提取音频", "-i {in1} -vn -c:a aac -b:a 192k audio.m4a"),
    FfmpegExample("转 MP3", "-i {in1} -vn -c:a libmp3lame -q:a 2 audio.mp3"),
    FfmpegExample("转 Opus", "-i {in1} -vn -c:a libopus -b:a 96k audio.opus"),
    FfmpegExample("截取片段", "-ss 00:00:10 -t 00:00:30 -i {in1} -c copy clip.mp4"),
    FfmpegExample("缩小到 720p", "-i {in1} -vf scale=-2:720 -c:v mpeg4 -q:v 4 -c:a aac scaled.mp4"),
    FfmpegExample("转 GIF", "-i {in1} -vf \"fps=10,scale=480:-1\" out.gif"),
    FfmpegExample("每秒抽一帧", "-i {in1} -vf fps=1 frame_%03d.png"),
    FfmpegExample("去掉声音", "-i {in1} -an -c:v copy silent.mp4"),
)
