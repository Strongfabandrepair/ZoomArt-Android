package com.example.zoomart

import com.example.zoomart.model.DrawingViewModel
import com.example.zoomart.model.StrokeData
import com.example.zoomart.model.StrokeEraser
import com.example.zoomart.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EraserTest {

    private fun stroke5(): StrokeData {
        val s = StrokeData()
        s.addPoint(StrokePoint(0f, 0f, 1f))
        s.addPoint(StrokePoint(3f, 0f, 1f))
        s.addPoint(StrokePoint(5f, 0f, 1f))
        s.addPoint(StrokePoint(7f, 0f, 1f))
        s.addPoint(StrokePoint(10f, 0f, 1f))
        return s
    }

    @Test
    fun erasingTheMiddleSplitsIntoTwoFragments() {
        val frags = StrokeEraser.erase(stroke5(), listOf(StrokePoint(5f, 0f, 0f)), 1f)
        assertEquals(2, frags.size)
        assertEquals(2, frags[0].points.size)
        assertEquals(2, frags[1].points.size)
        assertEquals(0f, frags[0].points[0].x, 0.001f)
        assertEquals(10f, frags[1].points[1].x, 0.001f)
    }

    @Test
    fun missingTheStrokeChangesNothing() {
        val frags = StrokeEraser.erase(stroke5(), listOf(StrokePoint(0f, 500f, 0f)), 4f)
        assertEquals(1, frags.size)
        assertEquals(5, frags[0].points.size)
    }

    @Test
    fun erasingEverythingRemovesTheStroke() {
        val s = stroke5()
        val centers = s.points.map { StrokePoint(it.x, it.y, 0f) }
        assertTrue(StrokeEraser.erase(s, centers, 1f).isEmpty())
    }

    @Test
    fun viewModelEraseIsOneUndoStepAndRoundTripsThroughRedo() {
        val vm = DrawingViewModel()
        vm.beginStroke(0f, 0f, 1f)
        vm.extendStroke(3f, 0f, 1f)
        vm.extendStroke(5f, 0f, 1f)
        vm.extendStroke(7f, 0f, 1f)
        vm.extendStroke(10f, 0f, 1f)
        vm.endStroke()
        assertEquals(1, vm.document.activeLayer.strokes.size)

        vm.eraserRadiusWorld = 1f
        vm.beginErase(5f, 0f)
        vm.eraseTo(5.5f, 0f)
        vm.endErase()
        assertEquals(2, vm.document.activeLayer.strokes.size)

        assertTrue(vm.undo())
        assertEquals(1, vm.document.activeLayer.strokes.size)
        assertEquals(5, vm.document.activeLayer.strokes[0].points.size)

        assertTrue(vm.redo())
        assertEquals(2, vm.document.activeLayer.strokes.size)
    }
}
