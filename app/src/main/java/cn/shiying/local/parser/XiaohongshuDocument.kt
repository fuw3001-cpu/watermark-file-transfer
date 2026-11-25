/* Public page state mapping adapted from parse-video/redbook.go and media-parser/xiaohongshu_parser.py (MIT).
 * Copyright (c) 2022 wujunwei928; Copyright (c) 2025-2026 ucmao. See THIRD_PARTY_NOTICES.md.
 */
package cn.shiying.local.parser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

object XiaohongshuDocument {
    fun parse(html: String, expectedId: String, pageUrl: String): MediaInfo {
        val roots = Jsoup.parse(html).select("script").mapNotNull { script ->
            InitialState.extract(script.data())?.let { runCatching { JSONObject(it) }.getOrNull() }
        }
        var note: JSONObject? = null
        for (root in roots) {
            val noteRoot = root.optJSONObject("note")
            val map = noteRoot?.optJSONObject("noteDetailMap")
            val boundNote = map?.optJSONObject(expectedId)?.optJSONObject("note")
            if (boundNote != null && boundNote.length() > 0 && boundNote.optString("noteId").isBlank() && boundNote.optString("id").isBlank()) {
                // The exact map key binds this object to the requested note; no recommendation fallback.
                boundNote.put("noteId", expectedId)
            }
            val candidates = listOfNotNull(
                map?.optJSONObject(expectedId)?.optJSONObject("note"),
                root.optJSONObject("noteData")?.optJSONObject("data")?.optJSONObject("noteData"),
                root.optJSONObject("noteData")?.optJSONObject("note"), noteRoot?.optJSONObject("note"),
            )
            note = candidates.firstOrNull {
                val id = it.optString("noteId").ifBlank { it.optString("id") }
                id.equals(expectedId, true)
            }
            if (note != null) break
        }
        val data = note ?: throw ParseException("小红书页面未提供目标笔记详情。请复制完整分享链接（保留参数）；也可尝试网页辅助解析。")
        val video = data.optJSONObject("video")
        val origin = video?.optJSONObject("consumer")?.optString("originVideoKey").orEmpty()
            .ifBlank { video?.optString("originVideoKey").orEmpty() }
        val stream = video?.optJSONObject("media")?.optJSONObject("stream") ?: video?.optJSONObject("stream")
        val candidates = mutableListOf<String>()
        if (origin.isNotBlank()) candidates += if (origin.startsWith("http")) origin else "https://sns-video-bd.xhscdn.com/${origin.trimStart('/')}"
        val mediaV2 = runCatching { JSONObject(video?.optString("mediaV2").orEmpty()) }.getOrNull()
        val opaque = mediaV2?.optJSONObject("video")?.optJSONObject("opaque1")
        candidates += opaque?.optString("hd_screencast_stream").orEmpty()
        candidates += opaque?.optString("default_screencast_stream").orEmpty()
        val markedFallbacks = mutableListOf<String>()
        for (codec in listOf("h264", "h265")) {
            val array = stream?.optJSONArray(codec) ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optInt("streamType") in listOf(259, 309)) markedFallbacks += item.optString("masterUrl")
                else candidates += item.optString("masterUrl")
            }
        }
        candidates += markedFallbacks
        val videoUrl = candidates.firstNotNullOfOrNull(::mediaUrl).orEmpty()
        val list = data.optJSONArray("imageList") ?: JSONArray()
        require(list.length() <= 100) { "图集超过 100 张，当前版本暂不支持" }
        val images = (0 until list.length()).mapNotNull { index ->
            val image = list.optJSONObject(index) ?: return@mapNotNull null
            val urls = mutableListOf<String>()
            val fileId = image.optString("fileId")
            if (fileId.isNotBlank() && !fileId.contains("..") && !fileId.startsWith("http")) {
                urls += "https://sns-img-qc.xhscdn.com/${fileId.trimStart('/')}?imageView2/2/w/1920/format/jpg"
            }
            val original = image.optString("urlDefault")
            val objectKey = Regex("/[0-9a-fA-F]{32}/([^!?]+)").find(original)?.groupValues?.get(1)
            if (objectKey != null && !objectKey.contains("..")) urls += "https://sns-img-qc.xhscdn.com/$objectKey?imageView2/2/w/1920/format/jpg"
            if (original.contains("/notes_pre_post/") && objectKey == null) {
                val path = original.toHttpUrlOrNull()?.encodedPath?.substringAfter("/notes_pre_post/")?.substringBefore('!')
                if (!path.isNullOrBlank() && !path.contains("..")) urls += "https://ci.xiaohongshu.com/notes_pre_post/$path?imageView2/format/jpg"
            }
            val infos = image.optJSONArray("infoList") ?: JSONArray()
            val sources = (listOf(image.optString("urlOriginal"), image.optString("url"), original) +
                (0 until infos.length()).map { infos.optJSONObject(it)?.optString("url").orEmpty() } +
                image.optString("urlPre")).mapNotNull(::mediaUrl).distinct()
            val explicit = listOf("animatedUrl", "gifUrl", "urlGif", "animated_url", "gif_url").flatMap { mediaUrls(image.opt(it)) }
            val gifSources = sources.filter(::isGifAddress)
            val animated = explicit.isNotEmpty() || gifSources.isNotEmpty() || image.optBoolean("isAnimated") ||
                image.optBoolean("is_animated") || image.optString("format").equals("gif", true)
            val webp = sources.filter { address ->
                address.toHttpUrlOrNull()?.let { it.encodedPath.contains("webp", true) || it.query.orEmpty().contains("webp", true) } == true
            }
            // Generated JPEG renditions can flatten originals whose URL does not advertise its format.
            val renditions = urls.mapNotNull(::mediaUrl)
            val preferred = sources.filterNot { it.substringBefore('?').endsWith(".jpg", true) ||
                it.substringBefore('?').endsWith(".jpeg", true) || it.contains("!watermark", true) }
            val candidatesForImage = (explicit + gifSources + webp + if (animated) sources else preferred + renditions + sources).distinct()
            val (videoSources, valid) = candidatesForImage.partition(::isMotionVideoAddress)
            val videoInfo = image.optJSONObject("video")
            val liveInfo = image.optJSONObject("livePhoto") ?: image.optJSONObject("live_photo")
            val streams = listOfNotNull(image.optJSONObject("stream"), videoInfo?.optJSONObject("stream"),
                videoInfo?.optJSONObject("media")?.optJSONObject("stream"), liveInfo?.optJSONObject("stream"))
            val motions = mutableListOf<String>()
            for (streamInfo in streams) for (codec in listOf("h264", "h265")) {
                val tracks = streamInfo.optJSONArray(codec) ?: continue
                for (i in 0 until tracks.length()) {
                    val track = tracks.optJSONObject(i) ?: continue
                    for (field in listOf("masterUrl", "master_url", "backupUrls", "backup_urls"))
                        motions += mediaUrls(track.opt(field))
                }
            }
            val live = (motions + mediaUrls(image.opt("livePhotoUrl")) + mediaUrls(image.opt("live_photo_url")) + videoSources).distinct()
            if ((image.optBoolean("livePhoto") || image.optBoolean("live_photo")) && live.isEmpty())
                throw ParseException("第 ${index+1} 张实况图未提供可下载视频，请稍后重试")
            if (valid.isEmpty() && live.isEmpty()) null else
                MediaImage(valid.firstOrNull().orEmpty(), valid.drop(1), live.firstOrNull().orEmpty(), live.drop(1), animated)
        }
        if (videoUrl.isBlank() && data.optString("type") == "video") throw ParseException("小红书未返回可下载的视频地址，请更换链接或稍后重试")
        if (videoUrl.isBlank() && images.isEmpty()) throw ParseException("笔记没有可下载的视频或图片")
        return MediaInfo(expectedId, data.optString("title").ifBlank { data.optString("desc").ifBlank { "小红书笔记" } },
            data.optJSONObject("user")?.optString("nickname").orEmpty().ifBlank { data.optJSONObject("user")?.optString("nickName").orEmpty() }, videoUrl,
            mediaUrl(data.optJSONObject("cover")?.optString("urlDefault").orEmpty())
                ?: images.firstOrNull()?.url.orEmpty(),
            pageUrl, MediaPlatform.XIAOHONGSHU, if (videoUrl.isBlank()) images else emptyList())
    }

    private fun mediaUrls(value: Any?): List<String> = when (value) {
        is String -> listOfNotNull(mediaUrl(value))
        is JSONArray -> (0 until value.length()).flatMap { mediaUrls(value.opt(it)) }
        is JSONObject -> listOf("url", "urlList", "url_list").flatMap { mediaUrls(value.opt(it)) }
        else -> emptyList()
    }

    private fun mediaUrl(value: String): String? {
        val url = (if (value.startsWith("//")) "https:$value" else value).toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (!(url.host.endsWith(".xhscdn.com") || url.host == "xhscdn.com" || url.host.endsWith(".xiaohongshu.com"))) return null
        if (url.port !in listOf(80, 443)) return null
        return url.newBuilder().scheme("https").port(443).build().toString()
    }
}
