package cn.shiying.local.conversion

import cn.shiying.local.meme.LayerTransform
import cn.shiying.local.meme.MemeText
import org.junit.Assert.*
import org.junit.Test

class MemeTransformTest {
    @Test fun draggingTextMovesBothAxesAndKeepsItsSize() {
        val original = MemeText(7, "你好", .3f, .7f, 32f)
        val moved = original.changed(.2f, -.3f)
        assertEquals(.5f, moved.x, .0001f); assertEquals(.4f, moved.y, .0001f)
        assertEquals(32f, moved.size, 0f); assertEquals("你好", moved.text)
        assertEquals(.3f, original.x, 0f)
    }

    @Test fun zoomAndNudgeStayBoundedForTextAndSubject() {
        val text = MemeText(1).changed(-4f, 4f, 100f)
        assertEquals(0f, text.x, 0f); assertEquals(1f, text.y, 0f); assertEquals(96f, text.size, 0f)
        val subject = LayerTransform().changed(-4f, 4f, .0001f)
        assertEquals(-3f, subject.x, 0f); assertEquals(4f, subject.y, 0f); assertEquals(.15f, subject.scale, 0f)
    }
    @Test fun subjectPinchKeepsTheTouchedSourcePointStationary() {
        val before = LayerTransform(.7f, .6f, 1f)
        val after = before.changed(0f, 0f, 2f, .2f, .2f)
        assertEquals((.2f-before.x)/before.scale, (.2f-after.x)/after.scale, .0001f)
        assertEquals((.2f-before.y)/before.scale, (.2f-after.y)/after.scale, .0001f)
    }
}
