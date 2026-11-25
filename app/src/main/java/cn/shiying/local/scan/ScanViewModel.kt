package cn.shiying.local.scan

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import cn.shiying.local.conversion.ConvertedFile
import cn.shiying.local.conversion.FileConverter
import cn.shiying.local.conversion.OutputFormat
import cn.shiying.local.documents.LocalOcr
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

data class ScanPage(val id:String,val source:File,val settings:ScanSettings=ScanSettings(),val detected:Boolean=false,
    val recognizedText:String?=null)
data class ScanState(val pages:List<ScanPage> = emptyList(),val selectedId:String?=null,val original:Bitmap?=null,
    val preview:Bitmap?=null,val busy:Boolean=false,val phase:String="",val error:String?=null,
    val title:String="扫描文档",val quality:ScanQuality=ScanQuality.ULTRA,val results:List<ConvertedFile> = emptyList(),val capturing:Boolean=false) {
    val selected get()=pages.firstOrNull { it.id==selectedId }
    val text get()=selected?.recognizedText
}

class ScanViewModel(application:Application,private val saved:SavedStateHandle):AndroidViewModel(application) {
    private val sessionId=saved.get<String>("scan-session")?.takeIf { it.matches(Regex("[0-9a-f-]{36}")) } ?: UUID.randomUUID().toString().also { saved["scan-session"]=it }
    private val directory=File(application.cacheDir,"scan-session-$sessionId").apply { mkdirs() }
    private val captureDirectory=File(application.cacheDir,"scan-capture").apply { mkdirs() }
    private val mutable=MutableStateFlow(ScanState(pages=restore(),title=saved.get<String>("scan-title") ?: "扫描文档",quality=ScanQuality.entries.firstOrNull { it.name==saved.get<String>("scan-quality") } ?: ScanQuality.ULTRA,capturing=saved.get<String>("scan-capture")!=null))
    val state=mutable.asStateFlow()
    private var task:Job?=null
    private var previewTask:Job?=null
    init {
        val pages = state.value.pages
        (pages.firstOrNull { it.id == saved.get<String>("scan-selected") } ?: pages.firstOrNull())?.let { select(it.id) }
    }

    private fun restore():List<ScanPage> = runCatching {
        val array=JSONArray(saved.get<String>("scan-pages") ?: "[]")
        (0 until minOf(array.length(),20)).mapNotNull { index ->
            val item=array.getJSONObject(index);val id=item.getString("id")
            if(!id.matches(Regex("[0-9a-f-]{36}"))) return@mapNotNull null
            val file=File(directory,"$id.image").takeIf { it.isFile } ?: File(directory,"$id.jpg");if(!file.isFile) return@mapNotNull null
            val list=item.getJSONArray("points")
            val quad=ScanQuad((0 until list.length()).map { val p=list.getJSONArray(it);ScanPoint(p.getDouble(0).toFloat(),p.getDouble(1).toFloat()) })
            val mode=ScanMode.entries.firstOrNull { it.name==item.optString("mode") } ?: ScanMode.ENHANCE
            ScanPage(id,file,ScanSettings(quad.takeIf { it.valid() } ?: ScanQuad(),mode,item.optInt("turns")%4),item.optBoolean("detected"))
        }
    }.getOrDefault(emptyList())
    private fun persist() {
        saved["scan-pages"]=JSONArray().apply { state.value.pages.forEach { page -> put(JSONObject().apply {
            put("id",page.id);put("mode",page.settings.mode.name);put("turns",page.settings.turns);put("detected",page.detected)
            put("points",JSONArray().apply { page.settings.quad.points.forEach { put(JSONArray().put(it.x).put(it.y)) } })
        }) } }.toString()
    }
    fun error(message:String) { mutable.update { it.copy(error=message,busy=false,phase="") } }
    fun title(value:String) { if(!state.value.busy) { mutable.update { it.copy(title=value.take(60)) };saved["scan-title"]=state.value.title } }
    fun quality(value:ScanQuality) { if(!state.value.busy) { mutable.update { it.copy(quality=value,results=emptyList()) };saved["scan-quality"]=value.name } }
    fun automaticCaptureFile():File {
        check(!state.value.busy && state.value.pages.size<20) { "请先结束当前操作，一次最多 20 页" }
        return File.createTempFile("capture-",".jpg",captureDirectory)
    }
    fun text(value:String) {
        if (state.value.busy) return
        mutable.update { s -> s.copy(pages = s.pages.map { if (it.id == s.selectedId) it.copy(recognizedText = value) else it }) }
    }

