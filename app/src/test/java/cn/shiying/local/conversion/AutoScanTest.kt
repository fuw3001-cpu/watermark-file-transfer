package cn.shiying.local.conversion

import cn.shiying.local.scan.*
import org.junit.Assert.*
import org.junit.Test

class AutoScanTest {
    private val page=ScanQuad(listOf(ScanPoint(.1f,.1f),ScanPoint(.8f,.1f),ScanPoint(.8f,.9f),ScanPoint(.1f,.9f)))
    @Test fun stablePageCapturesOnceAndMustLeaveBeforeNextCapture() {
        val gate=AutoCaptureGate()
        assertFalse(gate.update(page,true,0).capture)
        assertTrue(gate.update(page,true,1200).capture)
        assertFalse(gate.update(page,true,5000).capture)
        gate.update(null,true,5100);gate.update(null,true,5800)
        assertFalse(gate.update(page,true,5900).capture)
        assertTrue(gate.update(page,true,7100).capture)
    }
    @Test fun blurAndContinuousDriftCannotTriggerAutomaticCapture() {
        val gate=AutoCaptureGate()
        for(i in 0..20) assertFalse(gate.update(page,false,i*1000L).capture)
        gate.retry()
        for(i in 0..15) {
            val moved=ScanQuad(page.points.map { it.copy(x=it.x+i*.005f) })
            assertFalse(gate.update(moved,true,i*250L).capture)
        }
    }
    @Test fun pausedAutomaticScanningRestartsAFullStabilityInterval() {
        val gate=AutoCaptureGate()
        gate.update(page,true,0,false);gate.update(page,true,5000,false)
        assertFalse(gate.update(page,true,6000,true).capture)
        assertTrue(gate.update(page,true,7200,true).capture)
    }
    @Test fun highQualityPreservesSourceDetailWithoutUpscalingSmallImages() {
        assertEquals(1500 to 2000,ScanQuad().outputSize(1500,2000,3508,10_000_000))
        val high=ScanQuad().outputSize(4000,5600,3508,10_000_000)
        assertTrue(high.first>2000 && high.second in 3506..3508)
        assertTrue(high.first.toLong()*high.second<=10_000_000)
        val square=ScanQuad().outputSize(8000,8000,4800,12_000_000)
        assertTrue(square.first.toLong()*square.second<=12_000_000)
    }
    @Test fun highResolutionBlackWhiteProcessingDoesNotOverflowIntegralSums() {
        val pixels=IntArray(3600*3000) { 0xffeeeeee.toInt() }
        val result=ScanFilters.applyInPlace(pixels,3600,3000,ScanMode.BLACK_WHITE)
        assertSame(pixels,result)
        for(i in listOf(0,3600*1500+1800,pixels.lastIndex)) assertEquals(0xffffffff.toInt(),result[i])
    }
    @Test fun enhancedFineStrokesStayDarkWithoutTurningPaperNoiseIntoText() {
        val w=120;val h=100
        val source=IntArray(w*h) { i -> val g=218+i%5;0xff000000.toInt() or (g shl 16) or (g shl 8) or g }
        for(y in 25..75) source[y*w+60]=0xff969696.toInt()
        val result=ScanFilters.apply(source,w,h,ScanMode.ENHANCE)
        assertTrue("Fine gray stroke should keep its contrast",(result[50*w+60] and 255)<170)
        val background=(10..30).map { result[10*w+it] and 255 }
        assertTrue(background.min()>230)
        assertTrue("Small paper noise should stay small",background.max()-background.min()<10)
    }
    @Test fun enhancementDoesNotWashOutLargeDarkPrintedAreas() {
        val source=IntArray(100*100) { 0xff303030.toInt() }
        val result=ScanFilters.apply(source,100,100,ScanMode.ENHANCE)
        assertTrue((result[50*100+50] and 255)<120)
    }
}
