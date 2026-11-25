package cn.shiying.local.parser

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ShareLinks {
    private val hosts = setOf("v.douyin.com", "www.douyin.com", "douyin.com", "www.iesdouyin.com", "iesdouyin.com")
    private val xhsHosts = setOf("xhslink.com", "www.xhslink.com", "xhslink.cn", "www.xhslink.cn", "xiaohongshu.com", "www.xiaohongshu.com")
    private val link = Regex("https?://[^\\s<>\"，。！？、；（）【】]+", RegexOption.IGNORE_CASE)

    fun isDouyin(url: HttpUrl): Boolean = url.isHttps && url.host in hosts &&
        url.port == 443 && url.username.isEmpty() && url.password.isEmpty()
    fun isXiaohongshu(url: HttpUrl): Boolean = url.isHttps && url.host in xhsHosts &&
        url.port == 443 && url.username.isEmpty() && url.password.isEmpty()
    fun isSupported(url: HttpUrl) = isDouyin(url) || isXiaohongshu(url)
    fun samePlatform(first: HttpUrl, next: HttpUrl) =
        (isDouyin(first) && isDouyin(next)) || (isXiaohongshu(first) && isXiaohongshu(next))

    fun extract(text: String): HttpUrl {
        require(text.length <= 16_384) { "分享内容太长，请只粘贴一条作品链接" }
        return link.findAll(text).mapNotNull {
            it.value.trimEnd('.', ',', ';', '!', '?', ')', ']', '}', '”', '’').toHttpUrlOrNull()
        }.firstOrNull(::isSupported) ?: throw IllegalArgumentException("请粘贴抖音或小红书的作品分享链接")
    }

    fun videoId(url: HttpUrl): String? {
        if (!isDouyin(url)) return null
        url.queryParameter("modal_id")?.takeIf { it.matches(Regex("[0-9]{10,24}")) }?.let { return it }
        return Regex("/(?:share/)?(?:video|note|slides)/(\\d{10,24})(?:/|$)")
            .find(url.encodedPath)?.groupValues?.get(1)
    }
    fun noteId(url: HttpUrl): String? {
        if (!isXiaohongshu(url)) return null
        return Regex("/(?:explore|discovery/item)/(\\p{XDigit}{24})(?:/|$)")
            .find(url.encodedPath)?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT)
    }
}
