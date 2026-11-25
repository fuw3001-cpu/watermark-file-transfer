package cn.shiying.local.parser

import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

class DouyinDocumentTest {
    private val id = "7424432820954598707"
    private fun item(workId: String = id, images: String = "[]", host: String = "aweme.snssdk.com") = """
        {"aweme_id":"$workId","desc":"测试作品","author":{"nickname":"测试作者"},"images":$images,
        "video":{"play_addr":{"url_list":["https://$host/aweme/v1/playwm/?video_id=abc&ratio=720p"]},
        "cover":{"url_list":["https://example.com/cover.jpg"]}}}
    """.trimIndent()
    private fun page(json: String) = "<script>window._ROUTER_DATA = $json;</script>"

    @Test fun readsRouterDataAndConvertsOfficialWatermarkEndpoint() {
        val html = page("""{"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[${item()}]}}}}""")
        val result = DouyinDocument.parse(html, id, "https://www.douyin.com/video/$id")
        assertEquals("测试作品", result.title)
        assertEquals("测试作者", result.author)
        assertEquals("https://aweme.snssdk.com/aweme/v1/play/?video_id=abc&ratio=720p", result.videoUrl)
    }
    @Test fun readsUrlEncodedRenderData() {
        val data = URLEncoder.encode("""{"aweme":{"detail":${item()}}}""", "UTF-8")
        assertEquals(id, DouyinDocument.parse("<script id='RENDER_DATA'>$data</script>", id, "").id)
    }
    @Test fun doesNotReturnRecommendedVideoWithDifferentId() {
        assertThrows(ParseException::class.java) {
            DouyinDocument.parse(page("""{"recommended":[${item("9999999999999999999")}]}"""), id, "")
        }
    }
    @Test fun imagePostsUseImagesInsteadOfBackgroundMusicVideoAddress() {
        val info = DouyinDocument.parse(page("""{"item":${item(images = "[{\"url_list\":[\"https://example.com/a.jpg\",\"https://example.com/b.webp\"]}]")}}"""), id, "")
        assertTrue(info.isGallery)
        assertEquals("", info.videoUrl)
        assertEquals("https://example.com/a.jpg", info.images.single().url)
        assertEquals(listOf("https://example.com/b.webp"), info.images.single().alternatives)
    }
    @Test fun modernGalleryWithoutVideoKeepsImageOrderAndMatchesWork() {
        val json = """{"aweme_details":[${item("9999999999999999999")}, {"aweme_id":"$id","image_post_info":{"images":[{"display_image":{"url_list":["https://example.com/1.gif"]}}, {"url_list":["https://example.com/2.jpg"]}]}}]}"""
        val info = DouyinDocument.parseFeed(json, id, "")
        assertEquals(listOf("https://example.com/1.gif", "https://example.com/2.jpg"), info.images.map { it.url })
        assertTrue(DouyinDocument.parse(page(json), id, "").isGallery)
    }
    @Test fun invalidGalleryDoesNotSilentlyDropImagesOrReturnMusic() {
        for (images in listOf("[{\"url_list\":[\"javascript:alert(1)\"]}]", "[{\"url_list\":[\"https://example.com/ok.jpg\"]},{}]")) {
            assertThrows(ParseException::class.java) { DouyinDocument.parseFeed("""{"aweme_list":[${item(images = images)}]}""", id, "") }
        }
    }
    @Test fun doesNotModifyUnrelatedHosts() {
        val info = DouyinDocument.parse(page("""{"item":${item(host = "example.com")}}"""), id, "")
        assertTrue(info.videoUrl.contains("/playwm/"))
    }
    @Test fun rejectsVerificationPagesAndMissingVideoResources() {
        listOf("<html>请完成验证</html>", page("""{"item":{"aweme_id":"$id","video":{}}}""")).forEach {
            assertThrows(ParseException::class.java) { DouyinDocument.parse(it, id, "") }
        }
    }
    @Test fun matchesFeedItemInsteadOfFirstRecommendation() {
        val info = DouyinDocument.parseFeed("""{"aweme_list":[${item("9999999999999999999")},${item()}]}""", id, "")
        assertEquals(id, info.id)
    }
    @Test fun prefersH264VideoForAndroidCompatibility() {
        val data = org.json.JSONObject(item())
        data.getJSONObject("video").put("play_addr_h264", org.json.JSONObject("""{"url_list":["https://example.com/h264.mp4"]}"""))
        val info = DouyinDocument.parseFeed("""{"aweme_list":[$data]}""", id, "")
        assertEquals("https://example.com/h264.mp4", info.videoUrl)
    }
    @Test fun fallsBackWhenH264AddressObjectIsEmpty() {
        val data = org.json.JSONObject(item())
        data.getJSONObject("video").put("play_addr_h264", org.json.JSONObject())
        assertTrue(DouyinDocument.parseFeed("""{"aweme_list":[$data]}""", id, "").videoUrl.contains("/play/"))
    }

    private fun modernItem(workId: String = id) = org.json.JSONObject().put("awemeId", workId)
        .put("awemeType", 68).put("desc", "新版图集 + 中文")
        .put("authorInfo", org.json.JSONObject().put("nickname", "作者"))
        .put("images", org.json.JSONArray().put(org.json.JSONObject().put("urlList", org.json.JSONArray()
            .put("https://example.com/photo1.jpg").put("https://example.com/photo1.webp")))
            .put(org.json.JSONObject().put("downloadUrlList", org.json.JSONArray().put("https://example.com/photo2.gif"))))

    private fun flight(chunk: String) = "<script>self.__pace_f.push(" + org.json.JSONArray().put(1).put(chunk).toString() + ");</script>"

    @Test fun parsesModernFlightCamelCaseGalleryWithoutRunningJavascript() {
        val detail = modernItem()
        val payload = "8:[\"$\",\"\$La\",null,{\"awemeId\":\"$id\",\"aweme\":{\"detail\":$detail}}]\n"
        val info = DouyinDocument.parse(flight(payload), id, "")
        assertTrue(info.isGallery)
        assertEquals("作者", info.author)
        assertEquals("新版图集 + 中文", info.title)
        assertEquals(listOf("https://example.com/photo1.jpg", "https://example.com/photo2.gif"), info.images.map { it.url })
        assertEquals(listOf("https://example.com/photo1.webp"), info.images[0].alternatives)
    }

    @Test fun reassemblesFlightRecordsSplitAcrossScriptTags() {
        val raw = "a:[null,{\"detail\":${modernItem()}}]\n"
        val cut = raw.length / 2
        val info = DouyinDocument.parse(flight(raw.take(cut)) + flight(raw.drop(cut)), id, "")
        assertEquals(2, info.images.size)
    }

    @Test fun modernFlightDoesNotReturnDifferentRecommendedWork() {
        assertThrows(ParseException::class.java) {
            DouyinDocument.parse(flight("a:[${modernItem("9999999999999999999")}]\n"), id, "")
        }
    }

    @Test fun camelCaseRenderDataAndUnrelatedExecutableScriptAreHandledSeparately() {
        val data = URLEncoder.encode("""{"detail":${modernItem()}}""", "UTF-8")
        val html = "<script>throw new Error('must not run');</script><script id='RENDER_DATA'>$data</script>"
        assertEquals(2, DouyinDocument.parse(html, id, "").images.size)
    }
}
