package com.mingdai.toTool.markdown

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves bundled KaTeX assets and the generated HTML page to the preview and
 * export WebViews.
 *
 * Loading the page through a virtual URL (instead of `loadDataWithBaseURL`)
 * avoids data-URL size limits on multi-megabyte Markdown documents. The page
 * has a `<base>` tag pointing at [BASE_URL], so KaTeX CSS/JS/fonts resolve to
 * the same virtual origin and are served straight from assets.
 */
internal object MarkdownAssetServer {
    const val BASE_URL = "https://totool.local/"

    private const val PAGE_PATH = "markdown-page/"
    private val pages = ConcurrentHashMap<String, String>()
    private val pageCounter = AtomicLong()

    fun registerPage(html: String): String {
        val id = pageCounter.incrementAndGet().toString()
        pages[id] = html
        return "$BASE_URL$PAGE_PATH$id"
    }

    fun unregisterPage(url: String?) {
        if (url == null) return
        val marker = "$BASE_URL$PAGE_PATH"
        if (url.startsWith(marker)) pages.remove(url.removePrefix(marker))
    }

    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? =
        open(context, request.url)

    fun open(context: Context, uri: Uri?): WebResourceResponse? {
        if (uri?.host != "totool.local") return null
        val path = uri.path?.removePrefix("/") ?: return null
        if (path.startsWith(PAGE_PATH)) {
            val id = path.removePrefix(PAGE_PATH)
            val html = pages[id] ?: return null
            return WebResourceResponse(
                "text/html",
                "UTF-8",
                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
            )
        }
        if (!path.startsWith("markdown/") || path.contains("..")) return null
        val extension = path.substringAfterLast('.', "").lowercase()
        val mime = when (extension) {
            "css" -> "text/css"
            "js" -> "application/javascript"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }
        val encoding = if (mime == "text/css" || mime == "application/javascript") "UTF-8" else null
        return try {
            WebResourceResponse(mime, encoding, context.assets.open(path))
        } catch (e: Exception) {
            null
        }
    }
}
