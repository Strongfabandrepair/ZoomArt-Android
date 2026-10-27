package com.example.zoomart.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.example.zoomart.model.DrawingViewModel
import com.example.zoomart.ui.glass.GlassStyle

/**
 * P3.3 Blend/smudge panel: the Strength / Size / Softness sliders that
 * drive the smudge gesture (strength also scales with pen pressure at
 * deposit time; size is SCREEN-relative and converts to level-local units
 * per commit; softness shapes the stamp's radial alpha falloff at render).
 * Opening the panel arms the SMUDGE tool (see MainActivity).
 *
 * U-overhaul: dark-glass restyle — transparent body (the FloatingPanel
 * shell supplies the scrim), light text.
 */
class BlendPanel(
    context: Context,
    private val vm: DrawingViewModel,
    private val onChanged: () -> Unit = {},
) : LinearLayout(context) {

    private lateinit var strengthBar: SeekBar
    private lateinit var sizeBar: SeekBar
    private lateinit var softnessBar: SeekBar

    init {
        orientation = VERTICAL
        // U-overhaul: transparent glass body — the shell provides the scrim
        setBackgroundColor(0x00000000)
        val pad = (resources.displayMetrics.density * 8).toInt()
        setPadding(pad, pad, pad, pad)

        addView(TextView(context).apply {
            text = "Blend / Smudge"
            setTextColor(GlassStyle.TEXT_PRIMARY)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // strength 0..100 -> 0..1
        strengthBar = slider("Strength") { vm.smudgeStrength = it / 100f }
        // size 5..150 screen px
        sizeBar = slider("Size") { vm.smudgeSizeScreen = 5f + it * 1.45f }
        // softness 0..100 -> 0..1
        softnessBar = slider("Softness") { vm.smudgeSoftness = it / 100f }

        syncFromVm()
    }

    private fun slider(label: String, onChange: (Int) -> Unit): SeekBar {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply {
            text = label
            setTextColor(GlassStyle.TEXT_PRIMARY)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.3f))
        val bar = SeekBar(context).apply {
            max = 100
            contentDescription = label.lowercase() + " slider"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        onChange(progress)
                        onChanged()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { }
                override fun onStopTrackingTouch(sb: SeekBar?) { }
            })
        }
        row.addView(bar, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.7f))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return bar
    }

    fun syncFromVm() {
        strengthBar.progress = (vm.smudgeStrength * 100f).toInt().coerceIn(0, 100)
        sizeBar.progress = ((vm.smudgeSizeScreen - 5f) / 1.45f).toInt().coerceIn(0, 100)
        softnessBar.progress = (vm.smudgeSoftness * 100f).toInt().coerceIn(0, 100)
    }
}
