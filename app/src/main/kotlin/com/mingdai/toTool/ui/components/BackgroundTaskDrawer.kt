package com.mingdai.toTool.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.background.BackgroundTask
import com.mingdai.toTool.background.BackgroundTaskRegistry
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class DrawerSide { LEFT, RIGHT }

private val OffsetToVector = TwoWayConverter<Offset, AnimationVector2D>(
    convertToVector = { AnimationVector2D(it.x, it.y) },
    convertFromVector = { Offset(it.v1, it.v2) },
)

private val DrawerSnap: SpringSpec<Offset> = spring(
    dampingRatio = 0.78f,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * 主界面上的可拖动任务抽屉。
 * 位置由 [Animatable] 驱动：拖动时跟手，松手后用弹簧动画吸附到左右侧边；
 * 关闭主体后按钮回到打开前记录的纵向位置，而不是停在面板的旧位置。
 */
@Composable
fun BackgroundTaskDrawer(
    modifier: Modifier = Modifier,
    onOpenTool: (String) -> Unit = {},
) {
    val tasks by BackgroundTaskRegistry.tasks.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    var side by rememberSaveable { mutableStateOf(DrawerSide.RIGHT) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val handleWidthPx = with(density) { 34.dp.toPx() }
    val handleHeightPx = with(density) { 100.dp.toPx() }

    LaunchedEffect(tasks.isEmpty()) {
        if (tasks.isEmpty()) open = false
    }
    if (tasks.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxWidth = constraints.maxWidth.toFloat()
        val maxHeight = constraints.maxHeight.toFloat()
        val panelWidthPx = min(with(density) { 300.dp.toPx() }, maxWidth)
        var panelHeightPx by remember { mutableStateOf(0f) }
        var handleY by remember { mutableStateOf(Float.NaN) }
        var initialized by remember { mutableStateOf(false) }
        val position = remember { Animatable(Offset.Zero, OffsetToVector) }

        fun handleTargetX(targetSide: DrawerSide): Float =
            if (targetSide == DrawerSide.LEFT) 0f else (maxWidth - handleWidthPx).coerceAtLeast(0f)

        fun panelTargetX(targetSide: DrawerSide): Float =
            if (targetSide == DrawerSide.LEFT) 0f else (maxWidth - panelWidthPx).coerceAtLeast(0f)

        fun snapTo(target: Offset, animate: Boolean) {
            scope.launch {
                if (animate) position.animateTo(target, DrawerSnap) else position.snapTo(target)
            }
        }

        LaunchedEffect(maxWidth, maxHeight) {
            if (maxWidth <= 0f || maxHeight <= 0f) return@LaunchedEffect
            if (!initialized) {
                initialized = true
                val y = ((maxHeight - handleHeightPx) / 2f).coerceAtLeast(0f)
                handleY = y
                position.snapTo(Offset(handleTargetX(side), y))
            } else {
                val width = if (open) panelWidthPx else handleWidthPx
                val height = if (open) panelHeightPx.coerceAtLeast(handleHeightPx) else handleHeightPx
                position.snapTo(
                    Offset(
                        position.value.x.coerceIn(0f, (maxWidth - width).coerceAtLeast(0f)),
                        position.value.y.coerceIn(0f, (maxHeight - height).coerceAtLeast(0f)),
                    ),
                )
            }
        }

        LaunchedEffect(open, panelHeightPx, maxWidth, maxHeight) {
            if (!initialized || maxWidth <= 0f) return@LaunchedEffect
            if (open) {
                val maxY = if (panelHeightPx > 0f) {
                    (maxHeight - panelHeightPx).coerceAtLeast(0f)
                } else {
                    maxHeight
                }
                position.animateTo(
                    Offset(panelTargetX(side), position.value.y.coerceIn(0f, maxY)),
                    DrawerSnap,
                )
            } else {
                // 关闭主体时回到打开前的按钮位置，而不是面板被夹紧后的位置。
                val y = (if (handleY.isNaN()) position.value.y else handleY)
                    .coerceIn(0f, (maxHeight - handleHeightPx).coerceAtLeast(0f))
                position.animateTo(Offset(handleTargetX(side), y), DrawerSnap)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val offsetX = position.value.x.roundToInt()
            val offsetY = position.value.y.roundToInt()

            if (open) {
                val colors = MiuixTheme.colorScheme
                Card(
                    modifier = Modifier
                        .offset { IntOffset(offsetX, offsetY) }
                        .width(with(density) { panelWidthPx.toDp() })
                        .onSizeChanged { panelHeightPx = it.height.toFloat() }
                        .shadow(12.dp, RoundedCornerShape(18.dp))
                        .border(
                            1.dp,
                            colors.outline.copy(alpha = 0.55f),
                            RoundedCornerShape(18.dp),
                        )
                        .pointerInput(panelWidthPx, panelHeightPx, maxWidth, maxHeight) {
                            detectDragGestures(
                                onDragStart = { scope.launch { position.stop() } },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val height = panelHeightPx.coerceAtLeast(handleHeightPx)
                                    val next = Offset(
                                        (position.value.x + dragAmount.x)
                                            .coerceIn(0f, (maxWidth - panelWidthPx).coerceAtLeast(0f)),
                                        (position.value.y + dragAmount.y)
                                            .coerceIn(0f, (maxHeight - height).coerceAtLeast(0f)),
                                    )
                                    scope.launch { position.snapTo(next) }
                                },
                                onDragEnd = {
                                    val newSide = if (position.value.x + panelWidthPx / 2f < maxWidth / 2f) {
                                        DrawerSide.LEFT
                                    } else {
                                        DrawerSide.RIGHT
                                    }
                                    side = newSide
                                    val maxY = (maxHeight - panelHeightPx.coerceAtLeast(handleHeightPx))
                                        .coerceAtLeast(0f)
                                    snapTo(
                                        Offset(
                                            panelTargetX(newSide),
                                            position.value.y.coerceIn(0f, maxY),
                                        ),
                                        animate = true,
                                    )
                                },
                                onDragCancel = {
                                    snapTo(
                                        Offset(panelTargetX(side), position.value.y),
                                        animate = true,
                                    )
                                },
                            )
                        },
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
            } else {
                val colors = MiuixTheme.colorScheme
                val shape = if (side == DrawerSide.LEFT) {
                    RoundedCornerShape(topEnd = 17.dp, bottomEnd = 17.dp)
                } else {
                    RoundedCornerShape(topStart = 17.dp, bottomStart = 17.dp)
                }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(offsetX, offsetY) }
                        .width(34.dp)
                        .height(100.dp)
                        .shadow(10.dp, shape)
                        .clip(shape)
                        .background(colors.primary)
                        .border(1.dp, colors.onPrimary.copy(alpha = 0.35f), shape)
                        .pointerInput(handleWidthPx, handleHeightPx, maxWidth, maxHeight) {
                            detectDragGestures(
                                onDragStart = { scope.launch { position.stop() } },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val next = Offset(
                                        (position.value.x + dragAmount.x)
                                            .coerceIn(0f, (maxWidth - handleWidthPx).coerceAtLeast(0f)),
                                        (position.value.y + dragAmount.y)
                                            .coerceIn(0f, (maxHeight - handleHeightPx).coerceAtLeast(0f)),
                                    )
                                    scope.launch { position.snapTo(next) }
                                },
                                onDragEnd = {
                                    val newSide = if (position.value.x + handleWidthPx / 2f < maxWidth / 2f) {
                                        DrawerSide.LEFT
                                    } else {
                                        DrawerSide.RIGHT
                                    }
                                    side = newSide
                                    handleY = position.value.y
                                    val y = position.value.y.coerceIn(
                                        0f,
                                        (maxHeight - handleHeightPx).coerceAtLeast(0f),
                                    )
                                    snapTo(Offset(handleTargetX(newSide), y), animate = true)
                                },
                                onDragCancel = {
                                    snapTo(Offset(handleTargetX(side), position.value.y), animate = true)
                                },
                            )
                        }
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
