package com.mingdai.toTool.weaknet

import android.net.VpnService
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * 本地 HTTP/HTTPS 代理。VPN 只负责把目标应用导向系统 HTTP 代理，
 * 真正的限速、延迟和目标主机过滤都在这里完成。
 *
 * 这是代理级限速，不是内核级流量整形：支持遵循系统代理的 HTTP/HTTPS 流量；
 * 不使用代理的 App / 协议不会经过这里（VPN 也不会替它们转发）。
 */
internal class WeakNetProxyServer(
    private val vpn: VpnService,
    initialConfig: WeakNetConfig,
) {
    data class Stats(
        val rxBytes: Long,
        val txBytes: Long,
        val connections: Int,
        val lastHost: String,
    )

    @Volatile
    private var config: WeakNetConfig = initialConfig
    @Volatile
    private var paused = false
    @Volatile
    private var running = false
    @Volatile
    private var lastHost = ""

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val connectionThreads = Collections.synchronizedList(mutableListOf<Thread>())
    private val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val downloadLimiter = RateLimiter()
    private val uploadLimiter = RateLimiter()
    private val rxBytes = AtomicLong()
    private val txBytes = AtomicLong()
    private val connections = AtomicInteger()

    val port: Int get() = serverSocket?.localPort ?: 0

    fun start(): Int {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        running = true
        applyRates()
        acceptThread = thread(name = "weaknet-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                client.tcpNoDelay = true
                activeSockets.add(client)
                connections.incrementAndGet()
                val worker = thread(name = "weaknet-conn", isDaemon = true) {
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        // 单个连接失败不影响其它连接
                    } finally {
                        activeSockets.remove(client)
                        connections.decrementAndGet()
                        connectionThreads.remove(Thread.currentThread())
                        try {
                            client.close()
                        } catch (e: Exception) {
                            // 忽略
                        }
                    }
                }
                connectionThreads.add(worker)
            }
        }
        return server.localPort
    }

    fun updateConfig(newConfig: WeakNetConfig) {
        config = newConfig
        applyRates()
    }

    fun setPaused(value: Boolean) {
        paused = value
        applyRates()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // 忽略
        }
        serverSocket = null
        activeSockets.toList().forEach {
            try {
                it.close()
            } catch (e: Exception) {
                // 忽略
            }
        }
        activeSockets.clear()
        acceptThread?.join(500)
        acceptThread = null
        connectionThreads.toList().forEach { it.interrupt() }
        connectionThreads.clear()
    }

    fun snapshot(): Stats = Stats(
        rxBytes = rxBytes.get(),
        txBytes = txBytes.get(),
        connections = connections.get(),
        lastHost = lastHost,
    )

    private fun applyRates() {
        if (paused) {
            downloadLimiter.setRate(0)
            uploadLimiter.setRate(0)
        } else {
            downloadLimiter.setRate(config.downloadKBps)
            uploadLimiter.setRate(config.uploadKBps)
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val head = readHead(input)
        if (head == null) {
            writeError(output, 400, "Bad Request")
            return
        }
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull()?.split(" ", limit = 3) ?: run {
            writeError(output, 400, "Bad Request")
            return
        }
        if (first.size < 3) {
            writeError(output, 400, "Bad Request")
            return
        }
        val method = first[0].uppercase(Locale.ROOT)
        val targetText = first[1]
        val version = first[2]
        val headers = lines.drop(1)
        val target = parseTarget(method, targetText, headers) ?: run {
            writeError(output, 400, "Bad Target")
            return
        }
        lastHost = target.host
        val throttle = matchesHost(target.host, config.hostFilter)
        val upstream = Socket()
        try {
            vpn.protect(upstream)
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(target.host, target.port), 10_000)
            if (throttle && !paused && config.latencyMs > 0) {
                Thread.sleep(config.latencyMs.toLong())
            }
            activeSockets.add(upstream)
            if (method == "CONNECT") {
                output.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: toTool-WeakNet\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            } else {
                val path = target.path.ifBlank { "/" }
                val forwarded = StringBuilder()
                forwarded.append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
                headers.forEach { line ->
                    val name = line.substringBefore(':', "").trim().lowercase(Locale.ROOT)
                    if (name != "proxy-connection" && name != "proxy-authorization") {
                        forwarded.append(line).append("\r\n")
                    }
                }
                forwarded.append("\r\n")
                upstream.getOutputStream().write(forwarded.toString().toByteArray(Charsets.ISO_8859_1))
                upstream.getOutputStream().flush()
            }
            client.soTimeout = 0
            relay(client, input, output, upstream, throttle)
        } catch (e: Exception) {
            try {
                writeError(output, 502, "Bad Gateway")
            } catch (ignored: Exception) {
                // 连接已断开
            }
        } finally {
            activeSockets.remove(upstream)
            try {
                upstream.close()
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    private fun relay(
        client: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        upstream: Socket,
        throttle: Boolean,
    ) {
        val upload = thread(name = "weaknet-up", isDaemon = true) {
            pipe(
                clientInput,
                upstream.getOutputStream(),
                if (throttle) uploadLimiter else null,
                txBytes,
            )
        }
        val download = thread(name = "weaknet-down", isDaemon = true) {
            pipe(
                upstream.getInputStream(),
                clientOutput,
                if (throttle) downloadLimiter else null,
                rxBytes,
            )
        }
        upload.join(120_000)
        download.join(120_000)
        if (upload.isAlive) {
            try {
                client.close()
            } catch (e: Exception) {
                // 忽略
            }
        }
        if (download.isAlive) {
            try {
                upstream.close()
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    private fun pipe(
        input: InputStream,
        output: OutputStream,
        limiter: RateLimiter?,
        counter: AtomicLong,
    ) {
        val buffer = ByteArray(4 * 1024)
        try {
            while (running) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                limiter?.acquire(read)
                output.write(buffer, 0, read)
                output.flush()
                counter.addAndGet(read.toLong())
            }
        } catch (e: Exception) {
            // 流关闭 / 对端断开
        }
    }

    private data class Target(val host: String, val port: Int, val path: String)

    private fun parseTarget(method: String, target: String, headers: List<String>): Target? {
        if (method == "CONNECT") return splitHostPort(target, 443)
        val uri = if (target.startsWith("http://") || target.startsWith("https://")) {
            runCatching { URI(target) }.getOrNull()
        } else {
            null
        }
        if (uri != null && !uri.host.isNullOrBlank()) {
            val defaultPort = if (uri.scheme.equals("https", true)) 443 else 80
            val port = if (uri.port > 0) uri.port else defaultPort
            val path = buildString {
                append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                uri.rawQuery?.let { append('?').append(it) }
            }
            return Target(uri.host, port, path)
        }
        val hostHeader = headerValue(headers, "Host") ?: return null
        val base = splitHostPort(hostHeader, 80) ?: return null
        return Target(base.host, base.port, target)
    }

    private fun splitHostPort(value: String, defaultPort: Int): Target? {
        val text = value.trim().substringBefore('/')
        if (text.isEmpty()) return null
        var host = text
        var port = defaultPort
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end < 0) return null
            host = text.substring(1, end)
            if (end + 1 < text.length && text[end + 1] == ':') {
                port = text.substring(end + 2).toIntOrNull() ?: defaultPort
            }
        } else {
            val colon = text.lastIndexOf(':')
            if (colon > 0) {
                val parsed = text.substring(colon + 1).toIntOrNull()
                if (parsed != null) {
                    host = text.substring(0, colon)
                    port = parsed
                }
            }
        }
        if (host.isBlank() || port !in 1..65535) return null
        return Target(host, port, "")
    }

    private fun headerValue(headers: List<String>, name: String): String? =
        headers.firstOrNull { it.substringBefore(':', "").trim().equals(name, true) }
            ?.substringAfter(':', "")
            ?.trim()

    private fun matchesHost(host: String, filter: String): Boolean {
        if (filter.isBlank()) return true
        val cleanHost = host.lowercase(Locale.ROOT).removeSuffix(".")
        return filter.lowercase(Locale.ROOT)
            .split(',', ';', ' ', '\n')
            .map { it.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("*.").trim() }
            .filter { it.isNotEmpty() }
            .any { pattern -> cleanHost == pattern || cleanHost.endsWith(".$pattern") }
    }

    private fun readHead(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        var state = 0
        while (out.size() < 64 * 1024) {
            val byte = input.read()
            if (byte < 0) break
            out.write(byte)
            state = when {
                byte == 13 && (state == 0 || state == 2) -> state + 1
                byte == 10 && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return out.toString("ISO-8859-1")
        }
        return null
    }

    private fun writeError(output: OutputStream, code: Int, reason: String) {
        val body = "$code $reason"
        val text = "HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
        output.write(text.toByteArray(Charsets.UTF_8))
        output.flush()
    }
}

/** 共享令牌桶；每 1 KB/s 对应 1024 字节/秒。0 表示不限速。 */
internal class RateLimiter {
    private var bytesPerSecond = 0.0
    private var tokens = 0.0
    private var lastNs = System.nanoTime()

    @Synchronized
    fun setRate(kilobytesPerSecond: Int) {
        bytesPerSecond = max(0, kilobytesPerSecond) * 1024.0
        if (bytesPerSecond <= 0) tokens = 0.0
        refill()
    }

    @Synchronized
    fun acquire(bytes: Int) {
        if (bytesPerSecond <= 0) return
        while (true) {
            refill()
            if (tokens >= bytes) {
                tokens -= bytes
                return
            }
            val waitMs = ((bytes - tokens) / bytesPerSecond * 1000.0).toLong().coerceIn(1, 200)
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsed = (now - lastNs) / 1_000_000_000.0
        lastNs = now
        if (bytesPerSecond <= 0) return
        tokens = (tokens + elapsed * bytesPerSecond).coerceAtMost(bytesPerSecond)
    }
}
