package com.mingdai.toTool.ui.screens.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.gacha.GachaLinkBridge
import com.mingdai.toTool.gacha.GachaLinkService
import com.mingdai.toTool.shizuku.ShizukuAccess
import com.mingdai.toTool.shizuku.ShizukuState
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.WarnColor
import com.mingdai.toTool.ui.components.copyText
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 游戏打开抽卡记录时会通过 logcat 输出带 authkey 的记录链接。
 * 阻塞的日志监听由前台服务完成，通知栏持续显示等待/完成状态；这里只负责配置和展示结果。
 */
@Composable
fun GachaLinkTool(padding: PaddingValues) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val watch by GachaLinkBridge.state.collectAsState()

    var state by remember { mutableStateOf(ShizukuAccess.state(context)) }
    var identity by remember { mutableStateOf<String?>(null) }
    var settled by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }

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

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingStart) {
            pendingStart = false
            GachaLinkService.start(context)
        }
    }

    fun requestStart() {
        if (watch.running || state != ShizukuState.READY) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GachaLinkService.start(context)
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
                    "点下方「开始后台获取」，然后切换到原神、崩坏：星穹铁道或绝区零，打开「抽卡/跃迁记录」。后台会先读取最近日志，并持续等待新的记录链接。",
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
                        onClick = {
                            ShizukuAccess.requestPermission()?.let {
                                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                            }
                        },
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

        if (watch.running) {
            SecondaryButton(
                onClick = { GachaLinkService.stop(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("停止后台获取")
            }
        } else {
            PrimaryButton(
                onClick = { requestStart() },
                enabled = state == ShizukuState.READY,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("开始后台获取")
            }
        }

        if (watch.waiting) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("后台监听中", style = MiuixTheme.textStyles.body1)
                    Text(
                        "正在读取最近日志并等待新的抽卡链接。可以切换到游戏；通知栏会显示状态，找到后点通知即可回到这里。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        watch.error?.let { message ->
            Text(
                message,
                color = WarnColor,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        if (watch.link != null) {
            val url = watch.link ?: ""
            SectionLabel("获取结果")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(watch.game ?: "米游", style = MiuixTheme.textStyles.headline2)
                    Text(
                        url,
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
                            onClick = { copyText(context, url, "抽卡记录链接") },
                            modifier = Modifier.weight(1f),
                        ) { Text("复制链接") }
                        SecondaryButton(
                            onClick = { openWeb(context, url) },
                            modifier = Modifier.weight(1f),
                        ) { Text("在浏览器打开") }
                    }
                }
            }
        }

        HintText("后台监听会保留通知直到你手动停止。最近日志可能包含上一次打开的记录链接；如果结果不是本次记录，请重新在游戏里打开抽卡页后再试。")
    }
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
