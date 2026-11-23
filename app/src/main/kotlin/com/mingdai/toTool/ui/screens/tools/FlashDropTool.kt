package com.mingdai.toTool.ui.screens.tools

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.barcode.BarcodeRenderer
import com.mingdai.toTool.barcode.BarcodeType
import com.mingdai.toTool.data.LocalPrefs
import com.mingdai.toTool.flashdrop.ClipboardMessage
import com.mingdai.toTool.flashdrop.FlashDropController
import com.mingdai.toTool.flashdrop.FlashDropRuntime
import com.mingdai.toTool.flashdrop.FlashDropState
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.WarnColor
import com.mingdai.toTool.ui.components.copyText
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class FlashDropTab(val label: String) {
    CONNECTION("连接"),
    RECEIVED("已接收"),
    CLIPBOARD("剪贴板"),
}

/**
 * 隔空闪传：在手机上启动一个局域网网页服务，电脑浏览器访问后管理手机文件。
 *
 * 服务只在页面存在期间运行；离开页面会停止。配对码默认每次配对后轮换，
 * 也可以改成固定的 6 位自定义码。开启加密后，文件内容用 AES-GCM 传输。
 */
@Composable
fun FlashDropTool(padding: PaddingValues) {
    val context = LocalContext.current
    val prefs = LocalPrefs.current
    val controller = remember { FlashDropRuntime.get(context, prefs) }
    val state = controller.state
    val lifecycleOwner = LocalLifecycleOwner.current

    var showCodeDialog by remember { mutableStateOf(false) }
    var codeDraft by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf<String?>(null) }
    var autoPermissionRequested by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(FlashDropTab.CONNECTION) }

    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { controller.refresh() }

    LaunchedEffect(Unit) {
        controller.setToolVisible(true)
        controller.start()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    controller.setToolVisible(true)
                    controller.refresh()
                }
                Lifecycle.Event.ON_PAUSE -> controller.setToolVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            controller.setToolVisible(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun openCodeDialog() {
        codeDraft = state.customCode.ifBlank { state.code }
        codeError = null
        showCodeDialog = true
    }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val page = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { context.startActivity(page) }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            storagePermission.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    // 进入工具页先申请一次文件访问权限；用户拒绝后不再反复弹出，只保留页面里的授权卡片。
    LaunchedEffect(state.storageGranted, autoPermissionRequested) {
        if (!state.storageGranted && !autoPermissionRequested) {
            autoPermissionRequested = true
            requestStorageAccess()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        FlashDropTabs(selected = tab, onSelect = { tab = it })
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                FlashDropTab.CONNECTION -> ToolBody(PaddingValues(0.dp)) {
        SectionLabel("网页服务")
        ServiceCard(
            state = state,
            url = controller.primaryUrl(),
            onCopy = { controller.primaryUrl()?.let { copyText(context, it, "网页地址") } },
            onStart = { controller.start() },
            onStop = { controller.stop() },
            onRestart = { controller.restart() },
        )

        if (!state.storageGranted) {
            PermissionCard(onGrant = { requestStorageAccess() })
        }

        SectionLabel("配对码")
        PairingCard(
            state = state,
            onToggleCustom = { enabled ->
                if (enabled) {
                    openCodeDialog()
                } else {
                    controller.updateCustomCode(false, state.customCode)
                }
            },
            onEditCustom = { openCodeDialog() },
        )

        SectionLabel("安全")
        Card(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                checked = state.encryptionEnabled,
                onCheckedChange = { controller.updateEncryption(it) },
                title = "端到端加密",
                summary = "用配对码派生的 AES-GCM 密钥加密上传和下载的文件内容。开启后已有配对会失效，需要重新输入配对码。",
            )
        }
        HintText("配对码和网页服务只在本机局域网内使用；请只在可信 Wi-Fi 下开启。")

        SectionLabel("使用方式")
        UsageCard()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "服务地址",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    state.addresses.firstOrNull()?.let { "http://$it:${state.port ?: 2233}" } ?: "尚未连接到局域网",
                    style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                    color = MiuixTheme.colorScheme.onSurface,
                )
                if (state.addresses.size > 1) {
                    Text(
                        "另有 ${state.addresses.size - 1} 个地址，任选其一",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
                }
                FlashDropTab.RECEIVED -> ReceivedTab(controller)
                FlashDropTab.CLIPBOARD -> ClipboardTab(controller)
            }
        }
    }

    val pendingPairRequest = state.pairRequests.firstOrNull()
    var selectedPairNumber by remember { mutableStateOf<Int?>(null) }
    var pairNumberError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingPairRequest?.id) {
        selectedPairNumber = null
        pairNumberError = null
    }
    AppDialog(
        show = pendingPairRequest != null,
        title = "确认配对数字",
        summary = null,
        confirmText = null,
        dismissText = null,
        onDismiss = {},
        onConfirm = {},
    ) {
        pendingPairRequest?.let { request ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${request.remoteHost} 请求连接这台手机",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    request.userAgent.ifBlank { "未知浏览器" },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "请求时间：${formatFileTime(request.createdAt)}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    "请在网页上查看本次配对数字，选择相同的一项后再点确认。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    request.options.forEach { option ->
                        val selected = selectedPairNumber == option
                        val container = if (selected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.secondaryContainer
                        }
                        val content = if (selected) {
                            MiuixTheme.colorScheme.onPrimary
                        } else {
                            MiuixTheme.colorScheme.onSecondaryContainer
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(container)
                                .clickable {
                                    selectedPairNumber = option
                                    pairNumberError = null
                                }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                option.toString(),
                                style = MiuixTheme.textStyles.title4.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold,
                                color = content,
                                maxLines = 1,
                            )
                        }
                    }
                }
                pairNumberError?.let { message ->
                    Text(
                        message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = WarnColor,
                    )
                }
                PrimaryButton(
                    onClick = {
                        val choice = selectedPairNumber
                        if (choice != null && !controller.approvePairRequest(request.id, choice)) {
                            pairNumberError = "数字与网页不一致，请重新选择"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedPairNumber != null,
                ) { Text("确认配对") }
                Text(
                    "确认前可以随时改选；选错不会结束请求。也可以直接拒绝或暂时拉黑。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        onClick = { controller.denyPairRequest(request.id) },
                        modifier = Modifier.weight(1f),
                    ) { Text("拒绝", maxLines = 1) }
                    SecondaryButton(
                        onClick = { controller.blockPairRequest(request.id) },
                        modifier = Modifier.weight(1f),
                    ) { Text("暂时拉黑", maxLines = 1) }
                }
                Text(
                    "暂时拉黑只对本次隔空闪传服务生效，关闭服务后解除。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    AppDialog(
        show = showCodeDialog,
        title = "自定义配对码",
        summary = "必须是 6 位数字。自定义码在关闭开关前不会轮换。",
        confirmText = "保存",
        onDismiss = { showCodeDialog = false },
        onConfirm = {
            if (codeDraft.length != 6) {
                codeError = "请输入 6 位数字"
            } else if (controller.updateCustomCode(true, codeDraft)) {
                showCodeDialog = false
            } else {
                codeError = "配对码格式不正确"
            }
        },
    ) {
        TextField(
            value = codeDraft,
            onValueChange = { value ->
                codeDraft = value.filter(Char::isDigit).take(6)
                codeError = null
            },
            label = "6 位数字",
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
        )
        if (codeError != null) {
            Text(
                codeError!!,
                color = WarnColor,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ServiceCard(
    state: FlashDropState,
    url: String?,
    onCopy: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val running = state.running
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.starting) "●" else if (running) "●" else "○",
                    color = if (running) colors.primary else colors.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.title4,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        state.starting -> "正在启动"
                        running -> "服务运行中"
                        else -> "服务未运行"
                    },
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (running) {
                    Text(
                        "端口 ${state.port}",
                        style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }

            if (running && url != null) {
                Text(
                    "电脑浏览器打开",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
                Text(
                    url,
                    style = MiuixTheme.textStyles.title4.copy(fontFamily = FontFamily.Monospace),
                    color = colors.primary,
                )
                val qr = remember(url) {
                    runCatching {
                        BarcodeRenderer.render(
                            BarcodeRenderer.encode(BarcodeType.QR, url, 2),
                            maxSize = 700,
                        )
                    }.getOrNull()
                }
                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "访问隔空闪传网页的二维码",
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(172.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .padding(top = 4.dp),
                    )
                }
                Text(
                    "同一 Wi-Fi 下扫码，或手动输入上面的地址",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
            } else if (!state.starting) {
                Text(
                    if (running) "没有检测到局域网 IPv4 地址，请连接 Wi-Fi 后点「重启服务」。"
                    else "服务停止后，电脑上的页面会立即断开。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
            }

            if (state.error != null) {
                Text(
                    state.error,
                    style = MiuixTheme.textStyles.footnote1,
                    color = WarnColor,
                )
            }

            if (running) {
                Text(
                    if (state.pairedClients > 0) "已配对 ${state.pairedClients} 台电脑" else "还没有电脑配对",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                        Text("复制链接", maxLines = 1)
                    }
                    SecondaryButton(onClick = onRestart, modifier = Modifier.weight(1f)) {
                        Text("重启服务", maxLines = 1)
                    }
                }
                SecondaryButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("停止服务")
                }
            } else {
                PrimaryButton(
                    onClick = onStart,
                    enabled = !state.starting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.starting) "正在启动…" else "启动服务")
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "需要「所有文件访问权限」",
                style = MiuixTheme.textStyles.title4,
                color = WarnColor,
            )
            Text(
                "没有这项权限时，网页仍然可以查看已安装应用，但照片、视频、文档和上传下载会失败。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            SecondaryButton(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                Text("去授权")
            }
        }
    }
}

