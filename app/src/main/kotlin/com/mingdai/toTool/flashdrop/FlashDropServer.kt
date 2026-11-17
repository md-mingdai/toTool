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
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
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
 * 隔空闪传的手机端服务。
 *
 * - 只监听局域网，端口从 [PORT_START] 开始递增到 [PORT_END]
 * - 静态页面来自 assets/flashdrop（CI 构建 web/dist 后放进来）
 * - 所有 /api 接口同源；除配对/信息/健康检查外都需要 Bearer token
 * - 配对码默认每次成功配对后轮换；自定义配对码不轮换
 * - 可选的 AES-GCM 文件内容加密，密钥由配对码 + 随机 salt 经 PBKDF2 派生
 */
class FlashDropServer(
    context: Context,
    private val port: Int,
    initialCustomCodeEnabled: Boolean,
    initialCustomCode: String,
    initialEncryptionEnabled: Boolean,
    private val listener: Listener,
) : NanoHTTPD(port) {

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

    override fun serve(session: IHTTPSession): Response {
        return try {
            val path = session.uri.substringBefore('?')
            if (path.startsWith("/api/")) handleApi(session, path) else serveStatic(path)
        } catch (error: Exception) {
            Log.w(TAG, "request failed: ${session.uri}", error)
            jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "server-error"))
        }
    }

    /* ------------------------------ 配对与设置 ------------------------------ */

    override fun stop() {
        sessions.clear()
        listener.onClientsChanged(0)
        super.stop()
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

    private fun handlePair(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val body = runCatching { readJson(session) }.getOrNull()
            ?: return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "bad-json"))
        val code = body.optString("code").replace(" ", "").trim()
        if (code.length != 6 || code.any { !it.isDigit() }) {
            return pairingRejected()
        }

        synchronized(failedAttempts) {
            val now = System.currentTimeMillis()
            failedAttempts.removeAll { it < now - ATTEMPT_WINDOW_MS }
            if (failedAttempts.size >= MAX_ATTEMPTS) {
                return jsonResponse(
                    Response.Status.TOO_MANY_REQUESTS,
                    JSONObject().put("error", "rate-limited"),
                ).apply { addHeader("Retry-After", "60") }
            }
        }

        if (!MessageDigest.isEqual(currentCode.toByteArray(), code.toByteArray())) {
            synchronized(failedAttempts) { failedAttempts += System.currentTimeMillis() }
            return pairingRejected()
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
        return jsonResponse(Response.Status.OK, response)
    }

    private fun handleSession(session: IHTTPSession): Response {
        val authenticated = authenticated(session) ?: return unauthorized()
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("paired", true)
                .put("encryptionEnabled", authenticated.encryptionKey != null),
        )
    }

    private fun handleLogout(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val token = header(session, "authorization")?.removePrefix("Bearer ")?.trim()
            ?: return unauthorized()
        if (sessions.remove(token) == null) return unauthorized()
        listener.onClientsChanged(sessions.size)
        return jsonResponse(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun pairingRejected(): Response =
        jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("error", "invalid-code"))

    /* -------------------------------- API -------------------------------- */

    private fun handleApi(session: IHTTPSession, path: String): Response {
        return when {
            path == "/api/info" -> infoResponse()
            path == "/api/health" -> jsonResponse(
                Response.Status.OK,
                JSONObject().put("ok", true).put("service", "flashdrop"),
            )
            path == "/api/pair" -> handlePair(session)
            path == "/api/session" -> handleSession(session)
            path == "/api/logout" -> handleLogout(session)
            path == "/api/apps" -> handleApps(session)
            path == "/api/files" -> handleFiles(session)
            path == "/api/transfers" -> handleTransfers(session)
            path == "/api/clipboard" -> handleClipboard(session)
            path == "/api/upload" -> handleUpload(session)
            path == "/api/download" -> handleDownload(session)
            path == "/api/icon" -> handleIcon(session)
            path == "/api/thumb" -> handleThumb(session)
            else -> jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "not-found"))
        }
    }

    private fun infoResponse(): Response {
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("port", port)
                .put("device", deviceJson())
                .put("encryptionEnabled", encryptionEnabled),
        )
    }

    private fun handleApps(session: IHTTPSession): Response {
        if (authenticated(session) == null) return unauthorized()
        val scope = session.parameters["scope"]?.firstOrNull().orEmpty()
        val query = session.parameters["q"]?.firstOrNull().orEmpty().trim()
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
        return jsonResponse(Response.Status.OK, JSONObject().put("items", items).put("counts", counts))
    }

    private fun handleFiles(session: IHTTPSession): Response {
        if (authenticated(session) == null) return unauthorized()
        if (!hasStorageAccess()) {
            return jsonResponse(Response.Status.FORBIDDEN, JSONObject().put("error", "storage-permission"))
        }
        val category = session.parameters["category"]?.firstOrNull().orEmpty()
        val path = session.parameters["path"]?.firstOrNull().orEmpty()
        val query = session.parameters["q"]?.firstOrNull().orEmpty().trim()

        val items = when (category) {
            "storage" -> listStorage(path, query)
            "photos", "videos", "audio", "documents", "archives", "apks", "downloads" ->
                scanCategory(category, query)
            else -> return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "bad-category"))
        }
        return jsonResponse(Response.Status.OK, JSONObject().put("items", items))
    }

    private fun handleTransfers(session: IHTTPSession): Response {
        if (authenticated(session) == null) return unauthorized()
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
        return jsonResponse(Response.Status.OK, JSONObject().put("items", items))
    }

    private fun handleClipboard(session: IHTTPSession): Response {
        if (authenticated(session) == null) return unauthorized()
        if (session.method == Method.POST) {
            val body = runCatching { readJson(session) }.getOrNull()
                ?: return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "bad-json"))
            val text = body.optString("text")
            return if (writeClipboard(text)) {
                jsonResponse(Response.Status.OK, JSONObject().put("ok", true))
            } else {
                jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "clipboard-denied"))
            }
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
        return jsonResponse(Response.Status.OK, JSONObject().put("items", items))
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

    private fun handleIcon(session: IHTTPSession): Response {
        if (authenticated(session, allowQuery = true) == null) return unauthorized()
        val packageName = session.parameters["package"]?.firstOrNull().orEmpty()
        if (packageName.isEmpty()) return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing-package"))
        val cached = iconCache.get(packageName)
        if (cached != null) return bytesResponse("image/png", cached)
        val pm = appContext.packageManager
        val bytes = runCatching {
            val info = pm.getApplicationInfo(packageName, 0)
            val bitmap = pm.getApplicationIcon(info).toBitmap(96, 96, Bitmap.Config.ARGB_8888)
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }.getOrNull()
        if (bytes == null) return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "icon-not-found"))
        iconCache.put(packageName, bytes)
        return bytesResponse("image/png", bytes)
    }

    private fun handleThumb(session: IHTTPSession): Response {
        if (authenticated(session, allowQuery = true) == null) return unauthorized()
        val path = session.parameters["path"]?.firstOrNull().orEmpty()
        val file = safeFile(path)?.takeIf { it.isFile } ?: return jsonResponse(
            Response.Status.NOT_FOUND,
            JSONObject().put("error", "file-not-found"),
        )
        val key = "${file.absolutePath}:${file.lastModified()}"
        thumbCache.get(key)?.let { return bytesResponse("image/jpeg", it) }
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
        if (bytes == null) return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "thumb-not-found"))
        thumbCache.put(key, bytes)
        return bytesResponse("image/jpeg", bytes)
    }

    /* ------------------------------ 上传下载 ------------------------------ */

    private fun handleUpload(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val authenticated = authenticated(session) ?: return unauthorized()
        if (!hasStorageAccess()) {
            return jsonResponse(Response.Status.FORBIDDEN, JSONObject().put("error", "storage-permission"))
        }
        val name = header(session, "x-flashdrop-name")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: "upload.bin"
        val encrypted = header(session, "x-flashdrop-encrypted") == "1"
        if (encrypted != (authenticated.encryptionKey != null)) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", if (encrypted) "encryption-not-enabled" else "encryption-required"),
            )
        }

        val temp = File.createTempFile("flashdrop-", ".upload", appContext.cacheDir)
        var target: File? = null
        try {
            FileOutputStream(temp).use { output -> copyRequestBody(session, output) }
            val targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "download-dir"))
            }
            val saved = uniqueTarget(targetDir, sanitizeName(name))
            target = saved
            if (encrypted) {
                decryptToFile(temp, saved, authenticated.encryptionKey!!)
            } else {
                temp.copyTo(saved, overwrite = true)
            }
            addTransfer(saved.name, saved.length(), "upload")
            return jsonResponse(Response.Status.OK, JSONObject().put("item", fileJson(saved)))
        } catch (error: Exception) {
            target?.delete()
            Log.w(TAG, "upload failed", error)
            return jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "upload-failed"))
        } finally {
            temp.delete()
        }
    }

    private fun handleDownload(session: IHTTPSession): Response {
        val authenticated = authenticated(session) ?: return unauthorized()
        val packageName = session.parameters["package"]?.firstOrNull()
        if (packageName.isNullOrBlank() && !hasStorageAccess()) {
            return jsonResponse(Response.Status.FORBIDDEN, JSONObject().put("error", "storage-permission"))
        }
        val file: File
        val downloadName: String
        if (!packageName.isNullOrBlank()) {
            val app = installedApps().firstOrNull { it.packageName == packageName }
                ?: return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "app-not-found"))
            file = File(app.sourceDir)
            downloadName = "${sanitizeName(app.name)}.apk"
        } else {
            val path = session.parameters["path"]?.firstOrNull().orEmpty()
            file = safeFile(path)?.takeIf { it.isFile }
                ?: return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "file-not-found"))
            downloadName = file.name
        }
        if (!file.canRead()) {
            return jsonResponse(Response.Status.FORBIDDEN, JSONObject().put("error", "cannot-read"))
        }
        addTransfer(downloadName, file.length(), "download")
        val response = if (authenticated.encryptionKey != null) {
            encryptFileResponse(file, authenticated.encryptionKey!!)
        } else {
            NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK,
                "application/octet-stream",
                FileInputStream(file),
                file.length(),
            )
        }
        response.addHeader("Content-Disposition", contentDisposition(downloadName))
        response.addHeader("Cache-Control", "no-store")
        if (authenticated.encryptionKey != null) response.addHeader("X-FlashDrop-Encrypted", "1")
        return response
    }

    private fun encryptFileResponse(file: File, key: SecretKey): Response {
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = CipherInputStream(FileInputStream(file), cipher)
        val body = SequenceInputStream(ByteArrayInputStream(iv), encrypted)
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            "application/octet-stream",
            body,
            file.length() + GCM_IV_BYTES + GCM_TAG_BYTES,
        )
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

    private fun serveStatic(path: String): Response {
        val clean = path.trimStart('/').ifBlank { "index.html" }
        if (clean.contains("..")) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        }
        val candidates = if (clean == "index.html" || !clean.contains('.')) {
            listOf(clean, "index.html")
        } else {
            listOf(clean)
        }
        for (candidate in candidates) {
            val stream = runCatching { appContext.assets.open("flashdrop/$candidate") }.getOrNull() ?: continue
            val mime = mimeType(candidate)
            val response = NanoHTTPD.newChunkedResponse(Response.Status.OK, mime, stream)
            response.addHeader("Cache-Control", if (candidate.startsWith("assets/")) "public, max-age=31536000, immutable" else "no-store")
            addSecurityHeaders(response)
            return response
        }
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "text/plain; charset=utf-8",
            "Web 资源还没有打包进 APK",
        )
    }

    /* ------------------------------ 工具函数 ------------------------------ */

    private fun authenticated(session: IHTTPSession, allowQuery: Boolean = false): Session? {
        val token = when {
            header(session, "authorization")?.startsWith("Bearer ") == true ->
                header(session, "authorization")!!.removePrefix("Bearer ").trim()
            allowQuery -> session.parameters["token"]?.firstOrNull()
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

    private fun unauthorized(): Response =
        jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("error", "unauthorized"))

    private fun methodNotAllowed(): Response =
        jsonResponse(Response.Status.METHOD_NOT_ALLOWED, JSONObject().put("error", "method-not-allowed"))

    private fun jsonResponse(status: Response.Status, body: JSONObject): Response {
        val response = NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())
        response.addHeader("Cache-Control", "no-store")
        addSecurityHeaders(response)
        return response
    }

    private fun bytesResponse(mime: String, bytes: ByteArray): Response {
        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            mime,
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
        response.addHeader("Cache-Control", "no-store")
        addSecurityHeaders(response)
        return response
    }

    private fun addSecurityHeaders(response: Response) {
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("Referrer-Policy", "no-referrer")
    }

    private fun readJson(session: IHTTPSession): JSONObject {
        val max = 64 * 1024
        val body = readRequestBody(session, max)
        return JSONObject(String(body, Charsets.UTF_8))
    }

    /**
     * 按 Content-Length 精确读取请求体。
     * 不能读到 EOF：HTTP keep-alive 下这个流不会在请求体结束时关闭，
     * 读到 EOF 会一直等到 socket 超时，表现为“配对很慢”并最终返回 bad-json。
     */
    private fun readRequestBody(session: IHTTPSession, max: Int): ByteArray {
        val length = contentLength(session)
        if (length != null) {
            if (length > max) throw IOException("body-too-large")
            val bytes = ByteArray(length.toInt())
            readFully(session.inputStream, bytes)
            return bytes
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val read = session.inputStream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > max) throw IOException("body-too-large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun copyRequestBody(session: IHTTPSession, output: OutputStream) {
        val length = contentLength(session)
        if (length != null) {
            copyExactly(session.inputStream, output, length)
        } else {
            session.inputStream.copyTo(output)
        }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw IOException("unexpected-eof")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun contentLength(session: IHTTPSession): Long? =
        header(session, "content-length")?.toLongOrNull()?.takeIf { it >= 0 }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read <= 0) throw IOException("unexpected-eof")
            offset += read
        }
    }

    private fun header(session: IHTTPSession, name: String): String? =
        session.headers[name] ?: session.headers[name.lowercase(Locale.ROOT)]

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
        "downloads" -> setOf(
            "apk", "apks", "xapk", "apkm", "zip", "rar", "7z", "pdf", "doc", "docx",
            "xls", "xlsx", "ppt", "pptx", "jpg", "jpeg", "png", "webp", "mp4", "mov", "mkv", "mp3", "flac", "wav",
        )
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
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$safe\"; filename*=UTF-8''$encoded"
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
                val server = FlashDropServer(
                    context = context,
                    port = port,
                    initialCustomCodeEnabled = customCodeEnabled,
                    initialCustomCode = customCode,
                    initialEncryptionEnabled = encryptionEnabled,
                    listener = listener,
                )
                try {
                    server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (error: Exception) {
                    lastError = error
                    runCatching { server.stop() }
                }
            }
            throw IOException("2233-2333 没有可用端口", lastError)
        }
    }
}
