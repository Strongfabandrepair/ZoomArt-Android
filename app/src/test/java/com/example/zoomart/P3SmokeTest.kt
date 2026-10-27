package com.example.zoomart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.zoomart.model.LazyBrush
import com.example.zoomart.model.ShapeData
import com.example.zoomart.model.ShapeType
import com.example.zoomart.model.SmudgeData
import com.example.zoomart.model.StampData
import com.example.zoomart.ui.ZoomableCanvasView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P3 Robolectric smoke tests — the executable spec for the on-device pen
 * behavior (adb cannot inject stylus tool types):
 *  - SHAPE tool: a stylus drag commits EXACTLY one shape; a finger drag
 *    never does (pan only);
 *  - SELECT tool: tap-selects a shape, drag MOVES it, corner-handle drag
 *    RESIZES it — each gesture ONE undo step;
 *  - lazy mode: a stylus stroke's committed points are the rope output
 *    (smoothed + tapered);
 *  - SMUDGE tool: a stylus drag commits a stamp trail as one gesture;
 *  - pixel-level verification under Robolectric NATIVE graphics: filled
 *    vs outline rect pixel counts, smudge stamps rendering intermediate
 *    grays (soft alpha falloff).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real rasterization: pixel checks
class P3SmokeTest {

    private fun touch(
        view: View,
        action: Int,
        x: Float,
        y: Float,
        t: Long,
        tool: Int,
        pressure: Float = 1f,
    ) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; this.pressure = pressure; size = 1f
        })
        val e = MotionEvent.obtain(t, t + 16, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun stylus(view: View, action: Int, x: Float, y: Float, t: Long, pressure: Float = 1f) =
        touch(view, action, x, y, t, MotionEvent.TOOL_TYPE_STYLUS, pressure)

    private fun finger(view: View, action: Int, x: Float, y: Float, t: Long) =
        touch(view, action, x, y, t, MotionEvent.TOOL_TYPE_FINGER)

    // ---------------- P3.1 SHAPE tool ----------------

    @Test
    fun shapeToolStylusDragCommitsExactlyOneShapeAndFingersNeverDo() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val view = controller.get().canvasView
        val vm = view.viewModel
        val doc = vm.document
        val t = SystemClock.uptimeMillis()

        view.tool = ZoomableCanvasView.Tool.SHAPE
        vm.armedShapeType = ShapeType.ELLIPSE

        stylus(view, MotionEvent.ACTION_DOWN, 100f, 100f, t)
        stylus(view, MotionEvent.ACTION_MOVE, 200f, 180f, t + 40)
        stylus(view, MotionEvent.ACTION_UP, 200f, 180f, t + 80)

        assertEquals("one stylus drag = exactly one shape", 1, doc.activeLayer.shapes.size)
        val sh = doc.activeLayer.shapes[0]
        assertEquals(ShapeType.ELLIPSE, sh.type)
        assertEquals(100f, sh.x0, 0.0001f)
        assertEquals(200f, sh.x1, 0.0001f)
        assertEquals(180f, sh.y1, 0.0001f)
        assertEquals("shape lives in the camera's level", vm.camera.level.id, sh.levelId)

        // fingers NEVER draw shapes (they pan)
        val transXBefore = vm.camera.transX
        finger(view, MotionEvent.ACTION_DOWN, 300f, 300f, t + 200)
        finger(view, MotionEvent.ACTION_MOVE, 360f, 340f, t + 232)
        finger(view, MotionEvent.ACTION_UP, 360f, 340f, t + 248)
        assertEquals("finger drag panned the camera", transXBefore + 60f, vm.camera.transX, 0.001f)
        assertEquals("finger drag committed no shape", 1, doc.activeLayer.shapes.size)

        // undo removes the shape; redo restores it (one step)
        assertTrue(doc.undo())
        assertEquals(0, doc.activeLayer.shapes.size)
        assertTrue(doc.redo())
        assertEquals(1, doc.activeLayer.shapes.size)
        controller.destroy()
    }

    @Test
    fun selectToolTapsMovesAndResizesShapes() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val view = controller.get().canvasView
        val vm = view.viewModel
        val doc = vm.document
        val t = SystemClock.uptimeMillis()

        // seed a committed rect via the SHAPE tool
        view.tool = ZoomableCanvasView.Tool.SHAPE
        vm.armedShapeType = ShapeType.RECTANGLE
        stylus(view, MotionEvent.ACTION_DOWN, 100f, 100f, t)
        stylus(view, MotionEvent.ACTION_MOVE, 300f, 200f, t + 40)
        stylus(view, MotionEvent.ACTION_UP, 300f, 200f, t + 80)
        val shape = doc.activeLayer.shapes[0]
        assertEquals(1, doc.activeLayer.shapes.size)

        // --- TAP selects the shape; dragging the tap MOVES it ---
        view.tool = ZoomableCanvasView.Tool.SELECT
        stylus(view, MotionEvent.ACTION_DOWN, 150f, 120f, t + 200)
        assertTrue("tap must select the shape", doc.selection.selectedShapes.contains(shape))
        stylus(view, MotionEvent.ACTION_MOVE, 190f, 160f, t + 240)
        stylus(view, MotionEvent.ACTION_UP, 190f, 160f, t + 280)
        assertEquals("drag moved the shape", 140f, shape.x0, 0.0001f)
        assertEquals(140f, shape.y0, 0.0001f)
        assertEquals(340f, shape.x1, 0.0001f)

        assertTrue(doc.undo()) // the move is one undo step
        assertEquals(100f, shape.x0, 0.0001f)
        assertEquals(300f, shape.x1, 0.0001f)

        // --- re-select, then corner-handle drag RESIZES about the pivot ---
        stylus(view, MotionEvent.ACTION_DOWN, 150f, 120f, t + 400)
        stylus(view, MotionEvent.ACTION_UP, 150f, 120f, t + 416)
        assertTrue(doc.selection.selectedShapes.contains(shape))
        // grab the bottom-right HANDLE (bbox incl. outline padding); the
        // opposite handle is the fixed pivot of the uniform resize
        val handleBoxBefore = shape.bbox().copyOf()
        stylus(view, MotionEvent.ACTION_DOWN, 300f, 200f, t + 500)
        stylus(view, MotionEvent.ACTION_MOVE, 500f, 300f, t + 540)
        stylus(view, MotionEvent.ACTION_UP, 500f, 300f, t + 580)
        assertTrue("resize must grow the shape (x1: " + shape.x1 + ")",
            shape.x1 > 420f)
        assertTrue("resize must grow the shape (y1: " + shape.y1 + ")",
            shape.y1 > 240f)
        val handleBoxAfter = shape.bbox()
        assertEquals("pivot HANDLE corner stays fixed",
            handleBoxBefore[0], handleBoxAfter[0], 0.05f)
        assertEquals(handleBoxBefore[1], handleBoxAfter[1], 0.05f)

        assertTrue(doc.undo())
        assertEquals("resize undo restores the original anchors",
            300f, shape.x1, 0.0001f)
        assertEquals(200f, shape.y1, 0.0001f)
        controller.destroy()
    }

    // ---------------- P3.2 lazy brush ----------------

    @Test
    fun lazyStylusStrokeCommitsTheRopeOutputSmoothedAndTapered() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val view = controller.get().canvasView
        val vm = view.viewModel
        val doc = vm.document
        val t = SystemClock.uptimeMillis()

        vm.lazyEnabled = true
        vm.lazyBrush.radiusScreen = 50f
        assertTrue(vm.lazyActive)

        stylus(view, MotionEvent.ACTION_DOWN, 100f, 100f, t)
        stylus(view, MotionEvent.ACTION_MOVE, 300f, 100f, t + 40)
        stylus(view, MotionEvent.ACTION_UP, 300f, 100f, t + 80)

        assertEquals(1, doc.activeLayer.strokes.size)
        val s = doc.activeLayer.strokes[0]
        assertEquals("first committed point is the pen-down point",
            100f, s.points.first().x, 0.0001f)
        assertEquals("last committed point is the ROPE brush point (pen at 4r)",
            150f, s.points.last().x, 0.0001f)
        assertEquals(100f, s.points.last().y, 0.0001f)
        assertTrue("smoothed stroke has more points than raw events",
            s.points.size > 2)
        assertEquals(LazyBrush.TAPER_FRACTION, s.taper, 0.0001f)
        controller.destroy()
    }

    // ---------------- P3.3 smudge tool ----------------

    @Test
    fun smudgeStylusDragCommitsOneStampTrailGesture() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val view = controller.get().canvasView
        val vm = view.viewModel
        val doc = vm.document
        val t = SystemClock.uptimeMillis()

        view.tool = ZoomableCanvasView.Tool.SMUDGE
        vm.smudgeStrength = 1f
        vm.smudgeSizeScreen = 40f

        stylus(view, MotionEvent.ACTION_DOWN, 100f, 100f, t, 0.5f)
        stylus(view, MotionEvent.ACTION_MOVE, 120f, 100f, t + 40)
        stylus(view, MotionEvent.ACTION_MOVE, 140f, 100f, t + 80)
        stylus(view, MotionEvent.ACTION_UP, 140f, 100f, t + 120)

        assertEquals("one gesture = one smudge on the layer", 1, doc.activeLayer.smudges.size)
        val sm = doc.activeLayer.smudges[0]
        assertEquals(vm.camera.level.id, sm.levelId)
        assertTrue("stamps deposited along the drag", sm.stamps.size >= 3)
        assertEquals("strength x pressure alpha", 0.5f, sm.stamps[0].alpha, 0.0001f)
        assertEquals("radius = screen size / 2 (scale 1)", 20f, sm.stamps[0].radius, 0.0001f)

        assertTrue(doc.undo())
        assertEquals(0, doc.activeLayer.smudges.size)
        assertTrue(doc.redo())
        assertEquals(1, doc.activeLayer.smudges.size)
        controller.destroy()
    }

    // ---------------- pixel-level rendering (NATIVE graphics) ----------------

    private fun renderPixels(
        view: ZoomableCanvasView,
        setup: () -> Unit,
    ): IntArray {
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        setup()
        view.drawScene(canvas, includeInProgress = false)
        val px = IntArray(128 * 128)
        bmp.getPixels(px, 0, 128, 0, 0, 128, 128)
        bmp.recycle()
        return px
    }

    private fun darkCount(px: IntArray): Int =
        px.count { Color.red(it) < 128 && Color.green(it) < 128 && Color.blue(it) < 128 }

    @Test
    fun filledRectCoversFarMorePixelsThanOutlineOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = ZoomableCanvasView(context)
        val vm = view.viewModel

        vm.armedShapeType = ShapeType.RECTANGLE
        val filled = ShapeData(ShapeType.RECTANGLE, 0L)
        filled.setAnchors(20f, 20f, 100f, 100f)
        filled.fillOn = true
        filled.fillColor = 0xFF000000.toInt()
        filled.outlineWidthWorld = 0f

        val outlined = ShapeData(ShapeType.RECTANGLE, 0L)
        outlined.setAnchors(20f, 20f, 100f, 100f)
        outlined.fillOn = false
        outlined.outlineColor = 0xFF000000.toInt()
        outlined.outlineWidthWorld = 3f

        val filledPx = renderPixels(view) { vm.document.activeLayer.shapes.add(filled) }
        val fc = darkCount(filledPx)
        assertTrue("filled rect must render dark pixels (got $fc)", fc > 3000)

        val outlinedPx = renderPixels(view) {
            vm.document.activeLayer.shapes.clear() // only the outline shape renders
            vm.document.activeLayer.shapes.add(outlined)
        }
        val oc = darkCount(outlinedPx)
        assertTrue("outline rect renders some dark pixels (got $oc)", oc > 100)
        assertTrue("filled rect ($fc px) must dwarf the outline ($oc px)",
            fc > oc * 3)
    }

    @Test
    fun smudgeStampsRenderSoftIntermediateGrays() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = ZoomableCanvasView(context)
        val vm = view.viewModel

        vm.smudgeSoftness = 0.5f
        val sm = SmudgeData(0L)
        // black carried color at half alpha over the white background ->
        // mid-gray center, fading to white at the rim
        sm.stamps.add(StampData(64f, 64f, 30f, 0xFF000000.toInt(), 0.5f))
        vm.document.activeLayer.smudges.add(sm)

        val px = renderPixels(view) { }
        val center = px[64 * 128 + 64]
        val g = Color.green(center)
        assertTrue("stamp center must be an intermediate gray, got $g",
            g in 40..215)
        // rim (outside the radius) stays white
        val rim = px[10 * 128 + 10]
        assertEquals(Color.WHITE, rim)

        // fully-HARD stamp (softness 0) keeps a solid core: the same point
        // must be darker than the soft one's rim yet similar at center
        vm.smudgeSoftness = 0f
        val hardPx = renderPixels(view) {
            vm.document.activeLayer.smudges.clear()
            val hard = SmudgeData(0L)
            hard.stamps.add(StampData(64f, 64f, 30f, 0xFF000000.toInt(), 0.5f))
            vm.document.activeLayer.smudges.add(hard)
        }
        val midRing = hardPx[64 * 128 + 84] // 20px from center: inside radius
        val softRing = px[64 * 128 + 84]
        assertTrue("soft falloff must be lighter than the hard edge at the rim",
            Color.green(softRing) > Color.green(midRing))
    }

    @Test
    fun blendPanelSlidersDriveTheSmudgeSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = com.example.zoomart.model.DrawingViewModel()
        val panel = com.example.zoomart.ui.BlendPanel(context, vm)
        val sliders = ArrayList<android.widget.SeekBar>()
        fun walk(v: View) {
            if (v is android.widget.SeekBar) sliders.add(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(panel)
        assertEquals("strength / size / softness sliders", 3, sliders.size)

        // panel initializes from the VM defaults (0.7 / 40px / 0.5)
        assertEquals(70, sliders[0].progress)
        assertEquals(24, sliders[1].progress)   // (40 - 5) / 1.45
        assertEquals(50, sliders[2].progress)

        // syncFromVm maps VM state -> slider positions
        vm.smudgeStrength = 0.8f
        vm.smudgeSizeScreen = 77.5f           // 5 + 50 * 1.45
        vm.smudgeSoftness = 0.25f
        panel.syncFromVm()
        assertEquals(80, sliders[0].progress)
        assertEquals(50, sliders[1].progress)
        assertEquals(25, sliders[2].progress)
        assertFalse(vm.document.canUndo) // sliders are render state, no undo entries
    }
}
