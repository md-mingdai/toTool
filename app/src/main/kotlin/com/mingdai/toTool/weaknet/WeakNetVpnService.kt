package com.mingdai.toTool.weaknet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ProxyInfo
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
import kotlin.concurrent.thread

class WeakNetVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.mingdai.toTool.weaknet.START"
        const val ACTION_STOP = "com.mingdai.toTool.weaknet.STOP"
        const val ACTION_UPDATE = "com.mingdai.toTool.weaknet.UPDATE"
        const val ACTION_TOGGLE_PAUSE = "com.mingdai.toTool.weaknet.TOGGLE_PAUSE"
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

        fun togglePause(context: Context) {
            context.startService(Intent(context, WeakNetVpnService::class.java).setAction(ACTION_TOGGLE_PAUSE))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    @Volatile
    private var proxy: WeakNetProxyServer? = null

    @Volatile
    private var dns: WeakNetDnsForwarder? = null

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
            val stats = proxy?.snapshot() ?: return
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
                    port = proxy?.port,
                    rxBytes = stats.rxBytes,
                    txBytes = stats.txBytes,
                    rxSpeed = rxSpeed,
                    txSpeed = txSpeed,
                    connections = stats.connections,
                    lastHost = stats.lastHost,
                )
            }
            overlay?.refresh(WeakNetManager.state.value)
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
                if (running) {
                    config = WeakNetStore.load(this)
                    proxy?.updateConfig(config)
                    WeakNetManager.update { it.copy(config = config) }
                    if (config.showOverlay) showOverlay() else hideOverlay(false)
                }
                return START_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                if (running) togglePause()
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
            var server: WeakNetProxyServer? = null
            var descriptor: ParcelFileDescriptor? = null
            try {
                val startedServer = WeakNetProxyServer(this, startConfig)
                server = startedServer
                val port = startedServer.start()
                val builder = Builder()
                    .setSession("toTool 弱网助手")
                    .setMtu(1500)
                    .addAddress("10.66.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("10.66.0.1")
                    .setBlocking(false)
                val targetPackage = startConfig.packageName
                if (targetPackage != null) {
                    builder.addAllowedApplication(targetPackage)
                } else {
                    val selfPackage = packageName
                    builder.addDisallowedApplication(selfPackage)
                }
                builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", port))
                val established = builder.establish()
                    ?: throw IllegalStateException("VPN 授权未完成，或系统拒绝了代理网络")
                descriptor = established
                synchronized(stateLock) {
                    if (!starting) {
                        try {
                            startedServer.stop()
                        } catch (e: Exception) {
                            // 忽略
                        }
                        try {
                            established.close()
                        } catch (e: Exception) {
                            // 忽略
                        }
                        return@thread
                    }
                    proxy = startedServer
                    tun = established
                    dns = WeakNetDnsForwarder(this, established).also { it.start() }
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
                    if (startConfig.showOverlay) showOverlay()
                    handler.post(statsTick)
                }
            } catch (e: Exception) {
                try {
                    server?.stop()
                } catch (ignored: Exception) {
                    // 忽略
                }
                try {
                    descriptor?.close()
                } catch (ignored: Exception) {
                    // 忽略
                }
                val canceled = synchronized(stateLock) { !starting }
                if (!canceled) {
                    handler.post {
                        if (starting) fail("无法启动弱网助手：${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun togglePause() {
        paused = !paused
        proxy?.setPaused(paused)
        WeakNetManager.update { it.copy(paused = paused) }
        overlay?.refresh(WeakNetManager.state.value)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlay == null) {
            overlay = WeakNetOverlay(
                this,
                onTogglePause = { togglePause() },
                onStop = { stopEverything() },
                onHide = { hideOverlay(true) },
            )
        }
        overlay?.show()
        overlay?.refresh(WeakNetManager.state.value)
    }

    private fun hideOverlay(save: Boolean) {
        overlay?.hide()
        overlay = null
        if (save) {
            config = config.copy(showOverlay = false)
            WeakNetStore.save(this, config)
            WeakNetManager.update { it.copy(config = config) }
        }
    }

    private fun stopEverything() {
        var p: WeakNetProxyServer? = null
        var d: WeakNetDnsForwarder? = null
        var t: ParcelFileDescriptor? = null
        synchronized(stateLock) {
            if (!running && !starting && proxy == null && dns == null && tun == null) {
                stopSelf()
                return
            }
            running = false
            starting = false
            p = proxy
            d = dns
            t = tun
            proxy = null
            dns = null
            tun = null
        }
        handler.removeCallbacks(statsTick)
        hideOverlay(false)
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
            try {
                p?.stop()
            } catch (e: Exception) {
                // 忽略
            }
            try {
                d?.stop()
            } catch (e: Exception) {
                // 忽略
            }
            try {
                t?.close()
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    private fun fail(message: String) {
        var p: WeakNetProxyServer? = null
        var d: WeakNetDnsForwarder? = null
        var t: ParcelFileDescriptor? = null
        synchronized(stateLock) {
            running = false
            starting = false
            p = proxy
            d = dns
            t = tun
            proxy = null
            dns = null
            tun = null
        }
        handler.removeCallbacks(statsTick)
        hideOverlay(false)
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
            try {
                p?.stop()
            } catch (e: Exception) {
                // 忽略
            }
            try {
                d?.stop()
            } catch (e: Exception) {
                // 忽略
            }
            try {
                t?.close()
            } catch (e: Exception) {
                // 忽略
            }
        }
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
        val target = config.targetLabel
        val detail = if (paused) {
            "已暂停限速 · $target"
        } else {
            "↓${config.downloadKBps}KB/s ↑${config.uploadKBps}KB/s · $target"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("弱网助手运行中")
            .setContentText(detail)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "停止", stopIntent)
            .build()
    }
}
