package com.example.zoomart

import com.example.zoomart.model.BrushSpec
import com.example.zoomart.model.DocumentCodec
import com.example.zoomart.model.DrawingViewModel
import com.example.zoomart.model.ShapeData
import com.example.zoomart.model.ShapeGeometry
import com.example.zoomart.model.ShapeType
import com.example.zoomart.model.SmudgeData
import com.example.zoomart.model.StampData
import com.example.zoomart.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * P3.1 SHAPES suite (docs/REVIEW_2026-10-18.md SHAPES section):
 * per-type parametric geometry (star points, arrow head, rounded-rect
 * arcs, triangle, N-gon), constrain / from-center drag math, level
 * projection of shape bboxes, commit/undo/redo for shapes + moves +
 * corner resizes + delete, lock rejection, layer deep-copy independence,
 * and the codec v3 extension (shapes + smudges arrays, backward compat).
 * All pure JVM.
 */
class P3ShapesTest {

    private fun hypot(ax: Float, ay: Float, bx: Float, by: Float): Double =
        Math.hypot((ax - bx).toDouble(), (ay - by).toDouble())

    // ---------------- per-type parametric geometry ----------------

    @Test
    fun rectGeometryIsTheNormalizedAnchorBox() {
        val r = ShapeGeometry.rectOf(100f, 50f, 20f, -10f)
        assertEquals(20f, r[0], 0.0001f)
        assertEquals(-10f, r[1], 0.0001f)
        assertEquals(100f, r[2], 0.0001f)
        assertEquals(50f, r[3], 0.0001f)
    }

    @Test
    fun ellipsePointSatisfiesTheEllipseEquation() {
        val p = ShapeGeometry.ellipsePoint(0f, 0f, 100f, 50f, 33f)
        // center (50,25), radii (50,25): ((x-50)/50)^2 + ((y-25)/25)^2 == 1
        val u = (p[0] - 50f) / 50f
        val v = (p[1] - 25f) / 25f
        assertEquals(1.0, (u * u + v * v).toDouble(), 1e-4)
    }

    @Test
    fun triangleApexSitsAtTopCenterAndBaseAtBottomCorners() {
        val v = ShapeGeometry.triangleVertices(0f, 0f, 100f, 50f)
        assertEquals(3, v.size)
        assertEquals(50f, v[0][0], 0.0001f) // apex x = center
        assertEquals(0f, v[0][1], 0.0001f) // apex y = top
        assertEquals(0f, v[1][0], 0.0001f)
        assertEquals(50f, v[2][1], 0.0001f) // base at the bottom
    }

    @Test
    fun polygonHasNVerticesWithTheFirstAtTheTop() {
        val v = ShapeGeometry.polygonVertices(0f, 0f, 100f, 100f, 6)
        assertEquals(6, v.size)
        assertEquals("first vertex at -90 degrees = top-center",
            50f, v[0][0], 0.0001f)
        assertEquals(0f, v[0][1], 0.0001f)
        // regular N-gon on a square box: all vertices equidistant from center
        for (p in v) assertEquals(hypot(50f, 50f, p[0], p[1]), 50.0, 1e-3)
    }

    @Test
    fun starAlternatesOuterAndInnerRadii() {
        val v = ShapeGeometry.starVertices(0f, 0f, 100f, 100f, 5)
        assertEquals("2N vertices for N star points", 10, v.size)
        // outer vertex 0 at the top-center, radius = box half extent (50,50)
        assertEquals(50f, v[0][0], 0.0001f)
        assertEquals(0f, v[0][1], 0.0001f)
        assertEquals(50.0, hypot(50f, 50f, v[0][0], v[0][1]), 1e-3)
        // every inner vertex sits at STAR_INNER_RATIO * outer radius
        for (k in 0 until 5) {
            val inner = v[2 * k + 1]
            assertEquals(
                50.0 * ShapeGeometry.STAR_INNER_RATIO,
                hypot(50f, 50f, inner[0], inner[1]),
                1e-3,
            )
        }
    }

