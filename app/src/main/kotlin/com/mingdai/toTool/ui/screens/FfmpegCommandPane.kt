package com.mingdai.toTool.ui.screens

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mingdai.toTool.R
import com.mingdai.toTool.data.FFMPEG_COMMAND_MAX_CHARS
import com.mingdai.toTool.data.Prefs
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.KeepScreenOn
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.copyText
import com.mingdai.toTool.ui.screens.tools.LogPane
import com.mingdai.toTool.ui.screens.tools.ShellLine
import com.mingdai.toTool.ui.screens.tools.ShellLineKind
import com.mingdai.toTool.ui.screens.tools.ShellLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 格式转换页的「FFmpeg 命令」模式：直接执行原始 ffmpeg 命令。
 * 添加输入文件（读进应用目录，命令里用 {in1}、{in2}… 引用）→ 写命令 → 运行，日志实时显示，
 * 命令生成的文件会自动保存到「下载/toTool」。后台部分（命令解析、运行、收集输出）在 FfmpegCommand.kt。
 */

/**
 * @param onBusyChange 通知外层“正在读取文件 / 运行命令”，外层据此锁住模式切换
 *   （切走会销毁本页面，等于把正在跑的 ffmpeg 终止了）。
 */
@Composable
internal fun FfmpegCommandPane(
    modifier: Modifier = Modifier,
    onBusyChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val prefs = remember { Prefs(context) }

    /* ───────── 状态 ───────── */

    var command by remember {
        val draft = prefs.ffmpegDraft()
        mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    }
    var history by remember { mutableStateOf(prefs.ffmpegHistory()) }
    var showHistory by remember { mutableStateOf(false) }

    val inputs = remember { mutableStateListOf<StagedInput>() }
    var stagingName by remember { mutableStateOf<String?>(null) }
    var stageProgress by remember { mutableStateOf<Float?>(0f) }

    var running by remember { mutableStateOf(false) }
    var currentRun by remember { mutableStateOf<FfmpegRun?>(null) }
    var stageText by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var liveText by remember { mutableStateOf("") }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    var resultMsg by remember { mutableStateOf<String?>(null) }
    var noticeMsg by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val canceled = remember { AtomicBoolean(false) }
    val stageCanceled = remember { AtomicBoolean(false) }

    val log = remember { ShellLog(FFMPEG_LOG_MAX_LINES) }
    var lines by remember { mutableStateOf<List<ShellLine>>(emptyList()) }
    var dropped by remember { mutableIntStateOf(0) }
    var shownVersion by remember { mutableLongStateOf(-1L) }
    val logState = rememberLazyListState()
    val pageScroll = rememberScrollState()

    val busy = running || stagingName != null

    // 把缓冲里的日志同步到界面。同步前如果已经停在最底部，同步后继续跟到最新一行；
    // 用户往上翻着看的时候不去打扰。
    fun syncLog() {
        val version = log.version()
        if (version == shownVersion) return
        val followTail = !logState.canScrollForward
        val snapshot = log.snapshot()
        val droppedNow = log.droppedCount()
        lines = snapshot
        dropped = droppedNow
        shownVersion = version
        if (followTail && snapshot.isNotEmpty()) {
            val lastIndex = if (droppedNow > 0) snapshot.size else snapshot.size - 1
            logState.requestScrollToItem(lastIndex)
        }
    }

    // 运行期间每 100 ms 同步一次日志、刷新用时；结束（running 变 false）时再同步一次，保证最后几行不丢
    LaunchedEffect(running) {
        while (true) {
            syncLog()
            if (!running) break
            elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
            delay(100)
        }
    }

    LaunchedEffect(busy) { onBusyChange(busy) }
    KeepScreenOn(busy)

    // 进入页面时清掉上次异常退出遗留的输入副本和旧输出
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { cleanupFfmpegLeftovers(context) }
    }

    // 离开页面：结束还在跑的 ffmpeg，删掉输入副本，保存输入框里的内容
    DisposableEffect(Unit) {
        onDispose {
            canceled.set(true)
            stageCanceled.set(true)
            currentRun?.stop()
            val staged = inputs.map { it.file }
            thread(name = "ffmpeg-cleanup", isDaemon = true) { staged.forEach { it.delete() } }
            prefs.setFfmpegDraft(command.text)
            onBusyChange(false)
        }
    }

    /* ───────── 操作 ───────── */

    fun setCommand(text: String) {
        command = TextFieldValue(text, TextRange(text.length))
    }

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty() || running || stagingName != null) return
        errorMsg = null
        noticeMsg = null
        stageCanceled.set(false)
        scope.launch {
            try {
                for (uri in uris) {
                    if (inputs.size >= FFMPEG_MAX_INPUTS) {
                        noticeMsg = "最多添加 $FFMPEG_MAX_INPUTS 个输入文件，多出来的没有读取。"
                        break
                    }
                    val name = queryFileName(context, uri)
                    val size = queryFileSize(context, uri)
                    stagingName = name
                    stageProgress = 0f
                    // ffmpeg 需要能随机读取的真实路径：把文件复制成应用目录里的副本（分块复制，带进度，可取消）
                    val file = withContext(Dispatchers.IO) {
                        val need = if (size > 0L) size + MIN_FREE_HEADROOM_BYTES else 0L
                        val dir = pickWorkDir(context, need)
                        copyUriToTemp(context, uri, dir, size, stageCanceled, FFMPEG_INPUT_PREFIX) { p ->
                            stageProgress = p
                        }
                    }
                    val slot = (1..FFMPEG_MAX_INPUTS).first { s -> inputs.none { it.slot == s } }
                    inputs.add(StagedInput(slot, name, if (size >= 0L) size else file.length(), file))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConvertCanceledException) {
                noticeMsg = "已取消读取"
            } catch (e: Exception) {
                errorMsg = friendlyError(e)
            } finally {
                stagingName = null
            }
        }
    }

    fun removeInput(input: StagedInput) {
        inputs.remove(input)
        scope.launch(Dispatchers.IO) { input.file.delete() }
    }

    fun runCommand() {
        if (running || stagingName != null) return
        val text = command.text
        if (text.isBlank()) return
        val ffmpeg = ffmpegBinary(context)
        if (ffmpeg == null) {
            errorMsg = context.getString(
                if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) R.string.format_extract_fail
                else R.string.format_unsupported_abi
            )
            return
        }
        if (text.length > FFMPEG_COMMAND_MAX_CHARS) {
            errorMsg = "命令太长了（超过 $FFMPEG_COMMAND_MAX_CHARS 个字符）"
            return
        }
        val outDir = ffmpegOutputDir(context)
        val usedInputs = inputs.toList()
        val args = try {
            val tokens = stripProgramName(tokenizeCommand(normalizeQuotes(text)))
            if (tokens.isEmpty()) throw CommandSyntaxException("命令是空的")
            withGlobalOptions(expandPlaceholders(tokens, usedInputs, outDir))
        } catch (e: CommandSyntaxException) {
            errorMsg = e.message
            return
        }

        focusManager.clearFocus()
        canceled.set(false)
        resultMsg = null
        noticeMsg = null
        errorMsg = null
        liveText = ""
        progress = null
        stageText = "正在运行…"
        val run = FfmpegRun()
        currentRun = run
        startedAt = SystemClock.elapsedRealtime()
        elapsedSeconds = 0L
        running = true
        history = prefs.addFfmpegHistory(text)
        prefs.setFfmpegDraft(text)
        log.add(ShellLineKind.INFO, "[${clockNow()}] $ ffmpeg ${describeFfmpegCommand(text)}")
        for (input in usedInputs) {
            log.add(ShellLineKind.INFO, "${input.token} = ${input.file.absolutePath}")
        }
        scope.launch {
            // 等日志窗口排好版，再把页面滚到底，让用户看得到输出
            delay(150)
            pageScroll.animateScrollTo(pageScroll.maxValue)
        }
        scope.launch {
            val measurable = hasPredictableDuration(args)
            val tail = ArrayDeque<String>()
            var duration = 0.0
            var before: Map<String, Pair<Long, Long>>? = null
            var kept: List<File> = emptyList()
            try {
                val exit = withContext(Dispatchers.IO) {
                    before = snapshotOutputs(outDir)
                    runFfmpegCommand(
                        ffmpeg = ffmpeg,
                        args = args,
                        workDir = outDir,
                        tmpDir = context.cacheDir,
                        run = run,
                        onLine = { line ->
                            log.add(ShellLineKind.OUT, line)
                            if (line.isNotBlank() && parseProgressSeconds(line) < 0.0) {
                                tail.addLast(line.trim())
                                if (tail.size > 6) tail.removeFirst()
                            }
                            if (measurable && duration <= 0.0) duration = parseDurationSeconds(line)
                        },
                        onLive = { live ->
                            liveText = live.trim()
                            val seconds = parseProgressSeconds(live)
                            if (measurable && duration > 0.0 && seconds >= 0.0) {
                                progress = (seconds / duration).toFloat().coerceIn(0f, 0.99f)
                            }
                        },
                    )
                }
                val used = String.format(Locale.US, "%.2f", (SystemClock.elapsedRealtime() - startedAt) / 1000.0)
                val infoOnly = exit != 0 && tail.any { it.contains("At least one output file must be specified") }
                when {
                    run.stopped -> {
                        log.add(ShellLineKind.INFO, "[已停止] 用时 $used 秒")
                        noticeMsg = "已停止，没有完成的输出已丢弃。"
                    }
                    exit == 0 -> {
                        log.add(ShellLineKind.OK, "[完成] 退出码 0 · 用时 $used 秒")
                        val snapshot = before
                        val produced = if (snapshot == null) {
                            emptyList<File>()
                        } else {
                            withContext(Dispatchers.IO) {
                                changedOutputs(outDir, snapshot).filter { f -> f.length() > 0L }
                            }
                        }
                        if (produced.isNotEmpty()) {
                            stageText = "正在保存到下载目录…"
                            progress = 0f
                            liveText = ""
                            val outcome = withContext(Dispatchers.IO) {
                                saveFfmpegOutputs(
                                    context = context,
                                    files = produced,
                                    canceled = canceled,
                                    onStage = { stageText = it },
                                    onProgress = { progress = it },
                                )
                            }
                            kept = outcome.kept
                            if (outcome.saved.isNotEmpty()) {
                                for (item in outcome.saved.take(10)) log.add(ShellLineKind.OK, "[已保存] $item")
                                resultMsg = savedMessage(context, outcome.saved)
                            }
                            if (outcome.kept.isNotEmpty()) {
                                noticeMsg = "有 ${outcome.kept.size} 个文件没能保存到下载目录" +
                                    (outcome.failure?.let { "（$it）" } ?: "") + "，仍留在应用目录：\n" +
                                    outcome.kept.take(5).joinToString("\n") { it.absolutePath }
                            }
                        }
                    }
                    infoOnly -> {
                        log.add(ShellLineKind.INFO, "[结束] 没有指定输出文件，只显示了输入信息 · 用时 $used 秒")
                        noticeMsg = "命令里没有指定输出文件，ffmpeg 只显示了输入信息（这时退出码 1 是正常的）。"
                    }
                    else -> {
                        log.add(ShellLineKind.FAIL, "[结束] 退出码 $exit · 用时 $used 秒")
                        errorMsg = describeFailure(exit, tail.toList())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConvertCanceledException) {
                log.add(ShellLineKind.INFO, "[已取消保存]")
                noticeMsg = "已取消保存"
            } catch (e: OutOfMemoryError) {
                log.add(ShellLineKind.FAIL, "[失败] 内存不足")
                errorMsg = "内存不足：输出太大，设备无法处理。"
            } catch (e: Exception) {
                val problem = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                log.add(ShellLineKind.FAIL, "[失败] $problem")
                errorMsg = "无法运行 ffmpeg：${friendlyError(e)}"
            } finally {
                // 没能保存的输出（运行失败、已停止、取消保存）不留在应用目录里；保存失败的留着，路径已经告诉用户
                withContext(NonCancellable + Dispatchers.IO) {
                    val snapshot = before
                    if (snapshot != null) {
                        val keep = kept.toSet()
                        changedOutputs(outDir, snapshot).forEach { f -> if (f !in keep) f.delete() }
                    }
                }
                currentRun = null
                running = false
                stageText = ""
                liveText = ""
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        addFiles(uris)
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val bytes = log.plainText().toByteArray(Charsets.UTF_8)
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    try {
                        val stream = context.contentResolver.openOutputStream(uri)
                        if (stream == null) {
                            false
                        } else {
                            stream.use { it.write(bytes) }
                            true
                        }
                    } catch (e: Exception) {
                        false
                    }
                }
                Toast.makeText(context, if (saved) "日志已保存" else "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /* ───────── 界面 ───────── */

    Column(
        modifier = modifier
            .verticalScroll(pageScroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("输入文件")
        SecondaryButton(
            onClick = { picker.launch("*/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && inputs.size < FFMPEG_MAX_INPUTS,
        ) {
            Icon(Icons.Filled.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text(if (inputs.isEmpty()) "添加输入文件" else "继续添加（${inputs.size}/$FFMPEG_MAX_INPUTS）")
        }

        val staging = stagingName
        if (staging != null) {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Text(
                    text = "正在读取 $staging…",
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = stageProgress, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                SecondaryButton(
                    onClick = { stageCanceled.set(true) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("取消读取") }
            }
        }

        if (inputs.isNotEmpty()) {
            InputsCard(
                inputs = inputs,
                enabled = !busy,
                onInsert = { piece -> command = command.insertAtCursor(piece) },
                onRemove = { input -> removeInput(input) },
            )
        }

        SectionLabel("命令")
        TextField(
            value = command,
            onValueChange = { command = it },
            label = "ffmpeg 参数（开头的 ffmpeg 可以省略）",
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            textStyle = MiuixTheme.textStyles.main.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            minLines = 3,
            maxLines = 10,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                onClick = ::runCommand,
                enabled = !busy && command.text.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("运行") }
            SecondaryButton(
                onClick = { currentRun?.stop() },
                enabled = running,
                modifier = Modifier.weight(1f),
            ) { Text("停止") }
        }

        Text(
            text = "示例（点一下填入命令）",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FFMPEG_EXAMPLES.forEach { example ->
                SelectChip(
                    selected = false,
                    onClick = { setCommand(example.command) },
                    label = example.label,
                    enabled = !running,
                )
            }
        }

        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (if (showHistory) "▾ " else "▸ ") + "历史命令（${history.size}）",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showHistory = !showHistory }
                        .padding(vertical = 6.dp),
                )
                if (showHistory) {
                    Text(
                        text = "清空",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                prefs.clearFfmpegHistory()
                                history = emptyList()
                                showHistory = false
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
            }
            if (showHistory) {
                HistoryCard(history = history, enabled = !running, onPick = { setCommand(it) })
            }
        }

        if (running) {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                val percent = progress?.let { " ${(it * 100).toInt().coerceIn(0, 100)}%" } ?: ""
                Text(
                    text = stageText.ifEmpty { "正在运行…" } + percent + " · 用时 " + formatElapsed(elapsedSeconds),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (liveText.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = liveText,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        noticeMsg?.let { msg ->
            MessageCard(
                title = null,
                message = msg,
                container = MiuixTheme.colorScheme.secondaryContainer,
                content = MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }
        resultMsg?.let { msg ->
            MessageCard(
                title = "运行完成",
                message = msg,
                container = MiuixTheme.colorScheme.primaryContainer,
                content = MiuixTheme.colorScheme.onPrimaryContainer,
            )
        }
        errorMsg?.let { msg ->
            MessageCard(
                title = "运行失败",
                message = msg,
                container = MiuixTheme.colorScheme.errorContainer,
                content = MiuixTheme.colorScheme.onErrorContainer,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) { SectionLabel(if (running) "日志 · 运行中…" else "日志") }
            val hasLog = lines.isNotEmpty()
            IconButton(
                onClick = { copyText(context, log.plainText(), "日志") },
                enabled = hasLog,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "复制日志",
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { exportPicker.launch("ffmpeg-log-${fileStamp()}.txt") },
                enabled = hasLog,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = "导出日志",
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = {
                    log.clear()
                    syncLog()
                },
                enabled = hasLog && !running,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "清空日志",
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        LogPane(lines = lines, dropped = dropped, listState = logState)

        HintText(
            "不用写开头的 ffmpeg。参数的写法和命令行一样（支持单双引号、反斜杠续行，不支持管道、变量、通配符）。" +
                "输入文件用 {in1}、{in2}… 引用（直接写手机上的路径，应用通常没有权限读取）；" +
                "输出文件直接写文件名（如 out.mp4），成功后会自动保存到「下载/toTool」。" +
                "没写 -y / -n 时默认加 -y（覆盖同名输出）。"
        )
        HintText(
            "内置的是精简版 ffmpeg，没有 libx264、libmp3lame 等外部编码器，视频可以用自带的 mpeg4。" +
                "点上面的「编码器」「封装格式」「滤镜」示例，可以查看这个版本支持什么。"
        )
    }
}

@Composable
private fun InputsCard(
    inputs: List<StagedInput>,
    enabled: Boolean,
    onInsert: (String) -> Unit,
    onRemove: (StagedInput) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        inputs.forEach { input ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = input.name,
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = humanSize(input.size),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                // 点一下把 {inN} 插到命令的光标处
                SelectChip(
                    selected = false,
                    onClick = { onInsert(input.token) },
                    label = input.token,
                    enabled = enabled,
                )
                IconButton(
                    onClick = { onRemove(input) },
                    enabled = enabled,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "移除",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(history: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        history.forEach { item ->
            Text(
                text = item,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onPick(item) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/** 把 {inN} 之类的片段插到光标处（有选中内容就替换掉），前后没有空白时补一个空格，免得和旁边的参数粘在一起。 */
private fun TextFieldValue.insertAtCursor(piece: String): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    val before = text.substring(0, start)
    val after = text.substring(end)
    val lead = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
    val trail = if (after.isNotEmpty() && !after.first().isWhitespace()) " " else ""
    val inserted = lead + piece + trail
    return TextFieldValue(before + inserted + after, TextRange(before.length + inserted.length))
}

private fun savedMessage(context: Context, saved: List<String>): String =
    if (saved.size == 1) {
        context.getString(R.string.format_saved_to, saved[0])
    } else {
        val more = if (saved.size > 8) "\n……共 ${saved.size} 个文件" else ""
        "已保存 ${saved.size} 个文件：\n" + saved.take(8).joinToString("\n") + more
    }

private fun clockNow(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

private fun fileStamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
