package cn.shiying.local.conversion

import cn.shiying.local.documents.PdfMergeCore
import cn.shiying.local.documents.PrintPages
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfMergeTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun realPdfMergePreservesInputOrderPageDimensionsAndOriginalBytes() {
        val first = pdf("portrait.pdf", 300, 500)
        val second = pdf("landscape.pdf", 600, 200)
        val original = first.readBytes()
        val output = temp.newFile("merged.pdf")
        assertEquals(2, PdfMergeCore.merge(listOf(second, first), output))
        PDDocument.load(output).use { document ->
            assertEquals(2, document.numberOfPages)
            assertEquals(600f, document.getPage(0).mediaBox.width)
            assertEquals(200f, document.getPage(0).mediaBox.height)
            assertEquals(300f, document.getPage(1).mediaBox.width)
            assertEquals(500f, document.getPage(1).mediaBox.height)
        }
        assertArrayEquals(original, first.readBytes())
        val selected = temp.newFile("selected.pdf")
        output.inputStream().use { input -> selected.outputStream().use { target -> PdfMergeCore.writeSelectedPages(input, target, listOf(1)) } }
        PDDocument.load(selected).use { subset ->
            assertEquals(1, subset.numberOfPages)
            assertEquals(300f, subset.getPage(0).mediaBox.width)
        }
        System.getenv("SHIYING_PDF_QA_DIR")?.takeIf { it.isNotBlank() }?.let { path ->
            val directory = File(path).apply { mkdirs() }
            output.copyTo(File(directory, "merged.pdf"), overwrite = true)
            selected.copyTo(File(directory, "selected.pdf"), overwrite = true)
        }
    }
    @Test fun rejectsPageBudgetAndCanCancelBeforeWriting() {
        val first = pdf("one.pdf", 300, 500)
        val second = pdf("two.pdf", 300, 500)
        assertThrows(IllegalArgumentException::class.java) { PdfMergeCore.merge(listOf(first, second), temp.newFile("too-many.pdf"), 1) }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            PdfMergeCore.merge(listOf(first), temp.newFile("cancelled.pdf")) { throw java.util.concurrent.CancellationException() }
        }
    }
    @Test fun selectedPrintPagesAreClippedDeduplicatedAndOrdered() {
        assertEquals(listOf(0, 1, 2), PrintPages.indices(listOf(0 to Int.MAX_VALUE), 3))
        val selected = PrintPages.indices(listOf(4 to 8, 1 to 2, 2 to 4), 6)
        assertEquals(listOf(1, 2, 3, 4, 5), selected)
        assertEquals(listOf(1 to 5), PrintPages.ranges(selected))
        assertEquals(listOf(0 to 0, 2 to 3), PrintPages.ranges(listOf(0, 2, 3)))
        assertTrue(PrintPages.indices(listOf(9 to 10), 2).isEmpty())
    }
    @Test fun listMovesAreStableAndIgnoreOutOfBounds() {
        val files = listOf("first", "second", "third")
        assertEquals(listOf("second", "first", "third"), PrintPages.move(files, 0, 1))
        assertEquals(files, PrintPages.move(files, 0, -1))
        assertEquals(listOf("first", "third", "second"), PrintPages.move(files, 2, -1))
    }
    private fun pdf(name: String, width: Int, height: Int): File {
        // Small, valid vector PDF fixture; no Android graphics or font mocks.
        val label = name.substringBefore('.').uppercase(java.util.Locale.ROOT)
        val content = "0 0 1 rg 20 100 60 50 re f\n0 0 0 rg BT /F1 24 Tf 20 60 Td ($label) Tj ET\n"
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n${content}endstream", "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        val bytes = ByteArrayOutputStream()
        fun write(text: String) { bytes.write(text.toByteArray(Charsets.US_ASCII)) }
        write("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { index, value -> offsets += bytes.size(); write("${index + 1} 0 obj\n$value\nendobj\n") }
        val xref = bytes.size()
        write("xref\n0 6\n0000000000 65535 f \n")
        offsets.forEach { write(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", it)) }
        write("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return temp.newFile(name).apply { writeBytes(bytes.toByteArray()) }
    }
}
