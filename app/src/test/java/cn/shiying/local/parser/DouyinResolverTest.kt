package cn.shiying.local.parser

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class DouyinResolverTest {
    private val id = "7424432820954598707"

    @Test fun publicGalleryFallbackUsesPageWebIdAndReturnsMatchingImages() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths += request.url.encodedPath
            val body = when {
                request.url.encodedPath.contains("slidesinfo") -> {
                    assertEquals("[$id]", request.url.queryParameter("aweme_ids"))
                    assertEquals("75123456789012345", request.url.queryParameter("web_id"))
                    """{"aweme_details":[{"aweme_id":"$id","images":[{"url_list":["https://example.com/photo.gif"]}]}]}"""
                }
                request.url.encodedPath.contains("feed") -> """{"aweme_list":[]}"""
                else -> """<script>window._ROUTER_DATA={"webId":"75123456789012345"};</script>"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val info = DouyinResolver(client).resolve("https://www.douyin.com/note/$id")
        assertTrue(info.isGallery)
        assertEquals(id, info.id)
        assertEquals("https://example.com/photo.gif", info.images.single().url)
        assertTrue(paths.any { it.contains("slidesinfo") })
    }

    @Test fun unavailableGalleryCannotReturnARecommendedWork() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.contains("slidesinfo"))
                """{"aweme_details":[{"aweme_id":"9999999999999999999","images":[{"url_list":["https://example.com/wrong.jpg"]}]}]}"""
            else if (request.url.encodedPath.contains("feed")) """{"aweme_list":[]}"""
            else "<html>请验证</html>"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        assertThrows(ParseException::class.java) {
            runBlocking { DouyinResolver(client).resolve("https://www.douyin.com/note/$id") }
        }
    }

    @Test fun failedDirectGalleryCarriesDesktopAssistanceUrlAndSkipsRecommendationFeed() {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            paths += chain.request().url.encodedPath
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<html>页面需 JavaScript 加载</html>".toResponseBody()).build()
        }.build()
        val error = assertThrows(ParseException::class.java) {
            runBlocking { DouyinResolver(client).resolve("https://www.douyin.com/note/$id") }
        }
        assertEquals("https://www.douyin.com/note/$id", error.assistanceUrl)
        assertFalse(paths.any { it.contains("/feed/") })
    }
}
