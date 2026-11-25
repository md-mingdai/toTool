package com.mingdai.toTool.ui.screens.tools

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.text.TextUtils
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mingdai.toTool.ui.components.BitmapPreviewCard
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.MessageCard
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.copyText
import com.mingdai.toTool.videoparse.DetectedVideoLink
import com.mingdai.toTool.videoparse.ParsedMedia
import com.mingdai.toTool.videoparse.VideoCookieStore
import com.mingdai.toTool.videoparse.VideoHttp
import com.mingdai.toTool.videoparse.VideoLinkParser
import com.mingdai.toTool.videoparse.VideoMediaKind
import com.mingdai.toTool.videoparse.VideoParseException
import com.mingdai.toTool.videoparse.VideoParseResult
import com.mingdai.toTool.videoparse.VideoParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 视频平台分享链接解析:抖音/小红书/快手/微博/AcFun/B站/Pixiv。 */
@Composable
fun VideoParseTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var cookie by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<VideoParseResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    val detected = remember(input) { VideoLinkParser.parse(input) }
    val platform = detected?.platform

    LaunchedEffect(platform) {
        cookie = platform?.let { VideoCookieStore.get(context, it) }.orEmpty()
        result = null
        error = null
        cover = null
    }

    fun clearInput() {
        input = ""
        result = null
        error = null
        cover = null
    }

    fun parse() {
        val link = detected ?: return
        if (loading) return
        if (link.platform.cookieRequired && cookie.isBlank()) {
            error = "请先粘贴 ${link.platform.label} Cookie"
            return
        }
        scope.launch {
            loading = true
            error = null
            result = null
            cover = null
            try {
                val parsed = withContext(Dispatchers.IO) { VideoParsers.parse(link, cookie) }
                VideoCookieStore.set(context, link.platform, cookie)
                result = parsed
                if (parsed.cover.isNotBlank()) {
                    cover = VideoHttp.getBitmap(parsed.cover, parsed.coverHeaders)
                }
            } catch (e: VideoParseException) {
                error = e.message ?: "解析失败"
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    val idle = result == null && error == null && !loading

    val form: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                label = "粘贴视频分享链接或分享文案",
                minLines = 3,
                maxLines = 6,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = ::clearInput) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "清空输入",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            )
            detected?.let { link ->
                ReviewPlatformLine(link)
            } ?: Text(
                "支持抖音、小红书、快手、微博、AcFun、哔哩哔哩、Pixiv。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (platform?.needsCookie == true) {
                TextField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    label = if (platform.cookieRequired) "Cookie（必填）" else "Cookie（可选）",
                    minLines = 2,
                    maxLines = 5,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                )
                HintText(platform.cookieHint)
            }
            PrimaryButton(
                onClick = ::parse,
                enabled = detected != null && !loading &&
                    (!detected.platform.cookieRequired || cookie.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (loading) "正在解析…" else "解析链接") }
        }
    }

    if (idle) {
        IdleVideoParseLayout(padding, form)
    } else {
        ToolBody(padding) {
            form()
            if (loading) {
                LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
                Text(
                    "正在请求平台页面…",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            error?.let {
                MessageCard(
                    title = "解析失败",
                    message = it,
                    container = MiuixTheme.colorScheme.errorContainer,
                    content = MiuixTheme.colorScheme.onErrorContainer,
                )
            }
            result?.let { parsed ->
                VideoParseResultCard(parsed, cover)
            }
        }
    }
}

@Composable
private fun IdleVideoParseLayout(padding: PaddingValues, form: @Composable () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Link,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("视频链接解析", style = MiuixTheme.textStyles.title4)
            Spacer(Modifier.height(6.dp))
            Text(
                "粘贴分享链接或分享文案，解析视频与图集直链",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            form()
        }
    }
}

@Composable
private fun ReviewPlatformLine(link: DetectedVideoLink) {
    Text(
        "识别平台：${link.platform.label}",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.primary,
    )
    if (link.platform.cookieRequired) {
        HintText("该平台需要登录 Cookie；Cookie 只会保存在本机应用私有目录。")
    }
}

