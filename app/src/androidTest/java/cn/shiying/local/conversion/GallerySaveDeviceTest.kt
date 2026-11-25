package cn.shiying.local.conversion

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shiying.local.parser.*
import cn.shiying.local.storage.ImageSaver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class GallerySaveDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext
    private val resolver = app.contentResolver
    private fun bytes(name: String) = instrument.context.assets.open("gallery/$name").use { it.readBytes() }
    private val calls = mutableListOf<String>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        check(request.header("Referer") != null && request.header("User-Agent") != null)
        val name = request.url.pathSegments.last()
        calls += name
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
        val body = when (name) {
            "expired" -> { response.code(403); ByteArray(0) }
            "partial" -> { response.code(206).header("Content-Range", "bytes 0-99/580"); bytes("moving.gif").copyOf(100) }
            "short" -> { response.code(200); bytes("moving.gif").let { it.copyOf(it.size - 1) } }
            "full" -> bytes("moving.gif").also { response.code(206).header("Content-Range", "bytes 0-${it.lastIndex}/${it.size}") }
            else -> { response.code(200); bytes(name) }
        }
        response.body(body.toResponseBody()).build()
    }.build()
    private val saver = ImageSaver(app, client)

    private fun savedBytes(uri: Uri, mime: String): ByteArray {
        assertEquals(mime, resolver.getType(uri))
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        return resolver.openInputStream(uri)!!.use { it.readBytes() }
    }

    @Test fun markedAnimationsRejectSingleFramesAndIncompleteSourcesAndKeepOriginalBytes() = runBlocking {
        val json = """{"aweme_id":"123","images":[{"animatedUrl":{"urlList":["https://example.com/still.webp","https://example.com/single.gif","https://example.com/partial","https://example.com/short","https://example.com/full"]}},{"animatedUrl":"https://example.com/moving.webp"},{"animatedUrl":"https://example.com/moving.png"}]}"""
        val info = DouyinDocument.parseFeed(json, "123", "")
        val uris = saver.saveSelected(info, info.images.indices.toList()) { _, _ -> }
        try {
            listOf("gif" to "image/gif", "webp" to "image/webp", "png" to "image/png").forEachIndexed { index, (extension, mime) ->
                assertArrayEquals(bytes("moving.$extension"), savedBytes(uris[index], mime))
            }
            assertTrue(ImageDecoder.decodeDrawable(ImageDecoder.createSource(resolver, uris[0])) is AnimatedImageDrawable)
            assertTrue(ImageDecoder.decodeDrawable(ImageDecoder.createSource(resolver, uris[1])) is AnimatedImageDrawable)
            assertEquals(listOf("still.webp", "single.gif", "partial", "short", "full"), calls.take(5))
        } finally { uris.forEach { resolver.delete(it, null, null) } }
    }

    @Test fun mobileLiveUsesBackupForMotionPhotoGifAndMp4EvenWithoutCover() = runBlocking {
        val id = "64c8ae73000000001900b94d"
        val data = """{"id":"$id","imageList":[{"url":"https://sns-img-qc.xhscdn.com/expired","livePhoto":true,"stream":{"h264":[{"masterUrl":"https://sns-video-bd.xhscdn.com/expired","backupUrls":["https://sns-video-bd.xhscdn.com/motion.mp4"]}]}}]}"""
        val info = XiaohongshuDocument.parse("""<script>window.__INITIAL_STATE__={"noteData":{"data":{"noteData":$data}}};</script>""", id, "")
        val created = mutableListOf<Uri>()
        try {
            created += saver.saveSelected(info, listOf(0)) { _, _ -> }
            val movie = bytes("motion.mp4")
            val live = savedBytes(created.last(), "image/jpeg")
            assertArrayEquals(movie, live.copyOfRange(live.size - movie.size, live.size))
            assertTrue(live.toString(Charsets.ISO_8859_1).contains("GCamera:MotionPhoto="))
            created += saver.saveLive(info, 0, OutputFormat.MP4) { _, _ -> }
            assertArrayEquals(movie, savedBytes(created.last(), "video/mp4"))
            created += saver.saveLive(info, 0, OutputFormat.GIF) { _, _ -> }
            savedBytes(created.last(), "image/gif")
            assertTrue(ImageDecoder.decodeDrawable(ImageDecoder.createSource(resolver, created.last())) is AnimatedImageDrawable)
        } finally { created.forEach { resolver.delete(it, null, null) } }
    }

    @Test fun batchWithOnlyAStillPosterForMarkedAnimationRollsBack() = runBlocking {
        val id = "galleryQA${System.nanoTime()}"
        val info = MediaInfo(id, "", "", "", "", "", images = listOf(MediaImage("https://example.com/moving.gif"),
            MediaImage("https://example.com/still.webp", animated = true)))
        try {
            saver.saveSelected(info, listOf(0, 1)) { _, _ -> }
            fail("Should reject the static poster")
        } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("多帧")) }
        @Suppress("DEPRECATION")
        val includingPending = MediaStore.setIncludePending(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        resolver.query(includingPending, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("%$id%"), null)!!.use { assertEquals(0, it.count) }
    }
}
