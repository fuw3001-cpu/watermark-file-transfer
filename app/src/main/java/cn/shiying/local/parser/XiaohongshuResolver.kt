package cn.shiying.local.parser

import cn.shiying.local.network.Http
import cn.shiying.local.network.await
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.OkHttpClient

class XiaohongshuResolver(private val client: OkHttpClient = Http.client) {
    suspend fun resolve(text: String): MediaInfo = withContext(Dispatchers.IO) {
        val original = ShareLinks.extract(text)
        require(ShareLinks.isXiaohongshu(original)) { "请粘贴小红书分享链接" }
        var lastError: Exception = ParseException("小红书暂时无法解析")
        for (userAgent in listOf(Http.PC_USER_AGENT, Http.USER_AGENT)) {
            try {
                var url = original
                for (attempt in 0..6) {
                    require(ShareLinks.isXiaohongshu(url)) { "小红书链接跳转到了不支持的域名" }
                    val request = Request.Builder().url(url).header("User-Agent", userAgent)
                        .header("Referer", MediaPlatform.XIAOHONGSHU.referer)
                        .header("Accept-Language", "zh-CN,zh;q=0.9").build()
                    client.newCall(request).await().use { response ->
                        if (response.code in listOf(301, 302, 303, 307, 308)) {
                            url = response.header("Location")?.let(url::resolve) ?: throw ParseException("小红书链接跳转异常")
                        } else {
                            if (!response.isSuccessful) throw ParseException("小红书返回 ${response.code}，可能要求验证、登录或笔记不可见")
                            val id = ShareLinks.noteId(url) ?: ShareLinks.noteId(original)
                                ?: throw ParseException("未找到具体笔记；请复制完整的小红书分享链接，避免个人主页或搜索页")
                            val body = response.body ?: throw ParseException("小红书返回了空页面")
                            val bytes = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    kotlin.coroutines.coroutineContext.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (bytes.size() + count > 4 * 1024 * 1024) throw ParseException("笔记页面过大，已停止解析")
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            return@withContext XiaohongshuDocument.parse(bytes.toString("UTF-8"), id, url.toString())
                        }
                    }
                }
                throw ParseException("小红书链接跳转次数过多")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { kotlin.coroutines.coroutineContext.ensureActive(); lastError = e }
        }
        throw lastError
    }
}

class ShareResolver {
    suspend fun resolve(text: String): MediaInfo = if (ShareLinks.isXiaohongshu(ShareLinks.extract(text)))
        XiaohongshuResolver().resolve(text) else DouyinResolver().resolve(text)
}
