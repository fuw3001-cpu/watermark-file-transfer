package cn.shiying.local.parser

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class XiaohongshuDocumentTest {
    private val id = "67abcdef0123456789abcdef"
    private val other = "68abcdef0123456789abcdef"
    private fun note(noteId: String = id) = """{"noteId":"$noteId","title":"undefined 与中文","type":"video","user":{"nickname":"测试作者"},"video":{"consumer":{"originVideoKey":"video/test.mp4"},"media":{"stream":{"h264":[{"masterUrl":"https://sns-video-bd.xhscdn.com/fallback.mp4"}]}}},"imageList":[]}"""
    private fun page(note: String = note()) = """<script>window.__INITIAL_STATE__={"unused":undefined,"note":{"currentNoteId":"$id","noteDetailMap":{"$id":{"note":$note}}}};</script>"""

    @Test fun extractsExactNoteWithoutChangingUndefinedInsideQuotedText() {
        val result = XiaohongshuDocument.parse(page(), id, "https://www.xiaohongshu.com/explore/$id")
        assertEquals("undefined 与中文", result.title)
        assertEquals("测试作者", result.author)
        assertEquals("https://sns-video-bd.xhscdn.com/video/test.mp4", result.videoUrl)
        assertEquals(MediaPlatform.XIAOHONGSHU, result.platform)
        assertEquals("https://www.xiaohongshu.com/", result.platform.referer)
    }
    @Test fun doesNotAcceptRecommendedOrWrongNote() {
        assertThrows(ParseException::class.java) { XiaohongshuDocument.parse(page(note(other)), id, "") }
        assertThrows(ParseException::class.java) { XiaohongshuDocument.parse(page().replace("\"$id\":{", "\"$other\":{"), id, "") }
    }
    @Test fun mobileDataAndH264FallbackAreSupported() {
        val data = """<script>window.__INITIAL_STATE__={"noteData":{"data":{"noteData":{"id":"$id","type":"video","video":{"media":{"stream":{"h264":[{"masterUrl":"http://sns-video-bd.xhscdn.com/a.mp4"}]}}}}}}};</script>"""
        assertEquals("https://sns-video-bd.xhscdn.com/a.mp4", XiaohongshuDocument.parse(data, id, "").videoUrl)
    }
    @Test fun galleryKeepsOriginalUrlAsFallbackAndIsNotAPlayableVideo() {
        val original = "https://sns-webpic-qc.xhscdn.com/a/notes_pre_post/example!watermark"
        val data = """{"noteId":"$id","type":"normal","imageList":[{"fileId":"notes_pre_post/example","urlDefault":"$original"},{"urlDefault":"https://sns-img-qc.xhscdn.com/b.jpg"}]}"""
        val result = XiaohongshuDocument.parse(page(data), id, "")
        assertTrue(result.isGallery)
        assertEquals(2, result.images.size)
        assertTrue(result.images.first().url.contains("format/jpg"))
        assertTrue(result.images.first().alternatives.contains(original))
    }
    @Test fun invalidMediaAndVerificationPagesFailClearly() {
        assertThrows(ParseException::class.java) { XiaohongshuDocument.parse("<html>请登录</html>", id, "") }
        val invalid = note().replace("video/test.mp4", "https://127.0.0.1/secret").replace("https://sns-video-bd.xhscdn.com/fallback.mp4", "https://xhscdn.com.evil.test/a.mp4")
        assertThrows(ParseException::class.java) { XiaohongshuDocument.parse(page(invalid), id, "") }
    }
    @Test fun balancedExtractionIgnoresQuotedBracesAndTrailingJavascript() {
        val content = org.json.JSONObject().put("title", """a } " b""").toString()
        val json = InitialState.extract("window.__INITIAL_STATE__=$content;alert('ignored')")
        assertNotNull(json)
        assertFalse(json!!.contains("alert"))
    }
    @Test fun shortLinkRedirectKeepsXsecTokenAndBlocksAnotherPlatform() = runBlocking {
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor(Interceptor { chain ->
            calls += chain.request().url.toString()
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            if (chain.request().url.host == "xhslink.com") builder.code(302).message("redirect")
                .header("Location", "https://www.xiaohongshu.com/explore/$id?xsec_token=ab%2Bcd&xsec_source=pc_share").body("".toResponseBody()).build()
            else builder.code(200).message("OK").body(page().toResponseBody()).build()
        }).build()
        try {
            val result = XiaohongshuResolver(client).resolve("https://xhslink.com/a/test")
            assertEquals(id, result.id)
            assertTrue(calls[1].contains("xsec_token=ab%2Bcd"))
            assertEquals(2, calls.size)
        } finally { client.dispatcher.executorService.shutdown() }
    }
    @Test fun collectionPlaceholdersAreNormalizedWithoutExecutingThem() {
        val value = InitialState.extract("""window.__INITIAL_STATE__={"unused":new Map([]),"set":new Set(["("]),"date":new Date(0),"label":"new Map undefined"};""")
        val parsed = org.json.JSONObject(value!!)
        assertEquals(0, parsed.getJSONObject("unused").length())
        assertEquals("new Map undefined", parsed.getString("label"))
        assertTrue(parsed.isNull("date"))
    }
    @Test fun prefersNormalStreamOverMarkedFallback() {
        val note = """{"noteId":"$id","type":"video","video":{"media":{"stream":{"h264":[{"streamType":259,"masterUrl":"https://sns-video-bd.xhscdn.com/marked.mp4"},{"streamType":258,"masterUrl":"https://sns-video-bd.xhscdn.com/clean.mp4"}]}}}}"""
        assertEquals("https://sns-video-bd.xhscdn.com/clean.mp4", XiaohongshuDocument.parse(page(note), id, "").videoUrl)
    }
    @Test fun rejectsCrossDomainRedirectBeforeRequestingIt() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            calls++
            assertEquals("xhslink.com", chain.request().url.host)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("redirect")
                .header("Location", "https://example.com/private").body("".toResponseBody()).build()
        }.build()
        try {
            var failed = false
            try { XiaohongshuResolver(client).resolve("https://xhslink.com/a/test") } catch (_: IllegalArgumentException) { failed = true }
            assertTrue(failed)
            assertEquals(2, calls) // Each allowed UA makes only the original request.
        } finally { client.dispatcher.executorService.shutdown() }
    }
}
