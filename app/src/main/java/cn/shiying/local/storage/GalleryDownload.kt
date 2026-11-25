package cn.shiying.local.storage

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import cn.shiying.local.network.await
import cn.shiying.local.parser.MediaImage
import cn.shiying.local.parser.MediaInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** One budget per save, including rejected CDN responses. Only complete candidates reach MediaStore. */
internal class GalleryDownload(private val info: MediaInfo, private val client: OkHttpClient,
                               private val progress: (Long, Long) -> Unit) {
    private var received = 0L
    private val batchLimit = 512L * 1024 * 1024
    private val fullRange = Regex("""bytes 0-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

    suspend fun image(item: MediaImage, file: File): ImageEncoding {
        val needsAnimation = item.animated && !item.isLive
        return fetch(item.imageUrls, file, 64L * 1024 * 1024, "image/gif, image/webp, image/apng, image/png, image/jpeg",
            if (needsAnimation) "没有取得完整的多帧动图，平台可能只提供了封面。请重新解析图集。" else "无法取得完整图片，请重新解析图集。") {
            val encoding = RandomAccessFile(file, "r").use { input ->
                val header = ByteArray(12)
                input.readFully(header)
                ImageEncoding.sniff(header)
            }
            encoding?.takeIf { !needsAnimation || isAnimated(file, it) }
        }
    }

    suspend fun movie(item: MediaImage, file: File) {
        fetch(item.motionUrls, file, 128L * 1024 * 1024, "video/mp4, video/*;q=0.9, */*;q=0.5",
            "实况视频未完整下载，请重新解析图集后再试。") {
            val mp4 = RandomAccessFile(file, "r").use { input ->
                input.seek(4)
                input.readInt() == 0x66747970 // ftyp
            }
            if (!mp4) return@fetch null
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                true.takeIf { (0 until extractor.trackCount).any { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } }
            } catch (_: IllegalArgumentException) { null }
            finally { extractor.release() }
        }
    }

    private suspend fun <T : Any> fetch(urls: List<String>, file: File, limit: Long, accept: String,
                                       failure: String, validate: () -> T?): T {
        val job = currentCoroutineContext()
        for (url in urls) {
            job.ensureActive()
            val request = Request.Builder().url(url).header("User-Agent", info.userAgent)
                .header("Referer", info.platform.referer).header("Accept", accept).build()
            try {
                client.newCall(request).await().use { response ->
                    val body = response.body ?: return@use
                    val expected = when (response.code) {
                        200 -> body.contentLength()
                        206 -> {
                            val range = fullRange.matchEntire(response.header("Content-Range").orEmpty()) ?: return@use
                            val total = range.groupValues[2].toLongOrNull() ?: return@use
                            if (total <= 0 || range.groupValues[1].toLongOrNull() != total - 1) return@use
                            total
                        }
                        else -> return@use
                    }
                    if (expected == 0L || expected > limit) return@use
                    var size = 0L
                    file.outputStream().buffered().use { out ->
                        val source = body.source()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            job.ensureActive()
                            val n = source.read(buffer)
                            if (n == -1) break
                            size += n
                            received += n
                            require(received <= batchLimit) { "图集下载总量超过 512 MB，请分批保存" }
                            if (size > limit) throw IOException("单个媒体超过大小限制")
                            out.write(buffer, 0, n)
                            progress(received, -1)
                        }
                    }
                    job.ensureActive()
                    if (size < 12 || expected >= 0 && size != expected) return@use
                    validate()?.let { return it }
                }
            } catch (_: IOException) { job.ensureActive() }
        }
        throw IOException(failure)
    }

    companion object {
        private fun completeGif(file: File): Boolean = DataInputStream(file.inputStream().buffered()).use { data ->
            fun colorTable(packed: Int) {
                if (packed and 0x80 != 0) data.skipBytes(3 * (1 shl ((packed and 7) + 1)))
            }
            fun blocks() {
                var size = data.readUnsignedByte()
                while (size != 0) {
                    if (data.skipBytes(size) != size) throw IOException("GIF 数据不完整")
                    size = data.readUnsignedByte()
                }
            }
            data.skipBytes(10) // signature, width and height
            val screenFlags = data.readUnsignedByte()
            data.skipBytes(2)
            colorTable(screenFlags)
            var frames = 0
            var block = data.read()
            while (block != 0x3b) {
                when (block) {
                    0x21 -> { data.readUnsignedByte(); blocks() }
                    0x2c -> {
                        data.skipBytes(8) // image position and dimensions
                        colorTable(data.readUnsignedByte())
                        data.readUnsignedByte() // LZW minimum code size
                        blocks()
                        frames++
                    }
                    else -> return@use false
                }
                block = data.read()
            }
            frames > 1
        }

        fun isAnimated(file: File, format: ImageEncoding): Boolean {
            return when (format) {
                ImageEncoding.GIF, ImageEncoding.WEBP -> {
                    val complete = if (format == ImageEncoding.GIF) completeGif(file) else RandomAccessFile(file, "r").use { input ->
                        input.seek(4)
                        (Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL) + 8 <= input.length()
                    }
                    if (!complete) return false
                    val image = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, _, _ ->
                        decoder.setTargetSize(1, 1)
                    }
                    image is AnimatedImageDrawable
                }
                ImageEncoding.PNG -> RandomAccessFile(file, "r").use { input ->
                    input.seek(8)
                    var frames = 0
                    var declared = 0
                    while (input.filePointer + 12 <= input.length()) {
                        val size = input.readInt().toLong() and 0xffffffffL
                        val type = input.readInt()
                        val end = input.filePointer + size + 4 // chunk data plus CRC
                        if (end > input.length()) break
                        when (type) {
                            0x6163544c -> if (size == 8L) declared = input.readInt() // acTL
                            0x6663544c -> if (size == 26L) frames++ // fcTL
                            0x49454e44 -> return@use frames > 1 && frames == declared // IEND
                        }
                        input.seek(end)
                    }
                    false
                }
                ImageEncoding.JPEG -> false
            }
        }
    }
}
