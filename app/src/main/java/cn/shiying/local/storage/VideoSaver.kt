package cn.shiying.local.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import cn.shiying.local.network.Http
import cn.shiying.local.network.await
import cn.shiying.local.parser.MediaInfo
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

class VideoSaver(private val context: Context) {
    suspend fun save(info: MediaInfo, progress: (Long, Long) -> Unit): Uri = withContext(Dispatchers.IO) {
        require(info.videoUrl.toHttpUrlOrNull()?.isHttps == true) { "视频地址无效，请重新解析" }
        val request = Request.Builder().url(info.videoUrl)
            .header("User-Agent", info.userAgent).header("Referer", info.platform.referer).build()
        Http.mediaClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败（${response.code}），地址可能已过期，请重新解析")
            val body = response.body ?: throw IOException("平台返回了空文件")
            val maxSize = 512L * 1024 * 1024
            val total = body.contentLength()
            if (total > maxSize) throw IOException("当前版本最多保存 512 MB 的视频")
            // Reject verification HTML/JSON and incomplete MP4 files before creating a gallery entry.
            val source = body.source()
            if (!source.request(12) || source.buffer.clone().readByteArray(12)
                    .copyOfRange(4, 8).toString(Charsets.US_ASCII) != "ftyp") {
                throw IOException("平台没有返回 MP4 视频，可能需要验证或链接已过期")
            }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "拾影_${info.id}_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Shiying")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法创建视频文件，请检查存储空间")
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var reportedAt = 0L
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val count = source.read(buffer)
                        if (count == -1) break
                        kotlin.coroutines.coroutineContext.ensureActive()
                        received += count
                        if (received > maxSize) throw IOException("视频超过 512 MB，已停止保存")
                        output.write(buffer, 0, count)
                        if (received - reportedAt >= 256 * 1024) {
                            progress(received, total)
                            reportedAt = received
                        }
                    }
                    if (total >= 0 && received != total) throw IOException("视频下载不完整，请重新保存")
                    progress(received, total)
                } ?: throw IOException("无法写入相册")
                kotlin.coroutines.coroutineContext.ensureActive()
                if (resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) != 1) {
                    throw IOException("视频未能发布到相册，请重新保存")
                }
                uri
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }
    }
}
