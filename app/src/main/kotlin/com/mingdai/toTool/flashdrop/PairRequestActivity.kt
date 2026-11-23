package com.mingdai.toTool.flashdrop

import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.data.Prefs
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.theme.ToolyTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 后台收到配对请求时弹出的选择窗口；不是工具页面，只负责完成这一次配对。 */
class PairRequestActivity : ComponentActivity() {
    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        val controller = FlashDropRuntime.get(this, prefs)
        getSystemService(NotificationManager::class.java)?.cancel(PAIR_NOTIFICATION_ID)
        val initialId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        setContent {
            ToolyTheme(
                mode = prefs.themeMode(),
                customHue = prefs.customHue(),
                customSaturation = prefs.customSaturation(),
                customValue = prefs.customValue(),
            ) {
                val request = controller.state.pairRequests.firstOrNull { it.id == initialId }
                    ?: controller.state.pairRequests.firstOrNull()
                LaunchedEffect(request?.id) {
                    if (request == null) finish()
                }
                request?.let {
                    PairRequestDialog(
                        request = it,
                        controller = controller,
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun PairRequestDialog(
    request: PairRequestInfo,
    controller: FlashDropController,
    onDismiss: () -> Unit,
) {
    var selected by remember(request.id) { mutableStateOf<Int?>(null) }
    var error by remember(request.id) { mutableStateOf<String?>(null) }
    val colors = MiuixTheme.colorScheme

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(onClick = onDismiss),
        )
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .width(360.dp)
                .padding(24.dp),
            colors = CardDefaults.defaultColors(
                color = colors.surface,
                contentColor = colors.onSurface,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("隔空闪传配对请求", style = MiuixTheme.textStyles.title3)
                Text(
                    "${request.remoteHost} 请求连接，请从下面选择网页上显示的两位数。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
                if (request.userAgent.isNotBlank()) {
                    Text(
                        request.userAgent,
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    request.options.forEach { option ->
                        val chosen = selected == option
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (chosen) colors.primary else colors.secondaryContainer)
                                .clickable {
                                    selected = option
                                    error = null
                                }
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                option.toString(),
                                style = MiuixTheme.textStyles.title3.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold,
                                color = if (chosen) colors.onPrimary else colors.onSecondaryContainer,
                            )
                        }
                    }
                }
                error?.let {
                    Text(it, style = MiuixTheme.textStyles.footnote2, color = colors.error)
                }
                PrimaryButton(
                    onClick = {
                        val choice = selected ?: return@PrimaryButton
                        if (controller.approvePairRequest(request.id, choice)) {
                            onDismiss()
                        } else {
                            error = "数字与网页不一致，请重新选择"
                        }
                    },
                    enabled = selected != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("确认配对") }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(
                        onClick = {
                            controller.denyPairRequest(request.id)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("拒绝") }
                    SecondaryButton(
                        onClick = {
                            controller.blockPairRequest(request.id)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("暂时拉黑") }
                }
            }
        }
    }
}
