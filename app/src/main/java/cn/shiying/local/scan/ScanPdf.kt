package cn.shiying.local.scan

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File

data class ScanJpeg(val file:File,val width:Int,val height:Int)
object ScanPdf {
    fun write(pages:List<ScanJpeg>,output:File,title:String,checkCancelled:()->Unit={}) {
        require(pages.size in 1..20) { "一次最多导出 20 页" }
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { document ->
            document.documentInformation.title=title
            pages.forEach { image ->
                checkCancelled();require(image.width>0 && image.height>0 && image.file.length() in 3..64L*1024*1024)
                image.file.inputStream().use { require(it.read()==0xff && it.read()==0xd8) { "扫描图片格式异常" } }
                val box=if(image.width>image.height) PDRectangle(PDRectangle.A4.height,PDRectangle.A4.width) else PDRectangle.A4
                val page=PDPage(box);document.addPage(page)
                val embedded=image.file.inputStream().use { PDImageXObject(document,it,COSName.DCT_DECODE,image.width,image.height,8,PDDeviceRGB.INSTANCE) }
                val scale=minOf((box.width-24)/image.width,(box.height-24)/image.height)
                val w=image.width*scale;val h=image.height*scale
                PDPageContentStream(document,page).use { it.drawImage(embedded,(box.width-w)/2,(box.height-h)/2,w,h) }
            }
            checkCancelled();document.save(output)
        }
        require(output.length() in 1..256L*1024*1024) { "扫描 PDF 为空或超过 256 MB" }
    }
}
