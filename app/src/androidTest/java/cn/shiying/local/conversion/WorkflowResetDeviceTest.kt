package cn.shiying.local.conversion

import android.app.Application
import android.graphics.*
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shiying.local.documents.MergeViewModel
import cn.shiying.local.meme.MemeViewModel
import cn.shiying.local.scan.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkflowResetDeviceTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    @Test fun savedMemeClearsSubjectTemplateAndTextWhileKeepingResult()=runBlocking {
        val file=File.createTempFile("meme-reset-",".png",app.cacheDir)
        val image=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        Canvas(image).drawCircle(32f,32f,22f,Paint().apply { color=Color.RED })
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        val model=withContext(Dispatchers.Main) { MemeViewModel(app) }
        var uri:Uri?=null
        try {
            withContext(Dispatchers.Main) { model.source(Uri.fromFile(file)) }
            withTimeout(20000) { model.state.first { !it.busy && it.subject!=null } }
            withContext(Dispatchers.Main) { model.customTemplate(Uri.fromFile(file)) }
            withTimeout(10000) { model.state.first { !it.busy && it.customTemplate!=null } }
            withContext(Dispatchers.Main) { model.select(1);model.text("保存后清空");model.save() }
            val done=withTimeout(20000) { model.state.first { !it.busy && it.result!=null } }
            uri=done.result!!.uri
            assertNull(done.original);assertNull(done.cutout);assertNull(done.customTemplate)
            assertTrue(done.project.texts.all { it.text.isEmpty() })
        } finally { withContext(Dispatchers.Main) { model.cancel() };uri?.let { app.contentResolver.delete(it,null,null) };file.delete() }
    }
    @Test fun scannerKeepsOriginalBytesExportsHighResolutionAndClearsDraft()=runBlocking {
        val file=File.createTempFile("scan-high-",".png",app.cacheDir)
        val image=Bitmap.createBitmap(4000,2800,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(image).drawText("HIGH RESOLUTION",200f,500f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=110f })
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        val original=file.readBytes();val model=withContext(Dispatchers.Main) { ScanViewModel(app,SavedStateHandle()) }
        var uri:Uri?=null
        try {
            withContext(Dispatchers.Main) { model.import(listOf(Uri.fromFile(file))) }
            val ready=withTimeout(30000) { model.state.first { !it.busy && it.original!=null } }
            assertTrue(ready.original!!.width<=1600)
            assertArrayEquals(original,ready.pages.single().source.readBytes())
            withContext(Dispatchers.Main) { model.fullFrame();model.mode(ScanMode.ORIGINAL);model.quality(ScanQuality.HIGH);model.export(false) }
            val done=withTimeout(45000) { model.state.first { !it.busy && it.results.isNotEmpty() } }
            uri=done.results.single().uri
            assertTrue(done.pages.isEmpty());assertNull(done.original);assertNull(done.text)
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            app.contentResolver.openInputStream(uri!!).use { BitmapFactory.decodeStream(it,null,options) }
            assertTrue(options.outWidth>=3400 && options.outHeight>2000)
            assertArrayEquals(original,file.readBytes())
        } finally { withContext(Dispatchers.Main) { model.cancel();model.clearDraft() };uri?.let { app.contentResolver.delete(it,null,null) };file.delete() }
    }
    @Test fun mergingClearsInputsOnlyOnSuccess()=runBlocking {
        val folder=File(app.cacheDir,"scan-capture").apply { mkdirs() }
        val file=File.createTempFile("merge-reset-",".pdf",folder)
        PDDocument().use { it.addPage(PDPage());it.save(file) }
        val input=FileProvider.getUriForFile(app,"${app.packageName}.capture",file)
        val model=withContext(Dispatchers.Main) { MergeViewModel(app) }
        var result:Uri?=null
        try {
            withContext(Dispatchers.Main) { model.add(listOf(input)) }
            withTimeout(15000) { model.state.first { !it.busy && it.files.isNotEmpty() } }
            withContext(Dispatchers.Main) { model.rename("测试合并");model.merge() }
            val done=withTimeout(20000) { model.state.first { !it.busy && it.result!=null } }
            result=done.result!!.file.uri
            assertTrue(done.files.isEmpty());assertEquals("合并文档",done.name)
            file.writeText("invalid PDF")
            withContext(Dispatchers.Main) { model.add(listOf(input)) }
            withTimeout(15000) { model.state.first { !it.busy && it.files.isNotEmpty() } }
            withContext(Dispatchers.Main) { model.merge() }
            val failed=withTimeout(15000) { model.state.first { !it.busy && it.error!=null } }
            assertTrue(failed.files.isNotEmpty());assertNull(failed.result)
        } finally { withContext(Dispatchers.Main) { model.cancel() };result?.let { app.contentResolver.delete(it,null,null) };file.delete() }
    }
}
