package com.mingdai.toTool.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.mingdai.toTool.MainActivity
import com.mingdai.toTool.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 只要有后台任务就维持前台服务，避免系统回收正在运行的 FFmpeg / 隔空闪传。 */
class BackgroundTaskService : Service() {
    companion object {
        private const val CHANNEL_ID = "background_tasks"
        private const val NOTIFICATION_ID = 4401
        private const val ACTION_CANCEL_ALL = "com.mingdai.toTool.action.CANCEL_ALL_BACKGROUND_TASKS"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BackgroundTaskService::class.java),
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(BackgroundTaskRegistry.tasks.value))
        serviceScope.launch {
            BackgroundTaskRegistry.tasks.collectLatest { tasks ->
                if (tasks.none { it.keepAlive }) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, buildNotification(tasks.filter { it.keepAlive }))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            BackgroundTaskRegistry.cancelAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(BackgroundTaskRegistry.tasks.value))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "后台任务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "toTool 正在后台运行的转换、隔空闪传等任务"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(tasks: List<BackgroundTask>): Notification {
        val count = tasks.size
        val title = if (count <= 1) "toTool 正在后台工作" else "toTool 正在后台运行 $count 项任务"
        val detail = tasks.joinToString(" · ") { it.title }.ifBlank { "准备中..." }
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelAllIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BackgroundTaskService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (tasks.isNotEmpty()) addAction(0, "结束全部", cancelAllIntent)
            }
            .build()
    }
}
