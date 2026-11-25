package cn.shiying.local.conversion

import cn.shiying.local.scan.ScanJpeg
import cn.shiying.local.scan.ScanPdf
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScanPdfTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun source(name:String):File=temporary.newFile("$name.jpg").also { file ->
        javaClass.getResourceAsStream("/scan/$name.jpg")!!.use { stream -> file.outputStream().use { stream.copyTo(it) } }
    }
    @Test fun realJpegsBecomeOrderedPortraitAndLandscapeA4PagesWithoutReencoding() {
        val portrait=source("portrait");val landscape=source("landscape")
        val originals=listOf(portrait.readBytes(),landscape.readBytes())
        val output=File(temporary.root,"scanned.pdf")
        ScanPdf.write(listOf(ScanJpeg(portrait,600,840),ScanJpeg(landscape,840,600)),output,"扫描 QA")
        PDDocument.load(output).use { document ->
            assertEquals(2,document.numberOfPages)
            assertTrue(document.getPage(0).mediaBox.width<document.getPage(0).mediaBox.height)
            assertTrue(document.getPage(1).mediaBox.width>document.getPage(1).mediaBox.height)
            for(i in 0..1) {
                val page=document.getPage(i);val image=page.resources.getXObject(page.resources.xObjectNames.single()) as PDImageXObject
                assertArrayEquals(originals[i],image.cosObject.createRawInputStream().use { it.readBytes() })
            }
        }
        assertArrayEquals(originals[0],portrait.readBytes());assertArrayEquals(originals[1],landscape.readBytes())
        System.getenv("SHIYING_SCAN_QA_DIR")?.takeIf { it.isNotBlank() }?.let { directory ->
            File(directory).mkdirs();output.copyTo(File(directory,"scanned.pdf"),overwrite=true)
        }
    }
    @Test fun exportRejectsEmptyPagesAndHonoursCancellationBeforePublishing() {
        assertThrows(IllegalArgumentException::class.java) { ScanPdf.write(emptyList(),File(temporary.root,"empty.pdf"),"") }
        val source=source("portrait");val output=File(temporary.root,"cancelled.pdf")
        assertThrows(InterruptedException::class.java) { ScanPdf.write(listOf(ScanJpeg(source,600,840)),output,"") { throw InterruptedException() } }
        assertFalse(output.exists())
    }
}
