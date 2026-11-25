package cn.shiying.local

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.shiying.local.conversion.OutputFormat
import cn.shiying.local.parser.DouyinDocument
import cn.shiying.local.parser.ShareResolver
import cn.shiying.local.parser.XiaohongshuDocument
import cn.shiying.local.parser.MediaInfo
import cn.shiying.local.parser.ShareLinks
import cn.shiying.local.parser.ParseException
import cn.shiying.local.parser.DouyinWebResolver
import cn.shiying.local.parser.BrowserVerificationRequired
import cn.shiying.local.storage.VideoSaver
import cn.shiying.local.storage.ImageSaver
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class AppState(
    val text: String = "",
    val busy: Boolean = false,
    val saving: Boolean = false,
    val result: MediaInfo? = null,
    val error: String? = null,
    val message: String? = null,
    val browserUrl: String? = null,
    val requiresVerification: Boolean = false,
    val received: Long = 0,
    val total: Long = -1,
    val savedUri: Uri? = null,
    val savedImages: Map<Int, Uri> = emptyMap(),
    val savedLiveGifs: Map<Int, Uri> = emptyMap(),
    val savedLiveVideos: Map<Int, Uri> = emptyMap(),
    val savingImageIndex: Int? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    private val resolver = ShareResolver()
    private val saver = VideoSaver(application)
    private val imageSaver = ImageSaver(application)

    fun edit(text: String) {
        if (state.value.busy || state.value.saving) return
        mutableState.value = AppState(text = text.take(16_384))
    }

    fun parse() {
        if (state.value.busy || state.value.saving) return
        val text = state.value.text
        val link = try { ShareLinks.extract(text) } catch (e: IllegalArgumentException) {
            mutableState.update { it.copy(error = e.message) }; return
        }
        mutableState.value = AppState(text = text, busy = true, browserUrl = link.toString())
        task = viewModelScope.launch {
            try {
                val result = try { resolver.resolve(text) }
                catch(e:CancellationException) { throw e }
                catch(e:Exception) {
                    coroutineContext.ensureActive()
                    if(!ShareLinks.isDouyin(link)) throw e
                    val url=(e as? ParseException)?.assistanceUrl ?: link.toString()
                    mutableState.update { it.copy(browserUrl=url) }
                    DouyinWebResolver(getApplication()).resolve(url)
                }
                mutableState.update { it.copy(result = result, busy = false) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                mutableState.update { it.copy(busy = false, error = friendlyError(e),
                    requiresVerification=e is BrowserVerificationRequired,
                    browserUrl=(e as? BrowserVerificationRequired)?.pageUrl ?: (e as? ParseException)?.assistanceUrl ?: it.browserUrl) }
            }
        }
    }

    fun browserResult(html: String, pageUrl: String) {
        if (state.value.busy || state.value.saving) return
        val original = runCatching { ShareLinks.extract(state.value.text) }.getOrNull() ?: return
        val page = runCatching { ShareLinks.extract(pageUrl) }.getOrNull() ?: return
        if (!ShareLinks.samePlatform(original, page)) {
            mutableState.update { it.copy(error = "网页平台与原分享链接不匹配，请重新打开作品") }; return
        }
        val isXhs = ShareLinks.isXiaohongshu(original)
        val knownPage = state.value.browserUrl?.let { runCatching { ShareLinks.extract(it) }.getOrNull() }
            ?.takeIf { ShareLinks.samePlatform(original, it) }
        val id = if (isXhs) ShareLinks.noteId(original) ?: knownPage?.let(ShareLinks::noteId) ?: ShareLinks.noteId(page)
            else ShareLinks.videoId(original) ?: knownPage?.let(ShareLinks::videoId) ?: ShareLinks.videoId(page)
        val pageId = if (isXhs) ShareLinks.noteId(page) else ShareLinks.videoId(page)
        if (id != null && pageId != null && id != pageId) {
            mutableState.update { it.copy(error = "当前网页不是原分享作品，请重新打开原作品") }; return
        }
        if (id == null) {
            mutableState.update { it.copy(error = "当前网页不是具体作品，请重新复制单条作品的分享链接") }
            return
        }
        mutableState.update { it.copy(busy = true, error = null, message = null, requiresVerification = false) }
        task = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    if (isXhs) XiaohongshuDocument.parse(html, id, pageUrl) else DouyinDocument.parse(html, id, pageUrl)
                }
                mutableState.update { it.copy(busy = false, result = result, savedUri = null, savedImages = emptyMap(),savedLiveVideos=emptyMap(),savedLiveGifs=emptyMap()) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                mutableState.update { it.copy(busy = false, error = friendlyError(e), browserUrl = (e as? ParseException)?.assistanceUrl ?: it.browserUrl) }
            }
        }
    }

    fun save() = saveSelection(null)

    fun saveImage(index: Int) = saveSelection(index)
    fun saveLiveVideo(index: Int) = saveSelection(index, OutputFormat.MP4)
    fun saveLiveGif(index: Int) = saveSelection(index, OutputFormat.GIF)

    private fun saveSelection(imageIndex: Int?, liveFormat: OutputFormat? = null) {
        val result = state.value.result ?: return
        if (state.value.busy || state.value.saving) return
        val alreadySaved = when (liveFormat) {
            OutputFormat.GIF -> state.value.savedLiveGifs
            OutputFormat.MP4 -> state.value.savedLiveVideos
            else -> state.value.savedImages
        }
        val indices = if (result.isGallery) {
            if (imageIndex != null && imageIndex !in result.images.indices) return
            (imageIndex?.let { listOf(it) } ?: result.images.indices.toList()).filterNot { it in alreadySaved }
        } else emptyList()
        if (result.isGallery && indices.isEmpty()) return
        if (!result.isGallery && imageIndex != null) return
        if(liveFormat != null && (imageIndex==null || !result.images[imageIndex].isLive))return
        mutableState.update { it.copy(saving = true, savingImageIndex = imageIndex, error = null, message = null, received = 0, total = -1) }
        task = viewModelScope.launch {
            try {
                val progress: (Long, Long) -> Unit = { bytes, total -> mutableState.update { it.copy(received = bytes, total = total) } }
                if (liveFormat != null) {
                    val index = requireNotNull(imageIndex)
                    val uri = imageSaver.saveLive(result, index, liveFormat, progress)
                    mutableState.update { current ->
                        val done = current.copy(saving = false, savingImageIndex = null)
                        if (liveFormat == OutputFormat.GIF) done.copy(savedLiveGifs = done.savedLiveGifs + (index to uri),
                            message = "GIF 已保存 · Pictures/Shiying")
                        else done.copy(savedLiveVideos = done.savedLiveVideos + (index to uri), message = "实况视频已保存 · Movies/Shiying")
                    }
                } else if (result.isGallery) {
                    val uris = imageSaver.saveSelected(result, indices, progress)
                    mutableState.update {
                        val saved = it.savedImages + indices.zip(uris)
                        it.copy(saving = false, savingImageIndex = null, savedImages = saved,
                            savedUri = if (saved.size == result.images.size) saved[0] else null,
                            message = if (imageIndex != null) "第 ${imageIndex + 1} 张已保存到相册" else "已保存 ${uris.size} 张图片 · Pictures/Shiying")
                    }
                } else {
                    val uri = saver.save(result, progress)
                    mutableState.update { it.copy(saving = false, savedUri = uri, message = "已保存到相册 · Movies/Shiying") }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: OutOfMemoryError) {
                mutableState.update { it.copy(saving=false,savingImageIndex=null,error="内存不足，请单张保存或缩小图片") }
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                mutableState.update { it.copy(saving = false, savingImageIndex = null, error = friendlyError(e)) }
            }
        }
    }

    fun cancel() {
        task?.cancel()
        mutableState.update { it.copy(busy = false, saving = false, savingImageIndex = null, message = "已取消", error = null) }
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "网络请求超时，请检查网络后重试"
        is java.net.UnknownHostException -> "无法连接平台，请检查网络连接"
        is IOException -> e.message?.takeIf { !it.contains("http") } ?: "网络连接中断，请稍后重试"
        else -> e.message ?: "处理失败，请重新复制作品链接"
    }
}
