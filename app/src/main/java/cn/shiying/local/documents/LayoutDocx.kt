package cn.shiying.local.documents

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

data class LayoutText(val text: String, val x: Float, val y: Float, val width: Float, val height: Float,
    val fontSize: Float, val bold: Boolean = false, val color: String = "000000")
data class LayoutPage(val width: Float, val height: Float, val background: File, val text: List<LayoutText>)

/** Editable, positioned text over a text-cleared page background, not a page image masquerading as Word text. */
object LayoutDocx {
    fun write(file: File, pages: List<LayoutPage>) {
        require(pages.isNotEmpty()) { "PDF 没有页面" }
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="office" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            entry("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${pages.indices.joinToString("") { "<Relationship Id=\"img$it\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/page$it.jpg\"/>" }}</Relationships>""")
            var shapeId = 1
            val xml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office"><w:body>""")
                pages.forEachIndexed { pageIndex, page ->
                    fun section(): String = """<w:sectPr><w:type w:val="nextPage"/><w:pgSz w:w="${(page.width * 20).roundToInt()}" w:h="${(page.height * 20).roundToInt()}"/><w:pgMar w:top="0" w:right="0" w:bottom="0" w:left="0" w:header="0" w:footer="0"/><w:cols w:space="0"/></w:sectPr>"""
                    append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\"/>")
                    if (pageIndex < pages.lastIndex) append(section())
                    append("</w:pPr>")
                    val anchor = "mso-position-horizontal-relative:page;mso-position-vertical-relative:page;mso-wrap-style:none"
                    append("""<w:r><w:pict><v:rect id="page$shapeId" style="position:absolute;margin-left:0pt;margin-top:0pt;width:${pt(page.width)}pt;height:${pt(page.height)}pt;z-index:-1;$anchor" stroked="f" o:allowincell="f"><v:imagedata r:id="img$pageIndex"/></v:rect></w:pict></w:r>""")
                    shapeId++
                    page.text.forEach { line ->
                        val id = shapeId++
                        append("""<w:r><w:pict><v:rect id="text$id" filled="f" stroked="f" o:allowincell="f" style="position:absolute;margin-left:${pt(line.x)}pt;margin-top:${pt(line.y)}pt;width:${pt(line.width.coerceAtLeast(1f) + 2)}pt;height:${pt(line.height.coerceAtLeast(line.fontSize) + 4)}pt;z-index:1;$anchor"><v:textbox inset="0,0,0,0"><w:txbxContent><w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="${(line.height.coerceAtLeast(line.fontSize) * 20).roundToInt()}" w:lineRule="exact"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:eastAsia="Microsoft YaHei"/><w:sz w:val="${(line.fontSize * 2).roundToInt().coerceAtLeast(4)}"/><w:color w:val="${line.color}"/>${if (line.bold) "<w:b/>" else ""}<w:fitText w:val="${(line.width * 20).roundToInt().coerceAtLeast(1)}" w:id="$id"/></w:rPr><w:t xml:space="preserve">${escape(line.text)}</w:t></w:r></w:p></w:txbxContent></v:textbox></v:rect></w:pict></w:r>""")
                    }
                    append("</w:p>")
                    if (pageIndex == pages.lastIndex) append(section())
                }
                append("</w:body></w:document>")
            }
            entry("word/document.xml", xml)
            pages.forEachIndexed { index, page ->
                zip.putNextEntry(ZipEntry("word/media/page$index.jpg")); page.background.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    private fun pt(value: Float) = String.format(java.util.Locale.ROOT, "%.2f", value)
    fun escape(value: String) = value.filter { it == '\n' || it == '\t' || it.code >= 32 }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
