package com.example.zoomart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.zoomart.model.ImageDisplayFilter
import com.example.zoomart.model.PlacedImage
import com.example.zoomart.ui.ImagePanel
import com.example.zoomart.ui.ZoomableCanvasView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * P4 Robolectric smoke tests — the executable spec for the on-device pen
 * behavior (adb cannot inject stylus tool types, unchanged since P1):
 *  - P4.2 IMAGE TRANSFORMS through REAL stylus dispatch: tap-selects an
 *    image, drag MOVES it, corner-handle drag RESIZES it — each ONE undo
 *    step;
 *  - P4.3 ImagePanel controls driving the real document (transparency
 *    mapping, lock, display-filter cycle, TRACING MODE as one command) +
 *    the persistent bitmap-blur note;
 *  - P4.3 display filters verified at the PIXEL level (real ColorMatrix
 *    rasterization over a real decoded bitmap);
 *  - P4.5 demo seeding + the ANY-TOUCH cancel contract + the zoom/depth
 *    readout format.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class P4SmokeTest {

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

    private fun stylus(view: View, action: Int, x: Float, y: Float, t: Long) =
        touch(view, action, x, y, t, MotionEvent.TOOL_TYPE_STYLUS)

    private fun finger(view: View, action: Int, x: Float, y: Float, t: Long) =
        touch(view, action, x, y, t, MotionEvent.TOOL_TYPE_FINGER)

    private fun walk(root: View, pred: (View) -> Boolean): List<View> {
        val out = ArrayList<View>()
        fun rec(v: View) {
            if (pred(v)) out.add(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) rec(v.getChildAt(i))
        }
        rec(root)
        return out
    }

    /**
     * Deterministic seeded image on the ACTIVE layer via the legacy
     * placement math (viewport fallback 1080x2340 at scale 1):
     * w = 648, h = 324, box (216, 1008)-(864, 1332), center (540, 1170).
     */
    private fun seedActiveLayerImage(activity: MainActivity): PlacedImage {
        val img = activity.canvasView.viewModel.placeImage("imports/probe.png", 2f)!!
        assertEquals(216f, img.x, 0.001f)
        assertEquals(1008f, img.y, 0.001f)
        return img
    }

    // ---------------- P4.2: image select / move / resize via stylus ----------------

    @Test
    fun stylusTapsSelectsMovesAndResizesAnImage() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        val view = activity.canvasView
        val vm = view.viewModel
        val doc = vm.document
        val t = SystemClock.uptimeMillis()
        val img = seedActiveLayerImage(activity)

        // --- TAP selects the image; dragging the tap MOVES it ---
        view.tool = ZoomableCanvasView.Tool.SELECT
        stylus(view, MotionEvent.ACTION_DOWN, 540f, 1170f, t)
        assertTrue("tap must select the image", doc.selection.selectedImages.contains(img))
        stylus(view, MotionEvent.ACTION_MOVE, 580f, 1210f, t + 40)
        stylus(view, MotionEvent.ACTION_UP, 580f, 1210f, t + 80)
        assertEquals("drag moved the image", 256f, img.x, 0.001f)
        assertEquals(1048f, img.y, 0.001f)
        assertEquals("image still on its layer", 1, doc.activeLayer.images.size)

        assertTrue(doc.undo()) // the move is ONE undo step
        assertEquals(216f, img.x, 0.001f)
        assertTrue(doc.redo())
        assertEquals(256f, img.x, 0.001f)

        // --- the selection persists; grab the bottom-RIGHT handle of the
        // moved bbox (904, 1372) — the top-left corner is the resize pivot
        assertTrue(doc.selection.selectedImages.contains(img))
        stylus(view, MotionEvent.ACTION_DOWN, 904f, 1372f, t + 300)
        stylus(view, MotionEvent.ACTION_MOVE, 1104f, 1372f, t + 340)
        stylus(view, MotionEvent.ACTION_UP, 1104f, 1372f, t + 380)
        val factor = Math.hypot(1104.0 - 256.0, 1372.0 - 1048.0) /
            Math.hypot(904.0 - 256.0, 1372.0 - 1048.0)
        assertEquals("uniform resize grew the image", 648.0 * factor, img.w.toDouble(), 2.0)
        assertEquals("pivot corner stays fixed", 256f, img.x, 0.01f)
        assertEquals(1048f, img.y, 0.01f)

        assertTrue(doc.undo()) // the resize is ONE undo step
        assertEquals(648f, img.w, 0.001f)
        assertEquals(324f, img.h, 0.001f)
        controller.destroy()
    }

    @Test
    fun imagePanelControlsDriveTheCurrentImageAndShowTheNote() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        val view = activity.canvasView
        val vm = view.viewModel
        val doc = vm.document
        val img = seedActiveLayerImage(activity)

        val panel = ImagePanel(activity, view) { }
        panel.refresh()

        // the persistent bitmap-blur note (audit UI item)
        assertNotNull("panel carries the bitmap note",
            walk(panel) { it is TextView && it.contentDescription == "bitmap note" })

        // transparency slider maps alpha <-> progress
        val sliders = walk(panel) { it is SeekBar }.map { it as SeekBar }
        assertEquals("exactly one slider (transparency)", 1, sliders.size)
        val alphaBar = sliders[0]
        assertEquals(100, alphaBar.progress) // alpha 1 -> 100%
        img.alpha = 0.4f
        panel.refresh()
        assertEquals(40, alphaBar.progress)
        img.alpha = 1f // restore for the tracing assertions below
        panel.refresh()

        // LOCK toggle drives the (undoable) image lock
        val lock = walk(panel) { it is Button && it.text.toString() == "Lock" }
            .map { it as Button }[0]
        lock.performClick()
        assertTrue(img.locked)
        panel.refresh()
        assertNotNull("the lock button reads Unlock while locked",
            walk(panel) { it is Button && it.text.toString() == "Unlock" }.firstOrNull())
        assertTrue(doc.undo())
        assertFalse(img.locked)

        // DISPLAY cycle: Off -> Gray -> Contrast -> Off (UI-state only)
        val display = walk(panel) { it is Button && it.contentDescription == "image display filter" }
            .map { it as Button }[0]
        assertEquals("Display: Off", display.text.toString())
        display.performClick()
        assertEquals(ImageDisplayFilter.GRAYSCALE, img.displayFilter)
        assertEquals("Display: Gray", display.text.toString())
        display.performClick()
        assertEquals(ImageDisplayFilter.HIGH_CONTRAST, img.displayFilter)
        display.performClick()
        assertEquals(ImageDisplayFilter.NONE, img.displayFilter)

        // TRACING MODE: one tap -> alpha 40% + lock + fresh layer above
        val trace = walk(panel) { it is Button && it.contentDescription == "image tracing mode" }
            .map { it as Button }[0]
        trace.performClick()
        assertEquals(0.4f, img.alpha, 0.0001f)
        assertTrue(img.locked)
        assertEquals(3, doc.layers.size)
        val traceLayer = doc.layers[2]
        assertEquals("Layer 1 trace", traceLayer.name)
        assertSame(traceLayer, doc.activeLayer)
        assertTrue(doc.undo()) // the whole composite is ONE undo step
        assertEquals(1f, img.alpha, 0.0001f)
        assertFalse(img.locked)
        assertEquals(2, doc.layers.size)
        controller.destroy()
    }

    // ---------------- P4.3: display filters at the pixel level ----------------

    @Test
    fun displayFiltersRenderThroughTheColorMatrix() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = ZoomableCanvasView(context)
        val vm = view.viewModel

        // a real PNG on disk: a solid (100, 150, 200) fill
        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        val file = File(dir, "filter_probe.png")
        val src = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.rgb(100, 150, 200))
        java.io.FileOutputStream(file).use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }
        src.recycle()

        val img = PlacedImage("imports/filter_probe.png", 10f, 10f, 100f, 100f, 0L)
        vm.document.activeLayer.images.add(img)

        fun centerPixel(): Int {
            val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            view.drawScene(canvas, includeInProgress = false)
            val px = bmp.getPixel(60, 60)
            bmp.recycle()
            return px
        }

        // NONE: the plain bitmap color
        val plain = centerPixel()
        assertTrue("probe pixel must show the imported color, got " +
            Integer.toHexString(plain),
            Math.abs(Color.red(plain) - 100) < 12 &&
            Math.abs(Color.green(plain) - 150) < 12 &&
            Math.abs(Color.blue(plain) - 200) < 12)

        // GRAYSCALE: saturation 0 -> r == g == b (luma of the fill)
        img.displayFilter = ImageDisplayFilter.GRAYSCALE
        val gray = centerPixel()
        assertTrue("grayscale must equalize channels, got " + Integer.toHexString(gray),
            Math.abs(Color.red(gray) - Color.green(gray)) < 3 &&
            Math.abs(Color.green(gray) - Color.blue(gray)) < 3)
        assertTrue("grayscale luma ~143, got " + Color.green(gray),
            Math.abs(Color.green(gray) - 143) < 12)

        // HIGH CONTRAST: 1.8x about the 128 midpoint -> (78, 168, 255)
        img.displayFilter = ImageDisplayFilter.HIGH_CONTRAST
        val contrast = centerPixel()
        assertTrue("contrast must boost spread, got " + Integer.toHexString(contrast),
            Color.red(contrast) < 100 && Color.green(contrast) > 150 && Color.blue(contrast) > 240)
        assertTrue("contrast changes the render vs plain",
            contrast != plain && contrast != gray)
    }

    // ---------------- P4.5: demo seeding + cancel + readout ----------------

    @Test
    fun demoSeedsRingsFliesAndAnyTouchCancels() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        val view = activity.canvasView
        val vm = view.viewModel
        val doc = vm.document
        val t = SystemClock.uptimeMillis()
        val layersBefore = doc.layers.size

        view.runDemo()
        assertTrue(view.demoActive)
        assertEquals("demo seeded its ring layers", layersBefore + 4, doc.layers.size)
        assertEquals("demo levels chain created", 4, doc.levelTree.levelCount())
        assertFalse("demo seeding pollutes NO undo entries", doc.canUndo)

        // ANY touch cancels the flight (scenery stays)
        finger(view, MotionEvent.ACTION_DOWN, 300f, 300f, t + 100)
        assertFalse(view.demoActive)
        assertEquals("scenery stays after cancel", layersBefore + 4, doc.layers.size)
        controller.destroy()
    }

    @Test
    fun zoomReadoutShowsMagnificationAndLevelAlways() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = ZoomableCanvasView(context)

        assertEquals("1.0x · level 0", view.cameraReadout())
        for (i in 0 until 7) view.zoomBy(2f) // 128x crosses the promote threshold
        assertEquals(1, view.viewModel.camera.level.depth)
        assertEquals("128.0x · level 1", view.cameraReadout())
    }
}