@Composable
private fun PairingCard(
    state: FlashDropState,
    onToggleCustom: (Boolean) -> Unit,
    onEditCustom: () -> Unit,
) {
    val code = state.code.ifBlank { "------" }
    val display = if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (state.customCodeEnabled) "自定义配对码" else "本次配对码",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                display,
                style = MiuixTheme.textStyles.title1.copy(
                    fontSize = 36.sp,
                    letterSpacing = 5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                ),
                color = MiuixTheme.colorScheme.primary,
            )
            Text(
                if (state.customCodeEnabled) {
                    "固定的 6 位码，关闭开关后恢复自动轮换"
                } else {
                    "每完成一次配对，自动换一个新的 6 位码"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            checked = state.customCodeEnabled,
            onCheckedChange = onToggleCustom,
            title = "使用自定义配对码",
            summary = "关闭时，每次配对成功后自动轮换配对码",
        )
    }
    if (state.customCodeEnabled) {
        SecondaryButton(onClick = onEditCustom, modifier = Modifier.fillMaxWidth()) {
            Text("修改自定义配对码")
        }
    }
}

@Composable
private fun UsageCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UsageLine("1", "让手机和电脑连接同一个 Wi-Fi。")
            UsageLine("2", "在电脑浏览器打开上面的地址，或直接扫码。")
            UsageLine("3", "输入手机屏幕上显示的 6 位配对码。")
            UsageLine("4", "配对后即可浏览手机文件、上传和下载。")
        }
    }
}

