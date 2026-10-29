package com.example.zoomart.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.example.zoomart.ui.glass.GlassStyle
import kotlin.math.hypot

/**
 * v0.7.0 REVISION (docs/UI_STYLE_SPEC.md section 5) — the muted hue-RING
 * wheel from the color-wheel.jpg reference:
 *
 *   - a hue ANNULUS (inner radius 0.56R, rendered at S=1.0 V=0.65 — the
 *     measured muted ring, WheelGeometry.RING_VALUE);
 *   - a CENTER SWATCH disc at 0.22R showing the current color;
 *   - one small bright marker on the ring at the picked hue/sat;
 *   - a separate slim amber VALUE slider (0.6.0 hard-wired V=1.0).
 *
 * The pointer math is [WheelGeometry] (JVM-tested) — angle->hue,
 * radius->saturation — so touch and marker can never disagree (this
 * fixes the 0.6.0 bug where the marker drew 90deg off the touch).
 * The HSV model, the `color` property and the live `onColorChanged`
 * callback keep their exact 0.6.0 semantics.
 */
class ColorWheelView(context: Context) : LinearLayout(context) {

    /** Selected hue [0..360), saturation [0..1], value [0..1]. */
    var hue: Float = 0f
        private set
    var sat: Float = 1f
        private set
    var value: Float = 1f
        private set

    /** Selected color (full alpha); setter re-derives hue/sat/value. */
    var color: Int = 0xFF16181D.toInt()
        set(v) {
            field = v
            val hsv = FloatArray(3)
            Color.colorToHSV(v, hsv)
            hue = hsv[0]
            sat = hsv[1]
            value = hsv[2]
            if (::valueBar.isInitialized) {
                valueBar.progress = (value * 100f).toInt().coerceIn(0, 100)
            }
            ring.invalidate()
        }

    private var onColorChanged: ((Int) -> Unit)? = null

    private val ring = RingView(
        context,
        onPick = { h, s ->
            hue = h
            sat = s
            applyColor()
        },
        hueGetter = { hue },
        satGetter = { sat },
        swatchGetter = { color },
    )

    private lateinit var valueBar: SeekBar

    init {
        orientation = VERTICAL
        val density = resources.displayMetrics.density
        val pad = (density * 16).toInt()
        setPadding(pad, pad, pad, (density * 8).toInt())

        addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // slim value row: the muted ring never reaches V=1 on its own
        val valueRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        valueRow.addView(TextView(context).apply {
            text = "Value"
            textSize = 11f
            setTextColor(GlassStyle.TEXT_SECONDARY)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.22f))
        valueBar = SeekBar(context).apply {
            max = 100
            progress = 100
            contentDescription = "color value slider"
            progressTintList = ColorStateList.valueOf(GlassStyle.ACCENT)
            thumbTintList = ColorStateList.valueOf(GlassStyle.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF3C3C42.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        value = p / 100f
                        applyColor()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { }
                override fun onStopTrackingTouch(sb: SeekBar?) { }
            })
        }
        valueRow.addView(valueBar, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.78f))
        addView(valueRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // seed h/s/v from the default color
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
    }

    /** Computes the picked color from h/s/v and emits it live. */
    private fun applyColor() {
        color = Color.HSVToColor(floatArrayOf(hue, sat, value)) or 0xFF000000.toInt()
        ring.invalidate()
        onColorChanged?.invoke(color)
    }

    fun onColorChanged(listener: (Int) -> Unit) {
        onColorChanged = listener
    }

    /**
     * The ring itself: annulus + center swatch + marker. Square via
     * onMeasure; every constant comes from WheelGeometry (measured).
     */
    private class RingView(
        context: Context,
        private val onPick: (Float, Float) -> Unit,
        private val hueGetter: () -> Float,
        private val satGetter: () -> Float,
        private val swatchGetter: () -> Int,
    ) : View(context) {

        private val ringPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
        }
        private val gapPaint = Paint().apply {
            isAntiAlias = true
            color = 0xFF1C1D21.toInt() // measured dark chrome between ring+swatch
        }
        private val swatchBorderPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xFF3C3C3C.toInt()
        }
        private val swatchPaint = Paint().apply { isAntiAlias = true }
        private val markerPaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
        }
        private val markerBorderPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xFF1E1E1E.toInt()
        }

        private var shaderSet = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // square: side = min(available width, 260dp), floored at 180dp
            val d = resources.displayMetrics.density
            val cap = (d * 260f).toInt()
            val w = View.MeasureSpec.getSize(widthMeasureSpec)
                .coerceAtMost(cap).coerceAtLeast((d * 180f).toInt())
            setMeasuredDimension(w, w)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuildShader()
        }

        /** One sweep shader around the ring at the measured muted V. */
        private fun rebuildShader() {
            if (width <= 0) return
            val cx = width / 2f
            val cy = height / 2f
            val colors = IntArray(13)
            for (i in 0..12) {
                colors[i] = Color.HSVToColor(
                    floatArrayOf((i * 30f) % 360f, 1f, WheelGeometry.RING_VALUE))
            }
            ringPaint.shader = SweepGradient(cx, cy, colors, null)
            shaderSet = true
        }

        private fun radii(): FloatArray {
            val outer = minOf(width, height) / 2f * 0.92f
            val inner = outer * WheelGeometry.RING_INNER_FRACTION
            return floatArrayOf(inner, outer)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (!shaderSet) rebuildShader()
            val cx = width / 2f
            val cy = height / 2f
            val inner = radii()[0]
            val outer = radii()[1]
            val mid = (inner + outer) / 2f

            // neutral gap disc under everything (dark chrome)
            canvas.drawCircle(cx, cy, mid, gapPaint)
            // the muted hue annulus
            ringPaint.strokeWidth = outer - inner
            canvas.drawCircle(cx, cy, mid, ringPaint)
            // center swatch: the CURRENT color (true V from the slider)
            val swatchR = outer * WheelGeometry.CENTER_SWATCH_FRACTION
            swatchPaint.color = swatchGetter()
            canvas.drawCircle(cx, cy, swatchR, swatchPaint)
            canvas.drawCircle(cx, cy, swatchR, swatchBorderPaint)

            // marker: small white dot with ink border at (hue, sat)
            val r = WheelGeometry.radiusFromSaturation(satGetter(), inner, outer)
            val dxy = WheelGeometry.deltaFromHue(hueGetter(), r)
            val mx = cx + dxy[0]
            val my = cy + dxy[1]
            canvas.drawCircle(mx, my, resources.displayMetrics.density * 5f, markerPaint)
            canvas.drawCircle(mx, my, resources.displayMetrics.density * 5f, markerBorderPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val cx = width / 2f
                    val cy = height / 2f
                    val inner = radii()[0]
                    val outer = radii()[1]
                    val dx = event.x - cx
                    val dy = event.y - cy
                    val h = WheelGeometry.hueFromDelta(dx, dy)
                    val s = WheelGeometry.saturationFromRadius(
                        hypot(dx.toDouble(), dy.toDouble()).toFloat(), inner, outer)
                    onPick(h, s)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            }
            return super.onTouchEvent(event)
        }
    }
}
