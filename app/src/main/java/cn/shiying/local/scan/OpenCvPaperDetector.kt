package cn.shiying.local.scan

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class DetectedPaper(val quad:ScanQuad,val sharpness:Double,val confidence:Float,val frameWidth:Int,val frameHeight:Int)

object OpenCvPaperDetector {
    private var loaded:Boolean?=null
    @Synchronized fun available():Boolean {
        if(loaded==null) loaded=try { OpenCVLoader.initLocal() } catch(_:LinkageError) { false }
        return loaded==true
    }
    fun detect(bitmap:Bitmap):ScanQuad? {
        if(!available()) return null
        val rgba=Mat();val gray=Mat()
        try { Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY);return detectGray(gray)?.quad }
        finally { rgba.release();gray.release() }
    }
    fun detectGray(input:Mat):DetectedPaper? {
        if(!available() || input.empty()) return null
        val gray=Mat();val blurred=Mat();val edges=Mat();val binary=Mat();val hierarchy=Mat()
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(5.0,5.0))
        try {
            val factor=minOf(1.0,1000.0/maxOf(input.cols(),input.rows()))
            Imgproc.resize(input,gray,Size(input.cols()*factor,input.rows()*factor),0.0,0.0,Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(gray,blurred,Size(5.0,5.0),0.0)
            Imgproc.Canny(blurred,edges,35.0,115.0)
            Imgproc.morphologyEx(edges,edges,Imgproc.MORPH_CLOSE,kernel)
            Imgproc.threshold(blurred,binary,0.0,255.0,Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary,binary,Imgproc.MORPH_CLOSE,kernel)
            var best:ScanQuad?=null;var score=0f
            for(mask in listOf(edges,binary)) {
                val contours=ArrayList<MatOfPoint>()
                try {
                    Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE)
                    contours.forEach { contour ->
                        if(abs(Imgproc.contourArea(contour))<gray.total()*.08) return@forEach
                        val curve=MatOfPoint2f(*contour.toArray());val approx=MatOfPoint2f()
                        try {
                            Imgproc.approxPolyDP(curve,approx,.02*Imgproc.arcLength(curve,true),true)
                            if(approx.total()!=4L) return@forEach
                            val points=approx.toArray();val polygon=MatOfPoint(*points)
                            val convex=try { Imgproc.isContourConvex(polygon) } finally { polygon.release() }
                            if(!convex) return@forEach
                            val cx=points.map { it.x }.average();val cy=points.map { it.y }.average()
                            val ordered=points.sortedBy { atan2(it.y-cy,it.x-cx) }
                            val first=ordered.indices.minBy { ordered[it].x+ordered[it].y }
                            val normalized=(0..3).map { val p=ordered[(first+it)%4];ScanPoint((p.x/gray.cols()).toFloat(),(p.y/gray.rows()).toFloat()) }
                            val quad=ScanQuad(normalized)
                            if(!quad.valid() || quad.area !in .08f.. .975f) return@forEach
                            if(normalized.count { it.x<.012f || it.y<.012f || it.x>.988f || it.y>.988f }>=3) return@forEach
                            val angleScore=normalized.indices.minOf { i ->
                                val a=normalized[i];val b=normalized[(i+1)%4];val c=normalized[(i+2)%4]
                                val cross=abs((b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x))
                                cross/(hypot(b.x-a.x,b.y-a.y)*hypot(c.x-b.x,c.y-b.y))
                            }
                            if(angleScore<.3f) return@forEach
                            val candidate=quad.area*(.65f+.35f*angleScore)
                            if(candidate>score) { best=quad;score=candidate }
                        } finally { curve.release();approx.release() }
                    }
                } finally { contours.forEach { it.release() } }
            }
            val quad=best ?: return null
            val left=(quad.points.minOf { it.x }*gray.cols()).toInt().coerceIn(0,gray.cols()-1)
            val top=(quad.points.minOf { it.y }*gray.rows()).toInt().coerceIn(0,gray.rows()-1)
            val right=(quad.points.maxOf { it.x }*gray.cols()).toInt().coerceIn(left+1,gray.cols())
            val bottom=(quad.points.maxOf { it.y }*gray.rows()).toInt().coerceIn(top+1,gray.rows())
            val roi=gray.submat(top,bottom,left,right);val lap=Mat();val mean=MatOfDouble();val deviation=MatOfDouble()
            val sharpness=try { Imgproc.Laplacian(roi,lap,CvType.CV_64F);Core.meanStdDev(lap,mean,deviation);deviation.toArray()[0].pow(2) }
                finally { roi.release();lap.release();mean.release();deviation.release() }
            return DetectedPaper(quad,sharpness,score,gray.cols(),gray.rows())
        } finally { gray.release();blurred.release();edges.release();binary.release();hierarchy.release();kernel.release() }
    }
}
