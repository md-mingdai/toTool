package com.mingdai.toTool.weaknet

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 独立于 Activity 的悬浮窗：默认只显示目标应用的 1:1 图标，拖动可移动，点击才展开；
 * 展开后显示状态、速率和连接数，并可暂停限速或停止服务。
 */
internal class WeakNetOverlay(
    private val context: Context,
    private val onTogglePause: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val accent = Color.rgb(199, 244, 100)
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var iconContainer: FrameLayout? = null
    private var iconView: ImageView? = null
    private var statusDot: View? = null
    private var panel: LinearLayout? = null
    private var statusText: TextView? = null
    private var targetText: TextView? = null
    private var speedText: TextView? = null
    private var pauseButton: TextView? = null
    private var expanded = false
    private var lastState: WeakNetState? = null
    private var ballX = 0
    private var ballY = 0
    private var snapAnimator: ValueAnimator? = null

    fun show() {
        if (root != null || !Settings.canDrawOverlays(context)) return
        val frame = FrameLayout(context)

        val bubble = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(15).toFloat()
                setColor(Color.argb(247, 255, 255, 255))
                setStroke(dp(1), Color.argb(90, 199, 244, 100))
            }
            contentDescription = "弱网助手，点击展开"
            isClickable = true
            setOnTouchListener(dragOrTap {
                expand()
            })
        }
        iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setImageDrawable(currentAppIcon())
        }
        bubble.addView(iconView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        statusDot = View(context).apply {
            background = dot(Color.argb(215, 120, 130, 110))
        }
        bubble.addView(statusDot, FrameLayout.LayoutParams(dp(11), dp(11), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(4)
            bottomMargin = dp(4)
        })
        frame.addView(bubble, FrameLayout.LayoutParams(dp(52), dp(52)))
        iconContainer = bubble

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(240, 18, 23, 15))
                setStroke(dp(1), Color.argb(80, 199, 244, 100))
            }
            visibility = View.GONE
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "弱网助手"
            setTextColor(accent)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }
        title.setOnTouchListener(dragOnly())
        val collapse = smallButton("−") { collapse() }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(collapse)
        body.addView(header)

        statusText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, dp(3), 0, 0)
        }
        targetText = TextView(context).apply {
            setTextColor(Color.argb(200, 238, 242, 230))
            textSize = 11f
            setPadding(0, dp(3), 0, 0)
            maxLines = 2
        }
        speedText = TextView(context).apply {
            setTextColor(Color.argb(210, 199, 244, 100))
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
            setPadding(0, dp(5), 0, 0)
        }
        body.addView(statusText)
        body.addView(targetText)
        body.addView(speedText)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(9), 0, 0)
        }
        pauseButton = actionButton("暂停限制", accent) { onTogglePause() }
        val stop = actionButton("停止", Color.rgb(255, 152, 0)) { onStop() }
        controls.addView(pauseButton, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(8) })
        controls.addView(stop, LinearLayout.LayoutParams(0, dp(34), 1f))
        body.addView(controls)
        frame.addView(body, FrameLayout.LayoutParams(dp(246), FrameLayout.LayoutParams.WRAP_CONTENT))
        panel = body

        val lp = WindowManager.LayoutParams(
            dp(52),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val margin = dp(8)
            ballX = (context.resources.displayMetrics.widthPixels - dp(52) - margin).coerceAtLeast(margin)
            ballY = dp(130)
            x = ballX
            y = ballY
        }
        try {
            windowManager.addView(frame, lp)
            root = frame
            params = lp
            expanded = false
        } catch (e: Exception) {
            // 权限被撤销或窗口服务拒绝
        }
    }

    fun refresh(state: WeakNetState) {
        val current = root ?: return
        lastState = state
        val dotColor = when {
            state.error != null -> Color.rgb(220, 80, 70)
            state.paused -> Color.rgb(228, 163, 59)
            state.running -> accent
            else -> Color.argb(215, 120, 130, 110)
        }
        statusDot?.background = dot(dotColor)
        iconContainer?.alpha = if (state.running) 1f else 0.7f
        if (!expanded) return
        current.alpha = 1f
        statusText?.text = when {
            state.error != null -> "异常：${state.error}"
            state.paused -> "已暂停（直连）"
            state.running -> "运行中"
            else -> "已停止"
        }
        targetText?.text = state.config.targetLabel
        speedText?.text = String.format(
            Locale.US,
            "↓ %s  ↑ %s · %d 连接",
            speedText(state.rxSpeed),
            speedText(state.txSpeed),
            state.connections,
        )
        pauseButton?.text = if (state.paused) "继续限制" else "暂停限制"
    }

    fun hide() {
        val current = root ?: return
        runCatching { windowManager.removeView(current) }
        root = null
        params = null
        iconContainer = null
        iconView = null
        statusDot = null
        panel = null
        statusText = null
        targetText = null
        speedText = null
        pauseButton = null
        expanded = false
        lastState = null
        cancelSnap()
        ballX = 0
        ballY = 0
    }

    private fun expand() {
        if (expanded) return
        cancelSnap()
        val current = params ?: return
        ballX = current.x
        ballY = current.y
        val width = dp(246)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        current.width = width
        current.x = if (ballX + dp(26) < screenWidth / 2) {
            dp(8)
        } else {
            (screenWidth - width - dp(8)).coerceAtLeast(dp(8))
        }
        current.y = ballY.coerceIn(dp(8), (screenHeight - dp(72)).coerceAtLeast(dp(8)))
        iconContainer?.visibility = View.GONE
        panel?.visibility = View.VISIBLE
        expanded = true
        runCatching { windowManager.updateViewLayout(root ?: return, current) }
        lastState?.let { refresh(it) }
    }

    private fun collapse() {
        if (!expanded) return
        cancelSnap()
        val current = params ?: return
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        current.width = dp(52)
        current.x = ballX.coerceIn(dp(8), (screenWidth - dp(52) - dp(8)).coerceAtLeast(dp(8)))
        current.y = ballY.coerceIn(dp(8), (screenHeight - dp(72)).coerceAtLeast(dp(8)))
        panel?.visibility = View.GONE
        iconContainer?.visibility = View.VISIBLE
        expanded = false
        runCatching { windowManager.updateViewLayout(root ?: return, current) }
        // 从展开前悬浮球的位置出现，然后再吸到最近的侧边。
        snapBallToSide(true)
    }

    /** 拖动结束后让悬浮球吸附到最近的左右侧边。 */
    private fun snapBallToSide(animate: Boolean) {
        val current = params ?: return
        if (expanded) return
        val width = dp(52)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val margin = dp(8)
        val targetX = if (current.x + width / 2 < screenWidth / 2) {
            margin
        } else {
            (screenWidth - width - margin).coerceAtLeast(margin)
        }
        val targetY = current.y.coerceIn(dp(8), (screenHeight - dp(72)).coerceAtLeast(dp(8)))
        if (!animate || current.x == targetX) {
            current.x = targetX
            current.y = targetY
            ballX = targetX
            ballY = targetY
            runCatching { windowManager.updateViewLayout(root ?: return, current) }
            return
        }
        cancelSnap()
        val animator = ValueAnimator.ofInt(current.x, targetX).setDuration(180)
        animator.addUpdateListener {
            current.x = it.animatedValue as Int
            current.y = targetY
            runCatching { windowManager.updateViewLayout(root ?: return@addUpdateListener, current) }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                current.x = targetX
                current.y = targetY
                ballX = targetX
                ballY = targetY
                snapAnimator = null
                runCatching { windowManager.updateViewLayout(root ?: return, current) }
            }
        })
        snapAnimator = animator
        animator.start()
    }

    private fun cancelSnap() {
        // removeAllListeners 防止 cancel() 触发 onAnimationEnd 把球瞬间拉到目标侧边。
        snapAnimator?.apply {
            removeAllListeners()
            cancel()
        }
        snapAnimator = null
    }

    /** 旋转屏幕后把折叠图标或展开面板拉回可见区域。 */
    fun refreshPosition() {
        val current = root ?: return
        val currentParams = params ?: return
        cancelSnap()
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        if (expanded) {
            val width = dp(246)
            currentParams.width = width
            currentParams.x = currentParams.x.coerceIn(dp(8), (screenWidth - width - dp(8)).coerceAtLeast(dp(8)))
            currentParams.y = currentParams.y.coerceIn(dp(8), (screenHeight - dp(72)).coerceAtLeast(dp(8)))
        } else {
            currentParams.width = dp(52)
            currentParams.x = ballX
            currentParams.y = ballY
            currentParams.x = currentParams.x.coerceIn(dp(8), (screenWidth - dp(52) - dp(8)).coerceAtLeast(dp(8)))
            currentParams.y = currentParams.y.coerceIn(dp(8), (screenHeight - dp(72)).coerceAtLeast(dp(8)))
            ballX = currentParams.x
            ballY = currentParams.y
        }
        runCatching { windowManager.updateViewLayout(current, currentParams) }
        if (!expanded) snapBallToSide(false)
    }

    /** 图标：轻点是展开，按住拖动则移动窗口。 */
    private fun dragOrTap(onTap: () -> Unit): View.OnTouchListener {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        return View.OnTouchListener { _, event ->
            val current = params ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelSnap()
                    downX = event.rawX
                    downY = event.rawY
                    startX = current.x
                    startY = current.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        current.x = startX + dx.roundToInt()
                        current.y = startY + dy.roundToInt()
                        runCatching { windowManager.updateViewLayout(root ?: return@OnTouchListener true, current) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        ballX = current.x
                        ballY = current.y
                        snapBallToSide(true)
                    } else {
                        onTap()
                    }
                }
            }
            true
        }
    }

    /** 展开后的标题栏拖动。 */
    private fun dragOnly(): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        return View.OnTouchListener { _, event ->
            val current = params ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = current.x
                    startY = current.y
                }
                MotionEvent.ACTION_MOVE -> {
                    current.x = startX + (event.rawX - downX).roundToInt()
                    current.y = startY + (event.rawY - downY).roundToInt()
                    runCatching { windowManager.updateViewLayout(root ?: return@OnTouchListener true, current) }
                }
            }
            true
        }
    }

    private fun currentAppIcon(): Drawable? =
        runCatching { context.applicationInfo.loadIcon(context.packageManager) }.getOrNull()

    private fun smallButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(Color.argb(190, 238, 242, 230))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(4), dp(2))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(color)
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.argb(38, 199, 244, 100))
                setStroke(dp(1), Color.argb(85, 199, 244, 100))
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun dot(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), Color.argb(90, 18, 23, 15))
    }

    private fun speedText(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1048576.0)
        bytesPerSecond >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0)
        else -> "$bytesPerSecond B/s"
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
