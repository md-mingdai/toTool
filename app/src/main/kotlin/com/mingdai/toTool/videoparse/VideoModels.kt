package com.mingdai.toTool.videoparse

internal enum class VideoPlatform(
    val label: String,
    val needsCookie: Boolean = false,
    val cookieRequired: Boolean = false,
    val cookieHint: String = "",
) {
    DOUYIN("抖音"),
    XHS(
        label = "小红书",
        needsCookie = true,
        cookieRequired = true,
        cookieHint = "小红书网页端对未登录请求会跳转登录/验证码，需要粘贴浏览器 Cookie（含 web_session）。",
    ),
    KUAISHOU("快手"),
    WEIBO("微博"),
    ACFUN("AcFun"),
    BILIBILI(
        label = "哔哩哔哩",
        needsCookie = true,
        cookieHint = "可选。登录 Cookie 能提高可用清晰度；不填也能解析基础清晰度。",
    ),
    PIXIV(
        label = "Pixiv",
        needsCookie = true,
        cookieHint = "可选。未登录只能解析部分公开作品；受限作品需要浏览器 Cookie。",
    ),
}

internal enum class VideoMediaKind { VIDEO, IMAGE, AUDIO }

internal data class DetectedVideoLink(
    val platform: VideoPlatform,
    val kind: String,
    val id: String,
    val url: String,
    val isShortLink: Boolean = false,
)

internal data class ParsedMedia(
    val url: String,
    val kind: VideoMediaKind,
    val fileName: String = "",
    val quality: String = "",
    val isHls: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

internal data class VideoParseResult(
    val platform: VideoPlatform,
    val title: String,
    val author: String = "",
    val avatar: String = "",
    val cover: String = "",
    val coverHeaders: Map<String, String> = emptyMap(),
    val text: String = "",
    val timestampSeconds: Long = 0L,
    val url: String = "",
    val medias: List<ParsedMedia> = emptyList(),
    val notice: String = "",
)

internal class VideoParseException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { BLOCKED, NOT_FOUND, NETWORK, UNSUPPORTED, UNKNOWN }
}
