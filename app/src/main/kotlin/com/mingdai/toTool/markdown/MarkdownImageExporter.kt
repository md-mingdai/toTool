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
data class MarkdownExportResult(
    val location: String,
    val bytes: Long,
    val warning: String?,
)

object MarkdownImageExporter {
    private const val BRIDGE_NAME = "ToToolMarkdown"
    private const val PAGE_TIMEOUT_MS = 20_000L

    private data class PageSize(val cssHeight: Int, val cssWidth: Int)
    private data class CaptureResult(val bitmap: Bitmap, val warning: String?)

    suspend fun export(
        context: Context,
        html: String,
        style: MarkdownStyle,
        dark: Boolean,
        widthPx: Int,
        onWarning: ((String) -> Unit)? = null,
    ): MarkdownExportResult {
        val width = widthPx.coerceAtLeast(320)
        val captured = withContext(Dispatchers.Main) {
            capture(context, html, style, dark, width, onWarning)
        }
        val bitmap = captured.bitmap
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val saved = try {
                withContext(Dispatchers.IO) {
                    saveImageToGallery(context, "markdown_$stamp.png", "image/png") { out ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            throw IOException("PNG 编码失败")
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                throw IllegalStateException("图片过大，当前设备内存不足，请降低导出清晰度")
            }
            return MarkdownExportResult(saved.first, saved.second, captured.warning)
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
        onWarning: ((String) -> Unit)?,
    ): CaptureResult {
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
        var pageUrl: String? = null
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
                ): Boolean = request.url.host != "totool.local"

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? = MarkdownAssetServer.intercept(context, request)
            }
            pageUrl = MarkdownAssetServer.registerPage(html)
            webView.loadUrl(pageUrl)

            // 大文档 / 慢图片 / 公式排版需要更多时间；不能因为固定 20 秒就截断最终高度。
            val pageTimeout = (PAGE_TIMEOUT_MS + html.length / 10L).coerceAtMost(300_000L)
            val pageSize = try {
                withTimeout(pageTimeout) { ready.await() }
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

            // 不再按高度直接拒绝：内容很长时仍然导出，只给 UI 一个警告横幅。
            // 仍使用内存预算来估算风险，便于提示用户。
            val maxPixels = MemoryBudget.maxPixels(context, 9.0)
            val estimatedPixels = widthPx.toLong() * heightPx.toLong()
            val warning = if (estimatedPixels > maxPixels) {
                "文档较长，导出图片约 ${widthPx}×$heightPx 像素，可能占用较多内存并耗时；仍会尝试完整导出。"
            } else {
                null
            }
            if (warning != null) onWarning?.invoke(warning)

            // 让 WebView 按完整内容高度排版后一次性绘制。滚动后分段绘制在部分
            // Chromium/WebView 版本上只画可见视口，正是长图底部空白的来源。
            host.layoutParams = FrameLayout.LayoutParams(widthPx, heightPx)
            webView.layoutParams = FrameLayout.LayoutParams(widthPx, heightPx)
            host.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, widthPx, heightPx)
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, widthPx, heightPx)
            webView.scrollTo(0, 0)
            awaitFrame(webView)
            awaitFrame(webView)

            val bitmap = try {
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { bmp ->
                    bmp.eraseColor(style.background(dark))
                    webView.draw(Canvas(bmp))
                }
            } catch (e: OutOfMemoryError) {
                throw IllegalStateException("图片过大，当前设备内存不足，请降低导出清晰度")
            }
            return CaptureResult(bitmap, warning)
        } finally {
            MarkdownAssetServer.unregisterPage(pageUrl)
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
