package com.example.zoomart

import com.example.zoomart.model.BrushPresets
import com.example.zoomart.model.BrushSpec
import com.example.zoomart.model.Camera
import com.example.zoomart.model.DocumentCodec
import com.example.zoomart.model.DrawingViewModel
import com.example.zoomart.model.LevelTree
import com.example.zoomart.model.StrokeData
import com.example.zoomart.model.StrokePoint
import com.example.zoomart.model.TouchRouting
import com.example.zoomart.model.ZoomLevel
import com.example.zoomart.model.ZoomLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 spec-correctness suite (docs/REVIEW_2026-10-18.md CONFLICT/PARTIAL items):
 * pen-vs-finger input routing, pencil pressure->opacity, smooth cross-level
 * camera flights, and cross-level marquee selection. All pure JVM.
 */
class P1SpecTest {

    // ---------------- P1.1: tool-type routing (pure logic) ----------------

    @Test
    fun penFamilyIsStylusAndEraserOnly() {
        assertTrue(TouchRouting.isPen(TouchRouting.TOOL_TYPE_STYLUS))
        assertTrue(TouchRouting.isPen(TouchRouting.TOOL_TYPE_ERASER))
        assertFalse(TouchRouting.isPen(TouchRouting.TOOL_TYPE_FINGER))
        assertFalse(TouchRouting.isPen(TouchRouting.TOOL_TYPE_MOUSE))
        assertFalse(TouchRouting.isPen(TouchRouting.TOOL_TYPE_UNKNOWN))
    }

    @Test
    fun eraserToolTypeForcesTheEraseTool() {
        assertTrue(TouchRouting.forcesErase(TouchRouting.TOOL_TYPE_ERASER))
        assertFalse(TouchRouting.forcesErase(TouchRouting.TOOL_TYPE_STYLUS))
        assertFalse(TouchRouting.forcesErase(TouchRouting.TOOL_TYPE_FINGER))
    }

    // ---------------- P1.2: pencil pressure -> opacity ----------------

    @Test
    fun pencilMapsPressureToOpacityWithNearConstantWidth() {
        val p = BrushPresets.PENCIL
        assertTrue("pencil must opt into pressure opacity", p.pressureOpacity)
        assertEquals("full pressure renders at full brush opacity", p.opacity, p.alphaForPressure(1f), 0.0001f)
        assertEquals("zero pressure renders at the opacity floor", p.opacity * p.minOpacity, p.alphaForPressure(0f), 0.0001f)
        // width response stays LOW (spec: pressure drives opacity, not width)
        val w1 = p.widthForPressure(1f)
        val w0 = p.widthForPressure(0f)
        assertTrue("pencil width must stay near constant: $w0..$w1", w1 / w0 < 1.45f)
        // opacity is monotone in pressure
        var last = -1f
        var pr = 0f
        while (pr <= 1.0001f) {
            val a = p.alphaForPressure(pr)
            assertTrue("alpha dipped at pressure $pr", a >= last - 0.000001f)
            last = a
            pr += 0.1f
        }
    }

    @Test
    fun pressureOpacityClampsOutOfRangePressure() {
        val p = BrushPresets.PENCIL
        assertEquals(p.alphaForPressure(0f), p.alphaForPressure(-0.5f), 0.0001f)
        assertEquals(p.alphaForPressure(1f), p.alphaForPressure(3f), 0.0001f)
    }

    @Test
    fun ordinaryBrushesKeepOpacityPressureIndependent() {
        val b = BrushSpec(opacity = 0.8f)
        assertFalse(b.pressureOpacity)
        assertEquals(0.8f, b.alphaForPressure(0f), 0.0001f)
        assertEquals(0.8f, b.alphaForPressure(1f), 0.0001f)
    }

    @Test
    fun codecPreservesPressureOpacityFields() {
        val vm = DrawingViewModel()
        vm.currentBrush = BrushPresets.PENCIL
        vm.beginStroke(0f, 0f, 0.2f)
        vm.extendStroke(10f, 4f, 0.9f)
        vm.endStroke()
        val json1 = vm.serialize()
        val session = DocumentCodec.fromJson(json1)
        val b = session.document.activeLayer.strokes[0].brush
        assertTrue("pressureOpacity lost in round trip", b.pressureOpacity)
        assertEquals(BrushPresets.PENCIL.minOpacity, b.minOpacity, 0.0001f)
        val json2 = DocumentCodec.toJson(session.document, session.camera)
        assertEquals("pressure-opacity fields must round-trip byte-stable", json1, json2)
    }

    // ---------------- P1.3a: smooth cross-level camera flights ----------------

    /** On-screen magnification of root units (the quantity a flight lerps in log space). */
    private fun mag(cam: Camera): Double = cam.scale.toDouble() * cam.level.chainScaleProduct()

