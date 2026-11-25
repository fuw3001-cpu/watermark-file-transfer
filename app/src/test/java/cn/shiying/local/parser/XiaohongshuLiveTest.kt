package cn.shiying.local.parser

import cn.shiying.local.network.Http
import cn.shiying.local.network.await
import cn.shiying.local.storage.ImageEncoding
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class XiaohongshuLiveTest {
    @Test fun authorizedNoteReturnsRealMedia() = runBlocking {
        val link = System.getenv("SHIYING_LIVE_XHS_LINK").orEmpty()
        assumeTrue("Set SHIYING_LIVE_XHS_LINK to an authorized public note", link.isNotBlank())
        val info = XiaohongshuResolver().resolve(link)
        assertEquals(MediaPlatform.XIAOHONGSHU, info.platform)
        val outputPath = System.getenv("SHIYING_XHS_OUTPUT").orEmpty()
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
