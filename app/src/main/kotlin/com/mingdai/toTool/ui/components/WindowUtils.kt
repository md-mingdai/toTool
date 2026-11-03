package com.mingdai.toTool.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mingdai.toTool.data.LocalBehaviorState

/** 从任意 Context 里找到所属的 Activity（Compose 里的 LocalContext 常常是 ContextWrapper）。 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 隐藏 / 显示系统栏（状态栏 + 导航栏）。
 * 隐藏后从屏幕边缘滑一下会临时露出，几秒后自动收回，不会卡住用户。
 */
fun Activity.setSystemBarsHidden(hidden: Boolean) {
    val decor = window.decorView
    val controller = WindowCompat.getInsetsController(window, decor)
    if (hidden) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * 在当前页面存活期间保持屏幕常亮；页面离开时自动取消。
 * 时钟、坏点检测这类“盯着看”的工具要用，否则 30 秒后屏幕就息屏了。
 */
@Composable
fun KeepScreenOn(enabled: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val old = view.keepScreenOn
        if (enabled) view.keepScreenOn = true
        onDispose { view.keepScreenOn = old }
    }
}

/* ───────────── 触感反馈 ─────────────
 * 所有触感都走这里：先看设置里的“触感反馈”总开关，再用系统的 View 触感常量。
 * 常量由系统映射成对应马达效果，不需要 VIBRATE 权限，也会遵循用户在系统里关掉“触摸振动”的选择。
 */

/** 长按等“确认”类动作的触感。 */
fun View.hapticConfirm() {
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

/** 越过阈值（例如列表拉到边缘）的短促触感。API 34+ 有专门的“手势阈值”效果，低版本退回时钟滴答。 */
fun View.hapticThreshold() {
    val type = if (Build.VERSION.SDK_INT >= 34) {
        HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }
    performHapticFeedback(type)
}

/** 取得一个会遵循“触感反馈”总开关的触发器。 */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val behavior = LocalBehaviorState.current
    return androidx.compose.runtime.remember(view, behavior) { Haptics(view) { behavior.haptics } }
}

class Haptics(private val view: View, private val enabled: () -> Boolean) {
    fun confirm() { if (enabled()) view.hapticConfirm() }
    fun threshold() { if (enabled()) view.hapticThreshold() }
}
