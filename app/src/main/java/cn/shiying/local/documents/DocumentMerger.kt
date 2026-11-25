package cn.shiying.local.documents

import android.content.Context
import cn.shiying.local.conversion.ConvertedFile
import cn.shiying.local.conversion.FileConverter
import cn.shiying.local.conversion.OutputFormat
import cn.shiying.local.conversion.SelectedFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class MergedDocument(val file: ConvertedFile, val pages: Int, val documents: Int)

class DocumentMerger(private val context: Context) {
    suspend fun merge(sources: List<SelectedFile>, name: String, status: (String, Float?) -> Unit): MergedDocument = withContext(Dispatchers.IO) {
        require(sources.size in 1..20) { "一次可合并 1–20 个文档" }
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "merge-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        var aggregate = 0L
        try {
            val pdfs = sources.mapIndexed { index, source ->
                kotlin.coroutines.coroutineContext.ensureActive()
                val extension = source.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                require(extension in supported) { "不支持 ${source.name}；请选择 PDF、Word 或 Excel 文档" }
                val input = File(work, "input-$index.$extension")
                status("正在读取 ${index + 1}/${sources.size}：${source.name}", index.toFloat() / sources.size * 0.75f)
                context.contentResolver.openInputStream(source.uri)?.use { stream ->
                    input.outputStream().use { output ->
                        var size = 0L
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            size += count; aggregate += count
                            require(size <= 64L * 1024 * 1024) { "${source.name} 超过 64 MB" }
                            require(aggregate <= 256L * 1024 * 1024) { "输入文档总计超过 256 MB，请减少文件" }
                            output.write(buffer, 0, count)
                        }
                        require(size > 0) { "${source.name} 是空文件" }
                    }
                } ?: error("无法读取 ${source.name}，请重新添加文件")
                if (extension == "pdf") input else {
                    status("正在转成 PDF ${index + 1}/${sources.size}：${source.name}", null)
                    val converted = File(work, "converted-$index.pdf")
                    OfficeEngine.convert(context, input, converted, "pdf")
                    require(converted.length() <= 512L * 1024 * 1024) { "转换结果过大，请减少文档页数" }
                    converted
                }
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            status("正在按顺序合并页面…", 0.8f)
            val result = File(work, "merged.pdf")
            val jobContext = kotlin.coroutines.coroutineContext
            val pages = PdfMergeCore.merge(pdfs, result) { jobContext.ensureActive() }
            require(result.length() in 1..(512L * 1024 * 1024)) { "合并结果为空或超过 512 MB" }
            jobContext.ensureActive()
            status("正在保存合并文件…", 0.95f)
            val safeName = name.trim().removeSuffix(".pdf").replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").take(60).ifBlank { "合并文档" }
            val published = FileConverter(context).publish(result, "${safeName}_${System.currentTimeMillis()}.pdf", OutputFormat.PDF)
            MergedDocument(published, pages, sources.size)
        } finally { work.deleteRecursively() }
    }
    companion object { val supported = setOf("pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods") }
}
