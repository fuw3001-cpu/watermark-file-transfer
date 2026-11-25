package cn.shiying.local.meme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.media.FaceDetector
import kotlin.math.roundToInt

data class SubjectBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

data class TemplateAdvice(val reason: String, val choices: List<MemeTemplate>, val region: SubjectBounds,
                          val face: SubjectBounds?, val width: Int, val height: Int) {
    fun fit(template: MemeTemplate, useCutout: Boolean, mirrored: Boolean): LayerTransform {
        val whole = SubjectBounds(0f, 0f, 1f, 1f)
        val box = if (template == MemeTemplate.PANDA && face != null) face else if (useCutout) region else whole
        val frame = template.window ?: when (template) {
            MemeTemplate.PANDA -> TemplateWindow(143, 105, 369, 331)
            MemeTemplate.CUSTOM -> return template.subject
            else -> TemplateWindow(64, 45, 448, 377)
        }
        val unit = 380f / maxOf(width, height)
        val zoom = minOf((frame.right - frame.left) / (box.width * width * unit),
            (frame.bottom - frame.top) / (box.height * height * unit)).coerceIn(.15f, 8f)
        val dx = ((box.left + box.right) / 2 - .5f) * width * unit * zoom
        val dy = ((box.top + box.bottom) / 2 - .5f) * height * unit * zoom
        return LayerTransform((((frame.left + frame.right) / 2f - if (mirrored) -dx else dx) / 512f).coerceIn(-3f, 4f),
            (((frame.top + frame.bottom) / 2f - dy) / 512f).coerceIn(-3f, 4f), zoom)
    }
}

internal object TemplateMatcher {
    fun analyze(original: Bitmap, cutout: Bitmap?): TemplateAdvice {
        val region = cutout?.let(::alphaBounds) ?: SubjectBounds(0f, 0f, 1f, 1f)
        val faces = detectFaces(original)
        val aspect = region.width * original.width / (region.height * original.height)
        val (reason, choices) = when {
            faces.size > 1 -> "检测到多张脸，宽画框更容易保留合影。" to
                listOf(MemeTemplate.FILM, MemeTemplate.SNAPSHOT, MemeTemplate.POSTER)
            faces.size == 1 -> "检测到一张正脸，熊猫头可自动对齐脸部。" to
                listOf(MemeTemplate.PANDA, MemeTemplate.BUBBLE, MemeTemplate.STAMP)
            aspect > 1.28f -> "主体偏宽，横向画框能留出两侧空间。" to
                listOf(MemeTemplate.FILM, MemeTemplate.TELEVISION, MemeTemplate.ARCADE)
            aspect < .78f -> "主体偏高，这些画框适合纵向构图。" to
                listOf(MemeTemplate.STAMP, MemeTemplate.DAISY, MemeTemplate.NOTEBOOK)
            cutout != null && region.width * region.height < .87f -> "主体轮廓集中，适合贴纸和对话构图。" to
                listOf(MemeTemplate.BUBBLE, MemeTemplate.DAISY, MemeTemplate.COMIC)
            else -> "素材接近方形，这些模板能保留主体和配文位置。" to
                listOf(MemeTemplate.SNAPSHOT, MemeTemplate.TICKET, MemeTemplate.NOTEBOOK)
        }
        return TemplateAdvice(reason, choices, region, faces.singleOrNull(), original.width, original.height)
    }

    private fun alphaBounds(bitmap: Bitmap): SubjectBounds {
        val side = 64
        val small = Bitmap.createScaledBitmap(bitmap, side, side, true)
        val pixels = IntArray(side * side)
        try { small.getPixels(pixels, 0, side, 0, 0, side, side) }
        finally { if (small !== bitmap) small.recycle() }
        var left = side; var top = side; var right = -1; var bottom = -1
        for (y in 0 until side) for (x in 0 until side) {
            if ((pixels[y * side + x] ushr 24) >= 128) {
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        return if (right < left) SubjectBounds(0f, 0f, 1f, 1f)
            else SubjectBounds(left.toFloat() / side, top.toFloat() / side, (right + 1f) / side, (bottom + 1f) / side)
    }

    private fun detectFaces(source: Bitmap): List<SubjectBounds> {
        val scale = minOf(1f, 384f / maxOf(source.width, source.height))
        val width = ((source.width * scale).roundToInt() / 2) * 2
        val height = (source.height * scale).roundToInt()
        if (minOf(width, height) < 32) return emptyList()
        val input = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        try {
            Canvas(input).apply {
                drawColor(android.graphics.Color.WHITE)
                drawBitmap(source, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val faces = arrayOfNulls<FaceDetector.Face>(5)
            FaceDetector(width, height, faces.size).findFaces(input, faces)
            return faces.filterNotNull().filter { it.confidence() >= .45f && it.eyesDistance() >= 4f }.map { face ->
                val p = PointF().also(face::getMidPoint)
                val d = face.eyesDistance()
                SubjectBounds(((p.x - 1.45f * d) / width).coerceIn(0f, 1f), ((p.y - 1.35f * d) / height).coerceIn(0f, 1f),
                    ((p.x + 1.45f * d) / width).coerceIn(0f, 1f), ((p.y + 2f * d) / height).coerceIn(0f, 1f))
            }.filter { it.width > 0 && it.height > 0 }
        } catch (_: RuntimeException) { return emptyList() }
        catch (_: LinkageError) { return emptyList() }
        finally { input.recycle() }
    }
}
