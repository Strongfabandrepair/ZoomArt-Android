package com.example.zoomart.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.example.zoomart.model.BrushPresets
import com.example.zoomart.model.BrushSpec
import com.example.zoomart.ui.glass.GlassStyle
import com.example.zoomart.ui.glass.Springs

/**
 * Bottom sheet with brush presets and live parameter sliders. Emits a fresh
 * BrushSpec on every change; the activity applies it to the view model.
 * (Tweaks start from slider values; a preset's minWidthRatio is not exposed.)
 */
class BrushPanel(
    context: Context,
    private val onBrushChanged: (BrushSpec) -> Unit,
    private val onEraserRadius: (Float) -> Unit = {},
    private val onLazyChanged: (Boolean, Float) -> Unit = { _, _ -> },
) : LinearLayout(context) {

    private val presetButtons = mutableListOf<Button>()
    private lateinit var sizeBar: SeekBar
    private lateinit var responseBar: SeekBar
    private lateinit var opacityBar: SeekBar
    private lateinit var eraseBar: SeekBar
    private lateinit var lazyToggle: Button
    private lateinit var lazyRadiusBar: SeekBar
    /** v0.7.0: live preview strip showing the current brush stroke. */
    private lateinit var previewStrip: PreviewStrip

    private var widthWorld = 6f
    private var response = 2f
    private var opacity = 1f
    private var color: Int = 0xFF16181D.toInt()
    private var pressureOpacity = false // P1.2: preserved from presets across slider tweaks
    private var minOpacity = BrushSpec.DEFAULT_MIN_OPACITY
    private var lazyOn = false            // P3.2: stabilizer toggle
    private var lazyRadius = 60f          // P3.2: rope radius, screen px 0..200

    init {
        orientation = VERTICAL
        // U-overhaul: the panel itself is transparent glass — the
        // FloatingPanel shell behind it provides the dark scrim.
        setBackgroundColor(0x00000000)
        val pad = (resources.displayMetrics.density * 8).toInt()
        setPadding(pad, pad, pad, pad)

        val presetsRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        BrushPresets.all.forEachIndexed { i, (name, spec) ->
            val b = Button(context).apply {
                text = name
                textSize = 11f
                GlassStyle.button(this)
                setOnClickListener {
                    widthWorld = spec.baseWidthWorld
                    response = spec.pressureResponse
                    opacity = spec.opacity
                    color = spec.color
                    pressureOpacity = spec.pressureOpacity
                    minOpacity = spec.minOpacity
                    syncBars()
                    highlightPreset(i)
                    emit()
                    Springs.pulse(this) // U3: tool-selection pulse
                }
            }
            Springs.attachPressFeedback(b)
            presetButtons.add(b)
            val lp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(0, 0, pad, 0)
            presetsRow.addView(b, lp)
        }
        addView(presetsRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // v0.7.0: live preview strip — shows the current brush stroke sample
        // so the user sees the effect without drawing on the canvas.
        val previewLabel = TextView(context).apply {
            text = "Preview"
            setTextColor(GlassStyle.TEXT_SECONDARY)
            textSize = 10f
        }
        addView(previewLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        previewStrip = PreviewStrip(context)
        addView(previewStrip, LayoutParams(LayoutParams.MATCH_PARENT, (resources.displayMetrics.density * 34).toInt()))

        sizeBar = slider("Size") { widthWorld = 1f + it * 49f / 100f }
        responseBar = slider("Pressure") { response = it / 25f }
        opacityBar = slider("Opacity") { opacity = it / 100f }
        eraseBar = slider("Erase size") { onEraserRadius(5f + it * 1.45f) }

        // P3.2: lazy brush controls — ON/OFF toggle + rope radius 0..200 px
        val lazyRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lazyToggle = Button(context).apply {
            textSize = 11f
            contentDescription = "lazy toggle"
            GlassStyle.button(this)
            setOnClickListener {
                lazyOn = !lazyOn
                syncLazy()
                onLazyChanged(lazyOn, lazyRadius)
                Springs.pulse(this)
            }
        }
        Springs.attachPressFeedback(lazyToggle)
        lazyRow.addView(lazyToggle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.35f))
        val radiusLabel = TextView(context).apply {
            text = "Lazy px"
            setTextColor(GlassStyle.TEXT_PRIMARY)
        }
        lazyRow.addView(radiusLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.2f))
        lazyRadiusBar = SeekBar(context).apply {
            max = 200
            progress = 60
            contentDescription = "lazy radius slider"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        lazyRadius = progress.toFloat()
                        onLazyChanged(lazyOn, lazyRadius)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { }
                override fun onStopTrackingTouch(sb: SeekBar?) { }
            })
        }
        lazyRow.addView(lazyRadiusBar, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.45f))
        addView(lazyRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        syncBars()
        syncLazy()
        highlightPreset(-1)
        eraseBar.progress = 24 // ~40 world units default
    }

    private fun syncLazy() {
        lazyToggle.text = if (lazyOn) "Lazy ✓" else "Lazy ✗"
        lazyToggle.alpha = if (lazyOn) 1f else 0.6f
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
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        onChange(progress)
                        emit()
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

    private fun syncBars() {
        sizeBar.progress = ((widthWorld - 1f) * 100f / 49f).toInt().coerceIn(0, 100)
        responseBar.progress = (response * 25f).toInt().coerceIn(0, 100)
        opacityBar.progress = (opacity * 100f).toInt().coerceIn(0, 100)
    }

    private fun highlightPreset(index: Int) {
        presetButtons.forEachIndexed { i, b -> b.alpha = if (i == index) 1f else 0.45f }
    }

    fun syncFrom(spec: BrushSpec) {
        widthWorld = spec.baseWidthWorld
        response = spec.pressureResponse
        opacity = spec.opacity
        color = spec.color
        pressureOpacity = spec.pressureOpacity
        minOpacity = spec.minOpacity
        syncBars()
        previewStrip.setSpec(widthWorld, response, opacity, color, pressureOpacity, minOpacity)
    }

    private fun emit() {
        previewStrip.setSpec(widthWorld, response, opacity, color, pressureOpacity, minOpacity)
        onBrushChanged(
            BrushSpec(
                baseWidthWorld = widthWorld,
                pressureResponse = response,
                minWidthRatio = 0.15f,
                color = color,
                opacity = opacity,
                pressureOpacity = pressureOpacity,
                minOpacity = minOpacity,
            )
        )
    }

}

