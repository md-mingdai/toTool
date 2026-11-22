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
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.mingdai.toTool.MainActivity
import com.mingdai.toTool.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OrientationOption(val label: String, val value: Int) {
    PORTRAIT("竖屏", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    REVERSE_PORTRAIT("反向竖屏", ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT),
    LANDSCAPE("横屏", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    REVERSE_LANDSCAPE("反向横屏", ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
    SENSOR("跟随传感器", ActivityInfo.SCREEN_ORIENTATION_SENSOR);

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
        publish(OrientationOverlayState(false))
    }
}

/**
 * 用一扇不可见、不可触摸的悬浮窗把 WindowManager 的屏幕方向固定下来。
 * 停止服务即移除悬浮窗，恢复系统自动旋转。
 */
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == OrientationOverlayController.ACTION_STOP) {
            removeOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!showOrUpdateOverlay()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
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
                    OrientationOverlayState(false, detail = "创建悬浮窗失败：${e.message ?: e.javaClass.simpleName}"),
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
                OrientationOverlayState(false, detail = "更新悬浮窗方向失败：${e.message ?: e.javaClass.simpleName}"),
            )
            false
        }
    }

    private fun removeOverlay() {
        overlay?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlay = null
        params = null
        OrientationOverlayController.publish(OrientationOverlayState(false))
    }

    override fun onDestroy() {
        removeOverlay()
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
            .setContentText("已通过悬浮窗锁定为$label")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "停止", stopIntent)
            .build()
    }
}
