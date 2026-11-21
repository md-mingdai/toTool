package com.mingdai.toTool.media

import java.util.Locale

/** 媒体大类。UNKNOWN 只作为源文件检测结果，不会出现在目标格式里。 */
enum class MediaKind(val label: String) {
    IMAGE("图片"),
    VIDEO("视频"),
    AUDIO("音频"),
    UNKNOWN("其他"),
}

/** 一个可选的目标格式。 */
data class TargetOption(
    val id: String,
    val medium: MediaKind,
    val extension: String,
    val mime: String,
) {
    val label: String get() = "${medium.label} · $id"
}

data class TargetGroup(
    val medium: MediaKind,
    val label: String,
    val options: List<TargetOption>,
)

/** 目标容器需要的编码器。audioOnly 表示只输出音轨。 */
internal class FormatSpec(
    val audioOnly: Boolean,
    val videoEncoders: List<String> = emptyList(),
    val audioEncoders: List<String> = emptyList(),
    val mime: String,
)

internal val FORMAT_SPECS: Map<String, FormatSpec> = mapOf(
    "MP3" to FormatSpec(true, audioEncoders = listOf("libmp3lame"), mime = "audio/mpeg"),
    "WAV" to FormatSpec(true, audioEncoders = listOf("pcm_s16le"), mime = "audio/x-wav"),
    "AAC" to FormatSpec(true, audioEncoders = listOf("aac"), mime = "audio/aac"),
    "M4A" to FormatSpec(true, audioEncoders = listOf("aac"), mime = "audio/mp4"),
    "FLAC" to FormatSpec(true, audioEncoders = listOf("flac"), mime = "audio/flac"),
    "OGG" to FormatSpec(true, audioEncoders = listOf("libvorbis", "libopus", "vorbis"), mime = "audio/ogg"),
    "MP4" to FormatSpec(false, listOf("libx264", "mpeg4"), listOf("aac"), "video/mp4"),
    "MOV" to FormatSpec(false, listOf("libx264", "mpeg4"), listOf("aac"), "video/quicktime"),
    "MKV" to FormatSpec(false, listOf("libx264", "mpeg4"), listOf("aac"), "video/x-matroska"),
    "AVI" to FormatSpec(false, listOf("mpeg4"), listOf("libmp3lame", "ac3", "aac"), "video/x-msvideo"),
    "WEBM" to FormatSpec(false, listOf("libvpx-vp9", "libvpx"), listOf("libopus", "libvorbis"), "video/webm"),
)

internal val IMAGE_FORMATS = listOf("JPG", "PNG", "WEBP", "BMP")

/** 这些容器之间互转时，手机拍的 H.264/AAC 视频可以直接换壳。 */
private val REMUX_FRIENDLY = setOf("mp4", "m4v", "mov", "mkv")

internal fun imageMime(fmt: String): String = when (fmt) {
    "JPG" -> "image/jpeg"
    "PNG" -> "image/png"
    "WEBP" -> "image/webp"
    "BMP" -> "image/bmp"
    else -> "application/octet-stream"
}

internal fun encoderArgs(name: String): List<String> = when (name) {
    "libmp3lame" -> listOf("-q:a", "2")
    "aac", "ac3" -> listOf("-b:a", "192k")
    "vorbis" -> listOf("-strict", "-2", "-q:a", "5")
    "libvorbis" -> listOf("-q:a", "5")
    "libopus" -> listOf("-b:a", "128k")
    "mpeg4" -> listOf("-q:v", "3", "-pix_fmt", "yuv420p")
    "libx264" -> listOf("-crf", "23", "-preset", "veryfast", "-pix_fmt", "yuv420p")
    "libvpx" -> listOf("-b:v", "2M", "-pix_fmt", "yuv420p")
    "libvpx-vp9" -> listOf("-b:v", "2M", "-row-mt", "1", "-pix_fmt", "yuv420p")
    else -> emptyList()
}

internal fun firstAvailable(candidates: List<String>, available: Set<String>?): String? =
    candidates.firstOrNull { available == null || it in available }

internal fun isFormatSupported(fmt: String, available: Set<String>?): Boolean {
    val spec = FORMAT_SPECS[fmt] ?: return false
    return if (spec.audioOnly) firstAvailable(spec.audioEncoders, available) != null
    else firstAvailable(spec.videoEncoders, available) != null
}

/** 音频转视频需要视频和音频编码器同时存在。 */
internal fun isAudioVideoFormatSupported(fmt: String, available: Set<String>?): Boolean {
    val spec = FORMAT_SPECS[fmt] ?: return false
    if (spec.audioOnly) return false
    return firstAvailable(spec.videoEncoders, available) != null &&
        firstAvailable(spec.audioEncoders, available) != null
}

/**
 * 依次尝试的 ffmpeg 参数方案：先换壳（快且无损），不行再转码。
 * 返回的参数不含 `-i` 和输出路径。
 */
