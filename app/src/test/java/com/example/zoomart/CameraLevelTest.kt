package com.example.zoomart

import com.example.zoomart.model.BrushSpec
import com.example.zoomart.model.Camera
import com.example.zoomart.model.DocumentCodec
import com.example.zoomart.model.DrawingDocument
import com.example.zoomart.model.DrawingViewModel
import com.example.zoomart.model.LevelTree
import com.example.zoomart.model.StrokeData
import com.example.zoomart.model.StrokePoint
import com.example.zoomart.model.StrokeSmoother
import com.example.zoomart.model.ZoomLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 test suite (docs/P0_ZOOM_LEVELS.md): the seven mandated designs for the
 * nested zoom-level architecture, plus the retained pure-model coverage from
 * the old CanvasTransformTest (pressure curve, zoom-aware input filter,
 * undo/redo semantics, VM lifecycle). All pure JVM — no Android imports.
 */
class CameraLevelTest {

    /**
     * Builds a camera at [depth] levels, promoting at the ROOT ORIGIN so the
     * whole origin chain is exactly (0,0) — the coordinate regime the P0
     * invariant describes (local coords stay small, root coords are pure
     * scale compositions). A focus far from the origin would add a large
     * absolute base under 100^-30-sized detail, which no finite precision
     * can carry; the app never forms such absolute deep coordinates (the
     * render path composes transforms level-by-level instead).
     */
    private fun deepCamera(depth: Int, focusX: Float = 0f, focusY: Float = 0f): Camera {
        val cam = Camera(1f, 0f, 0f, LevelTree().root)
        repeat(depth) {
            cam.zoomAt(focusX, focusY, 200f) // overshoot past 100 -> clean promote
            cam.applyLevelTransitions(focusX, focusY)
        }
        return cam
    }

    private fun chainOf(cam: Camera): List<com.example.zoomart.model.ZoomLevel> {
        val chain = ArrayList<com.example.zoomart.model.ZoomLevel>()
        var l: com.example.zoomart.model.ZoomLevel? = cam.level
        while (l != null) {
            chain.add(l)
            l = l.parent
        }
        return chain // deepest first, root last
    }

    // ----- P0 test 1: screen <-> local <-> root round trips, 30-deep chain -----

    @Test
    fun roundTripScreenLocalRootAcross30DeepChain() {
        val cam = deepCamera(30)
        assertEquals(30, cam.level.depth)
        val chain = chainOf(cam) // [L30, L29, ..., root]
        assertEquals(31, chain.size)

        val samples = listOf(
            37.5f to -12.25f,
            0f to 0f,
            -4000f to 9000f,
            1234.5678f to -9876.5f,
        )

        // local <-> root (Double chain) reconstructed to < 0.01 local units
        // at EVERY depth of the chain.
        for (level in chain) {
            for ((x, y) in samples) {
                val rx = cam.localToRootX(level, x)
                val ry = cam.localToRootY(level, y)
                val backX = cam.rootToLocalX(level, rx)
                val backY = cam.rootToLocalY(level, ry)
                assertTrue(
                    "x round trip drifted at depth ${level.depth}: $x -> $rx -> $backX",
                    Math.abs(backX - x) < 0.01,
                )
                assertTrue(
                    "y round trip drifted at depth ${level.depth}: $y -> $ry -> $backY",
                    Math.abs(backY - y) < 0.01,
                )
            }
        }

        // screen <-> local at a camera living at EVERY depth (fresh descend;
        // snapping the depth-30 camera to root would need effective scale
        // 2e-60, which is exactly the float-range problem levels exist to
        // avoid — so each depth gets its own camera at localScale 2).
        for (d in 0..30) {
            val c = deepCamera(d)
            for ((x, y) in samples) {
                val sx = c.localToScreenX(x)
                val sy = c.localToScreenY(y)
                assertEquals(x, c.screenToLocalX(sx), 0.0001f)
                assertEquals(y, c.screenToLocalY(sy), 0.0001f)
            }
        }

        // root coordinates at depth 30 really are ~100^-30 magnitudes (the
        // Double chain is doing the work floats could never do)
        val deep = cam.level
        val rxDeep = Math.abs(cam.localToRootX(deep, 1f))
        assertTrue("root magnitude vanished: $rxDeep", rxDeep > 1e-100)
        assertTrue("root magnitude too large: $rxDeep", rxDeep < 1e-50)
    }

