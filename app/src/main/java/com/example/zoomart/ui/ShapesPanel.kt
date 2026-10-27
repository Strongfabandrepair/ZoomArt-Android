package com.example.zoomart.ui

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.example.zoomart.model.ShapeType
import com.example.zoomart.ui.glass.GlassStyle
import com.example.zoomart.ui.glass.Springs

/**
 * P3.1 Shapes panel: shape-type row (8 parametric types) + Fill toggle +
 * fill/outline color wells (reusing the HSV [ColorPickerDialog]) + width /
 * roundness / points sliders + the Constrain / From-Center toggles.
 *
 * Every control writes straight into the DrawingViewModel's shape state;
 * arming a type also switches the canvas to the SHAPE tool, so the next
 * stylus drag draws that shape (fingers keep panning per CORE#1).
 * All buttons carry contentDescriptions for uiautomator.
 */
class ShapesPanel(
    context: Context,
    private val vm: com.example.zoomart.model.DrawingViewModel,
    private val onArmed: () -> Unit,
) : LinearLayout(context) {

    private val typeButtons = LinkedHashMap<ShapeType, Button>()
    private lateinit var fillToggle: Button
    private lateinit var constrainToggle: Button
    private lateinit var centerToggle: Button
    private lateinit var widthBar: SeekBar
    private lateinit var roundBar: SeekBar
    private lateinit var pointsBar: SeekBar

    init {
        orientation = VERTICAL
        // U-overhaul: transparent glass body — the shell provides the scrim
        setBackgroundColor(0x00000000)
        val pad = (resources.displayMetrics.density * 8).toInt()
        setPadding(pad, pad, pad, pad)

        // ---- shape type row (arms the SHAPE tool) ----
        val row1 = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (type in ShapeType.entries) {
            val b = Button(context).apply {
                text = labelOf(type)
                textSize = 10f
                contentDescription = "shape " + type.name.lowercase()
                GlassStyle.button(this)
                setOnClickListener {
                    vm.armedShapeType = type
                    syncToggles()
                    onArmed()
                    Springs.pulse(this)
                }
            }
            Springs.attachPressFeedback(b)
            typeButtons[type] = b
            val lp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(0, 0, pad / 2, 0)
            row1.addView(b, lp)
        }
        addView(row1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---- fill toggle + color wells ----
        val row2 = LinearLayout(context).apply { orientation = HORIZONTAL }
        fillToggle = Button(context).apply {
            textSize = 11f
            contentDescription = "shape fill toggle"
            GlassStyle.button(this)
            setOnClickListener {
                vm.shapeFillOn = !vm.shapeFillOn
                syncToggles()
                onArmed()
                Springs.pulse(this)
            }
        }
        Springs.attachPressFeedback(fillToggle)
        row2.addView(fillToggle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.8f))
        val fillWell = Button(context).apply {
            textSize = 10f
            contentDescription = "shape fill color"
            GlassStyle.button(this)
            setOnClickListener {
                ColorPickerDialog(context, vm.shapeFillColor) { c ->
                    vm.shapeFillColor = c
                    setBackgroundColor(c)
                    onArmed()
                }.show()
            }
        }
        Springs.attachPressFeedback(fillWell)
        val outlineWell = Button(context).apply {
            textSize = 10f
            contentDescription = "shape outline color"
            GlassStyle.button(this)
            setOnClickListener {
                ColorPickerDialog(context, vm.shapeOutlineColor) { c ->
                    vm.shapeOutlineColor = c
                    setBackgroundColor(c)
                    onArmed()
                }.show()
            }
        }
        Springs.attachPressFeedback(outlineWell)
        row2.addView(fillWell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.6f))
        row2.addView(outlineWell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.6f))
        addView(row2, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---- constrain / from-center toggles ----
        val row3 = LinearLayout(context).apply { orientation = HORIZONTAL }
        constrainToggle = Button(context).apply {
            textSize = 11f
            contentDescription = "shape constrain toggle"
            GlassStyle.button(this)
            setOnClickListener {
                vm.shapeConstrain = !vm.shapeConstrain
                syncToggles()
                onArmed()
                Springs.pulse(this)
            }
        }
        Springs.attachPressFeedback(constrainToggle)
        centerToggle = Button(context).apply {
            textSize = 11f
            contentDescription = "shape from center toggle"
            GlassStyle.button(this)
            setOnClickListener {
                vm.shapeFromCenter = !vm.shapeFromCenter
                syncToggles()
                onArmed()
                Springs.pulse(this)
            }
        }
        Springs.attachPressFeedback(centerToggle)
        row3.addView(constrainToggle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row3.addView(centerToggle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(row3, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---- width / roundness / points sliders ----
        widthBar = slider("Width") { vm.shapeOutlineWidthScreen = 1f + it * 0.59f } // 1..60 px
        roundBar = slider("Round") { vm.shapeRoundness = it / 100f }
        pointsBar = slider("Points") { vm.shapePointCount = 3 + it * 9 / 100 } // 3..12

        syncToggles()
    }

    private fun labelOf(type: ShapeType): String = when (type) {
        ShapeType.LINE -> "Line"
        ShapeType.RECTANGLE -> "Rect"
        ShapeType.ROUNDED_RECT -> "RRect"
        ShapeType.ELLIPSE -> "Oval"
        ShapeType.TRIANGLE -> "Tri"
        ShapeType.POLYGON -> "Poly"
        ShapeType.STAR -> "Star"
        ShapeType.ARROW -> "Arrow"
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
                        onArmed()
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

    /** Highlights the armed type; refreshes toggle labels/color wells. */
    fun syncToggles() {
        for ((type, b) in typeButtons) {
            b.alpha = if (type == vm.armedShapeType) 1f else 0.45f
        }
        fillToggle.text = if (vm.shapeFillOn) "Fill ✓" else "Fill ✗"
        constrainToggle.text = if (vm.shapeConstrain) "Constrain ✓" else "Constrain ✗"
        centerToggle.text = if (vm.shapeFromCenter) "Center ✓" else "Center ✗"
        for ((type, b) in typeButtons) {
            b.setBackgroundColor(if (type == vm.armedShapeType) 0x1A2E66F6 else 0x00000000)
        }
        widthBar.progress = ((vm.shapeOutlineWidthScreen - 1f) * 100f / 59f).toInt().coerceIn(0, 100)
        roundBar.progress = (vm.shapeRoundness * 100f).toInt().coerceIn(0, 100)
        pointsBar.progress = ((vm.shapePointCount - 3) * 100f / 9f).toInt().coerceIn(0, 100)
    }
}
