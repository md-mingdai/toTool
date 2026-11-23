package com.mingdai.toTool.videoparse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object VideoHttp {
    const val UA_CHROME =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val UA_IPHONE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    internal data class Response(
        val status: Int,
        val body: String,
        val headers: Map<String, List<String>>,
        val finalUrl: String,
    ) {
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()
    }

    suspend fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        followRedirects: Boolean = true,
    ): Response = withContext(Dispatchers.IO) {
        var current = url
        var requestMethod = method.uppercase()
        var requestBody = body
        repeat(6) { hop ->
            val connection = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                throw VideoParseException(VideoParseException.Kind.NETWORK, "链接无效：$current", e)
            }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 25_000
                connection.requestMethod = requestMethod
                connection.setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                if (requestBody != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(requestBody.size)
                    connection.outputStream.use { it.write(requestBody) }
                }
                val status = try {
                    connection.responseCode
                } catch (e: IOException) {
                    throw VideoParseException(
                        VideoParseException.Kind.NETWORK,
                        "网络请求失败：$current",
                        e,
                    )
                }
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val responseHeaders = connection.headerFields
                    .filterKeys { it != null }
                    .mapValues { entry -> entry.value.orEmpty() }
                val location = responseHeaders.entries
                    .firstOrNull { it.key.equals("Location", ignoreCase = true) }
                    ?.value?.firstOrNull()
                if (followRedirects && status in 300..399 && !location.isNullOrBlank() && hop < 5) {
                    current = URL(URL(current), location).toString()
                    if (status in 301..303) {
                        requestMethod = "GET"
                        requestBody = null
                    }
                    return@repeat
                }
                return@withContext Response(status, text, responseHeaders, current)
            } finally {
                connection.disconnect()
            }
        }
        throw VideoParseException(VideoParseException.Kind.NETWORK, "重定向次数过多：$url")
    }

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        what: String = "页面",
    ): String {
        val response = request(url, headers = headers)
        requireSuccess(response, what)
        return response.body
    }

    suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        what: String = "接口",
    ): JSONObject {
        val text = getText(url, headers, what)
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw VideoParseException(
                VideoParseException.Kind.UNKNOWN,
                "$what 返回的不是有效 JSON",
                e,
            )
        }
    }

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        what: String = "接口",
    ): String {
        val encoded = form.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        val response = request(
            url,
            method = "POST",
            headers = headers + ("Content-Type" to "application/x-www-form-urlencoded"),
            body = encoded.toByteArray(Charsets.UTF_8),
        )
        requireSuccess(response, what)
        return response.body
    }

    suspend fun expandRedirect(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String? {
        val response = request(url, headers = headers, followRedirects = false)
        if (response.status in 300..399) {
            val location = response.header("Location") ?: return null
            return URL(URL(response.finalUrl), location).toString()
        }
        return null
    }

    fun requireSuccess(response: Response, what: String) {
        if (response.status in 200..299) return
        throw when (response.status) {
            403, 418, 429 -> VideoParseException(
                VideoParseException.Kind.BLOCKED,
                "$what 被平台风控拦截（HTTP ${response.status}），请稍后重试或提供登录 Cookie",
            )
            404, 410 -> VideoParseException(
                VideoParseException.Kind.NOT_FOUND,
                "$what 不存在或已被删除（HTTP ${response.status}）",
            )
            else -> VideoParseException(
                VideoParseException.Kind.UNKNOWN,
                "$what 请求失败（HTTP ${response.status}）",
            )
        }
    }

    fun extractInlineJson(html: String, key: String, stopAt: String = "</script>"): String? {
        val pattern = Regex(
            Regex.escape(key) + """\s*=\s*(.*?)""" + Regex.escape(stopAt),
            RegexOption.DOT_MATCHES_ALL,
        )
        return pattern.find(html)?.groupValues?.getOrNull(1)?.trim()
    }

    fun unescapeQuotes(text: String): String =
        text.replace("\\\"", "\"").replace("\\\\/", "/")

    fun secureUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }

    suspend fun getBitmap(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxSide: Int = 1080,
        maxEncodedBytes: Int = 24 * 1024 * 1024,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        var connection: HttpURLConnection? = null
        try {
            val active = URL(secureUrl(url)).openConnection() as HttpURLConnection
            connection = active
            active.instanceFollowRedirects = true
            active.connectTimeout = 15_000
            active.readTimeout = 20_000
            active.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (name, value) -> active.setRequestProperty(name, value) }
            if (active.responseCode !in 200..299) return@withContext null
            val bytes = active.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxEncodedBytes) return@withContext null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            if (bytes.isEmpty()) return@withContext null
            // 先读尺寸再按 inSampleSize 解码,避免为一张缩略图把数千万像素的原图整个解出来。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sourceSide = maxOf(bounds.outWidth, bounds.outHeight)
            if (sourceSide <= 0) return@withContext null
            var sample = 1
            while (sourceSide / sample > maxSide) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@withContext null
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= maxSide || longest <= 0) {
                bitmap
            } else {
                val scale = maxSide.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== bitmap) bitmap.recycle()
                scaled
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
