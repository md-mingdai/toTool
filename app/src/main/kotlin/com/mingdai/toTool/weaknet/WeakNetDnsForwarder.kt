package com.mingdai.toTool.weaknet

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * 极简 DNS 转发：VPN 把 DNS 指向 10.66.0.1，这里从 TUN 读出 UDP 53 查询，
 * 用受 protect() 保护的 UDP socket 向上游查询，再把响应封装回 IPv4/UDP 写进 TUN。
 * 只处理 IPv4 UDP，足够覆盖系统 DNS；DoH/DoT 走代理，不需要这里。
 */
internal class WeakNetDnsForwarder(
    private val vpn: VpnService,
    private val tun: ParcelFileDescriptor,
) {
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private val upstreams = listOf("1.1.1.1", "8.8.8.8", "114.114.114.114")

    fun start() {
        running = true
        worker = thread(name = "weaknet-dns", isDaemon = true) {
            val input = FileInputStream(tun.fileDescriptor)
            val output = FileOutputStream(tun.fileDescriptor)
            val packet = ByteArray(4096)
            try {
                while (running) {
                    val length = input.read(packet)
                    if (length <= 0) continue
                    val response = handle(packet, length)
                    if (response != null) {
                        try {
                            output.write(response)
                            output.flush()
                        } catch (e: Exception) {
                            // TUN 已关闭
                        }
                    }
                }
            } catch (e: Exception) {
                // 服务停止 / TUN 关闭
            } finally {
                try {
                    input.close()
                } catch (e: Exception) {
                    // 忽略
                }
                try {
                    output.close()
                } catch (e: Exception) {
                    // 忽略
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            tun.close()
        } catch (e: Exception) {
            // 忽略
        }
        worker?.join(800)
        worker = null
    }

    private fun handle(packet: ByteArray, length: Int): ByteArray? {
        if (length < 20) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if ((packet[9].toInt() and 0xFF) != 17) return null
        val srcPort = readU16(packet, ihl)
        val dstPort = readU16(packet, ihl + 2)
        if (dstPort != 53) return null
        val udpLength = readU16(packet, ihl + 4)
        if (udpLength < 8 || ihl + udpLength > length) return null
        val query = packet.copyOfRange(ihl + 8, ihl + udpLength)
        val answer = resolve(query) ?: return null
        return buildPacket(packet, ihl, srcPort, answer)
    }

    private fun resolve(query: ByteArray): ByteArray? {
        for (upstream in upstreams) {
            val socket = DatagramSocket()
            try {
                vpn.protect(socket)
                socket.soTimeout = 3000
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName(upstream), 53))
                val response = DatagramPacket(ByteArray(4096), 4096)
                socket.receive(response)
                return response.data.copyOfRange(response.offset, response.offset + response.length)
            } catch (e: Exception) {
                // 试下一个上游
            } finally {
                socket.close()
            }
        }
        return null
    }

    private fun buildPacket(queryPacket: ByteArray, ihl: Int, srcPort: Int, dns: ByteArray): ByteArray {
        val udpLength = 8 + dns.size
        val total = 20 + udpLength
        val out = ByteArray(total)
        out[0] = 0x45
        out[2] = ((total ushr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()
        out[4] = queryPacket[4]
        out[5] = queryPacket[5]
        out[6] = 0x40
        out[7] = 0
        out[8] = 64
        out[9] = 17
        // 交换源/目的 IPv4
        System.arraycopy(queryPacket, 16, out, 12, 4)
        System.arraycopy(queryPacket, 12, out, 16, 4)
        val checksum = ipChecksum(out, 0, 20)
        out[10] = ((checksum ushr 8) and 0xFF).toByte()
        out[11] = (checksum and 0xFF).toByte()
        writeU16(out, 20, 53)
        writeU16(out, 22, srcPort)
        writeU16(out, 24, udpLength)
        writeU16(out, 26, 0)
        System.arraycopy(dns, 0, out, 28, dns.size)
        return out
    }

    private fun ipChecksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length) {
            sum += readU16(bytes, i).toLong()
            i += 2
        }
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv().toInt() and 0xFFFF)
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }
}
