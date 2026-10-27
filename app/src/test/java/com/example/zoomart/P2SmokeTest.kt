package com.example.zoomart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.zoomart.model.BrushSpec
import com.example.zoomart.model.LayerBlend
import com.example.zoomart.ui.LayersPanel
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * P2 Robolectric smoke tests:
 *  - lock semantics through REAL stylus touch dispatch (the executable spec
 *    for the on-device behavior, like AppSmokeTest is for P1),
 *  - the LayersPanel v2 controls (lock/duplicate/blend cycle/reorder/rename
 *    dialog/collapse) driving the real document,
 *  - blend-mode compositing verified at the PIXEL level: drawScene renders
 *    into a Bitmap (Robolectric native graphics rasterize for real) and the
 *    overlap of two strokes must differ per blend mode exactly as
 *    PorterDuff/Skia blend math dictates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real rasterization: blend-mode pixel checks
class P2SmokeTest {

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

    // ---------------- P2.2: lock through real touch ----------------

    @Test
    fun lockedLayerBlocksStylusDrawingAndUnlockRestoresIt() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        val view = activity.canvasView
        val vm = view.viewModel
        val doc = vm.document
        val t = SystemClock.uptimeMillis()

        doc.setLayerLocked(doc.activeLayerIndex, true)
        stylus(view, MotionEvent.ACTION_DOWN, 100f, 100f, t)
        stylus(view, MotionEvent.ACTION_MOVE, 150f, 140f, t + 40)
        stylus(view, MotionEvent.ACTION_UP, 150f, 140f, t + 80)
        assertEquals("locked layer must reject stylus strokes", 0, doc.activeLayer.strokes.size)
        // the ONLY undo entry is the lock itself; the rejected gesture pushed nothing
        assertTrue(doc.undo())
        assertFalse(doc.activeLayer.locked)
        assertFalse("rejected strokes must not push undo entries", doc.canUndo)

