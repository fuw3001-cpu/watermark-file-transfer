package cn.shiying.local.parser

import cn.shiying.local.network.Http
import cn.shiying.local.network.await
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class DouyinResolver(private val client: OkHttpClient = Http.client) {
    suspend fun resolve(text: String): MediaInfo = withContext(Dispatchers.IO) {
        val shared = ShareLinks.extract(text)
        require(ShareLinks.isDouyin(shared)) { "此解析器只接受抖音链接" }
        ShareLinks.videoId(shared)?.takeUnless { isGalleryLink(shared) }?.let { id ->
            fetchFeed(id, shared.toString())?.let { return@withContext it }
        }
        val (finalUrl, html) = fetchPage(shared)
        val id = ShareLinks.videoId(finalUrl) ?: ShareLinks.videoId(shared)
            ?: throw ParseException("未识别到具体作品，请复制单条作品的分享链接")
        try {
            DouyinDocument.parse(html, id, finalUrl.toString())
        } catch (first: ParseException) {
            if (isGalleryLink(finalUrl) || isGalleryLink(shared)) {
                fetchGallery(id, finalUrl.toString(), html)?.let { return@withContext it }
                val desktop = "https://www.douyin.com/note/$id".toHttpUrl()
                try {
                    val (url, body) = fetchPage(desktop, Http.PC_USER_AGENT)
                    return@withContext DouyinDocument.parse(body, id, url.toString())
                } catch (e: CancellationException) { throw e }
                catch (_: java.io.IOException) { kotlin.coroutines.coroutineContext.ensureActive() }
                catch (_: ParseException) { kotlin.coroutines.coroutineContext.ensureActive() }
                throw ParseException("此图集需要网页加载，请打开作品网页后提取图片。", desktop.toString())
            }
            if (ShareLinks.videoId(shared) == null) {
                fetchFeed(id, finalUrl.toString())?.let { return@withContext it }
            }
            val fallback = "https://www.iesdouyin.com/share/video/$id/".toHttpUrl()
            if (finalUrl.host == fallback.host && finalUrl.encodedPath == fallback.encodedPath) throw first
            val (page, body) = fetchPage(fallback)
            DouyinDocument.parse(body, id, page.toString())
        }
    }

    private fun isGalleryLink(url: HttpUrl) = url.encodedPath.contains("/note/") || url.encodedPath.contains("/slides/")

    private suspend fun fetchGallery(id: String, pageUrl: String, html: String): MediaInfo? {
        // Public reflow API, referenced by parse-video's gallery path. No built-in account cookies.
        val builder = "https://www.iesdouyin.com/web/api/v2/aweme/slidesinfo/".toHttpUrl().newBuilder()
            .addQueryParameter("reflow_source", "reflow_page").addQueryParameter("aweme_ids", "[$id]")
            .addQueryParameter("request_source", "200")
        val webId = Regex("\"webId\"\\s*:\\s*\"([0-9]{10,24})\"").find(html)?.groupValues?.get(1)
        if (webId != null) builder.addQueryParameter("web_id", webId).addQueryParameter("device_id", webId)
        return try {
            val request = Request.Builder().url(builder.build()).header("User-Agent", Http.USER_AGENT)
                .header("Referer", pageUrl).build()
            client.newCall(request).await().use { response ->
                if (response.isSuccessful) DouyinDocument.parseFeed(readBody(response), id, pageUrl) else null
            }
        } catch (e: CancellationException) { throw e }
        catch (_: java.io.IOException) { kotlin.coroutines.coroutineContext.ensureActive(); null }
        catch (_: ParseException) { kotlin.coroutines.coroutineContext.ensureActive(); null }
    }

    private suspend fun fetchFeed(id: String, pageUrl: String): MediaInfo? {
        // MIT: ucmao/media-parser, src/parsers/douyin_parser.py, _request_mobile_feed.
        val userAgent = "com.ss.android.ugc.aweme/290101 (Linux; U; Android 10; zh_CN; Pixel 4; " +
            "Build/QQ3A.200805.001; Cronet/TTNetVersion:5f9037be 2023-01-13 QuicVersion:4668bb42 2022-11-21)"
        for (host in listOf("api5-normal-c-hl.amemv.com", "aweme.snssdk.com")) {
            try {
                val request = Request.Builder().url("https://$host/aweme/v1/feed/?aweme_id=$id&aid=1128")
                    .header("User-Agent", userAgent).header("Accept", "application/json").build()
                client.newCall(request).await().use { response ->
                    if (response.isSuccessful) {
                        return DouyinDocument.parseFeed(readBody(response), id, pageUrl)
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: ParseException) {
                kotlin.coroutines.coroutineContext.ensureActive()
            } catch (_: java.io.IOException) {
                kotlin.coroutines.coroutineContext.ensureActive()
            }
        }
        return null
    }

    private suspend fun readBody(response: okhttp3.Response): String {
        val body = response.body ?: throw ParseException("平台返回了空内容")
        val bytes = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > 4 * 1024 * 1024) throw ParseException("平台内容过大，已停止解析")
                bytes.write(buffer, 0, count)
            }
        }
        return bytes.toString("UTF-8")
    }

    private suspend fun fetchPage(initial: HttpUrl, userAgent: String = Http.USER_AGENT): Pair<HttpUrl, String> {
        var url = initial
        repeat(7) {
            require(ShareLinks.isDouyin(url)) { "链接跳转到了不支持的平台" }
            val request = Request.Builder().url(url).header("User-Agent", userAgent)
                .header("Accept-Language", "zh-CN,zh;q=0.9").build()
            client.newCall(request).await().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    url = response.header("Location")?.let(url::resolve)
                        ?: throw ParseException("分享链接跳转异常，请重新复制链接")
                } else {
                    if (!response.isSuccessful) throw ParseException("平台返回 ${response.code}，请稍后重试")
                    return url to readBody(response)
                }
            }
        }
        throw ParseException("分享链接跳转次数过多")
    }
}
