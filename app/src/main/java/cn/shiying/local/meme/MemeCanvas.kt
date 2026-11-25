package cn.shiying.local.meme

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.*

/** All coordinates use a 512-square canvas; export and interactive preview draw the same layers. */
object MemeCanvas {
    fun decode(context: Context, uri: Uri): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        require(info.mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) { "请选择 JPG、PNG、WebP 或 GIF 图片" }
        require(info.size.width.toLong() * info.size.height <= 16_000_000) { "图片超过 1600 万像素，请先缩小" }
        val scale = minOf(1.0, 1024.0 / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize(maxOf(1, (info.size.width * scale).roundToInt()), maxOf(1, (info.size.height * scale).roundToInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }

    private fun textLayout(text: MemeText, paint: TextPaint): StaticLayout {
        paint.textSize = text.size
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val width = (text.text.lines().maxOfOrNull { paint.measureText(it) } ?: 1f).coerceIn(24f, 480f).roundToInt()
        return StaticLayout.Builder.obtain(text.text, 0, text.text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).setMaxLines(5)
            .setEllipsize(TextUtils.TruncateAt.END).build()
    }

    fun textBounds(text: MemeText): RectF {
        val layout = textLayout(text, TextPaint(Paint.ANTI_ALIAS_FLAG))
        return RectF(text.x * 512 - layout.width / 2f, text.y * 512 - layout.height / 2f,
            text.x * 512 + layout.width / 2f, text.y * 512 + layout.height / 2f)
    }

    fun render(project: MemeProject, subject: Bitmap?, template: Bitmap?): Bitmap {
        val output = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        try { Canvas(output).apply { scale(2f, 2f); draw(this, project, subject, template) }; return output }
        catch (error: Throwable) { output.recycle(); throw error }
    }

    fun draw(canvas: Canvas, project: MemeProject, subject: Bitmap?, template: Bitmap?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.save()
        canvas.clipRect(0f, 0f, 512f, 512f)
        canvas.drawColor(Color.WHITE)
        when (project.template) {
            MemeTemplate.PANDA -> {
                paint.color = Color.BLACK
                canvas.drawRoundRect(142f, 319f, 370f, 580f, 80f, 80f, paint)
                canvas.drawCircle(126f, 101f, 61f, paint); canvas.drawCircle(386f, 101f, 61f, paint)
                canvas.drawCircle(256f, 218f, 170f, paint)
                paint.color = Color.WHITE; canvas.drawCircle(256f, 218f, 163f, paint)
            }
            MemeTemplate.POSTER -> {
                paint.color = Color.rgb(250, 234, 216); canvas.drawRect(0f, 0f, 512f, 512f, paint)
                paint.color = Color.WHITE; canvas.drawRoundRect(22f, 22f, 490f, 490f, 18f, 18f, paint)
                paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
                canvas.drawRoundRect(22f, 22f, 490f, 490f, 18f, 18f, paint); paint.style = Paint.Style.FILL
            }
            MemeTemplate.COMIC -> {
                paint.color = Color.rgb(255, 224, 83); canvas.drawRect(0f, 0f, 512f, 512f, paint)
                paint.color = Color.WHITE
                for (i in 0 until 18) {
                    val angle = i * PI * 2 / 18
                    val path = Path().apply { moveTo(256f, 256f)
                        lineTo((256 + cos(angle) * 740).toFloat(), (256 + sin(angle) * 740).toFloat())
                        lineTo((256 + cos(angle + .12) * 740).toFloat(), (256 + sin(angle + .12) * 740).toFloat()); close() }
                    canvas.drawPath(path, paint)
                }
                paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 8f
                canvas.drawRect(4f, 4f, 508f, 508f, paint); paint.style = Paint.Style.FILL
            }
            MemeTemplate.SNAPSHOT, MemeTemplate.FILM, MemeTemplate.BUBBLE, MemeTemplate.ARCADE,
            MemeTemplate.STAMP, MemeTemplate.TICKET, MemeTemplate.NOTEBOOK, MemeTemplate.TELEVISION,
            MemeTemplate.DAISY -> TemplateArt.draw(canvas, project.template)
            MemeTemplate.CUSTOM -> template?.let {
                val scale = minOf(512f / it.width, 512f / it.height)
                val w = it.width * scale; val h = it.height * scale
                canvas.drawBitmap(it, null, RectF((512-w)/2, (512-h)/2, (512+w)/2, (512+h)/2), paint)
            }
        }
        subject?.let {
            canvas.save()
            if (project.template == MemeTemplate.PANDA) canvas.clipPath(Path().apply { addCircle(256f, 218f, 148f, Path.Direction.CW) })
            project.template.window?.let { frame -> canvas.clipRect(frame.left, frame.top, frame.right, frame.bottom) }
            val scale = minOf(380f / it.width, 380f / it.height) * project.subject.scale
            val w = it.width * scale; val h = it.height * scale
            val x = project.subject.x * 512; val y = project.subject.y * 512
            if (project.mirrorSubject) canvas.scale(-1f, 1f, x, y)
            canvas.drawBitmap(it, null, RectF(x-w/2, y-h/2, x+w/2, y+h/2), paint)
            canvas.restore()
        }
        project.texts.filter { it.text.isNotBlank() }.forEach { text ->
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
            val layout = textLayout(text, textPaint)
            canvas.save(); canvas.translate(text.x*512-layout.width/2f, text.y*512-layout.height/2f)
            textPaint.style = Paint.Style.STROKE; textPaint.strokeWidth = maxOf(1.8f, text.size*.075f)
            textPaint.color = if (text.ink == TextInk.BLACK) Color.WHITE else Color.BLACK
            layout.draw(canvas)
            textPaint.style = Paint.Style.FILL; textPaint.color = text.ink.argb; layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}
