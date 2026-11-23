package com.mingdai.toTool.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.background.BackgroundTask
import com.mingdai.toTool.background.BackgroundTaskRegistry
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 主界面上的可拖动任务抽屉；按钮和展开主体都能拖，任务行可跳回对应工具。 */
@Composable
fun BackgroundTaskDrawer(
    modifier: Modifier = Modifier,
    onOpenTool: (String) -> Unit = {},
) {
    val tasks by BackgroundTaskRegistry.tasks.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(tasks.isEmpty()) {
        if (tasks.isEmpty()) open = false
    }
    if (tasks.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxWidth = constraints.maxWidth.toFloat()
        val maxHeight = constraints.maxHeight.toFloat()
        val baseLeft = (maxWidth - panelSize.width).coerceAtLeast(0f)
        val baseTop = ((maxHeight - panelSize.height) / 2f).coerceAtLeast(0f)

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .onSizeChanged { panelSize = it }
                .pointerInput(maxWidth, maxHeight, panelSize) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val maxLeft = (maxWidth - panelSize.width).coerceAtLeast(0f)
                        val maxTop = (maxHeight - panelSize.height).coerceAtLeast(0f)
                        val left = (baseLeft + dragOffset.x + dragAmount.x).coerceIn(0f, maxLeft)
                        val top = (baseTop + dragOffset.y + dragAmount.y).coerceIn(0f, maxTop)
                        dragOffset = Offset(left - baseLeft, top - baseTop)
                    }
                },
        ) {
            AnimatedVisibility(
                visible = open,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            ) {
                val colors = MiuixTheme.colorScheme
                Card(
                    modifier = Modifier
                        .width(300.dp)
                        .padding(6.dp)
                        .shadow(12.dp, RoundedCornerShape(18.dp))
                        .border(
                            1.dp,
                            colors.outline.copy(alpha = 0.55f),
                            RoundedCornerShape(18.dp),
                        ),
                    colors = CardDefaults.defaultColors(
                        color = colors.surface,
                        contentColor = colors.onSurface,
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("后台任务", style = MiuixTheme.textStyles.title4)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { open = false }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "收起",
                                    tint = colors.onSurfaceVariantSummary,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            tasks.forEach { task ->
                                TaskRow(
                                    task = task,
                                    onOpen = {
                                        open = false
                                        onOpenTool(task.toolId)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (!open) {
                val colors = MiuixTheme.colorScheme
                Box(
                    modifier = Modifier
                        .width(34.dp)
                        .height(100.dp)
                        .shadow(10.dp, RoundedCornerShape(topStart = 17.dp, bottomStart = 17.dp))
                        .clip(RoundedCornerShape(topStart = 17.dp, bottomStart = 17.dp))
                        .background(colors.primary)
                        .border(
                            1.dp,
                            colors.onPrimary.copy(alpha = 0.35f),
                            RoundedCornerShape(topStart = 17.dp, bottomStart = 17.dp),
                        )
                        .clickable { open = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            tasks.size.toString(),
                            style = MiuixTheme.textStyles.title4,
                            color = colors.onPrimary,
                        )
                        Text(
                            "任务",
                            style = MiuixTheme.textStyles.footnote2,
                            color = colors.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: BackgroundTask, onOpen: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                task.title,
                style = MiuixTheme.textStyles.body1,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "打开",
                style = MiuixTheme.textStyles.footnote2,
                color = colors.primary,
            )
        }
        if (task.detail.isNotBlank()) {
            Text(
                task.detail,
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurface.copy(alpha = 0.72f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (task.progress != null) {
            LinearProgressIndicator(
                progress = task.progress,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (task.cancel != null) {
            SecondaryButton(
                onClick = { BackgroundTaskRegistry.cancel(task.id) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("停止") }
        }
    }
}
