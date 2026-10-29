package com.example.zoomart

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.example.zoomart.ui.StudioBackdrop
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class StudioBackdropTest {
    @Test fun decorativeMotionStopsWhenPausedHiddenOrDetached() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity=controller.get()
        val host=FrameLayout(activity)
        val view=StudioBackdrop(activity)
        activity.setContentView(host);host.addView(view,FrameLayout.LayoutParams(400,400))
        // Robolectric leaves the attached window GONE until a simulated WM visibility event.
        val attachInfo=org.robolectric.util.ReflectionHelpers.getField<Any>(view,"mAttachInfo")
        org.robolectric.util.ReflectionHelpers.setField(attachInfo,"mWindowVisibility",View.VISIBLE)
        view.dispatchWindowVisibilityChanged(View.VISIBLE)
        controller.windowFocusChanged(true)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue("Motion: attached=${view.isAttachedToWindow}, shown=${view.isShown}, focus=${view.hasWindowFocus()}, enabled=${android.animation.ValueAnimator.areAnimatorsEnabled()}, window=${view.windowVisibility}",view.motionFrames>0)
        view.setMotionPaused(true)
        val count=view.motionFrames
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(count,view.motionFrames);assertFalse(view.motionScheduled)
        view.setMotionPaused(false)
        host.visibility=View.GONE
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertFalse(view.motionScheduled)
        host.removeView(view)
        assertFalse(view.motionScheduled)
        assertFalse(view.isClickable);assertFalse(view.isFocusable)
        controller.pause().stop().destroy()
    }
}