    @Test
    fun arrowHeadCornersFlankTheShaftAtHeadLengthBack() {
        val (hl, hr) = ShapeGeometry.arrowHead(0f, 0f, 100f, 0f)
        // len 100 -> head length 30 (ARROW_HEAD_FRACTION), half width 15
        assertEquals(70f, hl[0], 0.0001f)
        assertEquals(15f, abs(hl[1]), 0.0001f)
        assertEquals(70f, hr[0], 0.0001f)
        assertEquals(15f, abs(hr[1]), 0.0001f)
        assertEquals("head corners flank the shaft symmetrically", hl[1], -hr[1], 0.0001f)
    }

    @Test
    fun roundedRectArcVerticesLieOnTheCornerCircle() {
        val pts = ShapeGeometry.roundedRectArcVertices(0f, 0f, 100f, 60f, 0.5f, steps = 16)
        val cr = ShapeGeometry.cornerRadius(0f, 0f, 100f, 60f, 0.5f)
        assertEquals("roundness 0.5 of the smaller side (60): radius 15", 15f, cr, 0.0001f)
        // bottom-left corner circle center = (cr, 60-cr)
        for (p in pts) {
            assertEquals(cr, hypot(p[0], p[1], cr, 60f - cr).toFloat(), 0.001f)
        }
    }

    // ---------------- constrain / from-center drag math ----------------

    @Test
    fun constrainSquaresTheBoxPreservingDragDirection() {
        val a = ShapeGeometry.resolveAnchors(
            ShapeType.RECTANGLE, 0f, 0f, 100f, 40f, constrain = true, fromCenter = false)
        assertEquals(0f, a[0], 0.0001f)
        assertEquals(0f, a[1], 0.0001f)
        assertEquals(100f, a[2], 0.0001f)
        assertEquals("y must square up to the larger side", 100f, a[3], 0.0001f)

        val b = ShapeGeometry.resolveAnchors(
            ShapeType.ELLIPSE, 10f, 20f, -90f, 70f, constrain = true, fromCenter = false)
        assertEquals("negative x direction preserved", -90f, b[2], 0.0001f)
        assertEquals(120f, b[3], 0.0001f) // |20-120| = 100 = |10-(-90)|
    }

    @Test
    fun constrainSnapsLineDirectionTo15DegreesKeepingLength() {
        // 5.7 degrees snaps to 0
        val a = ShapeGeometry.resolveAnchors(
            ShapeType.LINE, 0f, 0f, 100f, 10f, constrain = true, fromCenter = false)
        val len = hypot(0f, 0f, a[2], a[3])
        assertEquals("length preserved", hypot(0f, 0f, 100f, 10f), len, 1e-3)
        assertEquals("snapped to the 0-degree axis", 0f, a[3], 0.01f)

        // 84.3 degrees snaps to 90
        val b = ShapeGeometry.resolveAnchors(
            ShapeType.LINE, 0f, 0f, 10f, 100f, constrain = true, fromCenter = false)
        assertEquals("snapped to the 90-degree axis", 0f, b[2], 0.01f)
        assertTrue(b[3] > 99f)

        // 22 degrees stays (already within 15-degree steps? 22 -> 15)
        val c = ShapeGeometry.resolveAnchors(
            ShapeType.LINE, 0f, 0f, 100f, 40f, constrain = true, fromCenter = false)
        val ang = Math.toDegrees(Math.atan2(c[3].toDouble(), c[2].toDouble()))
        assertEquals(15.0, ang, 0.01)
    }

    @Test
    fun fromCenterGrowsTheBoxSymmetricallyAboutTheStart() {
        val a = ShapeGeometry.resolveAnchors(
            ShapeType.RECTANGLE, 50f, 50f, 100f, 60f, constrain = false, fromCenter = true)
        assertEquals(0f, a[0], 0.0001f)
        assertEquals(40f, a[1], 0.0001f)
        assertEquals(100f, a[2], 0.0001f)
        assertEquals(60f, a[3], 0.0001f)
        assertEquals("start point is the box center",
            50f, (a[0] + a[2]) / 2f, 0.0001f)
    }

    @Test
    fun fromCenterThenConstrainStaysCentered() {
        val a = ShapeGeometry.resolveAnchors(
            ShapeType.ELLIPSE, 50f, 50f, 100f, 60f, constrain = true, fromCenter = true)
        assertEquals("centered box stays centered under constrain",
            50f, (a[0] + a[2]) / 2f, 0.0001f)
        assertEquals(50f, (a[1] + a[3]) / 2f, 0.0001f)
        assertEquals("square (circle) after constrain", abs(a[2] - a[0]), abs(a[3] - a[1]), 0.0001f)
    }

