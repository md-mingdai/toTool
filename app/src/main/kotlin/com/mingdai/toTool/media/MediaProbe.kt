package com.mingdai.toTool.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
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

internal fun probeImage(context: Context, uri: Uri): ImageProbeInfo? {
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
