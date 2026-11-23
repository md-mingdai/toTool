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
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
    val orientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
    val detail: String? = null,
) {
    val option: OrientationOption? get() = OrientationOption.fromValue(orientation)
}

object OrientationOverlayController {
    const val ACTION_START = "com.mingdai.toTool.orientation.START"
    const val ACTION_UPDATE = "com.mingdai.toTool.orientation.UPDATE"
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

/** 方向控制服务：只用一扇不可见悬浮窗向 WindowManager 申请方向，不再显示任何悬浮控件。 */
class OrientationOverlayService : Service() {
    companion object {
        private const val CHANNEL_ID = "orientation_overlay"
        private const val NOTIFICATION_ID = 4301
    }

    private var windowManager: WindowManager? = null
    private var overlay: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == OrientationOverlayController.ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        orientation = intent?.getIntExtra(
            OrientationOverlayController.EXTRA_ORIENTATION,
            orientation,
        ) ?: orientation
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!Settings.canDrawOverlays(this)) {
            OrientationOverlayController.publish(
                OrientationOverlayState(false, detail = "没有悬浮窗权限，无法锁定屏幕方向"),
            )
            stopEverything()
            return START_NOT_STICKY
        }
        if (!showOrUpdateOverlay()) {
            stopEverything()
            return START_NOT_STICKY
        }
        BackgroundTaskRegistry.add(
            this,
            BackgroundTask(
                id = OrientationOverlayController.TASK_ID,
                toolId = OrientationOverlayController.TOOL_ID,
                title = "全局方向",
                detail = OrientationOption.fromValue(orientation)?.label ?: "自定义方向",
                keepAlive = false,
                cancel = { stopEverything() },
            ),
        )
        OrientationOverlayController.publish(OrientationOverlayState(true, orientation))
        return START_NOT_STICKY
    }

    private fun showOrUpdateOverlay(): Boolean {
        val manager = windowManager ?: return false
        val existing = overlay
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
                overlay = view
                params = layout
                true
            } catch (e: Exception) {
                OrientationOverlayController.publish(
                    OrientationOverlayState(false, detail = "创建方向窗口失败：${e.message ?: e.javaClass.simpleName}"),
                )
                false
            }
        }
        val layout = params ?: return false
        layout.screenOrientation = orientation
        return try {
            manager.updateViewLayout(existing, layout)
            true
        } catch (e: Exception) {
            OrientationOverlayController.publish(
                OrientationOverlayState(false, detail = "更新方向窗口失败：${e.message ?: e.javaClass.simpleName}"),
            )
            false
        }
    }

    private fun removeOverlay() {
        overlay?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlay = null
        params = null
    }

    private fun stopEverything() {
        removeOverlay()
        BackgroundTaskRegistry.remove(OrientationOverlayController.TASK_ID)
        OrientationOverlayController.publish(OrientationOverlayState(false))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        removeOverlay()
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
                description = "通过不可见悬浮窗锁定系统屏幕方向"
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
            .setContentText("已锁定为$label")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", stopIntent)
            .build()
    }
}
