package com.example.zoomart

import android.app.Activity
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.example.zoomart.model.*
import com.example.zoomart.ui.BrushPanel
import com.example.zoomart.ui.glass.Springs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrushAtelierTest {
    private fun all(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    @Test fun personalBrushSurvivesRecreationAndKeepsFineSettings() {
        val controller=Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        val activity=controller.setup().get()
        var emitted=BrushSpec()
        val source=BrushPresets.SPRAY.copy(minWidthRatio=0.67f, angleDeg=43f, pressureOpacity=true)
        val panel=BrushPanel(activity,{emitted=it})
        activity.setContentView(panel); panel.syncFrom(source)
        all(panel).filterIsInstance<Button>().first { it.text == "Save brush" }.performClick()
        val dialog=ShadowDialog.getLatestDialog() as AlertDialog
        all(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("My copper mist")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val restored=BrushPanel(activity,{emitted=it})
        all(restored).first { it.contentDescription == "My copper mist" }.performClick()
        assertEquals(source.copy(color=BrushSpec().color),emitted)
        controller.pause().stop().destroy()
    }

    @Test fun presetKeepsArtistColorAndOpacityEditKeepsNibFloor() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        var emitted=BrushSpec()
        val panel=BrushPanel(activity,{emitted=it})
        panel.syncFrom(BrushSpec(color=0xFF008877.toInt()))
        all(panel).first { it.contentDescription == "Marker" }.performClick()
        assertEquals(0xFF008877.toInt(),emitted.color)
        val opacity=all(panel).filterIsInstance<SeekBar>().first { it.contentDescription == "Opacity" }
        opacity.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(BrushPresets.MARKER.minWidthRatio,emitted.minWidthRatio,0f)
        assertTrue(emitted.opacity < BrushPresets.MARKER.opacity)
    }
}
