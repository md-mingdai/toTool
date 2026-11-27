package com.mingdai.toTool.ui.screens.tools

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.KeepScreenOn
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.WarnColor
import com.mingdai.toTool.ui.components.copyText
import com.mingdai.toTool.shizuku.ShizukuAccess
import com.mingdai.toTool.shizuku.ShizukuState
import com.mingdai.toTool.permission.HighPermissionManager
import com.mingdai.toTool.permission.HighPermissionRun
import com.mingdai.toTool.permission.HighPermissionStatus
import com.mingdai.toTool.permission.HighPermissionStrategy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 执行 Shell 指令：向 Shizuku 申请授权，然后执行单条命令或导入的 .sh 脚本，把输出实时打印成日志。
 * 后台部分（状态检查、远程进程、日志缓冲）在 ShizukuShell.kt。
 */

/** 日志窗口的最大高度；输出更多时在窗口里滚动。 */
private val LogMaxHeight = 420.dp

/** 导入脚本后，预览显示的行数。 */
private const val SCRIPT_PREVIEW_LINES = 8

private val OkColor = Color(0xFF34C759)

/** 命令输入框下面的示例，点一下就填进去。 */
private val ExampleCommands = listOf(
    "id",
    "getprop ro.product.model",
    "pm list packages -3",
    "dumpsys battery",
    "ls -l /sdcard",
)

private class StatusInfo(val title: String, val detail: String, val accent: Color)

