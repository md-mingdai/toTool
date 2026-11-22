package com.mingdai.toTool.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.Html
import android.webkit.MimeTypeMap
import com.mingdai.toTool.media.queryFileName
import com.mingdai.toTool.media.queryFileSize
import java.io.IOException

internal data class SharePayload(
    val subject: String?,
    val text: String?,
    val mimeType: String?,
    val uris: List<Uri>,
) {
    val hasContent: Boolean get() = !text.isNullOrBlank() || uris.isNotEmpty()
}

internal data class SharedFileInfo(
    val name: String,
    val mime: String,
    val size: Long,
)

internal data class ShareExportResult(
    val exported: Int,
    val failures: List<String>,
)

/** 读取系统分享 Intent 里的文本和文件 URI；ClipData 与 EXTRA_STREAM 都兼容。 */
internal fun parseSharePayload(intent: Intent?): SharePayload {
    if (intent == null) return SharePayload(null, null, null, emptyList())
    val clip = intent.clipData
    var text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    if (text.isNullOrBlank()) {
        text = clip?.let { data ->
            (0 until data.itemCount).firstNotNullOfOrNull { index ->
                data.getItemAt(index).text?.toString()?.takeIf { it.isNotBlank() }
            }
        }
    }
    if (text.isNullOrBlank()) {
        val html = intent.getCharSequenceExtra(Intent.EXTRA_HTML_TEXT)?.toString()
        if (!html.isNullOrBlank()) {
            text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        }
    }
    val uris = LinkedHashSet<Uri>()
    clip?.let { data ->
        for (index in 0 until data.itemCount) {
            data.getItemAt(index).uri?.let(uris::add)
        }
    }
    singleUriExtra(intent, Intent.EXTRA_STREAM)?.let(uris::add)
    uriListExtra(intent, Intent.EXTRA_STREAM)?.forEach(uris::add)
    intent.data?.let(uris::add)
    return SharePayload(
        subject = (
            intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TITLE)
            )?.toString()?.takeIf { it.isNotBlank() },
        text = text?.takeIf { it.isNotBlank() },
        mimeType = intent.type,
        uris = uris.toList(),
    )
}

@Suppress("DEPRECATION")
private fun singleUriExtra(intent: Intent, name: String): Uri? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(name, Uri::class.java)
    } else {
        intent.getParcelableExtra(name)
    }
}.getOrNull()

@Suppress("DEPRECATION")
private fun uriListExtra(intent: Intent, name: String): List<Uri>? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableArrayListExtra(name, Uri::class.java)
    } else {
        intent.getParcelableArrayListExtra(name)
    }
}.getOrNull()

internal fun sharedFileInfo(context: Context, uri: Uri): SharedFileInfo {
    val rawName = queryFileName(context, uri)
    val name = rawName.takeIf { it.isNotBlank() && it != "file" } ?: "shared_file"
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            name.substringAfterLast('.', "").lowercase(),
        )
        ?: "application/octet-stream"
    return SharedFileInfo(name = name, mime = mime, size = queryFileSize(context, uri))
}

internal fun safeShareName(name: String): String = buildString {
    for (ch in name) {
        append(
            if (ch == '/' || ch == '\\' || ch in ":\"*?<>|" || ch.code < 32) '_' else ch,
        )
    }
}.trim().trim('.').take(120).ifBlank { "shared" }

internal fun uniqueShareName(name: String, used: MutableSet<String>): String {
    if (used.add(name)) return name
    val hasExtension = name.substringAfterLast('.', "").isNotBlank()
    val extension = if (hasExtension) name.substringAfterLast('.') else ""
    val base = if (hasExtension) name.substringBeforeLast('.') else name
    var index = 2
    while (true) {
        val candidate = if (extension.isBlank()) "$base ($index)" else "$base ($index).$extension"
        if (used.add(candidate)) return candidate
        index++
    }
}

private sealed interface ExportEntry {
    val name: String
    val mime: String

    data class Text(val text: String, override val name: String) : ExportEntry {
        override val mime: String = "text/plain"
    }

    data class File(val uri: Uri, override val name: String, override val mime: String) : ExportEntry
}

/** 把分享内容写进用户通过 Documents 文件夹选择器选中的目录。 */
internal fun exportShareToTree(
    context: Context,
    treeUri: Uri,
    payload: SharePayload,
    onProgress: (index: Int, total: Int, name: String) -> Unit,
): ShareExportResult {
    val usedNames = mutableSetOf<String>()
    val entries = mutableListOf<ExportEntry>()
    payload.text?.takeIf { it.isNotBlank() }?.let { text ->
        val subject = payload.subject?.let(::safeShareName)?.takeIf { it.isNotBlank() }
        val baseName = subject ?: "share"
        val fileName = if (baseName.endsWith(".txt", ignoreCase = true)) {
            baseName
        } else {
            "$baseName.txt"
        }
        entries += ExportEntry.Text(text, uniqueShareName(fileName, usedNames))
    }
    payload.uris.forEach { uri ->
        val info = runCatching { sharedFileInfo(context, uri) }.getOrElse {
            SharedFileInfo("shared_file", "application/octet-stream", -1L)
        }
        var name = safeShareName(info.name)
        if (!name.contains('.')) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(info.mime)
                ?.takeIf { it.isNotBlank() }
                ?.let { name = "$name.$it" }
        }
        entries += ExportEntry.File(uri, uniqueShareName(name, usedNames), info.mime)
    }

    val resolver = context.contentResolver
    val parent = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    var exported = 0
    val failures = mutableListOf<String>()
    entries.forEachIndexed { index, entry ->
        onProgress(index, entries.size, entry.name)
        var createdUri: Uri? = null
        try {
            val created = DocumentsContract.createDocument(resolver, parent, entry.mime, entry.name)
                ?: throw IOException("无法在所选目录创建文件")
            createdUri = created
            when (entry) {
                is ExportEntry.Text -> {
                    val output = resolver.openOutputStream(created)
                        ?: throw IOException("无法写入导出文件")
                    output.use { it.write(entry.text.toByteArray(Charsets.UTF_8)) }
                }
                is ExportEntry.File -> {
                    val input = resolver.openInputStream(entry.uri)
                        ?: throw IOException("无法读取分享文件")
                    val output = resolver.openOutputStream(created)
                        ?: throw IOException("无法写入导出文件")
                    input.use { source -> output.use { target -> source.copyTo(target, 1 shl 20) } }
                }
            }
            exported++
        } catch (e: Exception) {
            failures += "${entry.name}: ${e.message ?: e.javaClass.simpleName}"
            createdUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        }
    }
    onProgress(entries.size, entries.size, "")
    return ShareExportResult(exported = exported, failures = failures)
}
