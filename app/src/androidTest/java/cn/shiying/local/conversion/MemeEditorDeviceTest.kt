package cn.shiying.local.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shiying.local.meme.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemeEditorDeviceTest {
    @Test fun replacingAvatarKeepsTextLayoutAndOriginalImageMode() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val file = File.createTempFile("meme-replace-", ".png", app.cacheDir)
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(image).drawCircle(32f, 32f, 20f, Paint().apply { color = Color.RED })
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val model = withContext(Dispatchers.Main) { MemeViewModel(app) }
        try {
            withContext(Dispatchers.Main) { model.source(Uri.fromFile(file)) }
            withTimeout(20000) { model.state.first { !it.busy && it.subject != null } }
            withContext(Dispatchers.Main) {
                model.useCutout(false)
                model.select(1)
                model.text("替换头像保留文字")
                model.textSize(65f)
                model.textPosition(.16f)
                model.ink(TextInk.WHITE)
                model.duplicateText()
                model.select(null)
                model.transform(.12f, -.06f, 1.5f)
                model.mirrorSubject()
            }
            val before = model.state.value
            assertEquals(2, before.project.texts.size)
            assertEquals(before.project.texts[0].text, before.project.texts[1].text)
            assertEquals(before.project.texts[0].size, before.project.texts[1].size, 0f)
            assertNotEquals(before.project.texts[0].id, before.project.texts[1].id)
            withContext(Dispatchers.Main) { model.source(Uri.fromFile(file)) }
            val replaced = withTimeout(20000) { model.state.first { !it.busy && it.original !== before.original } }
            assertEquals(before.project, replaced.project)
            assertFalse(replaced.project.useCutout)
            withContext(Dispatchers.Main) { model.source(Uri.fromFile(File(file.parentFile, "missing-avatar.png"))) }
            val failed = withTimeout(10000) { model.state.first { !it.busy && it.error != null } }
            assertSame(replaced.original, failed.original)
            assertEquals(replaced.project, failed.project)
        } finally {
            withContext(Dispatchers.Main) { model.cancel() }
            file.delete()
        }
    }

    @Test fun avatarMirrorIsAppliedToExportWithoutChangingSource() {
        val subject = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        Canvas(subject).drawRect(0f, 0f, 32f, 32f, Paint().apply { color = Color.RED })
        val project = MemeProject(template = MemeTemplate.CUSTOM, subject = LayerTransform(.5f, .5f), texts = emptyList())
        val normal = MemeCanvas.render(project, subject, null)
        val mirrored = MemeCanvas.render(project.copy(mirrorSubject = true), subject, null)
        try {
            assertEquals(Color.RED, normal.getPixel(300, 512))
            assertEquals(Color.BLUE, mirrored.getPixel(300, 512))
            assertEquals(Color.RED, mirrored.getPixel(724, 512))
            assertEquals(Color.RED, subject.getPixel(10, 16))
        } finally { normal.recycle(); mirrored.recycle(); subject.recycle() }
    }

    @Test fun textCanMoveInsideCanvasAndPngPreservesRenderedPixels() {
        val template = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val project = MemeProject(template = MemeTemplate.CUSTOM, texts = listOf(MemeText(1, "测试文字", .25f, .25f)))
        val first = MemeCanvas.render(project, null, template)
        val moved = MemeCanvas.render(project.copy(texts = listOf(project.texts.single().changed(.45f, .45f))), null, template)
        try {
            fun darkCenter(image: Bitmap): Pair<Double, Double> {
                var x = 0L; var y = 0L; var count = 0L
                for (row in 0 until image.height) for (col in 0 until image.width) {
                    if (Color.red(image.getPixel(col, row)) < 80) { x += col; y += row; count++ }
                }
                assertTrue("Text must render visible glyphs", count > 100)
                return x.toDouble()/count to y.toDouble()/count
            }
            val a = darkCenter(first); val b = darkCenter(moved)
            assertTrue(b.first - a.first > 400 && b.second - a.second > 400)
            assertEquals(1024, moved.width); assertEquals(1024, moved.height)
            val bytes = ByteArrayOutputStream().also { assertTrue(moved.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertTrue(moved.sameAs(decoded)); decoded.recycle()
            assertEquals(Color.WHITE, template.getPixel(0, 0))
        } finally { first.recycle(); moved.recycle(); template.recycle() }
    }

    @Test fun transparentSubjectCompositesIntoCustomTemplateAndMovesIndependently() {
        val template = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val subject = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(subject).drawCircle(32f, 32f, 20f, Paint().apply { color = Color.RED })
        val project = MemeProject(template = MemeTemplate.CUSTOM, subject = LayerTransform(.5f, .5f, .5f), texts = emptyList())
        val first = MemeCanvas.render(project, subject, template)
        val moved = MemeCanvas.render(project.copy(subject = project.subject.changed(.3f, 0f)), subject, template)
        try {
            assertEquals(Color.BLUE, first.getPixel(0, 0)); assertEquals(Color.RED, first.getPixel(512, 512))
            assertEquals(Color.BLUE, moved.getPixel(512, 512)); assertEquals(Color.RED, moved.getPixel(819, 512))
            assertEquals(0, Color.alpha(subject.getPixel(0, 0)))
        } finally { first.recycle(); moved.recycle(); subject.recycle(); template.recycle() }
    }
}