    // ---------------- commit / undo / lock ----------------

    @Test
    fun shapeGestureCommitsOneShapeAndUndoRedoRoundTrips() {
        val vm = DrawingViewModel()
        val doc = vm.document
        vm.camera.scale = 2f // outline width must convert to level-local units
        vm.armedShapeType = ShapeType.STAR
        vm.shapePointCount = 6

        vm.beginShapeGesture(10f, 10f)
        assertNull("no shape until the drag ends", doc.activeLayer.shapes.firstOrNull())
        vm.extendShapeGesture(110f, 90f)
        val live = vm.inProgressShape
        assertTrue("live preview tracks the drag", live != null && live!!.x1 == 110f)
        val committed = vm.endShapeGesture()

        assertTrue(committed != null)
        assertEquals(1, doc.activeLayer.shapes.size)
        val sh = doc.activeLayer.shapes[0]
        assertSame(committed, sh)
        assertEquals(ShapeType.STAR, sh.type)
        assertEquals(0L, sh.levelId) // camera was at the root level
        assertEquals(10f, sh.x0, 0.0001f)
        assertEquals(110f, sh.x1, 0.0001f)
        assertEquals(6, sh.pointCount)
        assertEquals("screen px / camera scale (the scaledForLocalScale contract)",
            6f / 2f, sh.outlineWidthWorld, 0.0001f)

        assertTrue(doc.undo())
        assertEquals(0, doc.activeLayer.shapes.size)
        assertTrue(doc.redo())
        assertEquals(1, doc.activeLayer.shapes.size)
        assertEquals(ShapeType.STAR, doc.activeLayer.shapes[0].type)
        // only ONE undo entry for the whole gesture
        assertTrue(doc.undo())
        assertFalse(doc.canUndo)
    }

    @Test
    fun degenerateTapCommitsNoShapeAndPushesNoUndo() {
        val vm = DrawingViewModel()
        vm.beginShapeGesture(10f, 10f)
        vm.extendShapeGesture(10f, 10f)
        assertNull(vm.endShapeGesture())
        assertFalse(vm.document.canUndo)
        assertTrue(vm.document.activeLayer.shapes.isEmpty())
    }

    @Test
    fun lockedLayerRejectsShapeGesturesAtBothPaths() {
        val vm = DrawingViewModel()
        val doc = vm.document
        doc.setLayerLocked(doc.activeLayerIndex, true)
        vm.beginShapeGesture(0f, 0f)
        assertNull("locked layer must not open a shape gesture", vm.inProgressShape)
        assertFalse("commitShape must reject a locked active layer",
            doc.commitShape(ShapeData(ShapeType.RECTANGLE).also { it.setAnchors(0f, 0f, 5f, 5f) }))
        assertTrue(doc.activeLayer.shapes.isEmpty())
    }

    // ---------------- select: tap / move / corner-resize / delete ----------------

    private fun commitRect(vm: DrawingViewModel, x0: Float, y0: Float, x1: Float, y1: Float): ShapeData {
        vm.armedShapeType = ShapeType.RECTANGLE
        vm.beginShapeGesture(x0, y0)
        vm.extendShapeGesture(x1, y1)
        return vm.endShapeGesture()!!
    }

    @Test
    fun tapSelectsShapeAndDragMovesItAsOneUndoStep() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val sh = commitRect(vm, 100f, 100f, 300f, 200f)

        val sel = doc.selection
        val hit = sel.selectShapeAt(vm.camera, vm.visibleLevels(), 150f, 120f)
        assertSame("tap inside the rect must hit it", sh, hit)
        assertEquals(1, sel.selectedShapes.size)
        assertTrue("a shape-only selection reports non-empty", !sel.isEmpty)

        sel.beginTransform()
        sel.translateBy(40f, 30f)
        sel.endTransform()
        assertEquals(140f, sh.x0, 0.0001f)
        assertEquals(130f, sh.y0, 0.0001f)
        assertEquals(340f, sh.x1, 0.0001f)

