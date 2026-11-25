package cn.shiying.local.parser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import cn.shiying.local.network.Http
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

class BrowserVerificationRequired(val pageUrl:String):Exception("平台需要验证，请完成验证后重试")

/** Short-lived offscreen WebView. Only the platform's public page is loaded; no account or challenge automation. */
class DouyinWebResolver(private val context:Context) {
    companion object { private val session=Mutex() }
    suspend fun resolve(initialUrl:String):MediaInfo = session.withLock { resolvePage(initialUrl) }
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolvePage(initialUrl:String):MediaInfo = withContext(Dispatchers.Main.immediate) {
        require(initialUrl.toHttpUrlOrNull()?.let(ShareLinks::isDouyin)==true)
        val view=WebView(context)
        var failure:String?=null
        try {
            view.settings.apply {
                javaScriptEnabled=true;domStorageEnabled=true;userAgentString=Http.PC_USER_AGENT
                allowFileAccess=false;allowContentAccess=false;setSupportMultipleWindows(false)
                mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;cacheMode=WebSettings.LOAD_NO_CACHE
                blockNetworkImage=true;mediaPlaybackRequiresUserGesture=true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(view,false)
            view.webViewClient=object:WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean =
                    request.url.toString().toHttpUrlOrNull()?.let(ShareLinks::isDouyin)!=true
                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError) {
                    if(request.isForMainFrame) failure="作品加载失败，请检查网络后重试"
                }
                override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse) {
                    if(request.isForMainFrame) failure="暂时无法加载作品，请稍后重试"
                }
                override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {
                    failure="作品加载中断，请重新解析";return true
                }
            }
            // Give the page a desktop viewport without creating a visible browser panel.
            view.layout(0,0,1080,1920)
            view.loadUrl(initialUrl)
            withTimeoutOrNull(22_000) { awaitMedia(view,initialUrl) { failure } }
                ?: throw ParseException("暂未获取到作品，请稍后重试")
        } finally {
            withContext(NonCancellable+Dispatchers.Main.immediate) {
                view.stopLoading();view.destroy()
                WebStorage.getInstance().deleteAllData()
                withTimeoutOrNull(1500) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        CookieManager.getInstance().removeAllCookies { if(continuation.isActive) continuation.resume(Unit) }
                    }
                }
            }
        }
    }

    private suspend fun awaitMedia(view:WebView,initialUrl:String,failure:()->String?):MediaInfo {
        while(true) {
            currentCoroutineContext().ensureActive()
            failure()?.let { throw ParseException(it) }
            val encoded=suspendCancellableCoroutine<String?> { continuation ->
                view.evaluateJavascript(BrowserPage.snapshotScript) { value -> if(continuation.isActive) continuation.resume(value) }
            }
            val snapshot=runCatching { (JSONTokener(encoded.orEmpty()).nextValue() as? String)?.let(::JSONObject) }.getOrNull()
            if(snapshot!=null) {
                val url=snapshot.optString("url");val html=snapshot.optString("html")
                if(html.isNotEmpty() && html.length<=4*1024*1024) {
                    val media=withContext(Dispatchers.Default) {
                        try { BrowserPage.parseDouyin(html,initialUrl,url) } catch(_:ParseException) { null }
                    }
                    if(media!=null) return media
                }
                if(snapshot.optBoolean("verification")) throw BrowserVerificationRequired(initialUrl)
            }
            delay(700)
        }
    }
}
