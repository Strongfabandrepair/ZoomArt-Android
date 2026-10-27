package com.example.zoomart.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar

/**
 * HSV color picker: hue SeekBar + saturation/value pad + live preview.
 * Fully programmatic (no XML resources), matching the rest of the scaffold.
 */
class ColorPickerDialog(
    context: Context,
    initialColor: Int,
    private val onPicked: (Int) -> Unit,
) : Dialog(context) {

    private val hsv = FloatArray(3).also { Color.colorToHSV(initialColor, it) }

    private class SVPadView(context: Context) : View(context) {
        var hue: Float = 0f
            set(value) { field = value; rebuild() }
        var sat: Float = 1f
        var value: Float = 1f
        var onSV: (Float, Float) -> Unit = { _, _ -> }

        private val basePaint = Paint()
        private val overlayPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }
        private val markerPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.WHITE
        }

        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null) // xfermode correctness
        }

        private fun rebuild() {
            if (width <= 0 || height <= 0) return
            val full = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            basePaint.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                Color.WHITE, full, Shader.TileMode.CLAMP,
            )
            overlayPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP,
            )
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, basePaint)
            canvas.drawRect(0f, 0f, w, h, overlayPaint)
            canvas.drawCircle(sat * w, (1f - value) * h, 9f, markerPaint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    sat = (e.x / width).coerceIn(0f, 1f)
                    value = 1f - (e.y / height).coerceIn(0f, 1f)
                    onSV(sat, value)
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(e)
        }
    }

    private lateinit var pad: SVPadView
    private lateinit var preview: View

    init {
        setContentView(buildView(context))
        setTitle("Color")
    }

    private fun buildView(ctx: Context): View {
        val density = ctx.resources.displayMetrics.density

        pad = SVPadView(ctx).apply {
            hue = hsv[0]
            sat = hsv[1]
            value = hsv[2]
            onSV = { s, v ->
                hsv[1] = s
                hsv[2] = v
                updatePreview()
            }
        }

        val hueBar = SeekBar(ctx).apply {
            max = 359
            progress = hsv[0].toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        hsv[0] = p.toFloat()
                        pad.hue = p.toFloat()
                        updatePreview()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { }
                override fun onStopTrackingTouch(sb: SeekBar?) { }
            })
        }

        preview = View(ctx).apply { setBackgroundColor(currentColor()) }

        val ok = Button(ctx).apply {
            text = "OK"
            setOnClickListener {
                onPicked(currentColor())
                dismiss()
            }
        }
        val cancel = Button(ctx).apply {
            text = "Cancel"
            setOnClickListener { dismiss() }
        }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(ok, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(cancel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad16 = (density * 16).toInt()
            setPadding(pad16, pad16, pad16, pad16)
            addView(pad, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (density * 200).toInt()))
            addView(hueBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(preview, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (density * 40).toInt()))
            addView(buttons, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun currentColor(): Int = Color.HSVToColor(hsv) or 0xFF000000.toInt()

    private fun updatePreview() {
        preview.setBackgroundColor(currentColor())
    }
}
