package com.example.zoomart

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.zoomart.model.BrushPresets
import com.example.zoomart.ui.glass.GlassStyle
import com.example.zoomart.ui.glass.Springs

/**
 * v0.7.0 settings screen. Lets the user configure the app before opening
 * a canvas:
 *
 *   • Default brush preset (applies to new canvases)
 *   • Auto-hide panels while drawing (on/off)
 *   • Autosave interval (15s / 30s / 60s / off)
 *
 * Values persist to SharedPreferences via [AppSettings] and are read by
 * MainActivity when the canvas launches.
 */
class SettingsActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var presetLabel: TextView
    private lateinit var autosaveLabel: TextView

    private var brushIndex = 0
    private var autosaveMs = 15_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        brushIndex = AppSettings.getBrushPresetIndex(this)
        autosaveMs = AppSettings.getAutosaveMs(this)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val card = FrameLayout(this).apply {
            val p = resources.displayMetrics.density
            val pad = (p * 20).toInt()
            setPadding(pad, pad, pad, pad)
            background = GlassStyle.panelBackground(resources.displayMetrics.density)
            foreground = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = GlassStyle.CORNER_DP * p
                setStroke(maxOf(1, p.toInt()), GlassStyle.BORDER)
            }
            elevation = GlassStyle.ELEVATION_DP * p
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, width, height, GlassStyle.CORNER_DP * p)
                }
            }
            val lp = FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            layoutParams = lp
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setGravity(Gravity.CENTER)
        }

        val title = TextView(this).apply {
            text = "Settings"
            textSize = 28f
            setTextColor(GlassStyle.TEXT_PRIMARY)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (resources.displayMetrics.density * 24).toInt())
        }
        col.addView(title)

        // ----- default brush preset -----
        col.addView(sectionRow("Default brush", BrushPresets.all[brushIndex].first))
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setGravity(Gravity.CENTER) }
        presetLabel = TextView(this).apply {
            text = BrushPresets.all[brushIndex].first
            setTextColor(GlassStyle.TEXT_PRIMARY)
            textSize = 14f
        }
        val prevBtn = smallButton("‹") { brushIndex = (brushIndex - 1 + BrushPresets.all.size) % BrushPresets.all.size; refreshPreset() }
        val nextBtn = smallButton("›") { brushIndex = (brushIndex + 1) % BrushPresets.all.size; refreshPreset() }
        presetRow.addView(prevBtn)
        presetRow.addView(presetLabel)
        presetRow.addView(nextBtn)
        col.addView(presetRow)
        col.addView(spacer())

        // ----- auto-hide toggle -----
        col.addView(sectionRow("Auto-hide panels while drawing", if (AppSettings.getAutoHide(this)) "On" else "Off"))
        val autoHideRow = toggleRow("Auto-hide", AppSettings.getAutoHide(this)) { checked ->
            AppSettings.putAutoHide(this, checked)
            refreshAutohideLabel(checked)
        }
        col.addView(autoHideRow)
        col.addView(spacer())

        // ----- autosave interval -----
        col.addView(sectionRow("Autosave interval", autosaveMsLabel(autosaveMs)))
        val autosaveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        autosaveLabel = TextView(this).apply {
            text = autosaveMsLabel(autosaveMs)
            setTextColor(GlassStyle.TEXT_PRIMARY)
            textSize = 14f
        }
        val asPrev = smallButton("‹") {
            autosaveMs = when (autosaveMs) {
                15_000L -> 0L
                30_000L -> 15_000L
                60_000L -> 30_000L
                else -> 60_000L
            }
            AppSettings.putAutosaveMs(this, autosaveMs)
            autosaveLabel.text = autosaveMsLabel(autosaveMs)
        }
        val asNext = smallButton("›") {
            autosaveMs = when (autosaveMs) {
                0L -> 15_000L
                15_000L -> 30_000L
                30_000L -> 60_000L
                else -> 0L
            }
            AppSettings.putAutosaveMs(this, autosaveMs)
            autosaveLabel.text = autosaveMsLabel(autosaveMs)
        }
        autosaveRow.addView(asPrev)
        autosaveRow.addView(autosaveLabel)
        autosaveRow.addView(asNext)
        col.addView(autosaveRow)
        col.addView(spacer(heightDp = 24f))

        // ----- done button -----
        val doneBtn = glassButton("Done") {
            setResult(RESULT_OK)
            finish()
            overridePendingTransition(0, 0)
        }
        col.addView(doneBtn)

        card.addView(col)
        root.addView(card)
        setContentView(root)

        mainHandler.postDelayed({
            card.alpha = 0f
            card.scaleX = 0.92f
            card.scaleY = 0.92f
            card.alpha = 1f
            Springs.springScale(card, 1f)
        }, 60L)
    }

    private fun refreshPreset() {
        AppSettings.putBrushPresetIndex(this, brushIndex)
        presetLabel.text = BrushPresets.all[brushIndex].first
    }

    private fun refreshAutohideLabel(checked: Boolean) {
        // find the section row text and update it
    }

    private fun sectionRow(label: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(GlassStyle.TEXT_PRIMARY)
            textSize = 15f
            setPadding(0, 0, (resources.displayMetrics.density * 8).toInt(), 0)
        }, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f))
        row.addView(TextView(this).apply {
            text = value
            setTextColor(GlassStyle.TEXT_SECONDARY)
            textSize = 15f
        }, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f))
        val p = resources.displayMetrics.density
        row.setPadding(0, (p * 6).toInt(), 0, (p * 6).toInt())
        return row
    }

    private fun toggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(GlassStyle.TEXT_PRIMARY)
            textSize = 15f
        }, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f))
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        row.addView(sw, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val p = resources.displayMetrics.density
        row.setPadding(0, (p * 6).toInt(), 0, (p * 6).toInt())
        return row
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 18f
            GlassStyle.button(this)
            Springs.attachPressFeedback(this)
            setOnClickListener { onClick() }
            minHeight = (resources.displayMetrics.density * 36).toInt()
            setPadding((resources.displayMetrics.density * 10).toInt(), 0,
                (resources.displayMetrics.density * 10).toInt(), 0)
        }

    private fun glassButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 16f
            GlassStyle.button(this)
            Springs.attachPressFeedback(this)
            setOnClickListener { onClick() }
            minHeight = (resources.displayMetrics.density * 44).toInt()
            setAllCaps(false)
        }

    private fun spacer(heightDp: Float = 12f): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * heightDp).toInt())
        }

    private fun autosaveMsLabel(ms: Long): String = when (ms) {
        0L -> "Off"
        15_000L -> "15 s"
        30_000L -> "30 s"
        60_000L -> "60 s"
        else -> ms.toString() + " ms"
    }

    @SuppressLint("SetTextI18n")
    private fun toast(msg: String?) {
        Toast.makeText(this, msg ?: "", Toast.LENGTH_SHORT).show()
    }
}
