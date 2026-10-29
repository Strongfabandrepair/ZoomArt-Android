package com.example.zoomart.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.example.zoomart.model.*
import com.example.zoomart.ui.glass.GlassStyle
import com.example.zoomart.ui.glass.Springs
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Brush Atelier: real ink preview, pressure controls and a personal preset shelf. */
class BrushPanel(
    context: Context,
    private val onBrushChanged: (BrushSpec) -> Unit,
    private val onEraserRadius: (Float) -> Unit = {},
    private val onLazyChanged: (Boolean, Float) -> Unit = { _, _ -> },
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).roundToInt()
    private var currentSpec = BrushSpec()
    private var lazyOn = false
    private var lazyRadius = 60f
    private lateinit var lazyToggle: Button
    fun syncStabilizer(enabled: Boolean, radius: Float) {
        lazyOn = enabled; lazyRadius = radius
        lazyToggle.text = if (enabled) "Stabilizer · ON" else "Stabilizer · OFF"
        preview.setStabilizer(enabled, radius)
    }
    private val bars = mutableListOf<Pair<SeekBar, (BrushSpec) -> Int>>()
    private val shelf = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val preview = PreviewStrip(context)
    private val title = TextView(context)
    private val prefs = context.getSharedPreferences("artist_brushes", Context.MODE_PRIVATE)
    private val saved = mutableListOf<Pair<String, BrushSpec>>()
    private var selectedName = "Custom ink"
    private lateinit var body: LinearLayout
    private lateinit var pressureAlpha: CheckBox
    private val ink = 0xFF242635.toInt()
    private val accent = 0xFFAE5A19.toInt()

    init {
        orientation = VERTICAL
        val panelWidth = min(328, (resources.displayMetrics.widthPixels / density).toInt() - 72).coerceAtLeast(200)
        layoutParams = LayoutParams(dp(panelWidth), LayoutParams.WRAP_CONTENT)
        title.apply {
            text = "BRUSH ATELIER\nMake your mark."
            textSize = 17f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF25283D.toInt(), 0xFF51445C.toInt())).apply { cornerRadius = dp(18).toFloat() }
        }
        addView(title, LayoutParams(-1, -2))
        val scroll = ScrollView(context).apply { isFillViewport = false; clipToPadding = false }
        body = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(4), dp(12), dp(4), dp(12)) }
        scroll.addView(body)
        val available = (resources.displayMetrics.heightPixels / density * 0.64f).toInt()
        addView(scroll, LayoutParams(-1, dp(min(480, available).coerceAtLeast(180))))
        label("INK LAB  ·  draw here to try", body)
        body.addView(preview, LayoutParams(-1, dp(88)))
        val actions = LinearLayout(context)
        action("Clear", actions) { preview.clear() }
        action("Replay", actions) { preview.replay() }
        action("Save brush", actions) { savePreset() }
        body.addView(actions)
        label("YOUR BRUSH SHELF", body)
        val shelfScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        shelfScroll.addView(shelf); body.addView(shelfScroll, LayoutParams(-1, dp(92)))
        loadSaved(); rebuildShelf()
        slider("Size", { ((it.baseWidthWorld - 1f) * 100 / 79).roundToInt() },
            { "${(1 + it * 79f / 100).roundToInt()} px" }) { currentSpec = currentSpec.copy(baseWidthWorld = 1 + it * 79f / 100) }
        slider("Opacity", { (it.opacity * 100).roundToInt() }, { "$it%" }) { currentSpec = currentSpec.copy(opacity = it / 100f) }
        slider("Pressure", { (it.pressureResponse * 25).roundToInt() }, { "%.1f×".format(it / 25f) }) { currentSpec = currentSpec.copy(pressureResponse = it / 25f) }
        val details = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
        val more = Button(context).apply {
            text = "Fine tune  +"; isAllCaps = false; minHeight = dp(48)
            setOnClickListener {
                val show = details.visibility != VISIBLE
                text = if (show) "Fine tune  −" else "Fine tune  +"
                if (show) Springs.fadeBackIn(details) else Springs.fadeThenGone(details)
                Springs.pulse(this)
            }
        }
        body.addView(more, LayoutParams(-1, -2)); body.addView(details)
        slider("Nib angle", { (it.angleDeg / 1.8f).roundToInt() }, { "${(it * 1.8f).roundToInt()}°" }, details) { currentSpec = currentSpec.copy(angleDeg = it * 1.8f) }
        slider("Minimum width", { (it.minWidthRatio * 100).roundToInt() }, { "$it%" }, details) { currentSpec = currentSpec.copy(minWidthRatio = it / 100f) }
        slider("Spacing", { (it.spacingWorld * 2).roundToInt() }, { if (it == 0) "Continuous" else "%.1f px".format(it / 2f) }, details) { currentSpec = currentSpec.copy(spacingWorld = it / 2f, dashOn = if (it > 0) 0f else currentSpec.dashOn) }
        slider("Scatter", { (it.scatterWorld * 2).roundToInt() }, { "%.1f px".format(it / 2f) }, details) { currentSpec = currentSpec.copy(scatterWorld = it / 2f, spacingWorld = if (it > 0) max(1f, currentSpec.spacingWorld) else currentSpec.spacingWorld, dashOn = if (it > 0) 0f else currentSpec.dashOn) }
        slider("Dash length", { (it.dashOn * 2).roundToInt() }, { if (it == 0) "Solid" else "%.1f px".format(it / 2f) }, details) { currentSpec = currentSpec.copy(dashOn = it / 2f, dashOff = max(1f, currentSpec.dashOff), spacingWorld = if (it > 0) 0f else currentSpec.spacingWorld) }
        slider("Dash gap", { (it.dashOff * 2).roundToInt() }, { "%.1f px".format(max(0.5f, it / 2f)) }, details) { currentSpec = currentSpec.copy(dashOff = max(0.5f, it / 2f)) }
        pressureAlpha = CheckBox(context).apply {
            buttonTintList=ColorStateList.valueOf(accent)
            text = "Pressure controls opacity"; setTextColor(ink); minHeight = dp(48)
            setOnClickListener { currentSpec = currentSpec.copy(pressureOpacity = isChecked); emit(); Springs.tick(this) }
        }
        details.addView(pressureAlpha, LayoutParams(-1, dp(48)))
        slider("Minimum opacity", { (it.minOpacity * 100).roundToInt() }, { "$it%" }, details) { currentSpec = currentSpec.copy(minOpacity = it / 100f) }
        slider("Erase size", { 24 }, { "${(5 + it * 1.45f).roundToInt()} px" }, details, false) { onEraserRadius(5 + it * 1.45f) }
        val lazy = Button(context).apply {
            text = "Stabilizer · OFF"; contentDescription = "lazy toggle"; isAllCaps = false
            setOnClickListener { lazyOn = !lazyOn; text = if (lazyOn) "Stabilizer · ON" else "Stabilizer · OFF"; preview.setStabilizer(lazyOn, lazyRadius); onLazyChanged(lazyOn, lazyRadius); Springs.pulse(this) }
        }
        lazyToggle = lazy
        details.addView(lazy, LayoutParams(-1, dp(48)))
        slider("Stabilizer radius", { 30 }, { "${it * 2} px" }, details, false) { lazyRadius = it * 2f; preview.setStabilizer(lazyOn, lazyRadius); onLazyChanged(lazyOn, lazyRadius) }.contentDescription = "lazy radius slider"
        syncFrom(currentSpec)
    }
    private fun label(text: String, parent: LinearLayout) {
        parent.addView(TextView(context).apply {
            this.text = text; textSize = 10f; letterSpacing = 0.12f; setTextColor(accent)
            setPadding(dp(4), dp(10), dp(4), dp(8)); typeface = Typeface.DEFAULT_BOLD
        })
    }
    private fun action(text: String, parent: LinearLayout, click: () -> Unit) {
        parent.addView(Button(context).apply {
            this.text = text; isAllCaps = false; textSize = 12f; minWidth = 0
            setOnClickListener { click(); Springs.pulse(this) }; Springs.attachPressFeedback(this)
        }, LayoutParams(0, dp(48), 1f))
    }
    private fun slider(name: String, read: (BrushSpec) -> Int, format: (Int) -> String,
        parent: LinearLayout = body, brushSetting: Boolean = true, change: (Int) -> Unit): SeekBar {
        val row = LinearLayout(context).apply { orientation = VERTICAL }
        val caption = TextView(context).apply { textSize = 12f; setTextColor(ink); setPadding(dp(4), dp(6), dp(4), 0) }
        row.addView(caption)
        val bar = SeekBar(context).apply {
            max = 100; contentDescription = name
            progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(0xFFE2DCD5.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                    caption.text = "$name   ${format(p)}"
                    if (user) { change(p); if (brushSetting) { selectedName = "Custom ink"; emit() } }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) { Springs.tick(this@apply) }
            })
        }
        row.addView(bar, LayoutParams(-1, dp(48))); parent.addView(row, LayoutParams(-1, -2))
        bar.progress = read(currentSpec).coerceIn(0, 100)
        caption.text = "$name   ${format(bar.progress)}"
        if (brushSetting) bars.add(bar to read)
        return bar
    }
    private fun rebuildShelf() {
        shelf.removeAllViews()
        (BrushPresets.all + saved).forEachIndexed { index, (name, spec) ->
            val card = LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(6))
                fun face(selected: Boolean) = GradientDrawable().apply {
                    setColor(if (selected) 0xFFFFEBD5.toInt() else 0xFFFFF9F0.toInt())
                    cornerRadius = dp(14).toFloat(); setStroke(dp(if (selected) 2 else 1), if (selected) accent else 0xFFE6DACC.toInt())
                }
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_selected), face(true)); addState(intArrayOf(), face(false))
                }
                contentDescription = name; isFocusable = true
                addView(BrushPreviewView(context, name, spec), LayoutParams(dp(84), dp(32)))
                addView(TextView(context).apply { text = name; textSize = 11f; setTextColor(ink); maxLines = 2; gravity = Gravity.CENTER }, LayoutParams(-1, dp(32)))
                setOnClickListener {
                    selectedName = name
                    currentSpec = spec.copy(color = currentSpec.color, erase = false)
                    syncFrom(currentSpec); emit(); preview.replay(); Springs.pulse(this)
                    for (i in 0 until shelf.childCount) shelf.getChildAt(i).isSelected = shelf.getChildAt(i) === this
                }
                if (index >= BrushPresets.all.size) setOnLongClickListener {
                    AlertDialog.Builder(context).setTitle("Remove $name?").setNegativeButton("Keep", null)
                        .setPositiveButton("Remove") { _, _ -> saved.removeAt(index - BrushPresets.all.size); persist(); rebuildShelf() }.show(); true
                }
                Springs.attachPressFeedback(this)
            }
            shelf.addView(card, LayoutParams(dp(112), dp(80)).apply { rightMargin = dp(8) })
        }
    }
    private fun savePreset() {
        val input = EditText(context).apply { hint = "Brush name"; isSingleLine = true; filters = arrayOf(android.text.InputFilter.LengthFilter(32)) }
        AlertDialog.Builder(context).setTitle("Save your brush").setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "My brush ${saved.size + 1}" }
                val old = saved.indexOfFirst { it.first == name }
                if (old >= 0) saved[old] = name to currentSpec else if (saved.size < 32) saved.add(name to currentSpec)
                else { Toast.makeText(context, "Remove a saved brush first (hold its card)", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                persist(); rebuildShelf(); selectedName = name; emit()
            }.show()
    }
    private fun persist() {
        val array = JSONArray()
        saved.forEach { (name,b) -> array.put(JSONObject().put("name",name).put("width",b.baseWidthWorld)
            .put("pressure",b.pressureResponse).put("minWidth",b.minWidthRatio).put("opacity",b.opacity)
            .put("angle",b.angleDeg).put("on",b.dashOn).put("off",b.dashOff).put("scatter",b.scatterWorld)
            .put("spacing",b.spacingWorld).put("pressureOpacity",b.pressureOpacity).put("minOpacity",b.minOpacity)) }
        prefs.edit().putString("presets",array.toString()).apply()
    }
    private fun loadSaved() {
        runCatching {
            val array = JSONArray(prefs.getString("presets","[]"))
            repeat(min(32,array.length())) { i ->
                val b = array.getJSONObject(i)
                saved.add(b.getString("name") to BrushSpec(baseWidthWorld=b.getDouble("width").toFloat(),
                    pressureResponse=b.getDouble("pressure").toFloat(), minWidthRatio=b.getDouble("minWidth").toFloat(),
                    opacity=b.getDouble("opacity").toFloat(), angleDeg=b.getDouble("angle").toFloat(),
                    dashOn=b.getDouble("on").toFloat(),dashOff=b.getDouble("off").toFloat(),
                    scatterWorld=b.getDouble("scatter").toFloat(),spacingWorld=b.getDouble("spacing").toFloat(),
                    pressureOpacity=b.getBoolean("pressureOpacity"),minOpacity=b.getDouble("minOpacity").toFloat()))
            }
        }
    }
    fun syncFrom(spec: BrushSpec) {
        currentSpec = spec
        pressureAlpha.isChecked = spec.pressureOpacity
        bars.forEach { (bar, read) -> bar.progress = read(spec).coerceIn(0,100) }
        preview.setSpec(spec)
    }
    private fun emit() {
        bars.forEach { (bar, read) -> bar.progress = read(currentSpec).coerceIn(0, 100) }
        title.text = "BRUSH ATELIER\n$selectedName"
        preview.setSpec(currentSpec); onBrushChanged(currentSpec)
    }
}

