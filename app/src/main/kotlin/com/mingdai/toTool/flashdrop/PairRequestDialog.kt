package com.mingdai.toTool.flashdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 隔空闪传交互式配对弹窗。前台页面和后台透明 Activity 共用同一份内容与样式。
 * [onFinished] 在确认、拒绝或拉黑后调用，后台 Activity 用它关闭自己。
 */
@Composable
internal fun FlashDropPairRequestDialog(
    request: PairRequestInfo?,
    controller: FlashDropController,
    onFinished: () -> Unit = {},
) {
    var selected by remember(request?.id) { mutableStateOf<Int?>(null) }

    AppDialog(
        show = request != null,
        title = "确认配对数字",
        summary = null,
        confirmText = null,
        dismissText = null,
        onDismiss = {},
        onConfirm = {},
    ) {
        request?.let { info ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${info.remoteHost} 请求连接这台手机",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    info.userAgent.ifBlank { "未知浏览器" },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "请求时间：${formatPairTime(info.createdAt)}",
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
                    info.options.forEach { option ->
                        val chosen = selected == option
                        val container = if (chosen) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.secondaryContainer
                        }
                        val content = if (chosen) {
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
                                    selected = option
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
                PrimaryButton(
                    onClick = {
                        val choice = selected
                        if (choice == null) return@PrimaryButton
                        if (controller.approvePairRequest(info.id, choice)) {
                            onFinished()
                        } else {
                            // 选错直接取消这次配对，不再提示重试。
                            controller.denyPairRequest(info.id)
                            onFinished()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selected != null,
                ) { Text("确认配对") }
                Text(
                    "确认前可以随时改选；选错会直接取消本次配对请求。也可以直接拒绝或暂时拉黑。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        onClick = {
                            controller.denyPairRequest(info.id)
                            onFinished()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("拒绝", maxLines = 1) }
                    SecondaryButton(
                        onClick = {
                            controller.blockPairRequest(info.id)
                            onFinished()
                        },
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
}

private fun formatPairTime(millis: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(millis))
