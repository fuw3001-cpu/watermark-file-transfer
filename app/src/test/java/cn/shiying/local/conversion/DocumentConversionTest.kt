package cn.shiying.local.conversion

import cn.shiying.local.documents.LayoutDocx
import cn.shiying.local.documents.LayoutPage
import cn.shiying.local.documents.LayoutText
import cn.shiying.local.documents.PdfToWord
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentConversionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun layoutsContainEditableTextPageDimensionsAndBackgroundRelationships() {
        val background = temp.newFile("background.jpg").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())) }
        val output = temp.newFile("layout.docx")
        LayoutDocx.write(output, listOf(
            LayoutPage(595f, 842f, background, listOf(LayoutText("中文 & <test>", 36f, 48f, 200f, 16f, 12f))),
            LayoutPage(842f, 595f, background, listOf(LayoutText("Page 2", 50f, 70f, 100f, 18f, 14f, true))),
        ))
        ZipFile(output).use { zip ->
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val document = factory.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry("word/document.xml")))
            val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            assertEquals(2, document.getElementsByTagNameNS(word, "txbxContent").length)
            assertEquals("中文 & <test>", document.getElementsByTagNameNS(word, "t").item(0).textContent)
            assertEquals(2, document.getElementsByTagNameNS(word, "pgSz").length)
            assertTrue(zip.getEntry("word/media/page0.jpg") != null)
            assertTrue(zip.getEntry("word/media/page1.jpg") != null)
            val relations = zip.getInputStream(zip.getEntry("word/_rels/document.xml.rels")).bufferedReader().readText()
            assertTrue(relations.contains("media/page1.jpg"))
        }
    }
    @Test fun hocrKeepsChineseTogetherAndPreservesPageCoordinates() {
        val html = """<span class="ocr_line" title="bbox 20 40 220 80"><span class="ocrx_word">中文</span><span class="ocrx_word">识别</span><span class="ocrx_word">test</span></span>"""
        val line = PdfToWord.parseHocr(html, 2f).single()
        assertEquals("中文识别 test", line.text)
        assertEquals(10f, line.x)
        assertEquals(20f, line.y)
        assertEquals(100f, line.width)
    }
    @Test fun documentDirectionsDeclareCorrectOutputAndInputTypes() {
        assertEquals(OutputFormat.DOCX, ConversionSpec(ConversionKind.DOCUMENT, documentOperation = DocumentOperation.PDF_WORD).outputFormat)
        assertEquals("报告_转换_10.pdf", ConversionSpec(ConversionKind.DOCUMENT).outputName("报告.docx", 10))
        assertEquals(setOf("pdf"), DocumentOperation.PDF_WORD.extensions)
        assertTrue("xlsx" in DocumentOperation.EXCEL_PDF.extensions)
    }
}
