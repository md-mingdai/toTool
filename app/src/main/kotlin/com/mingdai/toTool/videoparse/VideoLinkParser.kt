package com.mingdai.toTool.videoparse

internal object VideoLinkParser {
    private val urlRegex = Regex("""https?://[^\s]+""")
    private const val TRAILING = "。,,;;)]}\"'、!?..."

    private data class Rule(
        val platform: VideoPlatform,
        val kind: String,
        val regex: Regex,
    )

    private val rules = listOf(
        Rule(VideoPlatform.DOUYIN, "short", Regex("""v\.douyin\.com/[A-Za-z0-9_\-]+""", RegexOption.IGNORE_CASE)),
        Rule(VideoPlatform.DOUYIN, "short", Regex("""jx\.douyin\.com/[A-Za-z0-9_\-]+""", RegexOption.IGNORE_CASE)),
        Rule(
            VideoPlatform.DOUYIN,
            "video",
            Regex("""(?:www\.)?douyin\.com/(video|note)/(\d{10,})""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            VideoPlatform.DOUYIN,
            "video",
            Regex("""douyin\.com/[^\s]*[?&]modal_id=(\d{10,})""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            VideoPlatform.DOUYIN,
            "video",
            Regex("""(?:iesdouyin|m\.douyin|jingxuan\.douyin)\.com/(?:share/|m/)?(?:slides|video|note)/(\d{10,})""", RegexOption.IGNORE_CASE),
        ),
        Rule(VideoPlatform.XHS, "short", Regex("""xhslink\.(?:com|cn)/[A-Za-z0-9._?%&+=/#@\-]+""", RegexOption.IGNORE_CASE)),
        Rule(
            VideoPlatform.XHS,
            "note",
            Regex("""xiaohongshu\.com/(?:explore|discovery/item|discovery)/([0-9a-zA-Z]{10,})""", RegexOption.IGNORE_CASE),
        ),
        Rule(VideoPlatform.KUAISHOU, "short", Regex("""v\.kuaishou\.com/[A-Za-z\d._?%&+\-=/#]+""", RegexOption.IGNORE_CASE)),
        Rule(
            VideoPlatform.KUAISHOU,
            "photo",
            Regex("""(?:www\.)?kuaishou\.com/(?:short-video|fw/photo|fw/long-video)/([A-Za-z\d]+)""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            VideoPlatform.KUAISHOU,
            "photo",
            Regex("""(?:www\.)?kuaishou\.com/f/([A-Za-z\d]+)""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            VideoPlatform.KUAISHOU,
            "photo",
            Regex("""(?:v\.m\.)?chenzhongtech\.com/fw/(?:photo|long-video)/([A-Za-z\d]+)""", RegexOption.IGNORE_CASE),
        ),
        Rule(VideoPlatform.WEIBO, "tv", Regex("""weibo\.com/tv/show/\d{4}:\d+\?mid=(\d+)""", RegexOption.IGNORE_CASE)),
        Rule(VideoPlatform.WEIBO, "fid", Regex("""video\.weibo\.com/show\?fid=(\d+:\d+)""", RegexOption.IGNORE_CASE)),
        Rule(
            VideoPlatform.WEIBO,
            "status",
            Regex("""weibo\.cn/(?:status|statuses|detail)/([0-9a-zA-Z]+)""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            VideoPlatform.WEIBO,
            "status",
            Regex("""weibo\.com/\d+/([0-9a-zA-Z]{6,})""", RegexOption.IGNORE_CASE),
        ),
        Rule(VideoPlatform.ACFUN, "ac", Regex("""acfun\.cn/.*(?:ac=|/ac)(\d+)""", RegexOption.IGNORE_CASE)),
        Rule(VideoPlatform.BILIBILI, "short", Regex("""b23\.tv/[A-Za-z\d]+""", RegexOption.IGNORE_CASE)),
        Rule(VideoPlatform.BILIBILI, "short", Regex("""bili2233\.cn/[A-Za-z\d]+""", RegexOption.IGNORE_CASE)),
        Rule(
            VideoPlatform.BILIBILI,
            "video",
            Regex("""bilibili\.com/video/(BV[0-9a-zA-Z]{10}|av\d+)""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            VideoPlatform.PIXIV,
            "artwork",
            Regex("""pixiv\.net/(?:[a-z]{2}/)?artworks/(\d+)""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            VideoPlatform.PIXIV,
            "artwork",
            Regex("""pixiv\.net/member_illust\.php\?[^\s]*illust_id=(\d+)""", RegexOption.IGNORE_CASE),
        ),
    )

    fun parse(text: String): DetectedVideoLink? {
        val raw = urlRegex.find(text.trim())?.value ?: return null
        val url = raw.trimEnd(*TRAILING.toCharArray())
        for (rule in rules) {
            val match = rule.regex.find(url) ?: continue
            val id = match.groupValues.drop(1).lastOrNull { it.isNotBlank() }.orEmpty()
            val short = rule.kind == "short"
            return DetectedVideoLink(
                platform = rule.platform,
                kind = if (short) "short" else rule.kind,
                id = if (short) "" else id,
                url = if (url.startsWith("http")) url else "https://$url",
                isShortLink = short,
            )
        }
        return null
    }
}
