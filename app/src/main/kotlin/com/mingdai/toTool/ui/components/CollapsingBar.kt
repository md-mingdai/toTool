package com.mingdai.toTool.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt

/**
 * 跟随列表滚动“上浮收起 / 下拉出现”的顶栏组件（例如搜索栏）的状态。
 *
 * 用法：
 * ```
 * val bar = remember { CollapsingBarState() }
 * Column(Modifier.nestedScroll(bar.connection)) {
 *     Box(Modifier.collapsingBar(bar)) { SearchField() }
 *     LazyColumn { ... }
 * }
 * ```
 * 向上滑（看更多内容）时先把它推出屏幕，再滚动列表；往回滑时它立刻重新出现。
 * 松手后会自动吸附到“完全显示 / 完全收起”两个位置之一。
 */
@Stable
class CollapsingBarState {
    /** 组件完整高度（px），由 [collapsingBar] 在测量时写入。 */
    var heightPx by mutableFloatStateOf(0f)
        internal set

    /** 当前位移：0 = 完全显示，-heightPx = 完全收起。 */
    var offsetPx by mutableFloatStateOf(0f)

    /** 是否参与滚动联动。当前页面没有这个组件时必须关掉，否则会白白吃掉列表的滚动量。 */
    var enabled: Boolean = true

    /** 0 = 完全显示，1 = 完全收起。 */
    val collapsedFraction: Float
        get() = if (heightPx <= 0f) 0f else (-offsetPx / heightPx).coerceIn(0f, 1f)

    fun reset() {
        offsetPx = 0f
    }

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!enabled || heightPx <= 0f || available.y == 0f) return Offset.Zero
            val before = offsetPx
            offsetPx = (before + available.y).coerceIn(-heightPx, 0f)
            // 组件移动时吃掉这部分滚动量：此时它“让出”的空间让内容整体平移，列表本身先不动，
            // 这样内容始终跟手 1:1，而不是组件移动 + 列表滚动叠加成两倍速度。
            val consumedY = offsetPx - before
            return if (consumedY != 0f) Offset(0f, consumedY) else Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            settle()
            return Velocity.Zero
        }
    }

    private suspend fun settle() {
        if (heightPx <= 0f) return
        val target = if (offsetPx > -heightPx / 2f) 0f else -heightPx
        if (offsetPx != target) {
            animate(
                initialValue = offsetPx,
                targetValue = target,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            ) { value, _ -> offsetPx = value }
        }
    }
}

/**
 * 让一个组件按 [state] 收起：布局高度随位移缩小（下面的内容随之上移），
 * 同时向上平移并淡出，超出自身范围的部分被裁掉，不会盖到顶部标题栏上。
 */
fun Modifier.collapsingBar(state: CollapsingBarState): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val full = placeable.height
        val shift = state.offsetPx.roundToInt().coerceIn(-full, 0)
        layout(placeable.width, (full + shift).coerceAtLeast(0)) {
            placeable.placeRelative(0, shift)
        }
    }
    .onSizeChanged { state.heightPx = it.height.toFloat() }
    .graphicsLayer { alpha = 1f - state.collapsedFraction }
