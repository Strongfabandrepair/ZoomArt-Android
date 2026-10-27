package com.example.zoomart

import com.example.zoomart.model.BrushPresets
import com.example.zoomart.model.BrushSpec
import com.example.zoomart.model.Camera
import com.example.zoomart.model.DocumentCodec
import com.example.zoomart.model.DrawingViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocumentCodecTest {

    private fun sampleViewModel(): DrawingViewModel {
        val vm = DrawingViewModel()
        vm.camera.scale = 3.25f
        vm.camera.transX = -412.5f
        vm.camera.transY = 88.75f
        vm.currentBrush = BrushSpec(
            baseWidthWorld = 9.5f, pressureResponse = 1.75f, minWidthRatio = 0.3f,
            color = 0xFF123456.toInt(), opacity = 0.9f,
        )
        vm.beginStroke(0f, 0f, 0.2f)
        vm.extendStroke(15f, -4.5f, 0.65f)
        vm.extendStroke(30.25f, 10.5f, 0.9f)
        vm.endStroke()
        vm.currentBrush = BrushSpec()
        vm.beginStroke(-5f, 12f, 0.5f)
        vm.extendStroke(-6.75f, 14.25f, 0.5f)
        vm.endStroke()
        vm.addBookmark(11.5f, -22.25f, "start \"pin\" \\1")
        return vm
    }

    @Test
    fun roundTripIsLossless() {
        val vm = sampleViewModel()
        val restored = DocumentCodec.fromJson(vm.serialize())
        assertEquals(2, restored.document.layers.size)
        assertEquals(vm.camera.scale, restored.camera.scale, 0.0001f)
        assertEquals(vm.camera.transX, restored.camera.transX, 0.0001f)
        assertEquals(vm.camera.transY, restored.camera.transY, 0.0001f)
        val layer = restored.document.activeLayer
        assertEquals(2, layer.strokes.size)
        val s0 = layer.strokes[0]
        // P0 screen-relative width: the brush (9.5 screen-px at 1x) was committed
        // at camera scale 3.25, so the stored level-local width is 9.5/3.25.
        assertEquals(9.5f / 3.25f, s0.brush.baseWidthWorld, 0.0001f)
        assertEquals(1.75f, s0.brush.pressureResponse, 0.0001f)
        assertEquals(0.3f, s0.brush.minWidthRatio, 0.0001f)
        assertEquals(0xFF123456.toInt(), s0.brush.color)
        assertEquals(0.9f, s0.brush.opacity, 0.0001f)
        assertEquals(3, s0.points.size)
        assertEquals(30.25f, s0.points[2].x, 0.0001f)
        assertEquals(0.9f, s0.points[2].pressure01, 0.0001f)
        assertEquals(1, restored.document.bookmarks.size)
        assertEquals("start \"pin\" \\1", restored.document.bookmarks[0].label)
    }

    @Test
    fun serializeIsStableAcrossRoundTrips() {
        val vm = sampleViewModel()
        val json1 = vm.serialize()
        val session = DocumentCodec.fromJson(json1)
        val json2 = DocumentCodec.toJson(session.document, session.camera)
        assertEquals(json1, json2)
    }

    @Test
    fun parserRejectsGarbage() {
        for (bad in listOf("", "{oops", "[1,2,", "{\"a\":}", "not json", "tru", "{\"a\" 1}")) {
            try {
                DocumentCodec.fromJson(bad)
                fail("expected JsonError for: " + bad)
            } catch (e: DocumentCodec.JsonError) {
                // expected
            }
        }
    }

    @Test
    fun missingOptionalFieldsFallBackToDefaults() {
        val session = DocumentCodec.fromJson("{\"camera\":{},\"layers\":[]}")
        assertEquals(1f, session.camera.scale, 0.0001f)
        assertEquals(0, session.document.layers.size)
    }

    @Test
    fun restoreGuaranteesAtLeastOneLayer() {
        val vm = DrawingViewModel()
        vm.restore(DocumentCodec.fromJson("{\"camera\":{},\"layers\":[]}"))
        assertEquals(1, vm.document.layers.size)
        vm.beginStroke(0f, 0f, 1f)
        vm.extendStroke(2f, 0f, 1f)
        vm.endStroke()
        assertEquals(1, vm.document.activeLayer.strokes.size)
    }

    @Test
    fun cameraInterpolationHitsEndpoints() {
        val a = Camera(scale = 1f, transX = 0f, transY = 0f)
        val b = Camera(scale = 4f, transX = 100f, transY = -60f)
        val at0 = a.interpolatedTo(b, 0f)
        assertEquals(1f, at0.scale, 0.0001f)
        assertEquals(0f, at0.transX, 0.0001f)
        val at1 = a.interpolatedTo(b, 1f)
        assertEquals(4f, at1.scale, 0.0001f)
        assertEquals(100f, at1.transX, 0.0001f)
        assertEquals(-60f, at1.transY, 0.0001f)
    }

    @Test
    fun cameraInterpolationIsMonotonic() {
        val a = Camera(scale = 1f)
        val b = Camera(scale = 64f)
        var last = 0f
        var t = 0f
        while (t <= 1f) {
            val s = a.interpolatedTo(b, t).scale
            assertTrue("scale dipped at t=" + t, s >= last - 0.0001f)
            last = s
            t += 0.25f
        }
        assertEquals(64f, last, 0.001f)
    }

    @Test
    fun brushPresetsAreSane() {
        for ((name, preset) in BrushPresets.all) {
            assertTrue(name + " width must be positive", preset.baseWidthWorld > 0f)
            assertTrue(name + " response must be positive", preset.pressureResponse > 0f)
            assertTrue(name + " must have full alpha", (preset.color ushr 24) == 0xFF)
        }
    }
}
