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
import io.ktor.utils.io.jvm.javaio.copyTo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
import javax.crypto.CipherInputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val secureRandom = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val iconCache = LruCache<String, ByteArray>(4 * 1024 * 1024)
    private val thumbCache = LruCache<String, ByteArray>(12 * 1024 * 1024)
    private val transfers = Collections.synchronizedList(mutableListOf<TransferRecord>())
    private val failedAttempts = mutableListOf<Long>()

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

    private data class TransferRecord(
        val id: String,
        val name: String,
        val size: Long,
        val direction: String,
        val progress: Int,
        val status: String,
        val createdAt: String,
    )

    val code: String get() = currentCode
    val runningPort: Int get() = port
    val encryptionActive: Boolean get() = encryptionEnabled
    val pairedCount: Int get() = sessions.size

    fun stop() {
        sessions.clear()
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

    private fun Application.configureServer() {
        routing {
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
            path == "/api/session" -> handleSession(call)
            path == "/api/logout" -> handleLogout(call)
            path == "/api/apps" -> handleApps(call)
            path == "/api/files" -> handleFiles(call)
            path == "/api/transfers" -> handleTransfers(call)
            path == "/api/clipboard" -> handleClipboard(call)
            path == "/api/upload" -> handleUpload(call)
            path == "/api/download" -> handleDownload(call)
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

        val items = when (category) {
            "storage" -> listStorage(path, query)
            "photos", "videos", "audio", "documents", "archives", "apks", "downloads" ->
                scanCategory(category, query)
            else -> {
                respondJson(call, HttpStatusCode.BadRequest, JSONObject().put("error", "bad-category"))
                return
            }
        }
        respondJson(call, HttpStatusCode.OK, JSONObject().put("items", items))
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
                items.put(
                    JSONObject()
                        .put("id", transfer.id)
                        .put("name", transfer.name)
                        .put("size", transfer.size)
                        .put("direction", transfer.direction)
                        .put("progress", transfer.progress)
                        .put("status", transfer.status)
                        .put("createdAt", transfer.createdAt),
                )
            }
        }
        respondJson(call, HttpStatusCode.OK, JSONObject().put("items", items))
    }

    private suspend fun handleClipboard(call: ApplicationCall) {
        if (authenticated(call) == null) return unauthorized(call)
        if (call.request.httpMethod == HttpMethod.Post) {
            val body = runCatching { readJson(call) }.getOrNull()
            if (body == null) {
                respondJson(call, HttpStatusCode.BadRequest, JSONObject().put("error", "bad-json"))
                return
            }
            val text = body.optString("text")
            if (writeClipboard(text)) {
                respondJson(call, HttpStatusCode.OK, JSONObject().put("ok", true))
            } else {
                respondJson(call, HttpStatusCode.InternalServerError, JSONObject().put("error", "clipboard-denied"))
            }
            return
        }
        if (call.request.httpMethod != HttpMethod.Get) {
            methodNotAllowed(call)
            return
        }

        val text = readClipboard().orEmpty()
        val items = JSONArray()
        if (text.isNotEmpty()) {
            items.put(
                JSONObject()
                    .put("type", "file")
                    .put("id", "clipboard-current")
                    .put("name", "当前剪贴板")
                    .put("kind", "text")
                    .put("size", text.toByteArray(Charsets.UTF_8).size)
                    .put("modified", formatModified(System.currentTimeMillis()))
                    .put("path", "")
                    .put("text", text),
            )
        }
        respondJson(call, HttpStatusCode.OK, JSONObject().put("items", items))
    }

    /* ------------------------------ 文件列表 ------------------------------ */

    private fun listStorage(relativePath: String, query: String): JSONArray {
        val root = externalRoot()
        val dir = if (relativePath.isBlank()) root else safeFile(relativePath)
        if (dir == null || !dir.isDirectory) return JSONArray()
        val children = dir.listFiles()
            ?.filter { !it.name.startsWith(".") }
            ?.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenByDescending { it.lastModified() })
            ?.take(MAX_LIST_ITEMS)
            .orEmpty()

        val items = JSONArray()
        children.forEach { file ->
            if (file.isDirectory) {
                items.put(folderJson(file))
            } else {
                items.put(fileJson(file))
            }
        }
        return items
    }

    private fun scanCategory(category: String, query: String): JSONArray {
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
        val queue = ArrayDeque<File>()
        roots.map { File(externalRoot(), it) }.filter { it.isDirectory }.forEach(queue::addLast)
        var visited = 0
        while (queue.isNotEmpty() && found.size < MAX_LIST_ITEMS && visited < MAX_SCAN_DIRS) {
            val dir = queue.removeFirst()
            visited += 1
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    queue.addLast(child)
                } else if ((category == "downloads" || child.extension.lowercase(Locale.ROOT) in extensions) &&
                    (query.isEmpty() || child.name.contains(query, ignoreCase = true))
                ) {
                    found += child
                    if (found.size >= MAX_LIST_ITEMS) break
                }
            }
        }
        found.sortByDescending { it.lastModified() }
        val items = JSONArray()
        found.forEach { items.put(fileJson(it)) }
        return items
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
            .put("path", relative)
    }

    private fun fileJson(file: File): JSONObject {
        val relative = relativePath(file)
        val kind = kindFor(file.name)
        val json = JSONObject()
            .put("type", "file")
            .put("id", relative)
            .put("name", file.name)
            .put("kind", kind)
            .put("size", file.length())
            .put("modified", formatModified(file.lastModified()))
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
            addTransfer(saved.name, saved.length(), "upload")
            respondJson(call, HttpStatusCode.OK, JSONObject().put("item", fileJson(saved)))
        } catch (error: Exception) {
            target?.delete()
            Log.w(TAG, "upload failed", error)
            respondJson(call, HttpStatusCode.InternalServerError, JSONObject().put("error", "upload-failed"))
        } finally {
            temp.delete()
        }
    }

    private suspend fun handleDownload(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Get) {
            methodNotAllowed(call)
            return
        }
        val authenticated = authenticated(call) ?: return unauthorized(call)
        val packageName = call.request.queryParameters["package"]
        if (packageName.isNullOrBlank() && !hasStorageAccess()) {
            respondJson(call, HttpStatusCode.Forbidden, JSONObject().put("error", "storage-permission"))
            return
        }
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
            val resolved = safeFile(path)?.takeIf { it.isFile }
            if (resolved == null) {
                respondJson(call, HttpStatusCode.NotFound, JSONObject().put("error", "file-not-found"))
                return
            }
            file = resolved
            downloadName = file.name
        }
        if (!file.canRead()) {
            respondJson(call, HttpStatusCode.Forbidden, JSONObject().put("error", "cannot-read"))
            return
        }
        addTransfer(downloadName, file.length(), "download")
        call.response.header("Content-Disposition", contentDisposition(downloadName))
        call.response.header("Cache-Control", "no-store")
        addSecurityHeaders(call)
        val key = authenticated.encryptionKey
        if (key != null) {
            call.response.header("X-FlashDrop-Encrypted", "1")
            val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            call.respondOutputStream(
                ContentType.Application.OctetStream,
                HttpStatusCode.OK,
                file.length() + GCM_IV_BYTES + GCM_TAG_BYTES,
            ) { output ->
                output.write(iv)
                CipherInputStream(FileInputStream(file), cipher).use { it.copyTo(output) }
            }
        } else {
            call.respondOutputStream(
                ContentType.Application.OctetStream,
                HttpStatusCode.OK,
                file.length(),
            ) { output ->
                FileInputStream(file).use { it.copyTo(output) }
            }
        }
    }

    private fun decryptToFile(source: File, target: File, key: SecretKey) {
        FileInputStream(source).use { input ->
            val iv = ByteArray(GCM_IV_BYTES)
            readFully(input, iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            CipherInputStream(input, cipher).use { decrypted ->
                FileOutputStream(target).use { output -> decrypted.copyTo(output) }
            }
        }
    }

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
            call.respondOutputStream(ContentType.parse(mimeType(candidate)), HttpStatusCode.OK) { output ->
                stream.use { it.copyTo(output) }
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
        val max = 64 * 1024
        val length = call.request.contentLength()
        if (length != null && length > max) throw IOException("body-too-large")
        val text = call.receiveText()
        if (text.length > max) throw IOException("body-too-large")
        return JSONObject(text)
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read <= 0) throw IOException("unexpected-eof")
            offset += read
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

    private fun kindFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "webp", "heic", "gif", "avif", "bmp", "dng" -> "image"
        "mp4", "mov", "mkv", "webm", "avi", "m4v", "3gp" -> "video"
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus" -> "audio"
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "csv" -> "document"
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> "archive"
        "apk", "apks", "xapk", "apkm" -> "apk"
        else -> "file"
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

    private fun contentDisposition(name: String): String {
        val safe = name.replace("\"", "").replace("\r", "").replace("\n", "")
        val ascii = safe.map { if (it.code in 32..126) it else '_' }.joinToString("")
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
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
        else -> "application/octet-stream"
    }

    private fun addTransfer(name: String, size: Long, direction: String) {
        synchronized(transfers) {
            transfers.add(
                0,
                TransferRecord(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    size = size,
                    direction = direction,
                    progress = 100,
                    status = "done",
                    createdAt = formatModified(System.currentTimeMillis()),
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
        private const val ATTEMPT_WINDOW_MS = 60_000L
        private const val MAX_ATTEMPTS = 5
        private const val MAX_LIST_ITEMS = 500
        private const val MAX_SCAN_DIRS = 2_000
        private const val APP_CACHE_MS = 30_000L
        private const val SALT_BYTES = 16
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
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
                    server.configureServer()
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
