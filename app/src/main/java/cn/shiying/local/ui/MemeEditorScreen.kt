package cn.shiying.local.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import cn.shiying.local.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import cn.shiying.local.conversion.ConvertedFile
import cn.shiying.local.meme.*
import kotlin.math.roundToInt
import kotlin.math.log2
import kotlin.math.pow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemeEditorScreen(onBack: () -> Unit, model: MemeViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val subjectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::source) }
    val templatePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::customTemplate) }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    var shareError by remember { mutableStateOf<String?>(null) }
    var panel by rememberSaveable { mutableStateOf("头像") }
    var gallery by rememberSaveable { mutableStateOf("推荐") }
    val text = state.project.texts.firstOrNull { it.id == state.selectedText }
    val savedResult = state.result
    val selectionColor = MaterialTheme.colorScheme.primary
    val controlScroll = rememberScrollState()
    LaunchedEffect(panel) { controlScroll.scrollTo(0) }
    BackHandler { if (state.busy) model.cancel() else onBack() }
    if (savedResult != null) {
        SavedMeme(savedResult, shareError, onBack, onShare = {
            shareError = null
            runCatching {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = savedResult.mime
                    putExtra(Intent.EXTRA_STREAM, savedResult.uri)
                    clipData = ClipData.newRawUri("表情包", savedResult.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "分享表情包"))
            }.onFailure { shareError = "无法打开分享面板" }
        }, onNext = {
            panel = "头像"
            gallery = "推荐"
            shareError = null
            model.startNext()
        })
        return
    }
    Scaffold(topBar = {
        ToolHeader(Tool.MEME, onBack, !state.busy) {
            TextButton(onClick = model::save, enabled = !state.busy && state.canExport) { Text("保存 PNG") }
        }
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentAlignment = Alignment.TopCenter) {
            val split = maxWidth >= 600.dp && maxWidth > maxHeight
            val short = maxHeight < 360.dp
            val previewSize = if (split) minOf(maxWidth * .34f, maxHeight * .65f, 320.dp)
                else minOf(maxWidth - 56.dp, maxHeight * (if (short) .20f else if (panel == "模板") .26f else .32f), 300.dp)
            val preview: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).previewMat(
                    MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.outlineVariant)
                    .padding(if (short) 4.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(previewSize).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Canvas(Modifier.fillMaxSize()
                            .onSizeChanged { canvasSize = it }
                            .semantics { contentDescription = "表情包预览，点击选择文字或头像，拖动位置，双指缩放。下方可用按钮调整。" }
                            .pointerInput(state.busy) {
                                if (!state.busy) detectTapGestures {
                                    model.tap(it.x / canvasSize.width, it.y / canvasSize.height)
                                    panel = if (model.state.value.selectedText == null) "头像" else "文字"
                                }
                            }
                            .pointerInput(state.busy) {
                                if (!state.busy) detectTransformGestures { centroid, pan, zoom, _ ->
                                    model.transform(pan.x / canvasSize.width, pan.y / canvasSize.height, zoom,
                                        centroid.x / canvasSize.width, centroid.y / canvasSize.height)
                                }
                            }) {
                            val canvas = drawContext.canvas.nativeCanvas
                            canvas.save()
                            canvas.scale(size.width / 512f, size.height / 512f)
                            MemeCanvas.draw(canvas, state.project, state.subject, state.customTemplate)
                            text?.takeIf { it.text.isNotBlank() }?.let {
                                canvas.drawRect(MemeCanvas.textBounds(it), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = selectionColor.toArgb()
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = 1.5f
                                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(7f, 5f), 0f)
                                })
                            }
                            canvas.restore()
                        }
                    }
                    if (!short) Text(when {
                        state.selectedText != null -> "拖动选中的文字，双指调整字号"
                        state.subject != null -> "拖动头像调整位置，双指缩放"
                        else -> "选头像或输入配文，即可制作表情包"
                    },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val inspector: @Composable ColumnScope.() -> Unit = {
                val panels = listOf("头像", "文字", "模板")
                StudioTabs(panels, panels.indexOf(panel), enabled = { !state.busy }, onSelect = { index ->
                    panel = panels[index]
                    if (panel == "头像") model.select(null)
                    if (panel == "文字") model.select(state.selectedText ?: state.project.texts.firstOrNull()?.id)
                })
                Column(Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surface).verticalScroll(controlScroll).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (panel) {
                        "头像" -> {
                            ToolAction(Tool.MEME, if (state.original == null) "选择头像 / 主体图片" else "替换头像",
                                if (state.original == null) "导入图片后可自动抠图" else "保留文字、位置和缩放",
                                !state.busy) { subjectPicker.launch(arrayOf("image/*")) }
                            if (state.original == null) {
                                Surface(onClick = model::trySample, enabled = !state.busy,
                                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Image(painterResource(R.drawable.studio_cat), null, Modifier.size(50.dp))
                                        Text("试用猫咪贴纸", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                            if (state.advice != null) {
                                TextButton(onClick = { gallery = "推荐"; panel = "模板" }, enabled = !state.busy) { Text("查看推荐模板") }
                            }
                            if (state.original != null) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = state.project.useCutout, enabled = !state.busy && state.cutout != null,
                                        onClick = { model.useCutout(true) }, label = { Text("抠图头像") })
                                    FilterChip(selected = !state.project.useCutout, enabled = !state.busy,
                                        onClick = { model.useCutout(false) }, label = { Text("完整原图") })
                                    FilterChip(selected = state.project.mirrorSubject, enabled = !state.busy,
                                        onClick = model::mirrorSubject, label = { Text("水平镜像") })
                                }
                                Text("头像大小  ${(state.project.subject.scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                                Slider(value = log2(state.project.subject.scale), onValueChange = { model.subjectScale(2f.pow(it)) },
                                    valueRange = log2(.15f)..log2(8f), enabled = !state.busy,
                                    modifier = Modifier.semantics { contentDescription = "头像大小" })
                            }
                            if (state.project.template == MemeTemplate.PANDA && state.subject != null) Text("放大头像，可让脸部填满熊猫头的窗口。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        "文字" -> {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.project.texts.forEachIndexed { index, layer ->
                                    FilterChip(selected = state.selectedText == layer.id, enabled = !state.busy,
                                        onClick = { model.select(layer.id) }, label = {
                                            Text(layer.text.ifBlank { "文字 ${index + 1}" }, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                                        })
                                }
                                TextButton(onClick = model::addText, enabled = !state.busy && state.project.texts.size < 5) { Text("添加文字") }
                            }
                            if (text != null) {
                                var field by remember(text.id) { mutableStateOf(TextFieldValue(text.text, TextRange(0, text.text.length))) }
                                var fieldFocused by remember(text.id) { mutableStateOf(false) }
                                LaunchedEffect(text.text) {
                                    if (field.text != text.text) field = TextFieldValue(text.text, TextRange(text.text.length))
                                }
                                LaunchedEffect(text.id, fieldFocused) {
                                    if (fieldFocused) {
                                        // Apply after the initial tap places the cursor. Later taps can position it normally.
                                        withFrameNanos { }
                                        field = field.copy(selection = TextRange(0, field.text.length))
                                    }
                                }
                                OutlinedTextField(value = field, onValueChange = {
                                    field = it.copy(text = it.text.take(80), selection = TextRange(
                                        it.selection.start.coerceAtMost(80), it.selection.end.coerceAtMost(80)))
                                    model.text(field.text)
                                }, enabled = !state.busy, label = { Text("替换这段文字") },
                                    supportingText = { Text("${text.text.length}/80 · 最多显示 5 行") },
                                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().onFocusChanged {
                                        fieldFocused = it.isFocused
                                    })
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextInk.entries.forEach { ink ->
                                        FilterChip(selected = text.ink == ink, enabled = !state.busy,
                                            onClick = { model.ink(ink) }, leadingIcon = {
                                                Spacer(Modifier.size(14.dp).background(Color(ink.argb), CircleShape)
                                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                                            }, label = { Text(ink.label) })
                                    }
                                }
                                Text("字号  ${text.size.roundToInt()}", style = MaterialTheme.typography.labelLarge)
                                Slider(value = text.size, onValueChange = model::textSize, valueRange = 14f..96f, enabled = !state.busy,
                                    modifier = Modifier.semantics { contentDescription = "文字字号" })
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("顶部居中" to .16f, "画面居中" to .5f, "底部居中" to .84f).forEach { (label, y) ->
                                        OutlinedButton(onClick = { model.textPosition(y) }, enabled = !state.busy) { Text(label) }
                                    }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = model::duplicateText, enabled = !state.busy && state.project.texts.size < 5) { Text("复制这段文字") }
                                    TextButton(onClick = { model.text("") }, enabled = !state.busy && text.text.isNotEmpty()) { Text("清空内容") }
                                    TextButton(onClick = model::removeText, enabled = !state.busy) { Text("删除文字层") }
                                }
                            } else {
                                Text("添加一段文字，再编辑内容、颜色和位置。", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        "模板" -> {
                            TemplateGallery(state, gallery, { gallery = it }, model::template)
                            OutlinedButton(onClick = { templatePicker.launch(arrayOf("image/*")) }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth()) { Text("导入自定义模板") }
                            Text("点选模板会适配头像位置，配文和字号会保留。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if ((panel == "文字" && text != null) || (panel == "头像" && state.subject != null)) {
                        HorizontalDivider()
                        Text(if (state.selectedText == null) "调整头像位置" else "微调文字位置", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("左移" to (-.02f to 0f), "右移" to (.02f to 0f), "上移" to (0f to -.02f), "下移" to (0f to .02f)).forEach { (label, delta) ->
                                OutlinedButton(onClick = { model.transform(delta.first, delta.second) }, enabled = !state.busy) { Text(label) }
                            }
                            OutlinedButton(onClick = { model.transform(0f, 0f, .9f) }, enabled = !state.busy) { Text("缩小") }
                            OutlinedButton(onClick = { model.transform(0f, 0f, 1.1f) }, enabled = !state.busy) { Text("放大") }
                            TextButton(onClick = model::resetPosition, enabled = !state.busy) { Text("位置复位") }
                            if (panel == "头像" && state.advice != null) TextButton(enabled = !state.busy,
                                onClick = { model.template(state.project.template) }) { Text("按主体对齐") }
                        }
                    }
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(state.phase)
                        TextButton(onClick = model::cancel) { Text("取消") }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (panel == "头像") Text("可直接使用透明 PNG。GIF 取首帧，复杂背景可能影响抠图效果。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (split) {
                Row(Modifier.widthIn(max = 1080.dp).fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(.85f)) { preview() }
                    Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(8.dp), content = inspector)
                }
            } else {
                Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    preview()
                    inspector()
                }
            }
        }
    }
}

