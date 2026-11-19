package com.mingdai.toTool.ui.screens.tools

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.notification.NotificationHiderBridge
import com.mingdai.toTool.notification.NotificationHiderItem
import com.mingdai.toTool.notification.NotificationHiderService
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.WarnColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NotificationHiderTool(padding: PaddingValues) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notifications by NotificationHiderBridge.notifications.collectAsState()
    val connected by NotificationHiderBridge.connected.collectAsState()
    var accessGranted by remember { mutableStateOf(NotificationHiderService.hasAccess(context)) }

    fun refreshAccess() {
        accessGranted = NotificationHiderService.hasAccess(context)
        if (accessGranted) {
            NotificationHiderService.requestAccessRebind(context)
            NotificationHiderBridge.refresh()
        }
    }

    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshAccess()
    }

    LaunchedEffect(Unit) { refreshAccess() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val active = notifications.filter { !it.isHidden }
    val hidden = notifications.filter { it.isHidden }
    val colors = MiuixTheme.colorScheme

    ToolBody(padding) {
        SectionLabel("隐藏系统通知")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("只隐藏本机通知", style = MiuixTheme.textStyles.body1)
                Text(
                    "通过 Android 通知使用权把选中的通知打盹数年，并在应用重新推送时继续隐藏。隐藏状态只保存在本机。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
                Text(
                    "请勿隐藏验证码、安全提醒或系统关键通知，否则可能错过重要信息。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = WarnColor,
                )
            }
        }

        if (!accessGranted) {
            SectionLabel("权限")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("需要通知使用权", style = MiuixTheme.textStyles.body1)
                    Text(
                        "Android 不允许应用自行获取该权限，请到系统设置中手动开启「toTool 隐藏系统通知」。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                    )
                    PrimaryButton(
                        onClick = {
                            settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("打开通知使用权设置")
                    }
                }
            }
            return@ToolBody
        }

        if (!connected) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("正在连接通知服务…", style = MiuixTheme.textStyles.body1)
                    Text(
                        "如果一直停在这里，请确认系统设置里的通知使用权仍然开启，然后点刷新。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                    )
                    SecondaryButton(
                        onClick = { refreshAccess() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("刷新连接")
                    }
                }
            }
            return@ToolBody
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) { SectionLabel("当前通知 · ${active.size}") }
            Text(
                "刷新",
                color = colors.primary,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { NotificationHiderBridge.refresh() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        if (active.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 42.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "当前没有可隐藏的通知",
                        style = MiuixTheme.textStyles.body2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    active.take(80).forEachIndexed { index, item ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp)
                                    .height(0.5.dp)
                                    .background(colors.dividerLine),
                            )
                        }
                        NotificationRow(
                            item = item,
                            action = "隐藏",
                            actionColor = colors.primary,
                            onAction = {
                                if (NotificationHiderBridge.hide(item.key)) {
                                    Toast.makeText(context, "已隐藏通知", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "隐藏失败，请刷新通知服务", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
            }
            if (active.size > 80) {
                HintText("仅显示最近 80 条通知。")
            }
        }

        if (hidden.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) { SectionLabel("已隐藏 · ${hidden.size}") }
                Text(
                    "全部恢复",
                    color = colors.primary,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            if (NotificationHiderBridge.unhideAll()) {
                                Toast.makeText(context, "正在恢复通知", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    hidden.forEachIndexed { index, item ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp)
                                    .height(0.5.dp)
                                    .background(colors.dividerLine),
                            )
                        }
                        NotificationRow(
                            item = item,
                            action = "恢复",
                            actionColor = colors.primary,
                            onAction = {
                                if (NotificationHiderBridge.unhide(item.key)) {
                                    Toast.makeText(context, "已取消隐藏", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "恢复失败，请刷新通知服务", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
            }
        }

        HintText("恢复通知后，当前被隐藏的通知需要系统重新投递或应用重新推送后才会重新出现。")
    }
}

@Composable
private fun NotificationRow(
    item: NotificationHiderItem,
    action: String,
    actionColor: androidx.compose.ui.graphics.Color,
    onAction: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.appName,
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatNotificationTime(if (item.isHidden) item.hiddenAt else item.postTime),
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            Text(
                item.title,
                style = MiuixTheme.textStyles.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (item.text.isNotBlank()) {
                Text(
                    item.text,
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Text(
            action,
            color = actionColor,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onAction)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

private fun formatNotificationTime(time: Long): String {
    if (time <= 0L) return ""
    val now = System.currentTimeMillis()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(now)) ==
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(time))
    return SimpleDateFormat(if (sameDay) "HH:mm" else "M月d日", Locale.getDefault()).format(Date(time))
}
