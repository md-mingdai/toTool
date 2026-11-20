package com.mingdai.toTool.ui.screens.tools

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.WarnColor
import com.mingdai.toTool.ui.components.displayStyle
import com.mingdai.toTool.weaknet.WeakNetConfig
import com.mingdai.toTool.weaknet.WeakNetManager
import com.mingdai.toTool.weaknet.WeakNetStore
import com.mingdai.toTool.weaknet.WeakNetVpnService
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class AppChoice(val packageName: String, val label: String)

private val WeakNetOk = Color(0xFF54A84A)

@Composable
fun WeakNetTool(padding: PaddingValues) {
    val context = LocalContext.current
    val state by WeakNetManager.state.collectAsState()
    val running = state.running
    val stored = remember { WeakNetStore.load(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var packageName by rememberSaveable { mutableStateOf(stored.packageName) }
    var appLabel by rememberSaveable { mutableStateOf(stored.appLabel) }
    var hostFilter by rememberSaveable { mutableStateOf(stored.hostFilter) }
    var downloadText by rememberSaveable { mutableStateOf(stored.downloadKBps.toString()) }
    var uploadText by rememberSaveable { mutableStateOf(stored.uploadKBps.toString()) }
    var latencyText by rememberSaveable { mutableStateOf(stored.latencyMs.toString()) }
    var showOverlay by rememberSaveable { mutableStateOf(stored.showOverlay) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showWarning by rememberSaveable { mutableStateOf(true) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var pendingStart by remember { mutableStateOf(false) }
    var selectedPreset by rememberSaveable { mutableStateOf("") }

    var apps by remember { mutableStateOf<List<AppChoice>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
        appsLoading = false
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun config(): WeakNetConfig = WeakNetConfig(
        packageName = packageName,
        appLabel = appLabel,
        hostFilter = hostFilter,
        downloadKBps = downloadText.toIntOrNull() ?: 128,
        uploadKBps = uploadText.toIntOrNull() ?: 64,
        latencyMs = latencyText.toIntOrNull() ?: 300,
        showOverlay = showOverlay,
    ).normalized()

    fun save() {
        WeakNetStore.save(context, config())
    }

    fun startService() {
        save()
        WeakNetManager.clearError()
        WeakNetVpnService.start(context)
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startService()
        } else {
            android.widget.Toast.makeText(context, "没有授予 VPN 权限，弱网助手无法启动", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayGranted = Settings.canDrawOverlays(context)
        if (overlayGranted && WeakNetManager.state.value.running) WeakNetVpnService.update(context)
    }

    fun requestVpnConsent() {
        val intent = VpnService.prepare(context)
        if (intent != null) vpnLauncher.launch(intent) else startService()
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingStart) {
            pendingStart = false
            requestVpnConsent()
        }
    }

    fun requestStart() {
        save()
        pendingStart = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            pendingStart = false
            requestVpnConsent()
        }
    }

    fun requestOverlay() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        overlayLauncher.launch(intent)
    }

    fun applyPreset(down: Int, up: Int, latency: Int, name: String) {
        downloadText = down.toString()
        uploadText = up.toString()
        latencyText = latency.toString()
        selectedPreset = name
    }

    val colors = MiuixTheme.colorScheme

    ToolBody(padding) {
        SectionLabel("运行状态")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (running) "●" else "○",
                        color = if (running) WeakNetOk else colors.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.title4,
                    )
                    Text(
                        when {
                            state.error != null -> "启动失败"
                            state.paused -> "已暂停限速"
                            running -> "运行中"
                            else -> "未运行"
                        },
                        style = MiuixTheme.textStyles.title4,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    if (running) {
                        Text(
                            "${state.connections} 连接",
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                }
                if (state.error != null) {
                    Text(state.error ?: "", color = WarnColor, style = MiuixTheme.textStyles.footnote1)
                }
                if (running) {
                    Text(
                        state.config.targetLabel,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        SpeedValue("下载", state.rxSpeed, colors.onSurface)
                        SpeedValue("上传", state.txSpeed, colors.onSurface)
                    }
                    Text(
                        "累计 ↓ ${formatBytes(state.rxBytes)} · ↑ ${formatBytes(state.txBytes)}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                    if (state.lastHost.isNotBlank()) {
                        Text(
                            "最近主机 ${state.lastHost}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SecondaryButton(
                            onClick = { WeakNetVpnService.pause(context) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("暂停限制", maxLines = 1)
                        }
                        SecondaryButton(
                            onClick = { WeakNetVpnService.stop(context) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("停止服务", maxLines = 1)
                        }
                    }
                } else {
                    Text(
                        when {
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
                                "弱网助手需要 Android 10 及以上系统"
                            state.paused ->
                                "已暂停限制，VPN 已关闭。点下方「继续限制」重新开启。"
                            else ->
                                "配置目标应用或主机后启动，VPN 会保持运行并可通过悬浮窗控制。"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }

        SectionLabel("目标")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (packageName == null) "所有应用" else (appLabel ?: packageName ?: ""),
                            style = MiuixTheme.textStyles.body1,
                        )
                        Text(
                            packageName ?: "不限制应用，配合目标主机使用",
                            style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    SecondaryButton(
                        onClick = { showAppPicker = true },
                        enabled = !running,
                    ) {
                        Text("选择应用")
                    }
                }
                if (packageName != null) {
                    SecondaryButton(
                        onClick = {
                            packageName = null
                            appLabel = null
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("清除应用，限制全部应用", maxLines = 1)
                    }
                }
                TextField(
                    value = hostFilter,
                    onValueChange = { hostFilter = it },
                    label = "目标主机（可选）",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running,
                )
                Text(
                    "填写域名或 IP；留空时限制目标应用的全部代理流量，填写后只对匹配主机限速，其它主机全速。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }

        SectionLabel("弱网参数")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectChip(selectedPreset == "2G", { applyPreset(16, 8, 800, "2G") }, "2G", enabled = !running)
            SelectChip(selectedPreset == "3G", { applyPreset(64, 32, 300, "3G") }, "3G", enabled = !running)
            SelectChip(selectedPreset == "弱 Wi-Fi", { applyPreset(256, 128, 120, "弱 Wi-Fi") }, "弱 Wi-Fi", enabled = !running)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = downloadText,
                onValueChange = { downloadText = it.filter(Char::isDigit) },
                label = "下载 KB/s",
                modifier = Modifier.weight(1f),
                enabled = !running,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            )
            TextField(
                value = uploadText,
                onValueChange = { uploadText = it.filter(Char::isDigit) },
                label = "上传 KB/s",
                modifier = Modifier.weight(1f),
                enabled = !running,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            )
        }
        TextField(
            value = latencyText,
            onValueChange = { latencyText = it.filter(Char::isDigit) },
            label = "附加延迟 ms",
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        )

        SectionLabel("悬浮窗")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示悬浮窗", style = MiuixTheme.textStyles.body1)
                        Text(
                            if (overlayGranted) "运行时可拖动查看速率、暂停限速或停止服务" else "需要先授予悬浮窗权限",
                            style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    Switch(
                        checked = showOverlay,
                        onCheckedChange = {
                            showOverlay = it
                            save()
                            if (running) WeakNetVpnService.update(context)
                        },
                    )
                }
                if (!overlayGranted) {
                    SecondaryButton(onClick = { requestOverlay() }, modifier = Modifier.fillMaxWidth()) {
                        Text("授予悬浮窗权限")
                    }
                }
            }
        }

        if (!running) {
            PrimaryButton(
                onClick = { requestStart() },
                modifier = Modifier.fillMaxWidth(),
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            ) {
                Text(if (state.paused) "继续限制" else "启动弱网助手")
            }
        }

        HintText("弱网助手通过系统 VPN 的 HTTP 代理限速，适合浏览器和遵循系统代理的 HTTP/HTTPS 应用。不使用代理的 App 或非 HTTP 协议可能没有网络；这是代理级模拟，不是内核级流量整形。目标主机留空时会限制该应用的全部代理流量。")
    }

    if (showWarning) {
        AppDialog(
            show = true,
            title = "使用警告",
            summary = "弱网助手仅供个人学习与参考使用。请仅对自己所有或已获得明确授权的应用、设备和系统使用，不得用于绕过他人网络管理、干扰他人服务或未经授权的测试。",
            confirmText = "我已了解",
            dismissText = null,
            onDismiss = {},
            onConfirm = { showWarning = false },
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = apps,
            loading = appsLoading,
            onDismiss = { showAppPicker = false },
            onSelect = { app ->
                packageName = app.packageName
                appLabel = app.label
                showAppPicker = false
            },
        )
    }
}

@Composable
private fun SpeedValue(label: String, value: Long, color: Color) {
    Column {
        Text(label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(
            formatSpeed(value),
            style = displayStyle(18.sp),
            color = color,
        )
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<AppChoice>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onSelect: (AppChoice) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isBlank()) apps else apps.filter {
            it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }
    AppDialog(
        show = true,
        title = "选择应用",
        summary = "VPN 只会把所选应用接入弱网代理",
        confirmText = "关闭",
        onDismiss = onDismiss,
        onConfirm = onDismiss,
    ) {
        TextField(
            value = query,
            onValueChange = { query = it },
            label = "搜索应用或包名",
            modifier = Modifier.fillMaxWidth(),
        )
        if (loading) {
            Text(
                "正在读取应用列表…",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(top = 8.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(app) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    Text(app.label, style = MiuixTheme.textStyles.body1)
                    Text(
                        app.packageName,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

private fun loadApps(context: Context): List<AppChoice> {
    val pm = context.packageManager
    return runCatching {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map { info ->
                AppChoice(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                )
            }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
            .toList()
    }.getOrDefault(emptyList())
}

private fun formatSpeed(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytes / 1024.0)
    else -> "$bytes B/s"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