@Composable
private fun UsageLine(index: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            index,
            style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}


@Composable
private fun FlashDropTabs(selected: FlashDropTab, onSelect: (FlashDropTab) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            FlashDropTab.entries.forEach { tab ->
                val active = tab == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(tab) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        tab.label,
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) colors.primary else colors.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(34.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (active) colors.primary else Color.Transparent),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.dividerLine))
    }
}

@Composable
private fun ReceivedTab(controller: FlashDropController) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            files = controller.receivedFiles()
            delay(1500)
        }
    }

    ToolBody(PaddingValues(0.dp)) {
        SectionLabel("文件")
        if (files.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 76.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "还没有接收到文件",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    files.forEachIndexed { index, file ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp)
                                    .height(0.5.dp)
                                    .background(MiuixTheme.colorScheme.dividerLine),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    file.name,
                                    style = MiuixTheme.textStyles.body2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${Formatter.formatShortFileSize(context, file.length())} · ${formatFileTime(file.lastModified())}",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Text(
                                "已接收",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            IconButton(
                                onClick = { pendingDelete = file },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "删除 ${file.name}",
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    AppDialog(
        show = pendingDelete != null,
        title = "删除接收到的文件",
        summary = pendingDelete?.let { "从手机中永久删除「${it.name}」？" },
        confirmText = "删除",
        onDismiss = { pendingDelete = null },
        onConfirm = {
            pendingDelete?.let { controller.deleteReceived(it) }
            pendingDelete = null
            files = controller.receivedFiles()
        },
    )
}

@Composable
private fun ClipboardTab(controller: FlashDropController) {
    val context = LocalContext.current
    var messages by remember { mutableStateOf<List<ClipboardMessage>>(emptyList()) }
    var text by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            messages = controller.syncClipboard()
            delay(1200)
        }
    }

    ToolBody(PaddingValues(0.dp)) {
        SectionLabel("剪贴板")
        if (messages.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "还没有剪贴板消息",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            messages.forEach { message ->
                val own = message.source == "phone"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.82f)
                            .clip(RoundedCornerShape(15.dp))
                            .background(
                                if (own) MiuixTheme.colorScheme.primaryContainer
                                else MiuixTheme.colorScheme.secondaryContainer,
                            )
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                    ) {
                        Column {
                            Text(
                                message.text,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${if (own) "手机" else "电脑"} · ${message.createdAt}",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { copyText(context, message.text, "剪贴板") },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = "复制",
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "发送到手机剪贴板",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                PrimaryButton(
                    onClick = {
                        if (text.isNotBlank() && controller.sendClipboardFromPhone(text)) {
                            text = ""
                            messages = controller.clipboardMessages()
                        }
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("发送")
                }
            }
        }
    }
}

private fun formatFileTime(millis: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(millis))
