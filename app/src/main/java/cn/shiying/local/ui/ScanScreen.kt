package cn.shiying.local.ui

import android.Manifest
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import cn.shiying.local.R
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.shiying.local.documents.PdfPrinter
import cn.shiying.local.scan.*
import coil.compose.AsyncImage

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onBack:()->Unit,model:ScanViewModel=viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var showAutoCamera by rememberSaveable { mutableStateOf(false) }
    var pendingAuto by rememberSaveable { mutableStateOf(true) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showText by rememberSaveable { mutableStateOf(false) }
    var showPages by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmNew by rememberSaveable { mutableStateOf(false) }
    var corner by rememberSaveable(state.selectedId) { mutableIntStateOf(0) }
    val takePicture=remember { object:ActivityResultContracts.TakePicture() {
        override fun createIntent(context:Context,input:Uri):Intent=super.createIntent(context,input).apply {
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData=ClipData.newRawUri("扫描拍照",input)
        }
    } }
    val camera=rememberLauncherForActivityResult(takePicture) { success -> if(success)previewMode=true;model.captureResult(success) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if(it.isNotEmpty())previewMode=true;model.import(it) }
    fun importImages() {
        runCatching { picker.launch(arrayOf("image/*")) }.onFailure { model.error("无法打开图片选择器") }
    }
    fun systemCamera() {
        runCatching { camera.launch(model.newCapture()) }.onFailure { model.captureResult(false);model.error("无法打开系统相机，请使用相册导入") }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) { if(pendingAuto)showAutoCamera=true else systemCamera() }
        else model.error("未获得相机权限，可从相册导入；自动扫描需要相机权限")
    }
    fun openCamera(auto:Boolean) {
        pendingAuto=auto
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) {
            if(auto)showAutoCamera=true else systemCamera()
        } else permission.launch(Manifest.permission.CAMERA)
    }
    val available=!state.busy && !state.capturing
    if(showAutoCamera) {
        AutoScanCamera(context,state.pages.size,available && state.pages.size<20,model::automaticCaptureFile, importError = state.error,
            onCaptured={ file -> previewMode=true;model.import(listOf(Uri.fromFile(file)),file) },onBack={ showAutoCamera=false })
        return
    }
    BackHandler { if(state.busy) model.cancel() else onBack() }
    ToolPage(Tool.SCAN, onBack, available, actions = {
        if (state.pages.isNotEmpty()) Button(enabled = available, onClick = { showExport = true }) { Text("导出") }
    }) {
        if (state.busy && !showText) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.phase, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = model::cancel) { Text("取消处理") }
        } else if (!state.busy && state.phase.isNotBlank() && state.results.isEmpty()) Text(state.phase)
        if (!showText) state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if(state.results.isNotEmpty()) {
            Text("已保存 ${state.results.size} 个文件",style=MaterialTheme.typography.titleMedium)
            state.results.forEach { result -> TextButton(onClick={
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(result.uri,result.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    .onFailure { model.error("文件已保存，当前没有能打开它的应用") }
            }) { Text(result.name) } }
            OutlinedButton(onClick={
                val items=state.results;val intent=if(items.size==1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM,items.first().uri)
                    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(items.map { it.uri }))
                intent.type=items.first().mime;intent.clipData=ClipData.newRawUri("扫描文件",items.first().uri).apply { items.drop(1).forEach { addItem(ClipData.Item(it.uri)) } }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(intent,"分享扫描文件")) }.onFailure { model.error("无法打开分享面板") }
            },modifier=Modifier.fillMaxWidth()) { Text("分享扫描文件") }
            state.results.firstOrNull { it.mime=="application/pdf" }?.let { pdf ->
                OutlinedButton(onClick={ runCatching { PdfPrinter.open(context,pdf.uri,pdf.name) }.onFailure { model.error("无法打开系统打印") } },
                    modifier=Modifier.fillMaxWidth()) { Text("打印扫描 PDF") }
            }
        }

        if (state.pages.isEmpty()) {
            Surface(onClick = { openCamera(true) }, enabled = available, shape = MaterialTheme.shapes.large,
                color = Color(0xFF214937), contentColor = Color(0xFFFBF6E8)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 134.dp).padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("自动识别扫描", style = MaterialTheme.typography.titleLarge)
                        Text("对准纸张，稳定后自动拍摄", style = MaterialTheme.typography.bodySmall)
                    }
                    Image(painterResource(R.drawable.scan_guide), null, Modifier.width(88.dp).aspectRatio(104f / 96))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = available, onClick = { openCamera(false) }) { Text("系统相机") }
                OutlinedButton(enabled = available, onClick = ::importImages) { Text("相册导入") }
            }
            Text("纸张四角完整入镜，识别更稳。支持多选照片，一次最多 20 页。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        } else {
            val pageIndex = state.pages.indexOfFirst { it.id == state.selectedId }
            var addMenu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("第 ${(pageIndex + 1).coerceAtLeast(1)} / ${state.pages.size} 页", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = available, onClick = { showPages = true }) { Text("管理页面") }
                Box {
                    OutlinedButton(enabled = available && state.pages.size < 20, onClick = { addMenu = true }) { Text("加页") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text("自动识别扫描") }, onClick = { addMenu = false; openCamera(true) })
                        DropdownMenuItem(text = { Text("系统相机") }, onClick = { addMenu = false; openCamera(false) })
                        DropdownMenuItem(text = { Text("相册导入") }, onClick = { addMenu = false; importImages() })
                    }
                }
            }
            val strip = rememberLazyListState()
            LaunchedEffect(pageIndex, state.pages.size) {
                if (state.pages.size > 1 && pageIndex >= 0) strip.scrollToItem(pageIndex)
            }
            if (state.pages.size > 1) LazyRow(state = strip, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
                    FilterChip(selected = page.id == state.selectedId, enabled = available, onClick = { model.select(page.id) },
                        label = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(model = page.source, contentDescription = null, modifier = Modifier.size(46.dp), contentScale = ContentScale.Fit)
                            Text("第 ${index+1} 页")
                        } })
                }
            }
        }
        state.selected?.let { page ->
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=!previewMode,onClick={ previewMode=false },label={ Text("调整纸边") })
                FilterChip(selected=previewMode,onClick={ previewMode=true },label={ Text("效果预览") })
            }
            if(!previewMode) {
                state.original?.let { image -> ScanCropper(image,page.settings.quad,corner,available,{ corner=it },model::corner) }
                Text("拖动角点时可放大查看纸边，也可用方向按钮微调。",style=MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("左上","右上","右下","左下").forEachIndexed { index,label ->
                        FilterChip(selected=corner==index,enabled=available,onClick={ corner=index },label={ Text(label) })
                    }
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("左" to (-.005f to 0f),"右" to (.005f to 0f),"上" to (0f to -.005f),"下" to (0f to .005f)).forEach { (label,delta) ->
                        OutlinedButton(enabled=available,onClick={ model.corner(corner,delta.first,delta.second) }) { Text(label) }
                    }
                    TextButton(enabled=available,onClick=model::detect) { Text("自动找边") }
                    TextButton(enabled=available,onClick=model::fullFrame) { Text("恢复全图") }
                }
                if(!page.detected) Text("自动纸边不明确时，请手动调整四角。",style=MaterialTheme.typography.bodySmall)
            } else {
                state.preview?.let { Image(bitmap=it.asImageBitmap(),contentDescription="校正后扫描预览",
                    contentScale=ContentScale.Fit,modifier=Modifier.fillMaxWidth().heightIn(max=430.dp)) }
                    ?: if (!state.busy && (state.error != null || state.original == null)) {
                        OutlinedButton(onClick = { model.reopen() }) { Text("重新打开页面") }
                    } else Text("正在生成预览…",style=MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ScanMode.entries.forEach { mode -> FilterChip(selected=page.settings.mode==mode,enabled=available,
                    onClick={ model.mode(mode);previewMode=true },label={ Text(mode.label) }) }
                OutlinedButton(enabled=available,onClick={ model.rotate();previewMode=true }) { Text("旋转 90°") }
            }
            if (state.pages.any { it.settings.mode != page.settings.mode }) TextButton(
                onClick = model::applyModeToAll, enabled = available) { Text("全部页面使用「${page.settings.mode.label}」") }
            OutlinedButton(enabled = available, modifier = Modifier.fillMaxWidth(), onClick = {
                showText = true
                if (state.text == null) model.recognize()
            }) { Text(if (state.text == null) "识别当前页文字" else "查看已识别文字") }
        }
    }
    if (showPages && state.pages.isNotEmpty()) ScanPageOrganizer(
        pages = state.pages, selected = state.selectedId, enabled = available,
        onClose = { showPages = false }, onOpen = { id -> showPages = false; model.select(id) },
        onMove = model::movePage, onDelete = { deleteId = it }, onNew = { confirmNew = true })
    if (showExport && state.pages.isNotEmpty()) ModalBottomSheet(onDismissRequest = { showExport = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        var pdf by rememberSaveable { mutableStateOf(true) }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("导出 ${state.pages.size} 页", style = MaterialTheme.typography.titleLarge)
            StudioTabs(listOf("一个 PDF", "逐页 JPG"), if (pdf) 0 else 1, onSelect = { pdf = it == 0 })
            OutlinedTextField(value = state.title, onValueChange = model::title, label = { Text("文件名称") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            StudioSection("导出清晰度")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScanQuality.entries.forEach { q -> FilterChip(selected = q == state.quality, onClick = { model.quality(q) }, label = { Text(q.label) }) }
            }
            Text(if (pdf) "按当前页序合成 PDF，保存到下载目录 Shiying。" else "每页保存一张 JPG，可在相册 Shiying 中查看。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { showExport = false; model.export(pdf) }, enabled = available,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (pdf) "保存 PDF" else "保存 ${state.pages.size} 张 JPG") }
        }
    }
    if (showText && state.selected != null) ModalBottomSheet(
        onDismissRequest = { showText = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        var confirmOcr by rememberSaveable { mutableStateOf(false) }
        var copied by remember(state.text) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("第 ${state.pages.indexOfFirst { it.id == state.selectedId } + 1} 页文字", style = MaterialTheme.typography.titleLarge)
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.phase)
                TextButton(onClick = model::cancel) { Text("取消识别") }
            } else {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val text = state.text
                if (text != null) {
                    OutlinedTextField(value = text, onValueChange = model::text, label = { Text("校对识别文字") },
                        minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth())
                    Button(enabled = text.isNotBlank(), onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("扫描文字", text))
                        copied = true
                    }) { Text(if (copied) "已复制" else "复制文字") }
                    TextButton(onClick = { confirmOcr = true }) { Text("重新识别") }
                } else OutlinedButton(onClick = model::recognize) { Text("重新识别") }
                TextButton(onClick = { showText = false }) { Text("返回页面") }
            }
        }
        if (confirmOcr) AlertDialog(onDismissRequest = { confirmOcr = false }, title = { Text("重新识别此页？") },
            text = { Text("识别成功后将替换当前文字。") },
            confirmButton = { TextButton(onClick = { confirmOcr = false; model.recognize() }) { Text("重新识别") } },
            dismissButton = { TextButton(onClick = { confirmOcr = false }) { Text("保留文字") } })
    }
    val deleteIndex = state.pages.indexOfFirst { it.id == deleteId }
    if (deleteIndex >= 0) AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text("删除第 ${deleteIndex + 1} 页？") },
        text = { Text("此页将从扫描草稿移除。") },
        confirmButton = { TextButton(enabled = available, onClick = {
            if (state.pages.size == 1) showPages = false
            model.remove(deleteId)
            deleteId = null
        }) { Text("删除此页") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("保留") } })
    if(confirmNew) AlertDialog(onDismissRequest={ confirmNew=false },title={ Text("新建扫描？") },
        text={ Text("将清空当前扫描草稿。已导出的文件和相册原图不受影响。") },
        confirmButton={ TextButton(onClick={ confirmNew=false;showPages=false;model.clearDraft();previewMode=false }) { Text("清空并新建") } },
        dismissButton={ TextButton(onClick={ confirmNew=false }) { Text("继续编辑") } })

}

