package cn.shiying.local.conversion

import cn.shiying.local.scan.*
import org.junit.Assert.*
import org.junit.Test

class ScanCoreTest {
    @Test fun quadRejectsCrossedCornersAndBoundsCorrectionSize() {
        val original=ScanQuad()
        assertEquals(original,original.moved(0,1f,1f))
        val moved=original.moved(0,.1f,.1f)
        assertTrue(moved.valid());assertTrue(moved.area<1)
        assertEquals(2000 to 1500,original.outputSize(4000,3000))
        assertFalse(ScanQuad(listOf(ScanPoint(Float.NaN,0f))).valid())
    }

    @Test fun brightTrapezoidPaperIsDetectedWithoutTakingTextHolesAsBoundary() {
        val w=240;val h=240
        val quad=ScanQuad(listOf(ScanPoint(.18f,.12f),ScanPoint(.87f,.20f),ScanPoint(.80f,.90f),ScanPoint(.10f,.80f)))
        fun inside(x:Float,y:Float)=quad.points.indices.all { i ->
            val a=quad.points[i];val b=quad.points[(i+1)%4];(b.x-a.x)*(y-a.y)-(b.y-a.y)*(x-a.x)>=0
        }
        val gray=IntArray(w*h) { i -> if(inside((i%w+.5f)/w,(i/w+.5f)/h)) 240 else 35 }
        for(y in 90..130) for(x in 80..150) if(y%12<3) gray[y*w+x]=30
        val result=PaperEdges.detect(w,h,gray) ?: error("Expected paper")
        quad.points.zip(result.points).forEach { (a,b) -> assertEquals(a.x,b.x,.025f);assertEquals(a.y,b.y,.025f) }
    }

    @Test fun blankWhiteBackgroundIsNotReportedAsAnAutomaticPaperBoundary() {
        assertNull(PaperEdges.detect(80,60,IntArray(80*60) { 255 }))
    }

    @Test fun filtersPreserveOriginalAndSeparateTextFromUnevenGrayPaper() {
        val w=120;val h=100
        val source=IntArray(w*h) { i -> val v=160+(i%w)*70/w;0xff000000.toInt() or (v shl 16) or (v shl 8) or v }
        for(y in 40..44) for(x in 20..99) source[y*w+x]=0xff202020.toInt()
        val original=source.copyOf()
        val bw=ScanFilters.apply(source,w,h,ScanMode.BLACK_WHITE)
        assertEquals(0xff000000.toInt(),bw[42*w+50]);assertEquals(0xffffffff.toInt(),bw[10*w+10])
        assertEquals(0xffffffff.toInt(),bw[10*w+110])
        val enhanced=ScanFilters.apply(source,w,h,ScanMode.ENHANCE)
        assertTrue((enhanced[10*w+10] and 255)>230)
        assertTrue(kotlin.math.abs((enhanced[10*w+10] and 255)-(enhanced[10*w+110] and 255))<20)
        assertArrayEquals(original,source)
    }

    @Test fun blackWhiteKeepsSolidInkAndPaleShadowedPaper() {
        val width = 320
        val height = 200
        val paper = IntArray(width * height) { index ->
            val gray = 135 + (index % width) * 90 / width
            (0xff000000.toInt() or (gray * 0x010101))
        }
        for (y in 40 until 145) for (x in 42 until 184) paper[y * width + x] = 0xff262626.toInt()
        val result = ScanFilters.apply(paper, width, height, ScanMode.BLACK_WHITE)
        for (y in 42 until 143) for (x in 44 until 182) assertEquals(0xff000000.toInt(), result[y * width + x])
        for (y in 0 until height) assertEquals(0xffffffff.toInt(), result[y * width + 5])
    }

    @Test fun grayscaleHasEqualChannelsAndProcessingCanBeCancelled() {
        val pixels=IntArray(60*60) { 0xffff0040.toInt() }
        val gray=ScanFilters.apply(pixels,60,60,ScanMode.GRAY)
        assertEquals(gray[0] and 255,gray[0] shr 16 and 255)
        assertEquals(gray[0] and 255,gray[0] shr 8 and 255)
        var checks=0
        assertThrows(InterruptedException::class.java) {
            ScanFilters.apply(pixels,60,60,ScanMode.BLACK_WHITE) { if(++checks==4) throw InterruptedException() }
        }
    }
}
