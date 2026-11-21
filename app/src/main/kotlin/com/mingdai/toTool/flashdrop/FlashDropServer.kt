package com.mingdai.toTool.flashdrop

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.drawable.toBitmap
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.response.header
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class PairRequestInfo(
    val id: String,
    val remoteHost: String,
    val userAgent: String,
    val createdAt: Long,
    /** 网页端展示的两位数；手机端要从 [options] 中选出它。 */
    val number: Int,
    val options: List<Int>,
)

data class ClipboardMessage(
    val id: String,
    val text: String,
    val source: String,
    val createdAt: String,
)

/**
 * 隔空闪传的手机端服务（Ktor CIO）。
 *
 * - 端口从 [PORT_START] 递增到 [PORT_END]
 * - 静态页面来自 assets/flashdrop（CI 构建 web/dist 后放进来）
 * - 所有 /api 接口同源；除配对、信息、健康检查外都需要 Bearer token
 * - 配对码默认每次成功配对后轮换；自定义配对码不轮换
 * - 可选的 AES-GCM 文件内容加密，密钥由配对码 + 随机 salt 经 PBKDF2 派生
 */
class FlashDropServer private constructor(
    context: Context,
    private val port: Int,
    initialCustomCodeEnabled: Boolean,
    initialCustomCode: String,
    initialEncryptionEnabled: Boolean,
    private val listener: Listener,
) {

    interface Listener {
        fun onCodeChanged(code: String)
        fun onClientsChanged(count: Int)
        fun onPairRequestsChanged(requests: List<PairRequestInfo>) {}
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val secureRandom = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val iconCache = LruCache<String, ByteArray>(4 * 1024 * 1024)
    private val thumbCache = LruCache<String, ByteArray>(12 * 1024 * 1024)
    private val transfers = Collections.synchronizedList(mutableListOf<TransferRecord>())
    private val failedAttempts = mutableListOf<Long>()
    private val clipboardHistory = Collections.synchronizedList(mutableListOf<ClipboardMessage>())
    private val sockets = Collections.synchronizedSet(mutableSetOf<WebSocketSession>())
    private val categoryCache = ConcurrentHashMap<String, CategoryCacheEntry>()
    private val pairRequests = ConcurrentHashMap<String, PendingPairRequest>()
    private val blockedPairDevices = Collections.synchronizedSet(mutableSetOf<String>())
    private val pairRequestTimes = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastClipboardText = ""

    @Volatile
    private var appsCache: List<AppInfo>? = null

    @Volatile
    private var appsCacheAt = 0L

    @Volatile
    private var engine: EmbeddedServer<*, *>? = null

    @Volatile
    private var customCodeEnabled = initialCustomCodeEnabled

    @Volatile
    private var customCode = initialCustomCode.filter(Char::isDigit).take(6)

    @Volatile
    private var encryptionEnabled = initialEncryptionEnabled

    @Volatile
    private var currentCode: String =
        if (initialCustomCodeEnabled && customCode.length == 6) customCode else generateCode()

    private data class Session(
        val token: String,
        val expiresAt: Long,
        val encryptionKey: SecretKey?,
    )

    private data class PendingPairRequest(
        val id: String,
        val remoteHost: String,
        val userAgent: String,
        val deviceKey: String,
        val createdAt: Long,
        val number: Int,
        val options: List<Int>,
        @Volatile var status: String = "pending",
        @Volatile var response: JSONObject? = null,
    )

    private data class CategoryCacheEntry(
        val cachedAt: Long,
        val files: List<File>,
    )

    private data class TransferRecord(
        val id: String,
        val name: String,
        val size: Long,
        val direction: String,
        val progress: Int,
        val status: String,
        val createdAt: String,
        val path: String? = null,
    )

    val code: String get() = currentCode
    val runningPort: Int get() = port
    val encryptionActive: Boolean get() = encryptionEnabled
    val pairedCount: Int get() = sessions.size

    fun stop() {
        sessions.clear()
        pairRequests.clear()
        blockedPairDevices.clear()
        pairRequestTimes.clear()
        listener.onPairRequestsChanged(emptyList())
        listener.onClientsChanged(0)
        runCatching { engine?.stop(0, 0) }
        engine = null
    }

    fun updateCustomCode(enabled: Boolean, value: String) {
        val clean = value.filter(Char::isDigit).take(6)
        customCodeEnabled = enabled && clean.length == 6
        customCode = clean
        currentCode = if (customCodeEnabled) clean else generateCode()
        listener.onCodeChanged(currentCode)
    }

    fun updateEncryption(enabled: Boolean) {
        if (encryptionEnabled == enabled) return
        encryptionEnabled = enabled
        // 旧 session 的密钥和当前设置不一致，直接让它们重新配对
        sessions.clear()
        listener.onClientsChanged(0)
    }

    fun approvePairRequest(id: String, selectedNumber: Int): Boolean {
        val now = System.currentTimeMillis()
        val request = synchronized(pairRequests) {
            val found = pairRequests[id] ?: return false
            if (found.status != "pending") return false
            when {
                now - found.createdAt > PAIR_REQUEST_TTL_MS -> {
                    found.status = "expired"
                    null
                }
                found.number != selectedNumber -> {
                    // 数字不匹配就不能完成配对，同时结束这次请求，避免反复试错。
                    found.status = "denied"
                    null
                }
                else -> found
            }
        } ?: run {
            notifyPairRequests()
            return false
        }
        val token = randomToken()
        val keyBytes = if (encryptionEnabled) ByteArray(32).also(secureRandom::nextBytes) else null
        val sessionKey = keyBytes?.let { SecretKeySpec(it, "AES") }
        sessions[token] = Session(token, now + TOKEN_TTL_MS, sessionKey)
        listener.onClientsChanged(sessions.size)
        val response = JSONObject()
            .put("token", token)
            .put("port", port)
            .put("device", deviceJson())
            .put(
                "encryption",
                JSONObject().put("enabled", sessionKey != null).apply {
                    keyBytes?.let { put("key", Base64.encodeToString(it, Base64.NO_WRAP)) }
                },
            )
        synchronized(pairRequests) {
            request.response = response
            request.status = "approved"
        }
        notifyPairRequests()
        return true
    }

    fun denyPairRequest(id: String): Boolean {
        val changed = synchronized(pairRequests) {
            val request = pairRequests[id] ?: return false
            if (request.status != "pending") return false
            request.status = "denied"
            true
        }
        if (changed) notifyPairRequests()
        return changed
    }

    fun blockPairRequest(id: String): Boolean {
        val changed = synchronized(pairRequests) {
            val request = pairRequests[id] ?: return false
            if (request.status != "pending") return false
            request.status = "blocked"
            blockedPairDevices.add(request.deviceKey)
            true
        }
        if (changed) notifyPairRequests()
        return changed
    }

    fun pendingPairRequests(): List<PairRequestInfo> = synchronized(pairRequests) {
        pairRequests.values
            .filter { it.status == "pending" }
            .sortedBy { it.createdAt }
            .map { PairRequestInfo(it.id, it.remoteHost, it.userAgent, it.createdAt, it.number, it.options) }
    }

    private fun notifyPairRequests() {
        listener.onPairRequestsChanged(pendingPairRequests())
    }

    private fun configureServer(application: Application) {
        application.install(WebSockets)
        application.routing {
            webSocket("/ws") {
                val token = call.request.queryParameters["token"]
                val session = token?.let { sessions[it] }
                if (session == null || session.expiresAt < System.currentTimeMillis()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                    return@webSocket
                }
                sockets.add(this)
                try {
                    send(Frame.Text("""{"type":"connected"}"""))
                    for (frame in incoming) {
                        val live = sessions[token]
                        if (live == null || live.expiresAt < System.currentTimeMillis()) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "session-ended"))
                            break
                        }
                        if (frame is Frame.Text && frame.readText() == "ping") {
                            send(Frame.Text("pong"))
                        }
                    }
                } finally {
                    sockets.remove(this)
                }
            }
            route("/") { handle { dispatch(call) } }
            route("/{path...}") { handle { dispatch(call) } }
        }
    }

    private suspend fun dispatch(call: ApplicationCall) {
        try {
            val path = call.request.path()
            if (path.startsWith("/api/")) handleApi(call, path) else serveStatic(call, path)
        } catch (error: Exception) {
            Log.w(TAG, "request failed: ${call.request.path()}", error)
            if (!call.response.isSent) {
                respondJson(call, HttpStatusCode.InternalServerError, JSONObject().put("error", "server-error"))
            }
        }
    }

    /* ------------------------------ 配对与设置 ------------------------------ */

    private suspend fun handlePair(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Post) {
            methodNotAllowed(call)
            return
        }
        val body = runCatching { readJson(call) }.getOrNull()
        if (body == null) {
            respondJson(call, HttpStatusCode.BadRequest, JSONObject().put("error", "bad-json"))
            return
        }
        val code = body.optString("code").replace(" ", "").trim()
        if (code.length != 6 || code.any { !it.isDigit() }) {
            pairingRejected(call)
            return
        }

        val rateLimited = synchronized(failedAttempts) {
            val now = System.currentTimeMillis()
            failedAttempts.removeAll { it < now - ATTEMPT_WINDOW_MS }
            failedAttempts.size >= MAX_ATTEMPTS
        }
        if (rateLimited) {
            call.response.header("Retry-After", "60")
            respondJson(call, HttpStatusCode.TooManyRequests, JSONObject().put("error", "rate-limited"))
            return
        }

        if (!MessageDigest.isEqual(currentCode.toByteArray(), code.toByteArray())) {
            synchronized(failedAttempts) { failedAttempts += System.currentTimeMillis() }
            pairingRejected(call)
            return
        }

        synchronized(failedAttempts) { failedAttempts.clear() }

        val token = randomToken()
        val encrypt = encryptionEnabled
        val salt = if (encrypt) ByteArray(SALT_BYTES).also(secureRandom::nextBytes) else null
        val encryptionKey = salt?.let { deriveKey(code, it) }
        sessions[token] = Session(token, System.currentTimeMillis() + TOKEN_TTL_MS, encryptionKey)
        listener.onClientsChanged(sessions.size)

        if (!customCodeEnabled) {
            currentCode = generateCode()
            listener.onCodeChanged(currentCode)
        }

        val response = JSONObject()
            .put("token", token)
            .put("port", port)
            .put("device", deviceJson())
        val encryptionJson = JSONObject().put("enabled", encrypt)
        if (salt != null) {
            // 不把密钥本身发给浏览器：双方用配对码 + 同一个 salt 各自派生
            encryptionJson
                .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .put("iterations", PBKDF2_ITERATIONS)
        }
        response.put("encryption", encryptionJson)
        respondJson(call, HttpStatusCode.OK, response)
    }

    private suspend fun handlePairRequest(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Post) {
            methodNotAllowed(call)
            return
        }
        val remoteHost = runCatching { call.request.local.remoteHost }.getOrDefault("unknown")
        val userAgent = header(call, "User-Agent").orEmpty().take(300)
        val deviceKey = MessageDigest.getInstance("SHA-256")
            .digest("$remoteHost\n$userAgent".toByteArray())
            .let { Base64.encodeToString(it, Base64.NO_WRAP) }
        if (deviceKey in blockedPairDevices) {
            respondJson(call, HttpStatusCode.Forbidden, JSONObject().put("error", "pair-request-blocked"))
            return
        }
        val now = System.currentTimeMillis()
        val last = pairRequestTimes[deviceKey] ?: 0L
        if (now - last < 1_500L) {
            respondJson(call, HttpStatusCode.TooManyRequests, JSONObject().put("error", "rate-limited"))
            return
        }
        pairRequestTimes[deviceKey] = now
        if (pairRequests.values.count { it.status == "pending" } >= 20) {
            respondJson(call, HttpStatusCode.TooManyRequests, JSONObject().put("error", "too-many-requests"))
            return
        }
        val request = synchronized(pairRequests) {
            pairRequests.values.firstOrNull { it.deviceKey == deviceKey && it.status == "pending" }
                ?: run {
                    val (number, options) = generatePairChallenge()
                    val created = PendingPairRequest(
                        id = randomToken(),
                        remoteHost = remoteHost,
                        userAgent = userAgent,
                        deviceKey = deviceKey,
                        createdAt = now,
                        number = number,
                        options = options,
                    )
                    pairRequests[created.id] = created
                    created
                }
        }
        notifyPairRequests()
        respondJson(
            call,
            HttpStatusCode.OK,
            JSONObject()
                .put("id", request.id)
                .put("status", request.status)
                .put("number", request.number),
        )
    }

    private suspend fun handlePairRequestStatus(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Get) {
            methodNotAllowed(call)
            return
        }
        val id = call.request.queryParameters["id"].orEmpty()
        val request = pairRequests[id]
        if (request == null) {
            respondJson(call, HttpStatusCode.NotFound, JSONObject().put("error", "pair-request-not-found"))
            return
        }
        if (request.status == "pending" && System.currentTimeMillis() - request.createdAt > PAIR_REQUEST_TTL_MS) {
            synchronized(pairRequests) { request.status = "expired" }
            notifyPairRequests()
        }
        val body = JSONObject().put("status", request.status)
        if (request.status == "approved") {
            request.response?.let { response ->
                response.keys().forEach { key -> body.put(key, response.get(key)) }
            }
        }
        respondJson(call, HttpStatusCode.OK, body)
    }

    private suspend fun handleSession(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Get) {
            methodNotAllowed(call)
            return
        }
        val authenticated = authenticated(call) ?: return unauthorized(call)
        respondJson(
            call,
            HttpStatusCode.OK,
            JSONObject()
                .put("paired", true)
                .put("encryptionEnabled", authenticated.encryptionKey != null),
        )
    }

    private suspend fun handleLogout(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Post) {
            methodNotAllowed(call)
            return
        }
        val token = header(call, "Authorization")?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrEmpty() || sessions.remove(token) == null) {
            unauthorized(call)
            return
        }
        listener.onClientsChanged(sessions.size)
        respondJson(call, HttpStatusCode.OK, JSONObject().put("ok", true))
    }

    private suspend fun pairingRejected(call: ApplicationCall) {
        respondJson(call, HttpStatusCode.Unauthorized, JSONObject().put("error", "invalid-code"))
    }

    /* -------------------------------- API -------------------------------- */

    private suspend fun handleApi(call: ApplicationCall, path: String) {
        when {
            path == "/api/info" -> infoResponse(call)
            path == "/api/health" -> respondJson(
                call,
                HttpStatusCode.OK,
                JSONObject().put("ok", true).put("service", "flashdrop"),
            )
            path == "/api/pair" -> handlePair(call)
            path == "/api/pair/request" -> handlePairRequest(call)
            path == "/api/pair/request/status" -> handlePairRequestStatus(call)
            path == "/api/session" -> handleSession(call)
            path == "/api/logout" -> handleLogout(call)
            path == "/api/apps" -> handleApps(call)
            path == "/api/files" -> handleFiles(call)
            path == "/api/files/delete" -> handleDeleteFiles(call)
            path == "/api/text" -> handleText(call)
            path == "/api/transfers" -> handleTransfers(call)
            path == "/api/transfers/delete" -> handleDeleteTransfer(call)
            path == "/api/clipboard" -> handleClipboard(call)
            path == "/api/upload" -> handleUpload(call)
            path == "/api/download" -> handleDownload(call)
            path == "/api/media" -> handleDownload(call, inline = true)
            path == "/api/icon" -> handleIcon(call)
            path == "/api/thumb" -> handleThumb(call)
            else -> respondJson(call, HttpStatusCode.NotFound, JSONObject().put("error", "not-found"))
        }
    }

    private suspend fun infoResponse(call: ApplicationCall) {
        respondJson(
            call,
            HttpStatusCode.OK,
            JSONObject()
                .put("port", port)
                .put("device", deviceJson())
                .put("encryptionEnabled", encryptionEnabled),
        )
    }

    private suspend fun handleApps(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Get) {
            methodNotAllowed(call)
            return
        }
        if (authenticated(call) == null) return unauthorized(call)
        val scope = call.request.queryParameters["scope"].orEmpty()
        val query = call.request.queryParameters["q"].orEmpty().trim()
        val all = installedApps()
        val filtered = all.asSequence()
            .filter { scope == "all" || scope.isEmpty() || it.scope == scope }
            .filter {
                query.isEmpty() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .toList()
        val counts = JSONObject()
            .put("all", all.size)
            .put("system", all.count { it.scope == "system" })
            .put("user", all.count { it.scope == "user" })
        val items = JSONArray()
        filtered.forEach { app ->
            items.put(
                JSONObject()
                    .put("type", "app")
                    .put("id", app.packageName)
                    .put("name", app.name)
                    .put("version", app.version)
                    .put("packageName", app.packageName)
                    .put("size", app.size)
                    .put("scope", app.scope)
                    .put("installedAt", app.installedAt)
                    .put("modifiedAt", app.modifiedAt)
                    .put("iconUrl", "/api/icon?package=${urlEncode(app.packageName)}"),
            )
        }
        respondJson(call, HttpStatusCode.OK, JSONObject().put("items", items).put("counts", counts))
    }

    private suspend fun handleFiles(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Get) {
            methodNotAllowed(call)
            return
        }
        if (authenticated(call) == null) return unauthorized(call)
        if (!hasStorageAccess()) {
            respondJson(call, HttpStatusCode.Forbidden, JSONObject().put("error", "storage-permission"))
            return
        }
        val category = call.request.queryParameters["category"].orEmpty()
        val path = call.request.queryParameters["path"].orEmpty()
        val query = call.request.queryParameters["q"].orEmpty().trim()
        val force = call.request.queryParameters["refresh"] == "1"
        val sort = call.request.queryParameters["sort"].orEmpty().takeIf { it in setOf("modified", "name", "size") } ?: "modified"

        val files = withContext(Dispatchers.IO) {
            when (category) {
                "storage" -> listStorage(path, query, sort, force)
                "photos", "videos", "audio", "documents", "archives", "apks", "downloads" ->
                    scanCategory(category, query, sort, force)
                else -> null
            }
        } ?: run {
            respondJson(call, HttpStatusCode.BadRequest, JSONObject().put("error", "bad-category"))
            return
        }
        val total = files.size
        val totalPages = maxOf(1, (total + PAGE_SIZE - 1) / PAGE_SIZE)
        val requestedPage = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val page = requestedPage.coerceIn(1, totalPages)
        val from = (page - 1) * PAGE_SIZE
        val to = minOf(total, from + PAGE_SIZE)
        val items = JSONArray()
        if (from < to) {
            files.subList(from, to).forEach { file ->
                if (file.isDirectory) items.put(folderJson(file)) else items.put(fileJson(file))
            }
        }
        respondJson(
            call,
            HttpStatusCode.OK,
            JSONObject()
                .put("items", items)
                .put("total", total)
                .put("page", page)
                .put("pageSize", PAGE_SIZE),
        )
    }

    private suspend fun handleText(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Get) {
            methodNotAllowed(call)
            return
        }
        if (authenticated(call) == null) return unauthorized(call)
        if (!hasStorageAccess()) {
            respondJson(call, HttpStatusCode.Forbidden, JSONObject().put("error", "storage-permission"))
            return
        }
        val path = call.request.queryParameters["path"].orEmpty()
        val file = safeFile(path)?.takeIf { it.isFile }
        if (file == null) {
            respondJson(call, HttpStatusCode.NotFound, JSONObject().put("error", "file-not-found"))
            return
        }
        if (!file.canRead()) {
            respondJson(call, HttpStatusCode.Forbidden, JSONObject().put("error", "cannot-read"))
            return
        }
        if (file.length() > TEXT_PREVIEW_LIMIT) {
            respondJson(call, HttpStatusCode.PayloadTooLarge, JSONObject().put("error", "text-too-large"))
            return
        }
        if (!looksLikeText(file)) {
            respondJson(call, HttpStatusCode.UnsupportedMediaType, JSONObject().put("error", "not-text"))
            return
        }
        val text = decodeText(file.readBytes())
        if (text == null) {
            respondJson(call, HttpStatusCode.UnsupportedMediaType, JSONObject().put("error", "not-text"))
            return
        }
        respondJson(call, HttpStatusCode.OK, JSONObject().put("text", text))
    }

    private suspend fun handleDeleteFiles(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Post) {
            methodNotAllowed(call)
            return
        }
        if (authenticated(call) == null) return unauthorized(call)
        if (!hasStorageAccess()) {
            respondJson(call, HttpStatusCode.Forbidden, JSONObject().put("error", "storage-permission"))
            return
        }
        val body = runCatching { readJson(call) }.getOrNull()
        val paths = body?.optJSONArray("paths")
        if (paths == null || paths.length() == 0) {
            respondJson(call, HttpStatusCode.BadRequest, JSONObject().put("error", "missing-paths"))
            return
        }
        val deleted = JSONArray()
        val failed = JSONArray()
        for (index in 0 until minOf(paths.length(), 100)) {
            val relative = paths.optString(index)
            val file = safeFile(relative)
            if (file == null) {
                failed.put(relative)
                continue
            }
            val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (ok) deleted.put(relative) else failed.put(relative)
        }
        if (deleted.length() > 0) invalidateFileCache()
        respondJson(
            call,
            HttpStatusCode.OK,
            JSONObject().put("deleted", deleted).put("failed", failed),
        )
    }

    private suspend fun handleTransfers(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Get) {
            methodNotAllowed(call)
            return
        }
        if (authenticated(call) == null) return unauthorized(call)
        val items = JSONArray()
        synchronized(transfers) {
            transfers.take(80).forEach { transfer ->
                items.put(transferJson(transfer))
            }
        }
        respondJson(call, HttpStatusCode.OK, JSONObject().put("items", items))
    }

    private suspend fun handleDeleteTransfer(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Post) {
            methodNotAllowed(call)
            return
        }
        if (authenticated(call) == null) return unauthorized(call)
        val body = runCatching { readJson(call) }.getOrNull()
        val id = body?.optString("id").orEmpty()
        if (id.isEmpty()) {
            respondJson(call, HttpStatusCode.BadRequest, JSONObject().put("error", "missing-id"))
            return
        }
        synchronized(transfers) {
            transfers.removeAll { it.id == id }
        }
        respondJson(call, HttpStatusCode.OK, JSONObject().put("ok", true))
    }

    private fun transferJson(transfer: TransferRecord): JSONObject = JSONObject()
        .put("id", transfer.id)
        .put("name", transfer.name)
        .put("size", transfer.size)
        .put("direction", transfer.direction)
        .put("progress", transfer.progress)
        .put("status", transfer.status)
        .put("createdAt", transfer.createdAt)
        .apply { transfer.path?.let { put("path", it) } }

    private suspend fun handleClipboard(call: ApplicationCall) {
        if (authenticated(call) == null) return unauthorized(call)
        if (call.request.httpMethod == HttpMethod.Post) {
            val body = runCatching { readJson(call) }.getOrNull()
            if (body == null) {
                respondJson(call, HttpStatusCode.BadRequest, JSONObject().put("error", "bad-json"))
                return
            }
            val text = body.optString("text")
            if (text.isEmpty() || text.length > 100_000) {
                respondJson(call, HttpStatusCode.BadRequest, JSONObject().put("error", "bad-text"))
                return
            }
            if (writeClipboard(text)) {
                lastClipboardText = text
                val message = addClipboardMessage(text, "web")
                notifyClipboard(message)
                respondJson(call, HttpStatusCode.OK, JSONObject().put("item", clipboardJson(message)))
            } else {
                respondJson(call, HttpStatusCode.InternalServerError, JSONObject().put("error", "clipboard-denied"))
            }
            return
        }
        if (call.request.httpMethod != HttpMethod.Get) {
            methodNotAllowed(call)
            return
        }

        syncSystemClipboard()
        val items = JSONArray()
        clipboardMessages().forEach { message -> items.put(clipboardJson(message)) }
        respondJson(call, HttpStatusCode.OK, JSONObject().put("items", items))
    }

    fun clipboardMessages(): List<ClipboardMessage> =
        synchronized(clipboardHistory) { clipboardHistory.toList() }

    fun syncClipboard(): List<ClipboardMessage> {
        syncSystemClipboard()
        return clipboardMessages()
    }

    fun sendClipboardFromPhone(text: String): Boolean {
        if (text.isBlank() || !writeClipboard(text)) return false
        lastClipboardText = text
        val message = addClipboardMessage(text, "phone")
        notifyClipboard(message)
        return true
    }

    private fun syncSystemClipboard() {
        val text = readClipboard()?.trim().orEmpty()
        if (text.isEmpty() || text == lastClipboardText) return
        lastClipboardText = text
        val message = addClipboardMessage(text, "phone")
        notifyClipboard(message)
    }

    private fun addClipboardMessage(text: String, source: String): ClipboardMessage {
        val message = ClipboardMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            source = source,
            createdAt = formatModified(System.currentTimeMillis()),
        )
        synchronized(clipboardHistory) {
            clipboardHistory.add(message)
            while (clipboardHistory.size > 100) clipboardHistory.removeAt(0)
        }
        return message
    }

    private fun clipboardJson(message: ClipboardMessage): JSONObject = JSONObject()
        .put("id", message.id)
        .put("text", message.text)
        .put("source", message.source)
        .put("createdAt", message.createdAt)

    private fun notifyClipboard(message: ClipboardMessage) {
        val payload = JSONObject()
            .put("type", "clipboard")
            .put("item", clipboardJson(message))
            .toString()
        val targets = synchronized(sockets) { sockets.toList() }
        targets.forEach { socket ->
            socket.launch {
                runCatching { socket.send(Frame.Text(payload)) }
            }
        }
    }

    /* ------------------------------ 文件列表 ------------------------------ */

    private fun listStorage(relativePath: String, query: String, sort: String, force: Boolean): List<File> {
        val root = externalRoot()
        val dir = if (relativePath.isBlank()) root else safeFile(relativePath)
        if (dir == null || !dir.isDirectory) return emptyList()
        val key = "storage|$relativePath|${query.lowercase(Locale.ROOT)}|$sort"
        return cachedFiles(key, force) {
            dir.listFiles()
                ?.filter { !it.name.startsWith(".") }
                ?.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
                ?.sortedWith(Comparator { left, right ->
                    if (left.isDirectory != right.isDirectory) {
                        if (left.isDirectory) -1 else 1
                    } else {
                        fileComparator(sort).compare(left, right)
                    }
                })
                .orEmpty()
        }
    }

    private fun scanCategory(category: String, query: String, sort: String, force: Boolean): List<File> {
        val key = "category|$category|${query.lowercase(Locale.ROOT)}|$sort"
        return cachedFiles(key, force) {
            val roots = when (category) {
                "photos" -> listOf("DCIM", "Pictures", "Download")
                "videos" -> listOf("DCIM", "Movies", "Download")
                "audio" -> listOf("Music", "Recordings", "Download")
                "documents" -> listOf("Documents", "Download")
                "archives" -> listOf("Download", "Documents")
                "apks", "downloads" -> listOf("Download")
                else -> emptyList()
            }
            val extensions = extensionsFor(category)
            val found = mutableListOf<File>()
            val visited = HashSet<String>()
            val queue = ArrayDeque<File>()
            roots.map { File(externalRoot(), it) }.filter { it.isDirectory }.forEach(queue::addLast)
            while (queue.isNotEmpty()) {
                val dir = queue.removeFirst()
                val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: continue
                if (!visited.add(canonical)) continue
                val children = dir.listFiles() ?: continue
                for (child in children) {
                    if (child.name.startsWith(".")) continue
                    if (Files.isSymbolicLink(child.toPath())) continue
                    if (child.isDirectory) {
                        queue.addLast(child)
                    } else if ((category == "downloads" || child.extension.lowercase(Locale.ROOT) in extensions) &&
                        (query.isEmpty() || child.name.contains(query, ignoreCase = true))
                    ) {
                        found += child
                    }
                }
            }
            found.sortWith(fileComparator(sort))
            found
        }
    }

    private fun fileComparator(sort: String): Comparator<File> = when (sort) {
        "name" -> {
            val collator = java.text.Collator.getInstance(Locale.CHINA)
            Comparator { left, right -> collator.compare(left.name, right.name) }
        }
        "size" -> compareByDescending { it.length() }
        else -> compareByDescending { it.lastModified() }
    }

    /** 分类/目录结果缓存：翻页时无需重新扫描，手动刷新和增删文件时失效。 */
    private fun cachedFiles(key: String, force: Boolean, loader: () -> List<File>): List<File> {
        val now = System.currentTimeMillis()
        if (!force) {
            categoryCache[key]?.takeIf { now - it.cachedAt <= CATEGORY_CACHE_MS }?.let { return it.files }
        }
        val files = loader()
        categoryCache[key] = CategoryCacheEntry(now, files)
        if (categoryCache.size > 32) {
            categoryCache.entries.removeIf { now - it.value.cachedAt > CATEGORY_CACHE_MS }
        }
        return files
    }

    fun invalidateFileCache() {
        categoryCache.clear()
    }

    private fun folderJson(file: File): JSONObject {
        val relative = relativePath(file)
        return JSONObject()
            .put("type", "folder")
            .put("id", relative)
            .put("name", file.name)
            .put("size", 0)
            .put("itemCount", file.listFiles()?.count { !it.name.startsWith(".") } ?: 0)
            .put("modified", formatModified(file.lastModified()))
            .put("modifiedAt", file.lastModified())
            .put("path", relative)
    }

    private fun fileJson(file: File): JSONObject {
        val relative = relativePath(file)
        var kind = kindFor(file.name)
        if (kind == "file" && file.length() <= TEXT_PREVIEW_LIMIT && looksLikeText(file)) {
            kind = "text"
        }
        val json = JSONObject()
            .put("type", "file")
            .put("id", relative)
            .put("name", file.name)
            .put("kind", kind)
            .put("size", file.length())
            .put("modified", formatModified(file.lastModified()))
            .put("modifiedAt", file.lastModified())
            .put("path", relative)
        if (kind == "image" || kind == "video") {
            json.put("thumbUrl", "/api/thumb?path=${urlEncode(relative)}")
        }
        return json
    }

    /* ------------------------------ 应用列表 ------------------------------ */

    private data class AppInfo(
        val name: String,
        val packageName: String,
        val version: String,
        val size: Long,
        val scope: String,
        val installedAt: String,
        val modifiedAt: Long,
        val sourceDir: String,
    )

    @Suppress("DEPRECATION")
    private fun installedApps(): List<AppInfo> {
        val now = System.currentTimeMillis()
        appsCache?.takeIf { now - appsCacheAt < APP_CACHE_MS }?.let { return it }

        val pm = appContext.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getInstalledPackages(0)
        }
        val result = packages.mapNotNull { packageInfo ->
            runCatching {
                val info = packageInfo.applicationInfo ?: return@runCatching null
                val versionName = packageInfo.versionName.orEmpty()
                val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
                val size = buildList {
                    add(File(info.sourceDir).length())
                    info.splitSourceDirs?.forEach { add(File(it).length()) }
                }.sum()
                val system = (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                AppInfo(
                    name = pm.getApplicationLabel(info).toString().ifBlank { info.packageName },
                    packageName = info.packageName,
                    version = if (versionCode > 0) "$versionName($versionCode)" else versionName,
                    size = size,
                    scope = if (system) "system" else "user",
                    installedAt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(packageInfo.firstInstallTime)),
                    modifiedAt = packageInfo.firstInstallTime,
                    sourceDir = info.sourceDir,
                )
            }.getOrNull()
        }.sortedBy { it.name.lowercase(Locale.getDefault()) }

        appsCache = result
        appsCacheAt = now
        return result
    }

    private suspend fun handleIcon(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Get) {
            methodNotAllowed(call)
            return
        }
        if (authenticated(call, allowQuery = true) == null) return unauthorized(call)
        val packageName = call.request.queryParameters["package"].orEmpty()
        if (packageName.isEmpty()) {
            respondJson(call, HttpStatusCode.BadRequest, JSONObject().put("error", "missing-package"))
            return
        }
        val cached = iconCache.get(packageName)
        if (cached != null) {
            bytesResponse(call, ContentType.Image.PNG, cached)
            return
        }
        val pm = appContext.packageManager
        val bytes = runCatching {
            val info = pm.getApplicationInfo(packageName, 0)
            val bitmap = pm.getApplicationIcon(info).toBitmap(96, 96, Bitmap.Config.ARGB_8888)
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }.getOrNull()
        if (bytes == null) {
            respondJson(call, HttpStatusCode.NotFound, JSONObject().put("error", "icon-not-found"))
            return
        }
        iconCache.put(packageName, bytes)
        bytesResponse(call, ContentType.Image.PNG, bytes)
    }

    private suspend fun handleThumb(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Get) {
            methodNotAllowed(call)
            return
        }
        if (authenticated(call, allowQuery = true) == null) return unauthorized(call)
        val path = call.request.queryParameters["path"].orEmpty()
        val file = safeFile(path)?.takeIf { it.isFile }
        if (file == null) {
            respondJson(call, HttpStatusCode.NotFound, JSONObject().put("error", "file-not-found"))
            return
        }
        val key = "${file.absolutePath}:${file.lastModified()}"
        thumbCache.get(key)?.let {
            bytesResponse(call, ContentType.Image.JPEG, it)
            return
        }
        val bytes = runCatching {
            val bitmap = when (kindFor(file.name)) {
                "image" -> decodeSampledBitmap(file, 240)
                "video" -> videoThumbnail(file)
                else -> null
            } ?: return@runCatching null
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.toByteArray()
            }
        }.getOrNull()
        if (bytes == null) {
            respondJson(call, HttpStatusCode.NotFound, JSONObject().put("error", "thumb-not-found"))
            return
        }
        thumbCache.put(key, bytes)
        bytesResponse(call, ContentType.Image.JPEG, bytes)
    }

    /* ------------------------------ 上传下载 ------------------------------ */

    private suspend fun handleUpload(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Post) {
            methodNotAllowed(call)
            return
        }
        val authenticated = authenticated(call) ?: return unauthorized(call)
        if (!hasStorageAccess()) {
            respondJson(call, HttpStatusCode.Forbidden, JSONObject().put("error", "storage-permission"))
            return
        }
        val name = header(call, "X-FlashDrop-Name")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: "upload.bin"
        val encrypted = header(call, "X-FlashDrop-Encrypted") == "1"
        if (encrypted != (authenticated.encryptionKey != null)) {
            respondJson(
                call,
                HttpStatusCode.BadRequest,
                JSONObject().put("error", if (encrypted) "encryption-not-enabled" else "encryption-required"),
            )
            return
        }

        val temp = File.createTempFile("flashdrop-", ".upload", appContext.cacheDir)
        var target: File? = null
        try {
            val contentLength = call.request.contentLength()
            val output = FileOutputStream(temp)
            try {
                call.receiveChannel().copyTo(output, contentLength ?: Long.MAX_VALUE)
            } finally {
                output.close()
            }
            val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsRoot, "toTool/FlashDrop")
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                respondJson(call, HttpStatusCode.InternalServerError, JSONObject().put("error", "download-dir"))
                return
            }
            val saved = uniqueTarget(targetDir, sanitizeName(name))
            target = saved
            if (encrypted) {
                decryptToFile(temp, saved, authenticated.encryptionKey!!)
            } else {
                temp.copyTo(saved, overwrite = true)
            }
            invalidateFileCache()
            addTransfer(
                saved.name,
                saved.length(),
                "upload",
                header(call, "X-FlashDrop-Task-Id"),
                relativePath(saved),
            )
            respondJson(call, HttpStatusCode.OK, JSONObject().put("item", fileJson(saved)))
        } catch (error: Exception) {
            target?.delete()
            Log.w(TAG, "upload failed", error)
            respondJson(call, HttpStatusCode.InternalServerError, JSONObject().put("error", "upload-failed"))
        } finally {
            temp.delete()
        }
    }

    private suspend fun handleDownload(call: ApplicationCall, inline: Boolean = false) {
        if (call.request.httpMethod != HttpMethod.Get) {
            methodNotAllowed(call)
            return
        }
        val authenticated = authenticated(call, allowQuery = true) ?: return unauthorized(call)
        val preview = call.request.queryParameters["preview"] == "1"
        val packageName = call.request.queryParameters["package"]
        if (packageName.isNullOrBlank() && !hasStorageAccess()) {
            respondJson(call, HttpStatusCode.Forbidden, JSONObject().put("error", "storage-permission"))
            return
        }
        var archive: File? = null
        try {
            val file: File
            val downloadName: String
            if (!packageName.isNullOrBlank()) {
                val app = installedApps().firstOrNull { it.packageName == packageName }
                if (app == null) {
                    respondJson(call, HttpStatusCode.NotFound, JSONObject().put("error", "app-not-found"))
                    return
                }
                file = File(app.sourceDir)
                downloadName = "${sanitizeName(app.name)}.apk"
            } else {
                val path = call.request.queryParameters["path"].orEmpty()
                val resolved = safeFile(path)?.takeIf { it.isFile || it.isDirectory }
                if (resolved == null) {
                    respondJson(call, HttpStatusCode.NotFound, JSONObject().put("error", "file-not-found"))
                    return
                }
                if (resolved.isDirectory) {
                    val zipped = File.createTempFile("flashdrop-", ".zip", appContext.cacheDir)
                    archive = zipped
                    try {
                        withContext(Dispatchers.IO) { zipDirectory(resolved, zipped) }
                    } catch (error: Exception) {
                        Log.w(TAG, "zip folder failed", error)
                        respondJson(call, HttpStatusCode.InternalServerError, JSONObject().put("error", "zip-failed"))
                        return
                    }
                    file = zipped
                    downloadName = "${sanitizeName(resolved.name)}.zip"
                } else {
                    file = resolved
                    downloadName = file.name
                }
            }
            if (!file.canRead()) {
                respondJson(call, HttpStatusCode.Forbidden, JSONObject().put("error", "cannot-read"))
                return
            }
            if (!preview) {
                addTransfer(
                    downloadName,
                    file.length(),
                    "download",
                    header(call, "X-FlashDrop-Task-Id"),
                )
            }
            call.response.header("Content-Disposition", contentDisposition(downloadName, inline))
            call.response.header("Cache-Control", "no-store")
            addSecurityHeaders(call)
            val key = authenticated.encryptionKey
            val contentType = if (inline) ContentType.parse(mimeType(downloadName)) else ContentType.Application.OctetStream
            if (key != null) {
                call.response.header("X-FlashDrop-Encrypted", "1")
                call.respondOutputStream(
                    contentType,
                    HttpStatusCode.OK,
                    encryptedLength(file.length()),
                ) {
                    encryptFile(file, key, this)
                }
            } else {
                val range = if (inline) parseRange(header(call, "Range"), file.length()) else null
                if (range != null) {
                    call.response.header("Accept-Ranges", "bytes")
                    call.response.header("Content-Range", "bytes ${range.start}-${range.end}/${file.length()}")
                    call.respondOutputStream(
                        contentType,
                        HttpStatusCode.PartialContent,
                        range.end - range.start + 1,
                    ) {
                        writeFileRange(file, range.start, range.end - range.start + 1, this)
                    }
                } else {
                    if (inline) call.response.header("Accept-Ranges", "bytes")
                    call.respondOutputStream(
                        contentType,
                        HttpStatusCode.OK,
                        file.length(),
                    ) {
                        FileInputStream(file).use { it.copyTo(this) }
                    }
                }
            }
        } finally {
            archive?.delete()
        }
    }

    /** 文件夹下载前先在手机缓存目录打包，网页端拿到的就是一个普通 zip。 */
    private fun zipDirectory(source: File, target: File) {
        val base = source.absoluteFile
        val basePath = base.path
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            base.walkTopDown()
                .onEnter { directory ->
                    directory == base || !Files.isSymbolicLink(directory.toPath())
                }
                .forEach { entry ->
                    if (entry == base || Files.isSymbolicLink(entry.toPath())) return@forEach
                    val relative = entry.absolutePath
                        .removePrefix(basePath)
                        .trimStart(File.separatorChar)
                        .replace(File.separatorChar, '/')
                    if (relative.isEmpty()) return@forEach
                    if (entry.isDirectory) {
                        zip.putNextEntry(ZipEntry("$relative/"))
                        zip.closeEntry()
                    } else if (entry.isFile) {
                        zip.putNextEntry(ZipEntry(relative))
                        entry.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
        }
    }

    private fun encryptedLength(fileLength: Long): Long {
        val chunks = if (fileLength == 0L) 1L else (fileLength + ENC_CHUNK_SIZE - 1) / ENC_CHUNK_SIZE
        return ENC_MAGIC.size + GCM_IV_BYTES + fileLength + chunks * (4L + GCM_TAG_BYTES)
    }

    private fun encryptFile(file: File, key: SecretKey, output: OutputStream) {
        val baseIv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        for (index in 8 until 12) baseIv[index] = 0
        output.write(ENC_MAGIC)
        output.write(baseIv)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(ENC_CHUNK_SIZE)
            var index = 0
            var read = input.read(buffer)
            if (read < 0) {
                writeEncryptedChunk(output, baseIv, index, key, ByteArray(0))
                return@use
            }
            while (read >= 0) {
                writeEncryptedChunk(output, baseIv, index, key, buffer.copyOf(read))
                index += 1
                read = input.read(buffer)
            }
        }
    }

    private fun writeEncryptedChunk(
        output: OutputStream,
        baseIv: ByteArray,
        index: Int,
        key: SecretKey,
        plaintext: ByteArray,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonceFor(baseIv, index)))
        val ciphertext = cipher.doFinal(plaintext)
        output.write(intToBytes(ciphertext.size))
        output.write(ciphertext)
    }

    /**
     * 新版格式：FDEC1 + 12 字节基础 IV + 每块 [4 字节长度 + AES-GCM 密文+tag]。
     * 每块独立 GCM，手机端和网页端都可以流式处理，避免大文件一次性解密卡死。
     */
    private fun decryptToFile(source: File, target: File, key: SecretKey) {
        FileInputStream(source).use { input ->
            val header = ByteArray(ENC_MAGIC.size)
            val headerRead = input.read(header)
            if (headerRead == ENC_MAGIC.size && header.contentEquals(ENC_MAGIC)) {
                val baseIv = ByteArray(GCM_IV_BYTES)
                readFully(input, baseIv)
                FileOutputStream(target).use { output ->
                    val lengthBytes = ByteArray(4)
                    var index = 0
                    while (true) {
                        val lengthRead = input.read(lengthBytes)
                        if (lengthRead < 0) break
                        if (lengthRead != 4) throw IOException("bad-encrypted-file")
                        val length = bytesToInt(lengthBytes)
                        if (length < GCM_TAG_BYTES || length > ENC_CHUNK_SIZE + GCM_TAG_BYTES) {
                            throw IOException("bad-encrypted-chunk")
                        }
                        val ciphertext = ByteArray(length)
                        readFully(input, ciphertext)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonceFor(baseIv, index)))
                        output.write(cipher.doFinal(ciphertext))
                        index += 1
                    }
                }
            } else {
                // 兼容早期 IV + 整文件密文的格式。
                val iv = ByteArray(GCM_IV_BYTES)
                if (headerRead > 0) header.copyInto(iv, 0, 0, minOf(headerRead, iv.size))
                if (headerRead < iv.size) readFully(input, iv, headerRead, iv.size - headerRead)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        val chunk = cipher.update(buffer, 0, read)
                        if (chunk != null && chunk.isNotEmpty()) output.write(chunk)
                    }
                    val finalChunk = cipher.doFinal()
                    if (finalChunk.isNotEmpty()) output.write(finalChunk)
                }
            }
        }
    }

    private fun nonceFor(baseIv: ByteArray, index: Int): ByteArray {
        val nonce = baseIv.copyOf()
        nonce[8] = (index ushr 24).toByte()
        nonce[9] = (index ushr 16).toByte()
        nonce[10] = (index ushr 8).toByte()
        nonce[11] = index.toByte()
        return nonce
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun bytesToInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)

    /* ------------------------------ 静态资源 ------------------------------ */

    private suspend fun serveStatic(call: ApplicationCall, path: String) {
        if (call.request.httpMethod != HttpMethod.Get && call.request.httpMethod != HttpMethod.Head) {
            methodNotAllowed(call)
            return
        }
        val clean = path.trimStart('/').ifBlank { "index.html" }
        if (clean.contains("..")) {
            call.respondText("Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            return
        }
        val candidates = if (clean == "index.html" || !clean.contains('.')) {
            listOf(clean, "index.html")
        } else {
            listOf(clean)
        }
        for (candidate in candidates) {
            val stream = runCatching { appContext.assets.open("flashdrop/$candidate") }.getOrNull() ?: continue
            call.response.header(
                "Cache-Control",
                if (candidate.startsWith("assets/")) "public, max-age=31536000, immutable" else "no-store",
            )
            addSecurityHeaders(call)
            call.respondOutputStream(ContentType.parse(mimeType(candidate)), HttpStatusCode.OK) {
                stream.use { it.copyTo(this) }
            }
            return
        }
        call.respondText(
            "Web 资源还没有打包进 APK",
            ContentType.Text.Plain,
            HttpStatusCode.NotFound,
        )
    }

    /* ------------------------------ 工具函数 ------------------------------ */

    private fun authenticated(call: ApplicationCall, allowQuery: Boolean = false): Session? {
        val authorization = header(call, "Authorization")
        val token = when {
            authorization?.startsWith("Bearer ") == true -> authorization.removePrefix("Bearer ").trim()
            allowQuery -> call.request.queryParameters["token"]
            else -> null
        } ?: return null
        val found = sessions[token] ?: return null
        if (found.expiresAt < System.currentTimeMillis()) {
            sessions.remove(token)
            listener.onClientsChanged(sessions.size)
            return null
        }
        return found
    }

    private suspend fun unauthorized(call: ApplicationCall) {
        respondJson(call, HttpStatusCode.Unauthorized, JSONObject().put("error", "unauthorized"))
    }

    private suspend fun methodNotAllowed(call: ApplicationCall) {
        respondJson(call, HttpStatusCode.MethodNotAllowed, JSONObject().put("error", "method-not-allowed"))
    }

    private suspend fun respondJson(call: ApplicationCall, status: HttpStatusCode, body: JSONObject) {
        call.response.header("Cache-Control", "no-store")
        addSecurityHeaders(call)
        call.respondText(body.toString(), ContentType.Application.Json, status)
    }

    private suspend fun bytesResponse(call: ApplicationCall, contentType: ContentType, bytes: ByteArray) {
        call.response.header("Cache-Control", "no-store")
        addSecurityHeaders(call)
        call.respondBytes(bytes, contentType, HttpStatusCode.OK)
    }

    private fun addSecurityHeaders(call: ApplicationCall) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
    }

    private suspend fun readJson(call: ApplicationCall): JSONObject {
        val max = 512 * 1024
        val length = call.request.contentLength()
        if (length != null && length > max) throw IOException("body-too-large")
        val text = call.receiveText()
        if (text.length > max) throw IOException("body-too-large")
        return JSONObject(text)
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        readFully(input, target, 0, target.size)
    }

    private fun readFully(input: InputStream, target: ByteArray, start: Int, length: Int) {
        var offset = start
        val end = start + length
        while (offset < end) {
            val read = input.read(target, offset, end - offset)
            if (read <= 0) throw IOException("unexpected-eof")
            offset += read
        }
    }

    private data class ByteRange(val start: Long, val end: Long)

    private fun parseRange(value: String?, length: Long): ByteRange? {
        if (value == null || length <= 0 || !value.startsWith("bytes=")) return null
        val spec = value.removePrefix("bytes=").substringBefore(',').trim()
        val startText = spec.substringBefore('-')
        val endText = spec.substringAfter('-', "")
        var start = startText.toLongOrNull()
        var end = endText.toLongOrNull()
        if (start == null) {
            val suffix = end ?: return null
            if (suffix <= 0) return null
            start = (length - suffix).coerceAtLeast(0)
            end = length - 1
        } else {
            if (start < 0 || start >= length) return null
            if (end == null || end >= length) end = length - 1
        }
        if (end < start) return null
        return ByteRange(start, end)
    }

    private fun writeFileRange(file: File, start: Long, length: Long, output: OutputStream) {
        FileInputStream(file).use { input ->
            var skipped = 0L
            while (skipped < start) {
                val step = input.skip(start - skipped)
                if (step <= 0) break
                skipped += step
            }
            var remaining = length
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private fun header(call: ApplicationCall, name: String): String? =
        call.request.headers[name] ?: call.request.headers[name.lowercase(Locale.ROOT)]

    private fun deviceJson(): JSONObject {
        val total = storageTotal()
        val free = storageFree()
        return JSONObject()
            .put("name", Build.MODEL.ifBlank { "Android" })
            .put("model", Build.MODEL)
            .put("system", "Android ${Build.VERSION.RELEASE ?: ""}")
            .put("storageUsed", total - free)
            .put("storageTotal", total)
    }

    private fun storageTotal(): Long = runCatching {
        val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
        stat.blockCountLong * stat.blockSizeLong
    }.getOrDefault(0L)

    private fun storageFree(): Long = runCatching {
        val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    private fun externalRoot(): File = Environment.getExternalStorageDirectory().canonicalFile

    private fun safeFile(relative: String): File? {
        if (relative.isBlank()) return externalRoot()
        val root = externalRoot()
        val file = File(root, relative).canonicalFile
        return file.takeIf { it.path == root.path || it.path.startsWith(root.path + File.separator) }
    }

    private fun relativePath(file: File): String =
        runCatching { file.relativeTo(externalRoot()).invariantSeparatorsPath }.getOrDefault(file.name)

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun extensionsFor(category: String): Set<String> = when (category) {
        "photos" -> setOf("jpg", "jpeg", "png", "webp", "heic", "gif", "avif", "bmp", "dng")
        "videos" -> setOf("mp4", "mov", "mkv", "webm", "avi", "m4v", "3gp")
        "audio" -> setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus")
        "documents" -> setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "csv")
        "archives" -> setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
        "apks" -> setOf("apk", "apks", "xapk", "apkm")
        "downloads" -> emptySet()
        else -> emptySet()
    }

    private val textExtensions = setOf(
        "txt", "md", "markdown", "mdx", "log", "csv", "tsv", "json", "json5", "xml",
        "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "env",
        "kt", "kts", "java", "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "cs", "fs", "fsx", "vb",
        "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte", "astro",
        "py", "pyw", "rb", "php", "pl", "pm", "lua", "r", "go", "rs", "swift", "m", "mm",
        "scala", "sc", "groovy", "gradle", "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1",
        "sql", "graphql", "gql", "proto", "html", "htm", "css", "scss", "sass", "less", "styl",
        "tex", "bib", "dart", "ex", "exs", "erl", "hrl", "hs", "lhs", "clj", "cljs", "cljc", "edn",
        "asm", "s", "vim", "nix", "dockerfile", "makefile", "mk", "cmake", "bazel", "bzl",
        "gitignore", "gitattributes", "editorconfig", "npmrc", "yarnrc",
    )

    private val textFileNames = setOf(
        "makefile", "dockerfile", "license", "readme", "changelog", "authors", "notice",
        "gemfile", "rakefile", "procfile", "justfile",
    )

    private fun kindFor(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        val ext = lower.substringAfterLast('.', "")
        if (ext in textExtensions || lower in textFileNames ||
            (lower.startsWith(".") && lower.removePrefix(".") in textFileNames)
        ) {
            return "text"
        }
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "heic", "gif", "avif", "bmp", "dng" -> "image"
            "mp4", "mov", "mkv", "webm", "avi", "m4v", "3gp" -> "video"
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus" -> "audio"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf" -> "document"
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> "archive"
            "apk", "apks", "xapk", "apkm" -> "apk"
            else -> "file"
        }
    }

    /** 只看文件头的可读字符，避免把伪装成 .txt/.md 的二进制文件塞进预览。 */
    private fun looksLikeText(file: File): Boolean {
        if (file.length() > TEXT_PREVIEW_LIMIT) return false
        return runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(4096)
                val read = input.read(buffer)
                if (read <= 0) return@use true
                if (read >= 2) {
                    val first = buffer[0].toInt() and 0xFF
                    val second = buffer[1].toInt() and 0xFF
                    if ((first == 0xFF && second == 0xFE) || (first == 0xFE && second == 0xFF)) return@use true
                }
                var bad = 0
                for (index in 0 until read) {
                    val byte = buffer[index].toInt() and 0xFF
                    if (byte == 0) return@use false
                    if (byte == 9 || byte == 10 || byte == 13 || byte in 32..126 || byte >= 0x80) continue
                    bad += 1
                }
                bad == 0
            }
        }.getOrDefault(false)
    }

    private fun decodeText(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return runCatching { String(bytes, 3, bytes.size - 3, Charsets.UTF_8) }.getOrNull()
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return runCatching { String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE")) }.getOrNull()
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return runCatching { String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE")) }.getOrNull()
        }
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8.removePrefix("\uFEFF")
        return runCatching {
            Charset.forName("GB18030").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }

    private fun decodeSampledBitmap(file: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    @Suppress("DEPRECATION")
    private fun videoThumbnail(file: File): Bitmap? =
        ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)

    private fun sanitizeName(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().take(180)
        return clean.ifBlank { "upload.bin" }
    }

    private fun uniqueTarget(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            val next = if (ext.isBlank()) "$base ($index)" else "$base ($index).$ext"
            candidate = File(dir, next)
            index += 1
        }
        return candidate
    }

    private fun contentDisposition(name: String, inline: Boolean = false): String {
        val safe = name.replace("\"", "").replace("\r", "").replace("\n", "")
        val ascii = safe.map { if (it.code in 32..126) it else '_' }.joinToString("")
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val type = if (inline) "inline" else "attachment"
        return "$type; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    private fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ico" -> "image/x-icon"
        "map" -> "application/json"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun addTransfer(
        name: String,
        size: Long,
        direction: String,
        requestedId: String? = null,
        path: String? = null,
    ) {
        val id = requestedId?.takeIf { it.length in 1..80 && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' } }
            ?: UUID.randomUUID().toString()
        synchronized(transfers) {
            transfers.add(
                0,
                TransferRecord(
                    id = id,
                    name = name,
                    size = size,
                    direction = direction,
                    progress = 100,
                    status = "done",
                    createdAt = formatModified(System.currentTimeMillis()),
                    path = path,
                ),
            )
            while (transfers.size > 80) transfers.removeAt(transfers.lastIndex)
        }
    }

    private fun formatModified(millis: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        val yesterday = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR) == 1
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
        return when {
            sameDay -> "今天 $time"
            yesterday -> "昨天 $time"
            else -> SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(millis))
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun randomToken(): String {
        val bytes = ByteArray(32).also(secureRandom::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCode(): String = (100000 + secureRandom.nextInt(900000)).toString()

    /** 生成网页端展示的两位数，以及手机端包含正确项的三个候选数字。 */
    private fun generatePairChallenge(): Pair<Int, List<Int>> {
        val number = 10 + secureRandom.nextInt(90)
        val options = linkedSetOf(number)
        while (options.size < 3) options.add(10 + secureRandom.nextInt(90))
        return number to options.shuffled()
    }

    private fun deriveKey(code: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(code.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val encoded = factory.generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun readClipboard(): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching {
                val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()
            }.getOrNull()
        }
        var result: String? = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result = runCatching {
                val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()
            }.getOrNull()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return result
    }

    private fun writeClipboard(text: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching {
                val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("隔空闪传", text))
            }.isSuccess
        }
        var ok = false
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            ok = runCatching {
                val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("隔空闪传", text))
            }.isSuccess
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return ok
    }

    companion object {
        private const val TAG = "FlashDropServer"
        const val PORT_START = 2233
        const val PORT_END = 2333
        private const val TOKEN_TTL_MS = 24L * 60 * 60 * 1000
        private const val PAIR_REQUEST_TTL_MS = 5L * 60 * 1000
        private const val ATTEMPT_WINDOW_MS = 60_000L
        private const val MAX_ATTEMPTS = 5
        private const val PAGE_SIZE = 250
        private const val CATEGORY_CACHE_MS = 8_000L
        private const val TEXT_PREVIEW_LIMIT = 1024L * 1024L
        private const val APP_CACHE_MS = 30_000L
        private const val SALT_BYTES = 16
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val ENC_CHUNK_SIZE = 1024 * 1024
        private val ENC_MAGIC = byteArrayOf(0x46, 0x44, 0x45, 0x43, 0x31)
        private const val PBKDF2_ITERATIONS = 120_000

        /**
         * 从 2233 递增到 2333，使用第一个能绑定的端口。
         * 端口被占用时继续尝试；全部失败才抛 IOException。
         */
        fun startOnFreePort(
            context: Context,
            customCodeEnabled: Boolean,
            customCode: String,
            encryptionEnabled: Boolean,
            listener: Listener,
        ): FlashDropServer {
            var lastError: Exception? = null
            for (port in PORT_START..PORT_END) {
                if (!isPortFree(port)) continue
                val server = FlashDropServer(
                    context = context,
                    port = port,
                    initialCustomCodeEnabled = customCodeEnabled,
                    initialCustomCode = customCode,
                    initialEncryptionEnabled = encryptionEnabled,
                    listener = listener,
                )
                val engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    server.configureServer(this)
                }
                try {
                    engine.start(wait = false)
                    server.engine = engine
                    return server
                } catch (error: Exception) {
                    lastError = error
                    runCatching { engine.stop(0, 0) }
                }
            }
            throw IOException("2233-2333 没有可用端口", lastError)
        }

        private fun isPortFree(port: Int): Boolean = runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", port))
            }
            true
        }.getOrDefault(false)
    }
}
