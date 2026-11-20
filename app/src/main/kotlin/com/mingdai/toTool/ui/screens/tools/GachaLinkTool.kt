package com.mingdai.toTool.ui.screens.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.WarnColor
import com.mingdai.toTool.ui.components.copyText
import com.mingdai.toTool.shizuku.ShizukuAccess
import com.mingdai.toTool.shizuku.ShizukuState
import java.io.IOException
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val GachaHosts = listOf("mihoyo.com", "hoyoverse.com", "aki-game.net", "aki-game.com")
private val UrlPattern = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
private const val GACHA_LOG_COMMAND =
    "logcat -d | grep -i gacha | grep -E 'https?://' | tail -n 20"

private data class GachaLink(val url: String, val game: String)

/**
 * 参考 Ascent 的 Shizuku 方案：游戏打开抽卡记录时会通过 logcat 输出带 authkey 的记录链接，
 * 这里用 Shizuku shell 读取最近日志并提取链接。链接只在本机显示/复制，不上传到任何服务。
 */
@Composable
fun GachaLinkTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var state by remember { mutableStateOf(ShizukuAccess.state(context)) }
    var identity by remember { mutableStateOf<String?>(null) }
    var settled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<GachaLink?>(null) }
    var currentRun by remember { mutableStateOf<ShellRun?>(null) }

    fun refreshShizuku() {
        val now = ShizukuAccess.state(context)
        state = now
        identity = if (now == ShizukuState.READY) ShizukuAccess.identity() else null
    }

    LaunchedEffect(Unit) {
        refreshShizuku()
        delay(1200)
        settled = true
    }

    DisposableEffect(Unit) {
        val onReceived = Shizuku.OnBinderReceivedListener { refreshShizuku() }
        val onDead = Shizuku.OnBinderDeadListener { refreshShizuku() }
        val onResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizuku() }
        Shizuku.addBinderReceivedListenerSticky(onReceived)
        Shizuku.addBinderDeadListener(onDead)
        Shizuku.addRequestPermissionResultListener(onResult)
        onDispose {
            Shizuku.removeBinderReceivedListener(onReceived)
            Shizuku.removeBinderDeadListener(onDead)
            Shizuku.removeRequestPermissionResultListener(onResult)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshShizuku()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun fetchLink() {
        if (busy || state != ShizukuState.READY) return
        busy = true
        error = null
        result = null
        val run = ShellRun()
        currentRun = run
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { readGachaLink(run) }
                result = found
            } catch (caught: CancellationException) {
                throw caught
            } catch (caught: Throwable) {
                error = caught.message?.takeIf { it.isNotBlank() } ?: "没有找到抽卡记录链接"
            } finally {
                currentRun = null
                busy = false
            }
        }
    }

    fun requestPermission() {
        ShizukuAccess.requestPermission()?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    ToolBody(padding) {
        SectionLabel("米游记录获取")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "在原神、崩坏：星穹铁道或绝区零中打开「抽卡/跃迁记录」，等记录页加载完成后回到 toTool，从系统日志提取本次记录链接。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    "仅限获取自己账号的记录。链接包含登录凭证（authkey），请勿发给他人；本工具不会上传链接。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = WarnColor,
                )
            }
        }

        SectionLabel("Shizuku")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            !settled && state == ShizukuState.NOT_RUNNING -> "正在检测 Shizuku…"
                            state == ShizukuState.READY -> "已就绪"
                            state == ShizukuState.NEEDS_PERMISSION -> "等待授权"
                            state == ShizukuState.NOT_INSTALLED -> "没有安装 Shizuku"
                            state == ShizukuState.NOT_RUNNING -> "Shizuku 未运行"
                            state == ShizukuState.DENIED -> "授权被拒绝"
                            else -> "Shizuku 不可用"
                        },
                        style = MiuixTheme.textStyles.body1,
                    )
                    if (identity != null) {
                        Text(
                            identity ?: "",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                when (state) {
                    ShizukuState.READY -> Unit
                    ShizukuState.NEEDS_PERMISSION -> PrimaryButton(
                        onClick = { requestPermission() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("请求 Shizuku 授权") }
                    ShizukuState.NOT_INSTALLED -> PrimaryButton(
                        onClick = {
                            if (!ShizukuAccess.openDownload(context)) {
                                Toast.makeText(context, "没有可以打开网页的应用", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("下载 Shizuku") }
                    else -> PrimaryButton(
                        onClick = {
                            if (!ShizukuAccess.openShizuku(context)) {
                                Toast.makeText(context, "没有找到 Shizuku 应用", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("打开 Shizuku") }
                }
                SecondaryButton(onClick = { refreshShizuku() }, modifier = Modifier.fillMaxWidth()) {
                    Text("重新检测")
                }
            }
        }

        PrimaryButton(
            onClick = { fetchLink() },
            enabled = state == ShizukuState.READY && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "正在读取系统日志…" else "获取抽卡记录链接")
        }
        if (busy) {
            SecondaryButton(
                onClick = {
                    currentRun?.stop()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("停止")
            }
        }

        if (error != null) {
            Text(
                error ?: "",
                color = WarnColor,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        result?.let { found ->
            SectionLabel("获取结果")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(found.game, style = MiuixTheme.textStyles.headline2)
                    Text(
                        found.url,
                        style = MiuixTheme.textStyles.footnote1.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        ),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PrimaryButton(
                            onClick = { copyText(context, found.url, "抽卡记录链接") },
                            modifier = Modifier.weight(1f),
                        ) { Text("复制链接") }
                        SecondaryButton(
                            onClick = { openWeb(context, found.url) },
                            modifier = Modifier.weight(1f),
                        ) { Text("在浏览器打开") }
                    }
                    SecondaryButton(onClick = { fetchLink() }, modifier = Modifier.fillMaxWidth()) {
                        Text("重新获取")
                    }
                }
            }
        }

        HintText("如果获取失败，请确认游戏内抽卡记录页已经加载完成，并重新执行本操作。日志链接只保存在当前页面，退出工具后不会持久化。")
    }
}

private fun readGachaLink(run: ShellRun): GachaLink {
    val output = Collections.synchronizedList(mutableListOf<String>())
    val errors = Collections.synchronizedList(mutableListOf<String>())
    val exitCode = runShizukuShell(GACHA_LOG_COMMAND, run) { isError, line ->
        if (isError) errors.add(line) else output.add(line)
    }
    val text = output.joinToString("\n")
    val urls = UrlPattern.findAll(text)
        .map { match -> match.value.trimEnd('.', ',', ';', ')', ']', '}') }
        .filter { url ->
            val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase(Locale.ROOT).orEmpty()
            GachaHosts.any { host == it || host.endsWith(".$it") }
        }
        .filter { url -> url.contains("gacha", ignoreCase = true) }
        .toList()
    val url = urls.lastOrNull()
    if (url == null) {
        val detail = errors.joinToString(" ").take(200)
        throw IOException(
            if (exitCode == 0) "没有在最近的系统日志中找到抽卡链接，请先打开游戏记录页"
            else "读取日志失败${if (detail.isBlank()) "" else "：$detail"}",
        )
    }
    return GachaLink(url, gameLabel(url))
}

private fun gameLabel(url: String): String = when {
    url.contains("hk4e", ignoreCase = true) -> "原神"
    url.contains("hkrpg", ignoreCase = true) -> "崩坏：星穹铁道"
    url.contains("zzz", ignoreCase = true) || url.contains("nap", ignoreCase = true) -> "绝区零"
    url.contains("bh3", ignoreCase = true) -> "崩坏3"
    else -> "米游"
}

private fun openWeb(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, "没有可以打开网页的应用", Toast.LENGTH_SHORT).show()
    }
}
