package com.example.zoomart

import com.example.zoomart.model.BrushSpec
import com.example.zoomart.model.PathSink
import com.example.zoomart.model.StrokeOutline
import com.example.zoomart.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeOutlineTest {

    private val brush = BrushSpec(baseWidthWorld = 10f, pressureResponse = 1f, minWidthRatio = 0f)

    private class RecordingSink : PathSink {
        val ops = mutableListOf<String>()
        override fun moveTo(x: Float, y: Float) { ops.add("M $x,$y") }
        override fun lineTo(x: Float, y: Float) { ops.add("L $x,$y") }
        override fun close() { ops.add("Z") }
    }

    private fun pt(x: Float, y: Float, p: Float) = StrokePoint(x, y, p)

    @Test
    fun horizontalStrokeOutlineIsAClosedQuad() {
        val sink = RecordingSink()
        assertTrue(StrokeOutline.buildPath(listOf(pt(0f, 0f, 1f), pt(10f, 0f, 1f)), brush, sink))
        assertEquals(listOf("M 0.0,5.0", "L 10.0,5.0", "L 10.0,-5.0", "L 0.0,-5.0", "Z"), sink.ops)
    }

    @Test
    fun singlePointIsDegenerate() {
        assertFalse(StrokeOutline.buildPath(listOf(pt(0f, 0f, 1f)), brush, RecordingSink()))
    }

    @Test
    fun duplicateMidpointsStayFinite() {
        val (left, right) = StrokeOutline.outlineVertices(
            listOf(pt(0f, 0f, 1f), pt(10f, 0f, 1f), pt(10f, 0f, 1f), pt(20f, 0f, 1f)), brush)!!
        for (v in left + right) {
            assertTrue("non-finite vertex", v[0].isFinite() && v[1].isFinite())
        }
    }

    @Test
    fun pressureShapesTheHalfWidth() {
        val (left, right) = StrokeOutline.outlineVertices(
            listOf(pt(0f, 0f, 1f), pt(10f, 0f, 0.5f)), brush)!!
        assertEquals(5f, left[0][1], 0.001f)   // p=1 -> half 5
        assertEquals(2.5f, left[1][1], 0.001f) // p=0.5 -> half 2.5
        assertEquals(-5f, right[0][1], 0.001f)
    }
}