        // unlocked again (via the undo above) -> the same gesture draws
        stylus(view, MotionEvent.ACTION_DOWN, 100f, 100f, t + 200)
        stylus(view, MotionEvent.ACTION_MOVE, 150f, 140f, t + 240)
        stylus(view, MotionEvent.ACTION_UP, 150f, 140f, t + 280)
        assertEquals("unlocked layer must draw again", 1, doc.activeLayer.strokes.size)
    }

    // ---------------- P2.4: panel v2 controls ----------------

    private fun walk(root: View, pred: (View) -> Boolean): List<View> {
        val out = ArrayList<View>()
        fun rec(v: View) {
            if (pred(v)) out.add(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) rec(v.getChildAt(i))
        }
        rec(root)
        return out
    }

    private fun buttonsWithText(root: View, text: String): List<Button> =
        walk(root) { it is Button && it.text.toString() == text }.map { it as Button }

    @Test
    fun layersPanelV2ControlsDriveTheDocument() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        val canvas = activity.canvasView
        val doc = canvas.viewModel.document
        val panel = LayersPanel(activity, canvas) { }
        panel.refresh()

        // rows are built in layer order: Background first, Layer 1 second
        val lockButtons = buttonsWithText(panel, "Lock")
        val dupButtons = buttonsWithText(panel, "Dup")
        val blendButtons = buttonsWithText(panel, "Normal")
        val upButtons = buttonsWithText(panel, "Up")
        val dnButtons = buttonsWithText(panel, "Dn")
        assertEquals("one Lock per layer", 2, lockButtons.size)
        assertEquals("one Dup per layer", 2, dupButtons.size)
        assertEquals("one blend cycle per layer", 2, blendButtons.size)
        assertEquals("one Up per layer", 2, upButtons.size)
        assertEquals("one Dn per layer", 2, dnButtons.size)

        // --- LOCK toggles the second layer and the row shows a padlock ---
        lockButtons[1].performClick()
        assertTrue(doc.layers[1].locked)
        assertTrue("panel rebuilt with Unlock for the locked row",
            buttonsWithText(panel, "Unlock").size == 1)
        val nameViews = walk(panel) {
            it is TextView && it.text.toString().contains("Layer 1")
        }.map { it as TextView }
        assertTrue("locked row must show a padlock glyph",
            nameViews.any { it.text.toString().contains("\uD83D\uDD12") })
        // re-locking an already locked layer must not push a second undo entry
        doc.setLayerLocked(1, true)
        assertTrue(doc.undo())          // undoes the lock
        assertFalse(doc.layers[1].locked)
        assertFalse("repeat no-op lock must not add an undo step", doc.undo())
        // restore the locked state for the rest of the test via the panel
        panel.refresh()                 // rows still showed the pre-undo state
        buttonsWithText(panel, "Lock")[1].performClick()
        assertTrue(doc.layers[1].locked)
        buttonsWithText(panel, "Unlock")[0].performClick()
        assertFalse(doc.layers[1].locked)

        // --- DUPLICATE deep-copies the second layer, activates the copy ---
        dupButtons[1].performClick()
        assertEquals(3, doc.layers.size)
        assertSame(doc.layers[2], doc.activeLayer)
        assertEquals("Layer 1 copy", doc.activeLayer.name)

        // --- BLEND cycles Normal -> Multiply -> Screen -> Overlay -> Normal ---
        // rows rebuilt after the duplicate: [Background, Layer 1, Layer 1 copy];
        // the ACTIVE layer is the copy -> its blend button is the LAST "Normal"
        val blend = walk(panel) { it is Button && it.text.toString() == "Normal" }
            .map { it as Button }.last()
        blend.performClick()
        assertEquals(LayerBlend.MULTIPLY, doc.activeLayer.blend)
        blend.performClick()
        assertEquals(LayerBlend.SCREEN, doc.activeLayer.blend)
        blend.performClick()
        assertEquals(LayerBlend.OVERLAY, doc.activeLayer.blend)
        blend.performClick()
        assertEquals(LayerBlend.NORMAL, doc.activeLayer.blend)

        // --- REORDER via Up: active copy moves above Layer 1 ---
        val up = walk(panel) { it is Button && it.text.toString() == "Up" }
            .map { it as Button }[2] // third row = the copy
        up.performClick()
        assertSame(doc.activeLayer, doc.layers[1])
        assertEquals("Layer 1 copy", doc.layers[1].name)
        val dn = walk(panel) { it is Button && it.text.toString() == "Dn" }
            .map { it as Button }[1]
        dn.performClick()
        assertSame(doc.activeLayer, doc.layers[2])

        // --- visibility checkbox still works, opacity slider synced ---
        val sliders = walk(panel) { it is SeekBar }
        assertEquals("one opacity slider per layer", 3, sliders.size)
        assertEquals(100, (sliders[2] as SeekBar).progress)

        // --- collapse chevron hides the rows section ---
        val chevron = walk(panel) { it is Button && it.text.toString() == "\u25B2" }
            .map { it as Button }[0]
        chevron.performClick()
        assertFalse("rows must collapse to GONE", panel.rowsVisible)
        val chevronCollapsed = walk(panel) {
            it is Button && it.text.toString() == "\u25BC"
        }.map { it as Button }[0]
        chevronCollapsed.performClick()
        assertTrue(panel.rowsVisible)
    }

    @Test
    fun layersPanelRenameDialogRenamesTheLayer() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        val canvas = activity.canvasView
        val doc = canvas.viewModel.document
        val panel = LayersPanel(activity, canvas) { }
        panel.refresh()

        // tap the SECOND row's name (Layer 1) -> rename dialog
        val nameView = walk(panel) {
            it is TextView && it.text.toString().startsWith("Layer 1")
        }[0]
        nameView.performClick()

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("rename dialog must be showing", dialog)
        val input = dialog!!.findViewById<EditText>(android.R.id.edit)
        assertNotNull("dialog must contain the prefilled name EditText", input)
        assertEquals("Layer 1", input!!.text.toString())
        input.setText("Ink")
        val ok = (dialog as androidx.appcompat.app.AlertDialog)
            .getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
        ok!!.performClick()
        // appcompat dispatches the button click through the main-looper handler
        ShadowLooper.idleMainLooper()
        assertEquals("Ink", doc.layers[1].name)
    }

    // ---------------- P2.3: blend compositing at the pixel level ----------------

    @Test
    fun blendModesCompositeInDrawScene() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = ZoomableCanvasView(context)
        val vm = view.viewModel
        val doc = vm.document

        // bottom: opaque RED stroke on Background
        doc.setActiveLayer(0)
        vm.currentBrush = BrushSpec(
            baseWidthWorld = 40f, pressureResponse = 0f,
            color = 0xFFFF0000.toInt(), opacity = 1f,
        )
        vm.beginStroke(32f, 32f, 1f)
        vm.extendStroke(34f, 32f, 1f)
        vm.endStroke()

        // top: opaque BLUE stroke on Layer 1 (the blended sheet)
        doc.setActiveLayer(1)
        vm.currentBrush = BrushSpec(
            baseWidthWorld = 40f, pressureResponse = 0f,
            color = 0xFF0000FF.toInt(), opacity = 1f,
        )
        vm.beginStroke(32f, 32f, 1f)
        vm.extendStroke(34f, 32f, 1f)
        vm.endStroke()

        fun overlapPixel(): Int {
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            view.drawScene(canvas, includeInProgress = false)
            return bmp.getPixel(33, 32)
        }

        // sanity for the probe geometry: red alone under a normal sheet = red
        doc.layers[1].isVisible = false
        val redOnly = overlapPixel()
        doc.layers[1].isVisible = true
        assertTrue("probe pixel must sit on the red stroke (got ${Integer.toHexString(redOnly)})",
            Color.red(redOnly) > 200 && Color.green(redOnly) < 60)

        // NORMAL: opaque blue over red -> blue wins
        doc.setLayerBlend(1, LayerBlend.NORMAL)
        val normalPx = overlapPixel()
        assertTrue("NORMAL must show the blue stroke on top",
            Color.blue(normalPx) > 200 && Color.red(normalPx) < 60)

        // MULTIPLY: red * blue = black
        doc.setLayerBlend(1, LayerBlend.MULTIPLY)
        val multiplyPx = overlapPixel()
        assertTrue("MULTIPLY must darken red*blue to ~black (got ${Integer.toHexString(multiplyPx)})",
            Color.red(multiplyPx) < 60 && Color.green(multiplyPx) < 60 && Color.blue(multiplyPx) < 60)

        // SCREEN: red + blue - red*blue = magenta
        doc.setLayerBlend(1, LayerBlend.SCREEN)
        val screenPx = overlapPixel()
        assertTrue("SCREEN must lighten red+blue to magenta (got ${Integer.toHexString(screenPx)})",
            Color.red(screenPx) > 200 && Color.green(screenPx) < 60 && Color.blue(screenPx) > 200)
    }

    @Test
    fun overlayBlendDiffersFromNormal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = ZoomableCanvasView(context)
        val vm = view.viewModel
        val doc = vm.document

        // bottom: opaque RED stroke
        doc.setActiveLayer(0)
        vm.currentBrush = BrushSpec(
            baseWidthWorld = 40f, pressureResponse = 0f,
            color = 0xFFFF0000.toInt(), opacity = 1f,
        )
        vm.beginStroke(32f, 32f, 1f)
        vm.extendStroke(34f, 32f, 1f)
        vm.endStroke()

        // top: opaque dark gray (0x555555) stroke — OVERLAY keeps the red
        // channel HIGH where the destination is saturated but crushes where
        // it is dark, unlike NORMAL which just paints gray
        doc.setActiveLayer(1)
        vm.currentBrush = BrushSpec(
            baseWidthWorld = 40f, pressureResponse = 0f,
            color = 0xFF555555.toInt(), opacity = 1f,
        )
        vm.beginStroke(32f, 32f, 1f)
        vm.extendStroke(34f, 32f, 1f)
        vm.endStroke()

        fun overlapPixel(): Int {
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            view.drawScene(canvas, includeInProgress = false)
            return bmp.getPixel(33, 32)
        }

        doc.setLayerBlend(1, LayerBlend.NORMAL)
        val normalPx = overlapPixel()
        doc.setLayerBlend(1, LayerBlend.OVERLAY)
        val overlayPx = overlapPixel()

        // NORMAL: flat gray covers the red
        assertTrue("NORMAL must paint flat gray (got ${Integer.toHexString(normalPx)})",
            Math.abs(Color.red(normalPx) - 0x55) < 30 && Math.abs(Color.green(normalPx) - 0x55) < 30)
        // OVERLAY with dst red saturated: red channel boosted well above
        // the gray source, green crushed to ~0
        assertTrue("OVERLAY must differ from NORMAL (got ${Integer.toHexString(overlayPx)})",
            Color.red(overlayPx) - Color.green(overlayPx) > 80)
    }
}
