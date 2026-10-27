package com.example.zoomart.ui.glass

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout

/**
 * U1 (UI overhaul): the decoupled overlay that hosts every floating panel
 * and the toolbar dock. A plain FrameLayout SIBLING above the canvas inside
 * the activity's content FrameLayout — never a window-level WindowManager
 * layer, so everything stays in-activity (safe, simple, lifecycle-free).
 *
 * GESTURE-ISOLATION GUARANTEE: this layer never sets an OnTouchListener
 * or OnInterceptTouchListener and is not clickable — Android's hit testing
 * skips it everywhere except where a VISIBLE FloatingPanel (or the dock)
 * actually is. Touches landing on a panel are consumed by the panel shell
 * (its handle drags, its controls click); every other touch falls through
 * to the canvas untouched. GONE/INVISIBLE shells are not hit-tested at
 * all, so a hidden dock lets pen strokes pass straight to the canvas.
 *
 * AUTO-HIDE: the canvas reports stylus-stroke/pinch activity through
 * [onCanvasActivity]; the [AutoHideStateMachine] slides visible shells
 * toward the nearest edge and fades them (dock to 0.0 + INVISIBLE,
 * panels to 0.15 — the sliver stays draggable and a touch restores
 * everything), springing back after the ~600 ms idle debounce. The
 * animations touch ONLY the shells' own subtrees — the canvas keeps its
 * frame budget (verified by the gfxinfo pass in docs/UI_OVERHAUL.md).
 */
class OverlayLayer(context: Context) : FrameLayout(context) {

    private val shells = ArrayList<FloatingPanel>()
    private var dock: FloatingPanel? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The auto-hide pipeline; exposed for the state-machine tests. */
    val autoHide = AutoHideStateMachine(
        scheduler = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
        onHide = { applyAutoHidden(true) },
        onShow = { applyAutoHidden(false) },
    )

    fun addShell(
        shell: FloatingPanel,
        gravity: Int,
        marginDp: Int = 12,
        bottomInsetPx: Int = 0,
    ) {
        shell.onPanelTouched = { autoHide.panelTouched() }
        val m = (resources.displayMetrics.density * marginDp).toInt()
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity)
        lp.setMargins(m, m, m, maxOf(m, bottomInsetPx))
        addView(shell, lp)
        shells.add(shell)
    }

    /** The dock joins the auto-hide pipeline (fades to 0.0 + INVISIBLE). */
    fun setDock(shell: FloatingPanel, gravity: Int, marginDp: Int = 10) {
        shell.onPanelTouched = { autoHide.panelTouched() }
        dock = shell
        val m = (resources.displayMetrics.density * marginDp).toInt()
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity)
        lp.setMargins(m, m, m, m)
        addView(shell, lp)
    }

    /** Canvas reports stylus-stroke / pinch begin(true)/end(false). */
    fun onCanvasActivity(active: Boolean) {
        autoHide.canvasActivity(active)
    }

    /** True while the pipeline has the shells hidden (test hook). */
    val panelsCurrentlyHidden: Boolean get() = autoHide.panelsHidden

    /** Whether auto-hide is active (user can disable it in Settings). */
    var autoHideEnabled = true
        set(value) {
            field = value
            if (!value) autoHide.resetToIdle()
        }

    private fun applyAutoHidden(hidden: Boolean) {
        if (!autoHideEnabled) return
        for (s in shells) {
            if (s.isShowing) s.setAutoHidden(hidden)
        }
        dock?.setAutoHidden(hidden)
    }

    /** Instantly brings everything back (used after demo/tour cancels). */
    fun restoreNow() {
        autoHide.panelTouched()
    }

    // documented no-op: this layer must NEVER consume or intercept touches
    override fun setOnTouchListener(l: View.OnTouchListener?) {
        if (l != null) {
            throw IllegalStateException(
                "OverlayLayer must never consume touches (gesture isolation)")
        }
        super.setOnTouchListener(l)
    }

    override fun setClickable(clickable: Boolean) {
        if (clickable) {
            throw IllegalStateException(
                "OverlayLayer must stay non-clickable (gesture isolation)")
        }
        super.setClickable(clickable)
    }
}
