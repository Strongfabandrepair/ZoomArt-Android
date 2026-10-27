package com.example.zoomart.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v0.7.0: circular color wheel (reference image 3 — "easy color selection").
 *
 * Hue is mapped around the wheel's circumference (0°=red, 120°=green, 240°=blue,
 * etc.). Saturation is the radial distance from center (center = gray/white,
 * edge = fully saturated). The center dot shows the currently selected color
 * (full saturation at the sampled hue).
 *
 * Purely programmatic — draws into an in-memory bitmap once, then just blits
 * it on each frame (cheap). Touching the wheel picks a hue+sat; dragging
 * updates live.
 */
class ColorWheelView(context: Context) : View(context) {

    private val wheelPaint = Paint().apply { isAntiAlias = true }
    private val centerPaint = Paint().apply { isAntiAlias = true }
    private val borderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF2A2E37.toInt()
    }

    private var wheelBmp: Bitmap? = null
    private var wheelSize = 0f

    /** Selected hue [0..360) and saturation [0..1]. Value/alpha fixed at 1.0. */
    var hue: Float = 0f
    var sat: Float = 1f

    /** Selected color (full alpha). Updated live by the wheel / by setColor(). */
    var color: Int = 0xFF16181D.toInt()
        set(value) {
            field = value
            Color.colorToHSV(value, hsv)
            hue = hsv[0]
            sat = hsv[1]
            centerPaint.color = value
            invalidate()
        }

    private val hsv = FloatArray(3)

    private var onColorChanged: ((Int) -> Unit)? = null

    fun onColorChanged(listener: (Int) -> Unit) {
        onColorChanged = listener
    }

    init {
        centerPaint.color = color
        setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        wheelSize = minOf(w, h).toFloat() * 0.9f
        rebuildWheel()
    }

    /** Render the color wheel into a bitmap (once, or when size changes). */
    private fun rebuildWheel() {
        if (wheelSize <= 0f) return
        val s = wheelSize.toInt()
        wheelBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)!!
        val c = Canvas(wheelBmp!!)
        val cx = s / 2f
        val cy = s / 2f
        val radius = s / 2f

        // Draw 360 thin wedges, each at full saturation for its hue.
        val wedge = Paint().apply { isAntiAlias = true }
        for (i in 0 until 360) {
            val h = i.toFloat()
            wedge.color = Color.HSVToColor(floatArrayOf(h, 1f, 1f))
            c.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                h - 0.5f, 1f, true, wedge)
        }

        // Saturation mask: center is white (sat=0), edge is transparent
        // so the full-sat wheel shows through but center fades to white.
        val satMask = Paint().apply {
            shader = RadialGradient(cx, cy, radius,
                intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt()),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), satMask)

        // Center dot border
        c.drawCircle(cx, cy, radius * 0.5f, borderPaint)
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        wheelBmp?.let { bmp ->
            val cx = (width / 2f).toFloat()
            val cy = (height / 2f).toFloat()
            val left = cx - bmp.width / 2f
            val top = cy - bmp.height / 2f
            canvas.drawBitmap(bmp, left, top, wheelPaint)

            // Center dot: selected color
            val dotRadius = bmp.width * 0.42f
            val dotLeft = cx - dotRadius
            val dotTop = cy - dotRadius
            canvas.drawCircle(cx, cy, dotRadius, centerPaint)
            canvas.drawCircle(cx, cy, dotRadius, borderPaint)

            // Selection marker: small dot at the hue+sat position on the wheel
            val angle = Math.toRadians(((hue + 90) % 360).toDouble()).toFloat() // 0° at top
            val r = sat * bmp.width * 0.45f
            val mx = cx + cos(angle.toDouble()).toFloat() * r
            val my = cy - sin(angle.toDouble()).toFloat() * r
            val markerPaint = Paint().apply {
                isAntiAlias = true
                color = Color.WHITE
            }
            canvas.drawCircle(mx, my, density * 5f, markerPaint)
            canvas.drawCircle(mx, my, density * 5f, Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.BLACK
            })
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val bmp = wheelBmp ?: return true
                val cx = (width / 2f).toFloat()
                val cy = (height / 2f).toFloat()
                val left = cx - bmp.width / 2f
                val top = cy - bmp.height / 2f
                val dx = event.x - (left + bmp.width / 2f)
                val dy = event.y - (top + bmp.height / 2f)
                val dist = kotlin.math.sqrt(dx.toDouble() * dx.toDouble() +
                    dy.toDouble() * dy.toDouble()).toFloat()
                val radius = bmp.width * 0.45f

                // angle: 0° at top, clockwise
                var angle = kotlin.math.atan2(-dy.toDouble(), dx.toDouble()).toFloat()
                angle = if (angle < 0f) angle + 6.2831853f else angle
                hue = (angle * 360f / 6.2831853f).coerceIn(0f, 360f)

                sat = (dist / radius).coerceIn(0f, 1f)

                color = Color.HSVToColor(floatArrayOf(hue, sat, 1f)) or 0xFF000000.toInt()
                onColorChanged?.invoke(color)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    /** Set color from outside (e.g. initial brush color) — use the `color` property setter. */

    companion object {
    }
}
