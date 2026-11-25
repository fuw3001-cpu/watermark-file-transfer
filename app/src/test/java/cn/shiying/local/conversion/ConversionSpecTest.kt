package cn.shiying.local.conversion

import org.junit.Assert.*
import org.junit.Test

class ConversionSpecTest {
    @Test fun outputFilenameKeepsUnicodeButRemovesPathAndControlCharacters() {
        val spec = ConversionSpec(imageFormat = OutputFormat.PNG)
        assertEquals("相册_照片_1_转换_123.png", spec.outputName("相册/照片:1.jpg", 123))
        assertEquals("文件_转换_123.png", spec.outputName("", 123))
    }
    @Test fun filenameAndMetadataFollowSelectedKind() {
        assertEquals("audio/mpeg", ConversionSpec(ConversionKind.AUDIO).outputFormat.mime)
        assertEquals("movie_转换_1.mp4", ConversionSpec(ConversionKind.VIDEO).outputName("movie.webm", 1))
    }
    @Test fun pathsWithSpacesAndShellSymbolsRemainSingleArguments() {
        val input = "/storage/Alice's clips/视频; name.mkv"
        val output = "/cache/out file.mp4"
        val arguments = ConversionCommands.encode(ConversionSpec(ConversionKind.VIDEO), input, output).toList()
        assertEquals(input, arguments[arguments.indexOf("-i") + 1])
        assertEquals(output, arguments.last())
        assertEquals("file,pipe", arguments[arguments.indexOf("-protocol_whitelist") + 1])
        assertFalse(arguments.contains("libx264"))
    }
    @Test fun gifRejectsOutOfBudgetTimingAndUsesImageMime() {
        assertEquals("image/gif", ConversionSpec(ConversionKind.GIF).outputFormat.mime)
        for (spec in listOf(ConversionSpec(ConversionKind.GIF, gifStartSeconds = -1),
            ConversionSpec(ConversionKind.GIF, gifDurationSeconds = 11), ConversionSpec(ConversionKind.GIF, gifFps = 60))) {
            assertThrows(IllegalArgumentException::class.java) { ConversionCommands.encode(spec, "in", "out") }
        }
    }
}
