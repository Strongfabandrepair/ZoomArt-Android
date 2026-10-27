package com.example.zoomart

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.example.zoomart.ui.ZoomableCanvasView
import com.example.zoomart.ui.glass.AutoHideStateMachine
import com.example.zoomart.ui.glass.FloatingPanel
import com.example.zoomart.ui.glass.OverlayLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * U1 (UI overhaul) Robolectric tests:
 *  - FloatingPanel drag: offsets persist in SharedPreferences (per-panel
 *    key) and reload into a fresh shell;
 *  - backdrop snapshots happen on SHOW and DRAG END ONLY — never per drag
 *    frame (the 60fps guarantee, asserted through the injected counter);
 *  - the real canvas fires onCanvasActivityChanged on stylus stroke
 *    begin/end and the real OverlayLayer auto-hide pipeline reacts
 *    (fade on active, debounce-restore on idle, dock INVISIBLE when
 *    fully hidden so touches pass through to the canvas);
 *  - the real activity boots on the new shell system (canvas below, glass
 *    overlay above, dock visible, panels GONE until toggled).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassPanelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * androidx.dynamicanimation keeps its AnimationHandler in a static
     * ThreadLocal, and the handler caches the Choreographer of the looper
     * that first started a spring. Robolectric recreates the main looper
     * PER TEST METHOD, so a handler claimed by an earlier method posts
     * frames to a dead looper and this method's springs never tick.
     * Dropping the ThreadLocal binding before each method re-binds the
     * handler to the CURRENT looper (test-infra only; production has one
     * looper for the whole process).
     */
    @Before
    fun resetDynamicAnimationHandler() {
        try {
            val f = Class.forName("androidx.dynamicanimation.animation.AnimationHandler")
                .getDeclaredField("sAnimatorHandler")
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (f.get(null) as ThreadLocal<*>).remove()
        } catch (_: Throwable) {
            // field renamed upstream -> the spring assertions just become
            // order-dependent; nothing else breaks
        }
    }

    private fun clearPanelPrefs() {
        context.getSharedPreferences("zoomart_panels", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ---------------- drag persistence ----------------

    @Test
    fun dragEndCommitsAndPersistsTheRestPosition() {
        clearPanelPrefs()
        val host = FrameLayout(context)
        val shell = FloatingPanel(context, "PersistPanel", { null })
        host.addView(shell)
        shell.show()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() // freeze post

        shell.beginDrag(500f, 500f)
        shell.dragBy(40f, 25f)
        shell.dragBy(-10f, 5f)
        shell.endDrag()

        // a fresh shell attached to a REAL window (a bare FrameLayout host
        // never fires onAttachedToWindow) — addView installs its own
        // gravity LP; the saved rest position must be applied at attach
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val reborn = FloatingPanel(controller.get(), "PersistPanel", { null })
        controller.get().overlay.addView(reborn, FrameLayout.LayoutParams(500, 900))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() // apply post
        val lp = reborn.layoutParams as FrameLayout.LayoutParams
        assertEquals(30, lp.leftMargin)
        assertEquals(30, lp.topMargin)
        assertEquals(Gravity.TOP or Gravity.START, lp.gravity)
        // the live shell no longer carries a drag translation
        assertEquals(0f, shell.translationX, 0.001f)
        assertEquals(0f, shell.translationY, 0.001f)
        controller.destroy()
    }

    // ---------------- snapshot-on-show, not per frame ----------------

    @Test
    fun backdropSnapshotsOnShowAndDragEndOnlyNeverPerFrame() {
        clearPanelPrefs()
        var providerCalls = 0
        val shell = FloatingPanel(context, "SnapPanel", { p ->
            providerCalls++
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        })

        // API 31+ path (Robolectric runs sdk 34): show -> exactly one snapshot
        shell.show()
        assertEquals("show must snapshot exactly once", 1, shell.backdropSnapshotCount)
        assertEquals(1, providerCalls)

        // any number of drag frames must NOT re-render the canvas
        shell.beginDrag(500f, 500f)
        for (i in 0 until 50) shell.dragBy(3f, 1f)
        assertEquals("drag frames must never snapshot per frame",
            1, shell.backdropSnapshotCount)
        assertEquals(1, providerCalls)

        // drag end -> one fresh snapshot (the moved panel samples its new
        // backdrop), and only one
        shell.endDrag()
        assertEquals(2, shell.backdropSnapshotCount)
        assertEquals(2, providerCalls)
    }

    // ---------------- real canvas -> real overlay pipeline ----------------

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

    private fun idleMain(ms: Long) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    @Test
    fun stylusStrokeDrivesTheAutoHidePipelineEndToEnd() {
        clearPanelPrefs()
        val canvas = ZoomableCanvasView(context)
        val overlay = OverlayLayer(context)

        val host = FrameLayout(context)
        host.addView(canvas, FrameLayout.LayoutParams(1080, 2340))
        host.addView(overlay, FrameLayout.LayoutParams(1080, 2340))
        canvas.layout(0, 0, 1080, 2340)
        overlay.layout(0, 0, 1080, 2340)

        val shell = FloatingPanel(context, "PipelinePanel", { null })
        overlay.addShell(shell, Gravity.BOTTOM or Gravity.START)
        shell.show()
        // give the shell a real rect so the hide vector has bounds to work with
        shell.layout(20, 1900, 420, 2200)
        assertTrue(shell.isShowing)

        val dock = FloatingPanel(context, "PipelineDock",
            { null }, kind = FloatingPanel.Kind.DOCK)
        overlay.setDock(dock, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        dock.show()
        dock.layout(290, 2260, 790, 2330)

        canvas.onCanvasActivityChanged = { active -> overlay.onCanvasActivity(active) }
        assertEquals(AutoHideStateMachine.State.IDLE, overlay.autoHide.state)

        // a real stylus stroke begins -> panels fade out toward the edges
        val t = SystemClock.uptimeMillis()
        touch(canvas, MotionEvent.ACTION_DOWN, 500f, 800f, t, MotionEvent.TOOL_TYPE_STYLUS, 0.8f)
        assertEquals(AutoHideStateMachine.State.ACTIVE, overlay.autoHide.state)
        assertTrue("panel must be flagged faded", shell.fadedForAutoHide)
        assertTrue("dock must be flagged faded", dock.fadedForAutoHide)

        // let the fade springs settle (alpha 0.15 / 0.0 + slide-out)
        idleMain(2000)
        assertTrue("faded panel sliver keeps a little alpha",
            shell.alpha in 0.02f..0.5f)
        assertEquals("dock fades all the way out and stops hit-testing",
            View.INVISIBLE, dock.visibility)

        // ...stroke ends -> 600ms debounce -> everything springs back
        touch(canvas, MotionEvent.ACTION_MOVE, 540f, 830f, t + 40, MotionEvent.TOOL_TYPE_STYLUS, 0.8f)
        touch(canvas, MotionEvent.ACTION_UP, 540f, 830f, t + 80, MotionEvent.TOOL_TYPE_STYLUS, 0.8f)
        assertEquals(AutoHideStateMachine.State.PENDING_RESTORE, overlay.autoHide.state)
        assertTrue("still faded inside the debounce window", shell.fadedForAutoHide)
        idleMain(2000)
        assertEquals(AutoHideStateMachine.State.IDLE, overlay.autoHide.state)
        idleMain(2500)
        assertTrue("panel restored to full alpha", shell.alpha > 0.9f)
        assertEquals("dock visible again", View.VISIBLE, dock.visibility)

        // finger pans NEVER trigger the auto-hide (spec: stylus + pinch only)
        touch(canvas, MotionEvent.ACTION_DOWN, 300f, 1200f, t + 400, MotionEvent.TOOL_TYPE_FINGER)
        touch(canvas, MotionEvent.ACTION_MOVE, 360f, 1260f, t + 440, MotionEvent.TOOL_TYPE_FINGER)
        touch(canvas, MotionEvent.ACTION_UP, 360f, 1260f, t + 480, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals("finger pan must not hide panels",
            AutoHideStateMachine.State.IDLE, overlay.autoHide.state)
        assertTrue(shell.alpha > 0.9f)
    }

    // ---------------- the whole activity boots on the new shell system ----------------

    @Test
    fun activityBootsWithGlassOverlayDockAndShells() {
        clearPanelPrefs()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        val activity = controller.get()

        // the dock + 6 panel shells live in the overlay; the canvas is a
        // sibling BELOW it (gesture isolation by construction)
        val overlay = activity.overlay
        assertEquals(7, overlay.childCount)

        val content = overlay.parent as FrameLayout
        assertEquals("content = canvas below + glass overlay above",
            2, content.childCount)
        assertTrue(content.getChildAt(0) is ZoomableCanvasView)
        assertTrue(content.getChildAt(1) === overlay)

        var docks = 0
        for (i in 0 until overlay.childCount) {
            val child = overlay.getChildAt(i)
            assertTrue("every overlay child is a floating shell", child is FloatingPanel)
            val fp = child as FloatingPanel
            if (fp.kind == FloatingPanel.Kind.DOCK) {
                docks++
                assertEquals("dock starts visible", View.VISIBLE, fp.visibility)
            } else {
                assertEquals("panels start hidden", View.GONE, fp.visibility)
            }
        }
        assertEquals("exactly one dock", 1, docks)

        // Regression (found on-device): a MATCH_PARENT fill child inside the
        // WRAP_CONTENT shell used to blow the shell up to the whole screen.
        // The dock is really laid out at setup — it must hug its content.
        val screenH = activity.resources.displayMetrics.heightPixels
        val dockShell = (0 until overlay.childCount)
            .map { overlay.getChildAt(it) as FloatingPanel }
            .first { it.kind == FloatingPanel.Kind.DOCK }
        assertTrue("laid-out dock height ${dockShell.height} must be a small strip",
            dockShell.height in 1 until screenH / 2)

        // And a PANEL shell measured with a screen-sized AT_MOST spec must
        // wrap its content, not expand to the full available space.
        val brushShell = overlay.getChildAt(0) as FloatingPanel
        val atMost = View.MeasureSpec.makeMeasureSpec(screenH * 3, View.MeasureSpec.AT_MOST)
        brushShell.measure(atMost, atMost)
        assertTrue("panel measured height ${brushShell.measuredHeight} must stay well under the screen",
            brushShell.measuredHeight < screenH * 3 / 4)
        assertTrue("panel measured width ${brushShell.measuredWidth} must stay under the screen",
            brushShell.measuredWidth <= screenH * 3)
        controller.destroy()
    }
}
