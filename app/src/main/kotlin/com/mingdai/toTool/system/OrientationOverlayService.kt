package com.mingdai.toTool.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mingdai.toTool.MainActivity
import com.mingdai.toTool.R
import com.mingdai.toTool.background.BackgroundTask
import com.mingdai.toTool.background.BackgroundTaskRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OrientationOption(
    val label: String,
    val subtitle: String,
    val value: Int,
) {
    LOCK_CURRENT("关闭自动旋转", "系统默认 · 锁定当前方向", ActivityInfo.SCREEN_ORIENTATION_LOCKED),
    FOLLOW_SYSTEM("开启", "系统默认", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
    FORCE_AUTO("强制自动旋转", "覆盖系统", ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR),
    FORCE_PORTRAIT("强制竖屏", "覆盖系统", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    FORCE_LANDSCAPE("强制横屏", "覆盖系统", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    FORCE_REVERSE_PORTRAIT("强制反向竖屏", "覆盖系统", ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT),
    FORCE_REVERSE_LANDSCAPE("强制反向横屏", "覆盖系统", ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
    FORCE_SENSOR_PORTRAIT("强制传感器竖屏", "覆盖系统", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
    FORCE_SENSOR_LANDSCAPE("强制传感器横屏", "覆盖系统", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
    FORCE_SENSOR("全局强制传感器", "覆盖系统", ActivityInfo.SCREEN_ORIENTATION_SENSOR);

    companion object {
        fun fromValue(value: Int): OrientationOption? = entries.firstOrNull { it.value == value }
    }
}

data class OrientationOverlayState(
    val running: Boolean,
    val active: Boolean = false,
    val orientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
    val detail: String? = null,
) {
    val option: OrientationOption? get() = OrientationOption.fromValue(orientation)
}

object OrientationOverlayController {
    const val ACTION_START = "com.mingdai.toTool.orientation.START"
    const val ACTION_UPDATE = "com.mingdai.toTool.orientation.UPDATE"
    const val ACTION_TOGGLE = "com.mingdai.toTool.orientation.TOGGLE"
    const val ACTION_STOP = "com.mingdai.toTool.orientation.STOP"
    const val EXTRA_ORIENTATION = "orientation"
    const val TOOL_ID = "dev_orientation"
    const val TASK_ID = "orientation"

    private val _state = MutableStateFlow(OrientationOverlayState(false))
    val state: StateFlow<OrientationOverlayState> = _state.asStateFlow()

    internal fun publish(state: OrientationOverlayState) {
        _state.value = state
    }

    fun start(context: Context, option: OrientationOption) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, OrientationOverlayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ORIENTATION, option.value),
        )
    }

    fun update(context: Context, option: OrientationOption) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, OrientationOverlayService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_ORIENTATION, option.value),
        )
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, OrientationOverlayService::class.java))
        BackgroundTaskRegistry.remove(TASK_ID)
        publish(OrientationOverlayState(false))
    }
}

/**
 * 方向控制服务：一个不可见的全屏窗口负责向 WindowManager 申请方向，
 * 另外只在右下角保留一个圆形悬浮开关，点按即可暂停/恢复方向锁定。
 */
class OrientationOverlayService : Service() {
    companion object {
        private const val CHANNEL_ID = "orientation_overlay"
        private const val NOTIFICATION_ID = 4301
    }