/**
 * v0.7.0: live brush preview — draws a sample stroke using the current
 * BrushSpec so the user sees size/opacity/pressure before touching canvas.
 */
class PreviewStrip(context: Context) : View(context) {

    private val strokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var spec: BrushSpec = BrushSpec()

    /** Called by BrushPanel.emit() with the latest spec. */
    fun setSpec(baseWidthWorld: Float, pressureResponse: Float, opacity: Float,
                color: Int, pressureOpacity: Boolean, minOpacity: Float) {
        spec = BrushSpec(
            baseWidthWorld = baseWidthWorld,
            pressureResponse = pressureResponse,
            minWidthRatio = 0.15f,
            color = color,
            opacity = opacity,
            pressureOpacity = pressureOpacity,
            minOpacity = minOpacity,
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density

        // Background swatch
        canvas.drawColor(0xFF2A2E37.toInt())

        // Draw a sample stroke: a gentle S-curve across the strip
        val cx = w * 0.5f
        val cy = h * 0.5f
        val amp = h * 0.35f
        val path = android.graphics.Path()
        path.moveTo(w * 0.05f, cy)
        path.cubicTo(
            w * 0.25f, cy - amp,
            w * 0.75f, cy + amp,
            w * 0.95f, cy
        )

        // Width: screen-relative at 1x (preview is at screen scale)
        val widthPx = spec.baseWidthWorld * density
        strokePaint.strokeWidth = widthPx.coerceIn(2f, 40f)

        // Color + opacity
        val alpha = (spec.opacity * 255).toInt()
        strokePaint.color = (spec.color and 0xFFFFFF) or (alpha shl 24)

        // Pressure variation: draw with several widths to show response
        if (spec.pressureResponse > 0f) {
            // faded lighter stroke = low pressure
            val lowAlpha = (spec.minOpacity * 255).toInt()
            strokePaint.alpha = lowAlpha
            strokePaint.strokeWidth = (widthPx * 0.4f).coerceIn(1f, 20f)
            canvas.drawPath(path, strokePaint)
        }
        // main stroke = full pressure
        strokePaint.alpha = alpha
        strokePaint.strokeWidth = widthPx.coerceIn(2f, 40f)
        canvas.drawPath(path, strokePaint)
    }
}
