package com.mingdai.toTool.ui.screens.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.permission.HighPermissionManager
import com.mingdai.toTool.permission.HighPermissionStatus
import com.mingdai.toTool.permission.HighPermissionStrategy
import com.mingdai.toTool.system.OrientationOption
import com.mingdai.toTool.system.OrientationOverlayController
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.MessageCard
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import java.util.Collections
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ─────────────────────────── 高级权限共享 ─────────────────────────── */

@Composable
private fun rememberHighPermission(): HighPermissionStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(HighPermissionManager.status(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            status = HighPermissionManager.status(context)
            delay(1500)
        }
    }
    return status
}

@Composable
private fun HighPermissionCard(context: Context, status: HighPermissionStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "高级权限",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                "${status.title} · ${if (status.ready) "已就绪" else "未就绪"}",
                style = MiuixTheme.textStyles.body1,
                color = if (status.ready) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
            )
            Text(
                status.detail,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (status.strategy == HighPermissionStrategy.SHIZUKU && !status.ready) {
                SecondaryButton(
                    onClick = {
                        val message = HighPermissionManager.requestPermission(context)
                        if (!message.isNullOrBlank()) {
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("授权 Shizuku") }
            }
        }
    }
}

private suspend fun runHighCommand(
    context: Context,
    script: String,
): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
    try {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val code = HighPermissionManager.execute(context, script) { _, line ->
            synchronized(lines) {
                if (lines.size < 40) lines.add(line)
            }
        }
        code to synchronized(lines) { lines.toList() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        -1 to listOf(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }
}

/* ─────────────────────────── 电量伪装 ─────────────────────────── */

private enum class BatteryFakeMode(val label: String) {
    DISCHARGING("放电"),
    CHARGING("充电"),
    FULL("充满"),
    NOT_CHARGING("未充电"),
}

private fun batteryScript(level: Int, mode: BatteryFakeMode): String = buildString {
    appendLine("dumpsys battery set level $level")
    when (mode) {
        BatteryFakeMode.DISCHARGING -> appendLine(
            "dumpsys battery set ac 0; dumpsys battery set usb 0; " +
                "dumpsys battery set wireless 0; dumpsys battery set status 3",
        )
        BatteryFakeMode.CHARGING -> appendLine(
            "dumpsys battery set ac 1; dumpsys battery set usb 0; " +
                "dumpsys battery set wireless 0; dumpsys battery set status 2",
        )
        BatteryFakeMode.FULL -> appendLine(
            "dumpsys battery set ac 1; dumpsys battery set usb 0; " +
                "dumpsys battery set wireless 0; dumpsys battery set status 5",
        )
        BatteryFakeMode.NOT_CHARGING -> appendLine(
            "dumpsys battery set ac 1; dumpsys battery set usb 0; " +
                "dumpsys battery set wireless 0; dumpsys battery set status 4",
        )
    }
}

@Composable
fun BatterySpoofTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberHighPermission()
    var level by rememberSaveable { mutableStateOf(50f) }
    var mode by rememberSaveable { mutableStateOf(BatteryFakeMode.DISCHARGING) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun execute(script: String, success: String) {
        if (busy || !permission.ready) return
        scope.launch {
            busy = true
            notice = null
            error = null
            val (code, lines) = runHighCommand(context, script)
            busy = false
            if (code == 0) {
                notice = success
            } else {
                error = buildString {
                    append("命令执行失败（退出码 $code）")
                    if (lines.isNotEmpty()) {
                        append("\n")
                        append(lines.takeLast(8).joinToString("\n"))
                    }
                }
            }
        }
    }

    ToolBody(padding) {
        HighPermissionCard(context, permission)
        SectionLabel("伪装电量")
        Text("${level.roundToInt()}%", style = MiuixTheme.textStyles.title2)
        Slider(
            value = level,
            onValueChange = { level = it },
            valueRange = 1f..100f,
            enabled = !busy,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BatteryFakeMode.entries.forEach { option ->
                SelectChip(
                    selected = mode == option,
                    onClick = { mode = option },
                    label = option.label,
                    enabled = !busy,
                )
            }
        }
        HintText("只修改系统报告的电池状态，不会改变真实电量；执行「恢复真实电量」或重启后恢复。")
        PrimaryButton(
            onClick = {
                execute(
                    batteryScript(level.roundToInt(), mode),
                    "已应用电量伪装：${level.roundToInt()}% / ${mode.label}",
                )
            },
            enabled = permission.ready && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "正在执行…" else "应用电量伪装") }
        SecondaryButton(
            onClick = { execute("dumpsys battery reset", "已恢复真实电池状态") },
            enabled = permission.ready && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("恢复真实电量") }
        notice?.let {
            MessageCard(null, it, MiuixTheme.colorScheme.secondaryContainer, MiuixTheme.colorScheme.onSecondaryContainer)
        }
        error?.let {
            MessageCard("执行失败", it, MiuixTheme.colorScheme.errorContainer, MiuixTheme.colorScheme.onErrorContainer)
        }
    }
}