internal fun buildAttempts(fmt: String, sourceExt: String, available: Set<String>?): List<List<String>> {
    val spec = FORMAT_SPECS[fmt] ?: return emptyList()
    val attempts = mutableListOf<List<String>>()
    if (!spec.audioOnly && sourceExt in REMUX_FRIENDLY && fmt.lowercase(Locale.ROOT) in REMUX_FRIENDLY) {
        attempts += listOf("-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy")
    }
    val audio = firstAvailable(spec.audioEncoders, available)
    if (spec.audioOnly) {
        if (audio != null) attempts += listOf("-vn", "-map", "0:a:0", "-c:a", audio) + encoderArgs(audio)
    } else {
        val video = firstAvailable(spec.videoEncoders, available)
        if (video != null) {
            val args = mutableListOf("-map", "0:v:0?", "-map", "0:a:0?", "-c:v", video)
            args += encoderArgs(video)
            if (audio != null) {
                args += listOf("-c:a", audio)
                args += encoderArgs(audio)
            }
            attempts += args
        }
    }
    return attempts
}

internal fun targetOption(fmt: String): TargetOption? {
    val spec = FORMAT_SPECS[fmt]
    return when {
        fmt in IMAGE_FORMATS -> TargetOption(fmt, MediaKind.IMAGE, fmt.lowercase(Locale.ROOT), imageMime(fmt))
        spec?.audioOnly == true -> TargetOption(fmt, MediaKind.AUDIO, fmt.lowercase(Locale.ROOT), spec.mime)
        spec != null -> TargetOption(fmt, MediaKind.VIDEO, fmt.lowercase(Locale.ROOT), spec.mime)
        else -> null
    }
}

private val TARGET_MEDIUM_ORDER = mapOf(
    MediaKind.IMAGE to listOf(MediaKind.IMAGE, MediaKind.VIDEO),
    MediaKind.VIDEO to listOf(MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.IMAGE),
    MediaKind.AUDIO to listOf(MediaKind.AUDIO, MediaKind.VIDEO),
    MediaKind.UNKNOWN to emptyList(),
)

/**
 * 当前源媒体可选的格式组。FFmpeg 能力未知/不可用时只保留 Android 系统编解码器能处理的图片目标。
 */
internal fun targetGroupsFor(
    source: MediaKind,
    sourceExt: String,
    capabilities: FfmpegCapabilities?,
    ffmpegAvailable: Boolean,
): List<TargetGroup> {
    if (source == MediaKind.UNKNOWN) return emptyList()
    val encoders = capabilities?.encoders
    val order = TARGET_MEDIUM_ORDER[source].orEmpty()
    return order.mapNotNull { medium ->
        val options = when (medium) {
            MediaKind.IMAGE -> IMAGE_FORMATS.mapNotNull(::targetOption)
            MediaKind.VIDEO -> {
                if (!ffmpegAvailable) emptyList()
                else listOf("MP4", "MKV", "MOV", "AVI", "WEBM")
                    .filter { fmt ->
                        if (source == MediaKind.AUDIO) isAudioVideoFormatSupported(fmt, encoders)
                        else isFormatSupported(fmt, encoders)
                    }
                    .mapNotNull(::targetOption)
            }
            MediaKind.AUDIO -> {
                if (!ffmpegAvailable) emptyList()
                else listOf("MP3", "WAV", "AAC", "M4A", "FLAC", "OGG")
                    .filter { isFormatSupported(it, encoders) }
                    .mapNotNull(::targetOption)
            }
            MediaKind.UNKNOWN -> emptyList()
        }
        if (options.isEmpty()) null else TargetGroup(medium, medium.label, options)
    }
}

internal fun defaultTarget(
    source: MediaKind,
    sourceExt: String,
    capabilities: FfmpegCapabilities?,
    ffmpegAvailable: Boolean,
): TargetOption? {
    val groups = targetGroupsFor(source, sourceExt, capabilities, ffmpegAvailable)
    val sameGroup = groups.firstOrNull { it.medium == source } ?: groups.firstOrNull()
    return sameGroup?.options?.firstOrNull { it.extension != sourceExt }
        ?: groups.firstNotNullOfOrNull { group ->
            group.options.firstOrNull { it.extension != sourceExt }
        }
}

internal fun isSameFormat(sourceExt: String, option: TargetOption): Boolean =
    sourceExt.equals(option.extension, ignoreCase = true)

internal fun normalizedExtension(name: String?): String {
    val ext = (name ?: "").substringAfterLast('.', "").lowercase(Locale.ROOT)
    return if (ext == "jpeg") "jpg" else ext
}

internal fun detectMediaKind(name: String?, mime: String? = null): MediaKind {
    val ext = normalizedExtension(name)
    val byExt = when (ext) {
        "mp4", "mov", "avi", "mkv", "webm", "flv", "wmv", "mpg", "mpeg", "ts", "m4v", "3gp" -> MediaKind.VIDEO
        "mp3", "wav", "aac", "m4a", "flac", "ogg", "wma", "opus", "aiff" -> MediaKind.AUDIO
        "jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp", "gif", "heic", "heif", "avif" -> MediaKind.IMAGE
        else -> MediaKind.UNKNOWN
    }
    if (byExt != MediaKind.UNKNOWN) return byExt
    return when {
        mime == null -> MediaKind.UNKNOWN
        mime.startsWith("image/") -> MediaKind.IMAGE
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        else -> MediaKind.UNKNOWN
    }
}

internal fun sourceMimeFor(kind: MediaKind): String = when (kind) {
    MediaKind.IMAGE -> "image/*"
    MediaKind.VIDEO -> "video/*"
    MediaKind.AUDIO -> "audio/*"
    MediaKind.UNKNOWN -> "*/*"
}