    // ----- P0 test 2: promote at 100x keeps the focus (and every point) on screen -----

    @Test
    fun promoteAt100xKeepsFocusScreenPosition() {
        val tree = LevelTree()
        val cam = Camera(1f, 50f, -30f, tree.root)
        val probePoints = listOf(10f to 20f, -40f to 55.5f, 0f to 0f)
        fun screenAt(p: Pair<Float, Float>) =
            floatArrayOf(cam.localToScreenX(p.first), cam.localToScreenY(p.second))

        cam.zoomAt(300f, 400f, 100f) // exactly the threshold
        val before = probePoints.map { screenAt(it) }
        assertTrue(cam.applyLevelTransitions(300f, 400f))
        assertEquals(1, cam.level.depth)
        assertEquals(1f, cam.scale, 1e-6f)

        // the focus point became the child origin -> still at (300, 400)
        assertEquals(300f, cam.localToScreenX(0f), 0.01f)
        assertEquals(400f, cam.localToScreenY(0f), 0.01f)

        // every sampled point keeps its screen position (compose root through
        // the new camera's parent chain: transformTo -> [k, tx, ty])
        val t = cam.transformTo(tree.root)
        for (i in probePoints.indices) {
            val (x, y) = probePoints[i]
            assertEquals(before[i][0], x * t[0] + t[1], 0.01f)
            assertEquals(before[i][1], y * t[0] + t[2], 0.01f)
        }

        // overshoot promote: 150x hands off to the child at exactly 1.5x
        val tree2 = LevelTree()
        val cam2 = Camera(1f, 0f, 0f, tree2.root)
        cam2.zoomAt(0f, 0f, 150f)
        val before2 = floatArrayOf(cam2.localToScreenX(77.7f), cam2.localToScreenY(-3.3f))
        assertTrue(cam2.applyLevelTransitions(0f, 0f))
        assertEquals(1.5f, cam2.scale, 1e-4f)
        assertEquals(before2[0], cam2.localToScreenX(cam2.rootToLocalX(cam2.level, cam2.localToRootX(tree2.root, 77.7f))), 0.01f)
        val t2 = cam2.transformTo(tree2.root)
        assertEquals(before2[0], 77.7f * t2[0] + t2[1], 0.01f)
        assertEquals(before2[1], -3.3f * t2[0] + t2[2], 0.01f)
    }

    // ----- P0 test 3: demote is the exact inverse of promote -----

    @Test
    fun demoteIsExactInverseOfPromote() {
        // exact (dyadic) values: the whole cycle is float-exact
        val tree = LevelTree()
        val cam = Camera(1f, 100f, 200f, tree.root)
        cam.zoomAt(300f, 400f, 100f)
        val scaleBefore = cam.scale
        val transXBefore = cam.transX
        val transYBefore = cam.transY
        val levelBefore = cam.level

        cam.promote(300f, 400f)
        assertEquals(1, cam.level.depth)
        cam.demote()
        assertSame(levelBefore, cam.level)
        assertEquals(scaleBefore, cam.scale, 0f)
        assertEquals(transXBefore, cam.transX, 0f)
        assertEquals(transYBefore, cam.transY, 0f)

        // messy values: still the inverse to within float rounding
        val cam2 = Camera(3.7f, -123.456f, 987.654f, LevelTree().root)
        cam2.zoomAt(321.7f, 123.4f, 177f)
        val s2 = cam2.scale; val tx2 = cam2.transX; val ty2 = cam2.transY
        cam2.promote(321.7f, 123.4f)
        cam2.demote()
        assertEquals(s2, cam2.scale, 1e-3f)
        assertEquals(tx2, cam2.transX, 1e-2f)
        assertEquals(ty2, cam2.transY, 1e-2f)

        // repeated hop cycle never drifts (30 promote/demote round trips)
        val cam3 = Camera(1f, 0f, 0f, LevelTree().root)
        cam3.zoomAt(500f, 500f, 100f)
        val tx0 = cam3.transX
        repeat(30) {
            cam3.promote(500f, 500f)
            cam3.demote()
        }
        assertEquals(tx0, cam3.transX, 1e-2f)
    }

