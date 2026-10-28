package com.example.zoomart.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.graphics.DashPathEffect
import com.example.zoomart.model.BrushPresets
import com.example.zoomart.model.BrushSpec
import com.example.zoomart.ui.glass.GlassStyle
import com.example.zoomart.ui.glass.Springs

/**
 * v0.7.0 REVISION (docs/UI_STYLE_SPEC.md section 4): the brush panel is
 * now a LIGHT reading panel in the brushes.jpg reference language — a
 * dense vertical list of preset ROWS, each with its characteristic stroke
 * PREVIEW (pure geometry in [BrushPreviewMath], JVM-tested):
 *
 *   - row cards #FAFAFA, 44dp tall, 8dp gaps, 8dp corners (measured
 *     pitch: ~52px rows + 10-12px gaps on the 353px reference);
 *   - ink #1E1E1E previews; the SELECTED preset renders its preview in
 *     the AMBER accent #FFAD01 + a 2dp amber edge (brushes2.jpg: the big
 *     amber stroke preview IS the selection state);
 *   - slim neutral sliders with amber thumb/progress.
 *
 * Callbacks, slider semantics and the contentDescriptions ("lazy toggle",
 * "lazy radius slider") are byte-identical to 0.6.0 — the shell (drag,
 * persistence, auto-hide) is the kept machinery.
 */
