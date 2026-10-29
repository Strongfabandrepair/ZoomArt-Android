package com.example.zoomart.ui.glass

import android.animation.ValueAnimator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.example.zoomart.R

/** Interruptible, local motion. Never animates or invalidates the drawing surface. */
object Springs {
    const val STIFFNESS = SpringForce.STIFFNESS_MEDIUM
    const val DAMPING = 0.82f
    fun enabled() = ValueAnimator.areAnimatorsEnabled()
    private fun motions(view: View): MutableMap<DynamicAnimation.ViewProperty, SpringAnimation> {
        @Suppress("UNCHECKED_CAST")
        val existing = view.getTag(R.id.motion_state) as? MutableMap<DynamicAnimation.ViewProperty, SpringAnimation>
        if (existing != null) return existing
        val state = mutableMapOf<DynamicAnimation.ViewProperty, SpringAnimation>()
        view.setTag(R.id.motion_state, state)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { state.values.toList().forEach { it.cancel() }; state.clear() }
        })
        return state
    }
    private fun animate(view: View, property: DynamicAnimation.ViewProperty, target: Float,
        end: (() -> Unit)? = null) {
        val state = motions(view)
        state.remove(property)?.cancel()
        if (!enabled()) { property.setValue(view, target); end?.invoke(); return }
        val animation = SpringAnimation(view, property).apply {
            spring = SpringForce(target).setStiffness(STIFFNESS).setDampingRatio(DAMPING)
            if (property == DynamicAnimation.ALPHA) { setMinValue(0f); setMaxValue(1f) }
        }
        state[property] = animation
        animation.addEndListener { _, cancelled, _, _ ->
            if (state[property] === animation) state.remove(property)
            if (!cancelled) end?.invoke()
        }
        animation.start()
    }
    fun springAlpha(view: View, target: Float) = animate(view, DynamicAnimation.ALPHA, target)
    fun springScale(view: View, target: Float) {
        animate(view, DynamicAnimation.SCALE_X, target); animate(view, DynamicAnimation.SCALE_Y, target)
    }
    fun springTranslationX(view: View, target: Float) = animate(view, DynamicAnimation.TRANSLATION_X, target)
    fun springTranslationY(view: View, target: Float) = animate(view, DynamicAnimation.TRANSLATION_Y, target)
    fun showPop(view: View, settleY: Float = 0f) {
        view.alpha = if (enabled()) 0f else 1f
        view.scaleX = if (enabled()) 0.96f else 1f; view.scaleY = view.scaleX
        springAlpha(view, 1f); springScale(view, 1f); springTranslationY(view, settleY)
    }
    fun pulse(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (enabled()) { view.scaleX = 0.96f; view.scaleY = 0.96f }
        springScale(view, 1f)
    }
    fun tick(view: View) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun fadeThenGone(view: View) = animate(view, DynamicAnimation.ALPHA, 0f) {
        view.visibility = View.GONE; view.alpha = 1f
    }
    fun fadeBackIn(view: View) {
        view.visibility = View.VISIBLE
        if (enabled()) view.alpha = 0f
        springAlpha(view, 1f)
    }
    // Return false: View's normal event handling performs the accessible click exactly once.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun attachPressFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (v.isEnabled) springScale(v, 0.96f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> springScale(v, 1f)
            }
            false
        }
    }
}
