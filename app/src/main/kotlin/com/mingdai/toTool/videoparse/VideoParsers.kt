package com.mingdai.toTool.videoparse

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ceil

internal object VideoParsers {
    private const val REFERER_ACFUN = "https://www.acfun.cn/"
    private const val REFERER_WEIBO = "https://weibo.com/"
    private const val REFERER_BILI = "https://www.bilibili.com/"
    private const val REFERER_PIXIV = "https://www.pixiv.net/"
    private const val REFERER_XHS = "https://www.xiaohongshu.com/"
    private const val XHS_IMAGE_CDN = "https://sns-img-bd.xhscdn.com"
    private const val TTWID_URL = "https://ttwid.bytedance.com/ttwid/union/register/"
    private const val DOUYIN_REFERER = "https://www.iesdouyin.com/"
    private val XHS_LOGIN_WALL = listOf("/login", "website-login", "captcha")

    @Volatile
    private var cachedTtwid: String? = null

    suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val resolved = if (link.isShortLink) expandShortLink(link) else link
        val result = when (resolved.platform) {
            VideoPlatform.DOUYIN -> parseDouyin(resolved, cookie)
            VideoPlatform.XHS -> parseXhs(resolved, cookie)
            VideoPlatform.KUAISHOU -> parseKuaishou(resolved, cookie)
            VideoPlatform.WEIBO -> parseWeibo(resolved, cookie)
            VideoPlatform.ACFUN -> parseAcFun(resolved, cookie)
            VideoPlatform.BILIBILI -> parseBilibili(resolved, cookie)
            VideoPlatform.PIXIV -> parsePixiv(resolved, cookie)
        }
        return result.copy(
            cover = VideoHttp.secureUrl(result.cover),
            medias = result.medias.map { media ->
                media.copy(
                    url = VideoHttp.secureUrl(media.url),
                    headers = media.headers,
                )
            },
        )
    }

    private suspend fun expandShortLink(link: DetectedVideoLink): DetectedVideoLink {
        var current = link
        repeat(5) {
            val ua = if (current.platform == VideoPlatform.DOUYIN || current.platform == VideoPlatform.KUAISHOU) {
                VideoHttp.UA_IPHONE
            } else {
                VideoHttp.UA_CHROME
            }
            val next = VideoHttp.expandRedirect(current.url, mapOf("User-Agent" to ua)) ?: return current
            val reparsed = VideoLinkParser.parse(next) ?: return current.copy(url = next)
            if (!reparsed.isShortLink) return reparsed
            current = reparsed
        }
        return current
    }

    // ---------------------------------------------------------------- AcFun

    private suspend fun parseAcFun(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val headers = buildMap {
            put("User-Agent", VideoHttp.UA_CHROME)
            put("Referer", REFERER_ACFUN)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        val html = VideoHttp.getText(
            "https://www.acfun.cn/v/ac${link.id}?quickViewId=videoInfo_new&ajaxpipe=1",
            headers,
            "AcFun 视频页",
        )
        val raw = VideoHttp.extractInlineJson(html, "window.videoInfo")
            ?: throw VideoParseException(VideoParseException.Kind.NOT_FOUND, "AcFun 页面没有视频信息")
        val json = try {
            JSONObject(VideoHttp.unescapeQuotes(raw.replace("\\\\\"", "\\\"")))
        } catch (e: Exception) {
            throw VideoParseException(VideoParseException.Kind.UNKNOWN, "AcFun 视频信息解析失败", e)
        }
        val title = json.str("title")
        val m3u8 = acfunM3u8(json)
            ?: throw VideoParseException(VideoParseException.Kind.UNKNOWN, "AcFun 没有找到可播放的 m3u8")
        return VideoParseResult(
            platform = VideoPlatform.ACFUN,
            title = title,
            author = json.obj("user")?.str("name").orEmpty(),
            cover = json.str("coverUrl"),
            coverHeaders = headers,
            text = json.str("description"),
            timestampSeconds = parseDateSeconds(json.str("createTime")),
            url = link.url,
            medias = listOf(
                ParsedMedia(
                    url = m3u8,
                    kind = VideoMediaKind.VIDEO,
                    fileName = "${fileNameBase(title, "acfun_${link.id}")}.mp4",
                    quality = "720P",
                    isHls = true,
                    headers = headers,
                ),
            ),
        )
    }

    private fun acfunM3u8(info: JSONObject): String? {
        val current = info.obj("currentVideoInfo") ?: return null
        val play = current.obj("ksPlayJson")
            ?: runCatching { JSONObject(current.str("ksPlayJson")) }.getOrNull()
            ?: return null
        val reps = play.arr("adaptationSet")?.obj(0)?.arr("representation") ?: return null
        val urls = (0 until reps.length()).mapNotNull { index ->
            reps.obj(index)?.str("url")?.takeIf { it.isNotBlank() }
        }
        return urls.getOrNull(3) ?: urls.firstOrNull()
    }

    // ---------------------------------------------------------------- Kuaishou

    private suspend fun parseKuaishou(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val headers = buildMap {
            put("User-Agent", VideoHttp.UA_IPHONE)
            put("Referer", "https://v.kuaishou.com/")
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        var pageUrl = VideoHttp.expandRedirect(link.url, headers) ?: link.url
        pageUrl = pageUrl.replace("/fw/long-video/", "/fw/photo/")
        val html = VideoHttp.getText(pageUrl, headers, "快手作品页")
        val stateRaw = VideoHttp.extractInlineJson(html, "window.INIT_STATE")
            ?: throw VideoParseException(VideoParseException.Kind.UNKNOWN, "快手页面没有 INIT_STATE，可能被风控或页面改版")
        val state = try {
            JSONObject(stateRaw)
        } catch (e: Exception) {
            throw VideoParseException(VideoParseException.Kind.UNKNOWN, "快手 INIT_STATE 解析失败", e)
        }
        val photo = findKuaishouPhoto(state)
            ?: throw VideoParseException(VideoParseException.Kind.NOT_FOUND, "快手页面里没有作品数据")
        val title = photo.str("caption")
        val videoUrl = firstUrlFromArray(photo.arr("mainMvUrls"))
        val imageUrls = kuaishouAtlasImages(photo.obj("ext_params")?.obj("atlas"))
        val medias = mutableListOf<ParsedMedia>()
        if (videoUrl != null) {
            medias += ParsedMedia(
                url = videoUrl,
                kind = VideoMediaKind.VIDEO,
                fileName = "${fileNameBase(title, "kuaishou_${link.id}")}.mp4",
                headers = headers,
            )
        }
        imageUrls.forEachIndexed { index, url ->
            medias += ParsedMedia(
                url = url,
                kind = VideoMediaKind.IMAGE,
                fileName = "${fileNameBase(title, "kuaishou_${link.id}")}_${index + 1}.jpg",
                headers = headers,
            )
        }
        if (medias.isEmpty()) {
            throw VideoParseException(VideoParseException.Kind.NOT_FOUND, "快手作品没有可用的视频或图片")
        }
        return VideoParseResult(
            platform = VideoPlatform.KUAISHOU,
            title = title,
            author = photo.str("userName").replace("ㅤ", "").trim(),
            avatar = photo.str("headUrl"),
            cover = firstUrlFromArray(photo.arr("coverUrls")).orEmpty(),
            coverHeaders = headers,
            text = title,
            timestampSeconds = photo.optLong("timestamp", 0L) / 1000L,
            url = link.url,
            medias = medias,
        )
    }

    private fun findKuaishouPhoto(state: JSONObject): JSONObject? {
        for (key in state.keys()) {
            val photo = state.optJSONObject(key)?.optJSONObject("photo")
            if (photo != null) return photo
        }
        return null
    }

    private fun kuaishouAtlasImages(atlas: JSONObject?): List<String> {
        if (atlas == null) return emptyList()
        val cdn = atlas.arr("cdnList")?.obj(0)?.str("cdn").orEmpty()
        val routes = atlas.arr("list") ?: return emptyList()
        if (cdn.isBlank()) return emptyList()
        return (0 until routes.length()).mapNotNull { index ->
            routes.optString(index).takeIf { it.isNotBlank() }?.let { "https://$cdn/$it" }
        }
    }

    // ---------------------------------------------------------------- Weibo

    private suspend fun parseWeibo(link: DetectedVideoLink, cookie: String): VideoParseResult {
        return when (link.kind) {
            "fid" -> parseWeiboFid(link, cookie)
            "tv" -> parseWeiboStatus(link, midToId(link.id), cookie)
            else -> parseWeiboStatus(link, link.id, cookie)
        }
    }

    private suspend fun parseWeiboStatus(
        link: DetectedVideoLink,
        weiboId: String,
        cookie: String,
    ): VideoParseResult {
        val headers = buildMap {
            put("Accept", "application/json, text/plain, */*")
            put("Referer", "https://m.weibo.cn/detail/$weiboId")
            put("Origin", "https://m.weibo.cn")
            put("X-Requested-With", "XMLHttpRequest")
            put("User-Agent", VideoHttp.UA_CHROME)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        val url = "https://m.weibo.cn/statuses/show?id=$weiboId&_=${System.currentTimeMillis()}"
        val response = VideoHttp.request(url, headers = headers, followRedirects = false)
        if (response.status != 200) {
            VideoHttp.requireSuccess(response, "微博内容")
        }
        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("application/json", ignoreCase = true) &&
            !response.body.trimStart().startsWith("{")) {
            throw VideoParseException(VideoParseException.Kind.BLOCKED, "微博返回了登录/验证页面")
        }
        val data = try {
            JSONObject(response.body).optJSONObject("data")
        } catch (e: Exception) {
            throw VideoParseException(VideoParseException.Kind.UNKNOWN, "微博 JSON 解析失败", e)
        } ?: throw VideoParseException(VideoParseException.Kind.NOT_FOUND, "微博内容不存在")
        return buildWeiboResult(link, data, cookie, allowRetweet = true)
    }

    private suspend fun parseWeiboFid(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val headers = buildMap {
            put("Referer", "https://h5.video.weibo.com/show/${link.id}")
            put("User-Agent", VideoHttp.UA_CHROME)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        val text = VideoHttp.postForm(
            "https://h5.video.weibo.com/api/component?page=/show/${link.id}",
            mapOf("data" to """{"Component_Play_Playinfo":{"oid":"${link.id}"}}"""),
            headers,
            "微博视频接口",
        )
        val data = try {
            JSONObject(text).optJSONObject("data")?.optJSONObject("Component_Play_Playinfo")
        } catch (e: Exception) {
            throw VideoParseException(VideoParseException.Kind.UNKNOWN, "微博视频接口解析失败", e)
        } ?: throw VideoParseException(VideoParseException.Kind.NOT_FOUND, "微博视频不存在")
        val mediaHeaders = weiboMediaHeaders(cookie)
        val title = data.str("title")
        val base = fileNameBase(title, "weibo_${link.id}")
        val urls = data.obj("urls")
        val firstKey = urls?.keys()?.asSequence()?.firstOrNull()
        val url = if (urls != null && firstKey != null) {
            httpsUrl(urls.str(firstKey))
        } else {
            httpsUrl(data.str("stream_url"))
        }
        if (url.isBlank()) {
            throw VideoParseException(VideoParseException.Kind.NOT_FOUND, "微博视频没有直链")
        }
        val user = data.obj("reward")?.obj("user")
        return VideoParseResult(
            platform = VideoPlatform.WEIBO,
            title = title,
            author = user?.str("name").orEmpty(),
            avatar = user?.str("profile_image_url").orEmpty(),
            cover = httpsUrl(data.str("cover_image")),
            coverHeaders = mediaHeaders,
            text = stripHtml(data.str("text")),
            timestampSeconds = parseDateSeconds(data.str("real_date")),
            url = link.url,
            medias = listOf(
                ParsedMedia(
                    url = url,
                    kind = VideoMediaKind.VIDEO,
                    fileName = "$base.mp4",
                    headers = mediaHeaders,
                ),
            ),
        )
    }

    private fun buildWeiboResult(
        link: DetectedVideoLink,
        data: JSONObject,
        cookie: String,
        allowRetweet: Boolean,
    ): VideoParseResult {
        val mediaHeaders = weiboMediaHeaders(cookie)
        val pageInfo = data.obj("page_info")
        val title = pageInfo?.str("title").orEmpty()
        val base = fileNameBase(title, "weibo_${link.id}")
        val medias = mutableListOf<ParsedMedia>()
        val videoUrl = weiboVideoUrl(pageInfo)
        if (videoUrl.isNotBlank()) {
            medias += ParsedMedia(
                url = videoUrl,
                kind = VideoMediaKind.VIDEO,
                fileName = "$base.mp4",
                headers = mediaHeaders,
            )
        }
        val pics = data.arr("pics")
        if (pics != null) {
            var imageIndex = 0
            for (index in 0 until pics.length()) {
                val pic = pics.obj(index) ?: continue
                val url = pic.obj("large")?.str("url").orEmpty()
                if (url.isBlank()) continue
                imageIndex++
                medias += ParsedMedia(
                    url = url,
                    kind = VideoMediaKind.IMAGE,
                    fileName = "${base}_$imageIndex.jpg",
                    quality = if (pic.str("videoSrc").isNotBlank()) "实况图" else "原图",
                    headers = mediaHeaders,
                )
            }
        }
        if (medias.isEmpty() && allowRetweet) {
            data.obj("retweeted_status")?.let { retweeted ->
                return buildWeiboResult(link, retweeted, cookie, allowRetweet = false)
            }
        }
        val user = data.obj("user")
        val userId = user?.optLong("id", 0L) ?: 0L
        val bid = data.str("bid")
        return VideoParseResult(
            platform = VideoPlatform.WEIBO,
            title = title,
            author = user?.str("screen_name").orEmpty(),
            avatar = user?.str("profile_image_url").orEmpty(),
            cover = pageInfo?.obj("page_pic")?.str("url").orEmpty(),
            coverHeaders = mediaHeaders,
            text = stripHtml(data.str("text")),
            timestampSeconds = parseWeiboTime(data.str("created_at")),
            url = if (userId > 0L && bid.isNotBlank()) "https://weibo.com/$userId/$bid" else link.url,
            medias = medias,
        )
    }

    private fun weiboVideoUrl(pageInfo: JSONObject?): String {
        val urls = pageInfo?.obj("urls") ?: return ""
        val value = urls.str("mp4_720p_mp4")
            .ifBlank { urls.str("mp4_hd_mp4") }
            .ifBlank { urls.str("mp4_ld_mp4") }
        return httpsUrl(value)
    }

    private fun weiboMediaHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", VideoHttp.UA_CHROME)
        put("Referer", REFERER_WEIBO)
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    private fun midToId(mid: String): String {
        val reversed = mid.reversed()
        if (reversed.isEmpty()) return ""
        val chunkCount = ceil(reversed.length / 7.0).toInt()
        val chunks = mutableListOf<String>()
        for (index in 0 until chunkCount) {
            val start = index * 7
            val end = minOf(start + 7, reversed.length)
            val number = reversed.substring(start, end).reversed().toLongOrNull() ?: 0L
            var encoded = base62(number)
            if (index < chunkCount - 1 && encoded.length < 4) {
                encoded = "0".repeat(4 - encoded.length) + encoded
            }
            chunks += encoded
        }
        return chunks.reversed().joinToString("")
    }

    private fun base62(value: Long): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (value == 0L) return "0"
        var number = value
        val builder = StringBuilder()
        while (number > 0L) {
            builder.insert(0, alphabet[(number % 62L).toInt()])
            number /= 62L
        }
        return builder.toString()
    }

    // ---------------------------------------------------------------- Bilibili

    private suspend fun parseBilibili(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val headers = buildMap {
            put("User-Agent", VideoHttp.UA_CHROME)
            put("Referer", REFERER_BILI)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        val id = link.id
        val viewUrl = if (id.startsWith("BV", ignoreCase = true)) {
            "https://api.bilibili.com/x/web-interface/view?bvid=$id"
        } else {
            "https://api.bilibili.com/x/web-interface/view?aid=${id.drop(2)}"
        }
        val view = VideoHttp.getJson(viewUrl, headers, "B 站视频信息")
        if (view.optInt("code") != 0) {
            throw VideoParseException(
                VideoParseException.Kind.NOT_FOUND,
                "B 站接口返回 ${view.optInt("code")}: ${view.optString("message")}",
            )
        }
        val data = view.optJSONObject("data")
            ?: throw VideoParseException(VideoParseException.Kind.UNKNOWN, "B 站视频信息为空")
        val bvid = data.str("bvid").ifBlank { id }
        val cid = data.arr("pages")?.obj(0)?.optLong("cid", 0L) ?: data.optLong("cid", 0L)
        if (cid <= 0L) throw VideoParseException(VideoParseException.Kind.UNKNOWN, "B 站缺少 cid")
        val title = data.str("title")
        val play = VideoHttp.getJson(
            "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=80&fnval=1&fnver=0&fourk=1&platform=html5&high_quality=1",
            headers,
            "B 站播放地址",
        )
        val playData = play.obj("data")
        val durl = playData?.arr("durl")
        if (durl == null || durl.length() == 0) {
            throw VideoParseException(
                VideoParseException.Kind.UNSUPPORTED,
                "B 站没有返回可下载的直链（code=${play.optInt("code")}）",
            )
        }
        val first = durl.obj(0)
            ?: throw VideoParseException(VideoParseException.Kind.UNKNOWN, "B 站直链为空")
        val url = httpsUrl(first.str("url"))
        if (url.isBlank()) {
            throw VideoParseException(VideoParseException.Kind.BLOCKED, "B 站返回了空直链")
        }
        val mediaHeaders = buildMap {
            put("User-Agent", VideoHttp.UA_CHROME)
            put("Referer", REFERER_BILI)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        return VideoParseResult(
            platform = VideoPlatform.BILIBILI,
            title = title,
            author = data.obj("owner")?.str("name").orEmpty(),
            avatar = data.obj("owner")?.str("face").orEmpty(),
            cover = httpsUrl(data.str("pic")),
            coverHeaders = mediaHeaders,
            text = data.str("desc"),
            timestampSeconds = data.optLong("pubdate", 0L),
            url = "https://www.bilibili.com/video/$bvid",
            medias = listOf(
                ParsedMedia(
                    url = url,
                    kind = VideoMediaKind.VIDEO,
                    fileName = "${fileNameBase(title, "bilibili_$bvid")}.mp4",
                    quality = biliQuality(playData?.optInt("quality") ?: 0),
                    headers = mediaHeaders,
                ),
            ),
        )
    }

    private fun biliQuality(value: Int): String = when (value) {
        6, 7 -> "240P"
        16 -> "360P"
        32 -> "480P"
        64 -> "720P"
        74 -> "720P60"
        80 -> "1080P"
        112 -> "1080P+"
        116 -> "1080P60"
        120 -> "4K"
        else -> if (value > 0) "${value}P" else ""
    }

    // ---------------------------------------------------------------- Pixiv

    private suspend fun parsePixiv(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val headers = buildMap {
            put("User-Agent", VideoHttp.UA_CHROME)
            put("Referer", REFERER_PIXIV)
            put("Accept", "application/json")
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        val info = VideoHttp.getJson(
            "https://www.pixiv.net/ajax/illust/${link.id}",
            headers,
            "Pixiv 作品信息",
        )
        if (info.optBoolean("error")) {
            val message = info.optString("message")
            val kind = if (message.contains("not found", true)) {
                VideoParseException.Kind.NOT_FOUND
            } else if (cookie.isBlank() || message.contains("login", true)) {
                VideoParseException.Kind.UNSUPPORTED
            } else {
                VideoParseException.Kind.BLOCKED
            }
            throw VideoParseException(kind, "Pixiv: $message")
        }
        val body = info.obj("body")
            ?: throw VideoParseException(VideoParseException.Kind.UNKNOWN, "Pixiv 作品信息为空")
        if (body.optInt("illustType", 0) == 2) {
            throw VideoParseException(VideoParseException.Kind.UNSUPPORTED, "Pixiv 动图作品暂不支持")
        }
        val pages = VideoHttp.getJson(
            "https://www.pixiv.net/ajax/illust/${link.id}/pages",
            headers,
            "Pixiv 图片列表",
        ).arr("body")
        val title = body.str("title")
        val base = fileNameBase(title, "pixiv_${link.id}")
        val mediaHeaders = buildMap {
            put("User-Agent", VideoHttp.UA_CHROME)
            put("Referer", REFERER_PIXIV)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        val medias = mutableListOf<ParsedMedia>()
        if (pages != null) {
            for (index in 0 until pages.length()) {
                val original = pages.obj(index)?.obj("urls")?.str("original").orEmpty()
                if (original.isBlank()) continue
                medias += ParsedMedia(
                    url = original,
                    kind = VideoMediaKind.IMAGE,
                    fileName = "${base}_${index + 1}.${fileExtension(original)}",
                    quality = "原图",
                    headers = mediaHeaders,
                )
            }
        }
        if (medias.isEmpty()) {
            body.obj("urls")?.str("original")?.takeIf { it.isNotBlank() }?.let { original ->
                medias += ParsedMedia(
                    url = original,
                    kind = VideoMediaKind.IMAGE,
                    fileName = "$base.${fileExtension(original)}",
                    quality = "原图",
                    headers = mediaHeaders,
                )
            }
        }
        if (medias.isEmpty()) {
            throw VideoParseException(VideoParseException.Kind.NOT_FOUND, "Pixiv 没有可用图片")
        }
        return VideoParseResult(
            platform = VideoPlatform.PIXIV,
            title = title,
            author = body.str("userName"),
            cover = medias.first().url,
            coverHeaders = mediaHeaders,
            text = body.str("description"),
            timestampSeconds = parseDateSeconds(body.str("createDate")),
            url = "https://www.pixiv.net/artworks/${link.id}",
            medias = medias,
        )
    }

    // ---------------------------------------------------------------- Xiaohongshu

    private suspend fun parseXhs(link: DetectedVideoLink, cookie: String): VideoParseResult {
        if (cookie.isBlank()) {
            throw VideoParseException(
                VideoParseException.Kind.UNSUPPORTED,
                "小红书需要登录 Cookie，请先在工具里粘贴包含 web_session 的 Cookie",
            )
        }
        val explore = runCatching { parseXhsExplore(link, cookie) }
        explore.getOrNull()?.let { return it }
        val exploreError = explore.exceptionOrNull()
        if (exploreError is VideoParseException && exploreError.kind == VideoParseException.Kind.UNSUPPORTED) {
            throw exploreError
        }
        val discovery = runCatching { parseXhsDiscovery(link, cookie) }
        discovery.getOrNull()?.let { return it }
        val discoveryError = discovery.exceptionOrNull()
        throw (discoveryError as? VideoParseException)
            ?: (exploreError as? VideoParseException)
            ?: VideoParseException(VideoParseException.Kind.UNKNOWN, "小红书解析失败")
    }

    private suspend fun parseXhsExplore(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val headers = xhsHeaders(cookie, desktop = true)
        val url = "https://www.xiaohongshu.com/explore/${link.id}${xhsQuery(link)}"
        val response = VideoHttp.request(url, headers = headers)
        VideoHttp.requireSuccess(response, "小红书笔记")
        if (XHS_LOGIN_WALL.any { response.finalUrl.contains(it, true) }) {
            throw VideoParseException(VideoParseException.Kind.UNSUPPORTED, "小红书登录态无效或需要验证")
        }
        val state = parseXhsState(response.body)
        val note = state.obj("note")
            ?.obj("noteDetailMap")
            ?.obj(link.id)
            ?.obj("note")
            ?: throw VideoParseException(VideoParseException.Kind.NOT_FOUND, "小红书笔记不存在")
        return buildXhsNote(link, note, headers, "nickname")
    }

    private suspend fun parseXhsDiscovery(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val headers = xhsHeaders(cookie, desktop = false)
        val url = "https://www.xiaohongshu.com/discovery/item/${link.id}${xhsQuery(link)}"
        val response = VideoHttp.request(url, headers = headers)
        VideoHttp.requireSuccess(response, "小红书笔记")
        if (XHS_LOGIN_WALL.any { response.finalUrl.contains(it, true) }) {
            throw VideoParseException(VideoParseException.Kind.UNSUPPORTED, "小红书登录态无效或需要验证")
        }
        val state = parseXhsState(response.body)
        val note = state.obj("noteData")?.obj("data")?.obj("noteData")
            ?: throw VideoParseException(VideoParseException.Kind.NOT_FOUND, "小红书笔记不存在")
        return buildXhsNote(link, note, headers, "nickName")
    }

    private fun xhsQuery(link: DetectedVideoLink): String {
        val query = link.url.substringAfter('?', "")
        return if (query.isBlank()) "" else "?$query"
    }

    private fun xhsHeaders(cookie: String, desktop: Boolean): Map<String, String> = buildMap {
        put("User-Agent", if (desktop) VideoHttp.UA_CHROME else VideoHttp.UA_IPHONE)
        put("Referer", REFERER_XHS)
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        if (!desktop) {
            put("Origin", "https://www.xiaohongshu.com")
            put("X-Requested-With", "XMLHttpRequest")
        }
        put("Cookie", cookie)
    }

    private fun parseXhsState(html: String): JSONObject {
        val raw = VideoHttp.extractInlineJson(html, "window.__INITIAL_STATE__")
            ?: throw VideoParseException(VideoParseException.Kind.UNKNOWN, "小红书页面没有 INITIAL_STATE")
        return try {
            JSONObject(raw.replace("undefined", "null"))
        } catch (e: Exception) {
            throw VideoParseException(VideoParseException.Kind.UNKNOWN, "小红书页面数据解析失败", e)
        }
    }

    private fun buildXhsNote(
        link: DetectedVideoLink,
        note: JSONObject,
        headers: Map<String, String>,
        authorKey: String,
    ): VideoParseResult {
        val title = note.str("title")
        val base = fileNameBase(title, "xhs_${link.id}")
        val rawImages = note.arr("imageList").let { array ->
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { index ->
                array.obj(index)?.str("urlDefault")?.ifBlank { array.obj(index)?.str("url") }
            }
        }
        val video = xhsVideo(note)
        val medias = mutableListOf<ParsedMedia>()
        if (video != null) {
            medias += ParsedMedia(
                url = video.first,
                kind = VideoMediaKind.VIDEO,
                fileName = "$base.mp4",
                quality = video.second,
                headers = headers,
            )
        } else if (note.obj("video") != null) {
            throw VideoParseException(VideoParseException.Kind.UNKNOWN, "小红书视频笔记没有可用直链")
        } else {
            rawImages.forEachIndexed { index, raw ->
                medias += ParsedMedia(
                    url = cleanXhsImage(raw),
                    kind = VideoMediaKind.IMAGE,
                    fileName = "${base}_${index + 1}.jpg",
                    quality = "原图",
                    headers = headers,
                )
            }
        }
        if (medias.isEmpty()) {
            throw VideoParseException(VideoParseException.Kind.NOT_FOUND, "小红书笔记没有媒体内容")
        }
        val user = note.obj("user")
        return VideoParseResult(
            platform = VideoPlatform.XHS,
            title = title,
            author = user?.str(authorKey).orEmpty(),
            avatar = user?.str("avatar").orEmpty(),
            cover = if (video != null) cleanXhsImage(rawImages.firstOrNull().orEmpty()) else medias.first().url,
            coverHeaders = headers,
            text = note.str("desc"),
            timestampSeconds = note.optLong("time", 0L) / 1000L,
            url = link.url,
            medias = medias,
        )
    }

    private fun xhsVideo(note: JSONObject): Pair<String, String>? {
        val stream = note.obj("video")?.obj("media")?.obj("stream") ?: return null
        val order = listOf("h265" to "H.265", "h264" to "H.264", "av1" to "AV1", "h266" to "H.266")
        for ((key, label) in order) {
            val first = stream.arr(key)?.obj(0) ?: continue
            val url = first.str("masterUrl").ifBlank { first.str("master_url") }
            if (url.isNotBlank()) return url to label
        }
        return null
    }

    private fun cleanXhsImage(raw: String): String {
        if (raw.isBlank()) return raw
        val id = raw.substringAfter("://", "")
            .substringAfter('/', "")
            .split('/')
            .drop(2)
            .joinToString("/")
            .substringBefore('!')
        return if (id.isBlank()) raw else "$XHS_IMAGE_CDN/$id"
    }

    // ---------------------------------------------------------------- Douyin

    private suspend fun parseDouyin(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val type = if (link.url.contains("/note/", true)) "note" else "video"
        val referer = "https://www.douyin.com/"
        val urls = listOf(
            "https://www.iesdouyin.com/share/$type/${link.id}/",
            "https://www.douyin.com/$type/${link.id}",
        )
        var lastError: Exception? = null
        for (attempt in 0..1) {
            val ttwid = runCatching {
                if (attempt == 0) ensureTtwid() else renewTtwid()
            }.getOrNull()
            val headers = buildMap {
                put("User-Agent", VideoHttp.UA_IPHONE)
                put("Referer", DOUYIN_REFERER)
                if (!ttwid.isNullOrBlank()) put("Cookie", "ttwid=$ttwid" + if (cookie.isBlank()) "" else "; $cookie")
                else if (cookie.isNotBlank()) put("Cookie", cookie)
            }
            for (url in urls) {
                try {
                    val html = VideoHttp.getText(url, headers, "抖音分享页")
                    val routerRaw = VideoHttp.extractInlineJson(html, "window._ROUTER_DATA")
                        ?: continue
                    val item = extractDouyinItem(routerRaw) ?: continue
                    return buildDouyinResult(link, item, referer, headers)
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        throw VideoParseException(
            VideoParseException.Kind.UNKNOWN,
            "抖音没有返回作品数据（可能需要 WebView 过风控）" +
                (lastError?.message?.let { "：$it" } ?: ""),
            lastError,
        )
    }

    private fun extractDouyinItem(routerRaw: String): JSONObject? {
        val router = runCatching { JSONObject(routerRaw) }.getOrNull() ?: return null
        val loader = router.obj("loaderData") ?: return null
        val page = loader.obj("video_(id)/page") ?: loader.obj("note_(id)/page") ?: return null
        val videoInfo = page.obj("videoInfoRes") ?: page.obj("video_info_res") ?: return null
        val items = videoInfo.arr("item_list") ?: videoInfo.arr("itemList") ?: return null
        return items.obj(0)
    }

    private fun buildDouyinResult(
        link: DetectedVideoLink,
        item: JSONObject,
        referer: String,
        requestHeaders: Map<String, String>,
    ): VideoParseResult {
        val mediaHeaders = mapOf(
            "User-Agent" to VideoHttp.UA_IPHONE,
            "Referer" to referer,
        )
        val title = item.str("desc")
        val base = fileNameBase(title, "douyin_${link.id}")
        val medias = mutableListOf<ParsedMedia>()
        val images = item.arr("images")
        if (images != null && images.length() > 0) {
            for (index in 0 until images.length()) {
                val image = images.obj(index) ?: continue
                val url = firstUrl(image) ?: continue
                medias += ParsedMedia(
                    url = url,
                    kind = VideoMediaKind.IMAGE,
                    fileName = "${base}_${index + 1}.${fileExtension(url)}",
                    quality = "原图",
                    headers = mediaHeaders,
                )
            }
        }
        val video = item.obj("video")
        if (video != null) {
            val url = douyinVideoUrl(video)
            if (url != null) {
                medias += ParsedMedia(
                    url = url,
                    kind = VideoMediaKind.VIDEO,
                    fileName = "$base.mp4",
                    headers = mediaHeaders,
                )
            }
        }
        if (medias.isEmpty()) {
            throw VideoParseException(VideoParseException.Kind.NOT_FOUND, "抖音作品没有可用媒体")
        }
        val author = item.obj("author")
        return VideoParseResult(
            platform = VideoPlatform.DOUYIN,
            title = title,
            author = author?.str("nickname").orEmpty(),
            avatar = firstUrl(author?.obj("avatar_thumb") ?: author?.obj("avatar_medium")).orEmpty(),
            cover = firstUrl(video?.obj("cover")).orEmpty(),
            coverHeaders = mediaHeaders,
            text = title,
            timestampSeconds = item.optLong("create_time", item.optLong("createTime", 0L)),
            url = link.url,
            medias = medias,
        )
    }

    private fun douyinVideoUrl(video: JSONObject): String? {
        val play = video.obj("play_addr") ?: video.obj("playAddr")
        val uri = play?.str("uri").orEmpty()
        if (uri.isNotBlank()) {
            return "https://aweme.snssdk.com/aweme/v1/play/?video_id=" +
                URLEncoder.encode(uri, "UTF-8") + "&ratio=720p"
        }
        val direct = firstUrl(play) ?: return null
        return direct.replace("/playwm/", "/play/")
    }

    private suspend fun ensureTtwid(): String {
        cachedTtwid?.let { return it }
        val ttwid = registerTtwid()
        cachedTtwid = ttwid
        return ttwid
    }

    private suspend fun renewTtwid(): String {
        cachedTtwid = null
        return ensureTtwid()
    }

    private suspend fun registerTtwid(): String {
        val body = """{"region":"cn","aid":1768,"needFid":false,"service":"www.iesdouyin.com","union":true,"fid":""}"""
            .toByteArray(Charsets.UTF_8)
        val response = VideoHttp.request(
            TTWID_URL,
            method = "POST",
            headers = mapOf(
                "User-Agent" to VideoHttp.UA_IPHONE,
                "Referer" to DOUYIN_REFERER,
                "Content-Type" to "application/json",
            ),
            body = body,
            followRedirects = false,
        )
        extractCookie(response, "ttwid")?.let { return it }
        val redirect = runCatching { JSONObject(response.body).optString("redirect_url") }.getOrDefault("")
        if (redirect.isNotBlank()) {
            val second = VideoHttp.request(
                redirect,
                headers = mapOf(
                    "User-Agent" to VideoHttp.UA_IPHONE,
                    "Referer" to DOUYIN_REFERER,
                ),
                followRedirects = false,
            )
            extractCookie(second, "ttwid")?.let { return it }
        }
        throw VideoParseException(VideoParseException.Kind.UNKNOWN, "无法获取抖音匿名 ttwid")
    }

    private fun extractCookie(response: VideoHttp.Response, name: String): String? {
        val cookies = response.headers.entries
            .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
            ?.value.orEmpty()
        val regex = Regex("""(?:^|;\s*)${Regex.escape(name)}=([^;\s]+)""")
        return cookies.firstNotNullOfOrNull { regex.find(it)?.groupValues?.getOrNull(1) }
    }

    // ---------------------------------------------------------------- shared

    private fun firstUrl(node: JSONObject?): String? {
        val list = node?.arr("url_list") ?: node?.arr("urlList") ?: return null
        for (index in 0 until list.length()) {
            val value = list.optString(index)
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun firstUrlFromArray(nodes: JSONArray?): String? {
        if (nodes == null) return null
        for (index in 0 until nodes.length()) {
            val value = nodes.obj(index)?.str("url").orEmpty()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun fileNameBase(title: String, fallback: String): String =
        sanitizeName(title).take(80).ifBlank { fallback }

    private fun sanitizeName(name: String): String = name
        .map { ch ->
            if (ch == '/' || ch == '\\' || ch in ":\"*?<>|" || ch.code < 32) '_' else ch
        }
        .joinToString("")
        .trim()
        .trim('.')

    private fun fileExtension(url: String): String {
        val path = url.substringBefore('?').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (ext.isNotBlank() && ext.length <= 4) ext else "jpg"
    }

    private fun stripHtml(raw: String): String =
        raw.replace("<br />", "\n").replace(Regex("<[^>]*>"), "")

    private fun httpsUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        else -> "https:$url"
    }

    private fun parseDateSeconds(value: String): Long {
        if (value.isBlank()) return 0L
        if (value.all { it.isDigit() }) {
            val number = value.toLongOrNull() ?: return 0L
            return if (number > 1_000_000_000_000L) number / 1000L else number
        }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )
        for (pattern in patterns) {
            val millis = runCatching {
                SimpleDateFormat(pattern, Locale.US).parse(value)?.time
            }.getOrNull()
            if (millis != null) return millis / 1000L
        }
        return 0L
    }

    private fun parseWeiboTime(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching {
            SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US).parse(value)?.time
        }.getOrNull()?.div(1000L) ?: 0L
    }

    private fun JSONObject.str(key: String): String =
        if (isNull(key)) "" else optString(key, "").takeIf { it != "null" }.orEmpty()

    private fun JSONObject.obj(key: String): JSONObject? = if (isNull(key)) null else optJSONObject(key)

    private fun JSONObject.arr(key: String): JSONArray? = if (isNull(key)) null else optJSONArray(key)

    private fun JSONArray.obj(index: Int): JSONObject? =
        if (index in 0 until length()) optJSONObject(index) else null
}
