package com.mingdai.toTool.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.roundToInt

internal data class VideoFrameInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val frameCount: Int,
    /** false 表示 frameCount 是按 fps/duration 估算的。 */
    val frameCountReliable: Boolean,
    val hasAudio: Boolean,
)

internal data class AudioProbeInfo(
    val durationMs: Long,
    val title: String,
    val artist: String,
    val hasEmbeddedCover: Boolean,
)

internal data class ImageProbeInfo(val width: Int, val height: Int)

internal fun probeVideo(context: Context, uri: Uri): VideoFrameInfo? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            ?.equals("yes", ignoreCase = true) ?: true
        val rawFrames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            ?.toIntOrNull() ?: 0
        val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toDoubleOrNull() ?: 0.0
        val estimatedFrames = if (durationMs > 0L && captureFps > 0.0) {
            (durationMs / 1000.0 * captureFps).roundToInt()
        } else if (durationMs > 0L) {
            (durationMs / 1000.0 * 30.0).roundToInt()
        } else {
            0
        }
        val reliable = rawFrames > 0
        val frameCount = if (reliable) rawFrames else estimatedFrames
        VideoFrameInfo(
            durationMs = durationMs,
            width = width,
            height = height,
            rotationDegrees = rotation,
            frameCount = frameCount,
            frameCountReliable = reliable,
            hasAudio = hasAudio,
        )
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

internal fun probeAudio(context: Context, uri: Uri): AudioProbeInfo? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        AudioProbeInfo(
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L,
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
            hasEmbeddedCover = runCatching { retriever.embeddedPicture != null }.getOrDefault(false),
        )
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

