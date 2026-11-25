package cn.shiying.local.conversion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shiying.local.meme.*
import cn.shiying.local.scan.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.CRC32
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class StudioWorkflowDeviceTest {
    @Test fun recommendationUsesOffCentreMaskAndAccountsForMirroring() {
        val sticker = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        Canvas(sticker).drawRect(20f, 88f, 125f, 112f, Paint().apply { color = Color.GREEN })
        try {
            val advice = TemplateMatcher.analyze(sticker, sticker)
            assertEquals(MemeTemplate.FILM, advice.choices.first())
            val normal = advice.fit(MemeTemplate.FILM, true, false)
            val mirror = advice.fit(MemeTemplate.FILM, true, true)
            assertEquals(1f, normal.x + mirror.x, .001f)
            assertEquals(normal.y, mirror.y, .001f)
            val rendered = MemeCanvas.render(MemeProject(template = MemeTemplate.FILM, subject = mirror,
                mirrorSubject = true, texts = emptyList()), sticker, null)
            try { assertEquals(Color.GREEN, rendered.getPixel(512, 476)) } finally { rendered.recycle() }
        } finally { sticker.recycle() }
    }

    @Test fun builtInTemplatesRenderDistinctArtworkAndSingleFaceFitsPanda() {
        val sums = MemeTemplate.entries.filter { it != MemeTemplate.CUSTOM }.map { template ->
            val image = MemeCanvas.render(MemeProject(template = template, texts = emptyList()), null, null)
            try {
                val bytes = ByteBuffer.allocate(image.byteCount)
                image.copyPixelsToBuffer(bytes)
                CRC32().apply { update(bytes.array()) }.value
            } finally { image.recycle() }
        }
        assertEquals(12, sums.distinct().size)
        val advice = TemplateAdvice("", listOf(MemeTemplate.PANDA), SubjectBounds(0f,0f,1f,1f),
            SubjectBounds(.6f,.2f,.8f,.4f), 600, 600)
        val position = advice.fit(MemeTemplate.PANDA, false, false)
        assertEquals(256f, position.x * 512 + .2f * 380 * position.scale, .01f)
        assertEquals(218f, position.y * 512 - .2f * 380 * position.scale, .01f)
    }

    @Test fun scanKeepsCorrectedTextByPageAndBatchFilterPreservesCropAndRotation() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val file = File.createTempFile("scan-session-test-", ".png", app.cacheDir)
        val image = Bitmap.createBitmap(100, 140, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val model = withContext(Dispatchers.Main) { ScanViewModel(app, SavedStateHandle()) }
        suspend fun ready() = withTimeout(15000) { model.state.first { !it.busy && it.original != null } }
        try {
            withContext(Dispatchers.Main) { model.import(List(10) { Uri.fromFile(file) }) }
            val ids = ready().pages.map { it.id }
            withContext(Dispatchers.Main) { model.select(ids.first()) }
            ready()
            withContext(Dispatchers.Main) { model.rotate(); model.corner(0,.05f,.05f); model.text("校对后的第一页"); model.select(ids[1]) }
            ready()
            withContext(Dispatchers.Main) { model.text("第二页"); model.mode(ScanMode.GRAY); model.applyModeToAll() }
            val pages = model.state.value.pages
            assertTrue(pages.all { it.settings.mode == ScanMode.GRAY })
            assertEquals(1, pages.first().settings.turns)
            assertEquals(.05f, pages.first().settings.quad.points[0].x, .001f)
            assertEquals(0, pages[1].settings.turns)
            withContext(Dispatchers.Main) { model.select(ids.first()) }
            assertEquals("校对后的第一页", ready().text)
            withContext(Dispatchers.Main) { model.detect() }
            ready()
            assertEquals(.05f, model.state.value.selected!!.settings.quad.points[0].x, .001f)
            withContext(Dispatchers.Main) { model.select(ids[1]) }
            assertEquals("第二页", ready().text)
            withContext(Dispatchers.Main) { model.movePage(ids.last(), 1) }
            assertEquals(listOf(ids.first(), ids.last()) + ids.subList(1, 9), ready().pages.map { it.id })
            assertEquals(ids[1], ready().selectedId)
            val original = ready().original
            withContext(Dispatchers.Main) { model.remove(ids.last()) }
            assertSame(original, ready().original)
            assertEquals("第二页", ready().text)
            withContext(Dispatchers.Main) { model.movePage(ids[1], 0); model.movePage(ids[1], 8) }
            assertEquals(ids[1], ready().pages.last().id)
            withContext(Dispatchers.Main) { model.remove() }
            assertEquals(ids[8], ready().selectedId)
        } finally {
            withContext(Dispatchers.Main) { model.cancel(); model.clearDraft() }
            file.delete()
        }
    }
}
