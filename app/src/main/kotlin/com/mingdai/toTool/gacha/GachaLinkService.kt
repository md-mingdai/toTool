package com.mingdai.toTool.gacha

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.mingdai.toTool.MainActivity
import com.mingdai.toTool.R
import com.mingdai.toTool.shizuku.ShizukuAccess
import com.mingdai.toTool.shizuku.ShizukuState
import com.mingdai.toTool.ui.screens.tools.ShellRun
import com.mingdai.toTool.ui.screens.tools.runShizukuShell
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 后台等待米游抽卡记录链接：把阻塞的 logcat 监听放到前台服务，
 * 用常驻通知告诉用户当前状态；找到链接后通知会一直保留，点按直接回到本工具。
 */
class GachaLinkService : Service() {
    companion object {
        const val ACTION_START = "com.mingdai.toTool.gacha.START"
        const val ACTION_STOP = "com.mingdai.toTool.gacha.STOP"
        private const val CHANNEL_ID = "gacha_watch"
        private const val WAITING_NOTIFICATION_ID = 4310
        private const val ERROR_NOTIFICATION_ID = 4312

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GachaLinkService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GachaLinkService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private var shellRun: ShellRun? = null

    @Volatile
    private var running = false

    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopWatch()
            ACTION_START, null -> if (!running) startWatch()
        }
        return START_STICKY
    }

    private fun startWatch() {
        running = true
        stopping = false
        GachaLinkBridge.state.value = GachaWatchState(running = true, waiting = true)
        startForeground(WAITING_NOTIFICATION_ID, waitingNotification())
        if (ShizukuAccess.state(this) != ShizukuState.READY) {
            fail("Shizuku 还没有授权，请先在米游记录获取页面授权")
            return
        }
        val found = AtomicReference<GachaLinkResult?>(null)
        val run = ShellRun()
        shellRun = run
        worker = thread(name = "gacha-logcat", isDaemon = true) {
            // logcat 不带 -d 会先输出最近缓冲区，再持续跟随新日志：
            // 既能把刚刚打开记录页产生的链接读出来，也能在用户切到游戏后继续等待。
            try {
                runShizukuShell("logcat -v brief", run) { isError, line ->
                    if (isError) return@runShizukuShell
                    val link = GachaLinkParser.parse(line) ?: return@runShizukuShell
                    if (found.compareAndSet(null, link)) {
                        run.stop()
                    }
                }
            } catch (e: Throwable) {
                handler.post {
                    if (!stopping && found.get() == null) {
                        fail("读取日志失败：${e.message ?: e.javaClass.simpleName}")
                    }
                }
                return@thread
            }
            val result = found.get()
            handler.post {
                if (!stopping) {
                    if (result != null) {
                        showResult(result)
                    } else {
                        fail("日志监听已结束，没有找到抽卡记录链接")
                    }
                }
            }
        }
    }

    private fun showResult(result: GachaLinkResult) {
        GachaLinkBridge.state.value = GachaWatchState(
            running = true,
            waiting = false,
            link = result.url,
            game = result.game,
        )
        val notification = resultNotification(result)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(WAITING_NOTIFICATION_ID, notification)
    }

    private fun fail(message: String) {
        running = false
        stopping = true
        shellRun?.stop()
        shellRun = null
        worker = null
        GachaLinkBridge.state.value = GachaWatchState(
            running = false,
            waiting = false,
            error = message,
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(ERROR_NOTIFICATION_ID, errorNotification(message))
        stopSelf()
    }

    private fun stopWatch() {
        running = false
        stopping = true
        shellRun?.stop()
        shellRun = null
        worker = null
        GachaLinkBridge.update { it.copy(running = false, waiting = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        shellRun?.stop()
        shellRun = null
        worker = null
        if (GachaLinkBridge.state.value.running) {
            GachaLinkBridge.update { it.copy(running = false, waiting = false) }
        }
        super.onDestroy()
    }

    private fun waitingNotification(
        text: String = "正在读取最近日志；请切换到游戏打开抽卡/跃迁记录页，找到链接后会自动返回",
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            10,
            openToolIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            11,
            Intent(this, GachaLinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("米游记录获取")
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "打开 toTool", open)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun resultNotification(result: GachaLinkResult): Notification {
        val open = PendingIntent.getActivity(
            this,
            12,
            openToolIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            13,
            Intent(this, GachaLinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("已获取${result.game}抽卡记录")
            .setContentText("点按回到 toTool 查看并复制链接")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "打开 toTool", open)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun errorNotification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            14,
            openToolIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("米游记录获取失败")
            .setContentText(message)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }

    private fun openToolIntent(): Intent =
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TOOL_ID, "dev_gacha")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "米游记录获取",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "等待抽卡记录链接时的后台状态"
                setShowBadge(false)
            },
        )
    }
}
