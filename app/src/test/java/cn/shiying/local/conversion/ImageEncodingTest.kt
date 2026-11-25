package cn.shiying.local.conversion

import cn.shiying.local.storage.ImageEncoding
import org.junit.Assert.*
import org.junit.Test

class ImageEncodingTest {
    @Test fun identifiesActualFileTypeInsteadOfTrustingNameOrHttpMime() {
        assertEquals(ImageEncoding.JPEG, ImageEncoding.sniff(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) + ByteArray(9)))
        assertEquals(ImageEncoding.PNG, ImageEncoding.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(4)))
        assertEquals(ImageEncoding.WEBP, ImageEncoding.sniff("RIFF1234WEBP".toByteArray()))
        assertEquals(ImageEncoding.GIF, ImageEncoding.sniff("GIF89a123456".toByteArray()))
        assertNull(ImageEncoding.sniff("<html>login required</html>".toByteArray()))
        assertNull(ImageEncoding.sniff(byteArrayOf(1, 2)))
    }
}
