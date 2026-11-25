package cn.shiying.local.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.shiying.local.network.Http
import cn.shiying.local.parser.MediaInfo
import cn.shiying.local.parser.ShareLinks
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONTokener

@Composable
@SuppressLint("SetJavaScriptEnabled") // Required by the visible platform page; no JS bridge, local file access or arbitrary top-level navigation.
fun BrowserAssist(initialUrl: String, onDismiss: () -> Unit, onExtract: (String, String) -> Unit) {
    val context = LocalContext.current
    val original = remember(initialUrl) { initialUrl.toHttpUrlOrNull() }
    var message by remember { mutableStateOf("加载作品网页中…") }
    var loaded by remember { mutableStateOf(false) }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.userAgentString = Http.PC_USER_AGENT
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return request.url.toString().toHttpUrlOrNull()?.let { next -> original?.let { ShareLinks.samePlatform(it, next) } } != true
                }
                override fun onPageFinished(view: WebView, url: String) {
                    loaded = url.toHttpUrlOrNull()?.let { next -> original?.let { ShareLinks.samePlatform(it, next) } } == true
                    message = "页面加载后，点击下方提取。若平台要求验证，请在网页中自行完成。"
                }
            }
            loadUrl(initialUrl)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading(); webView.destroy()
            CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("网页辅助解析", style = MaterialTheme.typography.titleLarge)
                Text(message, style = MaterialTheme.typography.bodySmall)
                AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
                Button(enabled = loaded, modifier = Modifier.fillMaxWidth(), onClick = {
                    val url = webView.url.orEmpty()
                    val extract = cn.shiying.local.parser.BrowserPage.extractScript
                    webView.evaluateJavascript(extract) { value ->
                        val html = runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
                        if (html == null || html.length > 4 * 1024 * 1024) message = "页面内容不可读取，请关闭后重试"
                        else onExtract(html, url)
                    }
                }) { Text("提取当前作品") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭网页") }
            }
        }
    }
}

@Composable
fun VideoPreview(info: MediaInfo, onDismiss: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(info.videoUrl) {
        VideoView(context).apply {
            setMediaController(MediaController(context).also { it.setAnchorView(this) })
            setOnErrorListener { _, _, _ -> error = "预览失败，播放地址可能已过期，请重新解析"; true }
            setOnPreparedListener { start() }
            setVideoURI(android.net.Uri.parse(info.videoUrl), mapOf("User-Agent" to info.userAgent, "Referer" to info.platform.referer))
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.stopPlayback() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("视频预览", style = MaterialTheme.typography.titleLarge)
                AndroidView(factory = { player }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭预览") }
            }
        }
    }
}
