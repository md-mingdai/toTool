package com.mingdai.toTool.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** 面向用户的转换错误。 */
internal class ConvertException(message: String) : Exception(message)

/** 用户取消。 */
internal class ConvertCanceledException : Exception("canceled")

internal const val MIN_FREE_HEADROOM_BYTES = 128L * 1024 * 1024
private const val COPY_BUFFER_BYTES = 1 shl 20

internal fun humanSize(bytes: Long): String = when {
    bytes < 0 -> "未知"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

internal fun friendlyError(e: Throwable): String {
    val m = e.message ?: e.javaClass.simpleName
    return when {
        m.contains("ENOSPC", ignoreCase = true) || m.contains("No space left", ignoreCase = true) ->
            "存储空间不足，请清理后重试"
        else -> m
    }
}

internal fun pickWorkDir(context: Context, needBytes: Long): File {
    val candidates = listOfNotNull(context.externalCacheDir, context.cacheDir)
    val best = candidates.maxByOrNull { it.usableSpace } ?: context.cacheDir
    if (needBytes > 0L && best.usableSpace < needBytes) {
        throw ConvertException(
            "存储空间不足：这次转换大约需要 ${humanSize(needBytes)}，当前只剩 ${humanSize(best.usableSpace)}。" +
                "请清理一些空间后重试。",
        )
    }
    return best
}

/** 清掉上次异常退出遗留的临时文件和应用目录里的旧成品。 */
internal fun cleanupStaleFiles(context: Context) {
    listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
        dir.listFiles { f -> f.name.startsWith("conv_") }?.forEach { it.delete() }
    }
    val cutoff = System.currentTimeMillis() - 6L * 60 * 60 * 1000
    context.getExternalFilesDir("conversions")?.listFiles()?.forEach { f ->
        if (f.lastModified() < cutoff) f.delete()
    }
}

/**
 * 把选中的文件复制成应用目录里的临时文件。ffmpeg 需要能随机读取的真实路径，
 * 管道对 MP4 不可靠。固定 1 MB 缓冲，按字节汇报进度，可取消。
 */
internal fun copyUriToTemp(
    context: Context,
    uri: Uri,
    dir: File,
    totalBytes: Long,
    canceled: AtomicBoolean,
    prefix: String = "conv_in_",
    onProgress: (Float?) -> Unit,
): File {
    val name = queryFileName(context, uri)
    val ext = name.substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
    val tempFile = File(dir, "$prefix${System.nanoTime()}.$ext")
    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ConvertException("无法读取输入文件")
        input.use { src ->
            tempFile.outputStream().use { dst ->
                val buf = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                var lastReport = 0L
                onProgress(if (totalBytes > 0) 0f else null)
                while (true) {
                    if (canceled.get()) throw ConvertCanceledException()
                    val n = src.read(buf)
                    if (n < 0) break
                    dst.write(buf, 0, n)
                    copied += n
                    if (copied - lastReport >= 4L * 1024 * 1024) {
                        lastReport = copied
                        onProgress(
                            if (totalBytes > 0) {
                                (copied.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        return tempFile
    } catch (e: Exception) {
        tempFile.delete()
        throw e
    }
}

internal class PublishResult(val path: String?, val error: String?)

/** 把成品复制到系统「下载/toTool」。Android 10+ 使用 MediaStore.Downloads。 */
internal fun publishToDownloads(
    context: Context,
    file: File,
    mime: String,
    canceled: AtomicBoolean,
    onProgress: (Float?) -> Unit,
): PublishResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return PublishResult(null, "Android 9 及以下不支持")
    }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/toTool")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = try {
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    } catch (e: Exception) {
        return PublishResult(null, friendlyError(e))
    } ?: return PublishResult(null, "系统拒绝创建文件")

    try {
        val total = file.length()
        val buf = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        var lastReport = 0L
        val out = resolver.openOutputStream(uri) ?: throw IOException("无法写入下载目录")
        out.use { sink ->
            file.inputStream().use { input ->
                while (true) {
                    if (canceled.get()) throw ConvertCanceledException()
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    copied += n
                    if (total > 0 && copied - lastReport >= 4L * 1024 * 1024) {
                        lastReport = copied
                        onProgress((copied.toDouble() / total).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        file.delete()
        return PublishResult(Environment.DIRECTORY_DOWNLOADS + "/toTool/" + file.name, null)
    } catch (e: ConvertCanceledException) {
        runCatching { resolver.delete(uri, null, null) }
        throw e
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        return PublishResult(null, friendlyError(e))
    }
}

internal fun queryFileName(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else "file"
        } ?: "file"
    } catch (e: Exception) {
        "file"
    }
}

internal fun queryFileSize(context: Context, uri: Uri): Long {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) {
                val s = c.getLong(0)
                if (s >= 0) return s
            }
        }
    } catch (e: Exception) {
        // 走下面的兜底
    }
    return try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (e: Exception) {
        -1L
    }
}

internal fun sanitizeFileName(name: String, fallback: String = "output"): String {
    val clean = name.map { c ->
        if (c == '/' || c == '\\' || c in ":\"*?<>|") '_' else c
    }.joinToString("").trim().trim('.')
    return clean.ifBlank { fallback }.take(120)
}

internal fun baseNameOf(name: String): String =
    sanitizeFileName(name.substringBeforeLast('.').ifBlank { "output" })
