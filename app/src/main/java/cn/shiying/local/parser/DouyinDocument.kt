/* Adapted from wujunwei928/parse-video, parser/douyin.go (MIT).
 * Copyright (c) 2022 wujunwei928. See THIRD_PARTY_NOTICES.md and bundled notices.
 */
package cn.shiying.local.parser

import java.net.URLDecoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup

/** Pure document mapping, kept independent of Android for fixture-based tests. */
object DouyinDocument {
    fun parse(html: String, expectedId: String?, pageUrl: String): MediaInfo {
        if (html.length > 4 * 1024 * 1024) throw ParseException("网页内容过大，已停止解析")
        val doc = Jsoup.parse(html)
        val roots = mutableListOf<Any>()
        val stream = StringBuilder()
        doc.select("script").forEach { script ->
            val content = script.data().trim()
            val markers = listOf("window._ROUTER_DATA", "window._SSR_DATA", "window.__INIT_PROPS__")
            val raw = when {
                markers.any(content::contains) -> content.substringAfter(markers.first(content::contains))
                    .trimStart().removePrefix("=").trim().removeSuffix(";")
                script.id() == "RENDER_DATA" -> if (content.startsWith("%"))
                    runCatching { URLDecoder.decode(content, "UTF-8") }.getOrDefault("") else content
                script.attr("type") == "application/json" -> content
                else -> ""
            }
            readJson(raw)?.let(roots::add)
            // React/pace Flight data is JSON inside a string argument, never evaluated as JavaScript.
            Regex("""(?:self|window)\.__pace_f\.push\s*\(""").findAll(content).forEach chunkLoop@ { match ->
                val args = runCatching { JSONTokener(content.substring(match.range.last + 1)).nextValue() as? JSONArray }.getOrNull()
                if (args?.optInt(0) != 1 || args.opt(1) !is String) return@chunkLoop
                val chunk = args.getString(1)
                if (chunk.startsWith("%7B", true) || chunk.startsWith("%5B", true)) {
                    runCatching { URLDecoder.decode(chunk, "UTF-8") }.getOrNull()?.let(::readJson)?.let(roots::add)
                } else {
                    readFlight(chunk, roots)
                    stream.append(chunk)
                }
            }
        }
        readFlight(stream.toString(), roots)
        return mapCandidates(roots.flatMap { findWorks(it) }, expectedId, pageUrl)
    }

    private fun readJson(raw: String): Any? = runCatching {
        if (!raw.trimStart().startsWith("{") && !raw.trimStart().startsWith("[")) null
        else JSONTokener(raw).nextValue().takeIf { it is JSONObject || it is JSONArray }
    }.getOrNull()

    private fun readFlight(raw: String, roots: MutableList<Any>) {
        raw.lineSequence().forEach { line ->
            if (Regex("""^[0-9a-fA-F]+:[\[{]""").containsMatchIn(line))
                readJson(line.substringAfter(':'))?.let(roots::add)
        }
    }

    /** Accept known API envelopes while matching the target work throughout. */
    fun parseFeed(json: String, expectedId: String, pageUrl: String): MediaInfo {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: throw ParseException("平台返回的作品详情格式异常")
        return mapCandidates(findWorks(root), expectedId, pageUrl)
    }

    private fun workId(item: JSONObject) = item.optString("aweme_id").ifBlank { item.optString("awemeId") }
    private fun author(item: JSONObject) = (item.optJSONObject("author") ?: item.optJSONObject("authorInfo"))
        ?.let { it.optString("nickname").ifBlank { it.optString("nickName") } }.orEmpty()