        assertTrue("move must be ONE undo step", doc.undo())
        assertEquals(100f, sh.x0, 0.0001f)
        assertEquals(200f, sh.y1, 0.0001f)
        assertTrue(doc.redo())
        assertEquals(140f, sh.x0, 0.0001f)
    }

    @Test
    fun tapOutsideShapeSelectsNothing() {
        val vm = DrawingViewModel()
        val doc = vm.document
        commitRect(vm, 100f, 100f, 300f, 200f)
        assertNull(doc.selection.selectShapeAt(vm.camera, vm.visibleLevels(), 500f, 500f))
        assertTrue(doc.selection.isEmpty)
    }

    @Test
    fun cornerResizeScalesUniformlyAboutTheOppositeCorner() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val sh = commitRect(vm, 100f, 100f, 300f, 200f)
        doc.selection.selectShapeAt(vm.camera, vm.visibleLevels(), 150f, 120f)

        val sel = doc.selection
        sel.beginTransform()
        // uniform 2x about the OPPOSITE corner (100,100)
        sel.scaleAboutCameraPoint(100f, 100f, 2f)
        sel.endTransform()

        assertEquals(100f, sh.x0, 0.0001f) // pivot corner stays fixed
        assertEquals(100f, sh.y0, 0.0001f)
        assertEquals(500f, sh.x1, 0.0001f) // far corner doubles away
        assertEquals(300f, sh.y1, 0.0001f)

        assertTrue(doc.undo())
        assertEquals(300f, sh.x1, 0.0001f)
        assertEquals(200f, sh.y1, 0.0001f)
    }

    @Test
    fun marqueeSelectsShapesAndDeleteRemovesThem() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val sh = commitRect(vm, 100f, 100f, 300f, 200f)
        assertTrue(vm.selectInWorldRect(0f, 0f, 1000f, 1000f))
        assertTrue(doc.selection.selectedShapes.contains(sh))
        assertTrue(doc.selection.deleteSelection())
        assertEquals(0, doc.activeLayer.shapes.size)
        assertTrue(doc.undo())
        assertEquals("delete rides the same single undo step", 1, doc.activeLayer.shapes.size)
    }

    @Test
    fun marqueeProjectsShapeBboxesAcrossLevels() {
        val vm = DrawingViewModel()
        val doc = vm.document
        // a shape in a CHILD level: root-local 50,50 * 100x zoom focus
        val child = doc.levelTree.childAt(doc.levelTree.root, 50f, 50f)
        val sh = ShapeData(ShapeType.RECTANGLE, child.id)
        sh.setAnchors(0f, 0f, 100f, 100f)
        doc.activeLayer.shapes.add(sh)
        // its projected root-frame bbox: origin 50,50 + size/100 = [50..51]
        assertTrue(vm.selectInWorldRect(49f, 49f, 52f, 52f))
        assertTrue("child-level shape selected via projected bbox",
            doc.selection.selectedShapes.contains(sh))
    }

    // ---------------- deep copy + codec ----------------

    @Test
    fun duplicateLayerDeepCopiesShapesAndSmudgesIndependently() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val sh = commitRect(vm, 0f, 0f, 100f, 100f)
        val sm = SmudgeData(0L)
        sm.stamps.add(StampData(5f, 5f, 4f, 0xFF112233.toInt(), 0.5f))
        doc.activeLayer.smudges.add(sm)
        doc.activeLayer.name = "Art"

        val copy = doc.duplicateLayer(doc.activeLayerIndex)!!
        assertEquals(1, copy.shapes.size)
        assertEquals(1, copy.smudges.size)
        assertNotSame(sh, copy.shapes[0])
        assertNotSame(sm, copy.smudges[0])
        assertEquals(sh.x1, copy.shapes[0].x1, 0.0001f)

        // mutate the original: the copy must not move
        sh.setAnchors(999f, 999f, 1000f, 1000f)
        assertEquals(100f, copy.shapes[0].x1, 0.0001f)
    }

    @Test
    fun codecRoundTripsShapesAndSmudgesByteStable() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val sh = ShapeData(ShapeType.STAR, 0L)
        sh.setAnchors(12.5f, -30.25f, 140f, 88.75f)
        sh.fillOn = false
        sh.fillColor = 0xFF123456.toInt()
        sh.outlineColor = 0xFF654321.toInt()
        sh.outlineWidthWorld = 3.75f
        sh.cornerRoundness = 0.6f
        sh.pointCount = 7
        doc.activeLayer.shapes.add(sh)
        val sm = SmudgeData(0L)
        sm.stamps.add(StampData(1.5f, 2.5f, 6.25f, 0xFFABCDEF.toInt(), 0.42f))
        sm.stamps.add(StampData(-3.5f, 4.5f, 8.0f, 0xFF000000.toInt(), 1.0f))
        doc.activeLayer.smudges.add(sm)

        val json1 = vm.serialize()
        val restored = DocumentCodec.fromJson(json1).document.layers[1]
        assertEquals(1, restored.shapes.size)
        val rs = restored.shapes[0]
        assertEquals(ShapeType.STAR, rs.type)
        assertEquals(-30.25f, rs.y0, 0.0001f)
        assertFalse(rs.fillOn)
        assertEquals(0xFF123456.toInt(), rs.fillColor)
        assertEquals(0xFF654321.toInt(), rs.outlineColor)
        assertEquals(3.75f, rs.outlineWidthWorld, 0.0001f)
        assertEquals(0.6f, rs.cornerRoundness, 0.0001f)
        assertEquals(7, rs.pointCount)
        assertEquals(1, restored.smudges.size)
        val rm = restored.smudges[0]
        assertEquals(2, rm.stamps.size)
        assertEquals(0xFFABCDEF.toInt(), rm.stamps[0].color)
        assertEquals(0.42f, rm.stamps[0].alpha, 0.0001f)
        assertEquals(8.0f, rm.stamps[1].radius, 0.0001f)

        val session = DocumentCodec.fromJson(json1)
        assertEquals("shape/smudge round trips must be byte-stable",
            json1, DocumentCodec.toJson(session.document, session.camera))
    }

    @Test
    fun preP3V3SessionMigratesWithNoShapesOrSmudges() {
        // exact pre-P3 v3 layer shape: no "shapes"/"smudges" keys at all
        val v3pre = "{\"version\":3," +
            "\"camera\":{\"levelId\":0,\"scale\":1.0,\"transX\":0.0,\"transY\":0.0}," +
            "\"activeLayer\":1," +
            "\"levels\":[{\"id\":0,\"parentId\":-1,\"originX\":0.0,\"originY\":0.0,\"scaleToParent\":100.0}]," +
            "\"layers\":[{\"name\":\"Background\",\"visible\":true,\"opacity\":1.0," +
            "\"locked\":false,\"blend\":\"NORMAL\",\"images\":[],\"strokes\":[]}," +
            "{\"name\":\"Layer 1\",\"visible\":true,\"opacity\":1.0," +
            "\"locked\":false,\"blend\":\"NORMAL\",\"images\":[]," +
            "\"strokes\":[{\"brush\":{\"width\":6.0,\"response\":2.0,\"minRatio\":0.15," +
            "\"color\":-15103044,\"opacity\":1.0,\"pOpacity\":false,\"minOp\":0.2}," +
            "\"level\":0,\"points\":[0.0,0.0,1.0,10.0,0.0,1.0]}]}]," +
            "\"bookmarks\":[]}"
        val session = DocumentCodec.fromJson(v3pre)
        val layer = session.document.layers[1]
        assertTrue("absent shapes must default to empty", layer.shapes.isEmpty())
        assertTrue("absent smudges must default to empty", layer.smudges.isEmpty())
        assertEquals(1, layer.strokes.size)
        // re-serialize: byte-stable, and still no shapes/smudges keys
        val json2 = DocumentCodec.toJson(session.document, session.camera)
        assertFalse(json2.contains("\"shapes\""))
        assertFalse(json2.contains("\"smudges\""))
        assertFalse("pre-P3 strokes carry no taper field", json2.contains("\"taper\""))
    }

    @Test
    fun unknownShapeTypeIsSkippedNotFatal() {
        val json = "{\"camera\":{},\"layers\":[{\"name\":\"L\",\"shapes\":[" +
            "{\"type\":\"HEXAGON\",\"level\":0,\"x0\":0.0,\"y0\":0.0,\"x1\":10.0,\"y1\":10.0}," +
            "{\"type\":\"RECTANGLE\",\"level\":0,\"x0\":0.0,\"y0\":0.0,\"x1\":20.0,\"y1\":20.0}" +
            "],\"strokes\":[]}]}"
        val session = DocumentCodec.fromJson(json)
        val layer = session.document.layers[0]
        assertEquals("unknown forward-compat types are skipped, known ones load",
            1, layer.shapes.size)
        assertEquals(ShapeType.RECTANGLE, layer.shapes[0].type)
    }

    @Test
    fun unsignedArgbColorEncodingsDoNotSaturateToWhite() {
        // colors written as UNSIGNED ints (0..4294967295) — e.g. by external
        // tools — must wrap into the signed ARGB range instead of saturating
        // to 0x7FFFFFFF (JVM double->int saturates!), which would white out
        // the content. Found on-device: a seeded session rendered blank.
        val blackUnsigned = (0xFF000000L).toDouble()   // 4278190080
        val redUnsigned = (0xFFFF0000L).toDouble()    // 4294901760
        val json = "{\"camera\":{},\"layers\":[{\"name\":\"L\"," +
            "\"shapes\":[{\"type\":\"RECTANGLE\",\"level\":0,\"x0\":0.0,\"y0\":0.0," +
            "\"x1\":10.0,\"y1\":10.0,\"fillOn\":true," +
            "\"fillColor\":" + blackUnsigned + ",\"outlineColor\":" + redUnsigned + "}]," +
            "\"smudges\":[{\"level\":0,\"stamps\":[1.0,1.0,2.0," + redUnsigned + ",0.5]}]," +
            "\"strokes\":[]}]}";
        val layer = DocumentCodec.fromJson(json).document.layers[0]
        assertEquals(0xFF000000.toInt(), layer.shapes[0].fillColor)
        assertEquals(0xFFFF0000.toInt(), layer.shapes[0].outlineColor)
        assertEquals(0xFFFF0000.toInt(), layer.smudges[0].stamps[0].color)
        // and the app's own signed encodings keep working
        val signed = "{\"camera\":{},\"layers\":[{\"name\":\"L\"," +
            "\"shapes\":[{\"type\":\"RECTANGLE\",\"level\":0,\"x0\":0.0,\"y0\":0.0," +
            "\"x1\":10.0,\"y1\":10.0,\"fillColor\":-16777216}],\"strokes\":[]}]}"
        assertEquals(0xFF000000.toInt(),
            DocumentCodec.fromJson(signed).document.layers[0].shapes[0].fillColor)
    }

    @Test
    fun shapeStateSurvivesViewModelRestore() {
        val vm = DrawingViewModel()
        vm.beginShapeGesture(0f, 0f)
        vm.extendShapeGesture(50f, 50f)
        vm.endShapeGesture()
        vm.document.setLayerLocked(1, true)
        val payload = vm.serialize()
        vm.restore(DocumentCodec.fromJson(payload))
        assertEquals(1, vm.document.activeLayer.shapes.size)
        assertTrue(vm.document.layers[1].locked)
        assertEquals(50f, vm.document.activeLayer.shapes[0].x1, 0.0001f)
    }

    @Test
    fun shapeOutlineWidthUsesLevelLocalUnitsAtZoom() {
        // zoomed camera commits thinner level-local widths for the same
        // screen weight — the P1 screen-relative contract for shapes
        val vm = DrawingViewModel()
        vm.camera.scale = 10f
        vm.shapeOutlineWidthScreen = 20f
        val sh = commitRect(vm, 0f, 0f, 50f, 50f)
        assertEquals(2f, sh.outlineWidthWorld, 0.0001f)
    }

    @Test
    fun strokeLineTaperRidesTheCodecToo() {
        // covered fully in P3LazyTest; here just the codec leg of a taper
        val vm = DrawingViewModel()
        vm.beginStroke(0f, 0f, 1f)
        vm.extendStroke(10f, 0f, 1f)
        val s = vm.endStroke()!!
        s.taper = 0.1f
        val restored = DocumentCodec.fromJson(vm.serialize()).document.layers[1]
        assertEquals(0.1f, restored.strokes[0].taper, 0.0001f)
    }
}
