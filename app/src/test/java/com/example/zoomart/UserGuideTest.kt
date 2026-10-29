package com.example.zoomart

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class UserGuideTest {
    private fun descendants(v: View): List<View> = listOf(v) +
        if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    @Test fun welcomeGuideOpensPackagedInstructionsWithoutStartingDrawing() {
        val title=Robolectric.buildActivity(TitleActivity::class.java).setup()
        try {
            val button=descendants(title.get().window.decorView).filterIsInstance<Button>()
                .single { it.text.toString()=="User Guide" }
            assertTrue(button.isEnabled);button.performClick()
            assertEquals(UserGuideActivity::class.java.name,
                Shadows.shadowOf(title.get()).nextStartedActivity.component!!.className)
            val guide=Robolectric.buildActivity(UserGuideActivity::class.java).setup()
            try {
                val labels=descendants(guide.get().window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(labels.contains("Your first drawing"))
                assertTrue(labels.contains("Save, reopen and back up"))
                assertTrue(labels.contains("Settings, motion and troubleshooting"))
                assertTrue(labels.any { it.contains("This guide works offline") })
                assertFalse(labels.any { it.contains("**") })
            } finally { guide.pause().stop().destroy() }
        } finally { title.pause().stop().destroy() }
    }
}
