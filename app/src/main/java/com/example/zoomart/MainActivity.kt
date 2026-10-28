package com.example.zoomart

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.zoomart.export.MediaSaver
import java.io.File
import com.example.zoomart.export.PngExporter
import com.example.zoomart.export.ZoomVideoExporter
import com.example.zoomart.model.CrashGuard
import com.example.zoomart.model.DocumentCodec
import com.example.zoomart.ui.BlendPanel
import com.example.zoomart.ui.BrushPanel
import com.example.zoomart.model.BrushPresets
import com.example.zoomart.ui.ColorPickerDialog
import com.example.zoomart.ui.ColorWheelView
import com.example.zoomart.ui.ImagePanel
import com.example.zoomart.ui.LayersPanel
import com.example.zoomart.ui.PinsPanel
import com.example.zoomart.ui.ShapesPanel
import com.example.zoomart.ui.ZoomableCanvasView
import com.example.zoomart.ui.glass.BackdropSnapshotter
import com.example.zoomart.ui.glass.Brand
import com.example.zoomart.ui.glass.FloatingPanel
import com.example.zoomart.ui.glass.GlassStyle
import com.example.zoomart.ui.glass.OverlayLayer
import com.example.zoomart.ui.glass.Springs
import java.io.FileOutputStream
import kotlin.math.max

/**
 * U-overhaul (0.6.0): the activity content is now a full-bleed FrameLayout
 * with the canvas at the bottom and the glass OverlayLayer (floating dock
 * + draggable panels) as a sibling ABOVE it. The flat bottom toolbar strip
 * became a floating, rounded, frosted dock (same buttons, same labels);
 * every panel sits in a FloatingPanel shell with a drag handle, backdrop
 * snapshot blur (API 31+) and spring micro-animations. Auto-hide: stylus
 * strokes/pinches slide the panels away and fade the dock; they spring
 * back ~600 ms after the last gesture (OverlayLayer + AutoHideStateMachine).
 */
class MainActivity : AppCompatActivity() {

    lateinit var canvasView: ZoomableCanvasView
        private set

    /** U-overhaul: exposed for the Robolectric glass tests (same module). */
    internal lateinit var overlay: OverlayLayer
        private set
    private val shellOf = HashMap<View, FloatingPanel>()

    private lateinit var brushPanel: BrushPanel
    private lateinit var layersPanel: LayersPanel
    private lateinit var pinsPanel: PinsPanel
    private lateinit var shapesPanel: ShapesPanel
    private lateinit var blendPanel: BlendPanel
    private lateinit var imagePanel: ImagePanel
    /** v0.7.0: the slim icon strip — every control is a 48dp line-icon
     *  chip; contentDescription keeps the legacy label words (a11y +
     *  dump-based tests keep matching). */
    private val stripChips = LinkedHashMap<String, ImageButton>()
    private val stripDots = HashMap<String, View>()
    private lateinit var colorSwatch: View
    private lateinit var undoButton: ImageButton
    private lateinit var redoButton: ImageButton
    private lateinit var lazyButton: ImageButton

    private val mainHandler = Handler(Looper.getMainLooper())
    private var exportRunning = false

    private val sessionFile: File
        get() = File(filesDir, "zoomart_session.json")

