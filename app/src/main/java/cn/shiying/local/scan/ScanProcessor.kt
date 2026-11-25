package cn.shiying.local.scan

import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.File
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.core.Core
import org.opencv.imgproc.Imgproc

object ScanProcessor {
    private fun decodeSource(source:ImageDecoder.Source,maxEdge:Int,maxPixels:Int):Bitmap = ImageDecoder.decodeBitmap(source) { decoder,info,_ ->
        require(info.size.width.toLong()*info.size.height<=64_000_000) { "照片超过 6400 万像素，请先缩小" }
        val scale=minOf(1.0,maxEdge.toDouble()/maxOf(info.size.width,info.size.height),kotlin.math.sqrt(maxPixels.toDouble()/(info.size.width.toDouble()*info.size.height)))
        decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()))
        decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }
    fun decode(context:Context,uri:Uri,maxEdge:Int=1600,maxPixels:Int=3_000_000):Bitmap = decodeSource(ImageDecoder.createSource(context.contentResolver,uri),maxEdge,maxPixels)
    fun load(file:File,maxEdge:Int=1600,maxPixels:Int=3_000_000):Bitmap = decodeSource(ImageDecoder.createSource(file),maxEdge,maxPixels)
    // Keep the source budget independent of output quality: resize once, after cropping the paper.
    fun loadForExport(file:File):Bitmap = load(file,6000,12_000_000)
    fun detect(input:Bitmap):ScanQuad? {
        OpenCvPaperDetector.detect(input)?.let { return it }
        val ratio=minOf(1f,320f/maxOf(input.width,input.height))
        val small=Bitmap.createScaledBitmap(input,maxOf(1,(input.width*ratio).roundToInt()),maxOf(1,(input.height*ratio).roundToInt()),true)
        try {
            val pixels=IntArray(small.width*small.height);small.getPixels(pixels,0,small.width,0,0,small.width,small.height)
            val gray=IntArray(pixels.size) { val c=pixels[it];((c shr 16 and 255)*77+(c shr 8 and 255)*150+(c and 255)*29) shr 8 }
            return PaperEdges.detect(small.width,small.height,gray)
        } finally { if(small!==input)small.recycle() }
    }
    fun jpeg(bitmap:Bitmap,file:File,quality:Int=98) {
        if(!bitmap.hasAlpha()) {
            file.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG,quality,it)) { "无法写入扫描图片" } }
        } else {
            val flat=Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
            try { Canvas(flat).apply { drawColor(Color.WHITE);drawBitmap(bitmap,0f,0f,null) }
                file.outputStream().use { require(flat.compress(Bitmap.CompressFormat.JPEG,quality,it)) { "无法写入扫描图片" } }
            } finally { flat.recycle() }
        }
    }
    private fun warpCubic(input:Bitmap,output:Bitmap,quad:ScanQuad):Boolean {
        if(input.width<2 || input.height<2 || output.width<2 || output.height<2 || !OpenCvPaperDetector.available()) return false
        val source=Mat();val target=Mat()
        val from=MatOfPoint2f(*quad.points.map { Point((it.x*(input.width-1)).toDouble(),(it.y*(input.height-1)).toDouble()) }.toTypedArray())
        val to=MatOfPoint2f(Point(0.0,0.0),Point((output.width-1).toDouble(),0.0),Point((output.width-1).toDouble(),(output.height-1).toDouble()),Point(0.0,(output.height-1).toDouble()))
        var transform:Mat?=null
        try {
            Utils.bitmapToMat(input,source)
            val matrix=Imgproc.getPerspectiveTransform(from,to);transform=matrix
            Imgproc.warpPerspective(source,target,matrix,Size(output.width.toDouble(),output.height.toDouble()),Imgproc.INTER_CUBIC,Core.BORDER_REPLICATE)
            Utils.matToBitmap(target,output)
            return true
        } finally { source.release();target.release();from.release();to.release();transform?.release() }
    }
    fun render(input:Bitmap,settings:ScanSettings,maxEdge:Int=1600,maxPixels:Int=3_000_000,highQuality:Boolean=false,checkCancelled:()->Unit={}):Bitmap {
        checkCancelled();require(settings.quad.valid()) { "裁剪四角无效，请重新调整" }
        val (w,h)=settings.quad.outputSize(input.width,input.height,maxEdge,maxPixels)
        var output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        try {
            val src=settings.quad.points.flatMap { listOf(it.x*input.width,it.y*input.height) }.toFloatArray()
            val dst=floatArrayOf(0f,0f,w.toFloat(),0f,w.toFloat(),h.toFloat(),0f,h.toFloat())
            val matrix=Matrix();require(matrix.setPolyToPoly(src,0,dst,0,4)) { "无法校正此裁剪区域" }
            if(!highQuality || !warpCubic(input,output,settings.quad)) {
                Canvas(output).apply { drawColor(Color.WHITE);drawBitmap(input,matrix,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
            }
            Canvas(output).drawColor(Color.WHITE,PorterDuff.Mode.DST_OVER)
            if(settings.mode!=ScanMode.ORIGINAL) {
                val pixels=IntArray(w*h);output.getPixels(pixels,0,w,0,0,w,h)
                val processed=ScanFilters.applyInPlace(pixels,w,h,settings.mode,checkCancelled)
                output.setPixels(processed,0,w,0,0,w,h)
            }
            checkCancelled()
            val turns=((settings.turns%4)+4)%4
            if(turns!=0) {
                val rotated=Bitmap.createBitmap(output,0,0,output.width,output.height,Matrix().apply { postRotate(turns*90f) },true)
                if(rotated!==output) { output.recycle();output=rotated }
            }
            output.setHasAlpha(false)
            return output
        } catch(error:Throwable) { output.recycle();throw error }
    }
}