internal fun probeImage(context: Context, uri: Uri, ext: String? = null): ImageProbeInfo? {
    if (ext == "svg") return SvgCodec.probe(context, uri)
    if (ext == "ico") return IcoCodec.probe(context, uri)?.let { ImageProbeInfo(it.width, it.height) }
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth > 0 && options.outHeight > 0) {
            ImageProbeInfo(options.outWidth, options.outHeight)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

/** 按 1-based 帧号取一帧；maxSide 不为空时返回缩小后的预览图。 */
internal fun extractVideoFrame(
    context: Context,
    uri: Uri,
    frameIndex1Based: Int,
    maxSide: Int? = null,
): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val index = (frameIndex1Based - 1).coerceAtLeast(0)
        val raw = retriever.getFrameAtIndex(index) ?: fallbackFrame(retriever, index)
        val frame = if (maxSide != null) scaleDown(raw, maxSide) else raw
        frame?.let { rotateFrame(it, retriever.rotationDegrees()) }
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** 按时间点取一帧，用于视频预览；失败时回退到首帧。 */
internal fun extractVideoFrameAt(
    context: Context,
    uri: Uri,
    timeMs: Long,
    maxSide: Int = 720,
): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val timeUs = timeMs.coerceAtLeast(0L) * 1000L
        val raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        val scaled = scaleDown(raw, maxSide)
        scaled?.let { rotateFrame(it, retriever.rotationDegrees()) }
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** 读取音频内嵌封面，用于音频工具预览。 */
internal fun extractAudioCover(context: Context, uri: Uri, maxSide: Int = 720): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val bytes = retriever.embeddedPicture ?: return null
        scaleDown(BitmapFactory.decodeByteArray(bytes, 0, bytes.size), maxSide)
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun fallbackFrame(retriever: MediaMetadataRetriever, index: Int): Bitmap? {
    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        ?.toLongOrNull() ?: 0L
    if (duration <= 0L) return null
    val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
        ?.toIntOrNull()?.takeIf { it > 0 } ?: 30
    val timeUs = (index.toDouble() / frames * duration * 1000.0).toLong()
    return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
}

private fun MediaMetadataRetriever.rotationDegrees(): Int =
    extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

private fun scaleDown(source: Bitmap?, maxSide: Int): Bitmap? {
    if (source == null) return null
    val longest = maxOf(source.width, source.height)
    if (longest <= maxSide) return source
    val scale = maxSide.toFloat() / longest
    val scaled = Bitmap.createScaledBitmap(
        source,
        (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    if (scaled !== source) source.recycle()
    return scaled
}

private fun rotateFrame(source: Bitmap, degrees: Int): Bitmap {
    val normalized = ((degrees % 360) + 360) % 360
    if (normalized == 0) return source
    val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (rotated !== source) source.recycle()
    return rotated
}


internal data class MediaSignature(
    val kind: MediaKind,
    val extension: String,
    val animated: Boolean,
)

/**
 * 先读取文件头判断真实媒体类型；文件头无法判断时才退回扩展名 / MIME。
 * 同时识别 GIF / APNG / 动画 WebP 的动画标记。
 */
internal fun detectMediaSignature(
    context: Context,
    uri: Uri,
    name: String?,
    mime: String?,
): MediaSignature {
    val ext = normalizedExtension(name)
    val header = readHeader(context, uri)
    if (ext == "svg" || mime == "image/svg+xml") {
        return MediaSignature(MediaKind.IMAGE, "svg", false)
    }
    if (ext == "ico" || mime == "image/x-icon" || mime == "image/vnd.microsoft.icon") {
        return MediaSignature(MediaKind.IMAGE, "ico", false)
    }
    if (header.size >= 12) {
        when {
            header.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> {
                return MediaSignature(MediaKind.IMAGE, "png", header.containsAscii("acTL"))
            }
            header.startsWith(0xFF, 0xD8, 0xFF) ->
                return MediaSignature(MediaKind.IMAGE, "jpg", false)
            header.startsWith(0x47, 0x49, 0x46, 0x38) ->
                return MediaSignature(MediaKind.IMAGE, "gif", gifImageCount(header) >= 2)
            header.startsWith(0x42, 0x4D) ->
                return MediaSignature(MediaKind.IMAGE, "bmp", false)
            header.startsWith(0x52, 0x49, 0x46, 0x46) -> {
                val riff = header.ascii(8, 4)
                when (riff) {
                    "WEBP" -> {
                        // FFmpeg 的 WebP 解码器对动画 WebP 支持不稳定；这里按静态图处理。
                        return MediaSignature(MediaKind.IMAGE, "webp", false)
                    }
                    "WAVE" -> return MediaSignature(MediaKind.AUDIO, "wav", false)
                    "AVI " -> return MediaSignature(MediaKind.VIDEO, "avi", false)
                }
            }
            header.ascii(4, 4) == "ftyp" -> {
                val brand = header.ascii(8, 4)
                when {
                    brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1") ->
                        return MediaSignature(MediaKind.IMAGE, "heic", false)
                    brand in setOf("avif", "avis") ->
                        return MediaSignature(MediaKind.IMAGE, "avif", false)
                    brand in setOf("M4A ", "M4B ", "M4P ", "F4A ", "F4B ") ->
                        return MediaSignature(MediaKind.AUDIO, "m4a", false)
                    brand == "qt  " ->
                        return MediaSignature(MediaKind.VIDEO, ext.ifBlank { "mov" }, false)
                    brand in setOf("3gp4", "3gp5", "3g2a") ->
                        return MediaSignature(MediaKind.VIDEO, "3gp", false)
                    brand in setOf("isom", "iso2", "mp41", "mp42", "avc1", "M4V ", "dash") ->
                        return MediaSignature(MediaKind.VIDEO, ext.ifBlank { "mp4" }, false)
                }
            }
            header.startsWith(0x1A, 0x45, 0xDF, 0xA3) -> {
                val isAudio = mime?.startsWith("audio/") == true
                return MediaSignature(
                    if (isAudio) MediaKind.AUDIO else MediaKind.VIDEO,
                    ext.ifBlank { if (isAudio) "mka" else "mkv" },
                    false,
                )
            }
            header.startsWith(0x66, 0x4C, 0x61, 0x43) ->
                return MediaSignature(MediaKind.AUDIO, "flac", false)
            header.startsWith(0x4F, 0x67, 0x67, 0x53) ->
                return MediaSignature(
                    if (ext == "ogv") MediaKind.VIDEO else MediaKind.AUDIO,
                    ext.ifBlank { "ogg" },
                    false,
                )
            header.startsWith(0x49, 0x44, 0x33) ->
                return MediaSignature(MediaKind.AUDIO, "mp3", false)
            header.startsWith(0x66, 0x4C, 0x56) ->
                return MediaSignature(MediaKind.VIDEO, "flv", false)
            header.size >= 2 && (header[0].toInt() and 0xFF) == 0xFF &&
                (header[1].toInt() and 0xE0) == 0xE0 -> {
                val isAdts = (header[1].toInt() and 0xF6) == 0xF0
                return MediaSignature(
                    MediaKind.AUDIO,
                    if (isAdts && (ext == "aac" || mime?.contains("aac") == true)) "aac" else "mp3",
                    false,
                )
            }
        }
    }
    val kind = detectMediaKind(name, mime)
    return MediaSignature(kind, ext, false)
}

private fun readHeader(context: Context, uri: Uri): ByteArray {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input -> input.readHeaderBytes(64 * 1024) }
            ?: ByteArray(0)
    } catch (e: Exception) {
        ByteArray(0)
    }
}

private fun InputStream.readHeaderBytes(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(limit.coerceAtMost(8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (total < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - total))
        if (read < 0) break
        out.write(buffer, 0, read)
        total += read
    }
    return out.toByteArray()
}

private fun ByteArray.startsWith(vararg bytes: Int): Boolean {
    if (size < bytes.size) return false
    bytes.forEachIndexed { index, value ->
        if (this[index].toInt() and 0xFF != value) return false
    }
    return true
}

private fun ByteArray.ascii(start: Int, count: Int): String {
    if (start < 0 || start + count > size) return ""
    return String(this, start, count, Charsets.US_ASCII)
}

private fun ByteArray.containsAscii(value: String): Boolean {
    val needle = value.toByteArray(Charsets.US_ASCII)
    if (needle.isEmpty() || needle.size > size) return false
    for (index in 0..size - needle.size) {
        var match = true
        for (j in needle.indices) {
            if (this[index + j] != needle[j]) {
                match = false
                break
            }
        }
        if (match) return true
    }
    return false
}

/** 简单解析 GIF 块，返回图像描述符数量（至少 2 个就是动图）。 */
private fun gifImageCount(bytes: ByteArray): Int {
    if (bytes.size < 13) return 1
    var pos = 13
    val packed = bytes[10].toInt() and 0xFF
    if (packed and 0x80 != 0) {
        pos += 3 * (1 shl ((packed and 0x07) + 1))
    }
    var count = 0
    while (pos < bytes.size) {
        when (bytes[pos].toInt() and 0xFF) {
            0x3B -> break
            0x21 -> {
                pos += 2
                pos = skipGifSubBlocks(bytes, pos)
            }
            0x2C -> {
                count++
                if (pos + 10 > bytes.size) break
                val imagePacked = bytes[pos + 9].toInt() and 0xFF
                pos += 10
                if (imagePacked and 0x80 != 0) {
                    pos += 3 * (1 shl ((imagePacked and 0x07) + 1))
                }
                if (pos >= bytes.size) break
                pos += 1 // LZW minimum code size
                pos = skipGifSubBlocks(bytes, pos)
            }
            else -> break
        }
    }
    return count.coerceAtLeast(if (count == 0) 1 else count)
}

private fun skipGifSubBlocks(bytes: ByteArray, start: Int): Int {
    var pos = start
    while (pos < bytes.size) {
        val size = bytes[pos].toInt() and 0xFF
        pos += 1
        if (size == 0) break
        pos += size
    }
    return pos
}
