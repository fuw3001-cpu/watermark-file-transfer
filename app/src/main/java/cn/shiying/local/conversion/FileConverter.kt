package cn.shiying.local.conversion

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import cn.shiying.local.documents.OfficeEngine
import cn.shiying.local.documents.PdfToWord

data class SelectedFile(val uri: Uri, val name: String, val size: Long?)
data class ConvertedFile(val uri: Uri, val name: String, val mime: String, val size: Long, val location: String)

class FileConverter(private val context: Context) {
    fun describe(uri: Uri): SelectedFile {
        var name = "本地文件"
        var size: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
        require(size == null || size!! <= NativeMediaConverter.MAX_BYTES) { "请选择不超过 512 MB 的文件" }
        return SelectedFile(uri, name, size)
    }

    suspend fun convert(source: SelectedFile, spec: ConversionSpec, status: (String, Float?) -> Unit): ConvertedFile = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "conversion-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val extension = if (spec.kind == ConversionKind.DOCUMENT) {
                source.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT).also {
                    require(it in spec.documentOperation.extensions) { "此转换请选择 ${spec.documentOperation.extensions.joinToString(" / ")} 文件" }
                    require(source.size == null || source.size <= 64L * 1024 * 1024) { "文档转换目前支持不超过 64 MB 的文件" }
                }
            } else "bin"
            val input = File(work, "input.$extension")
            val output = File(work, "output.${spec.outputFormat.extension}")
            status("正在读取文件…", null)
            context.contentResolver.openInputStream(source.uri)?.use { inputStream ->
                input.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val count = inputStream.read(buffer)
                        if (count < 0) break
                        read += count
                        require(read <= NativeMediaConverter.MAX_BYTES) { "文件超过 512 MB，已停止读取" }
                        if (spec.kind == ConversionKind.DOCUMENT) require(read <= 64L * 1024 * 1024) { "文档超过 64 MB，已停止读取" }
                        out.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("无法读取文件，请重新选择")
            require(input.length() > 0) { "选择的文件为空" }
            kotlin.coroutines.coroutineContext.ensureActive()
            status("正在转换…", null)
            when (spec.kind) {
                ConversionKind.IMAGE -> convertImage(input, output, spec)
                ConversionKind.MEME -> error("请使用表情包工作台编辑后保存")
                ConversionKind.DOCUMENT -> {
                    if (spec.documentOperation == DocumentOperation.PDF_WORD) PdfToWord(context).convert(input, output, status)
                    else {
                        status("正在启动本地 Office 引擎（首次使用需要解包）…", null)
                        OfficeEngine.convert(context, input, output, spec.outputFormat.extension)
                    }
                    require(output.length() in 1..NativeMediaConverter.MAX_BYTES) { "文档转换结果为空或过大" }
                    when (spec.outputFormat) {
                        OutputFormat.PDF -> require(output.inputStream().use { stream ->
                            val header = ByteArray(5); java.io.DataInputStream(stream).readFully(header); header.toString(Charsets.US_ASCII)
                        } == "%PDF-") { "PDF 输出校验失败" }
                        OutputFormat.DOCX, OutputFormat.XLSX -> java.util.zip.ZipFile(output).use { zip ->
                            require(zip.getEntry(if (spec.outputFormat == OutputFormat.DOCX) "word/document.xml" else "xl/workbook.xml") != null) { "Office 输出校验失败" }
                        }
                        else -> Unit
                    }
                }
                else -> NativeMediaConverter().convert(spec, input, output) { status("正在转换…", it) }
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            status("正在保存结果…", null)
            publish(output, spec.outputName(source.name, System.currentTimeMillis()), spec.outputFormat)
        } finally {
            // Only this operation's UUID-named private cache directory is removed.
            work.deleteRecursively()
        }
    }

    private fun convertImage(input: File, output: File, spec: ConversionSpec) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(input)) { decoder, info, _ ->
            require(info.size.width.toLong() * info.size.height <= 16_000_000) { "图片超过 1600 万像素，请先缩小图片" }
            require(info.mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) { "当前图片互转支持 JPG、PNG、WebP 和 GIF" }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        var flattened: Bitmap? = null
        try {
            // Android's decoder applies EXIF orientation. JPEG cannot retain alpha: flatten onto white.
            val toEncode = if (spec.outputFormat == OutputFormat.JPG && bitmap.hasAlpha()) {
                Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    Canvas(it).drawBitmap(bitmap, 0f, 0f, null)
                    flattened = it
                }
            } else bitmap
            val format = when (spec.outputFormat) {
                OutputFormat.JPG -> Bitmap.CompressFormat.JPEG
                OutputFormat.PNG -> Bitmap.CompressFormat.PNG
                OutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else legacyWebp()
                else -> error("不支持的图片输出格式")
            }
            output.outputStream().use { require(toEncode.compress(format, spec.quality.coerceIn(60, 100), it)) { "图片编码失败" } }
            require(output.length() > 0) { "图片转换结果为空" }
        } finally {
            flattened?.recycle()
            bitmap.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyWebp() = Bitmap.CompressFormat.WEBP

    internal suspend fun publish(file: File, name: String, format: OutputFormat): ConvertedFile {
        val (collection, directory) = when (format) {
            OutputFormat.MP4 -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            OutputFormat.MP3 -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
            OutputFormat.PDF, OutputFormat.DOCX, OutputFormat.XLSX, OutputFormat.CSV -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
        }
        val location = "$directory/Shiying"
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, location)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("无法创建结果文件，请检查存储空间")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("无法保存转换结果")
            kotlin.coroutines.coroutineContext.ensureActive()
            require(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) { "无法发布结果文件" }
            return ConvertedFile(uri, name, format.mime, file.length(), location)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
