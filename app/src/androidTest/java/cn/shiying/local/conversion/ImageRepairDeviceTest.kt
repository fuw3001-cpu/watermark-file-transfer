package cn.shiying.local.conversion

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.shiying.local.repair.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageRepairDeviceTest {
    @Test fun localRepairRemovesMarkedLineAndPreservesOriginalAndUnselectedPixels() {
        val source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(source).apply {
            drawRect(48f,25f,52f,75f,Paint().apply { color=Color.BLACK })
            drawRect(0f,0f,10f,10f,Paint().apply { color=Color.BLUE })
        }
        val result=ImageRepair.repair(source,listOf(RepairStroke(listOf(RepairPoint(.5f,.25f),RepairPoint(.5f,.75f)),.06f)))
        try {
            assertTrue(Color.red(result.getPixel(50,50))>240)
            assertEquals(Color.BLUE,result.getPixel(5,5))
            assertEquals(Color.BLACK,source.getPixel(50,50))
            assertEquals(source.width,result.width);assertEquals(source.height,result.height)
        } finally { source.recycle();result.recycle() }
    }
    @Test fun cornerWatermarkOnLargePhotoPreservesTranslucentSurroundingsAndEdges() {
        val source = Bitmap.createBitmap(2880, 1920, Bitmap.Config.ARGB_8888)
        val paper = Color.argb(160, 210, 190, 120)
        source.eraseColor(paper)
        Canvas(source).drawRect(2750f, 1880f, 2880f, 1886f, Paint().apply { color = Color.BLACK })
        val strokes = listOf(RepairStroke(listOf(RepairPoint(2750f / 2879, 1883f / 1919), RepairPoint(1f, 1883f / 1919)), .006f))
        try {
            repeat(3) {
                val start = android.os.SystemClock.elapsedRealtime()
                val output = ImageRepair.repair(source, strokes)
                android.util.Log.i("ShiyingRepairQA", "2880x1920 corner repair ms=" + (android.os.SystemClock.elapsedRealtime() - start))
                try {
                    assertEquals(source.getPixel(40, 40), output.getPixel(40, 40))
                    assertEquals(source.getPixel(2740, 1870), output.getPixel(2740, 1870))
                    assertEquals(source.getPixel(2780, 1883).ushr(24), output.getPixel(2780, 1883).ushr(24))
                    assertTrue(Color.red(output.getPixel(2780, 1883)) > 120)
                    assertEquals(Color.BLACK, source.getPixel(2780, 1883))
                } finally { output.recycle() }
            }
        } finally { source.recycle() }
    }

    @Test fun overlyLargeMaskIsRejectedAndCancellationIsObserved() {
        val source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
        try {
            val mask=(1..9).map { y -> RepairStroke(listOf(RepairPoint(0f,y/10f),RepairPoint(1f,y/10f)),.08f) }
            assertThrows(IllegalArgumentException::class.java) { ImageRepair.repair(source,mask) }
            assertThrows(InterruptedException::class.java) { ImageRepair.repair(source,listOf(RepairStroke(listOf(RepairPoint(.5f,.5f)),.01f))) { throw InterruptedException() } }
        } finally { source.recycle() }
    }
}
