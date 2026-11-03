package com.mingdai.toTool.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 列表被“拉过边缘”一定程度时的触感反馈：
 *  - 在顶部继续下拉、或在底部继续上推，累计超过 [thresholdPx] 时触发一次；
 *  - 快速甩动撞到边缘（剩余速度超过 [flingVelocity]）时也触发一次；
 *  - 一次手势最多触发一次，手指离开后重新计数。
 *
 * 必须挂在最外层的 `nestedScroll` 上（即写在其它 nestedScroll 之前）：
 * 列表自己、折叠的标题栏、搜索栏都会先消费滚动量，到这里的 `available` 才是
 * “真正滚不动、只剩被橡皮筋 / 拉伸效果吃掉”的那部分，所以不会在普通滚动时误触发。
 */
class EdgeHapticsConnection(
    private val thresholdPx: Float,
    private val flingVelocity: Float,
    private val onTrigger: () -> Unit,
) : NestedScrollConnection {

    /** 不参与时（例如当前页面没有列表）直接放行，什么都不记。 */
    var enabled: Boolean = true

    private var pulled = 0f // 本次手势累计的“滚不动”的距离，向下拉为正，向上推为负
    private var fired = false

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!enabled || source != NestedScrollSource.UserInput) return Offset.Zero
        val dy = available.y
        if (dy == 0f) return Offset.Zero
        // 拉到一半又反向推：从头计数，反方向的边缘可以再触发一次
        if (pulled != 0f && (pulled > 0f) != (dy > 0f)) {
            pulled = 0f
            fired = false
        }
        pulled += dy
        if (!fired && abs(pulled) >= thresholdPx) {
            fired = true
            onTrigger()
        }
        return Offset.Zero // 不消费：拉伸 / 回弹效果照常播放
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (enabled && !fired && abs(available.y) >= flingVelocity) onTrigger()
        pulled = 0f
        fired = false
        return Velocity.Zero
    }
}

/**
 * 创建并记住一个 [EdgeHapticsConnection]，触感走 [rememberHaptics]（遵循设置里的总开关）。
 * 用法：`Modifier.nestedScroll(rememberEdgeHaptics())`，并且让它排在其它 nestedScroll 之前。
 */
@Composable
fun rememberEdgeHaptics(enabled: Boolean = true): NestedScrollConnection {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val connection = remember(haptics, density) {
        EdgeHapticsConnection(
            thresholdPx = with(density) { 48.dp.toPx() },
            flingVelocity = with(density) { 600.dp.toPx() }, // 约 600dp/s：轻轻滑到底不算，用力一甩才算
            onTrigger = { haptics.threshold() },
        )
    }
    SideEffect { connection.enabled = enabled }
    return connection
}
