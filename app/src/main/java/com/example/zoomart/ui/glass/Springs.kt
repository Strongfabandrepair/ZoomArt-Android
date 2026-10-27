package com.example.zoomart.ui.glass

import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * U1/U3 (UI overhaul): the shared spring-physics micro-animation helpers
 * (androidx.dynamicanimation). One stiffness/damping pair everywhere:
 * STIFFNESS_MEDIUM + damping ~0.7 = snappy but visibly settling, the
 * Procreate-like feel. Panel animations only ever touch their own view
 * subtree (decoupling rule) — none of these invalidate the canvas.
 */
object Springs {

    const val STIFFNESS = SpringForce.STIFFNESS_MEDIUM
    const val DAMPING = 0.7f

    private fun force(target: Float): SpringForce =
        SpringForce(target).setStiffness(STIFFNESS).setDampingRatio(DAMPING)

    private fun anim(view: View, property: DynamicAnimation.ViewProperty, target: Float) =
        SpringAnimation(view, property).apply { spring = force(target) }

    fun springAlpha(view: View, target: Float) { anim(view, DynamicAnimation.ALPHA, target).start() }

    fun springScale(view: View, target: Float) {
        anim(view, DynamicAnimation.SCALE_X, target).start()
        anim(view, DynamicAnimation.SCALE_Y, target).start()
    }

    fun springTranslationX(view: View, target: Float) {
        anim(view, DynamicAnimation.TRANSLATION_X, target).start()
    }

    fun springTranslationY(view: View, target: Float) {
        anim(view, DynamicAnimation.TRANSLATION_Y, target).start()
    }

    /**
     * Panel show entrance: alpha 0 -> 1, scale 0.9 -> 1 and a small upward
     * settle. Safe to call repeatedly (springs retarget).
     */
    fun showPop(view: View, settleY: Float = 0f) {
        view.alpha = 0f
        view.scaleX = 0.9f
        view.scaleY = 0.9f
        springAlpha(view, 1f)
        springScale(view, 1f)
        springTranslationY(view, settleY)
    }

    /**
     * Tool-selection pulse: a quick scale kick that springs back to 1.
     */
    fun pulse(view: View) {
        view.scaleX = 0.85f
        view.scaleY = 0.85f
        springScale(view, 1f)
    }

    /** U3: animated collapse — fades [view] out and GONEs it at rest. */
    fun fadeThenGone(view: View) {
        val anim = SpringAnimation(view, DynamicAnimation.ALPHA, 0f).apply {
            spring = force(0f)
        }
        anim.addEndListener { _, canceled, _, _ ->
            if (!canceled) {
                view.visibility = View.GONE
                view.alpha = 1f
            }
        }
        anim.start()
    }

    /** U3: animated expand — VISIBLE + fade in. */
    fun fadeBackIn(view: View) {
        view.visibility = View.VISIBLE
        view.alpha = 0f
        SpringAnimation(view, DynamicAnimation.ALPHA, 1f).apply {
            spring = force(1f)
            start()
        }
    }

    private val pressListener = View.OnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> springScale(view, 0.92f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> springScale(view, 1f)
        }
        false
    }

    /**
     * Attaches spring press feedback to [view]. The listener never consumes
     * (always returns false), so the view's own click/touch handling runs
     * exactly as before — the animation composes with, never replaces it.
     * (View has no OnTouchListener getter, so this REPLACES any listener;
     * no ZoomArt view installs one — panels use setOnClickListener and the
     * drag handle overrides onTouchEvent.)
     */
    fun attachPressFeedback(view: View) {
        view.setOnTouchListener(pressListener)
    }
}