class BrushPreviewView(context: Context, private val presetName: String,
    private val brush: BrushSpec = BrushPresets.all.firstOrNull { it.first == presetName }?.second ?: BrushSpec()) : View(context) {
    var showAmber = false
    private val renderer by lazy { ZoomableCanvasView(context) }
    private var sample: StrokeData? = null
    private var cachedWidth = -1
    private var cachedHeight = -1
    private var cachedAmber = false
    override fun onDraw(canvas: Canvas) {
        if (sample == null || cachedWidth != width || cachedHeight != height || cachedAmber != showAmber) {
            cachedWidth=width; cachedHeight=height; cachedAmber=showAmber
            val fit = min(resources.displayMetrics.density, height * 0.6f / max(1f,brush.baseWidthWorld+brush.scatterWorld*2))
            sample=StrokeData(brush.scaledForLocalScale(1f/fit).copy(color=if(showAmber) GlassStyle.ACCENT else GlassStyle.LIGHT_INK))
            for(i in 0..60) {
                val t=i/60f
                sample!!.addPoint(StrokePoint(width*(0.06f+0.88f*t),height*(0.5f+0.2f*sin(t*2*PI.toFloat())),0.2f+0.8f*sin(t*PI.toFloat())))
            }
        }
        renderer.drawStroke(canvas,sample!!,1f)
    }
}