/* ─────────────────────────── DPI 修改 ─────────────────────────── */

@Composable
fun DpiTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberHighPermission()
    var dpiText by rememberSaveable { mutableStateOf("") }
    var currentDensity by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val dpi = dpiText.toIntOrNull()
    val valid = dpi != null && dpi in 120..1000

    suspend fun readDensity() {
        val (code, lines) = runHighCommand(context, "wm density")
        if (code != 0) return
        val text = lines.joinToString("\n")
        val override = Regex("""Override density:\s*(\d+)""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
        val physical = Regex("""Physical density:\s*(\d+)""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
        val value = override ?: physical
        currentDensity = value
        value?.let { dpiText = it.toString() }
    }

    LaunchedEffect(permission.ready) {
        if (permission.ready && currentDensity == null) readDensity()
    }

    fun execute(script: String, success: String) {
        if (busy || !permission.ready) return
        scope.launch {
            busy = true
            notice = null
            error = null
            val (code, lines) = runHighCommand(context, script)
            busy = false
            if (code == 0) {
                notice = success
                readDensity()
            } else {
                error = buildString {
                    append("命令执行失败（退出码 $code）")
                    if (lines.isNotEmpty()) {
                        append("\n")
                        append(lines.takeLast(8).joinToString("\n"))
                    }
                }
            }
        }
    }

    ToolBody(padding) {
        HighPermissionCard(context, permission)
        SectionLabel("屏幕密度")
        Text(
            currentDensity?.let { "当前：$it DPI" } ?: "当前：读取中…",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        TextField(
            value = dpiText,
            onValueChange = { dpiText = it.filter { ch -> ch.isDigit() }.take(4) },
            label = "DPI",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(360, 420, 480, 560, 640).forEach { value ->
                SelectChip(
                    selected = dpi == value,
                    onClick = { dpiText = value.toString() },
                    label = value.toString(),
                    enabled = !busy,
                )
            }
        }
        HintText("修改 DPI 可能让部分界面错位；部分 ROM 需要重启或重新登录。120–1000 为安全范围。")
        PrimaryButton(
            onClick = { dpi?.let { execute("wm density $it", "已应用 ${it} DPI") } },
            enabled = permission.ready && valid && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "正在执行…" else "应用 DPI") }
        SecondaryButton(
            onClick = { execute("wm density reset", "已恢复默认 DPI") },
            enabled = permission.ready && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("恢复默认 DPI") }
        notice?.let {
            MessageCard(null, it, MiuixTheme.colorScheme.secondaryContainer, MiuixTheme.colorScheme.onSecondaryContainer)
        }
        error?.let {
            MessageCard("执行失败", it, MiuixTheme.colorScheme.errorContainer, MiuixTheme.colorScheme.onErrorContainer)
        }
    }
}

/* ─────────────────────────── 高级重启 ─────────────────────────── */

private enum class RebootAction(
    val label: String,
    val script: String,
    val rootOnly: Boolean = false,
) {
    REBOOT("设备重启", "svc power reboot || reboot"),
    SOFT_REBOOT("快速重启（软重启）", "setprop ctl.restart zygote", rootOnly = true),
    RESTART_UI("重启用户界面", "pkill -f com.android.systemui || killall com.android.systemui", rootOnly = true),
    SHUTDOWN("设备关机", "svc power shutdown || reboot -p"),
    RECOVERY("重启至 Recovery", "reboot recovery"),
    FASTBOOT("重启至 fastboot（bootloader）", "reboot bootloader"),
    FASTBOOTD("重启至 fastbootd", "reboot fastboot"),
    SAFE_MODE("重启至安全模式", "setprop persist.sys.safemode 1; reboot", rootOnly = true),
}

