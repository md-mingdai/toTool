package com.mingdai.toTool.share

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.R
import com.mingdai.toTool.media.extractAudioCover
import com.mingdai.toTool.media.extractVideoFrameAt
import com.mingdai.toTool.media.humanSize
import com.mingdai.toTool.ui.components.BitmapPreviewCard
import com.mingdai.toTool.ui.components.MessageCard
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.copyText
import com.mingdai.toTool.ui.screens.tools.ImageOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class SharePreview(val bitmap: Bitmap?, val loading: Boolean)

@Composable
internal fun ShareDumpScreen(payload: SharePayload, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(0f) }
    var stage by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null || exporting) return@rememberLauncherForActivityResult
        scope.launch {
            exporting = true
            notice = null
            error = null
            progress = 0f
            stage = "准备导出…"
            try {
                val result = withContext(Dispatchers.IO) {
                    exportShareToTree(context, tree, payload) { index, total, name ->
                        stage = if (total > 0) "正在导出 ${index + 1}/$total：$name" else "正在导出…"
                        progress = if (total > 0) index.toFloat() / total else null
                    }
                }
                progress = 1f
                if (result.exported > 0) {
                    notice = "已导出 ${result.exported} 项到所选文件夹。"
                }
                if (result.failures.isNotEmpty()) {
                    error = buildString {
                        append("部分项目导出失败：\n")
                        append(result.failures.take(6).joinToString("\n"))
                        if (result.failures.size > 6) append("\n…")
                    }
                }
            } catch (e: Exception) {
                error = "导出失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                exporting = false
                stage = ""
            }
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.share_dump_title),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { padding ->
        ToolBody(padding) {
            Text("已收到分享内容", style = MiuixTheme.textStyles.headline2)
            Text(
                "类型：${payload.mimeType ?: "未知"}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            payload.subject?.let { subject ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "标题",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        SelectionContainer {
                            Text(subject, style = MiuixTheme.textStyles.body1)
                        }
                    }
                }
            }
            payload.text?.let { text ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "文本",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        SelectionContainer {
                            Text(
                                if (text.length > 20_000) text.take(20_000) + "…" else text,
                                style = MiuixTheme.textStyles.body1,
                            )
                        }
                        if (text.length > 20_000) {
                            Text(
                                "内容较长，仅显示前 20000 字符；导出会保存完整内容。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        SecondaryButton(
                            onClick = { copyText(context, text, "分享文本") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("复制文本") }
                    }
                }
            }
            if (payload.uris.isNotEmpty()) {
                Text("文件 (${payload.uris.size})", style = MiuixTheme.textStyles.title4)
                if (payload.uris.size == 1) {
                    val uri = payload.uris.first()
                    val info = rememberSharedFileInfo(uri)
                    if (info != null && isPreviewable(info.mime)) {
                        val preview = rememberSharePreview(uri, info.mime)
                        if (preview.loading || preview.bitmap != null) {
                            BitmapPreviewCard(
                                bitmap = preview.bitmap,
                                modifier = Modifier.fillMaxWidth().height(220.dp),
                                placeholder = "正在生成预览…",
                                busy = preview.loading,
                            )
                        }
                    }
                }
                payload.uris.forEach { uri ->
                    val info = rememberSharedFileInfo(uri)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    info?.name ?: uri.toString(),
                                    style = MiuixTheme.textStyles.body1,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    info?.let { "${it.mime} · ${humanSize(it.size)}" } ?: "正在读取文件信息…",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            if (!payload.hasContent) {
                MessageCard(
                    title = "没有可显示的内容",
                    message = "分享方没有提供文本或文件。",
                    container = MiuixTheme.colorScheme.secondaryContainer,
                    content = MiuixTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (exporting) {
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Text(
                    stage.ifBlank { "正在导出…" },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            notice?.let {
                MessageCard(
                    title = null,
                    message = it,
                    container = MiuixTheme.colorScheme.secondaryContainer,
                    content = MiuixTheme.colorScheme.onSecondaryContainer,
                )
            }
            error?.let {
                MessageCard(
                    title = "导出问题",
                    message = it,
                    container = MiuixTheme.colorScheme.errorContainer,
                    content = MiuixTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                "导出会打开系统文件夹选择器，只会写入你选中的位置。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            PrimaryButton(
                onClick = {
                    if (!exporting) {
                        runCatching { treePicker.launch(null) }
                            .onFailure { error = "无法打开文件夹选择器：${it.message ?: it.javaClass.simpleName}" }
                    }
                },
                enabled = payload.hasContent && !exporting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (exporting) "正在导出…" else "导出到…") }
            SecondaryButton(
                onClick = onClose,
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("关闭") }
        }
    }
}

@Composable
private fun rememberSharedFileInfo(uri: Uri): SharedFileInfo? {
    val context = LocalContext.current
    var info by remember(uri) { mutableStateOf<SharedFileInfo?>(null) }
    LaunchedEffect(uri) {
        info = withContext(Dispatchers.IO) {
            runCatching { sharedFileInfo(context, uri) }.getOrNull()
        }
    }
    return info
}

private fun isPreviewable(mime: String): Boolean =
    mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")

@Composable
private fun rememberSharePreview(uri: Uri, mime: String): SharePreview {
    val context = LocalContext.current
    var preview by remember(uri) { mutableStateOf(SharePreview(null, true)) }
    LaunchedEffect(uri, mime) {
        preview = SharePreview(
            withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        mime.startsWith("image/") -> ImageOps.uriToBitmap(context, uri, 960)
                        mime.startsWith("video/") -> extractVideoFrameAt(context, uri, 1000L, 960)
                        mime.startsWith("audio/") -> extractAudioCover(context, uri, 960)
                        else -> null
                    }
                }.getOrNull()
            },
            loading = false,
        )
    }
    return preview
}
