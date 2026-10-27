package com.example.zoomart

import com.example.zoomart.model.BrushPresets
import com.example.zoomart.model.DrawingViewModel
import com.example.zoomart.model.StrokeData
import com.example.zoomart.model.StrokePoint
import com.example.zoomart.model.StrokeTransforms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTransformTest {

    private fun horizontalStroke(y: Float): StrokeData {
        val s = StrokeData()
        s.addPoint(StrokePoint(0f, y, 1f))
        s.addPoint(StrokePoint(10f, y, 1f))
        return s
    }

    @Test
    fun translatePreservesPressureAndMovesPoints() {
        val out = StrokeTransforms.translate(
            listOf(StrokePoint(1f, 2f, 0.5f)), 10f, -3f)
        assertEquals(11f, out[0].x, 0.001f)
        assertEquals(-1f, out[0].y, 0.001f)
        assertEquals(0.5f, out[0].pressure01, 0.001f)
    }

    @Test
    fun rotate90AboutOriginSwapsAxes() {
        val out = StrokeTransforms.rotateAbout(listOf(StrokePoint(5f, 0f, 1f)), 0f, 0f, 90f)
        assertEquals(0f, out[0].x, 0.001f)
        assertEquals(5f, out[0].y, 0.001f)
    }

    @Test
    fun scaleAboutPivotKeepsPivotFixed() {
        val out = StrokeTransforms.scaleAbout(listOf(StrokePoint(4f, 6f, 1f)), 4f, 6f, 3f, 3f)
        assertEquals(4f, out[0].x, 0.001f)
        assertEquals(6f, out[0].y, 0.001f)
    }

    @Test
    fun marqueeSelectsAndMoveIsOneUndoStep() {
        val vm = DrawingViewModel()
        vm.beginStroke(0f, 100f, 1f)
        vm.extendStroke(10f, 100f, 1f)
        vm.endStroke()
        vm.beginStroke(0f, 500f, 1f)       // far stroke, must NOT be selected
        vm.extendStroke(10f, 500f, 1f)
        vm.endStroke()

        assertTrue(vm.selectInWorldRect(-5f, 90f, 20f, 120f))
        assertEquals(1, vm.document.selection.selected.size)
        val sel = vm.document.selection.selected.first()
        val beforeX = sel.minX

        vm.document.selection.beginTransform()
        vm.document.selection.translateBy(50f, 0f)
        vm.document.selection.endTransform()
        assertEquals(beforeX + 50f, sel.minX, 0.001f)

        assertTrue(vm.document.undo())
        assertEquals(beforeX, sel.minX, 0.001f)  // points restored exactly
        assertTrue(vm.document.redo())
        assertEquals(beforeX + 50f, sel.minX, 0.001f)
    }

    @Test
    fun flipAndRotateAreUndoable() {
        val vm = DrawingViewModel()
        vm.beginStroke(0f, 0f, 1f)
        vm.extendStroke(10f, 0f, 1f)
        vm.endStroke()
        vm.selectInWorldRect(-1f, -1f, 11f, 1f)
        val sel = vm.document.selection.selected.first()
        val x0 = sel.minX

        vm.document.selection.beginTransform()
        vm.document.selection.rotate(90f)
        vm.document.selection.endTransform()
        assertTrue(vm.document.undo())
        assertEquals(x0, sel.minX, 0.001f)
    }

    @Test
    fun deleteSelectionRemovesAndUndoRestores() {
        val vm = DrawingViewModel()
        vm.beginStroke(0f, 0f, 1f)
        vm.extendStroke(10f, 0f, 1f)
        vm.endStroke()
        vm.selectInWorldRect(-1f, -1f, 11f, 1f)
        assertTrue(vm.document.selection.deleteSelection())
        assertEquals(0, vm.document.activeLayer.strokes.size)
        assertTrue(vm.document.undo())
        assertEquals(1, vm.document.activeLayer.strokes.size)
    }

    @Test
    fun selectionRespectsActiveLayerOnly() {
        val vm = DrawingViewModel()
        vm.beginStroke(0f, 100f, 1f)
        vm.extendStroke(10f, 100f, 1f)
        vm.endStroke()
        vm.document.addLayerAboveActive()
        assertFalse(vm.selectInWorldRect(-5f, 90f, 20f, 120f)) // new layer is empty
    }

    @Test
    fun imagePlacementAndCodecRoundTrip() {
        val vm = DrawingViewModel()
        val img = vm.placeImage("imports/imp_1.png", 1.5f)!!
        assertTrue(img.w > 0f && img.h > 0f)
        assertEquals(img.w / img.h, 1.5f, 0.01f)
        val session = com.example.zoomart.model.DocumentCodec.fromJson(vm.serialize())
        val restored = session.document.layers[vm.document.activeLayerIndex].images
        assertEquals(1, restored.size)
        assertEquals("imports/imp_1.png", restored[0].path)
        assertEquals(img.x, restored[0].x, 0.001f)
    }

    @Test
    fun newBrushPresetsAreSane() {
        for ((name, preset) in BrushPresets.all) {
            assertTrue(name + " width", preset.baseWidthWorld > 0f)
            assertTrue(name + " alpha", (preset.color ushr 24) == 0xFF)
        }
        assertTrue(BrushPresets.CALLIGRAPHY.angleDeg != 0f)
        assertTrue(BrushPresets.DASHED_PEN.dashOn > 0f && BrushPresets.DASHED_PEN.dashOff > 0f)
        assertTrue(BrushPresets.SPRAY.scatterWorld > 0f)
    }
}
