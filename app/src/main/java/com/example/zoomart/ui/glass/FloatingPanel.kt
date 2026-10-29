package com.example.zoomart.ui.glass

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * U1 (UI overhaul): the reusable floating glass shell that hosts every
 * panel. A FrameLayout with:
 *
 *  - a dedicated TOP HANDLE BAR that consumes drag gestures (the shell
 *    moves; the hosted content never sees them);
 *  - a dark translucent rounded background (GradientDrawable, 1px
 *    rgba(255,255,255,0.08) hairline) + elevation for a soft shadow;
 *  - a backdrop ImageView showing a 1/8-scale SNAPSHOT of the canvas
 *    region behind the panel, blurred via RenderEffect on API 31+.
 *    Snapshots are taken on SHOW and on DRAG END only — never per frame,
 *    which is the 60/120 fps guarantee (asserted by GlassPanelTest);
 *    on API 26-30 the panel is scrim-only glass (documented tiering);
 *  - spring show/hide/auto-hide micro-animations that touch ONLY this
 *    subtree (the decoupling rule: the canvas is never invalidated by
 *    panel animation);
 *  - position persistence in SharedPreferences under [key]
 *    (convention: the hosted panel class's simpleName), loaded in init.
 *
 * Gesture isolation: the shell is a normal in-activity view inside the
 * OverlayLayer (NO window-level WindowManager floating). Touches landing
 * on the shell are consumed by the shell; everywhere else they fall
 * through to the canvas — the OverlayLayer never intercepts.
 */
class FloatingPanel(
    context: Context,
    val key: String,
    private val snapshotProvider: ((panel: FloatingPanel) -> Bitmap?)? = null,
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    val kind: Kind = Kind.PANEL,
    /** v0.7.0: LIGHT reading-panel variant (brush list) — same machinery,
     *  the neutral tokens swap for the measured light-panel look. */
    private val light: Boolean = false,
) : FrameLayout(context) {

    enum class Kind { PANEL, DOCK }

    /** Fired on every ACTION_DOWN dispatched inside this shell (test/a11y
     *  hook; the OverlayLayer wires it to the auto-hide restore). */
    var onPanelTouched: (() -> Unit)? = null

    /** Count of backdrop snapshot renders (snapshot-on-show, not per
     *  frame — the U1 test asserts this stays flat during drags). */
    var backdropSnapshotCount = 0
        private set

    private val density = resources.displayMetrics.density
    private val cornerPx = GlassStyle.CORNER_DP * density
    private val blurRadiusPx = GlassStyle.BLUR_DP * density

    // ----- children (bottom to top) -----
    private val backdrop = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        visibility = GONE
    }
    private val scrim = com.example.zoomart.ui.StudioBackdrop(context,subdued=true,light=light)
    fun setAmbientPaused(paused: Boolean) { scrim.setMotionPaused(paused) }
    private val handle = PanelHandle(context)
    val content = FrameLayout(context).apply {
        val p = (density * 12).toInt()
        setPadding(p, (density * 4).toInt(), p, (density * 10).toInt())
    }

    // ----- position state -----
    /** Rest position in OverlayLayer coords, kept in left/topMargin. */
    private var restFrozen = false
    private var dragging = false
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var autoHidden = false
    private var explicitlyDismissed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Springs we may need to cancel (per property). */
    private val springs = HashMap<DynamicAnimation.ViewProperty, SpringAnimation>()

    /** Persisted rest position, applied after attach (see onAttachedToWindow):
     *  the OverlayLayer's addView installs its own gravity LayoutParams, so
     *  anything set in init would be clobbered. */
    private var savedRest: IntArray? = null

    init {
        background = if (light) GlassStyle.lightPanelBackground(density)
            else GlassStyle.panelBackground(density)
        // the hairline must draw ABOVE the backdrop/scrim children, so it
        // lives on the foreground layer (the background's own stroke is
        // covered by the fill children)
        foreground = GradientDrawable().apply {
            cornerRadius = cornerPx
            setStroke(maxOf(1, density.toInt()),
                if (light) GlassStyle.LIGHT_BORDER else GlassStyle.BORDER)
        }
        elevation = GlassStyle.ELEVATION_DP * density
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, width, height, cornerPx)
            }
        }

        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (kind == Kind.PANEL) {
            addView(handle, LayoutParams(LayoutParams.MATCH_PARENT, HANDLE_DP * density.toInt()))
        } else {
            // Docks get a thin drag handle too — tapped to drag, double-tap to collapse
            addView(handle, LayoutParams(LayoutParams.MATCH_PARENT, (HANDLE_DP * 0.5f * density).toInt()))
        }
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // restore the persisted position (margin-based rest position) as a
        // PENDING value — onAttachedToWindow applies it after addView
        if (prefs.contains("$key.x") && prefs.contains("$key.y")) {
            savedRest = intArrayOf(prefs.getInt("$key.x", 0), prefs.getInt("$key.y", 0))
        }

        // panels start hidden until their toolbar button toggles them;
        // the dock starts visible (it IS the toolbar)
        visibility = if (kind == Kind.PANEL) GONE else VISIBLE
    }

    /** Hosts a panel view inside the shell (below the handle). */
    fun setContent(view: View) {
        content.removeAllViews()
        content.addView(view, view.layoutParams ?: LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        content.minimumWidth = if (kind == Kind.DOCK) 0 else (CONTENT_MIN_DP * density).toInt()
    }

    /**
     * The shell wraps its CONTENT only. The backdrop + scrim are
     * MATCH_PARENT fill children — measuring them inside a WRAP_CONTENT
     * parent would expand the shell to the whole screen (a MATCH_PARENT
     * child under an AT_MOST spec measures to the full available size —
     * found on-device as a 2260x1260 scrim). So this measure pass covers
     * ONLY the handle + content; the fill children get EXACTLY the
     * resolved shell size afterwards, and the default FrameLayout layout
     * positions them edge-to-edge inside it.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val handleH = if (handle.visibility == GONE) 0 else
            ((if (kind == Kind.DOCK) HANDLE_DP * 0.5f else HANDLE_DP.toFloat()) * density).toInt()
        measureChildWithMargins(content, widthMeasureSpec, 0, heightMeasureSpec, handleH)
        val clp = content.layoutParams as MarginLayoutParams
        val contentW = content.measuredWidth + clp.leftMargin + clp.rightMargin
        val contentH = content.measuredHeight + clp.topMargin + clp.bottomMargin
        var w = contentW
        var h = contentH
        if (handle.visibility != GONE && handle.parent != null) {
            val hlp = handle.layoutParams as MarginLayoutParams
            // the handle spans the full shell width (its drag surface)
            handle.measure(
                MeasureSpec.makeMeasureSpec(
                    contentW - hlp.leftMargin - hlp.rightMargin, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(handleH, MeasureSpec.EXACTLY))
            w = maxOf(w, handle.measuredWidth + hlp.leftMargin + hlp.rightMargin)
            h += handle.measuredHeight + hlp.topMargin + hlp.bottomMargin
        }
        w += paddingLeft + paddingRight
        h += paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec),
        )
        // fill children: exactly the resolved shell size
        val fillW = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val fillH = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        backdrop.measure(fillW, fillH)
        scrim.measure(fillW, fillH)
    }

    private var lastParentWidth=0
    private var lastParentHeight=0
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // FrameLayout overlays children by default. Reserve the actual handle strip.
        if (handle.visibility != GONE) content.offsetTopAndBottom(handle.measuredHeight)
        val host=parent as? View ?: return
        val resized=host.width!=lastParentWidth || host.height!=lastParentHeight
        lastParentWidth=host.width;lastParentHeight=host.height
        if (!dragging && !autoHidden && host.width>0 && host.height>0 && (resized || kind==Kind.DOCK)) {
            val safeWidth=width.coerceAtLeast((48*density).toInt())
            val safeHeight=height.coerceAtLeast(((if(kind==Kind.DOCK) 96 else 64)*density).toInt())
            val x=left.coerceIn(0,(host.width-safeWidth).coerceAtLeast(0))
            val y=top.coerceIn(0,(host.height-safeHeight).coerceAtLeast(0))
            if(x!=left || y!=top) post { if(isAttachedToWindow) commitRest(x.toFloat(),y.toFloat()) }
        }
    }

    val isShowing: Boolean get() = visibility == VISIBLE

    // ----- show / hide with springs -----

    /** Spring entrance: alpha 0->1, scale 0.9->1; snapshots the backdrop. */
    fun show() {
        explicitlyDismissed = false
        if (!Springs.enabled()) {
            springs.values.toList().forEach { it.cancel() }; springs.clear()
            visibility = VISIBLE; alpha = 1f; scaleX = 1f; scaleY = 1f
            translationX = 0f; translationY = 0f; autoHidden = false
            refreshBackdrop(); return
        }
        cancel(DynamicAnimation.ALPHA)
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            translationX = 0f
            translationY = 0f
            autoHidden = false
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
        }
        spring(DynamicAnimation.ALPHA, 1f)
        spring(DynamicAnimation.SCALE_X, 1f)
        spring(DynamicAnimation.SCALE_Y, 1f)
        refreshBackdrop()
    }

    /** Spring exit; GONE once the fade settles. */
    fun dismiss() {
        explicitlyDismissed = true
        if (!Springs.enabled()) {
            springs.values.toList().forEach { it.cancel() }; springs.clear()
            visibility = GONE; autoHidden = false; return
        }
        autoHidden = false
        if (visibility == GONE) return
        spring(DynamicAnimation.SCALE_X, 0.92f)
        spring(DynamicAnimation.SCALE_Y, 0.92f)
        val anim = spring(DynamicAnimation.ALPHA, 0f)
        anim.addEndListener { _, canceled, _, _ ->
            if (!canceled && alpha < 0.05f) {
                visibility = GONE
                translationX = 0f
                translationY = 0f
            }
        }
    }

    /** Pen contact closes immediately and cannot be undone by the idle restore timer. */
    fun dismissImmediately() {
        explicitlyDismissed = true
        autoHidden = false
        springs.values.toList().forEach { it.cancel() }; springs.clear()
        visibility = GONE
        translationX = 0f; translationY = 0f
    }

    /** True while the auto-hide pipeline has this shell faded out. */
    val fadedForAutoHide: Boolean get() = autoHidden

    /**
     * Auto-hide step: spring-slide toward the nearest screen edge
     * (PanelGeometry.hideVector, ~80% out) and fade — panels to 0.15
     * (the faded sliver stays touchable), the dock to 0.0 + INVISIBLE so
     * pen strokes pass through to the canvas over the dock's area.
     */
    fun setAutoHidden(hidden: Boolean) {
        if (explicitlyDismissed) return
        if (hidden == autoHidden) return
        autoHidden = hidden
        if (hidden) {
            if (visibility != VISIBLE) return
            val p = parent as? View
            val vec = PanelGeometry.hideVector(
                left + translationX, top + translationY,
                width.toFloat(), height.toFloat(),
                (p?.width ?: 0).toFloat(), (p?.height ?: 0).toFloat(),
            )
            spring(DynamicAnimation.TRANSLATION_X, vec[0])
            spring(DynamicAnimation.TRANSLATION_Y, vec[1])
            if (kind == Kind.DOCK) {
                val anim = spring(DynamicAnimation.ALPHA, 0f)
                if (!Springs.enabled()) visibility = INVISIBLE
                anim.addEndListener { _, canceled, _, _ ->
                    if (!canceled && autoHidden) visibility = INVISIBLE
                }
            } else {
                spring(DynamicAnimation.ALPHA, 0.15f)
            }
        } else {
            if (kind == Kind.DOCK) {
                visibility = VISIBLE
            }
            if (!dragging) {
                spring(DynamicAnimation.TRANSLATION_X, 0f)
                spring(DynamicAnimation.TRANSLATION_Y, 0f)
            }
            spring(DynamicAnimation.ALPHA, 1f)
        }
    }

    // ----- backdrop (one-shot snapshot; the blur tiering lives here) -----

    /**
     * Renders the canvas region behind the panel into the backdrop
     * ImageView. Called on SHOW and DRAG END ONLY — never per frame, so
     * pan/zoom frame cost is untouched (the perf story in
     * docs/UI_OVERHAUL.md). API 26-30: scrim-only glass, no snapshot.
     */
    fun refreshBackdrop() {
        if (Build.VERSION.SDK_INT < 31) {
            backdrop.visibility = GONE
            return
        }
        backdropSnapshotCount++
        val bmp = snapshotProvider?.invoke(this)
        if (bmp == null) {
            backdrop.visibility = GONE
            return
        }
        backdrop.setImageBitmap(bmp)
        backdrop.visibility = VISIBLE
        GlassStyle.applyBackdropBlur(backdrop, blurRadiusPx)
    }

    // ----- dragging (consumed by the dedicated handle bar) -----

    /**
     * Begins a drag gesture (the handle's ACTION_DOWN). Public because the
     * JVM/Robolectric drag tests drive the exact same entry point the
     * handle does.
     */
    fun beginDrag(rawX: Float, rawY: Float) {
        dragging = true
        lastRawX = rawX
        lastRawY = rawY
        // a hand on the panel cancels any in-flight spring
        cancel(DynamicAnimation.TRANSLATION_X)
        cancel(DynamicAnimation.TRANSLATION_Y)
        cancel(DynamicAnimation.SCALE_X)
        cancel(DynamicAnimation.SCALE_Y)
    }

    /**
     * Live drag step (translation only — no layout, no canvas work).
     * Exposed for the JVM/Robolectric drag-math tests, which drive it
     * directly the same way the handle does.
     */
    fun dragBy(dx: Float, dy: Float) {
        translationX += dx
        translationY += dy
    }

    /** Ends a drag: clamps to the reachable area, commits the rest
     *  position into margins, persists it, and refreshes the backdrop. */
    fun endDrag() {
        if (!dragging) return
        dragging = false
        val p = parent as? View
        val clamped = PanelGeometry.clampOffset(
            left + translationX, top + translationY,
            p?.width ?: 0, p?.height ?: 0, width, height,
        )
        commitRest(clamped[0], clamped[1])
        if (autoHidden) {
            autoHidden = false
            spring(DynamicAnimation.ALPHA, 1f)
        }
        refreshBackdrop()
    }

    private fun commitRest(x: Float, y: Float) {
        applyRest(x, y)
        prefs.edit()
            .putInt("$key.x", x.toInt())
            .putInt("$key.y", y.toInt())
            .apply()
    }

    /** Commits a rest position into margins (TOP|START), clearing any
     *  live drag translation. Does NOT touch persistence. */
    private fun applyRest(x: Float, y: Float) {
        translationX = 0f
        translationY = 0f
        val lp = (layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
        lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = x.toInt()
        lp.topMargin = y.toInt()
        layoutParams = lp
        requestLayout()
        restFrozen = true
    }

    /** First attach/layout: apply the persisted rest position (addView's
     *  gravity LP clobbers anything from init), freeze the gravity-provided
     *  default into margins, and clamp saved positions into this screen. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            val saved = savedRest
            when {
                saved != null -> {
                    // Clamp saved position to current screen so docks don't
                    // disappear after rotation/orientation change.
                    val p = parent as? View
                    if (p != null && width > 0 && height > 0) {
                        val clamped = PanelGeometry.clampOffset(
                            saved[0].toFloat(), saved[1].toFloat(),
                            p.width, p.height, width, height
                        )
                        applyRest(clamped[0], clamped[1])
                    } else {
                        applyRest(saved[0].toFloat(), saved[1].toFloat())
                    }
                    savedRest = null
                }
                !restFrozen && width > 0 -> {
                    commitRest(left.toFloat(), top.toFloat())
                }
                restFrozen && parent is View -> {
                    val p = parent as View
                    val clamped = PanelGeometry.clampOffset(
                        left.toFloat(), top.toFloat(), p.width, p.height, width, height)
                    if (clamped[0] != left.toFloat() || clamped[1] != top.toFloat()) {
                        commitRest(clamped[0], clamped[1])
                    }
                }
            }
        }
    }

    // A touch anywhere inside the shell can restore the auto-hidden
    // overlay (the handle drag + every control still work normally).
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            onPanelTouched?.invoke()
        }
        return super.dispatchTouchEvent(ev)
    }

    // ----- spring plumbing -----

    private fun force(target: Float) =
        SpringForce(target).setStiffness(Springs.STIFFNESS).setDampingRatio(Springs.DAMPING)

    private fun spring(
        property: DynamicAnimation.ViewProperty,
        target: Float,
    ): SpringAnimation {
        cancel(property)
        val anim = SpringAnimation(this, property).apply {
            spring = force(target)
            if (Springs.enabled()) start() else property.setValue(this@FloatingPanel, target)
        }
        springs[property] = anim
        return anim
    }

    private fun cancel(property: DynamicAnimation.ViewProperty) {
        springs.remove(property)?.cancel()
    }

    /** The dedicated top handle bar: a thin grip that eats drag gestures. */
    private inner class PanelHandle(context: Context) : LinearLayout(context) {
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            contentDescription = "drag handle"
            val grip = View(context).apply {
                background = if (light)
                    GradientDrawable().apply {
                        setColor(0x421E1E1E.toInt())
                        cornerRadius = 1.5f * density
                    }
                else GlassStyle.gripBackground(density)
            }
            addView(grip, LayoutParams((GRIP_W_DP * density).toInt(), (GRIP_H_DP * density).toInt())
                .apply { gravity = Gravity.CENTER_VERTICAL })
            val pad = (density * 6).toInt()
            setPadding(0, pad, 0, pad)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginDrag(event.rawX, event.rawY)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    dragBy(event.rawX - lastRawX, event.rawY - lastRawY)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endDrag()
                    performClick()
                    return true
                }
            }
            return false
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    companion object {
        private const val PREFS_NAME = "zoomart_panels"
        private const val HANDLE_DP = 26
        private const val GRIP_W_DP = 40
        private const val GRIP_H_DP = 4
        private const val CONTENT_MIN_DP = 250
    }
}
