package cn.shiying.local.documents

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

object PdfMergeCore {
    fun writeSelectedPages(input: java.io.InputStream, output: java.io.OutputStream, selected: List<Int>, checkCancelled: () -> Unit = {}) {
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { source ->
            require(selected.isNotEmpty() && selected.all { it in 0 until source.numberOfPages }) { "打印页码无效" }
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { subset ->
                selected.distinct().sorted().forEach { index ->
                    checkCancelled()
                    val page = source.getPage(index)
                    subset.importPage(page).resources = page.resources
                }
                checkCancelled()
                subset.save(output)
            }
        }
    }
    /** Appends full PDF pages in input order without rasterising or resizing them. */
    fun merge(inputs: List<File>, output: File, maxPages: Int = 500, checkCancelled: () -> Unit = {}): Int {
        require(inputs.isNotEmpty()) { "请先添加要合并的文档" }
        var count = 0
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { destination ->
            val merger = PDFMergerUtility()
            inputs.forEach { file ->
                checkCancelled()
                PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { source ->
                    require(!source.isEncrypted) { "暂不支持加密 PDF，请先解除密码保护" }
                    require(source.numberOfPages > 0) { "PDF 没有可合并的页面" }
                    count += source.numberOfPages
                    require(count <= maxPages) { "合并后超过 $maxPages 页，请减少文件或拆分批次" }
                    merger.appendDocument(destination, source)
                }
            }
            checkCancelled()
            destination.documentInformation.title = "合并文档"
            destination.save(output)
        }
        return count
    }
}

object PrintPages {
    fun indices(ranges: List<Pair<Int, Int>>, pageCount: Int): List<Int> {
        require(pageCount > 0)
        return ranges.flatMap { (start, end) ->
            if (end < 0 || start >= pageCount || end < start) emptyList()
            else (start.coerceAtLeast(0)..end.coerceAtMost(pageCount - 1)).toList()
        }.distinct().sorted()
    }
    fun <T> move(items: List<T>, index: Int, delta: Int): List<T> {
        val target = index + delta
        if (index !in items.indices || target !in items.indices) return items
        return items.toMutableList().apply { add(target, removeAt(index)) }
    }
    fun ranges(indices: List<Int>): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        for (index in indices.distinct().sorted()) {
            val last = result.lastOrNull()
            if (last != null && index == last.second + 1) result[result.lastIndex] = last.first to index
            else result += index to index
        }
        return result
    }
}
