package com.mingdai.toTool.ui.screens.tools

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mingdai.toTool.markdown.MarkdownAssetServer
import com.mingdai.toTool.markdown.MarkdownImageExporter
import com.mingdai.toTool.markdown.MarkdownRenderer
import com.mingdai.toTool.markdown.MarkdownStyle
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.KeepScreenOn
import com.mingdai.toTool.ui.components.MessageCard
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class MarkdownPreview(val html: String = "", val error: String? = null)

private data class PreviewPage(val html: String, val url: String?)

private data class HtmlExportRequest(
    val source: String,
    val style: MarkdownStyle,
    val dark: Boolean,
    val embedImages: Boolean,
)

private val MarkdownWarningContainer = Color(0xFFFFF3CD)
private val MarkdownWarningContent = Color(0xFF7A5B00)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var style by rememberSaveable { mutableStateOf(MarkdownStyle.GITHUB) }
    var dark by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var savedPath by remember { mutableStateOf<String?>(null) }
    var exportWarning by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var previewWidth by remember { mutableIntStateOf(0) }
    var exportWidthText by rememberSaveable { mutableStateOf("") }
    var htmlExporting by remember { mutableStateOf(false) }
    var embedHtmlImages by rememberSaveable { mutableStateOf(false) }
    var pendingHtmlRequest by remember { mutableStateOf<HtmlExportRequest?>(null) }

    val preview by produceState(MarkdownPreview(), source, style, dark) {
        if (source.isBlank()) {
            value = MarkdownPreview()
            return@produceState
        }
        val debounceMs = if (source.length > 4_000) {
            (source.length / 4_000).coerceIn(1, 30) * 90L
        } else {
            90L
        }
        delay(debounceMs)
        value = withContext(Dispatchers.Default) {
            try {
                MarkdownPreview(
                    MarkdownRenderer.render(context.applicationContext, source, style, dark),
                )
            } catch (e: OutOfMemoryError) {
                MarkdownPreview(error = "文档太大，当前设备内存不足，预览无法完成")
            } catch (e: Exception) {
                MarkdownPreview(error = e.message ?: "Markdown 渲染失败")
            }
        }
    }

    val density = context.resources.displayMetrics.density
    val fallbackPreviewWidth = (
        context.resources.displayMetrics.widthPixels -
            (32 * density).toInt()
        ).coerceAtLeast(320)
    val previewWidthPx = previewWidth.takeIf { it > 0 } ?: fallbackPreviewWidth
    val defaultExportWidth = previewWidthPx * 2
    val effectiveExportWidth = exportWidthText.trim().toIntOrNull()
        ?.coerceAtLeast(320)
        ?: defaultExportWidth

    val previewBackground = Color(style.background(dark))
    val previewShape = RoundedCornerShape(16.dp)
    val previewWebView = remember(context) {
        WebView(context).apply {
            setBackgroundColor(previewBackground.toArgb())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = openPreviewLink(context, request.url)

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = MarkdownAssetServer.intercept(context, request)
            }
        }
    }

    DisposableEffect(previewWebView) {
        onDispose {
            (previewWebView.tag as? PreviewPage)?.url?.let(MarkdownAssetServer::unregisterPage)
            previewWebView.stopLoading()
            runCatching { previewWebView.loadUrl("about:blank") }
            previewWebView.destroy()
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                source = withContext(Dispatchers.IO) { readMarkdownFile(context, uri) }
                savedPath = null
                error = null
            } catch (e: OutOfMemoryError) {
                error = "文档太大，当前设备内存不足，无法读取"
            } catch (e: Exception) {
                error = e.message ?: "读取 Markdown 文件失败"
            }
        }
    }

    val htmlPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val request = pendingHtmlRequest
        pendingHtmlRequest = null
        if (uri == null || request == null) {
            htmlExporting = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val html = withContext(Dispatchers.IO) {
                    MarkdownRenderer.renderSingleFile(
                        context.applicationContext,
                        request.source,
                        request.style,
                        request.dark,
                        embedRemoteImages = request.embedImages,
                    )
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(html.toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("无法写入 HTML 文件")
                }
                savedPath = "HTML 文件（${uri.lastPathSegment ?: "markdown.html"}）"
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                error = "文档或内联资源太大，当前设备内存不足"
            } catch (e: Exception) {
                error = e.message ?: "HTML 导出失败"
            } finally {
                htmlExporting = false
            }
        }
    }

    KeepScreenOn(exporting || htmlExporting)

    ToolBody(padding) {
        TextField(
            value = source,
            onValueChange = {
                source = it
                savedPath = null
                exportWarning = null
                error = null
            },
            label = "Markdown 源文本",
            modifier = Modifier.fillMaxWidth(),
            minLines = 9,
            maxLines = 14,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                onClick = {
                    importPicker.launch(
                        arrayOf(
                            "text/*",
                            "text/markdown",
                            "text/x-markdown",
                            "application/octet-stream",
                        ),
                    )
                },
                enabled = !exporting,
                modifier = Modifier.weight(1f),
            ) {
                Text("导入 .md")
            }
            SecondaryButton(
                onClick = {
                    source = ""
                    savedPath = null
                    error = null
                },
                enabled = source.isNotEmpty() && !exporting,
                modifier = Modifier.weight(1f),
            ) {
                Text("清空")
            }
        }

        Text(
            "渲染样式",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MarkdownStyle.entries.forEach { item ->
                SelectChip(
                    selected = style == item,
                    onClick = {
                        style = item
                        savedPath = null
                        exportWarning = null
                        error = null
                    },
                    label = item.label,
                    enabled = !exporting,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("深色主题", style = MiuixTheme.textStyles.body2)
                Text(
                    "预览和导出图片使用暗色配色",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Switch(
                checked = dark,
                onCheckedChange = {
                    dark = it
                    savedPath = null
                    exportWarning = null
                    error = null
                },
                enabled = !exporting,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("预览", style = MiuixTheme.textStyles.subtitle)
            if (source.isNotEmpty()) {
                Text(
                    "  ${source.length} 字符",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(430.dp)
                .onSizeChanged { previewWidth = it.width }
                .clip(previewShape)
                .background(previewBackground)
                .border(1.dp, MiuixTheme.colorScheme.outline, previewShape),
        ) {
            AndroidView(
                factory = { previewWebView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.setBackgroundColor(previewBackground.toArgb())
                    val loaded = view.tag as? PreviewPage
                    if (loaded?.html !== preview.html) {
                        loaded?.url?.let(MarkdownAssetServer::unregisterPage)
                        if (preview.html.isEmpty()) {
                            view.tag = PreviewPage("", null)
                            view.loadUrl("about:blank")
                        } else {
                            val url = MarkdownAssetServer.registerPage(preview.html)
                            view.tag = PreviewPage(preview.html, url)
                            view.loadUrl(url)
                        }
                    }
                },
            )
            if (preview.html.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().background(previewBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    if (source.isBlank() || preview.error != null) {
                        Text(
                            if (preview.error != null) "预览渲染失败" else "输入 Markdown 后在这里预览",
                            style = MiuixTheme.textStyles.body2,
                            color = if (dark) Color(0xFFB8B8B8) else Color(0xFF777777),
                            modifier = Modifier.padding(24.dp),
                        )
                    } else {
                        CircularProgressIndicator(size = 28.dp, strokeWidth = 2.dp)
                    }
                }
            }
        }

        preview.error?.let {
            MessageCard(
                "预览失败",
                it,
                MiuixTheme.colorScheme.errorContainer,
                MiuixTheme.colorScheme.onErrorContainer,
            )
        }

        Text(
            "导出图片宽度（px）",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        TextField(
            value = exportWidthText,
            onValueChange = { value ->
                exportWidthText = value.filter { it.isDigit() }.take(6)
            },
            label = "宽度（px）",
            useLabelAsPlaceholder = true,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("自动" to "", "1080" to "1080", "1440" to "1440", "2160" to "2160").forEach { (label, value) ->
                SelectChip(
                    selected = exportWidthText == value,
                    onClick = { exportWidthText = value },
                    label = label,
                    enabled = !exporting && !htmlExporting,
                )
            }
        }
        Text(
            "留空使用 ${defaultExportWidth}px（预览宽度的 2×）；当前有效宽度 ${effectiveExportWidth}px。单个 HTML 自适应宽度，不受此项影响。",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )

        if (source.length > 300_000 && exportWarning == null) {
            MessageCard(
                "提示",
                "文档较长，导出可能耗时并占用较多内存；不会限制 Markdown 长度。",
                MarkdownWarningContainer,
                MarkdownWarningContent,
            )
        }

        exportWarning?.let {
            MessageCard(
                "注意",
                it,
                MarkdownWarningContainer,
                MarkdownWarningContent,
            )
        }
        PrimaryButton(
            onClick = click@{
                if (exporting || htmlExporting || source.isBlank()) return@click
                val sourceAtClick = source
                val styleAtClick = style
                val darkAtClick = dark
                val widthAtClick = effectiveExportWidth
                exporting = true
                savedPath = null
                exportWarning = if (sourceAtClick.length > 300_000) {
                    "文档较长，正在导出；图片可能占用较多内存并耗时。"
                } else {
                    null
                }
                error = null
                scope.launch {
                    try {
                        val previewCssWidth = (previewWidthPx / density).roundToInt().coerceAtLeast(320)
                        val html = withContext(Dispatchers.Default) {
                            MarkdownRenderer.render(
                                context.applicationContext,
                                sourceAtClick,
                                styleAtClick,
                                darkAtClick,
                                viewportWidthCss = previewCssWidth,
                            )
                        }
                        val exported = MarkdownImageExporter.export(
                            context = context,
                            html = html,
                            style = styleAtClick,
                            dark = darkAtClick,
                            widthPx = widthAtClick,
                            onWarning = { exportWarning = it },
                        )
                        savedPath = exported.location
                        exportWarning = exported.warning ?: exportWarning
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OutOfMemoryError) {
                        error = "图片过大，当前设备内存不足，请降低导出宽度"
                    } catch (e: Exception) {
                        error = e.message ?: "导出失败"
                    } finally {
                        exporting = false
                    }
                }
            },
            enabled = source.isNotBlank() && !exporting && !htmlExporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (exporting) "正在导出整篇图片…" else "导出为图片")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("HTML 内嵌远程图片", style = MiuixTheme.textStyles.body2)
                Text(
                    "下载 Markdown 中的远程图片并转成 Base64；文件会更大，导出也更慢。关闭时保留原始 URL。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Switch(
                checked = embedHtmlImages,
                onCheckedChange = { embedHtmlImages = it },
                enabled = !exporting && !htmlExporting,
            )
        }

        SecondaryButton(
            onClick = click@{
                if (exporting || htmlExporting || source.isBlank()) return@click
                pendingHtmlRequest = HtmlExportRequest(source, style, dark, embedHtmlImages)
                htmlExporting = true
                savedPath = null
                exportWarning = null
                error = null
                htmlPicker.launch("markdown_${System.currentTimeMillis()}.html")
            },
            enabled = source.isNotBlank() && !exporting && !htmlExporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (htmlExporting) "正在导出单文件 HTML…" else "导出为单个 HTML（压缩为一行）")
        }

        savedPath?.let {
            MessageCard(
                null,
                "已保存到 $it",
                MiuixTheme.colorScheme.secondaryContainer,
                MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }
        error?.let {
            MessageCard(
                "操作失败",
                it,
                MiuixTheme.colorScheme.errorContainer,
                MiuixTheme.colorScheme.onErrorContainer,
            )
        }

        HintText(
            "支持 CommonMark / GFM：标题、列表、表格、任务列表、删除线、链接、图片和围栏代码块；行内 / 块级 LaTeX 公式使用本地 KaTeX 渲染。" +
                "原始 HTML 会按文本显示；代码高亮使用本地打包的 highlight.js。" +
                "导出宽度可填写像素值（留空为预览宽度的 2×），整篇文档会渲染成一张长图并保存到相册 Pictures/toTool；也可以导出为单个 HTML 文件，样式、字体和脚本全部内联并压缩为一行；可选择远程图片保留原始 URL 或下载后转成 Base64。",
        )
    }
}

private fun openPreviewLink(context: Context, uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase() ?: return false
    if (uri.host == "totool.local") return false
    if (scheme !in setOf("http", "https", "mailto", "tel")) return false
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    }.getOrDefault(true)
}

private fun readMarkdownFile(context: Context, uri: Uri): String {
    val reader = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)
        ?: throw IOException("无法读取这个文件")
    return reader.use {
        it.readText()
    }
}
