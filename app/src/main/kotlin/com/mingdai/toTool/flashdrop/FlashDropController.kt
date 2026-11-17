package com.mingdai.toTool.flashdrop

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.mingdai.toTool.data.Prefs
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class FlashDropState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val port: Int? = null,
    val code: String = "",
    val addresses: List<String> = emptyList(),
    val customCodeEnabled: Boolean = false,
    val customCode: String = "",
    val encryptionEnabled: Boolean = false,
    val pairedClients: Int = 0,
    val storageGranted: Boolean = false,
    val error: String? = null,
)

/**
 * 管理隔空闪传服务生命周期与设置。
 * 服务只在工具页显示期间运行；离开页面时停止，避免后台常驻。
 */
class FlashDropController(
    context: Context,
    private val prefs: Prefs,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var server: FlashDropServer? = null

    @Volatile
    private var wantRunning = false

    @Volatile
    private var starting = false

    var state by mutableStateOf(
        FlashDropState(
            customCodeEnabled = customCodeUsable(),
            customCode = prefs.flashDropCustomCode(),
            encryptionEnabled = prefs.flashDropEncryptionEnabled(),
            storageGranted = hasStorageAccess(),
        ),
    )
        private set

    private val listener = object : FlashDropServer.Listener {
        override fun onCodeChanged(code: String) {
            mainHandler.post {
                if (wantRunning) state = state.copy(code = code)
            }
        }

        override fun onClientsChanged(count: Int) {
            mainHandler.post {
                if (wantRunning) state = state.copy(pairedClients = count)
            }
        }

        override fun onError(message: String) {
            mainHandler.post {
                if (wantRunning) state = state.copy(error = message)
            }
        }
    }

    fun start() {
        if (wantRunning && (starting || server != null)) return
        wantRunning = true
        starting = true
        state = state.copy(starting = true, error = null)
        Thread({
            try {
                val next = FlashDropServer.startOnFreePort(
                    context = appContext,
                    customCodeEnabled = customCodeUsable(),
                    customCode = prefs.flashDropCustomCode(),
                    encryptionEnabled = prefs.flashDropEncryptionEnabled(),
                    listener = listener,
                )
                mainHandler.post {
                    starting = false
                    if (!wantRunning) {
                        next.stop()
                        return@post
                    }
                    server = next
                    state = state.copy(
                        running = true,
                        starting = false,
                        port = next.runningPort,
                        code = next.code,
                        addresses = localAddresses(),
                        pairedClients = next.pairedCount,
                        encryptionEnabled = next.encryptionActive,
                        storageGranted = hasStorageAccess(),
                        error = null,
                    )
                }
            } catch (error: Exception) {
                mainHandler.post {
                    starting = false
                    state = state.copy(
                        running = false,
                        starting = false,
                        error = "无法启动服务：${error.message ?: "端口 2233-2333 都被占用"}",
                    )
                }
            }
        }, "flashdrop-start").start()
    }

    fun stop() {
        wantRunning = false
        starting = false
        server?.stop()
        server = null
        state = state.copy(
            running = false,
            starting = false,
            port = null,
            code = "",
            addresses = emptyList(),
            pairedClients = 0,
        )
    }

    fun restart() {
        stop()
        start()
    }

    fun refresh() {
        state = state.copy(
            addresses = if (state.running) localAddresses() else emptyList(),
            storageGranted = hasStorageAccess(),
        )
    }

    /** 返回是否已接受自定义码。 */
    fun updateCustomCode(enabled: Boolean, code: String): Boolean {
        val clean = code.filter(Char::isDigit).take(6)
        if (enabled && clean.length != 6) return false
        prefs.setFlashDropCustomCodeEnabled(enabled)
        prefs.setFlashDropCustomCode(clean)
        server?.updateCustomCode(enabled, clean)
        state = state.copy(
            customCodeEnabled = enabled,
            customCode = clean,
            code = when {
                enabled -> clean
                server != null -> server!!.code
                else -> state.code
            },
        )
        return true
    }

    fun updateEncryption(enabled: Boolean) {
        prefs.setFlashDropEncryptionEnabled(enabled)
        server?.updateEncryption(enabled)
        state = state.copy(encryptionEnabled = enabled, pairedClients = 0)
    }

    fun clipboardMessages(): List<ClipboardMessage> = server?.clipboardMessages() ?: emptyList()

    fun syncClipboard(): List<ClipboardMessage> = server?.syncClipboard() ?: emptyList()

    fun sendClipboardFromPhone(text: String): Boolean = server?.sendClipboardFromPhone(text) == true

    fun receivedFiles(): List<File> {
        if (!hasStorageAccess()) return emptyList()
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "toTool/FlashDrop",
        )
        return dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun primaryUrl(): String? {
        val address = state.addresses.firstOrNull() ?: return null
        val port = state.port ?: return null
        return "http://$address:$port"
    }

    fun storageUsed(): Long {
        val total = storageTotal()
        return total - storageFree()
    }

    fun storageTotal(): Long = runCatching {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        stat.blockCountLong * stat.blockSizeLong
    }.getOrDefault(0L)

    private fun storageFree(): Long = runCatching {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    private fun customCodeUsable(): Boolean =
        prefs.flashDropCustomCodeEnabled() && prefs.flashDropCustomCode().length == 6

    private fun hasStorageAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun localAddresses(): List<String> {
        val result = linkedSetOf<String>()
        runCatching {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork
            val properties = manager.getLinkProperties(network)
            properties?.linkAddresses?.forEach { link ->
                val address = link.address
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    address.hostAddress?.let(result::add)
                }
            }
        }
        if (result.isEmpty()) {
            runCatching {
                for (networkInterface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!networkInterface.isUp || networkInterface.isLoopback) continue
                    for (address in Collections.list(networkInterface.inetAddresses)) {
                        if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                            address.hostAddress?.let(result::add)
                        }
                    }
                }
            }
        }
        return result.toList()
    }
}
