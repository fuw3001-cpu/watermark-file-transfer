package cn.shiying.local.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the actual Android codecs, JNI library and MediaStore, without network access. */
@RunWith(AndroidJUnit4::class)
class ConversionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun imageFormatsPreserveDimensionsAndOriginal() = runBlocking {
        val source = File.createTempFile("image-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val original = source.readBytes()
        try {
            for (format in ConversionSpec.imageFormats) {
                val result = FileConverter(context).convert(SelectedFile(Uri.fromFile(source), source.name, source.length()),
                    ConversionSpec(imageFormat = format)) { _, _ -> }
                try {
                    val decoded = context.contentResolver.openInputStream(result.uri).use { BitmapFactory.decodeStream(it) }!!
                    assertEquals(32, decoded.width)
                    assertEquals(24, decoded.height)
                    if (format == OutputFormat.JPG) assertTrue(Color.red(decoded.getPixel(0, 0)) >= 250)
                    else assertEquals(0, Color.alpha(decoded.getPixel(0, 0)))
                    decoded.recycle()
                    assertEquals(format.mime, context.contentResolver.getType(result.uri))
                    assertArrayEquals(original, source.readBytes())
                } finally { context.contentResolver.delete(result.uri, null, null) }
            }
        } finally { source.delete() }
    }

    @Test fun compatibleH264AndAacKeepTheirEncodedSamplesWhenMovedToMp4() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "remux-test-").toFile()
        var result: Uri? = null
        try {
            val clip = File(dir, "silent.mp4")
            InstrumentationRegistry.getInstrumentation().context.assets.open("gallery/motion.mp4").use { source ->
                clip.outputStream().use { source.copyTo(it) }
            }
            val movie = File(dir, "录影.mov")
            val recording = FFmpegKit.executeWithArguments(arrayOf("-y", "-i", clip.path,
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:v", "copy", "-c:a", "aac", movie.path))
            assertTrue(recording.output, ReturnCode.isSuccess(recording.returnCode))
            val input = Uri.fromFile(movie)
            val original = movie.readBytes()
            result = FileConverter(context).convert(SelectedFile(input, movie.name, movie.length()),
                ConversionSpec(ConversionKind.VIDEO)) { _, _ -> }.uri
            fun samples(uri: Uri): Map<String, Pair<Int, String>> {
                val extractor = android.media.MediaExtractor()
                try {
                    extractor.setDataSource(context, uri, null)
                    return (0 until extractor.trackCount).associate { index ->
                        val format = extractor.getTrackFormat(index)
                        val mime = format.getString(android.media.MediaFormat.KEY_MIME)!!
                        extractor.selectTrack(index)
                        extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
                        var count = 0
                        while (extractor.sampleTrackIndex == index) {
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            digest.update(buffer.array(), 0, size)
                            count++
                            extractor.advance()
                        }
                        extractor.unselectTrack(index)
                        assertTrue(count > 1)
                        mime to (count to android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP))
                    }
                } finally { extractor.release() }
            }
            val expected = samples(input)
            assertEquals(setOf("video/avc", "audio/mp4a-latm"), expected.keys)
            assertEquals(expected, samples(result))
            assertArrayEquals(original, movie.readBytes())
        } finally { result?.let { context.contentResolver.delete(it, null, null) }; dir.deleteRecursively() }
    }

    @Test fun nativeLibraryConvertsAviToMp4AndMp3() = runBlocking {
        val source = File.createTempFile("video-", ".avi", context.cacheDir)
        try {
            val generated = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=64x48:r=5", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=22050",
                "-t", "0.8", "-c:v", "mpeg4", "-c:a", "pcm_s16le", source.absolutePath))
            assertTrue(generated.output, ReturnCode.isSuccess(generated.returnCode))
            val original = source.readBytes()
            for (kind in listOf(ConversionKind.VIDEO, ConversionKind.AUDIO, ConversionKind.GIF)) {
                val result = FileConverter(context).convert(SelectedFile(Uri.fromFile(source), source.name, source.length()), ConversionSpec(kind)) { _, _ -> }
                try {
                    assertTrue(result.size > 0)
                    assertEquals(ConversionSpec(kind).outputFormat.mime, context.contentResolver.getType(result.uri))
                    assertArrayEquals(original, source.readBytes())
                    if (kind == ConversionKind.GIF) {
                        val drawable = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(context.contentResolver, result.uri))
                        assertTrue("GIF should contain animation", drawable is android.graphics.drawable.AnimatedImageDrawable)
                    }
                } finally { context.contentResolver.delete(result.uri, null, null) }
            }
        } finally { source.delete() }
    }
}