@Composable
fun AdvancedRebootTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberHighPermission()
    var action by rememberSaveable { mutableStateOf(RebootAction.REBOOT) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun execute() {
        if (busy || !permission.ready) return
        if (action.rootOnly && permission.strategy != HighPermissionStrategy.ROOT) return
        scope.launch {
            busy = true
            notice = null
            error = null
            val (code, lines) = runHighCommand(context, action.script)
            busy = false
            if (code == 0) {
                notice = "已发送${action.label}命令。"
            } else {
                error = buildString {
                    append("命令执行失败（退出码 $code）")
                    if (lines.isNotEmpty()) {
                        append("\n")
                        append(lines.takeLast(8).joinToString("\n"))
                    }
                }
            }
        }
    }

    val rootReady = permission.strategy == HighPermissionStrategy.ROOT && permission.ready
    val actionAllowed = permission.ready && (!action.rootOnly || rootReady)

    ToolBody(padding) {
        HighPermissionCard(context, permission)
        SectionLabel("重启方式")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RebootAction.entries.forEach { option ->
                SelectChip(
                    selected = action == option,
                    onClick = { action = option },
                    label = option.label,
                    enabled = !busy,
                )
            }
        }
        if (action.rootOnly && !rootReady) {
            HintText("「${action.label}」只能在 Root 策略下执行。")
        }
        HintText("命令通过高级权限执行，设备会立即重启或关机；请先保存其它应用中的工作。")
        PrimaryButton(
            onClick = { confirm = true },
            enabled = actionAllowed && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "正在执行…" else "执行${action.label}") }
        notice?.let {
            MessageCard(null, it, MiuixTheme.colorScheme.secondaryContainer, MiuixTheme.colorScheme.onSecondaryContainer)
        }
        error?.let {
            MessageCard("执行失败", it, MiuixTheme.colorScheme.errorContainer, MiuixTheme.colorScheme.onErrorContainer)
        }
    }

    AppDialog(
        show = confirm,
        title = "确认${action.label}？",
        summary = "设备会立即${if (action == RebootAction.SHUTDOWN) "关机" else "重启"}，未保存的工作可能丢失。",
        onDismiss = { confirm = false },
        confirmText = action.label,
        onConfirm = {
            confirm = false
            execute()
        },
    )
}

/* ─────────────────────────── 全局方向调节 ─────────────────────────── */

@Composable
fun GlobalOrientationTool(padding: PaddingValues) {
    val context = LocalContext.current
    val state by OrientationOverlayController.state.collectAsState()
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var selected by rememberSaveable { mutableStateOf(OrientationOption.FORCE_PORTRAIT) }

    LaunchedEffect(Unit) {
        while (true) {
            overlayAllowed = Settings.canDrawOverlays(context)
            delay(800)
        }
    }
    LaunchedEffect(state.orientation) {
        OrientationOption.fromValue(state.orientation)?.let { selected = it }
    }

    ToolBody(padding) {
        SectionLabel("悬浮窗方向控制")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("全局方向", style = MiuixTheme.textStyles.title4)
                        Text(
                            state.option?.label ?: "未锁定方向",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Switch(
                        checked = state.running,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                if (overlayAllowed) {
                                    OrientationOverlayController.start(context, selected)
                                } else {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                    runCatching { context.startActivity(intent) }.onFailure {
                                        Toast.makeText(context, "无法打开悬浮窗权限设置", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                OrientationOverlayController.stop(context)
                            }
                        },
                    )
                }
                Text(
                    "顶端胶囊开关控制启停；选择方向后立即生效。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (!overlayAllowed) {
                    Text(
                        "需要「显示在其他应用上层」权限才能创建不可见悬浮窗。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
        if (!overlayAllowed) {
            PrimaryButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { context.startActivity(intent) }.onFailure {
                        Toast.makeText(context, "无法打开悬浮窗权限设置", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("授予悬浮窗权限") }
        }
        SectionLabel("方向")
        OrientationOption.entries.forEach { option ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selected = option
                        if (state.running) {
                            OrientationOverlayController.update(context, option)
                        }
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = MiuixTheme.textStyles.body1)
                        Text(
                            option.subtitle,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (selected == option) {
                        Text(
                            "已选",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        state.detail?.let {
            MessageCard("悬浮窗方向控制", it, MiuixTheme.colorScheme.errorContainer, MiuixTheme.colorScheme.onErrorContainer)
        }
        HintText("方向由不可见悬浮窗向 WindowManager 申请；停止后恢复系统自动旋转。部分 ROM 可能忽略这类方向请求。")
    }
}
