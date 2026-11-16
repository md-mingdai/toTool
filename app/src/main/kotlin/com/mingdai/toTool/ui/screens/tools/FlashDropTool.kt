package com.mingdai.toTool.ui.screens.tools

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.barcode.BarcodeRenderer
import com.mingdai.toTool.barcode.BarcodeType
import com.mingdai.toTool.data.LocalPrefs
import com.mingdai.toTool.flashdrop.FlashDropController
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    val controller = remember { FlashDropController(context, prefs) }
    val state = controller.state
    val lifecycleOwner = LocalLifecycleOwner.current

    var showCodeDialog by remember { mutableStateOf(false) }
    var codeDraft by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf<String?>(null) }

    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { controller.refresh() }

    DisposableEffect(Unit) {
        controller.start()
        onDispose { controller.stop() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    ToolBody(padding) {
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
                        Text("复制链接")
                    }
                    SecondaryButton(onClick = onRestart, modifier = Modifier.weight(1f)) {
                        Text("重启服务")
                    }
                    SecondaryButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                        Text("停止")
                    }
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