@Composable
fun ShellTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    /* ───────── Shizuku 状态 ───────── */

    var state by remember { mutableStateOf(ShizukuAccess.state(context)) }
    var identity by remember { mutableStateOf<String?>(null) }
    var highStatus by remember { mutableStateOf(HighPermissionManager.status(context)) }
    // 刚进页面时 Binder 可能还在路上：先显示“正在连接”，过一会儿还没来才当作“没有运行”
    var settled by remember { mutableStateOf(false) }

    fun refresh() {
        val now = HighPermissionManager.status(context)
        highStatus = now
        state = ShizukuAccess.state(context)
        identity = if (state == ShizukuState.READY) ShizukuAccess.identity() else null
    }

    LaunchedEffect(Unit) {
        refresh()
        delay(1500)
        settled = true
    }

    // Binder 到达 / 断开、授权结果回来，都重新读一遍状态（这些回调都在主线程）
    DisposableEffect(Unit) {
        val onReceived = Shizuku.OnBinderReceivedListener { refresh() }
        val onDead = Shizuku.OnBinderDeadListener { refresh() }
        val onResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
        Shizuku.addBinderReceivedListenerSticky(onReceived)
        Shizuku.addBinderDeadListener(onDead)
        Shizuku.addRequestPermissionResultListener(onResult)
        onDispose {
            Shizuku.removeBinderReceivedListener(onReceived)
            Shizuku.removeBinderDeadListener(onDead)
            Shizuku.removeRequestPermissionResultListener(onResult)
        }
    }

    // 回到前台时再读一遍（用户可能刚从 Shizuku 里授权 / 启动服务回来）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /* ───────── 输入、日志、执行 ───────── */

    var command by rememberSaveable { mutableStateOf("") }
    var script by remember { mutableStateOf<ShellScript?>(null) }
    var scriptArgs by rememberSaveable { mutableStateOf("") }
    var fixedStdin by rememberSaveable { mutableStateOf(false) }
    var stdinText by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var currentRun by remember { mutableStateOf<HighPermissionRun?>(null) }
    var savedScripts by remember { mutableStateOf<List<SavedShellScript>>(emptyList()) }
    var history by remember { mutableStateOf<List<ShellHistoryEntry>>(emptyList()) }
    val colors = MiuixTheme.colorScheme

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            ShellLibrary.loadScripts(context) to ShellLibrary.loadHistory(context)
        }
        savedScripts = loaded.first
        history = loaded.second
        // 重开工具时恢复最近导入的脚本，脚本库仍然可以切换到其它条目。
        if (script == null) {
            script = loaded.first.firstOrNull()?.toShellScript()
        }
    }

    val log = remember { ShellLog() }
    var lines by remember { mutableStateOf<List<ShellLine>>(emptyList()) }
    var dropped by remember { mutableIntStateOf(0) }
    var shownVersion by remember { mutableLongStateOf(-1L) }
    val logState = rememberLazyListState()
    val pageScroll = rememberScrollState()

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

    // 运行期间每 100 ms 同步一次；结束（running 变 false）时再同步一次，保证最后几行不丢
    LaunchedEffect(running) {
        while (true) {
            syncLog()
            if (!running) break
            delay(100)
        }
    }

    // 离开页面时结束还在跑的命令
    DisposableEffect(Unit) {
        onDispose { currentRun?.stop() }
    }

    KeepScreenOn(running)

    fun execute(
        shown: String,
        text: String,
        arguments: List<String> = emptyList(),
        stdinValue: String? = null,
        historyKind: String = "command",
    ) {
        if (running) return
        if (!highStatus.ready) {
            Toast.makeText(context, "高级权限未就绪：${highStatus.detail}", Toast.LENGTH_SHORT).show()
            return
        }
        focusManager.clearFocus()
        val run = HighPermissionRun()
        currentRun = run
        running = true
        val startedEpoch = System.currentTimeMillis()
        log.add(ShellLineKind.INFO, "[${clockNow()}] $ $shown")
        scope.launch {
            // 等日志窗口排好版，再把页面滚到底，让用户看得到输出
            delay(150)
            pageScroll.animateScrollTo(pageScroll.maxValue)
        }
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            var exitCode = 0
            var failure: String? = null
            try {
                exitCode = withContext(Dispatchers.IO) {
                    HighPermissionManager.execute(context, text, arguments, stdinValue, run) { isError, line ->
                        log.add(if (isError) ShellLineKind.ERR else ShellLineKind.OUT, line)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            }
            val elapsedMs = SystemClock.elapsedRealtime() - started
            val seconds = String.format(Locale.US, "%.2f", elapsedMs / 1000.0)
            val problem = failure
            when {
                problem != null -> log.add(ShellLineKind.FAIL, "[失败] $problem")
                run.stopped -> log.add(ShellLineKind.INFO, "[已停止] 用时 $seconds 秒")
                exitCode == 0 -> log.add(ShellLineKind.OK, "[完成] 退出码 0 · 用时 $seconds 秒")
                else -> log.add(ShellLineKind.FAIL, "[结束] 退出码 $exitCode · 用时 $seconds 秒")
            }
            currentRun = null
            running = false
            // 启动失败往往是授权被撤销、服务已经停止：重新读一遍状态
            if (problem != null) refresh()
            val entry = ShellHistoryEntry(
                id = UUID.randomUUID().toString(),
                title = shown.take(500),
                kind = historyKind,
                startedAt = startedEpoch,
                durationMs = elapsedMs,
                exitCode = if (problem != null || run.stopped) null else exitCode,
                stopped = run.stopped,
                failed = problem != null,
            )
            try {
                history = withContext(Dispatchers.IO) {
                    ShellLibrary.appendHistory(context, entry)
                }
            } catch (e: Exception) {
                // 历史写入失败不影响本次执行结果
            }
        }
    }

    val scriptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) { readShellScript(context, uri) }
                    script = imported
                    val savedOk = withContext(Dispatchers.IO) {
                        runCatching { ShellLibrary.saveScript(context, imported) }.isSuccess
                    }
                    savedScripts = withContext(Dispatchers.IO) { ShellLibrary.loadScripts(context) }
                    Toast.makeText(
                        context,
                        if (savedOk) "脚本已导入并保存到脚本库" else "脚本已导入，但保存到脚本库失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Toast.makeText(context, e.message ?: "无法读取脚本", Toast.LENGTH_LONG).show()
                }
            }
        }
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

    val canRun = highStatus.ready && !running

    ToolBody(padding, scrollState = pageScroll) {
        if (highStatus.strategy == HighPermissionStrategy.SHIZUKU) {
            ShizukuStatusCard(
                state = state,
                connecting = !settled && state == ShizukuState.NOT_RUNNING,
                identity = identity,
                onRequest = {
                    ShizukuAccess.requestPermission()?.let {
                        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    }
                },
                onOpenShizuku = {
                    if (!ShizukuAccess.openShizuku(context)) {
                        Toast.makeText(context, "没有找到 Shizuku 应用", Toast.LENGTH_SHORT).show()
                    }
                },
                onDownload = {
                    if (!ShizukuAccess.openDownload(context)) {
                        Toast.makeText(context, "没有可以打开网页的应用", Toast.LENGTH_SHORT).show()
                    }
                },
                onRecheck = { refresh() },
            )
        } else {
            HighPermissionRootCard(status = highStatus, onRecheck = { refresh() })
        }

        SectionLabel("执行单条命令")
        TextField(
            value = command,
            onValueChange = { command = it },
            label = "要执行的命令",
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 8,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExampleCommands.forEach { example ->
                SelectChip(selected = false, onClick = { command = example }, label = example)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                onClick = { execute(describeCommand(command), command.trim()) },
                enabled = canRun && command.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("执行") }
            SecondaryButton(
                onClick = { currentRun?.stop() },
                enabled = running,
                modifier = Modifier.weight(1f),
            ) { Text("停止") }
        }

        SectionLabel("执行脚本文件")
        val importedScript = script
        if (importedScript == null) {
            SecondaryButton(
                onClick = { scriptPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !running,
            ) { Text("导入 .sh 脚本文件") }
        } else {
            ScriptCard(
                script = importedScript,
                canRun = canRun,
                busy = running,
                args = scriptArgs,
                onArgsChange = { scriptArgs = it },
                fixedStdin = fixedStdin,
                onFixedStdinChange = { fixedStdin = it },
                stdinText = stdinText,
                onStdinTextChange = { stdinText = it },
                onRun = {
                    val arguments = parseShellArguments(scriptArgs)
                    execute(
                        "脚本 ${importedScript.name}（${importedScript.lineCount} 行）" +
                            if (arguments.isEmpty()) "" else " + ${arguments.size} 个参数",
                        importedScript.text,
                        arguments,
                        if (fixedStdin) stdinText else null,
                        historyKind = "script",
                    )
                },
                onReplace = { scriptPicker.launch(arrayOf("*/*")) },
                onRemove = { script = null },
            )
        }

        SectionLabel("脚本库")
        if (savedScripts.isEmpty()) {
            Text(
                "导入的 .sh 脚本会保存在这里，重启后仍可载入。",
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    savedScripts.forEachIndexed { index, saved ->
                        if (index > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(colors.secondaryContainer),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !running) {
                                    script = saved.toShellScript()
                                    Toast.makeText(context, "已载入 ${saved.name}", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    saved.name,
                                    style = MiuixTheme.textStyles.body2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${saved.lineCount} 行 · ${historyTime(saved.savedAt)}",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = colors.onSurfaceVariantSummary,
                                )
                            }
                            Text(
                                "载入",
                                style = MiuixTheme.textStyles.footnote1,
                                color = colors.primary,
                            )
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            ShellLibrary.deleteScript(context, saved.id)
                                        }
                                        savedScripts = withContext(Dispatchers.IO) {
                                            ShellLibrary.loadScripts(context)
                                        }
                                        if (script?.text == saved.text) script = null
                                    }
                                },
                                enabled = !running,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "删除脚本",
                                    tint = colors.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
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
                onClick = { exportPicker.launch("shell-log-${fileStamp()}.txt") },
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) { SectionLabel("运行历史") }
            IconButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { ShellLibrary.clearHistory(context) }
                        history = emptyList()
                    }
                },
                enabled = history.isNotEmpty() && !running,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "清空运行历史",
                    tint = colors.onSurfaceVariantSummary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (history.isEmpty()) {
            Text(
                "还没有运行记录。命令和脚本的退出状态会保存在这里。",
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    history.forEachIndexed { index, entry ->
                        if (index > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(colors.secondaryContainer),
                            )
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.title,
                                    style = MiuixTheme.textStyles.body2,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                val status = when {
                                    entry.failed -> "失败"
                                    entry.stopped -> "已停止"
                                    entry.exitCode == 0 -> "成功"
                                    entry.exitCode != null -> "退出码 ${entry.exitCode}"
                                    else -> "未知"
                                }
                                Text(
                                    status,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = when {
                                        entry.failed -> colors.error
                                        entry.stopped -> colors.onSurfaceVariantSummary
                                        entry.exitCode == 0 -> OkColor
                                        else -> WarnColor
                                    },
                                )
                            }
                            Text(
                                "${if (entry.kind == "script") "脚本" else "命令"} · " +
                                    "${historyTime(entry.startedAt)} · ${historyDuration(entry.durationMs)}",
                                style = MiuixTheme.textStyles.footnote2,
                                color = colors.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }

        HintText("命令以设置页选择的高级权限来源（Shizuku 或 Root）执行，能改系统设置、卸载应用、删除文件，确认后再运行。「停止」只结束 Shell 进程，它留下的后台子进程不一定会退出。")
    }
}

