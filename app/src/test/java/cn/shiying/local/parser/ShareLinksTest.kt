package cn.shiying.local.parser

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ShareLinksTest {
    @Test fun extractsLinkFromChineseShareText() {
        assertEquals("https://v.douyin.com/abc/", ShareLinks.extract("3.12 复制打开抖音，看看作品 https://v.douyin.com/abc/。复制此链接").toString())
    }
    @Test fun selectsSupportedLinkWhenTextContainsOthers() {
        assertEquals("v.douyin.com", ShareLinks.extract("https://example.com https://v.douyin.com/abc/").host)
    }
    @Test fun rejectsSpoofedHostsAndUnencryptedLinks() {
        listOf("https://douyin.com.evil.test/video/123", "https://evildouyin.com/", "http://v.douyin.com/a/",
            "https://user@v.douyin.com/a/", "https://v.douyin.com:8443/a/", "https://127.0.0.1/").forEach {
            assertThrows(IllegalArgumentException::class.java) { ShareLinks.extract(it) }
        }
    }
    @Test fun getsIdsFromMobileDesktopAndSelectedVideoUrls() {
        val id = "7424432820954598707"
        listOf("https://www.iesdouyin.com/share/video/$id/", "https://www.douyin.com/video/$id",
            "https://www.douyin.com/note/$id", "https://www.iesdouyin.com/share/slides/$id/", "https://www.douyin.com/jingxuan?modal_id=$id").forEach {
            assertEquals(id, ShareLinks.videoId(it.toHttpUrl()))
        }
        assertNull(ShareLinks.videoId("https://v.douyin.com/abc/".toHttpUrl()))
        assertNull(ShareLinks.videoId("https://www.douyin.com/user/7424432820954598707".toHttpUrl()))
    }
    @Test fun acceptsXiaohongshuTextAndPreservesSecurityParameters() {
        val url = ShareLinks.extract("小红书分享：https://www.xiaohongshu.com/explore/67abcdef0123456789abcdef?xsec_token=ab%2Bcd&xsec_source=pc_share。")
        assertEquals("ab+cd", url.queryParameter("xsec_token"))
        assertEquals("67abcdef0123456789abcdef", ShareLinks.noteId(url))
        assertNull(ShareLinks.videoId(url))
        assertTrue(ShareLinks.isXiaohongshu(ShareLinks.extract("复制打开小红书 http://example.com https://xhslink.com/a/abc")))
        assertTrue(ShareLinks.isXiaohongshu(ShareLinks.extract("分享标题 https://xhslink.cn/o/example 去【小红书】看看")))
    }
    @Test fun rejectsXhsLookalikesAndCrossPlatformRedirects() {
        listOf("https://www.xiaohongshu.com.evil.test/explore/abc", "http://xhslink.com/abc", "https://user@xhslink.com/a/", "https://xhslink.com:8443/a", "https://xhslink.cn.evil.test/o/test").forEach {
            assertThrows(IllegalArgumentException::class.java) { ShareLinks.extract(it) }
        }
        assertFalse(ShareLinks.samePlatform("https://xhslink.com/a/abc".toHttpUrl(), "https://www.douyin.com/video/7424432820954598707".toHttpUrl()))
    }
}
