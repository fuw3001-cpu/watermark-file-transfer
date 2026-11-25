package cn.shiying.local.parser

import org.junit.Assert.*
import org.junit.Test

class BrowserPageTest {
    private val id="7424432820954598707"
    private fun gallery(workId:String=id)="""<script>window._ROUTER_DATA={"item":{"aweme_id":"$workId","images":[{"url_list":["https://example.com/1.jpg"]},{"url_list":["https://example.com/2.jpg"]}]}};</script>"""
    @Test fun loadedGalleryGoesDirectlyToTheSameMediaResultAsHttpParsing() {
        val result=BrowserPage.parseDouyin(gallery(),"https://www.douyin.com/note/$id","https://www.douyin.com/note/$id")
        assertTrue(result.isGallery)
        assertEquals(listOf("https://example.com/1.jpg","https://example.com/2.jpg"),result.images.map { it.url })
    }
    @Test fun shortLinkCanAdoptOnlyTheLoadedSupportedWorkIdentity() {
        assertEquals(id,BrowserPage.parseDouyin(gallery(),"https://v.douyin.com/example/","https://www.douyin.com/note/$id").id)
        for(url in listOf("https://example.com/note/$id","https://www.douyin.com.evil.test/note/$id","https://www.douyin.com/")) {
            assertThrows(ParseException::class.java) { BrowserPage.parseDouyin(gallery(),"https://v.douyin.com/example/",url) }
        }
    }
    @Test fun changingThePageOrOnlyReturningRecommendationsCannotReplaceTheSharedWork() {
        val other="7424432820954598708"
        assertThrows(ParseException::class.java) { BrowserPage.parseDouyin(gallery(),"https://www.douyin.com/note/$id","https://www.douyin.com/note/$other") }
        assertThrows(ParseException::class.java) { BrowserPage.parseDouyin(gallery(other),"https://www.douyin.com/note/$id","https://www.douyin.com/note/$id") }
    }
}