class BrushPanel(
    context: Context,
    private val onBrushChanged: (BrushSpec) -> Unit,
    private val onEraserRadius: (Float) -> Unit = {},
    private val onLazyChanged: (Boolean, Float) -> Unit = { _, _ -> },
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density

    private data class Row(
        val card: View,
        val edge: View,
        val preview: BrushPreviewView,
    )

    private val rows = mutableListOf<Row>()
    private lateinit var sizeBar: SeekBar
    private lateinit var responseBar: SeekBar
    private lateinit var opacityBar: SeekBar
    private lateinit var eraseBar: SeekBar
    private lateinit var angleBar: SeekBar
    private lateinit var lazyToggle: Button
    private lateinit var lazyRadiusBar: SeekBar
    /** Live preview strip of the CURRENT (possibly tweaked) spec. */
    private lateinit var previewStrip: PreviewStrip

    private var widthWorld = 6f
    private var response = 2f
    private var opacity = 1f
    private var color: Int = 0xFF16181D.toInt()
    private var pressureOpacity = false // P1.2: preserved from presets across slider tweaks
    private var minOpacity = BrushSpec.DEFAULT_MIN_OPACITY
    private var lazyOn = false            // P3.2: stabilizer toggle
    private var lazyRadius = 60f          // P3.2: rope radius, screen px 0..200
    /** The last-selected preset spec: its passthrough fields
     *  (dashOn/dashOff, angleDeg, scatterWorld, spacingWorld, erase)
     *  survive slider tweaks, so a dashed/dotted brush stays dashed. */
    private var currentSpec: BrushSpec = BrushSpec()

    init {
        orientation = VERTICAL
        // The light FloatingPanel shell behind provides the #F2F2F2 fill.
        setBackgroundColor(0x00000000)
        val pad = (density * 8).toInt()
        setPadding(pad, pad, pad, pad)

        // ----- the dense preset list (reference: brushes.jpg rows) -----
        // The list scrolls inside a capped container so the SHELL stays
        // compact on small/landscape screens (drag + auto-hide untouched).
        val listScroll = android.widget.ScrollView(context).apply {
            isFillViewport = false
        }
        val list = LinearLayout(context).apply { orientation = VERTICAL }
        BrushPresets.all.forEachIndexed { i, (name, spec) ->
            val row = buildRow(i, name, spec)
            rows.add(row)
            list.addView(row.card, LayoutParams(ROW_W_DP * density.toInt(),
                LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (density * 6).toInt()
            })
        }
        listScroll.addView(list)
        addView(listScroll, LayoutParams(ROW_W_DP * density.toInt(),
            (density * 288).toInt()).apply { bottomMargin = (density * 8).toInt() })

        // ----- live preview of the current spec -----
        previewStrip = PreviewStrip(context)
        addView(previewStrip, LayoutParams(ROW_W_DP * density.toInt(),
            (density * 30).toInt()).apply { bottomMargin = (density * 10).toInt() })

        sizeBar = slider("Size") { widthWorld = 1f + it * 49f / 100f }
        responseBar = slider("Pressure") { response = it / 25f }
        opacityBar = slider("Opacity") { opacity = it / 100f }
        eraseBar = slider("Erase size") { onEraserRadius(5f + it * 1.45f) }

        // P3.1: calligraphy nib angle — rotates the nib so the stroke tapers
        // (thick perpendicular to the nib, thin parallel). 0..180 deg.
        angleBar = slider("Angle") { progress ->
            currentSpec = currentSpec.copy(angleDeg = progress.toFloat() * 1.8f)
            emit()
        }

        // P3.2: lazy brush controls — ON/OFF toggle + rope radius 0..200 px
        val lazyRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lazyToggle = Button(context).apply {
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            setPadding((density * 10).toInt(), 0, (density * 10).toInt(), 0)
            contentDescription = "lazy toggle"
            restyleLazy(this)
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
            textSize = 11f
            setTextColor(GlassStyle.LIGHT_INK_SECONDARY)
        }
        lazyRow.addView(radiusLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.2f))
        lazyRadiusBar = SeekBar(context).apply {
            max = 200
            progress = 60
            contentDescription = "lazy radius slider"
            slimAmber()
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
        addView(lazyRow, LayoutParams(ROW_W_DP * density.toInt(), LayoutParams.WRAP_CONTENT))

        syncBars()
        syncLazy()
        highlightPreset(-1)
        eraseBar.progress = 24 // ~40 world units default
    }

    /** One light preset row: [2dp amber edge][preview][name + spec line]. */
    private fun buildRow(index: Int, name: String, spec: BrushSpec): Row {
        val card = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((density * 6).toInt(), (density * 8).toInt(),
                (density * 8).toInt(), (density * 8).toInt())
            background = GradientDrawable().apply {
                setColor(GlassStyle.LIGHT_ROW)
                cornerRadius = density * 8
                setStroke(maxOf(1, density.toInt()), GlassStyle.LIGHT_BORDER)
            }
            contentDescription = name
            isClickable = true
            isFocusable = true
        }
        val edge = View(context).apply { visibility = View.INVISIBLE }
        card.addView(edge, LayoutParams((density * 2).toInt(), (density * 28).toInt()).apply {
            rightMargin = (density * 6).toInt()
        })
        val preview = BrushPreviewView(context, name)
        card.addView(preview, LayoutParams((density * 72).toInt(), (density * 28).toInt()).apply {
            rightMargin = (density * 10).toInt()
        })
        val textCol = LinearLayout(context).apply { orientation = VERTICAL }
        textCol.addView(TextView(context).apply {
            text = name
            textSize = 13f
            setTextColor(GlassStyle.LIGHT_INK)
        })
        textCol.addView(TextView(context).apply {
            text = String.format("%.1fw · %.1fx · %d%%",
                spec.baseWidthWorld, spec.pressureResponse, (spec.opacity * 100).toInt())
            textSize = 10f
            setTextColor(GlassStyle.LIGHT_INK_SECONDARY)
        })
        card.addView(textCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        card.setOnClickListener {
            currentSpec = spec
            widthWorld = spec.baseWidthWorld
            response = spec.pressureResponse
            opacity = spec.opacity
            color = spec.color
            pressureOpacity = spec.pressureOpacity
            minOpacity = spec.minOpacity
            angleBar.progress = (spec.angleDeg / 1.8f).toInt().coerceIn(0, 100)
            syncBars()
            highlightPreset(index)
            emit()
            Springs.pulse(card)
        }
        Springs.attachPressFeedback(card)
        return Row(card, edge, preview)
    }

    private fun syncLazy() {
        lazyToggle.text = if (lazyOn) "Lazy ON" else "Lazy OFF"
        restyleLazy(lazyToggle)
    }

    /** Lazy chip: neutral light fill, amber when active (the accent rule). */
    private fun restyleLazy(b: Button) {
        b.background = GradientDrawable().apply {
            cornerRadius = density * 8
            if (lazyOn) {
                setColor(GlassStyle.ACCENT_DEEP)
            } else {
                setColor(0xFFE6E6EA.toInt())
            }
        }
        b.setTextColor(if (lazyOn) 0xFFFFFFFF.toInt() else GlassStyle.LIGHT_INK)
    }

    /** Slim neutral slider with amber thumb + amber progress (spec 4). */
    private fun SeekBar.slimAmber() {
        progressTintList = ColorStateList.valueOf(GlassStyle.ACCENT)
        thumbTintList = ColorStateList.valueOf(GlassStyle.ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(0xFFC8C8CE.toInt())
    }

    private fun slider(label: String, onChange: (Int) -> Unit): SeekBar {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(GlassStyle.LIGHT_INK_SECONDARY)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.3f))
        val bar = SeekBar(context).apply {
            max = 100
            slimAmber()
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
        addView(row, LayoutParams(ROW_W_DP * density.toInt(), LayoutParams.WRAP_CONTENT))
        return bar
    }

    private fun syncBars() {
        sizeBar.progress = ((widthWorld - 1f) * 100f / 49f).toInt().coerceIn(0, 100)
        responseBar.progress = (response * 25f).toInt().coerceIn(0, 100)
        opacityBar.progress = (opacity * 100f).toInt().coerceIn(0, 100)
        angleBar.progress = (currentSpec.angleDeg / 1.8f).toInt().coerceIn(0, 100)
    }

    /** Amber selection: the selected row's PREVIEW goes amber (the
     *  brushes2 reference language) + a 2dp amber edge on its card. */
    private fun highlightPreset(index: Int) {
        rows.forEachIndexed { i, r ->
            val on = i == index
            r.edge.visibility = if (on) View.VISIBLE else View.INVISIBLE
            r.preview.showAmber = on
            r.preview.invalidate()
        }
    }

    fun syncFrom(spec: BrushSpec) {
        currentSpec = spec
        widthWorld = spec.baseWidthWorld
        response = spec.pressureResponse
        opacity = spec.opacity
        color = spec.color
        pressureOpacity = spec.pressureOpacity
        minOpacity = spec.minOpacity
        syncBars()
        previewStrip.setSpec(currentSpec)
    }

    private fun emit() {
        previewStrip.setSpec(currentSpec.copy(
            baseWidthWorld = widthWorld,
            pressureResponse = response,
            minWidthRatio = 0.15f,
            color = color,
            opacity = opacity,
            pressureOpacity = pressureOpacity,
            minOpacity = minOpacity,
        ))
        onBrushChanged(
            currentSpec.copy(
                baseWidthWorld = widthWorld,
                pressureResponse = response,
                minWidthRatio = 0.15f,
                color = color,
                opacity = opacity,
                pressureOpacity = pressureOpacity,
                minOpacity = minOpacity,
            ),
        )
    }

    private companion object {
        /** Fixed compact content width (dp) — keeps the shell hugging. */
        const val ROW_W_DP = 264
    }
}