@Composable
private fun HighPermissionRootCard(
    status: HighPermissionStatus,
    onRecheck: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("高级权限：Root", style = MiuixTheme.textStyles.headline2)
            Text(
                status.detail,
                style = MiuixTheme.textStyles.footnote1,
                color = if (status.ready) colors.primary else WarnColor,
            )
            SecondaryButton(onClick = onRecheck, modifier = Modifier.fillMaxWidth()) {
                Text("重新检测")
            }
        }
    }
}

@Composable
private fun ShizukuStatusCard(
    state: ShizukuState,
    connecting: Boolean,
    identity: String?,
    onRequest: () -> Unit,
    onOpenShizuku: () -> Unit,
    onDownload: () -> Unit,
    onRecheck: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val info = if (connecting) {
        StatusInfo("正在连接 Shizuku…", "稍等一下，Shizuku 服务一般一两秒内就会连上。", colors.onSurfaceVariantSummary)
    } else {
        when (state) {
            ShizukuState.NOT_INSTALLED -> StatusInfo(
                "没有找到 Shizuku",
                "本工具靠 Shizuku（或 Sui）拿到 shell 权限。请先安装 Shizuku 并启动它，再回到这里。",
                WarnColor,
            )
            ShizukuState.NOT_RUNNING -> StatusInfo(
                "Shizuku 没有运行",
                "打开 Shizuku，按它的指引用「无线调试」或 root 启动服务，然后回到这里。",
                WarnColor,
            )
            ShizukuState.UNSUPPORTED -> StatusInfo(
                "Shizuku 版本太旧",
                "请把 Shizuku 更新到 v11 或更高版本。",
                colors.error,
            )
            ShizukuState.NEEDS_PERMISSION -> StatusInfo(
                "还没有授权",
                "点「请求授权」，在弹出的窗口里允许本应用使用 Shizuku。",
                WarnColor,
            )
            ShizukuState.DENIED -> StatusInfo(
                "授权被拒绝",
                "之前选了「拒绝且不再询问」。请在 Shizuku 的「授权管理」里为本应用打开权限，再回到这里。",
                colors.error,
            )
            ShizukuState.READY -> StatusInfo(
                "已授权",
                "命令会以 ${identity ?: "Shizuku 服务"} 的身份执行。",
                OkColor,
            )
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.size(10.dp).background(info.accent, CircleShape))
                Text(
                    text = info.title,
                    style = MiuixTheme.textStyles.headline2,
                    color = colors.onSurface,
                )
            }
            Text(
                text = info.detail,
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurfaceVariantSummary,
            )
            if (!connecting) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (state) {
                        ShizukuState.NEEDS_PERMISSION -> PrimaryButton(onClick = onRequest) { Text("请求授权") }
                        ShizukuState.NOT_INSTALLED -> PrimaryButton(onClick = onDownload) { Text("下载 Shizuku") }
                        ShizukuState.NOT_RUNNING,
                        ShizukuState.UNSUPPORTED,
                        ShizukuState.DENIED,
                        -> PrimaryButton(onClick = onOpenShizuku) { Text("打开 Shizuku") }
                        ShizukuState.READY -> Unit
                    }
                    SecondaryButton(onClick = onRecheck) { Text("重新检测") }
                }
            }
        }
    }
}

