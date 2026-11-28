package com.mingdai.toTool.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.mingdai.toTool.media.saveImageToGallery
import com.mingdai.toTool.ui.components.findActivity
import com.mingdai.toTool.ui.screens.tools.MemoryBudget
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders a complete HTML page in an off-screen software WebView and turns it
 * into a long PNG.
 *
 * A WebView is used (instead of screenshotting the preview) so the exported
 * image contains the whole document, not only the visible 400dp preview area.
 * It is attached just outside the window so Chromium still lays it out and
 * paints images/fonts normally.
 */
object MarkdownImageExporter {
    private const val BRIDGE_NAME = "ToToolMarkdown"
    private const val MAX_RENDER_HEIGHT_PX = 40_000
    private const val PAGE_TIMEOUT_MS = 20_000L

    private data class PageSize(val cssHeight: Int, val cssWidth: Int)

    suspend fun export(
        context: Context,
        html: String,
        style: MarkdownStyle,
        dark: Boolean,
        widthPx: Int,
    ): Pair<String, Long> {
        val width = widthPx.coerceAtLeast(320)
        val bitmap = withContext(Dispatchers.Main) {
            capture(context, html, style, dark, width)
        }
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            return withContext(Dispatchers.IO) {
                saveImageToGallery(context, "markdown_$stamp.png", "image/png") { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw IOException("PNG 编码失败")
                    }
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @SuppressLint("JavascriptInterface", "SetJavaScriptEnabled")
    private suspend fun capture(
        context: Context,
        html: String,
        style: MarkdownStyle,
        dark: Boolean,
        widthPx: Int,
    ): Bitmap {
        val activity = context.findActivity()
            ?: throw IllegalStateException("无法获取当前页面")
        val root = activity.window.decorView as? ViewGroup
            ?: throw IllegalStateException("无法创建导出画面")

        val host = FrameLayout(activity).apply {
            translationX = -100_000f
        }
        val webView = WebView(activity).apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            setBackgroundColor(style.background(dark))
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                loadsImagesAutomatically = true
                blockNetworkImage = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                useWideViewPort = true
                loadWithOverviewMode = true
                setInitialScale(100)
            }
        }

        val ready = CompletableDeferred<PageSize>()
        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onReady(heightCss: Int, widthCss: Int) {
                    if (!ready.isCompleted) ready.complete(PageSize(heightCss, widthCss))
                }

                @JavascriptInterface
                fun onError(message: String?) {
                    if (!ready.isCompleted) {
                        ready.completeExceptionally(IllegalStateException(message ?: "页面渲染失败"))
                    }
                }
            },
            BRIDGE_NAME,
        )

        var added = false
        try {
            host.layoutParams = FrameLayout.LayoutParams(widthPx, 1)
            root.addView(host)
            added = true
            host.addView(
                webView,
                FrameLayout.LayoutParams(widthPx, 1),
            )
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(READY_SCRIPT, null)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean = true

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? = MarkdownAssetServer.intercept(context, request)
            }
            webView.loadDataWithBaseURL(MarkdownAssetServer.BASE_URL, html, "text/html", "UTF-8", null)

            val pageSize = try {
                withTimeout(PAGE_TIMEOUT_MS) { ready.await() }
            } catch (e: TimeoutCancellationException) {
                // 少部分 WebView 在页面加载完时钟表 API 不稳定；contentHeight 仍能给出
                // 一个可用的 CSS 高度，宁可导出也不要直接失败。
                val fallbackHeight = webView.contentHeight
                if (fallbackHeight <= 0) throw e
                val density = webView.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
                PageSize(fallbackHeight, max(1, (widthPx / density).roundToInt()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val fallbackHeight = webView.contentHeight
                if (fallbackHeight <= 0) throw e
                val density = webView.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
                PageSize(fallbackHeight, max(1, (widthPx / density).roundToInt()))
            }
            if (pageSize.cssHeight <= 0) {
                throw IllegalStateException("没有可导出的内容")
            }
            val cssWidth = pageSize.cssWidth.coerceAtLeast(1)
            val heightPx = (pageSize.cssHeight.toDouble() * widthPx / cssWidth)
                .roundToInt()
                .coerceAtLeast(1)

            // 软件渲染的 WebView 和导出的位图各占一份内存，按 9 字节 / 像素预算限制高度。
            val maxPixels = MemoryBudget.maxPixels(context, 9.0)
            val heightLimit = (maxPixels / widthPx)
                .toInt()
                .coerceAtLeast(1)
                .coerceAtMost(MAX_RENDER_HEIGHT_PX)
            if (heightPx > heightLimit) {
                throw IllegalStateException(
                    "内容太长，单张图片放不下（上限约 $heightLimit px），请精简或分段渲染",
                )
            }

            // 不把 WebView 撑到整篇文档那么高：Chromium 的大尺寸软件层会触发 tile
            // 内存上限，页面下半部分可能画不出来。这里让 WebView 保持一个适中的
            // 视口高度，逐屏滚动并拼接到最终的整篇位图里。
            val viewportHeight = minOf(heightPx, 2200)
            host.layoutParams = FrameLayout.LayoutParams(widthPx, viewportHeight)
            webView.layoutParams = FrameLayout.LayoutParams(widthPx, viewportHeight)
            host.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, widthPx, viewportHeight)
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, widthPx, viewportHeight)
            webView.scrollTo(0, 0)
            awaitFrame(webView)
            awaitFrame(webView)

            val bitmap = try {
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                throw IllegalStateException("图片过大，内存不足，请分段渲染")
            }
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(style.background(dark))
                val maxScroll = (heightPx - viewportHeight).coerceAtLeast(0)
                var top = 0
                while (top < heightPx) {
                    val scrollY = minOf(top, maxScroll)
                    webView.scrollTo(0, scrollY)
                    awaitFrame(webView)
                    awaitFrame(webView)
                    val bottom = minOf(top + viewportHeight, heightPx)
                    canvas.save()
                    canvas.clipRect(0, top, widthPx, bottom)
                    canvas.translate(0f, scrollY.toFloat())
                    webView.draw(canvas)
                    canvas.restore()
                    top = bottom
                }
            } catch (e: Throwable) {
                bitmap.recycle()
                throw e
            }
            return bitmap
        } finally {
            host.removeView(webView)
            webView.stopLoading()
            runCatching { webView.loadUrl("about:blank") }
            webView.destroy()
            if (added) root.removeView(host)
        }
    }

    private suspend fun awaitFrame(view: View) {
        suspendCancellableCoroutine { continuation ->
            view.postOnAnimation {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private val READY_SCRIPT = """
        (function () {
            function finish() {
                var height = Math.max(
                    document.documentElement ? document.documentElement.scrollHeight : 0,
                    document.body ? document.body.scrollHeight : 0
                );
                var width = window.innerWidth || 1;
                try { window.$BRIDGE_NAME.onReady(height, width); }
                catch (e) { try { window.$BRIDGE_NAME.onError(String(e)); } catch (ignored) {} }
            }
            function afterImages() {
                var images = Array.prototype.slice.call(document.images || []);
                Promise.all(images.map(function (image) {
                    if (image.complete) return Promise.resolve();
                    return new Promise(function (resolve) {
                        image.addEventListener('load', resolve, { once: true });
                        image.addEventListener('error', resolve, { once: true });
                    });
                })).then(function () {
                    if (document.fonts && document.fonts.ready) return document.fonts.ready;
                    return null;
                }).then(function () {
                    requestAnimationFrame(function () { requestAnimationFrame(finish); });
                }).catch(function (e) {
                    try { window.$BRIDGE_NAME.onError(String(e)); } catch (ignored) {}
                });
            }
            afterImages();
        })();
    """.trimIndent()
}
