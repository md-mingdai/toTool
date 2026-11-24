package com.mingdai.toTool.weaknet

import org.amnezia.awg.hevtunnel.TProxyService
import java.io.File

/**
 * hev-socks5-tunnel 的 JNI 入口。
 * 它把 TUN 里的 IP 报文转换成 SOCKS5 TCP/UDP，由本地 WeakNetSocksServer 转发并施加弱网参数。
 */
internal object WeakNetTunnel {
    fun start(configFile: File, fd: Int) {
        TProxyService.TProxyStartService(configFile.absolutePath, fd)
    }

    fun stop() {
        runCatching { TProxyService.TProxyStopService() }
    }

    fun stats(): LongArray? = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
}