    private var windowManager: WindowManager? = null
    private var orientationOverlay: View? = null
    private var orientationParams: WindowManager.LayoutParams? = null
    private var toggleButton: TextView? = null
    private var toggleParams: WindowManager.LayoutParams? = null
    private var orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var active = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            OrientationOverlayController.ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            OrientationOverlayController.ACTION_TOGGLE -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                if (!Settings.canDrawOverlays(this)) {
                    OrientationOverlayController.publish(
                        OrientationOverlayState(false, detail = "没有悬浮窗权限，无法锁定屏幕方向"),
                    )
                    stopEverything()
                    return START_NOT_STICKY
                }
                active = !active
                applyActiveState()
                return START_NOT_STICKY
            }
            else -> {
                orientation = intent?.getIntExtra(
                    OrientationOverlayController.EXTRA_ORIENTATION,
                    orientation,
                ) ?: orientation
                active = true
                startForeground(NOTIFICATION_ID, buildNotification())
                if (!Settings.canDrawOverlays(this)) {
                    OrientationOverlayController.publish(
                        OrientationOverlayState(false, detail = "没有悬浮窗权限，无法锁定屏幕方向"),
                    )
                    stopEverything()
                    return START_NOT_STICKY
                }
                if (!ensureToggleButton() || !showOrUpdateOrientationOverlay()) {
                    stopEverything()
                    return START_NOT_STICKY
                }
                BackgroundTaskRegistry.add(
                    this,
                    BackgroundTask(
                        id = OrientationOverlayController.TASK_ID,
                        toolId = OrientationOverlayController.TOOL_ID,
                        title = "全局方向",
                        detail = detailText(),
                        keepAlive = false,
                        cancel = { stopEverything() },
                    ),
                    keepAlive = false,
                )
                publishState()
                return START_NOT_STICKY
            }
        }
    }

    private fun applyActiveState() {
        if (active) {
            if (!showOrUpdateOrientationOverlay()) {
                stopEverything()
                return
            }
        } else {
            removeOrientationOverlay()
        }
        updateToggleButton()
        updateNotification()
        publishState()
    }

    private fun publishState() {
        OrientationOverlayController.publish(
            OrientationOverlayState(
                running = true,
                active = active,
                orientation = orientation,
                detail = null,
            ),
        )
        BackgroundTaskRegistry.update(
            OrientationOverlayController.TASK_ID,
            detailText(),
            null,
        )
    }

    private fun detailText(): String {
        val label = OrientationOption.fromValue(orientation)?.label ?: "自定义方向"
        return if (active) "$label · 已启用" else "$label · 已暂停"
    }

    private fun ensureToggleButton(): Boolean {
        val manager = windowManager ?: return false
        if (toggleButton != null) return true
        val size = (52 * resources.displayMetrics.density).toInt().coerceAtLeast(40)
        val button = TextView(this).apply {
            text = "↻"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            elevation = 8 * resources.displayMetrics.density
            setOnClickListener { toggleActive() }
        }
        val layout = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = (24 * resources.displayMetrics.density).toInt()
            y = (104 * resources.displayMetrics.density).toInt()
        }
        return try {
            manager.addView(button, layout)
            toggleButton = button
            toggleParams = layout
            updateToggleButton()
            true
        } catch (e: Exception) {
            OrientationOverlayController.publish(
                OrientationOverlayState(false, detail = "创建悬浮开关失败：${e.message ?: e.javaClass.simpleName}"),
            )
            false
        }
    }

    private fun updateToggleButton() {
        val button = toggleButton ?: return
        val color = if (active) Color.rgb(46, 125, 50) else Color.argb(220, 70, 70, 70)
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.argb(90, 255, 255, 255))
        }
        button.alpha = if (active) 1f else 0.8f
    }

    private fun toggleActive() {
        active = !active
        applyActiveState()
    }

    private fun showOrUpdateOrientationOverlay(): Boolean {
        val manager = windowManager ?: return false
        val existing = orientationOverlay
        if (existing == null) {
            val view = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
            val layout = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenOrientation = orientation
            }
            return try {
                manager.addView(view, layout)
                orientationOverlay = view
                orientationParams = layout
                true
            } catch (e: Exception) {
                active = false
                OrientationOverlayController.publish(
                    OrientationOverlayState(false, detail = "创建方向窗口失败：${e.message ?: e.javaClass.simpleName}"),
                )
                false
            }
        }
        val layout = orientationParams ?: return false
        layout.screenOrientation = orientation
        return try {
            manager.updateViewLayout(existing, layout)
            true
        } catch (e: Exception) {
            active = false
            OrientationOverlayController.publish(
                OrientationOverlayState(false, detail = "更新方向窗口失败：${e.message ?: e.javaClass.simpleName}"),
            )
            false
        }
    }

    private fun removeOrientationOverlay() {
        orientationOverlay?.let { view -> runCatching { windowManager?.removeView(view) } }
        orientationOverlay = null
        orientationParams = null
    }

    private fun stopEverything() {
        removeOrientationOverlay()
        toggleButton?.let { button -> runCatching { windowManager?.removeView(button) } }
        toggleButton = null
        toggleParams = null
        BackgroundTaskRegistry.remove(OrientationOverlayController.TASK_ID)
        OrientationOverlayController.publish(OrientationOverlayState(false))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        removeOrientationOverlay()
        toggleButton?.let { button -> runCatching { windowManager?.removeView(button) } }
        toggleButton = null
        BackgroundTaskRegistry.remove(OrientationOverlayController.TASK_ID)
        OrientationOverlayController.publish(OrientationOverlayState(false))
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "屏幕方向控制",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "通过不可见悬浮窗切换全局屏幕方向"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val label = OrientationOption.fromValue(orientation)?.label ?: "自定义方向"
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OrientationOverlayService::class.java)
                .setAction(OrientationOverlayController.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TOOL_ID, OrientationOverlayController.TOOL_ID)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("屏幕方向控制")
            .setContentText(if (active) "已锁定为$label；点右下角悬浮按钮可暂停" else "已暂停；点右下角悬浮按钮可恢复$label")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", stopIntent)
            .build()
    }
}
