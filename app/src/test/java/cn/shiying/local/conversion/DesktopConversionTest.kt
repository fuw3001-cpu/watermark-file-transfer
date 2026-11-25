package cn.shiying.local.conversion

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests real conversion commands with a locally installed FFmpeg, not the Android JNI runtime. */
class DesktopConversionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realAviConvertsToMp4AndMp3() {
        val binary = System.getenv("SHIYING_FFMPEG").orEmpty()
        assumeTrue("Set SHIYING_FFMPEG for offline codec smoke tests", binary.isNotBlank())
        val probe = File(File(binary).parentFile, if (binary.endsWith(".exe")) "ffprobe.exe" else "ffprobe").absolutePath
        val input = File(temporary.root, "a user's video.avi")
        run(binary, listOf("-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=322x242:rate=12",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=22050", "-t", "1.2", "-c:v", "mpeg4", "-c:a", "pcm_s16le", input.absolutePath))
        val original = input.readBytes()
        for (kind in listOf(ConversionKind.VIDEO, ConversionKind.AUDIO)) {
            val spec = ConversionSpec(kind)
            val output = File(temporary.root, "转换结果.${spec.outputFormat.extension}")
            run(binary, ConversionCommands.encode(spec, input.absolutePath, output.absolutePath).toList())
            assertTrue(output.length() > 0)
            val info = JSONObject(run(probe, ConversionCommands.probe(output.absolutePath).toList()))
            val streams = info.getJSONArray("streams")
            val codecs = (0 until streams.length()).map { streams.getJSONObject(it).getString("codec_name") }
            if (kind == ConversionKind.VIDEO) {
                assertTrue(codecs.containsAll(listOf("mpeg4", "aac")))
                assertTrue(info.getJSONObject("format").getString("format_name").contains("mp4"))
            } else {
                assertEquals(listOf("mp3"), codecs)
                assertEquals("mp3", info.getJSONObject("format").getString("format_name"))
            }
            run(binary, listOf("-v", "error", "-i", output.absolutePath, "-f", "null", "-"))
        }
        assertArrayEquals("Original file must stay unchanged", original, input.readBytes())
    }

    @Test fun videoClipProducesAnimatedSilentGifAndGifReturnsToMp4() {
        val binary = System.getenv("SHIYING_FFMPEG").orEmpty()
        assumeTrue("Set SHIYING_FFMPEG for GIF codec tests", binary.isNotBlank())
        val probe = File(File(binary).parentFile, if (binary.endsWith(".exe")) "ffprobe.exe" else "ffprobe").absolutePath
        val input = File(temporary.root, "clip with spaces.avi")
        run(binary, listOf("-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=642x242:rate=12",
            "-t", "3", "-c:v", "mpeg4", input.absolutePath))
        val original = input.readBytes()
        val gif = File(temporary.root, "表情动图.gif")
        val spec = ConversionSpec(ConversionKind.GIF, gifStartSeconds = 1, gifDurationSeconds = 1, gifFps = 10)
        run(binary, ConversionCommands.encode(spec, input.absolutePath, gif.absolutePath).toList())
        val info = JSONObject(run(probe, ConversionCommands.probe(gif.absolutePath).toList()))
        assertEquals("gif", info.getJSONObject("format").getString("format_name"))
        val streams = info.getJSONArray("streams")
        assertEquals(1, streams.length())
        val stream = streams.getJSONObject(0)
        assertTrue(stream.getInt("width") <= 480 && stream.getInt("height") <= 480)
        assertTrue(stream.getInt("nb_frames") >= 8)
        assertTrue(info.getJSONObject("format").getString("duration").toDouble() in 0.8..1.2)
        val back = File(temporary.root, "gif-to-video.mp4")
        run(binary, ConversionCommands.encode(ConversionSpec(ConversionKind.VIDEO), gif.absolutePath, back.absolutePath).toList())
        run(binary, listOf("-v", "error", "-i", back.absolutePath, "-f", "null", "-"))
        assertArrayEquals(original, input.readBytes())
        System.getenv("SHIYING_GIF_QA_DIR")?.takeIf { it.isNotBlank() }?.let { path ->
            File(path).mkdirs()
            gif.copyTo(File(path, "clip.gif"), overwrite = true)
            back.copyTo(File(path, "gif-to-video.mp4"), overwrite = true)
        }
    }

    private fun run(binary: String, args: List<String>): String {
        val log = temporary.newFile()
        val process = ProcessBuilder(listOf(binary) + args).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("Conversion command timed out")
        }
        val output = log.readText()
        assertEquals(output, 0, process.exitValue())
        return output
    }
}
