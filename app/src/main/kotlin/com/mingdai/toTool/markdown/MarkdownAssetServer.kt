package com.mingdai.toTool.markdown

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * Serves the bundled KaTeX CSS / JS / fonts to the preview and export WebViews.
 *
 * The Markdown HTML is loaded with [BASE_URL] as its base, so assets such as
 * `markdown/katex/katex.min.css` can stay as small relative links instead of
 * being inlined (the fonts alone are about 600 KB).
 */
internal object MarkdownAssetServer {
    const val BASE_URL = "https://totool.local/"

    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? =
        open(context, request.url)

    fun open(context: Context, uri: Uri?): WebResourceResponse? {
        if (uri?.host != "totool.local") return null
        val path = uri.path?.removePrefix("/") ?: return null
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
