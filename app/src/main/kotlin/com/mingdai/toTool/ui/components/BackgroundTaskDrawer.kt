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
import androidx.compose.ui.platform.LocalDensity
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

private enum class DrawerSide { LEFT, RIGHT }

/** 主界面上的可拖动任务抽屉；松手后自动吸附到最近的左右屏幕边缘。 */
@Composable
fun BackgroundTaskDrawer(
    modifier: Modifier = Modifier,
    onOpenTool: (String) -> Unit = {},
) {
    val tasks by BackgroundTaskRegistry.tasks.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    var side by rememberSaveable { mutableStateOf(DrawerSide.RIGHT) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val handleWidthPx = with(density) { 34.dp.toPx() }
    val handleHeightPx = with(density) { 100.dp.toPx() }

    LaunchedEffect(tasks.isEmpty()) {
        if (tasks.isEmpty()) open = false
    }
    if (tasks.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxWidth = constraints.maxWidth.toFloat()
        val maxHeight = constraints.maxHeight.toFloat()
        var position by remember(maxWidth, maxHeight) {
            mutableStateOf(
                Offset(
                    x = (maxWidth - handleWidthPx).coerceAtLeast(0f),
                    y = ((maxHeight - handleHeightPx) / 2f).coerceAtLeast(0f),
                ),
            )
        }

        LaunchedEffect(maxWidth, maxHeight, panelSize, side) {
            val width = panelSize.width.toFloat().takeIf { it > 0f } ?: handleWidthPx
            val height = panelSize.height.toFloat().takeIf { it > 0f } ?: handleHeightPx
            val x = if (side == DrawerSide.LEFT) 0f else (maxWidth - width).coerceAtLeast(0f)
            val y = position.y.coerceIn(0f, (maxHeight - height).coerceAtLeast(0f))
            position = Offset(x, y)
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .onSizeChanged { panelSize = it }
                .pointerInput(maxWidth, maxHeight, panelSize) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val width = panelSize.width.toFloat().takeIf { it > 0f } ?: handleWidthPx
                            val height = panelSize.height.toFloat().takeIf { it > 0f } ?: handleHeightPx
                            position = Offset(
                                (position.x + dragAmount.x)
                                    .coerceIn(0f, (maxWidth - width).coerceAtLeast(0f)),
                                (position.y + dragAmount.y)
                                    .coerceIn(0f, (maxHeight - height).coerceAtLeast(0f)),
                            )
                        },
                        onDragEnd = {
                            val width = panelSize.width.toFloat().takeIf { it > 0f } ?: handleWidthPx
                            val height = panelSize.height.toFloat().takeIf { it > 0f } ?: handleHeightPx
                            val newSide = if (position.x + width / 2f < maxWidth / 2f) {
                                DrawerSide.LEFT
                            } else {
                                DrawerSide.RIGHT
                            }
                            side = newSide
                            val x = if (newSide == DrawerSide.LEFT) 0f else (maxWidth - width).coerceAtLeast(0f)
                            position = Offset(x, position.y.coerceIn(0f, (maxHeight - height).coerceAtLeast(0f)))
                        },
                    )
                },
        ) {
            AnimatedVisibility(
                visible = open,
                enter = slideInHorizontally(initialOffsetX = { if (side == DrawerSide.LEFT) -it else it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { if (side == DrawerSide.LEFT) -it else it }) + fadeOut(),
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
                val shape = if (side == DrawerSide.LEFT) {
                    RoundedCornerShape(topEnd = 17.dp, bottomEnd = 17.dp)
                } else {
                    RoundedCornerShape(topStart = 17.dp, bottomStart = 17.dp)
                }
                Box(
                    modifier = Modifier
                        .width(34.dp)
                        .height(100.dp)
                        .shadow(10.dp, shape)
                        .clip(shape)
                        .background(colors.primary)
                        .border(1.dp, colors.onPrimary.copy(alpha = 0.35f), shape)
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