@Composable
private fun VideoParseResultCard(result: VideoParseResult, cover: Bitmap?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(result.platform.label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.primary)
            Text(result.title.ifBlank { "未命名作品" }, style = MiuixTheme.textStyles.title4)
            if (result.author.isNotBlank()) {
                Text(
                    "作者：${result.author}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (result.text.isNotBlank() && result.text != result.title) {
                Text(
                    if (result.text.length > 1200) result.text.take(1200) + "…" else result.text,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (cover != null) {
                BitmapPreviewCard(
                    bitmap = cover,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
            }
            if (result.notice.isNotBlank()) {
                HintText(result.notice)
            }
        }
    }
    SectionLabel("媒体（${result.medias.size}）")
    result.medias.forEachIndexed { index, media ->
        MediaCard(
            media = media,
            index = index,
            platformLabel = result.platform.label,
            cover = cover,
        )
    }
    HintText("部分直链需要 Referer/Cookie 才能访问；应用内下载会自动带上解析时得到的请求头。m3u8 请复制直链后用支持 HLS 的工具下载。")
}

@Composable
private fun MediaCard(
    media: ParsedMedia,
    index: Int,
    platformLabel: String,
    cover: Bitmap?,
) {
    val context = LocalContext.current
    var preview by remember(media.url) { mutableStateOf<Bitmap?>(null) }
    var previewLoading by remember(media.url) {
        mutableStateOf(media.kind == VideoMediaKind.IMAGE)
    }

    if (media.kind == VideoMediaKind.IMAGE) {
        LaunchedEffect(media.url) {
            preview = previewSemaphore.withPermit {
                VideoHttp.getBitmap(
                    url = media.url,
                    headers = media.headers,
                    maxSide = 480,
                    maxEncodedBytes = 12 * 1024 * 1024,
                )
            }
            previewLoading = false
        }
    }

    val kind = when (media.kind) {
        VideoMediaKind.VIDEO -> "视频"
        VideoMediaKind.IMAGE -> "图片"
        VideoMediaKind.AUDIO -> "音频"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$kind ${index + 1}",
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.weight(1f),
                )
                if (media.quality.isNotBlank()) {
                    Text(
                        media.quality,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
            if (media.kind == VideoMediaKind.VIDEO) {
                VideoWebPreview(
                    media = media,
                    cover = cover,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
            }
            if (media.kind == VideoMediaKind.IMAGE) {
                BitmapPreviewCard(
                    bitmap = preview,
                    placeholder = if (previewLoading) "正在加载预览..." else "预览加载失败",
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
            }
            Text(
                media.fileName.ifBlank { "${platformLabel}_${index + 1}" },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    onClick = {
                        copyText(context, media.url, "直链")
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("复制", maxLines = 1) }
                if (!media.isHls) {
                    SecondaryButton(
                        onClick = { enqueueDownload(context, media, index) },
                        modifier = Modifier.weight(1f),
                    ) { Text("下载", maxLines = 1) }
                }
                SecondaryButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(media.url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            Toast.makeText(context, "没有可打开该链接的应用", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("打开", maxLines = 1) }
            }
        }
    }
}

private val previewSemaphore = Semaphore(3)

/**
 * 视频预览：优先用 WebView 播放直链；需要额外请求头、HLS 不被支持或加载失败时，
 * 自动回退到解析结果封面，并保持下载/复制/打开按钮可用。
 */
@Composable
private fun VideoWebPreview(
    media: ParsedMedia,
    cover: Bitmap?,
    modifier: Modifier = Modifier,
) {
    var ready by remember(media.url) { mutableStateOf(false) }
    var failed by remember(media.url) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
    ) {
        BitmapPreviewCard(
            bitmap = cover,
            modifier = Modifier.fillMaxSize(),
            placeholder = when {
                failed && cover == null -> "视频预览不可用"
                failed -> "仅显示封面"
                else -> "正在加载视频预览…"
            },
            busy = !ready && !failed,
        )
        if (!failed) {
            key(media.url) {
                var webView by remember { mutableStateOf<WebView?>(null) }
                DisposableEffect(media.url) {
                    onDispose {
                        webView?.let { view ->
                            view.stopLoading()
                            view.loadUrl("about:blank")
                            view.destroy()
                        }
                        webView = null
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize().alpha(if (ready) 1f else 0f),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setBackgroundColor(android.graphics.Color.BLACK)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = false
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            media.headers["User-Agent"]
                                ?.takeIf { it.isNotBlank() }
                                ?.let { settings.userAgentString = it }
                            CookieManager.getInstance().setAcceptCookie(true)
                            applyPreviewCookies(media)
                            val previewView = this
                            addJavascriptInterface(
                                object {
                                    @android.webkit.JavascriptInterface
                                    fun onReady() {
                                        previewView.post { ready = true }
                                    }

                                    @android.webkit.JavascriptInterface
                                    fun onError() {
                                        previewView.post { failed = true }
                                    }
                                },
                                "TtVideo",
                            )
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (!request.isForMainFrame && !ready) {
                                        view.post { failed = true }
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    errorResponse: WebResourceResponse,
                                ) {
                                    if (!request.isForMainFrame && !ready) {
                                        view.post { failed = true }
                                    }
                                }
                            }
                            loadDataWithBaseURL(
                                media.headers["Referer"]
                                    ?.takeIf { it.startsWith("http") }
                                    ?: media.url,
                                videoPreviewHtml(media.url),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                            webView = this
                        }
                    },
                )
            }
        }
    }
}

private fun applyPreviewCookies(media: ParsedMedia) {
    val cookie = media.headers["Cookie"]?.takeIf { it.isNotBlank() } ?: return
    val manager = CookieManager.getInstance()
    val targets = listOfNotNull(
        media.url,
        media.headers["Referer"]?.takeIf { it.startsWith("http") },
    )
    cookie.split(';')
        .map { it.trim() }
        .filter { it.contains('=') }
        .forEach { pair ->
            targets.forEach { target ->
                runCatching { manager.setCookie(target, "$pair; path=/") }
            }
        }
    manager.flush()
}

private fun videoPreviewHtml(url: String): String {
    val escaped = TextUtils.htmlEncode(url)
    return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
          <style>
            html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}
            video{width:100%;height:100%;object-fit:contain;background:#000}
          </style>
        </head>
        <body>
          <video id="v" src="$escaped" controls playsinline autoplay preload="metadata"></video>
          <script>
            (function(){
              var v=document.getElementById('v');
              var done=false;
              function start(){
                var p=v.play();
                if(p&&p.catch)p.catch(function(){
                  v.muted=true;
                  var q=v.play();
                  if(q&&q.catch)q.catch(function(){});
                });
              }
              function ok(){if(done)return;done=true;start();try{TtVideo.onReady()}catch(e){}}
              function bad(){if(done)return;done=true;try{TtVideo.onError()}catch(e){}}
              v.addEventListener('loadedmetadata',ok);
              v.addEventListener('canplay',ok);
              v.addEventListener('error',function(){done=true;try{TtVideo.onError()}catch(e){}});
              setTimeout(function(){if(v.readyState===0)bad()},10000);
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}

private fun enqueueDownload(context: Context, media: ParsedMedia, index: Int) {
    val name = (media.fileName.ifBlank {
        "video_${System.currentTimeMillis()}_${index + 1}.${extensionFor(media.kind)}"
    }).replace(Regex("""[\\/:*?"<>|]"""), "_")
    val mime = when (media.kind) {
        VideoMediaKind.VIDEO -> "video/mp4"
        VideoMediaKind.IMAGE -> "image/jpeg"
        VideoMediaKind.AUDIO -> "audio/mpeg"
    }
    try {
        val request = DownloadManager.Request(Uri.parse(media.url))
            .setTitle(name)
            .setMimeType(mime)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "toTool/VideoParse/$name",
            )
        media.headers.forEach { (key, value) -> request.addRequestHeader(key, value) }
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: throw IllegalStateException("系统下载服务不可用")
        manager.enqueue(request)
        Toast.makeText(context, "已加入系统下载：$name", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "下载失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
    }
}

private fun extensionFor(kind: VideoMediaKind): String = when (kind) {
    VideoMediaKind.VIDEO -> "mp4"
    VideoMediaKind.IMAGE -> "jpg"
    VideoMediaKind.AUDIO -> "mp3"
}
