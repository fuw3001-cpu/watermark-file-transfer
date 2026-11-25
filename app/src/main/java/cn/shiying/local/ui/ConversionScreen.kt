package cn.shiying.local.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import coil.compose.AsyncImage
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.shiying.local.conversion.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversionScreen(onBack: () -> Unit, onOpenEditor: (Tool) -> Unit, model: ConversionViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::select) }
    var openError by remember { mutableStateOf<String?>(null) }
    val invalidStart = state.spec.kind == ConversionKind.GIF && state.spec.gifStartSeconds !in 0..3600
    BackHandler { if (state.busy) model.cancel() else onBack() }
    val scroll = rememberScrollState()
    LaunchedEffect(state.result?.uri) { if (state.result != null) scroll.scrollTo(0) }
    ToolPage(Tool.CONVERT, onBack, !state.busy, scrollState = scroll) {
        state.result?.let { result ->
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("转换完成", style = MaterialTheme.typography.titleLarge)
                    Text(result.name)
                    if (result.mime.startsWith("image/")) AsyncImage(model = result.uri, contentDescription = "生成结果预览",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp), contentScale = ContentScale.Fit)
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = result.mime
                            putExtra(Intent.EXTRA_STREAM, result.uri)
                            clipData = android.content.ClipData.newRawUri(result.name, result.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "分享文件")) }.onFailure { openError = "无法打开分享面板" }
                    }, modifier = Modifier.fillMaxWidth()) { Text("分享结果文件") }
                    Text("%.2f MB · %s".format(result.size / 1048576.0, result.location))
                    OutlinedButton(onClick = {
                        openError = null
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(result.uri, result.mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: android.content.ActivityNotFoundException) {
                            openError = "文件已保存，但手机上没有可打开此格式的应用，可在系统相册或文件管理器中查看。"
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("打开结果文件") }
                    if (result.mime == "application/pdf") OutlinedButton(onClick = {
                        openError = null
                        runCatching { cn.shiying.local.documents.PdfPrinter.open(context, result.uri, result.name) }
                            .onFailure { openError = it.message ?: "无法打开系统打印" }
                    }, modifier = Modifier.fillMaxWidth()) { Text("打印 PDF") }
                    openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        StudioSection("选择转换类型")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ConversionKind.entries.filter { it != ConversionKind.MEME }.forEach { kind ->
                FilterChip(selected = state.spec.kind == kind, enabled = !state.busy,
                    onClick = { openError = null; model.kind(kind) }, label = { Text(kind.label) })
            }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ToolAction(Tool.CONVERT, if (state.source == null) "选择文件" else "重新选择文件",
                    "从设备导入原文件", !state.busy) {
                    openError = null
                    picker.launch(state.spec.kind.pickerTypes)
                }
                state.source?.let { source ->
                    Text(source.name, style = MaterialTheme.typography.bodyLarge)
                    Text(source.size?.let { "%.2f MB".format(it / 1048576.0) } ?: "文件大小将在读取后确认",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Text("转换设置", style = MaterialTheme.typography.titleMedium)
                if (state.spec.kind == ConversionKind.DOCUMENT) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DocumentOperation.entries.forEach { operation ->
                            FilterChip(selected = state.spec.documentOperation == operation, enabled = !state.busy,
                                onClick = { model.documentOperation(operation) }, label = { Text(operation.label) })
                        }
                    }
                    Text(state.spec.documentOperation.explanation, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("文档最多 64 MB，结果保存在 Downloads/Shiying。Office 引擎需要 ARM64 手机；首次使用会解包，请预留足够空间。PDF 转 Word 可使用离线 OCR。", style = MaterialTheme.typography.bodySmall)
                } else if (state.spec.kind == ConversionKind.GIF) {
                    Text("视频或动图 → GIF，最长 10 秒，无声音。")
                    OutlinedTextField(
                        value = state.spec.gifStartSeconds.takeIf { it >= 0 }?.toString().orEmpty(),
                        onValueChange = { value -> if (value.length <= 4 && value.all(Char::isDigit)) model.gifSettings(start = value.toIntOrNull() ?: -1) },
                        enabled = !state.busy, label = { Text("起始时间（秒）") }, singleLine = true,
                        isError = invalidStart, supportingText = if (invalidStart) ({ Text("请输入 0 到 3600 之间的整秒数") }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                    Text("截取时长 · ${state.spec.gifDurationSeconds} 秒")
                    Slider(value = state.spec.gifDurationSeconds.toFloat(), onValueChange = { model.gifSettings(duration = it.toInt()) },
                        enabled = !state.busy, valueRange = 1f..10f, steps = 8)
                    Text("帧率 · ${state.spec.gifFps} 帧/秒")
                    Slider(value = state.spec.gifFps.toFloat(), onValueChange = { model.gifSettings(fps = it.toInt()) },
                        enabled = !state.busy, valueRange = 5f..15f, steps = 9)
                    Text("最长边 480 像素，循环播放。可将 GIF 再转 MP4，或在图片互转中提取首帧。", style = MaterialTheme.typography.bodySmall)
                } else if (state.spec.kind == ConversionKind.IMAGE) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConversionSpec.imageFormats.forEach { format ->
                            FilterChip(selected = state.spec.imageFormat == format, enabled = !state.busy,
                                onClick = { model.imageFormat(format) }, label = { Text(format.label) })
                        }
                    }
                    if (state.spec.imageFormat != OutputFormat.PNG) {
                        Text("输出质量 · ${state.spec.quality}%")
                        Slider(value = state.spec.quality.toFloat(), onValueChange = { model.quality(it.toInt()) },
                            enabled = !state.busy, valueRange = 60f..100f, steps = 7)
                    }
                    Text(when (state.spec.imageFormat) {
                        OutputFormat.JPG -> "JPG 不支持透明背景，透明区域会填成白色。"
                        OutputFormat.PNG -> "PNG 使用无损编码，支持透明背景，文件可能更大。"
                        else -> "WebP 使用有损压缩，可保留透明背景。"
                    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("支持 JPG、PNG、WebP、GIF，最多 1600 万像素；动态图只导出第一帧。", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("输出格式 · ${state.spec.outputFormat.label}", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.spec.kind == ConversionKind.VIDEO)
                        "兼容的 H.264 视频和 AAC 音轨可直接封装为 MP4，保留原画质。其他视频或 GIF 会重新编码。"
                    else "从音频或视频中提取第一路音轨，生成 192 kbps MP3。没有音轨的文件无法转换。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = model::convert, enabled = !state.busy && state.source != null && !invalidStart,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(if (state.busy) state.phase else if (state.spec.kind == ConversionKind.DOCUMENT) "转换并保存" else "保存为 ${state.spec.outputFormat.label}")
                }
                if (state.busy) {
                    state.progress?.let { progress ->
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = model::cancel, modifier = Modifier.fillMaxWidth()) { Text("取消处理") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!state.busy && state.phase == "已取消") Text("已取消，原文件保留。")
            }
        }
        HorizontalDivider()
        Text("图片编辑", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onOpenEditor(Tool.REPAIR) }, enabled = !state.busy) { Text("图片去水印") }
            OutlinedButton(onClick = { onOpenEditor(Tool.MEME) }, enabled = !state.busy) { Text("表情包工作台") }
        }
        Text("媒体文件最多 512 MB，文档最多 64 MB。请保持 App 开启；转换会使用手机的存储、运算和电量。图片、视频、MP3 和文档分别存入 Pictures、Movies、Music 和 Downloads 下的 Shiying 目录。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