    private val crashFile: File
        get() = File(filesDir, "zoomart_crash_session.json")

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { importImage(it) } }

    private val autosaveRunnable = object : Runnable {
        override fun run() {
            saveSession(quiet = true)
            mainHandler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        canvasView = ZoomableCanvasView(this)
        brushPanel = BrushPanel(
            this,
            // v0.7.0: selecting a preset resets erase (smart switch back to draw).
            { brush -> canvasView.viewModel.selectBrushPreset(brush); updateSwatch() },
            { radius ->
                canvasView.viewModel.eraserRadiusWorld = radius
                canvasView.requestSceneRepaint()
            },
            // P3.2: lazy stabilizer toggle + rope radius (screen px)
            { on, radius ->
                canvasView.viewModel.lazyEnabled = on
                canvasView.viewModel.lazyBrush.radiusScreen = radius
            },
        )
        layersPanel = LayersPanel(this, canvasView) { refreshToolbar() }
        pinsPanel = PinsPanel(this, canvasView) { refreshToolbar() }
        // P3.1/P3.3: arming a shape type or opening the Blend panel switches
        // the canvas tool so the next stylus gesture draws/smudges
        shapesPanel = ShapesPanel(this, canvasView.viewModel) {
            canvasView.tool = ZoomableCanvasView.Tool.SHAPE
            canvasView.requestSceneRepaint()
        }
        blendPanel = BlendPanel(this, canvasView.viewModel)
        // P4.3: image panel (transparency / lock / tracing / display filters)
        imagePanel = ImagePanel(this, canvasView) { refreshToolbar() }

        CrashGuard.install(
            sessionSerializer = {
                if (::canvasView.isInitialized) canvasView.viewModel.serialize() else null
            },
            crashFile = crashFile,
        )

        // U-overhaul: full-bleed canvas with the glass overlay above it.
        val content = FrameLayout(this)
        content.addView(canvasView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay = OverlayLayer(this)
        content.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(content)

        // U-overhaul: each panel floats in a draggable glass shell; the
        // backdrop provider snapshots the canvas region behind the shell
        // (show/drag-end only) for the API 31+ frosted blur.
        val snapshotter = BackdropSnapshotter(canvasView)

        // v0.7.0 REVISION: ONE slim monochrome icon strip (the reference
        // icons.jpg language — docs/UI_STYLE_SPEC.md section 3) plus the
        // six floating panels behind it. The color swatch rides the strip.
        buildToolStrip(snapshotter)

        updateSwatch()
        overlay.autoHideEnabled = AppSettings.getAutoHide(this)

        canvasView.onStateChanged = { refreshToolbar() }
        canvasView.onEyedrop = { color ->
            canvasView.viewModel.currentBrush =
                canvasView.viewModel.currentBrush.copy(color = color)
            brushPanel.syncFrom(canvasView.viewModel.currentBrush)
            updateSwatch()
        }
        // U-overhaul: stylus strokes / pinches drive the auto-hide pipeline.
        canvasView.onCanvasActivityChanged = { active -> overlay.onCanvasActivity(active) }

        // v0.7.0: honor the entry mode passed from TitleActivity ("resume" or "new")
        // and the persisted user settings before loading the canvas state.
        val entryMode = intent.getStringExtra("zoomart.mode")
        if (entryMode == "new") {
            sessionFile.delete()
            crashFile.delete()
            toast("New project")
        }
        // Apply persisted default brush preset.
        canvasView.viewModel.currentBrush = BrushPresets.all[AppSettings.getBrushPresetIndex(this)].second
        // The brush panel reflects that spec (dash/angle/scatter fields
        // included) before the user touches a slider.
        brushPanel.syncFrom(canvasView.viewModel.currentBrush)

        loadSession()
        refreshToolbar()


        mainHandler.postDelayed(autosaveRunnable, 15_000L)
    }

    // ------------------------------------------------------------------
    // v0.7.0 REVISION — the slim monochrome icon strip (docs/UI_STYLE_SPEC
    // .md section 3). ONE horizontally-scrollable dock: 24dp thin line-art
    // icons in 48dp targets, neutral strokes, NO labels/headers/dividers —
    // the old 8-section grouping is expressed purely as 14dp spacing gaps.
    // Active tool = amber tint + 4dp underline dot.
    // ------------------------------------------------------------------

    /** Builds the strip dock + the six draggable panel shells. */
    private fun buildToolStrip(snapshotter: BackdropSnapshotter) {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun addGap() {
            row.addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams((density * 14).toInt(), 1)
            })
        }

        // ----- tools -----
        stripChip(row, R.drawable.ic_brush, "Brush") { togglePanel(brushPanel) }
        stripChip(row, R.drawable.ic_erase, "Erase") { toggleEraseOnBrush() }
        stripChip(row, R.drawable.ic_pick, "Pick") { togglePick() }
        stripChip(row, R.drawable.ic_select, "Select") { toggleSelect() }
        stripChip(row, R.drawable.ic_shapes, "Shapes") { toggleShapesTool() }
        stripChip(row, R.drawable.ic_blend, "Blend") { toggleBlendTool() }
        addGap()
        // ----- color + stabilizer -----
        val swatchFrame = FrameLayout(this).apply {
            contentDescription = "Color"
            isClickable = true
            isFocusable = true
            Springs.attachPressFeedback(this)
            setOnClickListener {
                showColorPicker()
                GlassStyle.selectionTap(this@MainActivity)
            }
        }
        colorSwatch = View(this)
        swatchFrame.addView(colorSwatch, FrameLayout.LayoutParams(
            (density * 28).toInt(), (density * 28).toInt(), Gravity.CENTER))
        row.addView(swatchFrame, LinearLayout.LayoutParams(
            (density * 48).toInt(), (density * 48).toInt()))
        lazyButton = stripChip(row, R.drawable.ic_lazy, "Lazy") { toggleLazy() }
        addGap()
        // ----- panels / content -----
        stripChip(row, R.drawable.ic_layers, "Layers") {
            togglePanel(layersPanel); layersPanel.refresh()
        }
        stripChip(row, R.drawable.ic_image, "Image") {
            togglePanel(imagePanel)
            imagePanel.refresh()
            if (shellOf[imagePanel]?.isShowing == true) {
                toast("Image panel - acts on the selected or latest image")
            }
        }
        stripChip(row, R.drawable.ic_pins, "Pins") {
            togglePanel(pinsPanel); pinsPanel.refresh()
        }
        stripChip(row, R.drawable.ic_import, "Import") { importLauncher.launch("image/*") }
        addGap()
        // ----- history / edit -----
        undoButton = stripChip(row, R.drawable.ic_undo, "Undo") {
            canvasView.viewModel.undo()
            canvasView.requestSceneRepaint()
            refreshToolbar()
        }
        redoButton = stripChip(row, R.drawable.ic_redo, "Redo") {
            canvasView.viewModel.redo()
            canvasView.requestSceneRepaint()
            refreshToolbar()
        }
        stripChip(row, R.drawable.ic_delete, "Delete") {
            if (canvasView.viewModel.document.selection.deleteSelection()) {
                canvasView.requestSceneRepaint()
                toast("Selection deleted (Undo restores it)")
            }
        }
        stripChip(row, R.drawable.ic_clear, "Clear") { confirmClearLayer() }
        stripChip(row, R.drawable.ic_flip, "Flip") {
            withSelection { it.flip(true) }
            toast("Selection flipped horizontally")
        }
        stripChip(row, R.drawable.ic_rot90, "Rotate") {
            withSelection { it.rotate(15f) }
            toast("Selection rotated +15 deg (repeat to keep turning)")
        }
        addGap()
        // ----- view -----
        stripChip(row, R.drawable.ic_zoom_out, "Zoom out") { canvasView.zoomBy(0.5f) }
        stripChip(row, R.drawable.ic_zoom_in, "Zoom in") { canvasView.zoomBy(2f) }
        addGap()
        // ----- export / demo -----
        stripChip(row, R.drawable.ic_png, "PNG") { exportPng() }
        stripChip(row, R.drawable.ic_video, "Video") { exportVideo() }
        stripChip(row, R.drawable.ic_demo, "Demo") { canvasView.runDemo() }

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalFadingEdgeEnabled = true
            isFillViewport = false
        }
        scroll.addView(row)
        val dock = FloatingPanel(this, "Dock",
            { p -> snapshotter.snapshot(p) }, kind = FloatingPanel.Kind.DOCK)
        dock.setContent(scroll)
        overlay.setDock(dock, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)

        // ----- the six panel shells: drag + persist + auto-hide unchanged -----
        panelShell("BrushPanel", brushPanel, Gravity.BOTTOM or Gravity.START,
            light = true, snapshotter = snapshotter)
        panelShell("ShapesPanel", shapesPanel, Gravity.BOTTOM or Gravity.START,
            snapshotter = snapshotter)
        panelShell("BlendPanel", blendPanel, Gravity.BOTTOM or Gravity.START,
            snapshotter = snapshotter)
        panelShell("LayersPanel", layersPanel, Gravity.END or Gravity.CENTER_VERTICAL,
            snapshotter = snapshotter)
        panelShell("ImagePanel", imagePanel, Gravity.BOTTOM or Gravity.START,
            snapshotter = snapshotter)
        panelShell("PinsPanel", pinsPanel, Gravity.TOP or Gravity.END,
            snapshotter = snapshotter)
        updateStripStates()
    }

    /** Wraps a panel view in a draggable FloatingPanel shell (shellOf
     *  registration keeps togglePanel working for all six panels). */
    private fun panelShell(
        key: String,
        panel: View,
        gravity: Int,
        light: Boolean = false,
        snapshotter: BackdropSnapshotter,
    ): FloatingPanel {
        val shell = FloatingPanel(this, key, { p -> snapshotter.snapshot(p) }, light = light)
        shell.setContent(panel)
        shellOf[panel] = shell
        overlay.addShell(shell, gravity,
            bottomInsetPx = if (gravity and Gravity.BOTTOM != 0) {
                (resources.displayMetrics.density * 14).toInt()
            } else 0)
        return shell
    }

    /**
     * One icon chip: 48dp touch target, 24dp line icon, amber underline
     * dot for the ACTIVE state. [desc] is the legacy text label, kept as
     * contentDescription so TalkBack and dump-based tests keep matching.
     */
    private fun stripChip(
        row: LinearLayout, iconRes: Int, desc: String, onClick: () -> Unit,
    ): ImageButton {
        val density = resources.displayMetrics.density
        val chip = FrameLayout(this)
        val icon = ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = desc
            GlassStyle.iconButton(this)
            Springs.attachPressFeedback(this)
            setPadding(0, 0, 0, (density * 5).toInt())
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (isEnabled) GlassStyle.pressTap(this@MainActivity)
                    }
                }
                false
            }
            setOnClickListener {
                onClick()
                GlassStyle.selectionTap(this@MainActivity)
                updateStripStates()
            }
        }
        chip.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                setColor(GlassStyle.ACCENT)
                cornerRadius = 2f * density
            }
            visibility = View.INVISIBLE
        }
        chip.addView(dot, FrameLayout.LayoutParams(
            (density * 4).toInt(), (density * 4).toInt(),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (density * 2).toInt()
        })
        row.addView(chip, LinearLayout.LayoutParams(
            (density * 48).toInt(), (density * 48).toInt()))
        stripChips[desc] = icon
        stripDots[desc] = dot
        return icon
    }

    /** Recomputes the strip visuals: amber tint + dot for the active tool,
     *  dimming for disabled actions (undo/redo). */
    private fun updateStripStates() {
        if (stripChips.isEmpty()) return
        val vm = canvasView.viewModel
        val tool = canvasView.tool
        val active = mapOf(
            "Brush" to (tool == ZoomableCanvasView.Tool.DRAW && !vm.eraseOnCurrentBrush),
            "Erase" to vm.eraseOnCurrentBrush,
            "Pick" to (tool == ZoomableCanvasView.Tool.EYEDROPPER),
            "Select" to (tool == ZoomableCanvasView.Tool.SELECT),
            "Shapes" to (tool == ZoomableCanvasView.Tool.SHAPE),
            "Blend" to (tool == ZoomableCanvasView.Tool.SMUDGE),
            "Lazy" to vm.lazyEnabled,
        )
        for ((desc, icon) in stripChips) {
            val on = active[desc] == true
            icon.setColorFilter(if (on) GlassStyle.ACCENT else GlassStyle.TEXT_PRIMARY)
            icon.alpha = if (icon.isEnabled) 1f else 0.35f
            stripDots[desc]?.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
    }

    /** v0.7.0: the eyedropper is a first-class strip tool again (pen tap
     *  samples + auto-returns to drawing — see ZoomableCanvasView). */
    private fun togglePick() {
        canvasView.tool =
            if (canvasView.tool == ZoomableCanvasView.Tool.EYEDROPPER)
                ZoomableCanvasView.Tool.DRAW
            else ZoomableCanvasView.Tool.EYEDROPPER
        toast(
            if (canvasView.tool == ZoomableCanvasView.Tool.EYEDROPPER)
                "Eyedropper ON - tap with the pen to sample a color"
            else "Back to drawing"
        )
    }

    private fun togglePanel(panel: View) {
        val shell = shellOf[panel] ?: return
        val wasOpen = shell.isShowing
        // Dismiss all other panel shells first (one panel at a time)
        for (s in listOf(brushPanel, layersPanel, pinsPanel, shapesPanel, blendPanel, imagePanel)) {
            val sh = shellOf[s]
            if (sh != null && sh != shell) sh.dismiss()
        }
        if (wasOpen) {
            shell.dismiss()
        } else {
            shell.show()
        }
    }

    /** P4.4: quick lazy-stabilizer toggle (mirrors the Brush panel control). */
    private fun toggleLazy() {
        val vm = canvasView.viewModel
        vm.lazyEnabled = !vm.lazyEnabled
        toast(if (vm.lazyEnabled) "Lazy stabilizer ON (radius in Brush panel)"
              else "Lazy stabilizer OFF")
        refreshToolbar()
    }

    /** P4.4: CLEAR — empties the active layer behind a confirm dialog. */
    private fun confirmClearLayer() {
        val doc = canvasView.viewModel.document
        val layer = doc.activeLayer
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear layer?")
            .setMessage("Remove all content on '" + layer.name + "'? (Undo restores it)")
            .setPositiveButton("Clear") { _, _ ->
                if (doc.clearActiveLayer()) {
                    canvasView.requestSceneRepaint()
                    toast("Layer cleared (Undo restores it)")
                }
                refreshToolbar()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * P3.1: the Shapes toolbar button toggles the SHAPE tool; the panel
     * opens alongside. Tapping it again returns to plain drawing.
     */
    private fun toggleShapesTool() {
        if (canvasView.tool == ZoomableCanvasView.Tool.SHAPE) {
            canvasView.tool = ZoomableCanvasView.Tool.DRAW
            shellOf[shapesPanel]?.dismiss()
            toast("Back to drawing")
        } else {
            canvasView.tool = ZoomableCanvasView.Tool.SHAPE
            togglePanel(shapesPanel)
            shapesPanel.syncToggles()
            toast("Shapes - drag to draw " +
                canvasView.viewModel.armedShapeType.name.lowercase() +
                "; Select tool taps/moves/resizes shapes")
        }
    }

    /** P3.3: the Blend toolbar button arms the smudge tool + its panel. */
    private fun toggleBlendTool() {
        if (canvasView.tool == ZoomableCanvasView.Tool.SMUDGE) {
            canvasView.tool = ZoomableCanvasView.Tool.DRAW
            shellOf[blendPanel]?.dismiss()
            toast("Back to drawing")
        } else {
            canvasView.tool = ZoomableCanvasView.Tool.SMUDGE
            togglePanel(blendPanel)
            blendPanel.syncFromVm()
            toast("Blend/smudge ON - drag to smear color (strength/size/softness in panel)")
        }
    }

    /** v0.7.0: erase-as-modifier — toggle erase on the current brush, not a locked tool mode. */
    private fun toggleEraseOnBrush() {
        val vm = canvasView.viewModel
        vm.eraseOnCurrentBrush = !vm.eraseOnCurrentBrush
        toast(if (vm.eraseOnCurrentBrush)
            "Erase ON for current brush - tap any brush preset to return to drawing"
        else "Back to drawing")
    }

    private fun toggleSelect() {
        canvasView.tool =
            if (canvasView.tool == ZoomableCanvasView.Tool.SELECT) ZoomableCanvasView.Tool.DRAW
            else ZoomableCanvasView.Tool.SELECT
        if (canvasView.tool != ZoomableCanvasView.Tool.SELECT) {
            canvasView.viewModel.document.selection.clear()
        }
        toast(
            if (canvasView.tool == ZoomableCanvasView.Tool.SELECT)
                "Select ON - drag a rectangle around strokes; drag inside to move, pinch to scale"
            else "Back to drawing"
        )
    }

    private fun withSelection(action: (com.example.zoomart.model.SelectionModel) -> Unit) {
        val sel = canvasView.viewModel.document.selection
        if (sel.isEmpty) {
            toast("Nothing selected - use Select first")
            return
        }
        sel.beginTransform()
        action(sel)
        sel.endTransform()
        canvasView.requestSceneRepaint()
    }

    private fun showColorPicker() {
        // v0.7.0: circular color wheel replaces the HSV dialog — live preview,
        // no OK/Cancel tap required (color applies as you pick).
        ColorWheelView(this).apply {
            color = canvasView.viewModel.currentBrush.color
            onColorChanged { color ->
                canvasView.viewModel.currentBrush =
                    canvasView.viewModel.currentBrush.copy(color = color)
                brushPanel.syncFrom(canvasView.viewModel.currentBrush)
                updateSwatch()
            }
        }.let { wheel ->
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Color")
                .setView(wheel)
                .setPositiveButton("OK") { _, _ -> }
                .show()
        }
    }

    private fun updateSwatch() {
        if (::colorSwatch.isInitialized) {
            colorSwatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(canvasView.viewModel.currentBrush.color)
                setStroke(maxOf(1, (resources.displayMetrics.density).toInt()),
                    GlassStyle.BORDER)
            }
        }
    }

    // ----- image import (Infinite-Design-style: place images from Gallery) -----

    private fun importImage(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // inJustDecodeBounds=true makes decodeStream return null BY DESIGN
            // (it only fills bounds.outWidth/outHeight). Checking the null
            // result always threw "unreadable image" — the real failure behind
            // every manual import. Check the parsed bounds instead.
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "unreadable image" }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: throw IllegalStateException("could not decode image")
            val dir = File(filesDir, "imports").apply { mkdirs() }
            val file = File(dir, "imp_" + System.currentTimeMillis() + ".png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val aspect = bmp.width.toFloat() / bmp.height.toFloat()
            bmp.recycle()
            // P4.1: IMAGE IMPORT V2 — the bitmap lands on a NEW layer above
            // the active one (named "Image <n>"), which becomes active;
            // ONE undo step covers the whole import.
            val img = canvasView.viewModel.importImageOnNewLayer("imports/" + file.name, aspect)
            canvasView.requestSceneRepaint()
            if (img != null) {
                toast("Image placed on new layer '" +
                    canvasView.viewModel.document.activeLayer.name + "'")
            } else {
                toast("Image placed (raster underlay)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Import failed", t)
            toast("Import failed: " + t.message)
        }
    }

    // ----- exports -----

    private fun exportPng() {
        if (exportRunning) return
        exportRunning = true
        toast("Rendering PNG...")
        Thread {
            try {
                val name = "zoomart_" + System.currentTimeMillis() + ".png"
                val bmp = PngExporter.renderBitmap(canvasView)
                val where = MediaSaver.saveToGallery(this@MainActivity, bmp, name)
                    ?: run {
                        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
                        dir.mkdirs()
                        val file = File(dir, name)
                        FileOutputStream(file).use {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        file.absolutePath
                    }
                bmp.recycle()
                Log.i(TAG, "PNG saved: " + where)
                runOnUiThread { toast("Saved: " + where) }
            } catch (t: Throwable) {
                Log.e(TAG, "PNG export failed", t)
                runOnUiThread { toast("PNG export failed: " + t.message) }
            } finally {
                exportRunning = false
            }
        }.start()
    }

    private fun exportVideo() {
        if (exportRunning) return
        val bookmarks = canvasView.viewModel.document.bookmarks.toList()
        if (bookmarks.isEmpty()) {
            toast("Add pins first (double-tap the canvas)")
            return
        }
        exportRunning = true
        toast("Recording zoom tour (" + bookmarks.size + " pins)...")
        Thread {
            try {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                dir.mkdirs()
                val file = File(dir, "zoomart_tour_" + System.currentTimeMillis() + ".mp4")
                ZoomVideoExporter.export(canvasView, bookmarks, file)
                Log.i(TAG, "Video saved: " + file.absolutePath)
                runOnUiThread { toast("Saved: " + file.absolutePath) }
            } catch (t: Throwable) {
                Log.e(TAG, "Video export failed", t)
                runOnUiThread { toast("Video export failed: " + t.message) }
            } finally {
                exportRunning = false
            }
        }.start()
    }

    // ----- session persistence -----

    private fun saveSession(quiet: Boolean) {
        try {
            val payload = canvasView.viewModel.serialize()
            val tmp = File(filesDir, "zoomart_session.json.tmp")
            tmp.writeText(payload)
            sessionFile.delete()
            if (!tmp.renameTo(sessionFile)) {
                sessionFile.writeText(payload)
            }
            // v0.7.0: record that a session exists so the title page can offer Resume.
            AppSettings.noteSessionSaved(this)
            if (!quiet) toast("Session saved")
        } catch (t: Throwable) {
            if (!quiet) toast("Save failed: " + t.message)
        }
    }

    private fun loadSession() {
        if (crashFile.exists()) {
            try {
                canvasView.viewModel.restore(DocumentCodec.fromJson(crashFile.readText()))
                crashFile.delete()
                canvasView.requestSceneRepaint()
                toast("Recovered session from last crash")
                return
            } catch (t: Throwable) {
                crashFile.delete()
            }
        }
        val f = sessionFile
        if (!f.exists()) return
        try {
            canvasView.viewModel.restore(DocumentCodec.fromJson(f.readText()))
            canvasView.requestSceneRepaint()
            toast("Session restored")
        } catch (t: Throwable) {
            toast("Session file unreadable; starting fresh")
        }
    }

    override fun onPause() {
        super.onPause()
        saveSession(quiet = true)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autosaveRunnable)
        super.onDestroy()
    }

    // ----- misc -----

    private fun refreshToolbar() {
        if (::undoButton.isInitialized) {
            val doc = canvasView.viewModel.document
            undoButton.isEnabled = doc.canUndo
            redoButton.isEnabled = doc.canRedo
        }
        updateStripStates()
        if (::layersPanel.isInitialized) layersPanel.refresh()
        if (::pinsPanel.isInitialized) pinsPanel.refresh()
        if (::imagePanel.isInitialized) imagePanel.refresh()
    }

    private fun toast(msg: String?) {
        Toast.makeText(this, msg ?: "", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        private const val TAG = "ZoomArt"
    }
}
