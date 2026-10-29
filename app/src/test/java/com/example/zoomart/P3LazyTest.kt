package com.example.zoomart

import com.example.zoomart.model.BrushSpec
import com.example.zoomart.model.DrawingViewModel
import com.example.zoomart.model.LazyBrush
import com.example.zoomart.model.StrokeOutline
import com.example.zoomart.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * P3.2 LAZY BRUSH suite (docs/REVIEW_2026-10-18.md LAZY BRUSH section):
 * pure rope math (pen inside radius -> static, pen at 3r -> brush at 2r
 * along the direction, radius 0 = raw pen), midpoint-quad smoothing
 * geometry, arclength taper factors, outline-width tapering, and the
 * ViewModel commit path (screen rope -> level-local stroke points at any
 * zoom). All pure JVM.
 */
class P3LazyTest {

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Double =
        Math.hypot((ax - bx).toDouble(), (ay - by).toDouble())

    // ---------------- rope math ----------------

    @Test
    fun penInsideRadiusLeavesBrushStatic() {
        val rope = LazyBrush(radiusScreen = 50f)
        rope.begin(100f, 100f)
        val b = rope.feed(130f, 120f) // 36 px away < 50
        assertEquals(100f, b[0], 0.0001f)
        assertEquals(100f, b[1], 0.0001f)
        assertTrue("brush point must not move inside the rope radius",
            rope.brushX == 100f && rope.brushY == 100f)
    }

    @Test
    fun penOutsideRadiusKeepsBrushExactlyOneRadiusBehind() {
        val rope = LazyBrush(radiusScreen = 50f)
        rope.begin(100f, 100f)
        val b = rope.feed(250f, 100f) // pen at distance 3r, straight right
        assertEquals(200f, b[0], 0.0001f)
        assertEquals(100f, b[1], 0.0001f)
        assertEquals(50.0, dist(250.0f, 100.0f, rope.brushX, rope.brushY), 1e-4)

        // diagonal direction too
        rope.begin(0f, 0f)
        val c = rope.feed(300f, 400f) // distance 500 = 10r along (3,4)/5
        assertEquals(270f, c[0], 0.001f)
        assertEquals(360f, c[1], 0.001f)
        assertEquals(50.0, dist(300f, 400f, rope.brushX, rope.brushY), 1e-4)
    }

    @Test
    fun radiusZeroDegeneratesToTheRawPen() {
        val rope = LazyBrush(radiusScreen = 0f)
        rope.begin(10f, 10f)
        val b = rope.feed(999f, -123f)
        assertEquals(999f, b[0], 0.0001f)
        assertEquals(-123f, b[1], 0.0001f)
    }

    @Test
    fun beginAnchorsTheBrushAtThePenAndEndDeactivates() {
        val rope = LazyBrush(radiusScreen = 10f)
        assertFalse(rope.active)
        rope.begin(42f, 24f)
        assertTrue(rope.active)
        assertEquals(42f, rope.brushX, 0.0001f)
        rope.end()
        assertFalse(rope.active)
    }

    // ---------------- midpoint quadratic smoothing ----------------

    @Test
    fun smoothingHitsTheExactQuadMidpointForAnElbow() {
        // p0=(0,0) p1=(10,0) p2=(10,10): the interior quad runs
        // mid(p0,p1)=(5,0) --control (10,0)--> mid(p1,p2)=(10,5)
        // at t=0.5: 0.25*(5,0) + 0.5*(10,0) + 0.25*(10,5) = (8.75, 1.25)
        val pts = listOf(
            StrokePoint(0f, 0f, 1f),
            StrokePoint(10f, 0f, 1f),
            StrokePoint(10f, 10f, 1f),
        )
        val out = LazyBrush.smoothPoints(pts, samples = 4)
        assertTrue("smoothing densifies the point list", out.size > pts.size)
        assertEquals("first point preserved", 0f, out.first().x, 0.0001f)
        assertEquals("last point preserved", 10f, out.last().y, 0.0001f)
        val found = out.any { abs(it.x - 8.75f) < 1e-4f && abs(it.y - 1.25f) < 1e-4f }
        assertTrue("quad midpoint (8.75, 1.25) must be an emitted sample", found)
    }

    @Test
    fun smoothingStaysInsideTheControlPolygon() {
        val pts = listOf(
            StrokePoint(0f, 0f, 0.5f),
            StrokePoint(20f, 0f, 0.7f),
            StrokePoint(20f, 20f, 0.9f),
        )
        for (p in LazyBrush.smoothPoints(pts, samples = 8)) {
            assertTrue("x within [0,20]", p.x >= -1e-4f && p.x <= 20f + 1e-4f)
            assertTrue("y within [0,20]", p.y >= -1e-4f && p.y <= 20f + 1e-4f)
            assertTrue("pressure stays in range", p.pressure01 in 0f..1f)
        }
    }

    // ---------------- taper ----------------

