package com.mingdai.toTool.weaknet

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** 独立于 Activity 的悬浮窗：显示状态、速率和连接数，并可暂停限速或停止服务。 */
internal class WeakNetOverlay(
    private val context: Context,
    private val onTogglePause: () -> Unit,
    private val onStop: () -> Unit,
    private val onHide: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var statusText: TextView? = null
    private var targetText: TextView? = null
    private var speedText: TextView? = null
    private var pauseButton: TextView? = null

    fun show() {
        if (root != null || !Settings.canDrawOverlays(context)) return
        val accent = Color.rgb(199, 244, 100)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(238, 18, 23, 15))
                setStroke(dp(1), Color.argb(70, 199, 244, 100))
            }
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
        val close = TextView(context).apply {
            text = "×"
            setTextColor(Color.argb(190, 238, 242, 230))
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(4), dp(2))
            isClickable = true
            setOnClickListener { onHide() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close)
        layout.addView(header)

        statusText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
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
        layout.addView(statusText)
        layout.addView(targetText)
        layout.addView(speedText)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        pauseButton = actionButton("暂停限制", accent) { onTogglePause() }
        val stop = actionButton("停止", Color.rgb(255, 152, 0)) { onStop() }
        controls.addView(pauseButton, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(8) })
        controls.addView(stop, LinearLayout.LayoutParams(0, dp(34), 1f))
        layout.addView(controls)

        val lp = WindowManager.LayoutParams(
            dp(246),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = context.resources.displayMetrics.widthPixels - dp(262)
            y = dp(130)
        }
        val drag = object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val current = params ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = current.x
                        startY = current.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        current.x = (startX + event.rawX - downX).roundToInt()
                        current.y = (startY + event.rawY - downY).roundToInt()
                        runCatching { windowManager.updateViewLayout(layout, current) }
                    }
                }
                return false
            }
        }
        header.setOnTouchListener(drag)
        try {
            windowManager.addView(layout, lp)
            root = layout
            params = lp
        } catch (e: Exception) {
            // 权限被撤销或窗口服务拒绝
        }
    }

    fun refresh(state: WeakNetState) {
        val current = root ?: return
        val status = when {
            state.error != null -> "异常：${state.error}"
            state.paused -> "已暂停限速"
            state.running -> "运行中"
            else -> "已停止"
        }
        statusText?.text = status
        targetText?.text = state.config.targetLabel
        speedText?.text = String.format(
            Locale.US,
            "↓ %s  ↑ %s · %d 连接",
            speedText(state.rxSpeed),
            speedText(state.txSpeed),
            state.connections,
        )
        pauseButton?.text = if (state.paused) "继续限制" else "暂停限制"
        if (!state.running && state.error == null) {
            current.alpha = 0.72f
        } else {
            current.alpha = 1f
        }
    }

    fun hide() {
        val current = root ?: return
        runCatching { windowManager.removeView(current) }
        root = null
        params = null
        statusText = null
        targetText = null
        speedText = null
        pauseButton = null
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

    private fun speedText(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1048576.0)
        bytesPerSecond >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0)
        else -> "$bytesPerSecond B/s"
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