    private fun mapCandidates(candidates: List<JSONObject>, expectedId: String?, pageUrl: String): MediaInfo {
        val item = candidates.firstOrNull {
            (expectedId == null || workId(it) == expectedId) && (it.has("video") || imageArray(it) != null)
        } ?: throw ParseException("分享页未提供作品资源，可尝试网页辅助解析或更换公开链接。")
        val rawImages = imageArray(item)
        if (rawImages != null) {
            require(rawImages.length() <= 100) { "图集超过 100 张，请更换作品" }
            val images = (0 until rawImages.length()).map { index ->
                val image = rawImages.opt(index)
                val entry = image as? JSONObject
                val explicit = listOf("animated_url", "animatedUrl", "gif_url", "gifUrl", "animated_image", "animatedImage")
                    .flatMap { imageUrls(entry?.opt(it)) }
                val originals = imageUrls(image)
                val gifUrls = originals.filter(::isGifAddress)
                val candidates = (explicit + gifUrls + originals).distinct()
                val (videoSources, urls) = candidates.partition(::isMotionVideoAddress)
                val livePhoto = entry?.optJSONObject("live_photo") ?: entry?.optJSONObject("livePhoto")
                val video = entry?.optJSONObject("video") ?: livePhoto?.optJSONObject("video") ?: livePhoto
                val tracks = video?.optJSONArray("bit_rate") ?: video?.optJSONArray("bitRate")
                val motion = (playUrls(video) + (0 until (tracks?.length() ?: 0)).flatMap { playUrls(tracks?.optJSONObject(it)) } +
                    listOf("live_photo_url", "livePhotoUrl", "video_url", "videoUrl").flatMap { imageUrls(entry?.opt(it)) } + videoSources).distinct()
                if (motion.isEmpty() && (entry?.optBoolean("is_live_photo") == true || entry?.optBoolean("isLivePhoto") == true))
                    throw ParseException("第 ${index+1} 张实况图未提供可下载视频")
                if (urls.isEmpty() && motion.isEmpty()) throw ParseException("第 ${index + 1} 张图片暂无可下载地址，请重新解析")
                MediaImage(urls.firstOrNull().orEmpty(), urls.drop(1), motion.firstOrNull().orEmpty(), motion.drop(1),
                    explicit.isNotEmpty() || gifUrls.isNotEmpty() || entry?.optBoolean("isAnimated") == true || entry?.optBoolean("is_animated") == true)

            }
            return MediaInfo(
                id = workId(item).ifBlank { expectedId.orEmpty() },
                title = item.optString("desc").ifBlank { "抖音图集" },
                author = author(item),
                videoUrl = "", coverUrl = images.first().url, pageUrl = pageUrl, images = images,
            )
        }
        if (item.optInt("aweme_type", item.optInt("awemeType")) in listOf(68, 150)) throw ParseException("平台未提供此图集的图片地址，请尝试网页辅助解析")
        val video = item.optJSONObject("video") ?: throw ParseException("作品没有视频信息")
        val rawUrl = listOf("play_addr_h264", "playAddrH264", "play_addr", "playAddr").asSequence()
            .flatMap { imageUrls(video.opt(it)).asSequence() }.firstOrNull()
            ?: throw ParseException("平台未提供视频播放地址，请稍后重试")
        // Upstream's playwm -> play transformation applies only to the official play endpoint.
        val parsed = rawUrl.toHttpUrlOrNull()!!
        val mediaUrl = if ((parsed.host == "douyin.com" || parsed.host.endsWith(".douyin.com") ||
            parsed.host == "www.iesdouyin.com" || parsed.host == "aweme.snssdk.com") &&
            parsed.encodedPath.contains("/playwm/")) {
            parsed.newBuilder().encodedPath(parsed.encodedPath.replace("/playwm/", "/play/")).build().toString()
        } else rawUrl
        val cover = (imageUrls(video.opt("cover")) + imageUrls(video.opt("coverUrlList"))).firstOrNull().orEmpty()
        return MediaInfo(
            id = workId(item).ifBlank { expectedId.orEmpty() },
            title = item.optString("desc").ifBlank { "抖音视频" },
            author = author(item),
            videoUrl = mediaUrl,
            coverUrl = cover.takeIf { it.toHttpUrlOrNull()?.isHttps == true }.orEmpty(),
            pageUrl = pageUrl,
        )
    }

    private fun playUrls(video: JSONObject?) = listOf("play_addr_h264", "playAddrH264", "play_addr", "playAddr",
        "playUrlList", "playApi", "download_addr", "downloadAddr").flatMap { imageUrls(video?.opt(it)) }

    private fun imageArray(item: JSONObject): JSONArray? {
        val post = item.optJSONObject("image_post_info") ?: item.optJSONObject("imagePostInfo")
        return listOf(post?.optJSONArray("images"), post?.optJSONArray("image_list"), post?.optJSONArray("imageList"),
            item.optJSONArray("images"), item.optJSONArray("image_list"), item.optJSONArray("image_infos"),
            item.optJSONArray("original_images"), item.optJSONArray("imageList"), item.optJSONArray("imageInfos"),
            item.optJSONArray("originalImages")).firstOrNull { it != null && it.length() > 0 }
    }

    private fun imageUrls(value: Any?, depth: Int = 0): List<String> {
        if (depth > 4) return emptyList()
        return when (value) {
            is String -> listOfNotNull((if (value.startsWith("//")) "https:$value" else value).toHttpUrlOrNull()?.takeIf {
                it.scheme in listOf("http", "https") && it.username.isEmpty() && it.password.isEmpty() && it.port in listOf(80, 443)
            }?.newBuilder()?.scheme("https")?.port(443)?.build()?.toString())
            is JSONArray -> (0 until value.length()).flatMap { imageUrls(value.opt(it), depth + 1) }
            is JSONObject -> listOf("url_list", "urlList", "display_image", "image_url", "image", "origin_cover", "download_url_list", "download_url", "url", "downloadUrlList", "displayImage", "imageUrl", "originCover", "downloadUrl")
                .flatMap { imageUrls(value.opt(it), depth + 1) }
            else -> emptyList()
        }
    }

    private fun findWorks(value: Any?, depth: Int = 0): List<JSONObject> {
        if (depth > 30) return emptyList()
        return when (value) {
            is JSONObject -> if (workId(value).isNotEmpty() && (value.has("video") || imageArray(value) != null)) listOf(value)
                else value.keys().asSequence().flatMap { findWorks(value.opt(it), depth + 1) }.toList()
            is JSONArray -> (0 until value.length()).flatMap { findWorks(value.opt(it), depth + 1) }
            else -> emptyList()
        }
    }
}
