package cn.shiying.local.ui

import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import cn.shiying.local.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.shiying.local.MainViewModel
import cn.shiying.local.parser.ShareLinks
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun HomeScreen(model: MainViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showBrowser by rememberSaveable { mutableStateOf(false) }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    var previewImage by rememberSaveable(state.result) { mutableStateOf<Int?>(null) }
    var activeTool by rememberSaveable { mutableStateOf<Tool?>(null) }
    var editorFromConverter by rememberSaveable { mutableStateOf(false) }
    var toolsTab by rememberSaveable { mutableStateOf(false) }
    var pasteMessage by remember { mutableStateOf<String?>(null) }
    var openError by remember(state.savedUri) { mutableStateOf<String?>(null) }
    val linkScroll = rememberScrollState()
    val toolScroll = rememberScrollState()
    val pageStates = rememberSaveableStateHolder()
    val focus = LocalFocusManager.current
    val tool = activeTool
    if (tool != null) {
        val back = {
            activeTool = if (editorFromConverter) Tool.CONVERT else null
            editorFromConverter = false
        }
        pageStates.SaveableStateProvider(tool.name) {
            when (tool) {
                Tool.CONVERT -> ConversionScreen(onBack = back, onOpenEditor = {
                    editorFromConverter = true
                    activeTool = it
                })
                Tool.REPAIR -> ImageRepairScreen(onBack = back)
                Tool.MEME -> MemeEditorScreen(onBack = back)
                Tool.SCAN -> ScanScreen(onBack = back)
                Tool.MERGE -> MergePrintScreen(onBack = back)
            }
        }
        return
    }
    val working = state.busy || state.saving
    BackHandler(enabled = toolsTab) { toolsTab = false }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 5.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_launcher), null, Modifier.size(37.dp).clip(MaterialTheme.shapes.small))
                    Text("拾影", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    TextButton(onClick = { showAbout = true }) { Text("关于") }
                }
                StudioTabs(listOf("链接收藏", "本地工具"), if (toolsTab) 1 else 0,
                    onSelect = { focus.clearFocus(); toolsTab = it == 1 }, enabled = { it == 0 || !working },
                    modifier = Modifier.padding(horizontal = 18.dp), icons = listOf(R.drawable.ic_link, R.drawable.ic_grid))
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(if (toolsTab) toolScroll else linkScroll)
                    .padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (toolsTab) {
                        ToolDirectory { activeTool = it }
                    } else {
                        Surface(shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column {
                                LinkCover()
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(value = state.text, onValueChange = { pasteMessage = null; model.edit(it) },
                                        enabled = !working, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                                        label = { Text("分享链接或整段文案") },
                                        placeholder = { Text("在这里粘贴或输入") },
                                        shape = MaterialTheme.shapes.medium, isError = state.error != null && state.result == null,
                                    )
                                    val paste = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = runCatching { clipboard.primaryClip }.getOrNull()
                                        val pasted = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text else null
                                        if (pasted.isNullOrBlank()) pasteMessage = "剪贴板中没有可粘贴的文字"
                                        else { pasteMessage = null; model.edit(pasted.toString()) }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        FilledTonalButton(onClick = paste, enabled = !working, modifier = Modifier.weight(1.15f).heightIn(min = 48.dp),
                                            shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 8.dp)) {
                                            Icon(painterResource(R.drawable.ic_paste), null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(5.dp))
                                            Text("从剪贴板粘贴", textAlign = TextAlign.Center)
                                        }
                                        Button(onClick = { focus.clearFocus(); model.parse() }, enabled = !working && state.text.isNotBlank(),
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium,
                                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 8.dp)) {
                                            if (state.busy) {
                                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(if (state.busy) "正在解析…" else "解析链接", textAlign = TextAlign.Center)
                                        }
                                    }
                                    if (state.text.isNotEmpty()) TextButton(onClick = { pasteMessage = null; model.edit("") },
                                        enabled = !working, modifier = Modifier.align(Alignment.End)) { Text("清空") }
                                    pasteMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    if (state.busy) TextButton(onClick = model::cancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                                    state.error?.takeIf { state.result == null }?.let { error ->
                                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                        if (state.browserUrl != null && !working && (state.requiresVerification ||
                                                state.browserUrl?.toHttpUrlOrNull()?.let(ShareLinks::isXiaohongshu) == true)) {
                                            OutlinedButton(onClick = { showBrowser = true }, modifier = Modifier.fillMaxWidth()) {
                                                Text(if(state.requiresVerification) "完成平台验证" else "尝试网页辅助解析")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (state.result == null && !working) QuickTools(open = { focus.clearFocus(); activeTool = it },
                            all = { focus.clearFocus(); toolsTab = true })
                        state.result?.let { result ->
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("解析结果", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text("${result.platform.label} · ${if (result.isGallery) "图集 ${result.images.size} 张" else "视频"}", style = MaterialTheme.typography.labelLarge)
                                        if (result.isGallery) {
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                itemsIndexed(result.images, key = { index, _ -> "${result.id}-$index" }) { index, item ->
                                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        AsyncImage(model = ImageRequest.Builder(context).data(item.previewUrl)
                                                            .setHeader("User-Agent", result.userAgent).setHeader("Referer", result.platform.referer).build(),
                                                            contentDescription = "图集第 ${index + 1} 张，共 ${result.images.size} 张",
                                                            contentScale = ContentScale.Fit, modifier = Modifier.width(240.dp).height(260.dp).clickable(onClickLabel = "查看第 ${index + 1} 张大图") { previewImage = index })
                                                        Row(Modifier.width(240.dp), horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically) {
                                                            Text("${index + 1} / ${result.images.size}", style = MaterialTheme.typography.labelMedium)
                                                            TextButton(onClick = { model.saveImage(index) }, enabled = !working && index !in state.savedImages) {
                                                                Text(when { index in state.savedImages -> "已保存"; state.saving && state.savingImageIndex == index -> "保存中…"; else -> "保存这张" })
                                                            }
                                                        }
                                                        if (item.isLive) TextButton(onClick = { model.saveLiveGif(index) }, enabled = !working && index !in state.savedLiveGifs) {
                                                            Text(if (index in state.savedLiveGifs) "GIF 已保存" else "实况另存 GIF（最长 10 秒）")
                                                        } else if (item.animated) Text("动图", style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                            Text("点击图片查看大图，可单张或全部保存。", style = MaterialTheme.typography.bodySmall)
                                        } else if (result.coverUrl.isNotEmpty()) AsyncImage(
                                            model = ImageRequest.Builder(context).data(result.coverUrl)
                                                .setHeader("User-Agent", result.userAgent).setHeader("Referer", result.platform.referer).build(),
                                            contentDescription = "视频封面", contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                                        )
                                        Text(result.title, style = MaterialTheme.typography.titleMedium)
                                        if (result.author.isNotEmpty()) Text("作者 · ${result.author}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("平台提供的媒体资源 · 不进行画面修补", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (!result.isGallery) OutlinedButton(onClick = { showPreview = true }, enabled = !working,
                                            modifier = Modifier.fillMaxWidth()) { Text("播放预览") }
                                        Button(onClick = model::save, enabled = !working && state.savedUri == null,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                                            Text(when { state.saving -> "正在保存…"; state.savedUri != null -> "已保存到相册";
                                                result.isGallery -> if (state.savedImages.isEmpty()) "保存全部 ${result.images.size} 张图片" else "保存剩余 ${result.images.size - state.savedImages.size} 张图片"; else -> "保存到相册" })
                                        }
                                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                        if (state.saving) {
                                            TextButton(onClick = model::cancel) { Text("取消保存") }
                                            if (state.total > 0) LinearProgressIndicator(
                                                progress = { (state.received.toFloat() / state.total).coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth(),
                                            ) else LinearProgressIndicator(Modifier.fillMaxWidth())
                                            Text("已下载 ${"%.1f".format(state.received / 1048576.0)} MB · 请保持 App 开启",
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                        openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                        state.savedUri?.let { uri ->
                                            TextButton(onClick = {
                                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                                                    if (result.isGallery) context.contentResolver.getType(uri) ?: "image/*" else "video/mp4")
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                                    .onFailure { openError = "文件已保存，请从系统相册或文件管理器打开。" }
                                            }, modifier = Modifier.fillMaxWidth()) { Text(if (result.isGallery) "打开已保存的图片" else "打开已保存的视频") }
                                        }
                                    }
                                }
                            }
                        }
                        state.message?.let {
                            Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                                Text(it, Modifier.fillMaxWidth().padding(16.dp))
                            }
                        }
                        Text("请仅收藏有权保存的作品。解析与下载需联网。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (showBrowser && state.browserUrl != null) BrowserAssist(
        initialUrl = state.browserUrl!!, onDismiss = { showBrowser = false },
        onExtract = { html, url -> showBrowser = false; model.browserResult(html, url) },
    )
    if (showPreview && state.result != null && !state.result!!.isGallery) VideoPreview(state.result!!, onDismiss = { showPreview = false })
    state.result?.takeIf { it.isGallery }?.let { result ->
        previewImage?.takeIf { it in result.images.indices }?.let { index ->
            GalleryPreview(result, index, state, model::saveImage, model::cancel, onSaveLiveVideo=model::saveLiveVideo,onSaveLiveGif=model::saveLiveGif,onDismiss = { previewImage = null })
        }
    }
    if (showAbout) AlertDialog(
        onDismissRequest = { showAbout = false }, title = { Text("拾影 · ${cn.shiying.local.BuildConfig.VERSION_NAME}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("链接收藏、表情包制作、文档扫描与转换。")
            Text("无需注册，无广告。文件在本地处理，链接解析需联网。")
            TextButton(onClick = { showAbout = false; showLicenses = true }) { Text("开源许可") }
        } }, confirmButton = { TextButton(onClick = { showAbout = false }) { Text("关闭") } },
    )
    if (showLicenses) {
        val notices = remember {
            val base = context.assets.open("open_source_notices.txt").bufferedReader().use { it.readText() }
            val libraries = listOf("ffmpegkit", "documents", "matting", "scanning").joinToString("\n\n") { folder ->
                context.assets.list("licenses/$folder").orEmpty().sorted().joinToString("\n\n") { file ->
                    "$file\n" + context.assets.open("licenses/$folder/$file").bufferedReader().use { it.readText() }
                }
            }
            "$base\n\n$libraries"
        }
        AlertDialog(onDismissRequest = { showLicenses = false }, title = { Text("开源许可") },
            text = { Text(notices, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("关闭") } })
    }
}

@Composable
private fun LinkCover() {
    Box(Modifier.fillMaxWidth().heightIn(min = 128.dp), contentAlignment = Alignment.CenterStart) {
        Image(painterResource(R.drawable.film_slides), null, Modifier.matchParentSize(),
            contentScale = ContentScale.Crop, alignment = Alignment.Center)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(
            Color(0xDD101D16), Color(0xB3101D16), Color.Transparent, Color.Transparent))))
        Column(Modifier.padding(horizontal = 19.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("抖音  /  小红书", color = Color(0xFFFFD699), style = MaterialTheme.typography.labelMedium)
            Text("收藏分享作品", color = Color.White, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.semantics { heading() })
        }
    }
}

@Composable
private fun QuickTools(open: (Tool) -> Unit, all: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StudioSection("常用工具")
            TextButton(onClick = all) { Text("全部工具"); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp)) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth / 3 < 97.dp * LocalDensity.current.fontScale
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Tool.REPAIR, Tool.MEME, Tool.SCAN).forEach { tool ->
                    val label = if (!compact) tool.title else when (tool) {
                        Tool.REPAIR -> "去水印"
                        Tool.MEME -> "做表情包"
                        else -> "扫文档"
                    }
                    Surface(onClick = { open(tool) }, modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ToolCover(tool, Modifier.fillMaxWidth().height(72.dp))
                            Text(label, Modifier.padding(horizontal = 2.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
