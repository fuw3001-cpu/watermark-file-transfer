package cn.shiying.local.conversion

import android.app.Application
import android.graphics.*
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shiying.local.scan.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanDeviceTest {
    @Test fun highQualityNativeWarpKeepsFullFrameFinePixelsAndOriginal() {
        assertTrue("OpenCV JNI must load for the high quality export path",OpenCvPaperDetector.available())
        val input=Bitmap.createBitmap(120,160,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(120*160) { i -> if((i%120)%3==0 || (i/120)%7==0)Color.BLACK else Color.WHITE }
        input.setPixels(pixels,0,120,0,0,120,160)
        val result=ScanProcessor.render(input,ScanSettings(mode=ScanMode.ORIGINAL),highQuality=true)
        try {
            assertEquals(input.width,result.width);assertEquals(input.height,result.height)
            val actual=IntArray(pixels.size);result.getPixels(actual,0,120,0,0,120,160)
            assertArrayEquals(pixels,actual)
            assertEquals(Color.BLACK,input.getPixel(0,0))
        } finally { input.recycle();result.recycle() }
    }
    @Test fun highQualityExportFlattensTransparentPaperToWhite() {
        assertTrue(OpenCvPaperDetector.available())
        val input=Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888)
        Canvas(input).drawRect(10f,10f,30f,30f,Paint().apply { color=Color.BLACK })
        val output=ScanProcessor.render(input,ScanSettings(mode=ScanMode.ORIGINAL),highQuality=true)
        try {
            assertEquals(Color.WHITE,output.getPixel(2,2));assertEquals(Color.BLACK,output.getPixel(20,20))
            assertEquals(Color.TRANSPARENT,input.getPixel(2,2))
        } finally { input.recycle();output.recycle() }
    }
    @Test fun perspectiveCorrectionKeepsCornerColoursAndRotationSwapsDimensions() {
        val input=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        val paper=Bitmap.createBitmap(100,140,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas=Canvas(paper);val paint=Paint()
        listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW).forEachIndexed { i,color ->
            paint.color=color
            val rect=listOf(RectF(0f,0f,25f,35f),RectF(75f,0f,100f,35f),RectF(75f,105f,100f,140f),RectF(0f,105f,25f,140f))[i]
            canvas.drawRect(rect,paint)
        }
        val quad=ScanQuad(listOf(ScanPoint(.25f,.08f),ScanPoint(.85f,.2f),ScanPoint(.75f,.9f),ScanPoint(.15f,.8f)))
        val matrix=Matrix().apply { assertTrue(setPolyToPoly(floatArrayOf(0f,0f,100f,0f,100f,140f,0f,140f),0,
            quad.points.flatMap { listOf(it.x*400,it.y*300) }.toFloatArray(),0,4)) }
        Canvas(input).drawBitmap(paper,matrix,Paint(Paint.FILTER_BITMAP_FLAG))
        val settings=ScanSettings(quad,ScanMode.ORIGINAL)
        val result=ScanProcessor.render(input,settings);val rotated=ScanProcessor.render(input,settings.copy(turns=1))
        try {
            listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW).forEachIndexed { i,color ->
                val point=listOf(.1f to .1f,.9f to .1f,.9f to .9f,.1f to .9f)[i]
                assertEquals(color,result.getPixel((result.width*point.first).toInt(),(result.height*point.second).toInt()))
            }
            assertEquals(result.width,rotated.height);assertEquals(result.height,rotated.width)
            assertEquals(Color.DKGRAY,input.getPixel(0,0))
        } finally { input.recycle();paper.recycle();result.recycle();rotated.recycle() }
    }

    @Test fun successfulConversionClearsSelectedFileButFailureKeepsIt() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val valid=File.createTempFile("selection-",".png",app.cacheDir)
        val invalid=File.createTempFile("selection-",".bin",app.cacheDir).apply { writeText("not an image") }
        val bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        valid.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        val model=withContext(Dispatchers.Main) { ConversionViewModel(app) }
        var result:ConvertedFile?=null
        try {
            withContext(Dispatchers.Main) { model.select(Uri.fromFile(valid)) }
            withTimeout(15000) { model.state.first { !it.busy && it.source!=null } }
            val selected = model.state.value.source
            withContext(Dispatchers.Main) { model.kind(ConversionKind.IMAGE) }
            assertEquals(selected, model.state.value.source)
            withContext(Dispatchers.Main) { model.convert() }
            val success=withTimeout(15000) { model.state.first { !it.busy && it.result!=null } }
            result=success.result;assertNull(success.source);assertNotNull(success.result)
            withContext(Dispatchers.Main) { model.select(Uri.fromFile(invalid)) }
            withTimeout(15000) { model.state.first { !it.busy && it.source!=null } }
            withContext(Dispatchers.Main) { model.convert() }
            val failure=withTimeout(15000) { model.state.first { !it.busy && it.error!=null } }
            assertNotNull(failure.source);assertNull(failure.result)
        } finally {
            withContext(Dispatchers.Main) { model.cancel() }
            result?.let { app.contentResolver.delete(it.uri,null,null) };valid.delete();invalid.delete()
        }
    }
}
