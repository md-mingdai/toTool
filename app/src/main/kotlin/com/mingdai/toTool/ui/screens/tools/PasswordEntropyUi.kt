package com.mingdai.toTool.ui.screens.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SelectChip
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 密码生成器里的「熵源选择」区块：传感器采集 / 绘制采点两页。 */
@Composable
internal fun PasswordEntropySection(
    tab: PasswordEntropyTab,
    onTabChange: (PasswordEntropyTab) -> Unit,
    sources: List<PasswordEntropySource>,
    selectedIds: Set<String>,
    onToggleSource: (String) -> Unit,
    drawPoints: List<PasswordDrawPoint?>,
    drawCount: Int,
    collecting: Boolean,
    onStrokeStart: (Offset) -> Unit,
    onStrokePoint: (Offset) -> Unit,
    onClearDrawing: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SelectChip(
                    selected = tab == PasswordEntropyTab.SENSORS,
                    onClick = { onTabChange(PasswordEntropyTab.SENSORS) },
                    label = "传感器采集",
                    modifier = Modifier.weight(1f),
                    enabled = !collecting,
                )
                SelectChip(
                    selected = tab == PasswordEntropyTab.DRAW,
                    onClick = { onTabChange(PasswordEntropyTab.DRAW) },
                    label = "绘制采点",
                    modifier = Modifier.weight(1f),
                    enabled = !collecting,
                )
            }

            when (tab) {
                PasswordEntropyTab.SENSORS -> {
                    if (sources.isEmpty()) {
                        Text(
                            "此设备没有可用的传感器熵源。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.onSurfaceVariantSummary,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            sources.forEach { source ->
                                SelectChip(
                                    selected = source.id in selectedIds,
                                    onClick = { onToggleSource(source.id) },
                                    label = source.label,
                                    enabled = !collecting,
                                )
                            }
                        }
                    }
                    HintText(
                        "上方选项变化时会先用系统随机数刷新密码；点击下方按钮才会采样所选熵源。" +
                            "生成时会采样所选传感器约 0.4 秒；麦克风与摄像头最长约 2.5 秒，" +
                            "首次使用需要授权。采样结果只用于本次生成，不会保存原始数据。" +
                            "熵源只用于播种，密码强度仍由长度与字符池决定。",
                    )
                }
                PasswordEntropyTab.DRAW -> {
                    PasswordDrawingPad(
                        points = drawPoints,
                        enabled = !collecting,
                        onStart = onStrokeStart,
                        onPoint = onStrokePoint,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .widthIn(max = 300.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已采样 $drawCount / 500 点",
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (drawCount >= 500) colors.primary else colors.onSurfaceVariantSummary,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(onClick = onClearDrawing, enabled = drawCount > 0) {
                            Text("清空")
                        }
                    }
                    HintText(
                        "在 1:1 小画板上随意涂画，采集约 500 个带时间戳的坐标点并作为唯一种子生成密码；" +
                            "同一笔迹与相同字符设置会得到相同密码，改动笔迹或设置后才会变化。" +
                            "上方选项变化时会先用系统随机数刷新密码；点击下方按钮才会使用笔迹熵源。" +
                            "原始笔迹不会保存，请尽量画得随机、难以复现。" +
                            "熵源只用于播种，密码强度仍由长度与字符池决定。",
                    )
                }
            }
        }
    }
}

@Composable
private fun PasswordDrawingPad(
    points: List<PasswordDrawPoint?>,
    enabled: Boolean,
    onStart: (Offset) -> Unit,
    onPoint: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val strokeColor = colors.primary
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceContainer)
            .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = onStart,
                    onDrag = { change, _ ->
                        change.consume()
                        onPoint(change.position)
                    },
                )
            },
    ) {
        val path = Path()
        var last: Offset? = null
        for (point in points) {
            if (point == null) {
                last = null
                continue
            }
            val position = Offset(point.x, point.y)
            if (last == null) {
                path.moveTo(position.x, position.y)
            } else {
                path.lineTo(position.x, position.y)
            }
            last = position
        }
        drawPath(
            path = path,
            color = strokeColor,
            style = Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
