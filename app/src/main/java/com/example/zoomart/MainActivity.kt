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
import android.util.AtomicFile
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private var crashHandler: Thread.UncaughtExceptionHandler? = null

    private val sessionFile: File
        get() = File(filesDir, "zoomart_session.json")

    private val crashFile: File
        get() = File(filesDir, "zoomart_crash_session.json")

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { importImage(it) } }

    private val autosaveRunnable = object : Runnable {
        override fun run() {
            queueSessionSave()
            scheduleAutosave()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        canvasView = ZoomableCanvasView(this)
        brushPanel = BrushPanel(
            this,
            // v0.7.0: selecting a preset resets erase (smart switch back to draw).
            { brush ->
                canvasView.viewModel.selectBrushPreset(brush)
                canvasView.tool = ZoomableCanvasView.Tool.DRAW
                updateSwatch()
                updateStripStates()
            },
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

        crashHandler = CrashGuard.install(
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
        WindowInsetsSupport.apply(content)

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

        canvasView.onStylusContact = { shellOf.values.forEach { it.dismiss() } }
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
        if (entryMode == "new" && savedInstanceState == null) {
            // Keep a recoverable copy before replacing the current project.
            com.example.zoomart.model.SessionWriter.awaitIdle()
            val archive = File(filesDir, "project_backups").apply { mkdirs() }
            try {
                for (file in listOf(sessionFile, crashFile, File(sessionFile.path + ".bak"))) {
                    if (file.exists()) file.copyTo(File(archive, "${System.currentTimeMillis()}_${file.name}"))
                }
                AtomicFile(sessionFile).delete()
                AtomicFile(crashFile).delete()
            } catch (e: Exception) {
                toast("Could not back up the previous project; opening it instead")
            }
            toast("New project")
        }
        // Apply persisted default brush preset.
        canvasView.viewModel.currentBrush = BrushPresets.all[AppSettings.getBrushPresetIndex(this)].second
        // The brush panel reflects that spec (dash/angle/scatter fields
        // included) before the user touches a slider.
        brushPanel.syncFrom(canvasView.viewModel.currentBrush)

        val projectPath = if (savedInstanceState == null) intent.getStringExtra("zoomart.project") else null
        if (projectPath != null) {
            try {
                com.example.zoomart.model.SessionWriter.awaitIdle()
                val library = com.example.zoomart.model.ProjectLibrary(filesDir)
                val selected = library.read(com.example.zoomart.model.ProjectLibrary.Entry(File(projectPath), "Drawing"))
                if (sessionFile.exists()) library.save("Before opening another drawing",
                    DocumentCodec.fromJson(AtomicFile(sessionFile).openRead().bufferedReader().use { it.readText() }))
                if (crashFile.exists()) {
                    val backup = File(filesDir, "project_backups").apply { mkdirs() }
                    crashFile.copyTo(File(backup, "${System.currentTimeMillis()}_recovery.json"))
                }
                canvasView.viewModel.restore(selected)
                if (saveSession(quiet = true)) AtomicFile(crashFile).delete()
            } catch (e: Exception) { toast("Could not open drawing: ${e.message}"); loadSession() }
        } else loadSession()
        refreshToolbar()


    }

    // ------------------------------------------------------------------
    // Contextual orbital dock: labeled primary actions, selection commands,
    // secondary tools under More, and draggable themed panel shells.
    // ------------------------------------------------------------------

    /** Builds the strip dock + the six draggable panel shells. */
    private fun buildToolStrip(snapshotter: BackdropSnapshotter) {
        val density = resources.displayMetrics.density
        val vertical = AppSettings.getDockOrientation(this) == 1
        val row = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun addGap() = Unit

        stripChip(row, R.drawable.ic_studio, getString(R.string.project_library)) {
            startActivity(android.content.Intent(this, ProjectLibraryActivity::class.java))
        }
        // ----- tools -----
        stripChip(row, R.drawable.ic_brush, "Brush") {
            canvasView.tool = ZoomableCanvasView.Tool.DRAW
            canvasView.viewModel.eraseOnCurrentBrush = false
            togglePanel(brushPanel)
        }
        stripChip(row, R.drawable.ic_erase, "Erase") { toggleEraseOnBrush() }
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
        stripChip(row, R.drawable.ic_png, "PNG") {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Export image")
                .setItems(arrayOf("Current view (keep zoomed detail)", "Entire drawing")) { _, which ->
                    exportPng(entireDrawing = which == 1)
                }.setNegativeButton("Cancel", null).show()
        }
        stripChip(row, R.drawable.ic_video, "Video") { exportVideo() }
        stripChip(row, R.drawable.ic_demo, "Demo") { canvasView.runDemo() }

        val secondary = listOf("Pick", "Shapes", "Blend", "Lazy", "Image", "Pins", "Import", "Clear", "Zoom out", "Zoom in", "PNG", "Video", "Demo")
        secondary.forEach { (stripChips[it]?.parent as? View)?.visibility = View.GONE }
        stripChip(row, R.drawable.ic_more, getString(R.string.more_tools)) {
            val actions = secondary.mapNotNull { stripChips[it] }
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.more_tools)
                .setItems(actions.map { it.contentDescription.toString() }.toTypedArray()) { _, index -> actions[index].performClick() }.show()
        }
        val scroll = if (vertical) android.widget.ScrollView(this).apply {
            isVerticalFadingEdgeEnabled = true
            addView(row)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                (resources.displayMetrics.heightPixels * 0.72f).toInt())
        } else HorizontalScrollView(this).apply {
            isHorizontalFadingEdgeEnabled = true
            isFillViewport = false
            addView(row)
        }
        val dock = FloatingPanel(this, "Dock",
            { p -> snapshotter.snapshot(p) }, kind = FloatingPanel.Kind.DOCK)
        dock.setContent(scroll)
        overlay.setDock(dock, if (vertical) Gravity.START or Gravity.CENTER_VERTICAL
            else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)

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
        if (light) shell.setContent(panel) else {
            val density = resources.displayMetrics.density
            val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val title = when(key) {
                "LayersPanel" -> R.string.layer_studio
                "ShapesPanel" -> R.string.shape_studio
                "BlendPanel" -> R.string.blend_studio
                "ImagePanel" -> R.string.image_studio
                else -> R.string.pin_studio
            }
            host.addView(TextView(this).apply {
                setText(title);textSize=18f;setTextColor(android.graphics.Color.WHITE)
                setPadding((12*density).toInt(),(12*density).toInt(),(12*density).toInt(),(12*density).toInt())
                background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xFF25283D.toInt(),0xFF51445C.toInt())).apply { cornerRadius=14*density }
                androidx.core.view.ViewCompat.setAccessibilityHeading(this,true)
            },LinearLayout.LayoutParams(-1,-2))
            val scroll = ScrollView(this).apply { addView(panel) }
            host.addView(scroll,LinearLayout.LayoutParams(-1,
                minOf((420*density).toInt(),(resources.displayMetrics.heightPixels*0.55f).toInt())))
            host.layoutParams=FrameLayout.LayoutParams(minOf((360*density).toInt(),resources.displayMetrics.widthPixels-(64*density).toInt()),-2)
            shell.setContent(host)
        }
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
        val labelHeight=(16*density*resources.configuration.fontScale).toInt()
        val label=when(desc) {
            "Brush" -> getString(R.string.dock_brush)
            "Erase" -> getString(R.string.dock_erase)
            "Undo" -> getString(R.string.dock_undo)
            "Redo" -> getString(R.string.dock_redo)
            "Pick" -> getString(R.string.dock_pick)
            "Select" -> getString(R.string.dock_select)
            "Shapes" -> getString(R.string.dock_shapes)
            "Blend" -> getString(R.string.dock_blend)
            "Lazy" -> getString(R.string.dock_lazy)
            "Layers" -> getString(R.string.dock_layers)
            "Image" -> getString(R.string.dock_image)
            "Pins" -> getString(R.string.dock_pins)
            "Import" -> getString(R.string.dock_import)
            "Delete" -> getString(R.string.dock_delete)
            "Clear" -> getString(R.string.dock_clear)
            "Flip" -> getString(R.string.dock_flip)
            "Rotate" -> getString(R.string.dock_rotate)
            "Zoom out" -> getString(R.string.dock_zoom_out)
            "Zoom in" -> getString(R.string.dock_zoom_in)
            "PNG" -> getString(R.string.dock_png)
            "Video" -> getString(R.string.dock_video)
            "Demo" -> getString(R.string.dock_demo)
            else -> desc
        }
        val icon = ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = label
            tooltipText = label
            GlassStyle.iconButton(this)
            Springs.attachPressFeedback(this)
            setPadding(0, 0, 0, labelHeight)
            setOnClickListener {
                onClick()
                Springs.pulse(this)
                updateStripStates()
            }
        }
        chip.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        chip.addView(TextView(this).apply {
            text = when(desc) { getString(R.string.project_library) -> getString(R.string.studio_short); getString(R.string.more_tools) -> getString(R.string.more_short); else -> label }
            maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
            textSize=9f;setTextColor(0xFFDADBE2.toInt());gravity=Gravity.CENTER
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },FrameLayout.LayoutParams(-1,labelHeight,Gravity.BOTTOM))
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                setColor(GlassStyle.ACCENT)
                cornerRadius = 2f * density
            }
            visibility = View.INVISIBLE
        }
        chip.addView(dot, FrameLayout.LayoutParams(
            (density * 4).toInt(), (density * 4).toInt(),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (density * 2).toInt()
        })
        row.addView(chip, LinearLayout.LayoutParams(
            (density * 48).toInt(), maxOf((density * 48).toInt(),(density*32).toInt()+labelHeight)))
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
            icon.isSelected = on
            androidx.core.view.ViewCompat.setStateDescription(icon, if (on) getString(R.string.tool_selected) else null)
            if (desc in listOf("Delete", "Flip", "Rotate")) (icon.parent as? View)?.visibility =
                if (tool == ZoomableCanvasView.Tool.SELECT) View.VISIBLE else View.GONE
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
        if (panel === brushPanel) brushPanel.syncStabilizer(canvasView.viewModel.lazyEnabled, canvasView.viewModel.lazyBrush.radiusScreen)
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
        canvasView.tool = ZoomableCanvasView.Tool.DRAW
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
        refreshToolbar()
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
        toast("Importing image…")
        Thread {
            var output: File? = null
            var bitmap: Bitmap? = null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable image" }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                val decoded = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("Could not decode image")
                bitmap = decoded
                val orientation = runCatching {
                    contentResolver.openInputStream(uri)?.use {
                        android.media.ExifInterface(it).getAttributeInt(
                            android.media.ExifInterface.TAG_ORIENTATION, 1)
                    } ?: 1
                }.getOrDefault(1)
                val matrix = android.graphics.Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f)
                        3 -> setRotate(180f)
                        4 -> setScale(1f, -1f)
                        5 -> { setRotate(90f); postScale(-1f, 1f) }
                        6 -> setRotate(90f)
                        7 -> { setRotate(270f); postScale(-1f, 1f) }
                        8 -> setRotate(270f)
                    }
                }
                val oriented = if (matrix.isIdentity) decoded else
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (oriented !== decoded) decoded.recycle()
                bitmap = oriented
                val dir = File(filesDir, "imports").apply { mkdirs() }
                val file = File.createTempFile("imp_", ".png", dir)
                output = file
                FileOutputStream(file).use { check(oriented.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                val aspect = oriented.width.toFloat() / oriented.height
                runOnUiThread {
                    if (isDestroyed || isFinishing) { file.delete(); return@runOnUiThread }
                    canvasView.viewModel.importImageOnNewLayer("imports/" + file.name, aspect)
                    canvasView.requestSceneRepaint()
                    refreshToolbar()
                    toast("Image placed on a new layer")
                }
            } catch (t: Throwable) {
                output?.delete()
                Log.e(TAG, "Import failed", t)
                runOnUiThread { if (!isDestroyed) toast("Import failed: " + t.message) }
            } finally { bitmap?.recycle() }
        }.start()
    }

    // ----- exports -----

    /** Capture on the UI thread; workers never share mutable geometry or Paint with the screen. */
    private fun exportSnapshot(): ZoomableCanvasView = ZoomableCanvasView(this).also { copy ->
        copy.viewModel.restore(DocumentCodec.fromJson(canvasView.viewModel.serialize()))
        copy.layout(0, 0, canvasView.width.coerceAtLeast(1), canvasView.height.coerceAtLeast(1))
        copy.canvasRotationDegrees = canvasView.canvasRotationDegrees
        canvasView.viewModel.document.layers.zip(copy.viewModel.document.layers).forEach { (source, target) ->
            source.images.zip(target.images).forEach { (a, b) -> b.displayFilter = a.displayFilter }
        }
    }

    private fun exportPng(entireDrawing: Boolean = false) {
        if (exportRunning) return
        val snapshot = try { exportSnapshot() } catch (e: Exception) {
            toast("Could not prepare export: ${e.message}"); return
        }
        exportRunning = true
        toast("Rendering PNG...")
        Thread {
            try {
                val name = "zoomart_" + System.currentTimeMillis() + ".png"
                val bmp = if (entireDrawing) PngExporter.renderBitmap(snapshot) else PngExporter.renderViewport(snapshot)
                val where = try { MediaSaver.saveToGallery(this@MainActivity, bmp, name)
                    ?: run {
                        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(filesDir, "exports")
                        dir.mkdirs()
                        val file = File(dir, name)
                        FileOutputStream(file).use {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        file.absolutePath
                    }
                } finally { bmp.recycle() }
                Log.i(TAG, "PNG saved: " + where)
                runOnUiThread { showExportResult(where, "image/png") }
            } catch (t: Throwable) {
                Log.e(TAG, "PNG export failed", t)
                runOnUiThread { toast("PNG export failed: " + t.message) }
            } finally {
                runOnUiThread { exportRunning = false }
            }
        }.start()
    }

    private fun exportVideo() {
        if (exportRunning) return
        val bookmarks = canvasView.viewModel.document.bookmarks.toList()
        if (bookmarks.isEmpty()) {
            toast("Add pins first (hold the S Pen still on the canvas)")
            return
        }
        val snapshot = try { exportSnapshot() } catch (e: Exception) {
            toast("Could not prepare export: ${e.message}"); return
        }
        exportRunning = true
        toast("Recording zoom tour (" + bookmarks.size + " pins)...")
        Thread {
            try {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(filesDir, "exports")
                dir.mkdirs()
                val file = File(dir, "zoomart_tour_" + System.currentTimeMillis() + ".mp4")
                ZoomVideoExporter.export(snapshot, bookmarks, file)
                val where = MediaSaver.saveVideoToGallery(this@MainActivity, file) ?: file.absolutePath
                Log.i(TAG, "Video saved: " + file.absolutePath)
                runOnUiThread { showExportResult(where, "video/mp4") }
            } catch (t: Throwable) {
                Log.e(TAG, "Video export failed", t)
                runOnUiThread { toast("Video export failed: " + t.message) }
            } finally {
                runOnUiThread { exportRunning = false }
            }
        }.start()
    }

    // ----- session persistence -----

    private fun showExportResult(where: String, mime: String) {
        if (isFinishing || isDestroyed) return
        val gallery = where.startsWith("content:")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (mime == "image/png") "Image saved" else "Video saved")
            .setMessage(if (gallery) "Saved to your gallery's ZoomArt folder."
                else "Saved on this device. Use Share to send it or save a copy to your files.")
            .setNegativeButton("Done", null)
            .setPositiveButton("Share") { _, _ ->
                val uri = if (gallery) Uri.parse(where) else
                    androidx.core.content.FileProvider.getUriForFile(this, "$packageName.exports", File(where))
                val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("ZoomArt export", uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(share, "Share artwork"))
            }.show()
    }

    private fun queueSessionSave() {
        if (!::canvasView.isInitialized) return
        val snapshot = com.example.zoomart.model.SessionWriter.capture(canvasView.viewModel.document, canvasView.viewModel.camera)
        val app = applicationContext
        com.example.zoomart.model.SessionWriter.enqueue(sessionFile, snapshot) { error ->
            if (error == null) AppSettings.noteSessionSaved(app)
            else { Log.e(TAG, "Background save failed", error)
                mainHandler.post { if (!isDestroyed) toast("Save failed. Your last good save is retained.") }
            }
        }
    }

    private fun saveSession(quiet: Boolean): Boolean {
        val atomic = AtomicFile(sessionFile)
        var output: FileOutputStream? = null
        try {
            val payload = canvasView.viewModel.serialize()
            output = atomic.startWrite()
            output.write(payload.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            // v0.7.0: record that a session exists so the title page can offer Resume.
            AppSettings.noteSessionSaved(this)
            if (!quiet) toast("Session saved")
            return true
        } catch (t: Throwable) {
            atomic.failWrite(output)
            Log.e(TAG, "Session save failed", t)
            if (!quiet) toast("Save failed: " + t.message)
            return false
        }
    }

    private fun loadSession() {
        com.example.zoomart.model.SessionWriter.latest(sessionFile)?.let {
            canvasView.viewModel.restore(it); canvasView.requestSceneRepaint(); return
        }
        if (crashFile.exists()) {
            try {
                canvasView.viewModel.restore(DocumentCodec.fromJson(crashFile.readText()))
                if (saveSession(quiet = true)) AtomicFile(crashFile).delete()
                canvasView.requestSceneRepaint()
                toast("Recovered session from last crash")
                return
            } catch (t: Throwable) {
                Log.e(TAG, "Crash recovery file unreadable; retaining for recovery", t)
            }
        }
        val f = sessionFile
        if (!f.exists() && !File(f.path + ".bak").exists()) return
        try {
            val payload = AtomicFile(f).openRead().bufferedReader().use { it.readText() }
            canvasView.viewModel.restore(DocumentCodec.fromJson(payload))
            canvasView.requestSceneRepaint()
            toast("Session restored")
        } catch (t: Throwable) {
            // Keep the original bytes available even after a fresh autosave succeeds.
            runCatching {
                f.copyTo(File(filesDir, "unreadable_session_${System.currentTimeMillis()}.json"))
            }
            toast("Session file unreadable; starting fresh")
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(autosaveRunnable)
        canvasView.cancelActiveGesture()
        super.onPause()
        queueSessionSave()
    }

    private fun scheduleAutosave() {
        mainHandler.removeCallbacks(autosaveRunnable)
        val interval = AppSettings.getAutosaveMs(this)
        if (interval > 0L) mainHandler.postDelayed(autosaveRunnable, interval)
    }

    override fun onResume() {
        super.onResume()
        overlay.autoHideEnabled = AppSettings.getAutoHide(this)
        canvasView.fingerDrawingEnabled = AppSettings.getFingerDrawing(this)
        scheduleAutosave()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autosaveRunnable)
        CrashGuard.uninstall(crashHandler)
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
        if (::layersPanel.isInitialized && shellOf[layersPanel]?.isShowing == true) layersPanel.refresh()
        if (::pinsPanel.isInitialized && shellOf[pinsPanel]?.isShowing == true) pinsPanel.refresh()
        if (::imagePanel.isInitialized && shellOf[imagePanel]?.isShowing == true) imagePanel.refresh()
    }

    private fun toast(msg: String?) {
        Toast.makeText(this, msg ?: "", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        private const val TAG = "ZoomArt"
    }
}
