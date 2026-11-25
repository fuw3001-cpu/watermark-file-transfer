package cn.shiying.local.conversion

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shiying.local.documents.*
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun scannedPdfGetsEditableEnglishAndChineseText() = runBlocking {
        val dir = File(context.cacheDir, "doc-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val input = File(dir, "scan.pdf")
            val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            Canvas(bitmap).apply {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 60f }
                drawText("OFFLINE SCAN TEST", 100f, 250f, paint)
                drawText("中文识别测试", 100f, 450f, paint)
            }
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
                page.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, 595f, 842f), null)
                pdf.finishPage(page)
                input.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            bitmap.recycle()
            val output = File(dir, "scan.docx")
            PdfToWord(context).convert(input, output) { _, _ -> }
            ZipFile(output).use { zip ->
                val xml = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().readText()
                assertTrue(xml, xml.contains("OFFLINE", true))
                assertTrue(xml, Regex("[\\u4e00-\\u9fff]").containsMatchIn(xml))
                assertTrue(xml.contains("w:txbxContent"))
                assertTrue(zip.getEntry("word/media/page0.jpg") != null)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun arm64OfficeEngineRendersWordAndPdfRoundTripsEditableText() = runBlocking {
        assumeTrue(Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a")
        val dir = File(context.cacheDir, "office-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val background = File(dir, "white.jpg")
            val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            background.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            val input = File(dir, "input.docx")
            LayoutDocx.write(input, listOf(LayoutPage(595f, 842f, background,
                listOf(LayoutText("OFFICE TEST FOR LOCAL CONVERSION", 36f, 48f, 440f, 24f, 16f)))))
            val pdf = File(dir, "result.pdf")
            OfficeEngine.convert(context, input, pdf, "pdf")
            assertEquals("%PDF-", pdf.inputStream().use { stream -> val header = ByteArray(5); java.io.DataInputStream(stream).readFully(header); String(header) })
            val output = File(dir, "restored.docx")
            PdfToWord(context).convert(pdf, output) { _, _ -> }
            ZipFile(output).use { zip ->
                val xml = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().readText()
                assertTrue(xml, xml.contains("OFFICE"))
                assertTrue(xml.contains("w:txbxContent"))
            }
        } finally { dir.deleteRecursively() }
    }
}