    // ----- P0 test 4: zoom-out integrity, stroke from level 12 -----

    @Test
    fun zoomOutIntegrityStrokeFromLevel12ProjectsToRightRootExtent() {
        val cam = deepCamera(12)
        val deepLevel = cam.level
        assertEquals(12, deepLevel.depth)

        // a stroke committed in level 12's local frame
        val s = StrokeData()
        s.levelId = deepLevel.id
        s.addPoint(StrokePoint(100f, 100f, 0.5f))
        s.addPoint(StrokePoint(600f, 100f, 0.5f))

        // truth: root extent of the stroke bbox, computed in Double
        val rootMinX = cam.localToRootX(deepLevel, s.minX)
        val rootMaxX = cam.localToRootX(deepLevel, s.maxX)
        val rootExtent = rootMaxX - rootMinX
        assertTrue("root extent vanished: $rootExtent", rootExtent > 0)
        assertTrue("root extent not deep-tiny: $rootExtent", rootExtent < 1e-20)

        // demote all the way to root, then project the bbox through the
        // composed render transform (float chain)
        while (!cam.level.isRoot) cam.demote()
        assertTrue(cam.level.isRoot)
        val t = cam.transformTo(deepLevel)
        val projectedExtent = (s.maxX - s.minX) * t[0]

        val expectedOnScreen = rootExtent * cam.effectiveScaleToRoot()
        assertEquals(expectedOnScreen, projectedExtent.toDouble(), expectedOnScreen * 1e-3)
    }

    // ----- P0 test 5: childAt reuses the same child for repeated promotes -----

    @Test
    fun childAtReusesSameChildForRepeatedPromote() {
        val tree = LevelTree()
        val cam = Camera(1f, 0f, 0f, tree.root)

        cam.zoomAt(300f, 400f, 100f)
        cam.applyLevelTransitions(300f, 400f)
        val child1 = cam.level
        assertEquals(2, tree.levelCount()) // root + child1

        // demote and re-promote at the SAME focus: same child, no new level
        cam.demote()
        cam.zoomAt(300f, 400f, 100f)
        cam.applyLevelTransitions(300f, 400f)
        assertSame(child1, cam.level)
        assertEquals(2, tree.levelCount())

        // a focus 20 screen-px away (0.2 parent units) still reuses the child
        cam.demote()
        cam.zoomAt(320f, 400f, 100f)
        cam.applyLevelTransitions(320f, 400f)
        assertSame(child1, cam.level)
        assertEquals(2, tree.levelCount())

        // a focus far outside the tolerance forks a new child
        cam.demote()
        cam.zoomAt(800f, 400f, 100f)
        cam.applyLevelTransitions(800f, 400f)
        assertFalse(cam.level === child1)
        assertEquals(3, tree.levelCount())
    }

    // ----- P0 test 6: codec v3 round-trips a 30-level tree byte-stable; v1 migrates -----

