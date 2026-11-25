package cn.shiying.local.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup
import kotlin.math.sqrt

class PdfToWord(private val context: Context) {
    suspend fun convert(input: File, output: File, status: (String, Float?) -> Unit) {
        PDFBoxResourceLoader.init(context)
        val pages = mutableListOf<LayoutPage>()
        var ocr: TessBaseAPI? = null
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                require(!document.isEncrypted) { "暂不支持加密 PDF，请先用原密码解除保护" }
                require(document.numberOfPages in 1..20) { "版式识别目前支持 1–20 页 PDF，请先拆分长文档" }
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        for (index in 0 until renderer.pageCount) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            status("正在分析第 ${index + 1}/${renderer.pageCount} 页…", index.toFloat() / renderer.pageCount)
                            renderer.openPage(index).use { page ->
                                val scale = minOf(2f, sqrt(4_000_000f / (page.width.toFloat() * page.height)))
                                val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    val extracted = if (document.getPage(index).rotation % 360 == 0) extract(document, index) else emptyList()
                                    val lines = if (extracted.sumOf { it.text.length } >= 20) extracted else {
                                        status("正在离线识别扫描页 ${index + 1}/${renderer.pageCount}…", index.toFloat() / renderer.pageCount)
                                        val engine = ocr ?: createOcr().also { ocr = it }
                                        engine.setImage(bitmap)
                                        parseHocr(engine.getHOCRText(index), scale)
                                    }
                                    kotlin.coroutines.coroutineContext.ensureActive()
                                    val canvas = Canvas(bitmap)
                                    val paint = Paint()
                                    // Preserve illustrations/table rules as an image, replace text regions with editable text.
                                    lines.forEach { line ->
                                        val left = (line.x * scale).coerceIn(0f, bitmap.width - 1f)
                                        val top = (line.y * scale).coerceIn(0f, bitmap.height - 1f)
                                        val right = ((line.x + line.width) * scale).coerceIn(left + 0.1f, bitmap.width.toFloat())
                                        val bottom = ((line.y + line.height) * scale).coerceIn(top + 0.1f, bitmap.height.toFloat())
                                        val samples = listOf(left - 2 to top - 2, right + 2 to top - 2, left - 2 to bottom + 2, right + 2 to bottom + 2)
                                            .map { (x, y) -> bitmap.getPixel(x.toInt().coerceIn(0, bitmap.width - 1), y.toInt().coerceIn(0, bitmap.height - 1)) }
                                        paint.color = samples.groupBy { it }.maxByOrNull { it.value.size }?.key ?: Color.WHITE
                                        canvas.drawRect(left, top, right, bottom, paint)
                                    }
                                    val background = File(output.parentFile, "page-$index.jpg")
                                    background.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
                                    pages += LayoutPage(page.width.toFloat(), page.height.toFloat(), background, lines)
                                } finally { bitmap.recycle() }
                            }
                        }
                    }
                }
            }
            require(pages.any { it.text.isNotEmpty() }) { "没有识别到可编辑文字，请使用清晰、方向正确的中英文扫描件" }
            kotlin.coroutines.coroutineContext.ensureActive()
            status("正在重建 Word 版式…", 0.95f)
            LayoutDocx.write(output, pages)
        } finally { ocr?.recycle() }
    }

    private fun createOcr(): TessBaseAPI = LocalOcr.create(context)

    private fun extract(document: PDDocument, pageIndex: Int): List<LayoutText> {
        val lines = mutableListOf<LayoutText>()
        val stripper = object : PDFTextStripper() {
            val colors = java.util.IdentityHashMap<TextPosition, String>()
            override fun processTextPosition(position: TextPosition) {
                val rgb = runCatching { graphicsState.nonStrokingColor.toRGB() }.getOrDefault(0)
                colors[position] = String.format(java.util.Locale.ROOT, "%06X", rgb and 0xFFFFFF)
                super.processTextPosition(position)
            }
            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                if (text.isBlank() || positions.isEmpty() || positions.any { it.dir != 0f }) return
                val first = positions.first()
                val x = positions.minOf { it.xDirAdj }
                val size = positions.map { it.fontSizeInPt }.average().toFloat().coerceIn(4f, 120f)
                val top = positions.minOf { it.yDirAdj - it.heightDir }
                val width = positions.maxOf { it.xDirAdj + it.widthDirAdj } - x
                val height = positions.maxOf { it.yDirAdj } - top + 1f
                lines += LayoutText(text, x, top, width, height, size,
                    first.font.name?.contains("bold", ignoreCase = true) == true,
                    colors[first] ?: "000000")
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.getText(document)
        return lines
    }

    companion object {
        fun parseHocr(html: String, scale: Float): List<LayoutText> = Jsoup.parse(html).select(".ocr_line, .ocrx_line").mapNotNull { line ->
            val box = Regex("bbox (\\d+) (\\d+) (\\d+) (\\d+)").find(line.attr("title")) ?: return@mapNotNull null
            val (x1, y1, x2, y2) = box.groupValues.drop(1).map { it.toFloat() / scale }
            val words = line.select(".ocrx_word")
            val text = if (words.isEmpty()) line.text() else buildString {
                words.forEach { word ->
                    val value = word.text()
                    if (isNotEmpty() && value.isNotEmpty() && !(last().code in 0x2E80..0x9FFF && value.first().code in 0x2E80..0x9FFF)) append(' ')
                    append(value)
                }
            }
            if (text.isBlank()) null else LayoutText(text, x1, y1, x2 - x1, y2 - y1, ((y2 - y1) * 0.9f).coerceIn(4f, 72f))
        }
    }
}
