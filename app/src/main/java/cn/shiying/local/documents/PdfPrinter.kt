package cn.shiying.local.documents

import android.content.Context
import android.net.Uri
import android.os.*
import android.print.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object PdfPrinter {
    /** Opens Android's print panel; the user chooses the printer, pages, copies and final action. */
    fun open(context: Context, uri: Uri, name: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            ?: error("设备没有系统打印服务，请检查系统打印设置")
        PDFBoxResourceLoader.init(context)
        manager.print(name, Adapter(context, uri, name), null)
    }

    private class Adapter(private val context: Context, private val uri: Uri, private val name: String) : PrintDocumentAdapter() {
        private val executor = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())
        private val finished = AtomicBoolean(false)
        private var pageCount = 0

        override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, signal: CancellationSignal,
            callback: LayoutResultCallback, extras: Bundle?) {
            executor.execute {
                try {
                    signal.throwIfCanceled()
                    if (pageCount == 0) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { pageCount = it.numberOfPages }
                        } ?: error("无法读取合并 PDF")
                    }
                    require(pageCount > 0) { "PDF 没有页面" }
                    val info = PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pageCount).build()
                    main.post {
                        if (!finished.get()) {
                            if (signal.isCanceled) callback.onLayoutCancelled() else callback.onLayoutFinished(info, oldAttributes != newAttributes)
                        }
                    }
                } catch (_: OperationCanceledException) {
                    main.post { if (!finished.get()) callback.onLayoutCancelled() }
                } catch (_: Exception) {
                    main.post { if (!finished.get()) callback.onLayoutFailed("无法读取 PDF，请重新生成合并文件") }
                }
            }
        }

        override fun onWrite(ranges: Array<out PageRange>, destination: ParcelFileDescriptor, signal: CancellationSignal, callback: WriteResultCallback) {
            executor.execute {
                try {
                    signal.throwIfCanceled()
                    val selected = PrintPages.indices(ranges.map { it.start to it.end }, pageCount)
                    require(selected.isNotEmpty()) { "没有选中可打印的页面" }
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destination.fileDescriptor).use { raw ->
                            val output = object : java.io.FilterOutputStream(raw) {
                                override fun write(value: Int) { signal.throwIfCanceled(); out.write(value) }
                                override fun write(bytes: ByteArray, offset: Int, length: Int) { signal.throwIfCanceled(); out.write(bytes, offset, length) }
                            }
                            if (selected.size == pageCount) {
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    signal.throwIfCanceled()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            } else {
                                PdfMergeCore.writeSelectedPages(input, output, selected) { signal.throwIfCanceled() }
                            }
                            output.flush()
                        }
                    } ?: error("无法读取合并 PDF")
                    signal.throwIfCanceled()
                    main.post {
                        if (!finished.get()) {
                            if (signal.isCanceled) callback.onWriteCancelled()
                            else callback.onWriteFinished(PrintPages.ranges(selected).map { PageRange(it.first, it.second) }.toTypedArray())
                        }
                    }
                } catch (_: OperationCanceledException) {
                    main.post { if (!finished.get()) callback.onWriteCancelled() }
                } catch (_: Exception) {
                    main.post { if (!finished.get()) callback.onWriteFailed("打印文件生成失败，请重试或减少页数") }
                }
            }
        }
        override fun onFinish() { finished.set(true); executor.shutdownNow() }
    }
}
