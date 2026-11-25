package cn.shiying.local.parser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class MediaPlatform(val label: String, val referer: String) {
    DOUYIN("抖音", "https://www.douyin.com/"), XIAOHONGSHU("小红书", "https://www.xiaohongshu.com/")
}
data class MediaImage(val url: String, val alternatives: List<String> = emptyList(),
    val motionUrl: String = "", val motionAlternatives: List<String> = emptyList(), val animated: Boolean = false) {
    val imageUrls get() = (listOf(url) + alternatives).filter { it.isNotBlank() }.distinct()
    val motionUrls get() = (listOf(motionUrl) + motionAlternatives).filter { it.isNotBlank() }.distinct()
    val isLive get() = motionUrl.isNotBlank()
    val previewUrl get() = url
}

data class MediaInfo(
    val id: String,
    val title: String,
    val author: String,
    val videoUrl: String,
    val coverUrl: String,
    val pageUrl: String,
    val platform: MediaPlatform = MediaPlatform.DOUYIN,
    val images: List<MediaImage> = emptyList(),
) {
    val isGallery get() = videoUrl.isBlank() && images.isNotEmpty()
    val userAgent get() = if (platform == MediaPlatform.XIAOHONGSHU) cn.shiying.local.network.Http.PC_USER_AGENT else cn.shiying.local.network.Http.USER_AGENT
}

class ParseException(message: String, val assistanceUrl: String? = null) : Exception(message)

private val gifAddress = Regex("""(?i)(\.gif(?:[?!/#~]|$)|format[=/]gif(?:[&#/]|$))""")
internal fun isGifAddress(url: String) = gifAddress.containsMatchIn(url)

internal fun isMotionVideoAddress(address: String): Boolean {
    val url = address.toHttpUrlOrNull() ?: return false
    return url.encodedPath.endsWith(".mp4", true) || url.queryParameter("mime_type") in setOf("video_mp4", "video/mp4")
}
