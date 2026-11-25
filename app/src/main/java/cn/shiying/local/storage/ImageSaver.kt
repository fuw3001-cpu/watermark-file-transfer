package cn.shiying.local.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import cn.shiying.local.network.Http
import cn.shiying.local.parser.MediaImage
import cn.shiying.local.parser.MediaInfo
import java.io.IOException
import java.io.File
import android.graphics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import android.media.MediaMetadataRetriever
import cn.shiying.local.conversion.*

class ImageSaver(private val context: Context, private val client: OkHttpClient = Http.mediaClient) {
    suspend fun saveSelected(info: MediaInfo, indices: List<Int>, progress: (Long, Long) -> Unit): List<Uri> = withContext(Dispatchers.IO) {
        require(info.isGallery && info.images.size <= 100) { "图集信息无效，请重新解析" }
        require(indices.isNotEmpty() && indices.distinct().size == indices.size && indices.all { it in info.images.indices }) { "请选择有效的图片" }
        val created = mutableListOf<Uri>()
        val resolver = context.contentResolver
        val downloader = GalleryDownload(info, client, progress)
        val stamp = System.currentTimeMillis()
        val work=java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(),"gallery-").toFile()
        try {
            indices.forEach { index ->
                val image = info.images[index]
                kotlin.coroutines.coroutineContext.ensureActive()
                val original = File(work, "image")
                val (prepared, savedEncoding) = if (image.isLive) {
                    val movie = File(work, "motion.mp4")
                    downloader.movie(image, movie)
                    val cover = File(work, "cover.jpg")
                    val live = File(work, "live.jpg")
                    val encoding = try { downloader.image(image, original) } catch (_: IOException) { null }
                    val jobContext = kotlin.coroutines.coroutineContext
                    fun combine(jpeg: File) = MotionPhotoWriter.write(jpeg, movie, live) { jobContext.ensureActive() }
                    val combined = if (encoding == null) false else try {
                        if (encoding == ImageEncoding.JPEG) combine(original) else {
                            writeImageCover(original, cover)
                            combine(cover)
                        }
                        true
                    } catch (_: IOException) { false } catch (_: IllegalArgumentException) { false }
                    if (!combined) {
                        jobContext.ensureActive()
                        val retriever = MediaMetadataRetriever()
                        val frame = try {
                            retriever.setDataSource(movie.absolutePath)
                            retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1600, 1600)
                        } finally { retriever.release() }
                        if (frame == null) throw IOException("无法生成实况封面，可打开大图另存视频或 GIF")
                        try { writeJpeg(frame, cover) } finally { frame.recycle() }
                        combine(cover)
                    }
                    live to ImageEncoding.JPEG
                } else original to downloader.image(image, original)
                run {
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "拾影_${info.id}_${stamp}_${index + 1}${if(image.isLive) "_实况" else ""}.${savedEncoding.extension}")
                        put(MediaStore.Images.Media.MIME_TYPE, savedEncoding.mime)
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Shiying")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }) ?: throw IOException("无法创建图片文件，请检查存储空间")
                    created += uri
                    resolver.openOutputStream(uri)?.use { output ->
                        // Copy the validated original bytes or the complete Motion Photo, without frame extraction.
                        val sourceFile=prepared
                        sourceFile.inputStream().use { source ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            kotlin.coroutines.coroutineContext.ensureActive()
                            output.write(buffer, 0, count)
                        }
                        }
                    } ?: throw IOException("无法写入相册")
                }
                work.listFiles()?.forEach { it.delete() }
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            for (uri in created) {
                kotlin.coroutines.coroutineContext.ensureActive()
                require(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) { "图片无法发布到相册" }
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            created.toList()
        } catch (e: Throwable) {
            created.forEach { runCatching { resolver.delete(it, null, null) } }
            throw e
        } finally { work.deleteRecursively() }
    }

    suspend fun saveLive(info: MediaInfo, index: Int, format: OutputFormat, progress: (Long, Long) -> Unit): Uri = withContext(Dispatchers.IO) {
        require(format == OutputFormat.MP4 || format == OutputFormat.GIF)
        val image = info.images[index]
        require(image.isLive)
        val work = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "gallery-live-").toFile()
        try {
            val movie = File(work, "motion.mp4")
            GalleryDownload(info, client, progress).movie(image, movie)
            val output = if (format == OutputFormat.MP4) movie else File(work, "motion.gif").also { gif ->
                NativeMediaConverter().convert(ConversionSpec(kind = ConversionKind.GIF, gifDurationSeconds = 10), movie, gif) {}
                require(GalleryDownload.isAnimated(gif, ImageEncoding.GIF)) { "此视频未能生成多帧 GIF，可保存原视频" }
            }
            val name = "拾影_${info.id}_${System.currentTimeMillis()}_${index + 1}.${format.extension}"
            FileConverter(context).publish(output, name, format).uri
        } finally { work.deleteRecursively() }
    }

    private fun writeJpeg(bitmap: Bitmap, target: File) {
        target.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)) throw IOException("无法生成实况封面") }
    }

    private fun writeImageCover(original: File, target: File) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(original)) { decoder, meta, _ ->
            require(meta.size.width.toLong() * meta.size.height <= 16_000_000) { "实况封面超过 1600 万像素" }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            if (!bitmap.hasAlpha()) writeJpeg(bitmap, target) else {
                val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                    writeJpeg(flat, target)
                } finally { flat.recycle() }
            }
        } finally { bitmap.recycle() }
    }
}