    @Test
    fun taperFactorIsZeroAtEndsAndOneInTheMiddle() {
        assertEquals(0f, LazyBrush.taperFactorAt(0f), 0.0001f)
        assertEquals(0f, LazyBrush.taperFactorAt(1f), 0.0001f)
        assertEquals(1f, LazyBrush.taperFactorAt(0.5f), 0.0001f)
        assertEquals(1f, LazyBrush.taperFactorAt(0.1f), 1e-4f)
        assertEquals(1f, LazyBrush.taperFactorAt(0.9f), 1e-4f)
        // half-way through the ramp: smoothstep(0.5) = 0.5
        assertEquals(0.5f, LazyBrush.taperFactorAt(0.05f), 0.0001f)
        assertEquals("fraction 0 disables taper", 1f, LazyBrush.taperFactorAt(0f, 0f), 0.0001f)
    }

    @Test
    fun outlineWidthsTaperAtTheStrokeEnds() {
        val brush = BrushSpec(baseWidthWorld = 10f, pressureResponse = 0f) // constant width
        val pts = (0..20).map { StrokePoint(it * 10f, 0f, 1f) }
        val plain = StrokeOutline.outlineVertices(pts, brush, taper = 0f)!!
        assertEquals("no taper: constant half-width", 5f,
            plain.first[10][1] - pts[10].y, 0.001f)
        assertEquals("no taper: end cap width is full", 5f,
            plain.first[0][1] - pts[0].y, 0.001f)

        val tapered = StrokeOutline.outlineVertices(pts, brush, taper = 0.1f)!!
        val halfAt: (Int) -> Float = { i -> abs(tapered.first[i][1] - pts[i].y) }
        assertEquals("middle keeps full width", 5f, halfAt(10), 0.001f)
        assertTrue("first point tapering toward zero width",
            halfAt(0) < 0.05f)
        assertTrue("last point tapering toward zero width",
            halfAt(pts.size - 1) < 0.05f)
        assertTrue("ramp grows monotonically over the first 10%",
            halfAt(1) < halfAt(2) && halfAt(2) <= halfAt(3))
    }

    // ---------------- ViewModel commit path ----------------

    @Test
    fun lazyStrokeCommitsRopeOutputWithSmoothingAndTaper() {
        val vm = DrawingViewModel()
        vm.lazyEnabled = true
        vm.lazyBrush.radiusScreen = 50f
        assertTrue(vm.lazyActive)

        vm.beginStrokeAtScreen(100f, 100f, 1f)
        vm.extendStrokeAtScreen(300f, 100f, 1f) // pen at 4r -> brush at (250,100)
        val live = vm.inProgress!!
        assertEquals("stroke point = rope brush point in level-local coords (scale 1)",
            250f, live.points[1].x, 0.0001f)
        assertEquals(100f, live.points[1].y, 0.0001f)
        assertEquals(0f, live.taper, 0f) // taper lands at commit, not live

        val committed = vm.endStrokeLazy()!!
        assertEquals(1, vm.document.activeLayer.strokes.size)
        val s = vm.document.activeLayer.strokes[0]
        assertEquals("smoothing densified the committed points",
            true, s.points.size > 2)
        assertEquals("endpoints survive smoothing exactly",
            100f, s.points.first().x, 0.0001f)
        assertEquals(250f, s.points.last().x, 0.0001f)
        assertEquals("lazy strokes carry the taper fraction",
            LazyBrush.TAPER_FRACTION, s.taper, 0.0001f)
        assertFalse("gesture ended", vm.lazyBrush.active)
    }

    @Test
    fun ropeFeedsThroughTheCameraAtZoom() {
        val vm = DrawingViewModel()
        vm.camera.scale = 4f // level-local units are 4x screen px
        vm.lazyEnabled = true
        vm.lazyBrush.radiusScreen = 50f

        vm.beginStrokeAtScreen(100f, 100f, 0.8f)
        vm.extendStrokeAtScreen(300f, 100f, 0.8f)
        // brush at (250,100) screen -> local (62.5, 25)
        val live = vm.inProgress!!
        assertEquals(62.5f, live.points[1].x, 0.0001f)
        assertEquals(25f, live.points[1].y, 0.0001f)
    }

    @Test
    fun lazyOffKeepsTheLegacyCommitPathUntouched() {
        val vm = DrawingViewModel()
        assertFalse(vm.lazyActive)
        vm.beginStrokeAtScreen(100f, 100f, 1f)
        vm.extendStrokeAtScreen(200f, 100f, 1f)
        vm.extendStrokeAtScreen(300f, 100f, 1f)
        val s = vm.endStrokeLazy()!!
        assertEquals("no smoothing when the stabilizer is off", 3, s.points.size)
        assertEquals("no taper when the stabilizer is off", 0f, s.taper, 0f)
        assertEquals(300f, s.points.last().x, 0.0001f)
    }

    @Test
    fun zeroRadiusMeansRawPenEvenWhenToggledOn() {
        val vm = DrawingViewModel()
        vm.lazyEnabled = true
        vm.lazyBrush.radiusScreen = 0f
        assertFalse("radius 0 must disable the rope", vm.lazyActive)
        vm.beginStrokeAtScreen(100f, 100f, 1f)
        vm.extendStrokeAtScreen(300f, 100f, 1f)
        val s = vm.endStrokeLazy()!!
        assertEquals("raw pen point committed", 300f, s.points.last().x, 0.0001f)
        assertEquals(0f, s.taper, 0f)
    }
}