/** A bounded scratch pad using the same brush renderer as the artwork. */
class PreviewStrip(context: Context) : View(context) {
    private val renderer by lazy { ZoomableCanvasView(context) }
    private var spec = BrushSpec()
    private var sample = StrokeData(spec)
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var reveal = 1f
    private var animation: android.animation.ValueAnimator? = null
    private var drawing = false
    private var previousSample: StrokeData? = null
    private var pointer = -1
    private val stabilizer = LazyBrush()
    private var stabilized = false
    fun clear() { animation?.cancel(); reveal=1f; sample=StrokeData(spec.scaledForLocalScale(1f/density)); invalidate() }
    fun setStabilizer(enabled: Boolean, radius: Float) {
        if (stabilized == (enabled && radius > 0f) && stabilizer.radiusScreen == (if (enabled) radius else 0f)) return
        stabilized = enabled && radius > 0f
        stabilizer.radiusScreen = if (enabled) radius else 0f
        replay()
    }
    init { contentDescription = "Live brush test pad. Draw to try the current brush"; isFocusable = true }
    fun setSpec(value: BrushSpec) { animation?.cancel(); reveal = 1f; spec = value; resetSample(); invalidate() }
    private fun resetSample() {
        sample = StrokeData(spec.scaledForLocalScale(1f / density))
        val rope = LazyBrush(if (stabilized) stabilizer.radiusScreen else 0f)
        for (i in 0..100) {
            val t = i / 100f
            val x = width*(0.08f+0.84f*t)
            val y = height*(0.5f + 0.22f*sin(t*2f*PI.toFloat()))
            if (i == 0) rope.begin(x,y)
            val xy = rope.feed(x,y)
            sample.addPoint(StrokePoint(xy[0],xy[1],0.12f + 0.88f*sin(t*PI.toFloat())))
        }
        if (stabilized) { sample.setPoints(LazyBrush.smoothPoints(sample.points)); sample.taper=LazyBrush.TAPER_FRACTION }
    }

