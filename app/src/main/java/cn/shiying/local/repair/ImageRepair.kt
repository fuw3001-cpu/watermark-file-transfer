package cn.shiying.local.repair

import android.graphics.Bitmap
import android.graphics.Rect
import cn.shiying.local.scan.OpenCvPaperDetector
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

data class RepairPoint(val x:Float,val y:Float)
data class RepairStroke(val points:List<RepairPoint>,val radius:Float)

object ImageRepair {
    private const val INPAINT_RADIUS = 3.0
    private const val CONTEXT_PIXELS = 16

    fun repair(source: Bitmap, strokes: List<RepairStroke>, checkCancelled: () -> Unit = {}): Bitmap {
        require(strokes.isNotEmpty() && strokes.size <= 100) { "请先涂抹水印区域" }
        require(OpenCvPaperDetector.available()) { "无法加载本地修复组件" }
        val width = source.width
        val height = source.height
        fun radius(stroke: RepairStroke) = maxOf(1, (stroke.radius * minOf(width, height)).toInt())
        val region = Rect()
        for (stroke in strokes) {
            checkCancelled()
            require(stroke.radius in .003f.. .08f && stroke.points.size in 1..2000)
            val reach = radius(stroke) + 1
            for (p in stroke.points) {
                require(p.x in 0f..1f && p.y in 0f..1f)
                val x = (p.x * (width - 1)).roundToInt()
                val y = (p.y * (height - 1)).roundToInt()
                region.union(x - reach, y - reach, x + reach + 1, y + reach + 1)
            }
        }
        region.inset(-CONTEXT_PIXELS, -CONTEXT_PIXELS)
        check(region.intersect(0, 0, width, height))
        val mask = Mat.zeros(region.height(), region.width(), CvType.CV_8UC1)
        val rgba = Mat()
        val rgb = Mat()
        val repaired = Mat()
        try {
            val white = Scalar(255.0)
            for (stroke in strokes) {
                val r = radius(stroke)
                var previous: Point? = null
                stroke.points.forEachIndexed { i, p ->
                    if (i % 128 == 0) checkCancelled()
                    val point = Point((p.x * (width - 1) - region.left).toDouble(), (p.y * (height - 1) - region.top).toDouble())
                    previous?.let { Imgproc.line(mask, it, point, white, r * 2, Imgproc.LINE_8) }
                    Imgproc.circle(mask, point, r, white, -1)
                    previous = point
                }
            }
            val selected = Core.countNonZero(mask)
            require(selected > 0 && selected <= width.toLong() * height * .25) { "涂抹区域过大，请缩小选区后重试" }
            checkCancelled()
            val tile = Bitmap.createBitmap(source, region.left, region.top, region.width(), region.height())
            try { Utils.bitmapToMat(tile, rgba, source.hasAlpha() && source.isPremultiplied) }
            finally { if (tile !== source) tile.recycle() }
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            rgba.release()
            Photo.inpaint(rgb, mask, repaired, INPAINT_RADIUS, Photo.INPAINT_TELEA)
            rgb.release()
            checkCancelled()
            val output = source.copy(Bitmap.Config.ARGB_8888, true) ?: throw OutOfMemoryError()
            try {
                copySelectedPixels(output, region, repaired, mask, checkCancelled)
                return output
            } catch (error: Throwable) { output.recycle(); throw error }
        } finally { rgba.release(); rgb.release(); repaired.release(); mask.release() }
    }

    private fun copySelectedPixels(output: Bitmap, region: Rect, rgb: Mat, mask: Mat, checkCancelled: () -> Unit) {
        val width = region.width()
        val selection = ByteArray(width)
        val colors = ByteArray(width * 3)
        val pixels = IntArray(width)
        for (y in 0 until region.height()) {
            checkCancelled()
            mask.get(y, 0, selection)
            if (selection.all { it == 0.toByte() }) continue
            rgb.get(y, 0, colors)
            output.getPixels(pixels, 0, width, region.left, region.top + y, width, 1)
            var x = 0
            while (x < width) {
                if (selection[x] == 0.toByte()) { x++; continue }
                val start = x
                while (x < width && selection[x] != 0.toByte()) {
                    val i = x * 3
                    pixels[x] = (pixels[x] and 0xff000000.toInt()) or ((colors[i].toInt() and 255) shl 16) or
                        ((colors[i + 1].toInt() and 255) shl 8) or (colors[i + 2].toInt() and 255)
                    x++
                }
                output.setPixels(pixels, start, width, region.left + start, region.top + y, x - start, 1)
            }
        }
    }
}
