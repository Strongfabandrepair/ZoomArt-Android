package com.example.zoomart

import com.example.zoomart.model.*
import org.junit.Assert.*
import org.junit.Test

class AtelierGeometryTest {
    private fun line() = StrokeData(BrushSpec(baseWidthWorld = 2f)).apply {
        levelId = 17; taper = 0.1f
        addPoint(StrokePoint(0f, 0f, 0.2f)); addPoint(StrokePoint(100f, 0f, 1f))
    }
    @Test fun sparseStrokeIsCutBySweepWithNoNearbyEndpoint() {
        val fragments = StrokeEraser.erase(line(), listOf(StrokePoint(50f,-20f,1f), StrokePoint(50f,20f,1f)), 3f)
        assertEquals(2, fragments.size)
        assertEquals(46f, fragments[0].points.last().x, 0.01f)
        assertEquals(54f, fragments[1].points.first().x, 0.01f)
        assertEquals(0.568f, fragments[0].points.last().pressure01, 0.001f)
        assertEquals(17L, fragments[0].levelId)
        assertEquals(0.1f, fragments[1].taper, 0f)
    }
    @Test fun noHitRetainsStrokeIdentity() {
        val source = line()
        assertSame(source, StrokeEraser.erase(source, listOf(StrokePoint(500f,500f,1f)),3f).single())
    }
    @Test fun eraserEndpointTrimWithSamePointCountIsAppliedAndUndoable() {
        val vm=DrawingViewModel()
        val stroke=line().apply { levelId=0 }
        vm.document.commitStroke(stroke)
        vm.eraserRadiusWorld=3f; vm.beginErase(100f,0f); vm.eraseTo(100f,1f); vm.endErase()
        assertTrue(vm.document.activeLayer.strokes.single().points.last().x < 100f)
        vm.undo(); assertSame(stroke,vm.document.activeLayer.strokes.single())
    }
    @Test fun siblingTransformUsesSharedDeepFrame() {
        val tree=LevelTree(); var shared=tree.root
        repeat(180) { shared=tree.childAt(shared,1000f,1000f) }
        val left=tree.childAt(shared,0f,0f)
        val right=tree.childAt(shared,10f,20f)
        val camera=Camera(2f,3f,4f,left)
        val transform=camera.transformTo(right)
        assertArrayEquals(floatArrayOf(2f,2003f,4004f),transform,0.01f)
    }
}