    fun replay() {
        animation?.cancel(); resetSample()
        if (!Springs.enabled()) { reveal = 1f; invalidate(); return }
        animation = android.animation.ValueAnimator.ofFloat(0f,1f).apply {
            duration = 550; interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }; start()
        }
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { resetSample() }
    override fun onDraw(canvas: Canvas) {
        paint.color = 0xFFFFFCF6.toInt(); canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),16*density,16*density,paint)
        paint.color = 0xFFE2DAD0.toInt()
        var x = 12*density
        while (x < width) { var y = 12*density; while (y < height) { canvas.drawCircle(x,y,0.65f*density,paint); y += 16*density }; x += 16*density }
        canvas.save(); canvas.clipRect(0f,0f,width*reveal,height.toFloat())
        renderer.drawStroke(canvas,sample,1f, if (stabilized && drawing) LazyBrush.smoothPoints(sample.points) else null); canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousSample=sample
                animation?.cancel(); reveal=1f; drawing=true; pointer=event.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(true)
                sample=StrokeData(spec.scaledForLocalScale(1f/density)); stabilizer.begin(event.x,event.y); add(event,0)
            }
            MotionEvent.ACTION_MOVE -> { val i=event.findPointerIndex(pointer); if(drawing && i>=0) add(event,i) }
            MotionEvent.ACTION_UP -> { val i=event.findPointerIndex(pointer); if(i>=0) add(event,i); drawing=false; if (stabilized && sample.points.size > 1) { sample.setPoints(LazyBrush.smoothPoints(sample.points)); sample.taper=LazyBrush.TAPER_FRACTION }; stabilizer.end(); parent?.requestDisallowInterceptTouchEvent(false); performClick() }
            MotionEvent.ACTION_CANCEL -> { previousSample?.let { sample=it }; previousSample=null; stabilizer.end(); drawing=false; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        invalidate(); return true
    }
    private fun add(e: MotionEvent,i: Int) {
        if(sample.points.size>=512) return
        val pressure=if(e.getToolType(i)==MotionEvent.TOOL_TYPE_STYLUS) e.getPressure(i).coerceIn(0f,1f) else 1f
        val xy = if (stabilized) stabilizer.feed(e.getX(i),e.getY(i)) else floatArrayOf(e.getX(i),e.getY(i))
        val last = sample.points.lastOrNull()
        if (last == null || last.x != xy[0] || last.y != xy[1]) sample.addPoint(StrokePoint(xy[0],xy[1],pressure))
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { animation?.cancel(); reveal = 1f; super.onDetachedFromWindow() }
}
