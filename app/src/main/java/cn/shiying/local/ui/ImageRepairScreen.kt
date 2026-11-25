package cn.shiying.local.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import cn.shiying.local.R
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.shiying.local.repair.*
import coil.compose.AsyncImage

@Composable
fun ImageRepairScreen(onBack:()->Unit,model:RepairViewModel=viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::select) }
    var brush by rememberSaveable { mutableFloatStateOf(.018f) }
    var current by remember { mutableStateOf<List<RepairPoint>>(emptyList()) }
    var view by remember(state.source, state.preview) { mutableStateOf(if (state.preview == null) "涂抹" else "修复预览") }
    var shareError by remember { mutableStateOf<String?>(null) }
    BackHandler { if(state.busy)model.cancel() else onBack() }
    ToolPage(Tool.REPAIR, onBack, !state.busy) {
        if (state.source == null) {
            ToolAction(Tool.REPAIR, "选择图片", "涂抹水印区域，预览后另存为 PNG", !state.busy) {
                picker.launch(arrayOf("image/*"))
            }
            if (state.result == null && !state.busy) {
                Surface(onClick = {
                    val sample = Uri.parse("android.resource://${context.packageName}/${R.drawable.repair_sample}")
                    model.select(sample)
                }, shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column {
                        Image(painterResource(R.drawable.repair_sample), null, contentScale = ContentScale.Crop,
                            alignment = Alignment.BottomCenter, modifier = Modifier.fillMaxWidth().aspectRatio(1.85f))
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("试用示例照片", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text("涂抹右下角的 SAMPLE，查看修复效果。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !state.busy) { Text("更换图片") }
        }
        state.source?.let { source ->
            if (state.preview == null) Text("在图上涂抹要修复的区域", style = MaterialTheme.typography.labelLarge)
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("原图对比", "修复预览", "涂抹").forEach { mode ->
                    FilterChip(selected = view == mode, enabled = !state.busy, onClick = { view = mode },
                        label = { Text(mode) })
                }
            }
            val editable = !state.busy && view == "涂抹"
            val heightLimit = LocalConfiguration.current.screenHeightDp.dp * .52f
            BoxWithConstraints(Modifier.fillMaxWidth().previewMat(MaterialTheme.colorScheme.surfaceContainer,
                MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) {
                val aspect = source.width.toFloat() / source.height
                val height = minOf(maxWidth / aspect, heightLimit)
                Box(Modifier.size(width = height * aspect, height = height)) {
                    Image((if(view == "修复预览") state.preview ?: source else source).asImageBitmap(),contentDescription="图片修复画布",modifier=Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()
                        .pointerInput(source, editable, brush) {
                            if (!editable) return@pointerInput
                            fun point(p: Offset) = RepairPoint((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f))
                            try {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume()
                                    current = listOf(point(down.position))
                                    do {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        val p = point(change.position)
                                        if (current.size < 2000 && p != current.last()) current = current + p
                                        change.consume()
                                        if (!change.pressed) {
                                            model.stroke(RepairStroke(current, brush))
                                            break
                                        }
                                    } while (true)
                                    current = emptyList()
                                }
                            } finally { current = emptyList() }
                        }) {
                        if(view == "涂抹") for(stroke in state.strokes+listOf(RepairStroke(current,brush))) {
                            if(stroke.points.isEmpty())continue
                            val path=Path().apply { val p=stroke.points.first();moveTo(p.x*size.width,p.y*size.height)
                                stroke.points.drop(1).forEach { lineTo(it.x*size.width,it.y*size.height) } }
                            val radius=stroke.radius*minOf(size.width,size.height)
                            drawPath(path,Color.Red.copy(alpha=.4f),style=Stroke(width=radius*2,cap=StrokeCap.Round,join=StrokeJoin.Round))
                            if(stroke.points.size==1) { val p=stroke.points.first();drawCircle(Color.Red.copy(alpha=.4f),radius,Offset(p.x*size.width,p.y*size.height)) }
                        }
                    }
                }
            }
            if (view == "涂抹") {
                Text("画笔直径 · ${maxOf(1, (brush * 2 * minOf(source.width, source.height)).roundToInt())} 像素",style=MaterialTheme.typography.labelLarge)
                Slider(value=brush,onValueChange={ brush=it },valueRange=.005f.. .06f,enabled=!state.busy,
                    modifier = Modifier.semantics { contentDescription = "画笔大小" })
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick=model::undo,enabled=!state.busy && state.strokes.isNotEmpty()) { Text("撤销") }
                    TextButton(onClick=model::redo,enabled=!state.busy && state.undone.isNotEmpty()) { Text("重做") }
                    TextButton(onClick=model::clear,enabled=!state.busy && state.strokes.isNotEmpty()) { Text("清空涂抹") }
                }
                if (state.preview == null) Button(onClick=model::repair,enabled=!state.busy && state.strokes.isNotEmpty(),modifier=Modifier.fillMaxWidth()) { Text("生成修复预览") }
            }
            if (state.preview != null) OutlinedButton(onClick=model::save,enabled=!state.busy && state.preview!=null,modifier=Modifier.fillMaxWidth()) { Text("保存图片") }
        }
        if(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick=model::cancel) { Text("取消") } }
        if(state.phase.isNotBlank())Text(state.phase)
        state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        state.result?.let { result ->
            AsyncImage(model=result.uri,contentDescription="已保存的修复图片",modifier=Modifier.fillMaxWidth().heightIn(max=360.dp))
            OutlinedButton(onClick={
                runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type=result.mime;putExtra(Intent.EXTRA_STREAM,result.uri);clipData=ClipData.newRawUri("修复图片",result.uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },"分享图片")) }.onFailure { shareError="无法打开分享面板" }
            }) { Text("分享图片") }
        }
        shareError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        Text("适合简单背景上的小块水印。复杂纹理可能留下痕迹。支持静态图，PNG 最长边 4096 像素，最高 1200 万像素。",style=MaterialTheme.typography.bodySmall)
    }
}
