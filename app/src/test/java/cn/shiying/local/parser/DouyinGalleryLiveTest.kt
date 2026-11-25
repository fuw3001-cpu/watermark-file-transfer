package cn.shiying.local.parser

import cn.shiying.local.network.Http
import cn.shiying.local.network.await
import cn.shiying.local.storage.ImageEncoding
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DouyinGalleryLiveTest {
    @Test fun authorizedGalleryReturnsRealImages() = runBlocking {
        val link = System.getenv("SHIYING_LIVE_DOUYIN_GALLERY").orEmpty()
        assumeTrue("Set SHIYING_LIVE_DOUYIN_GALLERY to an authorized public note", link.isNotBlank())
        val info = DouyinResolver().resolve(link)
        verifyImages(info)
    }

    @Test fun authorizedBrowserPageReturnsRealImages() = runBlocking {
        val html = System.getenv("SHIYING_DOUYIN_BROWSER_HTML").orEmpty()
        val url = System.getenv("SHIYING_DOUYIN_BROWSER_URL").orEmpty()
        assumeTrue("Supply an authorized browser page and canonical URL", html.isNotBlank() && url.isNotBlank())
        val page = ShareLinks.extract(url)
        require(ShareLinks.isDouyin(page))
        val id = ShareLinks.videoId(page) ?: error("Expected a work URL")
        val info = DouyinDocument.parse(java.io.File(html).readText(), id, url)
        println("Browser-assisted extraction (separate from native direct resolver)")
        verifyImages(info)
    }

    private suspend fun verifyImages(info: MediaInfo) {
        assertEquals(MediaPlatform.DOUYIN, info.platform)
        assertTrue("Expected a gallery", info.isGallery)
        val outputPath = System.getenv("SHIYING_DOUYIN_GALLERY_OUTPUT").orEmpty()
        val all = outputPath.isNotEmpty()
        val media = if (info.isGallery) info.images.map { listOf(it.url) + it.alternatives } else listOf(listOf(info.videoUrl))
        val selected = if (all) media else media.take(1)
        var aggregate = 0L
        println("Matched target note: ${if (info.isGallery) "gallery, ${media.size} images" else "video"}")
        selected.forEachIndexed { index, urls ->
            var valid = false
            for (url in urls) {
                val builder = Request.Builder().url(url).header("User-Agent", info.userAgent).header("Referer", info.platform.referer)
                if (!all) builder.header("Range", "bytes=0-1023")
                Http.mediaClient.newCall(builder.build()).await().use { response ->
                    if (response.isSuccessful) {
                        val source = response.body!!.source()
                        if (source.request(12)) {
                            val bytes = source.buffer.clone().readByteArray(12)
                            val encoding = ImageEncoding.sniff(bytes)
                            valid = if (info.isGallery) encoding != null else bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp"
                            if (valid && all) {
                                val file = java.io.File(outputPath, "media-${index + 1}.${encoding?.extension ?: "mp4"}")
                                file.parentFile?.mkdirs()
                                var count = 0L
                                file.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val read = source.read(buffer)
                                        if (read < 0) break
                                        count += read; aggregate += read
                                        val perItemLimit = if (info.isGallery) 64L * 1024 * 1024 else 512L * 1024 * 1024
                                        check(count <= perItemLimit && aggregate <= 512L * 1024 * 1024) { "Media test size limit exceeded" }
                                        output.write(buffer, 0, read)
                                    }
                                }
                                if (response.body!!.contentLength() >= 0) assertEquals(response.body!!.contentLength(), count)
                                println("Verified complete media ${index + 1}: $count bytes")
                            }
                        }
                    }
                }
                if (valid) break
            }
            assertTrue("Platform returned no usable bytes for media ${index + 1}", valid)
        }
    }
}
