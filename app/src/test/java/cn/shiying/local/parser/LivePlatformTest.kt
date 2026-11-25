package cn.shiying.local.parser

import cn.shiying.local.network.Http
import cn.shiying.local.network.await
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in smoke test. Never contacts the platform during ordinary test runs. */
class LivePlatformTest {
    @Test fun suppliedPublicLinkResolvesToMp4() = runBlocking {
        val link = System.getenv("SHIYING_LIVE_LINK").orEmpty()
        assumeTrue("Set SHIYING_LIVE_LINK to test an authorized public video", link.isNotEmpty())
        val info = DouyinResolver().resolve(link)
        assertTrue(info.id.isNotBlank())
        assertTrue(info.title.isNotBlank())
        val outputPath = System.getenv("SHIYING_LIVE_VIDEO_PATH").orEmpty()
        val builder = Request.Builder().url(info.videoUrl).header("User-Agent", Http.USER_AGENT)
            .header("Referer", "https://www.douyin.com/")
        if (outputPath.isEmpty()) builder.header("Range", "bytes=0-1023")
        val request = builder.build()
        Http.mediaClient.newCall(request).await().use { response ->
            assertTrue("Media HTTP status ${response.code}", response.isSuccessful)
            val source = response.body!!.source()
            assertTrue("Media response needs MP4 header", source.request(12))
            assertEquals("ftyp", source.buffer.clone().readByteArray(12).copyOfRange(4, 8).toString(Charsets.US_ASCII))
            if (outputPath.isNotEmpty()) {
                val file = java.io.File(outputPath)
                file.parentFile?.mkdirs()
                var bytes = 0L
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        bytes += count
                        check(bytes <= 512L * 1024 * 1024) { "Live video exceeds test size limit" }
                        output.write(buffer, 0, count)
                    }
                }
                if (response.body!!.contentLength() >= 0) assertEquals(response.body!!.contentLength(), bytes)
                println("Complete live media download verified: $bytes bytes")
            }
            println("Live check passed: matching video metadata and MP4 media response")
        }
    }
}
