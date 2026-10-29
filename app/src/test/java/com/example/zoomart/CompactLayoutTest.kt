package com.example.zoomart

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import com.example.zoomart.model.SessionWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CompactLayoutTest {
    private fun children(view: View): List<View> = listOf(view) +
        if(view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    private fun checkCanvas(width: Int,height: Int) {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity=controller.get()
            val root=activity.findViewById<View>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            root.layout(0,0,width,height)
            assertEquals(width,activity.canvasView.width)
            assertTrue(activity.canvasView.height>0)
            val buttons=children(root).filterIsInstance<ImageButton>().filter { it.isShown }
            assertTrue(buttons.isNotEmpty())
            val minimum=(48*activity.resources.displayMetrics.density).toInt()
            buttons.forEach { assertFalse(it.contentDescription.isNullOrBlank());assertTrue(it.height>=minimum) }
        } finally { controller.pause().stop().destroy();SessionWriter.awaitIdle() }
    }
    @Test @Config(qualifiers="w360dp-h640dp-mdpi") fun phonePortrait() = checkCanvas(360,640)
    @Test @Config(qualifiers="w640dp-h360dp-land-mdpi") fun shortLandscape() = checkCanvas(640,360)
    @Test @Config(qualifiers="w320dp-h480dp-mdpi") fun narrowWindow() = checkCanvas(320,480)
    @Test @Config(qualifiers="ar-rEG-ldrtl-w360dp-h640dp-mdpi") fun rightToLeftWindow() = checkCanvas(360,640)
}
