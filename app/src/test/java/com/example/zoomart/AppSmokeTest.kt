package com.example.zoomart

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.example.zoomart.model.DocumentCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Headless smoke test: boots the real activity, dispatches real touch
 * sequences, and asserts the whole draw -> undo/redo -> zoom -> pin ->
 * autosave-on-pause -> restore cycle under Robolectric on the JVM.
 *
 * P1.1: pen (TOOL_TYPE_STYLUS) events drive the drawing/pin phases, and a
 * dedicated finger phase proves TOOL_TYPE_FINGER events PAN the camera and
 * never commit strokes or pins. adb cannot inject tool types, so this is
 * the executable spec for pen-vs-finger routing on-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSmokeTest {

    @SuppressLint("ClickableViewAccessibility")
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

    @Test
    fun stylusDrawsFingerPansUndoZoomPinAutosaveAndRestoreCycle() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()
        val view = activity.canvasView
        val vm = view.viewModel

        // sanity: our routing constants match the platform's
        assertEquals(MotionEvent.TOOL_TYPE_FINGER, com.example.zoomart.model.TouchRouting.TOOL_TYPE_FINGER)
        assertEquals(MotionEvent.TOOL_TYPE_STYLUS, com.example.zoomart.model.TouchRouting.TOOL_TYPE_STYLUS)
        assertEquals(MotionEvent.TOOL_TYPE_ERASER, com.example.zoomart.model.TouchRouting.TOOL_TYPE_ERASER)

        // --- 1. STYLUS draws a three-point pressure-styled stroke ---
        val t = SystemClock.uptimeMillis()
        stylus(view, MotionEvent.ACTION_DOWN, 100f, 100f, t, 0.8f)
        stylus(view, MotionEvent.ACTION_MOVE, 130f, 108f, t + 32)
        stylus(view, MotionEvent.ACTION_MOVE, 160f, 120f, t + 64)
        stylus(view, MotionEvent.ACTION_UP, 160f, 120f, t + 96)
        assertEquals(1, vm.document.activeLayer.strokes.size)
        assertEquals(3, vm.document.activeLayer.strokes[0].points.size)
        assertEquals(0.8f, vm.document.activeLayer.strokes[0].points[0].pressure01, 0.001f)

        // --- 2. undo / redo ---
        assertTrue(vm.undo())
        assertEquals(0, vm.document.activeLayer.strokes.size)
        assertTrue(vm.redo())
        assertEquals(1, vm.document.activeLayer.strokes.size)

        // --- 3. FINGER drag pans the camera and never draws ---
        val transXBefore = vm.camera.transX
        val transYBefore = vm.camera.transY
        finger(view, MotionEvent.ACTION_DOWN, 200f, 300f, t + 200)
        finger(view, MotionEvent.ACTION_MOVE, 260f, 360f, t + 232)
        finger(view, MotionEvent.ACTION_UP, 260f, 360f, t + 248)
        assertEquals("finger drag must pan x", transXBefore + 60f, vm.camera.transX, 0.001f)
        assertEquals("finger drag must pan y", transYBefore + 60f, vm.camera.transY, 0.001f)
        assertEquals("finger must not commit strokes", 1, vm.document.activeLayer.strokes.size)

        // --- 4. FINGER double-tap does NOT pin ---
        finger(view, MotionEvent.ACTION_DOWN, 100f, 100f, t + 300)
        finger(view, MotionEvent.ACTION_UP, 100f, 100f, t + 316)
        finger(view, MotionEvent.ACTION_DOWN, 105f, 103f, t + 360)
        finger(view, MotionEvent.ACTION_UP, 105f, 103f, t + 376)
        assertEquals("finger double-tap must not pin", 0, vm.document.bookmarks.size)

        // --- 5. STYLUS double-tap drops a pin; taps never commit strokes ---
        stylus(view, MotionEvent.ACTION_DOWN, 100f, 100f, t + 500)
        stylus(view, MotionEvent.ACTION_UP, 100f, 100f, t + 516)
        stylus(view, MotionEvent.ACTION_DOWN, 105f, 103f, t + 560)
        stylus(view, MotionEvent.ACTION_UP, 105f, 103f, t + 576)
        assertEquals(1, vm.document.bookmarks.size)
        assertEquals("taps must never commit strokes", 1, vm.document.activeLayer.strokes.size)

        // --- 6. zoom keeps camera math honest even with zero-size view ---
        view.zoomBy(2f)
        assertEquals(2f, vm.camera.scale, 0.001f)

        // --- 7. pause -> atomic autosave fires ---
        controller.pause()
        val sessionFile = File(activity.filesDir, "zoomart_session.json")
        assertTrue("autosave file missing", sessionFile.exists())
        val restored = DocumentCodec.fromJson(sessionFile.readText())
        assertEquals(2f, restored.camera.scale, 0.001f)
        assertEquals(1, restored.document.activeLayer.strokes.size)
        assertEquals(1, restored.document.bookmarks.size)

        // --- 8. restore path (what relaunch exercises) ---
        vm.restore(restored)
        assertEquals(1, vm.document.activeLayer.strokes.size)
        assertEquals(2f, vm.camera.scale, 0.001f)

        controller.destroy()
    }

    @Test
    fun fingerFlingGlideDoesNotDraw() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val view = controller.get().canvasView
        val vm = view.viewModel
        val t = SystemClock.uptimeMillis()

        // a quick finger flick: DOWN -> fast MOVEs -> UP
        finger(view, MotionEvent.ACTION_DOWN, 500f, 500f, t)
        finger(view, MotionEvent.ACTION_MOVE, 450f, 470f, t + 16)
        finger(view, MotionEvent.ACTION_MOVE, 300f, 380f, t + 32)
        finger(view, MotionEvent.ACTION_MOVE, 150f, 260f, t + 48)
        finger(view, MotionEvent.ACTION_UP, 100f, 200f, t + 64)

        assertEquals("finger flick must not leave a stroke", 0, vm.document.activeLayer.strokes.size)
        assertTrue(
            "finger flick should have panned (or flung) the camera",
            vm.camera.transX != 0f || vm.camera.transY != 0f,
        )
        controller.destroy()
    }
}