@Composable
private fun ScriptCard(
    script: ShellScript,
    canRun: Boolean,
    busy: Boolean,
    args: String,
    onArgsChange: (String) -> Unit,
    fixedStdin: Boolean,
    onFixedStdinChange: (Boolean) -> Unit,
    stdinText: String,
    onStdinTextChange: (String) -> Unit,
    onRun: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val preview = remember(script) { scriptPreview(script) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = script.name,
                style = MiuixTheme.textStyles.headline2,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${script.lineCount} 行 · ${shellFormatBytes(script.bytes)}",
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.secondaryContainer)
                    .padding(10.dp),
            ) {
                Text(
                    text = preview,
                    color = colors.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            TextField(
                value = args,
                onValueChange = onArgsChange,
                label = "脚本参数（可选）",
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
            )
            Text(
                "参数按空白拆分并支持引号，脚本内用 \$1、\$2 读取；不会做变量展开。",
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("固定标准输入", style = MiuixTheme.textStyles.body2)
                    Text(
                        "开启后只写入下面的内容，写完立即 EOF，脚本无法再读取其它输入。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
                Switch(
                    checked = fixedStdin,
                    onCheckedChange = onFixedStdinChange,
                    enabled = !busy,
                )
            }
            if (fixedStdin) {
                TextField(
                    value = stdinText,
                    onValueChange = onStdinTextChange,
                    label = "标准输入内容",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
            } else {
                Text(
                    "未设置时标准输入为空，脚本读取会立即得到 EOF。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            PrimaryButton(
                onClick = onRun,
                enabled = canRun,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("执行脚本", maxLines = 1)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryButton(onClick = onReplace, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("换一个", maxLines = 1)
                }
                SecondaryButton(onClick = onRemove, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("移除", maxLines = 1)
                }
            }
        }
    }
}

/** 日志窗口。「执行 Shell 指令」和格式转换页的「FFmpeg 命令」共用。 */
@Composable
internal fun LogPane(
    lines: List<ShellLine>,
    dropped: Int,
    listState: LazyListState,
) {
    val colors = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        if (lines.isEmpty()) {
            Text(
                text = "还没有输出。执行命令后，这里会实时显示标准输出、错误输出和退出码。",
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            // 固定上限高度：外层页面本身可以滚动，日志窗口必须有确定的高度才能放进去
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = LogMaxHeight),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (dropped > 0) {
                    item(key = "dropped") {
                        Text(
                            text = "…… 已省略最早的 $dropped 行",
                            color = colors.onSurfaceVariantSummary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
                items(lines, key = { it.id }) { line -> ShellLineText(line) }
            }
        }
    }
}

@Composable
private fun ShellLineText(line: ShellLine) {
    val colors = MiuixTheme.colorScheme
    val color = when (line.kind) {
        ShellLineKind.OUT -> colors.onSurface
        ShellLineKind.ERR -> colors.error
        ShellLineKind.INFO -> colors.onSurfaceVariantSummary
        ShellLineKind.OK -> OkColor
        ShellLineKind.FAIL -> colors.error
    }
    val emphasized = line.kind == ShellLineKind.INFO || line.kind == ShellLineKind.OK || line.kind == ShellLineKind.FAIL
    Text(
        text = line.text.ifEmpty { " " },
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (emphasized) FontWeight.SemiBold else null,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
}

/* ───────── 小工具 ───────── */

private fun clockNow(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

private fun fileStamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

/** 日志里“正在执行”那一行显示的命令：多行命令只显示第一行，并注明总行数。 */
private fun describeCommand(text: String): String {
    val all = text.trim().lines()
    val first = all.first().let { if (it.length > 200) it.take(200) + "…" else it }
    return if (all.size > 1) "$first …（共 ${all.size} 行）" else first
}

private fun scriptPreview(script: ShellScript): String {
    val head = script.text.lineSequence()
        .take(SCRIPT_PREVIEW_LINES)
        .joinToString("\n") { if (it.length > 120) it.take(120) + "…" else it }
    return if (script.lineCount > SCRIPT_PREVIEW_LINES) "$head\n…" else head
}

private fun shellFormatBytes(bytes: Int): String =
    if (bytes < 1024) "$bytes 字节" else String.format(Locale.US, "%.1f KB", bytes / 1024.0)

private fun historyTime(epochMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun historyDuration(ms: Long): String =
    if (ms < 1000L) "$ms ms" else String.format(Locale.US, "%.2f 秒", ms / 1000.0)