    @Test
    fun codecRoundTrips30LevelTreeByteStableAndMigratesV1() {
        val vm = DrawingViewModel()
        repeat(30) {
            vm.camera.zoomAt(500f, 500f, 200f)
            vm.camera.applyLevelTransitions(500f, 500f)
        }
        val deep = vm.camera.level
        assertEquals(30, deep.depth)

        fun commitAt(levelId: Long, x: Float, y: Float) {
            val s = StrokeData()
            s.levelId = levelId
            s.addPoint(StrokePoint(x, y, 0.5f))
            s.addPoint(StrokePoint(x + 250f, y + 40f, 0.75f))
            vm.document.activeLayer.strokes.add(s)
        }
        val root = vm.document.levelTree.root
        val mid = vm.document.levelTree.byId(deep.id)!!.let { l -> var p = l; repeat(25) { p = p.parent!! }; p }
        commitAt(root.id, 0f, 0f)
        commitAt(mid.id, 1000f, -2000f)
        commitAt(deep.id, 321.25f, 999.5f)
        vm.document.addBookmark(10f, 20f, 2.5f, "root pin", root.id)
        vm.document.addBookmark(1f, 2f, 3f, "deep pin", deep.id)
        vm.document.reindex()

        val json1 = DocumentCodec.toJson(vm.document, vm.camera)
        val session = DocumentCodec.fromJson(json1)
        val json2 = DocumentCodec.toJson(session.document, session.camera)
        assertEquals("round trip must be byte-stable", json1, json2)

        // tree rebuilt identically: same shape, ids, and parent chain
        val tree2 = session.document.levelTree
        assertEquals(31, tree2.levelCount())
        val deep2 = tree2.byId(deep.id)!!
        assertEquals(30, deep2.depth)
        assertEquals(deep.id, deep2.id)
        val mid2 = run { var l = deep2; repeat(25) { l = l.parent!! }; l }
        assertEquals(mid.id, mid2.id)
        assertEquals(mid.depth, mid2.depth)
        // the serialized camera lands back on the deep level of the new tree
        assertEquals(deep.id, session.camera.level.id)
        assertEquals(30, session.camera.level.depth)

        // strokes land in their levels with exact geometry
        val strokes = session.document.activeLayer.strokes
        assertEquals(3, strokes.size)
        assertEquals(root.id, strokes[0].levelId)
        assertEquals(mid.id, strokes[1].levelId)
        assertEquals(deep.id, strokes[2].levelId)
        assertEquals(321.25f, strokes[2].points[0].x, 0f)
        assertEquals(999.5f, strokes[2].points[0].y, 0f)
        assertEquals(571.25f, strokes[2].points[1].x, 0f)

        // bookmarks keep their levels; camera keeps its level
        assertEquals(root.id, session.document.bookmarks[0].levelId)
        assertEquals(deep.id, session.document.bookmarks[1].levelId)
        assertEquals(deep.id, session.camera.level.id)

        // ---- legacy v1 session file migrates losslessly to the root level ----
        val v1 = "{\"version\":1,\"camera\":{\"scale\":2.5,\"transX\":-100.0,\"transY\":40.0}," +
            "\"activeLayer\":1," +
            "\"layers\":[" +
            "{\"name\":\"Background\",\"visible\":true,\"opacity\":1.0,\"images\":[],\"strokes\":[]}," +
            "{\"name\":\"Layer 1\",\"visible\":true,\"opacity\":1.0,\"images\":[]," +
            "\"strokes\":[{\"brush\":{\"width\":9.5,\"response\":2.0,\"minRatio\":0.15," +
            "\"color\":-12303292,\"opacity\":1.0}," +
            "\"points\":[10.0,20.0,0.5,30.5,-40.25,0.75]}]}]," +
            "\"bookmarks\":[{\"worldX\":5.0,\"worldY\":6.0,\"scale\":2.5,\"label\":\"old pin\"}]}"
        val restored = DocumentCodec.fromJson(v1)
        assertEquals(1, restored.document.levelTree.levelCount()) // root only
        assertSame(restored.document.levelTree.root, restored.camera.level)
        val legacy = restored.document.activeLayer.strokes[0]
        assertEquals(0L, legacy.levelId)
        assertEquals(9.5f, legacy.brush.baseWidthWorld, 0.0001f)
        assertEquals(2, legacy.points.size)
        assertEquals(30.5f, legacy.points[1].x, 0.0001f)
        assertEquals(-40.25f, legacy.points[1].y, 0.0001f)
        assertEquals(0.75f, legacy.points[1].pressure01, 0.0001f)
        assertEquals(0L, restored.document.bookmarks[0].levelId)
        assertEquals("old pin", restored.document.bookmarks[0].label)
        assertEquals(2.5f, restored.camera.scale, 0.0001f)
    }

