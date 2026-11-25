package cn.shiying.local.meme

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/** Artwork uses the same 512-unit coordinates as the interactive canvas and PNG export. */
internal object TemplateArt {
    fun draw(canvas: Canvas, template: MemeTemplate) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun ink(rgb: Int): Paint = paint.apply { color = rgb or 0xff000000.toInt() }
        fun fill(rgb: Int) = canvas.drawColor(ink(rgb).color)
        fun box(x: Int, y: Int, right: Int, bottom: Int, rgb: Int, radius: Int = 0) =
            canvas.drawRoundRect(x.toFloat(), y.toFloat(), right.toFloat(), bottom.toFloat(),
                radius.toFloat(), radius.toFloat(), ink(rgb))
        fun dot(x: Int, y: Int, radius: Int, rgb: Int) = canvas.drawCircle(x.toFloat(), y.toFloat(), radius.toFloat(), ink(rgb))
        when (template) {
            MemeTemplate.SNAPSHOT -> {
                fill(0x9CAD9C)
                for (i in 0..512 step 40) {
                    box(i, 0, i + 1, 512, 0xABBBAA)
                    box(0, i, 512, i + 1, 0xABBBAA)
                }
                box(49, 34, 477, 492, 0x778A79, 3)
                box(42, 26, 470, 482, 0xFFFAEB, 3)
                box(65, 48, 447, 380, 0xD3E3D7)
                canvas.save()
                canvas.rotate(-15f, 85f, 34f)
                box(41, 20, 129, 47, 0xE7CA83)
                canvas.restore()
            }
            MemeTemplate.FILM -> {
                fill(0x242C29)
                for (x in 18..492 step 38) {
                    box(x, 20, x + 22, 47, 0xDBC998, 4)
                    box(x, 464, x + 22, 491, 0xDBC998, 4)
                }
                box(20, 87, 492, 392, 0x101511, 5)
                box(26, 98, 486, 380, 0xA4B8AA)
                box(27, 67, 78, 70, 0xD29C52)
                box(434, 408, 485, 411, 0xD29C52)
            }
            MemeTemplate.BUBBLE -> {
                fill(0xDC9298)
                for (y in 12..512 step 24) for (x in (if (y % 48 == 12) 6 else 18)..512 step 24) dot(x, y, 2, 0xBE717B)
                canvas.drawPath(Path().apply { moveTo(86f, 348f); lineTo(68f, 422f); lineTo(184f, 367f); close() }, ink(0x352B34))
                box(24, 36, 489, 388, 0x352B34, 58)
                canvas.drawPath(Path().apply { moveTo(98f, 342f); lineTo(81f, 404f); lineTo(187f, 357f); close() }, ink(0xFFF5DC))
                box(31, 43, 480, 378, 0xFFF5DC, 52)
            }
            MemeTemplate.ARCADE -> {
                fill(0xAAB8C1)
                box(24, 18, 488, 491, 0x413C51, 34)
                box(22, 13, 484, 481, 0xD7BBCE, 34)
                box(48, 47, 463, 331, 0x4B4F59, 21)
                box(64, 68, 448, 310, 0x687760)
                box(70, 74, 442, 304, 0xBDCD9E)
                box(88, 344, 105, 399, 0x434452); box(69, 363, 124, 381, 0x434452)
                dot(378, 383, 18, 0x984E68); dot(425, 361, 18, 0x984E68)
                for (x in 332..430 step 14) box(x, 451, x + 6, 456, 0x876F83, 2)
            }
            MemeTemplate.STAMP -> {
                fill(0x375E59)
                box(23, 20, 489, 492, 0xFFEDC9)
                for (n in 38..479 step 26) {
                    dot(n, 20, 6, 0x375E59); dot(n, 492, 6, 0x375E59)
                    dot(23, n, 6, 0x375E59); dot(489, n, 6, 0x375E59)
                }
                box(49, 41, 463, 389, 0x477A73); box(53, 45, 459, 385, 0xF4D297)
                box(64, 54, 448, 374, 0xB5D1BF)
                box(57, 452, 455, 455, 0xB78C57)
            }
            MemeTemplate.TICKET -> {
                fill(0xC66F4F)
                box(19, 45, 493, 466, 0xEFC174, 18)
                box(29, 58, 483, 455, 0xFFE4A6, 12)
                for (y in 64..445 step 18) box(400, y, 402, y + 8, 0xAA7449)
                dot(18, 255, 17, 0xC66F4F); dot(494, 255, 17, 0xC66F4F)
                box(41, 90, 382, 349, 0x975F43); box(44, 93, 379, 346, 0xF2C79C)
                for (y in 123..345 step 11) box(428, y, 461, y + if (y % 3 == 0) 6 else 3, 0x67513B)
                box(48, 373, 370, 375, 0xC3925C)
            }
            MemeTemplate.NOTEBOOK -> {
                fill(0x727F92)
                box(28, 23, 489, 494, 0x4C5A67, 7)
                box(23, 16, 482, 486, 0xF9F4DA, 7)
                for (x in 50..476 step 25) box(x, 23, x + 1, 477, 0xC1CFC4)
                for (y in 31..478 step 25) box(27, y, 477, y + 1, 0xC1CFC4)
                box(66, 17, 69, 485, 0xCC9386)
                for (y in 53..464 step 44) {
                    dot(40, y, 7, 0x5B6570)
                    box(13, y - 3, 42, y + 3, 0xA6ADB0, 3)
                }
                box(124, 397, 407, 462, 0xFFFBEA)
            }
            MemeTemplate.TELEVISION -> {
                fill(0xE3BD8B)
                canvas.drawPath(Path().apply {
                    moveTo(150f, 23f); lineTo(236f, 71f); lineTo(307f, 18f)
                }, ink(0x514741).apply { style = Paint.Style.STROKE; strokeWidth = 6f })
                paint.style = Paint.Style.FILL
                box(30, 66, 485, 387, 0x64493E, 34)
                box(24, 59, 479, 378, 0xAB6048, 34)
                box(41, 87, 406, 360, 0x403F3D, 30)
                box(51, 103, 397, 352, 0xB7CEC4, 22)
                for (y in listOf(143, 206)) {
                    dot(440, y, 18, 0xF6D5A5)
                    box(438, y - 14, 442, y - 3, 0x6C5044)
                }
                for (y in 265..329 step 10) box(421, y, 459, y + 3, 0x654937, 1)
                box(51, 378, 84, 392, 0x594B3F, 4)
                box(409, 378, 442, 392, 0x594B3F, 4)
            }
            MemeTemplate.DAISY -> {
                fill(0x577461)
                box(39, 24, 473, 488, 0xEFE8C9, 60)
                box(51, 36, 461, 476, 0xFAF5E5, 51)
                for ((x, y) in listOf(45 to 75, 457 to 164, 40 to 320, 467 to 421)) {
                    for (petal in 0 until 6) {
                        canvas.save()
                        canvas.rotate(petal * 60f, x.toFloat(), y.toFloat())
                        canvas.drawOval(x - 8f, y - 26f, x + 8f, y - 3f, ink(0xE3B69A))
                        canvas.restore()
                    }
                    dot(x, y, 8, 0x9B6338)
                }
            }
            else -> error("Unsupported artwork: ${template.name}")
        }
    }
}
