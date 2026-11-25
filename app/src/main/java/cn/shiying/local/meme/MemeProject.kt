package cn.shiying.local.meme

data class LayerTransform(val x: Float = .5f, val y: Float = .47f, val scale: Float = 1f) {
    fun changed(dx: Float, dy: Float, zoom: Float = 1f, anchorX: Float? = null, anchorY: Float? = null): LayerTransform {
        val nextScale = (scale * zoom).coerceIn(.15f, 8f)
        val factor = nextScale / scale
        return copy(x = (x + dx + if (anchorX != null) (x-anchorX)*(factor-1) else 0f).coerceIn(-3f, 4f),
            y = (y + dy + if (anchorY != null) (y-anchorY)*(factor-1) else 0f).coerceIn(-3f, 4f), scale = nextScale)
    }
}

data class TemplateWindow(val left: Int, val top: Int, val right: Int, val bottom: Int)

enum class MemeTemplate(val label: String, val subject: LayerTransform, val window: TemplateWindow? = null) {
    PANDA("熊猫头", LayerTransform(.5f, .43f, .72f)),
    POSTER("海报框", LayerTransform(.5f, .47f, .95f)),
    COMIC("漫画框", LayerTransform(.5f, .47f, .86f)),
    SNAPSHOT("拍立得", LayerTransform(.5f, .43f, .88f), TemplateWindow(66, 49, 446, 379)),
    FILM("电影胶片", LayerTransform(.5f, .47f, .95f), TemplateWindow(26, 98, 486, 380)),
    BUBBLE("对话气泡", LayerTransform(.5f, .42f, .78f), TemplateWindow(62, 64, 450, 365)),
    ARCADE("像素掌机", LayerTransform(.5f, .37f, .68f), TemplateWindow(70, 74, 442, 304)),
    STAMP("旅行邮票", LayerTransform(.5f, .43f, .85f), TemplateWindow(64, 54, 448, 374)),
    TICKET("入场券", LayerTransform(.44f, .43f, .78f), TemplateWindow(44, 93, 379, 346)),
    NOTEBOOK("方格手账", LayerTransform(.51f, .42f, .83f), TemplateWindow(83, 54, 449, 376)),
    TELEVISION("复古电视", LayerTransform(.45f, .45f, .85f), TemplateWindow(57, 122, 390, 345)),
    DAISY("雏菊花笺", LayerTransform(.5f, .43f, .82f), TemplateWindow(80, 58, 432, 378)),
    CUSTOM("自定义", LayerTransform()),
}

enum class TextInk(val label: String, val argb: Int) {
    BLACK("黑字", 0xff111111.toInt()), WHITE("白字", 0xffffffff.toInt()), YELLOW("黄字", 0xffffdf35.toInt()),
}

data class MemeText(val id: Int, val text: String = "", val x: Float = .5f, val y: Float = .84f,
    val size: Float = 34f, val ink: TextInk = TextInk.BLACK) {
    fun changed(dx: Float, dy: Float, zoom: Float = 1f) = copy(
        x = (x + dx).coerceIn(0f, 1f), y = (y + dy).coerceIn(0f, 1f), size = (size * zoom).coerceIn(14f, 96f),
    )
}

data class MemeProject(val template: MemeTemplate = MemeTemplate.PANDA,
    val subject: LayerTransform = MemeTemplate.PANDA.subject, val useCutout: Boolean = true,
    val texts: List<MemeText> = listOf(MemeText(1)), val mirrorSubject: Boolean = false)