    fun newCapture():Uri {
        check(!state.value.busy && !state.value.capturing && state.value.pages.size<20) { "请先结束当前操作，一次最多 20 页" }
        val file=File.createTempFile("capture-",".jpg",captureDirectory)
        saved["scan-capture"]=file.absolutePath;mutable.update { it.copy(capturing=true,error=null) }
        return FileProvider.getUriForFile(getApplication(),"${getApplication<Application>().packageName}.capture",file)
    }
    fun captureResult(success:Boolean) {
        val path=saved.remove<String>("scan-capture");mutable.update { it.copy(capturing=false) }
        val file=path?.let(::File)?.takeIf { it.canonicalPath.startsWith(captureDirectory.canonicalPath+File.separator) }
        if(file==null) { if(success) error("拍照文件不可读取，请重新拍摄");return }
        if(!success || !file.isFile || file.length()==0L) { file.delete();return }
        import(listOf(Uri.fromFile(file)),file)
    }
    fun import(uris:List<Uri>,capture:File?=null) {
        if(state.value.busy || uris.isEmpty()) return
        if(state.value.pages.size+uris.size>20) { capture?.delete();error("一次最多 20 页，请减少选择的图片");return }
        previewTask?.cancel();mutable.update { it.copy(busy=true,phase="正在导入照片…",error=null) }
        task=viewModelScope.launch {
            val created=mutableListOf<ScanPage>()
            val createdFiles=mutableListOf<File>()
            try {
                withContext(Dispatchers.IO) {
                    uris.forEachIndexed { index,uri ->
                        ensureActive();mutable.update { it.copy(phase="正在导入 ${index+1}/${uris.size}…") }
                        val id=UUID.randomUUID().toString();val file=File(directory,"$id.image");createdFiles+=file
                        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use { output ->
                                val buffer=ByteArray(64*1024);var count=0L
                                while(true) { ensureActive();val n=input.read(buffer);if(n<0)break;count+=n
                                    require(count<=128L*1024*1024) { "单张原图超过 128 MB" };output.write(buffer,0,n) }
                            }
                        } ?: throw java.io.IOException("无法读取原始照片")
                        require((state.value.pages.sumOf { it.source.length() }+createdFiles.sumOf { it.length() })<=512L*1024*1024) { "扫描原片总计超过 512 MB，请分批处理" }
                        val bitmap=ScanProcessor.load(file)
                        try {
                            val quad=ScanProcessor.detect(bitmap)
                            created+=ScanPage(id,file,ScanSettings(quad ?: ScanQuad()),quad!=null)
                        } finally { bitmap.recycle() }
                    }
                    ensureActive()
                }
                mutable.update { it.copy(pages=it.pages+created,busy=false,phase="",results=emptyList()) };persist()
                select(created.last().id)
            } catch(e:CancellationException) { createdFiles.forEach { it.delete() };throw e }
            catch(_:OutOfMemoryError) { createdFiles.forEach { it.delete() };error("内存不足，请减少页面或缩小照片") }
            catch(e:Exception) { createdFiles.forEach { it.delete() };error(e.message ?: "导入失败，请重新选择") }
            finally { capture?.delete() }
        }
    }
    fun reopen() {
        val id = state.value.selectedId ?: return
        if (state.value.original == null) select(id) else refreshPreview()
    }
    fun select(id:String) {
        if(state.value.busy) return
        val page=state.value.pages.firstOrNull { it.id==id } ?: return
        if (state.value.selectedId == id && state.value.original != null) return
        saved["scan-selected"] = id
        previewTask?.cancel();mutable.update { it.copy(selectedId=id,busy=true,original=null,preview=null,phase="正在打开页面…",error=null) }
        task=viewModelScope.launch {
            try {
                val bitmap=withContext(Dispatchers.IO) { ScanProcessor.load(page.source) }
                mutable.update { it.copy(original=bitmap,busy=false,phase="") };refreshPreview()
            } catch(e:CancellationException) { throw e }
            catch(_:OutOfMemoryError) { error("内存不足，无法打开页面") }
            catch(e:Exception) { error(e.message ?: "无法打开页面") }
        }
    }
    private fun change(settings:(ScanSettings)->ScanSettings) {
        if(state.value.busy) return
        val selected=state.value.selectedId ?: return
        mutable.update { it.copy(pages=it.pages.map { p -> if(p.id==selected) p.copy(settings=settings(p.settings)) else p },results=emptyList(),error=null,phase="") }
        persist();refreshPreview()
    }
    fun corner(index:Int,dx:Float,dy:Float)=change { it.copy(quad=it.quad.moved(index,dx,dy)) }
    fun fullFrame()=change { it.copy(quad=ScanQuad()) }
    fun mode(value:ScanMode)=change { it.copy(mode=value) }
    fun rotate()=change { it.copy(turns=(it.turns+1)%4) }
    fun applyModeToAll() {
        val mode = state.value.selected?.settings?.mode ?: return
        if (state.value.busy) return
        mutable.update { s -> s.copy(pages = s.pages.map { it.copy(settings = it.settings.copy(mode = mode)) },
            phase = "已将「${mode.label}」应用到全部 ${s.pages.size} 页", error = null) }
        persist()
    }
    fun detect() {
        val image = state.value.original ?: return
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, phase = "正在查找纸边…", error = null) }
        task = viewModelScope.launch {
            try {
                val quad = withContext(Dispatchers.Default) { ScanProcessor.detect(image) }
                if (quad == null) {
                    error("未找到清晰纸边。可拖动四角调整。")
                } else {
                    mutable.update { s -> s.copy(busy = false, phase = "", pages = s.pages.map { p ->
                        if (p.id == s.selectedId) p.copy(settings = p.settings.copy(quad = quad), detected = true) else p
                    }) }
                    persist()
                    refreshPreview()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { error("内存不足，请手动调整纸边") }
            catch (e: Exception) { error(e.message ?: "查找纸边失败，请手动调整") }
        }
    }
    private fun refreshPreview() {
        previewTask?.cancel();val raw=state.value.original ?: return;val page=state.value.selected ?: return
        mutable.update { it.copy(preview=null) }
        previewTask=viewModelScope.launch {
            delay(140)
            try {
                val preview=withContext(Dispatchers.Default) { val ctx=currentCoroutineContext();ScanProcessor.render(raw,page.settings) { ctx.ensureActive() } }
                mutable.update { if(it.selectedId==page.id && it.selected?.settings==page.settings) it.copy(preview=preview) else it }
            } catch(e:CancellationException) { throw e }
            catch(_:OutOfMemoryError) { mutable.update { it.copy(error="内存不足，无法生成预览") } }
            catch(e:Exception) { mutable.update { it.copy(error=e.message ?: "预览失败") } }
        }
    }
    fun movePage(id: String, destination: Int) {
        val current = state.value
        val index = current.pages.indexOfFirst { it.id == id }
        if (current.busy || current.capturing || index < 0 || destination !in current.pages.indices || index == destination) return
        val reordered = current.pages.toMutableList()
        reordered.add(destination, reordered.removeAt(index))
        mutable.update { it.copy(pages = reordered, results = emptyList(), phase = "", error = null) }
        persist()
    }

    fun remove(id: String? = state.value.selectedId) {
        val current = state.value
        val index = current.pages.indexOfFirst { it.id == id }
        if (current.busy || current.capturing || index < 0) return
        val remaining = current.pages.filterNot { it.id == id }
        val selectedRemoved = current.selectedId == id
        if (selectedRemoved) previewTask?.cancel()
        mutable.update {
            val next = it.copy(pages = remaining, results = emptyList(), phase = "", error = null)
            if (selectedRemoved) next.copy(selectedId = null, original = null, preview = null) else next
        }
        current.pages[index].source.delete()
        persist()
        if (selectedRemoved) {
            saved.remove<String>("scan-selected")
            remaining.getOrNull(minOf(index, remaining.lastIndex))?.let { select(it.id) }
        }
    }
    fun clearDraft() {
        if(state.value.busy || state.value.capturing) return
        previewTask?.cancel();val pages=state.value.pages
        mutable.value=ScanState()
        pages.forEach { it.source.delete() }
        saved["scan-title"]="扫描文档";persist()
    }
    fun recognize() {
        val page=state.value.selected ?: return;val settings=page.settings
        if(state.value.busy) return
        previewTask?.cancel();mutable.update { it.copy(busy=true,phase="正在离线识别中英文…",error=null) }
        task=viewModelScope.launch {
            try {
                val text=withContext(Dispatchers.Default) {
                    val ctx=currentCoroutineContext();val source=ScanProcessor.loadForExport(page.source)
                    val bitmap=try { ScanProcessor.render(source,settings,ScanQuality.HIGH.edge,ScanQuality.HIGH.pixels,highQuality=true) { ctx.ensureActive() } } finally { source.recycle() }
                    try { val engine=LocalOcr.create(getApplication());try { engine.setImage(bitmap);engine.utF8Text.orEmpty().trim() } finally { engine.recycle() } }
                    finally { bitmap.recycle() }
                }
                mutable.update { s -> s.copy(busy = false, phase = "", pages = s.pages.map { p ->
                    if (p.id == page.id && text.isNotBlank()) p.copy(recognizedText = text) else p
                }, error = if (text.isBlank()) "没有识别到文字，请检查方向和清晰度" else null) }
                refreshPreview()
            } catch(e:CancellationException) { throw e }
            catch(_:OutOfMemoryError) { error("内存不足，请稍后识别") }
            catch(_:LinkageError) { error("此设备无法加载离线 OCR") }
            catch(e:Exception) { error(e.message ?: "文字识别失败") }
        }
    }
    fun export(pdf:Boolean) {
        val snapshot=state.value
        if(snapshot.busy || snapshot.pages.isEmpty()) return
        previewTask?.cancel();mutable.update { it.copy(busy=true,phase="正在准备扫描文件…",error=null,results=emptyList()) }
        task=viewModelScope.launch {
            val outputs=mutableListOf<ConvertedFile>()
            val work=File(directory,"export-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                withContext(Dispatchers.IO) {
                    val ctx=currentCoroutineContext();val images=mutableListOf<ScanJpeg>()
                    snapshot.pages.forEachIndexed { index,page ->
                        ensureActive();mutable.update { it.copy(phase="正在处理 ${index+1}/${snapshot.pages.size} 页…") }
                        val raw=ScanProcessor.loadForExport(page.source)
                        try { val result=ScanProcessor.render(raw,page.settings,snapshot.quality.edge,snapshot.quality.pixels,highQuality=true) { ctx.ensureActive() }
                            try { val target=File(work,"page-${index+1}.jpg");ScanProcessor.jpeg(result,target,if(snapshot.quality==ScanQuality.ULTRA)100 else 98);images+=ScanJpeg(target,result.width,result.height) }
                            finally { result.recycle() }
                        } finally { raw.recycle() }
                    }
                    val name=snapshot.title.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"),"_").trim().take(60).ifBlank { "扫描文档" }
                    val stamp=System.currentTimeMillis();val converter=FileConverter(getApplication())
                    if(pdf) {
                        val target=File(work,"scan.pdf");ScanPdf.write(images,target,name) { ctx.ensureActive() }
                        outputs+=converter.publish(target,"${name}_${stamp}.pdf",OutputFormat.PDF)
                    } else images.forEachIndexed { index,image -> outputs+=converter.publish(image.file,"${name}_${stamp}_${index+1}.jpg",OutputFormat.JPG) }
                    ensureActive()
                }
                snapshot.pages.forEach { it.source.delete() }
                mutable.value=ScanState(quality=snapshot.quality,phase="导出完成，输入已清空",results=outputs.toList())
                saved["scan-title"]="扫描文档";persist()
            } catch(e:CancellationException) { outputs.forEach { runCatching { getApplication<Application>().contentResolver.delete(it.uri,null,null) } };throw e }
            catch(_:OutOfMemoryError) { outputs.forEach { runCatching { getApplication<Application>().contentResolver.delete(it.uri,null,null) } };error("内存不足，请减少扫描页数") }
            catch(e:Exception) { outputs.forEach { runCatching { getApplication<Application>().contentResolver.delete(it.uri,null,null) } };error(e.message ?: "导出失败") }
            finally { work.deleteRecursively() }
        }
    }
    fun cancel() { task?.cancel();previewTask?.cancel();mutable.update { it.copy(busy=false,phase="已取消",error=null) };refreshPreview() }
}