@Composable
private fun SavedMeme(result: ConvertedFile, error: String?, onBack: () -> Unit, onShare: () -> Unit, onNext: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Scaffold(topBar = { ToolHeader(Tool.MEME, onBack, true) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter) {
            val wide = maxWidth >= 550.dp && maxWidth > maxHeight
            val imageSize = if (wide) minOf(maxHeight - 44.dp, maxWidth * .40f) else minOf(maxWidth - 88.dp, maxHeight * .5f)
            val artwork: @Composable () -> Unit = {
                Box(Modifier.clip(MaterialTheme.shapes.large).previewMat(colors.surfaceContainer, colors.outlineVariant)
                    .padding(14.dp), contentAlignment = Alignment.Center) {
                    AsyncImage(model = result.uri, contentDescription = "已保存的表情包", contentScale = ContentScale.Fit,
                        modifier = Modifier.size(imageSize.coerceIn(80.dp, 340.dp)).border(1.dp, colors.outlineVariant))
                }
            }
            val actions: @Composable ColumnScope.() -> Unit = {
                Text("已保存到相册", style = MaterialTheme.typography.headlineSmall)
                Text("1024 × 1024 PNG · ${result.location}", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
                Button(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("分享表情包") }
                OutlinedButton(onClick = onNext, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("再做一张") }
                error?.let { Text(it, color = colors.error) }
            }
            if (wide) Row(Modifier.widthIn(max = 840.dp).fillMaxSize().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                artwork()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), content = actions)
            } else Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                artwork()
                actions()
            }
        }
    }
}