    // ----- P0 test 7: jitter probe, render-bbox width at depth 30 vs depth 0 -----

    @Test
    fun jitterProbeBboxWidthStableAtDepth30VsDepth0() {
        val cam30 = deepCamera(30) // camera at depth 30, localScale 2.0
        val tree = cam30.level.tree
        val cam0 = Camera(2f, cam30.transX, cam30.transY, tree.root)

        // a "fixed stroke" at the upper edge of the coordinate invariant
        val minX = 9_000f
        val maxX = 9_050f // 50 local units wide

        fun projectedWidth(cam: Camera): Float {
            val t = cam.transformTo(cam.level)
            return (maxX * t[0] + t[1]) - (minX * t[0] + t[1])
        }

        val w0 = projectedWidth(cam0)
        val w30 = projectedWidth(cam30)
        assertEquals(100f, w0, 0.01f) // 50 units * 2.0x
        assertTrue(
            "render width jittered at depth 30: $w30 vs $w0",
            Math.abs(w30 - w0) / w0 < 1e-3,
        )

        // ancestor projection path (3 levels up, as the renderer composes it)
        var ancestor = cam30.level
        repeat(3) { ancestor = ancestor.parent!! }
        val tA = cam30.transformTo(ancestor)
        val wAnc = (maxX * tA[0] + tA[1]) - (minX * tA[0] + tA[1])
        val expectedAnc = 50f * cam30.scale * 100f * 100f * 100f
        assertTrue(
            "ancestor-projected width jittered: $wAnc vs $expectedAnc",
            Math.abs(wAnc - expectedAnc) / expectedAnc < 1e-3,
        )
    }

    // ----- retained from the old CanvasTransformTest: camera basics (root level) -----

    @Test
    fun localToScreenAppliesScaleThenTranslate() {
        val cam = Camera(scale = 2f, transX = -150f, transY = -50f)
        assertEquals(1850f, cam.localToScreenX(1000f), 0.01f)
        assertEquals(950f, cam.localToScreenY(500f), 0.01f)
    }

    @Test
    fun screenToLocalIsInverseOfLocalToScreen() {
        val cam = Camera(scale = 7.5f, transX = 123.4f, transY = -987.6f)
        assertEquals(42.42f, cam.screenToLocalX(cam.localToScreenX(42.42f)), 0.0001f)
        assertEquals(-17.3f, cam.screenToLocalY(cam.localToScreenY(-17.3f)), 0.0001f)
    }

    @Test
    fun zoomAtKeepsTheLocalPointUnderTheFocal() {
        val cam = Camera(scale = 1f)
        cam.panBy(50f, 30f)
        val worldX = cam.screenToLocalX(300f)
        val worldY = cam.screenToLocalY(400f)
        cam.zoomAt(300f, 400f, 4f)
        assertEquals(300f, cam.localToScreenX(worldX), 0.01f)
        assertEquals(400f, cam.localToScreenY(worldY), 0.01f)
    }

    @Test
    fun zoomIsClampedAtTheRootLevelAndByPromotionBeyondIt() {
        val cam = Camera() // root
        cam.zoomAt(0f, 0f, 1_000_000f)
        assertEquals(Camera.OVERSHOOT_SCALE, cam.scale, 0.001f)
        cam.zoomAt(0f, 0f, 0.000001f)
        assertEquals(Camera.MIN_SCALE, cam.scale, 0.001f)
        // zooming in past the threshold at root hands off to a child level
        cam.scale = 1f
        cam.zoomAt(0f, 0f, 250f)
        cam.applyLevelTransitions(0f, 0f)
        assertEquals(1, cam.level.depth)
        assertTrue(cam.scale in 1f..(ZoomLevels.THRESHOLD + 0.001f))
    }

