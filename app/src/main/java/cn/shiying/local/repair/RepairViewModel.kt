package cn.shiying.local.repair

import android.app.Application
import android.graphics.*
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.shiying.local.conversion.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class RepairState(val source:Bitmap?=null,val preview:Bitmap?=null,val strokes:List<RepairStroke> = emptyList(),
    val undone: List<RepairStroke> = emptyList(),
    val busy:Boolean=false,val phase:String="",val error:String?=null,val result:ConvertedFile?=null)
class RepairViewModel(app:Application):AndroidViewModel(app) {
    private val mutable=MutableStateFlow(RepairState())
    val state=mutable.asStateFlow()
    private var task:Job?=null
    fun select(uri:Uri) {
        if(state.value.busy)return
        mutable.update { it.copy(busy=true,error=null,phase="正在读取图片…") }
        task=viewModelScope.launch {
            try {
                val source=withContext(Dispatchers.IO) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(getApplication<Application>().contentResolver,uri)) { decoder,info,_ ->
                        require(!info.isAnimated) { "局部修复暂只支持静态图片，动图请使用原格式保存" }
                        require(info.size.width.toLong()*info.size.height<=64_000_000) { "图片超过 6400 万像素" }
                        val scale=minOf(1.0,4096.0/maxOf(info.size.width,info.size.height),kotlin.math.sqrt(12_000_000.0/info.size.width/info.size.height))
                        decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()))
                        decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    }
                }
                mutable.value=RepairState(source=source)
            } catch(e:CancellationException) { throw e }
            catch(_:OutOfMemoryError) { fail("内存不足，请缩小图片") }
            catch(e:Exception) { fail(e.message ?: "图片读取失败") }
        }
    }
    fun stroke(value: RepairStroke) {
        mutable.update {
            when {
                it.busy || it.source == null -> it
                it.strokes.size >= 100 -> it.copy(error = "最多保留 100 笔，请撤销一笔或生成修复预览")
                else -> it.copy(strokes = it.strokes + value, undone = emptyList(), preview = null, error = null, result = null)
            }
        }
    }
    fun undo() {
        mutable.update { state ->
            val last = state.strokes.lastOrNull()
            if (state.busy || last == null) state else state.copy(strokes = state.strokes.dropLast(1),
                undone = state.undone + last, preview = null, error = null)
        }
    }
    fun redo() {
        mutable.update { state ->
            val last = state.undone.lastOrNull()
            if (state.busy || last == null) state else state.copy(strokes = state.strokes + last,
                undone = state.undone.dropLast(1), preview = null, error = null)
        }
    }
    fun clear() {
        if (!state.value.busy) mutable.update { it.copy(strokes = emptyList(), undone = emptyList(), preview = null, error = null) }
    }
    fun repair() {
        val snapshot=state.value;val source=snapshot.source ?: return
        if(snapshot.busy || snapshot.strokes.isEmpty())return
        mutable.update { it.copy(busy=true,error=null,phase="正在本地修复…") }
        task=viewModelScope.launch {
            try {
                val image=withContext(Dispatchers.Default) { val ctx=currentCoroutineContext();ImageRepair.repair(source,snapshot.strokes) { ctx.ensureActive() } }
                mutable.update { it.copy(busy=false,preview=image,phase="") }
            } catch(e:CancellationException) { throw e }
            catch(_:OutOfMemoryError) { fail("内存不足，请缩小图片") }
            catch(e:Exception) { fail(e.message ?: "修复失败") }
        }
    }
    fun save() {
        val snapshot=state.value;val image=snapshot.preview ?: return
        if(snapshot.busy)return
        mutable.update { it.copy(busy=true,error=null,phase="正在保存…") }
        task=viewModelScope.launch {
            try {
                val output=withContext(Dispatchers.IO) {
                    val file=File.createTempFile("repair-",".png",getApplication<Application>().cacheDir)
                    try { file.outputStream().use { require(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
                        ensureActive();FileConverter(getApplication()).publish(file,"拾影_图片修复_${System.currentTimeMillis()}.png",OutputFormat.PNG)
                    } finally { file.delete() }
                }
                mutable.value=RepairState(result=output,phase="已保存，输入已清空")
            } catch(e:CancellationException) { throw e }
            catch(_:OutOfMemoryError) { fail("内存不足，请稍后保存") }
            catch(e:Exception) { fail(e.message ?: "保存失败") }
        }
    }
    private fun fail(message:String) { mutable.update { it.copy(busy=false,error=message,phase="") } }
    fun cancel() { task?.cancel();mutable.update { it.copy(busy=false,phase="已取消") } }
}
