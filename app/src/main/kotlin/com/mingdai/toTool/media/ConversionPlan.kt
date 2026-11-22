package com.mingdai.toTool.media

import android.net.Uri
import java.util.Locale
import kotlin.math.ceil

internal enum class VisualMode(val label: String) {
    COVER("封面"),
    WAVEFORM("波形"),
    SOLID("纯色"),
    CUSTOM("自定义"),
}

internal enum class WaveformMode(val ffmpeg: String, val label: String) {
    CLINE("cline", "填充"),
    LINE("line", "线条"),
    POINT("point", "点"),
    P2P("p2p", "峰对峰"),
}

internal enum class ResolutionPreset(val width: Int, val height: Int, val label: String) {
    P720(1280, 720, "720P"),
    P1080(1920, 1080, "1080P"),
}

internal enum class ConversionBranch {
    IMAGE_IMAGE,
    IMAGE_VIDEO,
    VIDEO_VIDEO,
    VIDEO_AUDIO,
    VIDEO_IMAGE,
    VIDEO_GIF,
    AUDIO_AUDIO,
    AUDIO_VIDEO,
    IMAGE_GIF,
}

internal data class GifComposeItem(
    val uri: Uri,
    val name: String,
    val holdFrames: Int = 5,
    val animated: Boolean = false,
    val original: Boolean = false,
)

internal data class SourceMedia(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val kind: MediaKind,
    val extension: String,
    val mime: String?,
    val animated: Boolean = false,
)

internal data class ConversionPlan(
    val branch: ConversionBranch,
    val source: SourceMedia,
    val target: TargetOption,
    val durationSeconds: Double? = null,
    val frameIndex: Int? = null,
    val visualMode: VisualMode = VisualMode.COVER,
    val waveformMode: WaveformMode = WaveformMode.CLINE,
    val waveformColor: Int = 0xFF8BC34A.toInt(),
    val waveformBackground: Int = 0xFF000000.toInt(),
    val resolution: ResolutionPreset = ResolutionPreset.P720,
    val solidColor: Int = 0xFF162018.toInt(),
    val customImage: Uri? = null,
    val music: Uri? = null,
    val gifItems: List<GifComposeItem> = emptyList(),
) {
    val baseName: String get() = baseNameOf(source.name)

    val outputName: String
        get() = when (branch) {
            ConversionBranch.VIDEO_IMAGE -> {
                val frame = ((frameIndex ?: 1) - 1).coerceAtLeast(0) + 1
                "${baseName}_frame_$frame.${target.extension}"
            }
            else -> "$baseName.${target.extension}"
        }
}

internal fun branchFor(
    source: MediaKind,
    target: MediaKind,
    targetFormat: String,
): ConversionBranch? = when (source) {
    MediaKind.IMAGE -> when (target) {
        MediaKind.IMAGE -> if (targetFormat == "GIF") ConversionBranch.IMAGE_GIF else ConversionBranch.IMAGE_IMAGE
        MediaKind.VIDEO -> ConversionBranch.IMAGE_VIDEO
        else -> null
    }
    MediaKind.VIDEO -> when (target) {
        MediaKind.VIDEO -> ConversionBranch.VIDEO_VIDEO
        MediaKind.AUDIO -> ConversionBranch.VIDEO_AUDIO
        MediaKind.IMAGE -> if (targetFormat == "GIF") ConversionBranch.VIDEO_GIF else ConversionBranch.VIDEO_IMAGE
        else -> null
    }
    MediaKind.AUDIO -> when (target) {
        MediaKind.AUDIO -> ConversionBranch.AUDIO_AUDIO
        MediaKind.VIDEO -> ConversionBranch.AUDIO_VIDEO
        else -> null
    }
    MediaKind.UNKNOWN -> null
}

internal fun ConversionBranch.needsFfmpeg(): Boolean = when (this) {
    ConversionBranch.IMAGE_IMAGE,
    ConversionBranch.VIDEO_IMAGE -> false
    else -> true
}

internal fun safetyDurationSeconds(durationMs: Long): String? {
    if (durationMs <= 0L) return null
    return (ceil(durationMs / 1000.0).toLong() + 1L).toString()
}

internal fun formatSeconds(value: Double): String = String.format(Locale.US, "%.3f", value)

internal fun expectedSourceDurationMs(video: VideoFrameInfo?, audio: AudioProbeInfo?): Long =
    video?.durationMs?.takeIf { it > 0L } ?: audio?.durationMs?.takeIf { it > 0L } ?: 0L
