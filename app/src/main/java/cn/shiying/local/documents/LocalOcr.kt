package cn.shiying.local.documents

import android.content.Context
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

object LocalOcr {
    @Synchronized
    fun create(context:Context):TessBaseAPI {
        val root=File(context.noBackupFilesDir,"tesseract-fast-2026")
        val data=File(root,"tessdata").apply { mkdirs() }
        for(language in listOf("eng","chi_sim")) {
            val model=File(data,"$language.traineddata")
            val expected=if(language=="eng") 4_113_088L else 2_469_156L
            if(model.length()!=expected) {
                val temporary=File(data,"$language.part")
                context.assets.open("ocr/$language.traineddata").use { input -> temporary.outputStream().use { input.copyTo(it) } }
                require(temporary.length()==expected && temporary.renameTo(model)) { "无法准备离线 OCR 模型" }
            }
        }
        val engine=TessBaseAPI()
        if(!engine.init(root.absolutePath,"chi_sim+eng")) { engine.recycle();error("离线 OCR 初始化失败") }
        engine.pageSegMode=TessBaseAPI.PageSegMode.PSM_AUTO
        return engine
    }
}