@Composable
private fun ScanCropper(image:Bitmap,quad:ScanQuad,selected:Int,enabled:Boolean,onSelect:(Int)->Unit,onMove:(Int,Float,Float)->Unit) {
    var viewport by remember { mutableStateOf(IntSize(1,1)) }
    var dragging by remember(image, enabled) { mutableIntStateOf(-1) }
    val bitmapPaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val current by rememberUpdatedState(quad)
    fun rectangle():RectF {
        val factor=minOf(viewport.width.toFloat()/image.width,viewport.height.toFloat()/image.height)
        val w=image.width*factor;val h=image.height*factor
        return RectF((viewport.width-w)/2,(viewport.height-h)/2,(viewport.width+w)/2,(viewport.height+h)/2)
    }
    Canvas(Modifier.fillMaxWidth().height(340.dp).onSizeChanged { viewport=it }
        .semantics { contentDescription="扫描裁剪区域，可拖动四个角；下方提供角点选择及方向按钮。" }
        .pointerInput(image,enabled) {
            if(enabled) {
                var corner=-1
                detectDragGestures(onDragStart={ touch ->
                    val rect=rectangle()
                    corner=current.points.indices.minByOrNull { i ->
                        val p=current.points[i];(Offset(rect.left+p.x*rect.width(),rect.top+p.y*rect.height())-touch).getDistance()
                    } ?: -1
                    if(corner>=0) {
                        val p=current.points[corner]
                        if((Offset(rect.left+p.x*rect.width(),rect.top+p.y*rect.height())-touch).getDistance()>48.dp.toPx()) corner=-1
                        else { dragging = corner; onSelect(corner) }
                    }
                },onDragEnd={ corner=-1; dragging=-1 },onDragCancel={ corner=-1; dragging=-1 }) { change,delta ->
                    if(corner>=0) { change.consume();val rect=rectangle();onMove(corner,delta.x/rect.width(),delta.y/rect.height()) }
                }
            }
        }) {
        val rect=rectangle();drawContext.canvas.nativeCanvas.drawBitmap(image,null,rect,bitmapPaint)
        val points=quad.points.map { Offset(rect.left+it.x*rect.width(),rect.top+it.y*rect.height()) }
        val outside=Path().apply {
            fillType=PathFillType.EvenOdd;addRect(androidx.compose.ui.geometry.Rect(rect.left,rect.top,rect.right,rect.bottom))
            moveTo(points[0].x,points[0].y);points.drop(1).forEach { lineTo(it.x,it.y) };close()
        }
        drawPath(outside,Color.Black.copy(alpha=.45f))
        val line=Path().apply { moveTo(points[0].x,points[0].y);points.drop(1).forEach { lineTo(it.x,it.y) };close() }
        val blue=Color(0xff3567df)
        drawPath(line,blue,style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        points.forEachIndexed { index,p -> drawCircle(if(index==selected) blue else Color.White,10.dp.toPx(),p)
            drawCircle(blue,10.dp.toPx(),p,style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
        if (enabled) points.getOrNull(dragging)?.let { point ->
            val radius = minOf(43.dp.toPx(), size.width / 4f, size.height / 4f)
            val margin = 6.dp.toPx()
            val center = Offset(if (point.x < size.width / 2) size.width - radius - margin else radius + margin, radius + margin)
            val lens = android.graphics.Path().apply { addCircle(center.x, center.y, radius, android.graphics.Path.Direction.CW) }
            drawContext.canvas.nativeCanvas.apply {
                save()
                clipPath(lens)
                drawColor(android.graphics.Color.DKGRAY)
                translate(center.x, center.y)
                scale(2.5f, 2.5f)
                translate(-point.x, -point.y)
                drawBitmap(image, null, rect, bitmapPaint)
                restore()
            }
            drawCircle(Color.White, radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            for (color in listOf(Color.Black, Color.White)) {
                val width = if (color == Color.Black) 3.dp.toPx() else 1.dp.toPx()
                val dx = Offset(9.dp.toPx(), 0f)
                val dy = Offset(0f, 9.dp.toPx())
                drawLine(color, center - dx, center + dx, width)
                drawLine(color, center - dy, center + dy, width)
            }
        }
    }
}
