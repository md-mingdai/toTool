package com.mingdai.toTool.weaknet

import android.net.VpnService
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.math.pow

private const val MAX_SESSIONS = 96
private const val MAX_UDP_ASSOCIATIONS = 32
private const val TCP_BUFFER_SIZE = 16 * 1024
private const val TCP_QUEUE_CHUNKS = 128
private const val UDP_BUFFER_SIZE = 65_535
private const val CONNECT_TIMEOUT_MS = 10_000
private const val TCP_IDLE_TIMEOUT_MS = 30 * 60 * 1000
private const val TCP_RETRANSMIT_DELAY_MS = 300

/**
 * 本地 SOCKS5 服务端，给 hev-socks5-tunnel 做真正的 TCP/UDP 转发：
 * - TCP CONNECT：保护后的 Socket 直连目标，按字节流中继；
 * - UDP ASSOCIATE：客户端面（127.0.0.1）与目标面分开两个 DatagramSocket，支持 Full-cone 式回包；
 * - 限速和附加延迟作用在真正的转发路径上，TCP 与 UDP 都生效；
 * - hostFilter 只对匹配的目标施加弱网，其它目标全速。
 */
internal class WeakNetSocksServer(
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
    private var running = false

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = Collections.synchronizedList(mutableListOf<Thread>())
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val datagramSockets = Collections.synchronizedSet(mutableSetOf<DatagramSocket>())
    private val downloadLimiter = RateLimiter()
    private val uploadLimiter = RateLimiter()
    private val rxBytes = AtomicLong()
    private val txBytes = AtomicLong()
    private val connections = AtomicInteger()
    private val udpAssociations = AtomicInteger()
    private val lastHost = AtomicReference("")
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(4)

    val port: Int get() = serverSocket?.localPort ?: 0

    fun start(): Int {
        val server = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        running = true
        applyRates()
        acceptThread = thread(name = "weaknet-socks-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                if (!running) {
                    closeQuietly(client)
                    break
                }
                if (connections.get() >= MAX_SESSIONS) {
                    closeQuietly(client)
                    continue
                }
                client.tcpNoDelay = true
                sockets.add(client)
                connections.incrementAndGet()
                val worker = thread(name = "weaknet-socks-conn", isDaemon = true) {
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        // 单个连接失败不影响其它连接
                    } finally {
                        sockets.remove(client)
                        closeQuietly(client)
                        connections.decrementAndGet()
                        workers.remove(Thread.currentThread())
                    }
                }
                workers.add(worker)
            }
        }
        return server.localPort
    }

    fun updateConfig(newConfig: WeakNetConfig) {
        config = newConfig
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
        sockets.toList().forEach { closeQuietly(it) }
        sockets.clear()
        datagramSockets.toList().forEach { closeQuietly(it) }
        datagramSockets.clear()
        scheduler.shutdownNow()
        workers.toList().forEach { it.interrupt() }
        workers.clear()
        acceptThread?.join(300)
        acceptThread = null
    }

    fun snapshot(): Stats = Stats(
        rxBytes = rxBytes.get(),
        txBytes = txBytes.get(),
        connections = connections.get(),
        lastHost = lastHost.get(),
    )

    private fun applyRates() {
        downloadLimiter.setRate(config.downloadKBps)
        uploadLimiter.setRate(config.uploadKBps)
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()

        if (readByte(input) != SOCKS_VERSION) return
        val methodCount = readByte(input)
        if (methodCount <= 0) return
        val methods = ByteArray(methodCount)
        readFully(input, methods)
        if (methods.none { (it.toInt() and 0xFF) == METHOD_NO_AUTH }) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0xFF.toByte()))
            output.flush()
            return
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NO_AUTH.toByte()))
        output.flush()

        if (readByte(input) != SOCKS_VERSION) return
        val command = readByte(input)
        readByte(input) // RSV
        val address = readAddress(input) ?: run {
            replyError(output, REPLY_ADDRESS_UNSUPPORTED)
            return
        }
        val port = readPort(input)
        if (address.host.isNotBlank()) lastHost.set(address.host)

        when (command) {
            CMD_CONNECT -> handleConnect(client, input, output, address, port)
            CMD_UDP_ASSOCIATE -> handleUdpAssociate(client, input, output)
            else -> replyError(output, REPLY_COMMAND_UNSUPPORTED)
        }
    }

    // ------------------------------------------------------------------ TCP

    private fun handleConnect(
        client: Socket,
        clientInput: InputStream,
        output: OutputStream,
        address: SocksAddress,
        port: Int,
    ) {
        if (port !in 1..65535) {
            replyError(output, REPLY_GENERAL_FAILURE)
            return
        }
        val upstream = Socket()
        try {
            val target = address.resolved ?: InetAddress.getByName(address.host)
            val throttle = matchesHost(address.host, config.hostFilter, target)
            if (throttle) {
                var connectDelay = jitterDelay(config.latencyMs, config.jitterMs)
                if (rollLoss(config.lossPercent)) connectDelay += TCP_RETRANSMIT_DELAY_MS
                if (connectDelay > 0) Thread.sleep(connectDelay.toLong())
            }
            vpn.protect(upstream)
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(target, port), CONNECT_TIMEOUT_MS)
            sockets.add(upstream)
            replySuccess(output, null, 0)
            client.soTimeout = TCP_IDLE_TIMEOUT_MS
            upstream.soTimeout = TCP_IDLE_TIMEOUT_MS
            upstream.keepAlive = true
            client.keepAlive = true
            relay(client, clientInput, upstream, throttle)
        } catch (e: Exception) {
            runCatching { replyError(output, REPLY_CONNECTION_REFUSED) }
        } finally {
            sockets.remove(upstream)
            closeQuietly(upstream)
        }
    }

    private fun relay(
        client: Socket,
        clientInput: InputStream,
        upstream: Socket,
        throttle: Boolean,
    ) {
        val closed = AtomicBoolean(false)
        val closeBoth = {
            if (closed.compareAndSet(false, true)) {
                closeQuietly(client)
                closeQuietly(upstream)
            }
        }
        val latency = if (throttle) config.latencyMs else 0
        val jitter = if (throttle) config.jitterMs else 0
        val loss = if (throttle) config.lossPercent else 0
        val upload = startPipe(
            input = clientInput,
            output = upstream.getOutputStream(),
            limiter = if (throttle) uploadLimiter else null,
            counter = txBytes,
            latencyMs = latency,
            jitterMs = jitter,
            lossPercent = loss,
            onEof = { runCatching { upstream.shutdownOutput() } },
            onError = closeBoth,
        )
        val download = startPipe(
            input = upstream.getInputStream(),
            output = client.getOutputStream(),
            limiter = if (throttle) downloadLimiter else null,
            counter = rxBytes,
            latencyMs = latency,
            jitterMs = jitter,
            lossPercent = loss,
            onEof = { runCatching { client.shutdownOutput() } },
            onError = closeBoth,
        )
        upload.reader.join()
        upload.writer?.join()
        download.reader.join()
        download.writer?.join()
        closeBoth()
    }

    private class Pipe(val reader: Thread, val writer: Thread? = null)

    private fun startPipe(
        input: InputStream,
        output: OutputStream,
        limiter: RateLimiter?,
        counter: AtomicLong,
        latencyMs: Int,
        jitterMs: Int,
        lossPercent: Int,
        onEof: () -> Unit,
        onError: () -> Unit,
    ): Pipe {
        val pipeClosed = AtomicBoolean(false)
        val fail = {
            if (pipeClosed.compareAndSet(false, true)) onError()
        }
        if (latencyMs <= 0 && jitterMs <= 0 && lossPercent <= 0) {
            val worker = thread(name = "weaknet-pipe", isDaemon = true) {
                val buffer = ByteArray(TCP_BUFFER_SIZE)
                var eof = false
                try {
                    while (running && !pipeClosed.get()) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            eof = true
                            break
                        }
                        if (read == 0) continue
                        limiter?.acquire(read)
                        if (!running || pipeClosed.get()) break
                        output.write(buffer, 0, read)
                        output.flush()
                        counter.addAndGet(read.toLong())
                    }
                } catch (e: Exception) {
                    // 流关闭 / 对端断开
                } finally {
                    workers.remove(Thread.currentThread())
                    if (eof) {
                        runCatching { onEof() }
                    } else {
                        fail()
                    }
                }
            }
            workers.add(worker)
            return Pipe(worker)
        }

        val queue = ArrayBlockingQueue<Chunk>(TCP_QUEUE_CHUNKS)
        val writer = thread(name = "weaknet-pipe-w", isDaemon = true) {
            var normalEnd = false
            var stalledUntilNanos = 0L
            try {
                while (running && !pipeClosed.get()) {
                    val chunk = try {
                        queue.poll(100, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        break
                    } ?: continue
                    if (chunk.length < 0) {
                        normalEnd = true
                        break
                    }
                    val now = System.nanoTime()
                    var delayMs = jitterDelay(latencyMs, jitterMs)
                    if (rollLossForBytes(lossPercent, chunk.length)) {
                        delayMs += TCP_RETRANSMIT_DELAY_MS
                        // 丢包会让 TCP 头部阻塞，后续数据一起等一个 RTO，而不是只推迟这一块。
                        stalledUntilNanos = maxOf(
                            stalledUntilNanos,
                            now + TCP_RETRANSMIT_DELAY_MS * 1_000_000L,
                        )
                    }
                    val wait = maxOf(
                        chunk.atNanos + delayMs * 1_000_000L,
                        stalledUntilNanos,
                    ) - now
                    if (wait > 0) LockSupport.parkNanos(wait)
                    if (Thread.interrupted() || !running || pipeClosed.get()) break
                    limiter?.acquire(chunk.length)
                    if (!running || pipeClosed.get()) break
                    output.write(chunk.data, 0, chunk.length)
                    output.flush()
                    counter.addAndGet(chunk.length.toLong())
                }
            } catch (e: Exception) {
                // 流关闭 / 对端断开
            } finally {
                workers.remove(Thread.currentThread())
                if (normalEnd) {
                    runCatching { onEof() }
                } else {
                    fail()
                }
            }
        }
        val reader = thread(name = "weaknet-pipe-r", isDaemon = true) {
            val buffer = ByteArray(TCP_BUFFER_SIZE)
            var eof = false
            try {
                while (running && !pipeClosed.get()) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        eof = true
                        break
                    }
                    if (read == 0) continue
                    val chunk = Chunk(buffer.copyOf(read), read, System.nanoTime())
                    while (running && !pipeClosed.get()) {
                        if (queue.offer(chunk, 100, TimeUnit.MILLISECONDS)) break
                    }
                }
            } catch (e: Exception) {
                // 流关闭 / 对端断开
            } finally {
                if (eof) {
                    while (!pipeClosed.get()) {
                        try {
                            if (queue.offer(Chunk(ByteArray(0), -1, 0), 100, TimeUnit.MILLISECONDS)) break
                        } catch (e: InterruptedException) {
                            break
                        }
                        if (!running) break
                    }
                }
                workers.remove(Thread.currentThread())
                if (!eof) fail()
            }
        }
        workers.add(writer)
        workers.add(reader)
        return Pipe(reader, writer)
    }

    private class Chunk(val data: ByteArray, val length: Int, val atNanos: Long)

    // ------------------------------------------------------------------ UDP

    private fun handleUdpAssociate(
        client: Socket,
        input: InputStream,
        output: OutputStream,
    ) {
        if (udpAssociations.get() >= MAX_UDP_ASSOCIATIONS) {
            replyError(output, REPLY_COMMAND_UNSUPPORTED)
            return
        }
        val loopback = InetAddress.getByName("127.0.0.1")
        val clientSocket: DatagramSocket
        val targetSocket: DatagramSocket
        try {
            clientSocket = DatagramSocket(InetSocketAddress(loopback, 0))
            targetSocket = DatagramSocket(0)
        } catch (e: Exception) {
            replyError(output, REPLY_GENERAL_FAILURE)
            return
        }
        runCatching { vpn.protect(targetSocket) }
        datagramSockets.add(clientSocket)
        datagramSockets.add(targetSocket)
        udpAssociations.incrementAndGet()

        val clientUdp = AtomicReference<InetSocketAddress?>(null)
        val shapedTargets = ConcurrentHashMap.newKeySet<InetSocketAddress>()
        val resolvedHosts = ConcurrentHashMap<String, InetAddress>()
        val closed = AtomicBoolean(false)
        val cleanup = {
            if (closed.compareAndSet(false, true)) {
                datagramSockets.remove(clientSocket)
                datagramSockets.remove(targetSocket)
                closeQuietly(clientSocket)
                closeQuietly(targetSocket)
                udpAssociations.decrementAndGet()
            }
        }

        try {
            replySuccess(output, loopback, clientSocket.localPort)
        } catch (e: Exception) {
            cleanup()
            return
        }
        client.soTimeout = 0

        val watcher = thread(name = "weaknet-udp-ctl", isDaemon = true) {
            try {
                while (running && !closed.get()) {
                    if (input.read() < 0) break
                }
            } catch (e: Exception) {
                // 控制连接关闭
            } finally {
                workers.remove(Thread.currentThread())
                cleanup()
            }
        }
        workers.add(watcher)

        val responseWorker = thread(name = "weaknet-udp-down", isDaemon = true) {
            val buffer = ByteArray(UDP_BUFFER_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (running && !closed.get()) {
                    packet.setLength(buffer.size)
                    try {
                        targetSocket.receive(packet)
                    } catch (e: Exception) {
                        if (!running || closed.get()) break
                        continue
                    }
                    val source = packet.socketAddress as? InetSocketAddress ?: continue
                    val clientAddress = clientUdp.get() ?: continue
                    val throttle = config.hostFilter.isBlank() || shapedTargets.contains(source)
                    val header = buildUdpHeader(source)
                    val payload = ByteArray(header.size + packet.length)
                    System.arraycopy(header, 0, payload, 0, header.size)
                    System.arraycopy(packet.data, 0, payload, header.size, packet.length)
                    sendDatagram(
                        socket = clientSocket,
                        destination = clientAddress,
                        data = payload,
                        limiter = if (throttle) downloadLimiter else null,
                        counter = rxBytes,
                        closed = closed,
                        latencyMs = if (throttle) config.latencyMs else 0,
                        jitterMs = if (throttle) config.jitterMs else 0,
                        lossPercent = if (throttle) config.lossPercent else 0,
                    )
                }
            } catch (e: Exception) {
                // 目标面 socket 已关闭
            } finally {
                workers.remove(Thread.currentThread())
                cleanup()
            }
        }
        workers.add(responseWorker)

        try {
            val buffer = ByteArray(UDP_BUFFER_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)
            while (running && !closed.get()) {
                packet.setLength(buffer.size)
                try {
                    clientSocket.receive(packet)
                } catch (e: Exception) {
                    if (!running || closed.get()) break
                    continue
                }
                val source = packet.socketAddress as? InetSocketAddress ?: continue
                clientUdp.set(source)
                val request = parseUdpRequest(packet.data, packet.length) ?: continue
                lastHost.set(request.host)
                val targetAddress = request.address
                    ?: resolvedHosts[request.host]
                    ?: runCatching { InetAddress.getByName(request.host) }
                        .getOrNull()
                        ?.also {
                            if (resolvedHosts.size < 512) resolvedHosts[request.host] = it
                        }
                    ?: continue
                val target = InetSocketAddress(targetAddress, request.port)
                val throttle = matchesHost(request.host, config.hostFilter, targetAddress)
                if (throttle) shapedTargets.add(target) else shapedTargets.remove(target)
                sendDatagram(
                    socket = targetSocket,
                    destination = target,
                    data = request.payload,
                    limiter = if (throttle) uploadLimiter else null,
                    counter = txBytes,
                    closed = closed,
                    latencyMs = if (throttle) config.latencyMs else 0,
                    jitterMs = if (throttle) config.jitterMs else 0,
                    lossPercent = if (throttle) config.lossPercent else 0,
                )
            }
        } catch (e: Exception) {
            // 客户端面 socket 已关闭
        } finally {
            cleanup()
            watcher.interrupt()
            responseWorker.interrupt()
        }
    }

    private fun sendDatagram(
        socket: DatagramSocket,
        destination: InetSocketAddress,
        data: ByteArray,
        limiter: RateLimiter?,
        counter: AtomicLong,
        closed: AtomicBoolean,
        latencyMs: Int,
        jitterMs: Int,
        lossPercent: Int,
    ) {
        if (rollLoss(lossPercent)) return
        val send = Runnable {
            if (!running || closed.get()) return@Runnable
            limiter?.acquire(data.size)
            if (!running || closed.get()) return@Runnable
            val success = runCatching {
                socket.send(DatagramPacket(data, data.size, destination))
            }.isSuccess
            if (success) counter.addAndGet(data.size.toLong())
        }
        if (limiter != null) {
            val delay = jitterDelay(latencyMs, jitterMs)
            runCatching {
                scheduler.schedule(send, delay.toLong(), TimeUnit.MILLISECONDS)
            }
        } else {
            send.run()
        }
    }

    private fun jitterDelay(baseMs: Int, jitterMs: Int): Int =
        baseMs + if (jitterMs > 0) ThreadLocalRandom.current().nextInt(jitterMs + 1) else 0

    private fun rollLoss(percent: Int): Boolean =
        percent > 0 && ThreadLocalRandom.current().nextInt(100) < percent

    /** TCP 的丢包按 1500 字节左右的包折算，避免把 16KB 一块当成单个包低估丢包率。 */
    private fun rollLossForBytes(percent: Int, bytes: Int): Boolean {
        if (percent <= 0 || bytes <= 0) return false
        val probability = 1.0 - (1.0 - percent / 100.0).pow(bytes / 1500.0)
        return ThreadLocalRandom.current().nextDouble() < probability
    }

    private class UdpRequest(
        val host: String,
        val address: InetAddress?,
        val port: Int,
        val payload: ByteArray,
    )

    private fun parseUdpRequest(data: ByteArray, length: Int): UdpRequest? {
        if (length < 4) return null
        if ((data[2].toInt() and 0xFF) != 0) return null // FRAG != 0
        var index = 3
        val atyp = data[index++].toInt() and 0xFF
        val address: InetAddress?
        val host: String
        when (atyp) {
            ATYP_IPV4 -> {
                if (length < index + 4) return null
                address = InetAddress.getByAddress(data.copyOfRange(index, index + 4))
                host = address.hostAddress ?: return null
                index += 4
            }
            ATYP_IPV6 -> {
                if (length < index + 16) return null
                address = InetAddress.getByAddress(data.copyOfRange(index, index + 16))
                host = address.hostAddress ?: return null
                index += 16
            }
            ATYP_DOMAIN -> {
                if (length < index + 1) return null
                val nameLength = data[index++].toInt() and 0xFF
                if (nameLength == 0 || length < index + nameLength) return null
                host = String(data, index, nameLength, Charsets.UTF_8)
                address = null
                index += nameLength
            }
            else -> return null
        }
        if (length < index + 2) return null
        val port = ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
        index += 2
        if (port !in 1..65535) return null
        return UdpRequest(host, address, port, data.copyOfRange(index, length))
    }

    private fun buildUdpHeader(source: InetSocketAddress): ByteArray {
        val ip = source.address?.address ?: return ByteArray(0)
        val atyp = if (ip.size == 16) ATYP_IPV6 else ATYP_IPV4
        val header = ByteArray(4 + ip.size + 2)
        header[3] = atyp.toByte()
        System.arraycopy(ip, 0, header, 4, ip.size)
        header[4 + ip.size] = ((source.port shr 8) and 0xFF).toByte()
        header[5 + ip.size] = (source.port and 0xFF).toByte()
        return header
    }

    // ------------------------------------------------------------------ SOCKS5 编解码

    private class SocksAddress(val host: String, val resolved: InetAddress?)

    private fun readAddress(input: InputStream): SocksAddress? {
        return when (val atyp = readByte(input)) {
            ATYP_IPV4 -> {
                val bytes = ByteArray(4)
                readFully(input, bytes)
                val address = InetAddress.getByAddress(bytes)
                SocksAddress(address.hostAddress ?: "", address)
            }
            ATYP_IPV6 -> {
                val bytes = ByteArray(16)
                readFully(input, bytes)
                val address = InetAddress.getByAddress(bytes)
                SocksAddress(address.hostAddress ?: "", address)
            }
            ATYP_DOMAIN -> {
                val length = readByte(input)
                if (length <= 0) return null
                val bytes = ByteArray(length)
                readFully(input, bytes)
                SocksAddress(String(bytes, Charsets.UTF_8), null)
            }
            else -> null
        }
    }

    private fun readPort(input: InputStream): Int {
        val high = readByte(input)
        val low = readByte(input)
        return (high shl 8) or low
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException()
        return value and 0xFF
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
    }

    private fun replySuccess(output: OutputStream, address: InetAddress?, port: Int) {
        val ip = address?.address ?: ByteArray(4)
        val atyp = if (ip.size == 16) ATYP_IPV6 else ATYP_IPV4
        val reply = ByteArray(4 + ip.size + 2)
        reply[0] = SOCKS_VERSION.toByte()
        reply[1] = REPLY_SUCCEEDED.toByte()
        reply[2] = 0
        reply[3] = atyp.toByte()
        System.arraycopy(ip, 0, reply, 4, ip.size)
        reply[4 + ip.size] = ((port shr 8) and 0xFF).toByte()
        reply[5 + ip.size] = (port and 0xFF).toByte()
        output.write(reply)
        output.flush()
    }

    private fun replyError(output: OutputStream, code: Int) {
        val reply = byteArrayOf(
            SOCKS_VERSION.toByte(),
            code.toByte(),
            0,
            ATYP_IPV4.toByte(),
            0, 0, 0, 0,
            0, 0,
        )
        output.write(reply)
        output.flush()
    }

    private fun matchesHost(host: String, filter: String, resolved: InetAddress? = null): Boolean {
        if (filter.isBlank()) return true
        val patterns = filterPatterns(filter)
        if (patterns.isEmpty()) return true
        val cleanHost = host.lowercase(Locale.ROOT).removeSuffix(".")
        if (patterns.any { pattern -> cleanHost == pattern || cleanHost.endsWith(".$pattern") }) {
            return true
        }
        val addresses = if (resolved != null) {
            listOf(resolved)
        } else {
            runCatching { InetAddress.getAllByName(host).toList() }.getOrNull().orEmpty()
        }
        return addresses.any addressLoop@{ address ->
            val ip = address.hostAddress?.lowercase(Locale.ROOT) ?: return@addressLoop false
            patterns.any { pattern -> pattern == ip }
        }
    }

    private fun filterPatterns(filter: String): List<String> =
        filter.lowercase(Locale.ROOT)
            .split(',', ';', ' ', '\n')
            .map { raw ->
                var text = raw.trim()
                if (text.contains("://")) text = text.substringAfter("://")
                text = text.substringBefore('/')
                if (text.startsWith("[")) {
                    text = text.substringAfter('[').substringBefore(']')
                } else if (text.count { it == ':' } == 1) {
                    text = text.substringBefore(':')
                }
                text.removePrefix("*.").removeSuffix(".").trim()
            }
            .filter { it.isNotEmpty() }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }

    private fun closeQuietly(socket: DatagramSocket) {
        runCatching { socket.close() }
    }

    private companion object {
        const val SOCKS_VERSION = 5
        const val METHOD_NO_AUTH = 0
        const val CMD_CONNECT = 1
        const val CMD_UDP_ASSOCIATE = 3
        const val ATYP_IPV4 = 1
        const val ATYP_DOMAIN = 3
        const val ATYP_IPV6 = 4
        const val REPLY_SUCCEEDED = 0
        const val REPLY_GENERAL_FAILURE = 1
        const val REPLY_COMMAND_UNSUPPORTED = 7
        const val REPLY_ADDRESS_UNSUPPORTED = 8
        const val REPLY_CONNECTION_REFUSED = 5
    }
}
