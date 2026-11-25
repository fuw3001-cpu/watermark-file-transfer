package cn.shiying.local.conversion

import cn.shiying.local.scan.copyScanLuma
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ScanLumaTest {
    @Test fun tightAndStridedCropsMatchWithoutMovingCameraBuffer() {
        val input = ByteBuffer.wrap(ByteArray(48) { it.toByte() }).apply { position(2) }
        val output = ByteArray(4)
        copyScanLuma(input, 0, 1, 2, 2, 2, 1, output)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), output)
        copyScanLuma(input, 1, 1, 2, 2, 6, 1, output)
        assertArrayEquals(byteArrayOf(9, 10, 15, 16), output)
        copyScanLuma(input, 1, 1, 2, 2, 8, 2, output)
        assertArrayEquals(byteArrayOf(12, 14, 20, 22), output)
        assertEquals(2, input.position())
    }
}