    private fun deepTarget(tree: LevelTree): Camera {
        val deep = Camera(1f, 0f, 0f, tree.root)
        repeat(3) {
            deep.zoomAt(0f, 0f, 200f)
            deep.applyLevelTransitions(0f, 0f)
        }
        deep.scale = 2.5f
        deep.transX = 300f
        deep.transY = -150f
        return deep
    }

    @Test
    fun crossLevelFlightNeverHoldsAndStaysInTheLegalBand() {
        val tree = LevelTree()
        val from = Camera(1f, 0f, 0f, tree.root)
        val deep = deepTarget(tree)
        assertEquals(3, deep.level.depth)
        assertTrue("test must cross levels", mag(deep) > mag(from) * 100.0)

        var lastMag = mag(from)
        var t = 0.05f
        while (t < 0.999f) {
            val cam = from.interpolatedTo(deep, t)
            val m = mag(cam)
            assertTrue("flight held still at t=$t ($lastMag -> $m)", m > lastMag * 1.000001)
            lastMag = m
            if (cam.level.isRoot) {
                assertTrue(cam.scale >= Camera.MIN_SCALE - 0.0001f)
            } else {
                assertTrue(
                    "localScale out of [1,100) at t=$t: ${cam.scale}",
                    cam.scale >= 1f && cam.scale < ZoomLevels.THRESHOLD,
                )
            }
            t += 0.05f
        }
    }

    @Test
    fun crossLevelFlightIsScreenContinuous() {
        val tree = LevelTree()
        val from = Camera(1f, 0f, 0f, tree.root)
        val deep = deepTarget(tree)

        // a root point just off the flight anchor, sized to sit on-screen at the target
        val magTo = mag(deep)
        val probeX = deep.localToRootX(deep.level, deep.screenToLocalX(0f)) + 100.0 / magTo
        val probeY = deep.localToRootY(deep.level, deep.screenToLocalY(0f)) + 60.0 / magTo

        var prevX = 0.0
        var prevY = 0.0
        var prevSet = false
        var t = 0f
        while (t <= 0.999f) {
            val cam = from.interpolatedTo(deep, t)
            val k = cam.transformTo(tree.root) // root-local -> screen
            val sx = probeX * k[0] + k[1]
            val sy = probeY * k[0] + k[2]
            if (prevSet) {
                assertTrue("screen x jump at t=$t: ${Math.abs(sx - prevX)}", Math.abs(sx - prevX) < 200.0)
                assertTrue("screen y jump at t=$t: ${Math.abs(sy - prevY)}", Math.abs(sy - prevY) < 200.0)
            }
            prevX = sx
            prevY = sy
            prevSet = true
            t += 0.05f
        }
        // the flight actually went somewhere
        assertTrue(Math.abs(prevX) > 1.0 || Math.abs(prevY) > 1.0)
    }

    @Test
    fun crossLevelFlightLandsExactlyOnTargetBothWays() {
        val tree = LevelTree()
        val from = Camera(1f, 7.5f, -3.25f, tree.root)
        val deep = deepTarget(tree)

        val at0 = from.interpolatedTo(deep, 0f)
        assertEquals(1f, at0.scale, 0f)
        assertEquals(7.5f, at0.transX, 0f)
        assertSame(from.level, at0.level)

        val at1 = from.interpolatedTo(deep, 1f)
        assertSame(deep.level, at1.level)
        assertEquals(2.5f, at1.scale, 0f)
        assertEquals(300f, at1.transX, 0f)
        assertEquals(-150f, at1.transY, 0f)

        // the reverse (zoom-out) flight lands on the source camera too
        val back = deep.interpolatedTo(from, 1f)
        assertSame(from.level, back.level)
        assertEquals(1f, back.scale, 0.0001f)
        assertEquals(7.5f, back.transX, 0.0001f)
        assertEquals(-3.25f, back.transY, 0.0001f)
    }

    // ---------------- P1.3b: cross-level marquee selection ----------------

    /**
     * Four strokes around the root origin, in three frames:
     *  - S0 root        [100..600] x {100}
     *  - S1 child c1    local [10000..60000] x {10000}  (== S0's root region)
     *  - S2 child c2    local [10000..60000] x {10000}  (root frame [150..650] x {100})
     *  - S3 grandchild  local [1e6..6e6] x {1e6}        (== S0's root region, but NOT
     *                                                   visible from a root camera)
     */
    private fun scene(): DrawingViewModel {
        val vm = DrawingViewModel()
        val tree = vm.document.levelTree
        fun stroke(levelId: Long, x0: Float, y0: Float, x1: Float, y1: Float): StrokeData {
            val s = StrokeData()
            s.levelId = levelId
            s.addPoint(StrokePoint(x0, y0, 0.5f))
            s.addPoint(StrokePoint(x1, y1, 0.5f))
            vm.document.activeLayer.strokes.add(s)
            return s
        }
        stroke(0L, 100f, 100f, 600f, 100f)
        val c1 = tree.childAt(tree.root, 0f, 0f)
        stroke(c1.id, 10_000f, 10_000f, 60_000f, 10_000f)
        val c2 = tree.childAt(tree.root, 50f, 0f)
        stroke(c2.id, 10_000f, 10_000f, 60_000f, 10_000f)
        val g = tree.childAt(c1, 0f, 0f)
        stroke(g.id, 1_000_000f, 1_000_000f, 6_000_000f, 1_000_000f)
        vm.document.reindex()
        return vm
    }