    @Test
    fun visibleLocalRectMatchesCornerUnprojection() {
        val cam = Camera(scale = 3f, transX = 10f, transY = -20f)
        val r = cam.visibleLocalRect(1000f, 500f)
        assertEquals(cam.screenToLocalX(0f), r[0], 0.001f)
        assertEquals(cam.screenToLocalY(0f), r[1], 0.001f)
        assertEquals(cam.screenToLocalX(1000f), r[2], 0.001f)
        assertEquals(cam.screenToLocalY(500f), r[3], 0.001f)
    }

    // ----- retained: BrushSpec pressure curve -----

    @Test
    fun pressureMapsZeroToMinAndOneToBase() {
        val brush = BrushSpec(baseWidthWorld = 10f, pressureResponse = 1f, minWidthRatio = 0.2f)
        assertEquals(2f, brush.widthForPressure(0f), 0.001f)
        assertEquals(10f, brush.widthForPressure(1f), 0.001f)
    }

    @Test
    fun pressureCurveIsMonotonic() {
        val brush = BrushSpec(pressureResponse = 2f)
        var last = -1f
        var p = 0f
        while (p <= 1.0001f) {
            val w = brush.widthForPressure(p)
            assertTrue("width shrank at pressure $p", w >= last - 0.000001f)
            last = w
            p += 0.1f
        }
    }

    @Test
    fun screenRelativeWidthConversionAtCommitScale() {
        val brush = BrushSpec(baseWidthWorld = 10f, dashOn = 4f, dashOff = 2f)
        val scaled = brush.scaledForLocalScale(4f)
        assertEquals(2.5f, scaled.baseWidthWorld, 0.0001f)
        assertEquals(1f, scaled.dashOn, 0.0001f)
        assertEquals(0.5f, scaled.dashOff, 0.0001f)
        assertEquals(10f, brush.scaledForLocalScale(1f).baseWidthWorld, 0.0001f)
    }

    // ----- retained: StrokeSmoother zoom-aware input filter -----

    @Test
    fun smootherRejectsSubPixelMovesAtHighZoom() {
        val cam = Camera(scale = 4f)
        val stroke = StrokeData()
        stroke.addPoint(StrokePoint(0f, 0f, 1f))
        assertFalse(StrokeSmoother.shouldAdd(stroke, StrokePoint(0.2f, 0f, 1f), cam))
        assertTrue(StrokeSmoother.shouldAdd(stroke, StrokePoint(0.5f, 0f, 1f), cam))
    }

    @Test
    fun smootherThresholdScalesWithZoom() {
        val zoomedOut = Camera(scale = 1f)
        val zoomedIn = Camera(scale = 4f)
        val stroke = StrokeData()
        stroke.addPoint(StrokePoint(0f, 0f, 1f))
        val candidate = StrokePoint(0.5f, 0f, 1f)
        assertFalse(StrokeSmoother.shouldAdd(stroke, candidate, zoomedOut))
        assertTrue(StrokeSmoother.shouldAdd(stroke, candidate, zoomedIn))
    }

    // ----- retained: DrawingDocument undo/redo semantics -----

    @Test
    fun tapsAreNotCommittedAsStrokes() {
        val doc = DrawingDocument()
        val tap = StrokeData()
        tap.addPoint(StrokePoint(0f, 0f, 0.5f))
        assertFalse(doc.commitStroke(tap))
        assertFalse(doc.canUndo)
    }

    private fun twoPointStroke(): StrokeData {
        val s = StrokeData()
        s.addPoint(StrokePoint(0f, 0f, 0.5f))
        s.addPoint(StrokePoint(5f, 0f, 0.5f))
        return s
    }