/**
 * v0.7.0: renders ONE preset's characteristic stroke preview from the
 * pure geometry in [BrushPreviewMath]. Ink #1E1E1E normally; the SELECTED
 * preset draws in the amber accent (caller flips [selected]).
 */
class BrushPreviewView(context: Context, private val presetName: String) : View(context) {

    /** True when this row is the selected preset -> amber stroke.
     *  (Named showAmber: View already owns a setSelected(Boolean).) */
    var showAmber = false

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint().apply { isAntiAlias = true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val ink = if (showAmber) GlassStyle.ACCENT else GlassStyle.LIGHT_INK
        for (s in BrushPreviewMath.segments(presetName)) {
            val a = (s.alpha * 255).toInt().coerceIn(0, 255)
            if (s.x0 == s.x1 && s.y0 == s.y1) {
                dotPaint.color = (ink and 0x00FFFFFF) or (a shl 24)
                canvas.drawCircle(s.x0 * w, s.y0 * h, s.w * h * 0.5f, dotPaint)
            } else {
                paint.color = (ink and 0x00FFFFFF) or (a shl 24)
                paint.strokeWidth = (s.w * h).coerceAtLeast(1f)
                canvas.drawLine(s.x0 * w, s.y0 * h, s.x1 * w, s.y1 * h, paint)
            }
        }
    }
}

/**
 * Live brush preview — a sample stroke of the CURRENT BrushSpec so the
 * user sees size/opacity/pressure before touching the canvas. Restyled
 * for the light panel (neutral row background, content-colored stroke).
 */
class PreviewStrip(context: Context) : View(context) {

    private val strokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var spec: BrushSpec = BrushSpec()

    /** Called by BrushPanel.emit() with the latest spec (dash fields
     *  included so a dotted/dashed brush previews as dashed). */
    fun setSpec(spec: BrushSpec) {
        this.spec = spec
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density

        // light row background (the shell behind is the light panel)
        canvas.drawColor(GlassStyle.LIGHT_ROW)

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
        // Dash/dot brushes: the dash pattern (screen px at 1x) on the live
        // preview so the user sees a dashed stroke before touching canvas.
        if (spec.dashOn > 0f) {
            strokePaint.pathEffect = DashPathEffect(
                floatArrayOf(spec.dashOn * density, spec.dashOff * density), 0f)
        }
        // main stroke = full pressure
        strokePaint.alpha = alpha
        strokePaint.strokeWidth = widthPx.coerceIn(2f, 40f)
        canvas.drawPath(path, strokePaint)
        strokePaint.pathEffect = null
    }
}
