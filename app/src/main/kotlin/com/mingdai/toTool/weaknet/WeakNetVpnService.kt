package com.mingdai.toTool.weaknet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mingdai.toTool.MainActivity
import com.mingdai.toTool.R
import com.mingdai.toTool.background.BackgroundTask
import com.mingdai.toTool.background.BackgroundTaskRegistry
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class WeakNetVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.mingdai.toTool.weaknet.START"
        const val ACTION_STOP = "com.mingdai.toTool.weaknet.STOP"
        const val ACTION_UPDATE = "com.mingdai.toTool.weaknet.UPDATE"
        const val ACTION_PAUSE = "com.mingdai.toTool.weaknet.PAUSE"
        const val ACTION_RESUME = "com.mingdai.toTool.weaknet.RESUME"
        const val TASK_ID = "weaknet"
        private const val CHANNEL_ID = "weaknet"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WeakNetVpnService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WeakNetVpnService::class.java).setAction(ACTION_STOP))
        }

        fun update(context: Context) {
            context.startService(Intent(context, WeakNetVpnService::class.java).setAction(ACTION_UPDATE))
        }

        fun pause(context: Context) {
            context.startService(Intent(context, WeakNetVpnService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WeakNetVpnService::class.java).setAction(ACTION_RESUME),
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    @Volatile
    private var socks: WeakNetSocksServer? = null

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    private var overlay: WeakNetOverlay? = null

    @Volatile
    private var config = WeakNetConfig()

    @Volatile
    private var paused = false

    @Volatile
    private var running = false

    @Volatile
    private var starting = false

    private val statsTick = object : Runnable {
        private var lastRx = 0L
        private var lastTx = 0L
        private var lastAt = 0L

        override fun run() {
            if (!running) return
            val stats = socks?.snapshot() ?: return
            val now = SystemClock.elapsedRealtime()
            val elapsed = if (lastAt == 0L) 0L else (now - lastAt).coerceAtLeast(1L)
            val rxSpeed = if (lastAt == 0L) 0L else ((stats.rxBytes - lastRx) * 1000 / elapsed).coerceAtLeast(0L)
            val txSpeed = if (lastAt == 0L) 0L else ((stats.txBytes - lastTx) * 1000 / elapsed).coerceAtLeast(0L)
            lastRx = stats.rxBytes
            lastTx = stats.txBytes
            lastAt = now
            WeakNetManager.update {
                it.copy(
                    running = true,
                    paused = paused,
                    config = config,
                    port = socks?.port,
                    rxBytes = stats.rxBytes,
                    txBytes = stats.txBytes,
                    rxSpeed = rxSpeed,
                    txSpeed = txSpeed,
                    connections = stats.connections,
                    lastHost = stats.lastHost,
                )
            }
            overlay?.refresh(WeakNetManager.state.value)
            updateTaskDetail()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                if (running || paused) {
                    config = WeakNetStore.load(this)
                    if (running) socks?.updateConfig(config)
                    WeakNetManager.update { it.copy(config = config) }
                    if (config.showOverlay) showOverlay() else hideOverlay()
                    updateTaskDetail()
                    updateNotification()
                }
                return START_STICKY
            }
            ACTION_PAUSE -> {
                if (running || starting) pauseWeakNet()
                return START_STICKY
            }
            ACTION_RESUME -> {
                if (!running && !starting) startWeakNet()
                return START_STICKY
            }
            ACTION_START -> if (!running) startWeakNet()
            else -> if (!running) startWeakNet()
        }
        return START_STICKY
    }

    private fun startWeakNet() {
        if (running || starting) return
        config = WeakNetStore.load(this)
        paused = false
        startForeground(NOTIFICATION_ID, buildNotification())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            fail("弱网助手需要 Android 10 及以上系统")
            return
        }
        starting = true
        val startConfig = config
        thread(name = "weaknet-start", isDaemon = true) {
            var server: WeakNetSocksServer? = null
            var descriptor: ParcelFileDescriptor? = null
            try {
                val startedServer = WeakNetSocksServer(this, startConfig)
                server = startedServer
                val port = startedServer.start()
                val builder = Builder()
                    .setSession("toTool 弱网助手")
                    .setMtu(1500)
                    .addAddress("10.66.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fc00::2", 128)
                    .addRoute("::", 0)
                    .setBlocking(false)
                // mapdns 会拦截发往这个地址的 DNS 查询,返回 240.0.0.0/4 里的映射 IP;
                // 应用连接映射 IP 时,hev 把原始域名交给本地 SOCKS5,域名过滤和远端解析都能生效。
                builder.addDnsServer("10.66.0.1")
                val targetPackage = startConfig.packageName
                if (targetPackage != null) {
                    builder.addAllowedApplication(targetPackage)
                }
                if (targetPackage != packageName) {
                    // 工具自身的 DNS 解析和 SOCKS 转发必须绕过 VPN，避免回环。
                    builder.addDisallowedApplication(packageName)
                }
                val established = builder.establish()
                    ?: throw IllegalStateException("VPN 授权未完成，或系统拒绝了代理网络")
                descriptor = established

                val configFile = File(cacheDir, "weaknet_tproxy.conf")
                configFile.writeText(
                    buildString {
                        appendLine("tunnel:")
                        appendLine("  mtu: 1500")
                        appendLine("  icmp: 'reply'")
                        appendLine("socks5:")
                        appendLine("  address: '127.0.0.1'")
                        appendLine("  port: $port")
                        appendLine("  udp: 'udp'")
                        appendLine("mapdns:")
                        appendLine("  address: 10.66.0.1")
                        appendLine("  port: 53")
                        appendLine("  network: 240.0.0.0")
                        appendLine("  netmask: 240.0.0.0")
                        appendLine("  cache-size: 10000")
                        appendLine("misc:")
                        appendLine("  log-level: warn")
                    },
                )
                // 放在独立线程里启动：即使 JNI 变成阻塞式的 tunnel 循环，服务状态也能正常建立。
                thread(name = "weaknet-tunnel", isDaemon = true) {
                    try {
                        if (!running && !starting) return@thread
                        WeakNetTunnel.start(configFile, established.fd)
                    } catch (e: Throwable) {
                        handler.post {
                            if (running || starting) {
                                fail("弱网转发层启动失败：${e.message ?: e.javaClass.simpleName}")
                            }
                        }
                    }
                }

                synchronized(stateLock) {
                    if (!starting) {
                        runCatching { WeakNetTunnel.stop() }
                        runCatching { startedServer.stop() }
                        runCatching { established.close() }
                        return@thread
                    }
                    socks = startedServer
                    tun = established
                    running = true
                    starting = false
                }
                handler.post {
                    if (!running) return@post
                    WeakNetManager.update {
                        WeakNetState(
                            running = true,
                            paused = false,
                            port = port,
                            config = startConfig,
                        )
                    }
                    registerTask()
                    if (startConfig.showOverlay) showOverlay()
                    handler.post(statsTick)
                }
            } catch (e: Throwable) {
                runCatching { WeakNetTunnel.stop() }
                runCatching { server?.stop() }
                runCatching { descriptor?.close() }
                val canceled = synchronized(stateLock) { !starting }
                if (!canceled) {
                    handler.post {
                        if (starting) fail("无法启动弱网助手：${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlay == null) {
            overlay = WeakNetOverlay(
                this,
                onTogglePause = { if (paused) startWeakNet() else pauseWeakNet() },
                onStop = { stopEverything() },
            )
        }
        overlay?.show()
        overlay?.refresh(WeakNetManager.state.value)
    }

    private fun hideOverlay() {
        overlay?.hide()
        overlay = null
    }

    /** 暂停限速：关闭 VPN / 代理资源，但保留前台服务与悬浮球，方便随时继续。 */
    private fun pauseWeakNet() {
        var p: WeakNetSocksServer? = null
        var t: ParcelFileDescriptor? = null
        synchronized(stateLock) {
            if (!running && !starting) {
                paused = true
                return
            }
            running = false
            starting = false
            paused = true
            p = socks
            t = tun
            socks = null
            tun = null
        }
        handler.removeCallbacks(statsTick)
        WeakNetManager.update {
            it.copy(
                running = false,
                paused = true,
                port = null,
                rxSpeed = 0,
                txSpeed = 0,
                connections = 0,
            )
        }
        overlay?.refresh(WeakNetManager.state.value)
        registerTask()
        updateNotification()
        thread(name = "weaknet-pause", isDaemon = true) {
            runCatching { WeakNetTunnel.stop() }
            runCatching { p?.stop() }
            runCatching { t?.close() }
        }
    }

    private fun stopEverything() {
        var p: WeakNetSocksServer? = null
        var t: ParcelFileDescriptor? = null
        synchronized(stateLock) {
            running = false
            starting = false
            paused = false
            p = socks
            t = tun
            socks = null
            tun = null
        }
        handler.removeCallbacks(statsTick)
        hideOverlay()
        BackgroundTaskRegistry.remove(TASK_ID)
        WeakNetManager.update {
            it.copy(
                running = false,
                paused = false,
                port = null,
                rxSpeed = 0,
                txSpeed = 0,
                connections = 0,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // 关闭 socket / TUN / DNS 线程可能涉及系统调用，放到后台，避免停止或转屏时卡住主线程。
        thread(name = "weaknet-stop", isDaemon = true) {
            runCatching { WeakNetTunnel.stop() }
            runCatching { p?.stop() }
            runCatching { t?.close() }
        }
    }

    private fun registerTask() {
        val state = WeakNetManager.state.value
        BackgroundTaskRegistry.add(
            this,
            BackgroundTask(
                id = TASK_ID,
                toolId = "dev_weaknet",
                title = "弱网助手",
                detail = taskDetail(state),
                keepAlive = false,
                cancel = { handler.post { stopEverything() } },
            ),
            keepAlive = false,
        )
    }

    private fun updateTaskDetail(state: WeakNetState = WeakNetManager.state.value) {
        BackgroundTaskRegistry.update(TASK_ID) { it.copy(detail = taskDetail(state)) }
    }

    private fun taskDetail(state: WeakNetState): String =
        if (state.paused) {
            "已暂停限速 · ${state.config.targetLabel}"
        } else {
            "↓${formatRate(state.rxSpeed)} ↑${formatRate(state.txSpeed)} · " +
                "${state.connections} 连接 · ${state.config.targetLabel}"
        }

    private fun formatRate(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB/s", bytes / 1048576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB/s", bytes / 1024.0)
        else -> "$bytes B/s"
    }

    private fun updateNotification() {
        if (!running && !paused) return
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(
                NOTIFICATION_ID,
                buildNotification(),
            )
        }
    }

    private fun fail(message: String) {
        var p: WeakNetSocksServer? = null
        var t: ParcelFileDescriptor? = null
        synchronized(stateLock) {
            running = false
            starting = false
            p = socks
            t = tun
            socks = null
            tun = null
        }
        handler.removeCallbacks(statsTick)
        hideOverlay()
        BackgroundTaskRegistry.remove(TASK_ID)
        WeakNetManager.update {
            it.copy(
                running = false,
                paused = false,
                error = message,
                port = null,
                connections = 0,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        thread(name = "weaknet-fail-cleanup", isDaemon = true) {
            runCatching { WeakNetTunnel.stop() }
            runCatching { p?.stop() }
            runCatching { t?.close() }
        }
    }

    override fun onDestroy() {
        runCatching { WeakNetTunnel.stop() }
        runCatching { socks?.stop() }
        runCatching { tun?.close() }
        BackgroundTaskRegistry.remove(TASK_ID)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // WindowManager 的配置更新也在主线程排队，延后到当前转屏消息处理完再 reposition。
        handler.postDelayed({ overlay?.refreshPosition() }, 120)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "弱网助手",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "弱网助手的 VPN 代理与悬浮窗状态"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WeakNetVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val resumeIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, WeakNetVpnService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val target = config.targetLabel
        val detail = if (paused) {
            "已暂停限速 · $target"
        } else {
            "↓${config.downloadKBps}KB/s ↑${config.uploadKBps}KB/s · $target"
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (paused) "弱网助手已暂停" else "弱网助手运行中")
            .setContentText(detail)
            .setContentIntent(openIntent)
            .setOngoing(true)
        if (paused) builder.addAction(0, "继续", resumeIntent)
        return builder.addAction(0, "停止", stopIntent).build()
    }
}