    @Test
    fun undoRedoCycleRestoresState() {
        val doc = DrawingDocument()
        doc.commitStroke(twoPointStroke())
        assertEquals(1, doc.activeLayer.strokes.size)
        assertTrue(doc.undo())
        assertEquals(0, doc.activeLayer.strokes.size)
        assertTrue(doc.redo())
        assertEquals(1, doc.activeLayer.strokes.size)
        assertFalse(doc.redo())
    }

    @Test
    fun newCommitClearsTheRedoStack() {
        val doc = DrawingDocument()
        doc.commitStroke(twoPointStroke())
        doc.undo()
        doc.commitStroke(twoPointStroke())
        assertFalse(doc.canRedo)
    }

    // ----- retained: DrawingViewModel lifecycle + P0 level commit -----

    @Test
    fun strokeLifecycleCommitsThroughTheDocument() {
        val vm = DrawingViewModel()
        vm.beginStroke(0f, 0f, 1f)
        vm.extendStroke(10f, 0f, 0.8f)
        vm.extendStroke(20f, 0f, 0.6f)
        vm.endStroke()
        assertEquals(1, vm.document.activeLayer.strokes.size)
        assertTrue(vm.document.canUndo)
        // P0: committed stroke carries the camera's level id (root) and
        // screen-relative width (6 world units at 1x zoom stays 6)
        assertEquals(0L, vm.document.activeLayer.strokes[0].levelId)
        assertEquals(6f, vm.document.activeLayer.strokes[0].brush.baseWidthWorld, 0.0001f)
    }

    @Test
    fun strokeCommitInDeepLevelCarriesLevelIdAndScaledWidth() {
        val vm = DrawingViewModel()
        repeat(3) {
            vm.camera.zoomAt(500f, 500f, 200f)
            vm.camera.applyLevelTransitions(500f, 500f)
        }
        assertEquals(3, vm.camera.level.depth)
        vm.beginStroke(100f, 100f, 1f)
        vm.extendStroke(150f, 120f, 0.9f)
        vm.endStroke()
        val stroke = vm.document.activeLayer.strokes[0]
        assertEquals(vm.camera.level.id, stroke.levelId)
        assertEquals(6f / vm.camera.scale, stroke.brush.baseWidthWorld, 0.0001f)
        // the committed stroke is queryable in ITS level's index bucket
        val q = vm.document.strokeIndex.queryVisibleLevel(stroke.levelId, -1000f, -1000f, 1000f, 1000f)
        assertEquals(1, q.size)
    }

    @Test
    fun extendStrokeAppliesZoomAwareFiltering() {
        val vm = DrawingViewModel()
        vm.beginStroke(0f, 0f, 1f)
        assertFalse(vm.extendStroke(0.01f, 0f, 1f)) // sub-pixel at scale 1
    }

    @Test
    fun bookmarksCaptureCameraStateAndLevel() {
        val vm = DrawingViewModel()
        vm.camera.scale = 3f
        val bookmark = vm.addBookmark(10f, -20f, "test")
        assertEquals(10f, bookmark.worldX, 0.001f)
        assertEquals(-20f, bookmark.worldY, 0.001f)
        assertEquals(3f, bookmark.scale, 0.001f)
        assertEquals("test", bookmark.label)
        assertEquals(0L, bookmark.levelId)
    }

    @Test
    fun serializeEmitsBracedJsonWithExpectedKeys() {
        val vm = DrawingViewModel()
        vm.beginStroke(0f, 0f, 1f)
        vm.extendStroke(10f, 5f, 0.9f)
        vm.endStroke()
        vm.addBookmark(1f, 2f, "pin")
        val json = vm.serialize()
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"camera\""))
        assertTrue(json.contains("\"levels\""))
        assertTrue(json.contains("\"layers\""))
        assertTrue(json.contains("\"bookmarks\""))
        assertTrue(json.contains("\"version\":3"))
    }
}