    private fun child(vm: DrawingViewModel, index: Int): ZoomLevel =
        vm.document.levelTree.childrenOf(vm.document.levelTree.root)[index]

    @Test
    fun marqueeFromRootSelectsVisibleChildLevelStrokes() {
        val vm = scene()
        assertTrue(vm.selectInWorldRect(-10f, 90f, 700f, 120f))
        val ids = vm.document.selection.selected.map { it.levelId }.toSet()
        assertEquals(3, ids.size) // S0 + S1 + S2; the grandchild S3 is filtered out
        assertTrue(0L in ids)
        assertTrue(child(vm, 0).id in ids)
        assertTrue(child(vm, 1).id in ids)
    }

    @Test
    fun marqueeFromRootSelectsAChildStrokeWithoutRootOnes() {
        val vm = scene()
        val c2 = child(vm, 1)
        // root-frame region only S2's projected bbox covers
        assertTrue(vm.selectInWorldRect(610f, 90f, 700f, 120f))
        val sel = vm.document.selection.selected
        assertEquals(1, sel.size)
        assertEquals(c2.id, sel.first().levelId)
    }

    @Test
    fun marqueeFromDeepCameraReachesAncestorsAndChildrenNotSiblings() {
        val vm = scene()
        vm.camera.zoomAt(0f, 0f, 200f)
        vm.camera.applyLevelTransitions(0f, 0f)
        assertEquals(1, vm.camera.level.depth)
        assertSame(child(vm, 0), vm.camera.level)

        // camera at c1: visible set = {root, c1, grandchild}; sibling c2 excluded
        assertTrue(vm.selectInWorldRect(9_000f, 9_000f, 61_000f, 11_000f))
        val ids = vm.document.selection.selected.map { it.levelId }.toSet()
        assertEquals(3, ids.size)
        assertTrue(0L in ids)                       // root stroke, reached upward
        assertTrue(child(vm, 0).id in ids)          // camera level stroke
        assertTrue(vm.document.levelTree.childrenOf(child(vm, 0))[0].id in ids) // child reached downward
        assertFalse(child(vm, 1).id in ids)         // invisible sibling
    }

    @Test
    fun crossLevelTranslateMovesEachStrokeInItsOwnFrame() {
        val vm = DrawingViewModel()
        val tree = vm.document.levelTree
        val s0 = StrokeData()
        s0.addPoint(StrokePoint(100f, 100f, 0.5f))
        s0.addPoint(StrokePoint(600f, 100f, 0.5f))
        vm.document.activeLayer.strokes.add(s0)
        val c1 = tree.childAt(tree.root, 0f, 0f)
        val s1 = StrokeData()
        s1.levelId = c1.id
        s1.addPoint(StrokePoint(10_000f, 10_000f, 0.5f))
        s1.addPoint(StrokePoint(60_000f, 10_000f, 0.5f))
        vm.document.activeLayer.strokes.add(s1)
        vm.document.reindex()

        assertTrue(vm.selectInWorldRect(-10f, 90f, 700f, 120f))
        assertEquals(2, vm.document.selection.selected.size)

        vm.document.selection.beginTransform()
        vm.document.selection.translateBy(10f, 4f) // camera-frame (root, scale 1) delta
        vm.document.selection.endTransform()

        assertEquals(110f, s0.minX, 0.0001f) // root stroke: +10 root units
        assertEquals(104f, s0.minY, 0.0001f)
        assertEquals(11_000f, s1.minX, 0.01f) // c1 stroke: +10 * 100 in ITS frame
        assertEquals(10_400f, s1.minY, 0.01f)

        // one undo restores BOTH strokes exactly, in both frames
        assertTrue(vm.undo())
        assertEquals(100f, s0.minX, 0f)
        assertEquals(10_000f, s1.minX, 0f)
    }

    @Test
    fun selectionBboxSpansLevelsInTheCameraFrame() {
        val vm = scene()
        // S0 + S1 share the root region [100..600]; S2 (child level) projects
        // to [150..650] — the union spans levels in CAMERA-frame coordinates.
        assertTrue(vm.selectInWorldRect(-10f, 90f, 700f, 120f))
        val b = vm.document.selection.bbox()!!
        assertEquals(100f, b[0], 0.0001f)
        assertEquals(100f, b[1], 0.0001f)
        assertEquals(650f, b[2], 0.0001f)
        assertEquals(100f, b[3], 0.0001f)
    }
}
