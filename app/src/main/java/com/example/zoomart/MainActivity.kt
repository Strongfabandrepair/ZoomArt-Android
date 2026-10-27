package com.example.zoomart

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var colorSwatch: Button
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var lazyButton: Button

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
            { radius -> canvasView.viewModel.eraserRadiusWorld = radius },
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

        // v0.7.0: grouped floating toolbars replace the monolithic dock.
        buildGroupedToolbars(snapshotter)

        // v0.7.0: honor the persisted auto-hide preference.
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

        loadSession()
        refreshToolbar()
        mainHandler.postDelayed(autosaveRunnable, 15_000L)
    }

    // v0.7.0: grouped floating toolbars replace the single monolithic dock.
    // Each group is its own FloatingPanel shell; groups with panels can extend
    // into their full panel (chevron), groups without panels show inline buttons.
    private fun buildGroupedToolbars(snapshotter: BackdropSnapshotter) {
        colorSwatch = Button(this).apply { setOnClickListener { showColorPicker() } }
        updateSwatch()

        undoButton = Button(this).apply {
            text = "Undo"
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                resources.getDrawable(R.drawable.ic_undo), null, null, null)
            setCompoundDrawablePadding((resources.displayMetrics.density * 4).toInt())
            GlassStyle.button(this)
            Springs.attachPressFeedback(this)
            setOnClickListener {
                canvasView.viewModel.undo()
                canvasView.requestSceneRepaint()
                refreshToolbar()
            }
        }
        redoButton = Button(this).apply {
            text = "Redo"
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                resources.getDrawable(R.drawable.ic_redo), null, null, null)
            setCompoundDrawablePadding((resources.displayMetrics.density * 4).toInt())
            GlassStyle.button(this)
            Springs.attachPressFeedback(this)
            setOnClickListener {
                canvasView.viewModel.redo()
                canvasView.requestSceneRepaint()
                refreshToolbar()
            }
        }
        lazyButton = Button(this).apply {
            text = "Lazy"
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                resources.getDrawable(R.drawable.ic_lazy), null, null, null)
            setCompoundDrawablePadding((resources.displayMetrics.density * 4).toInt())
            GlassStyle.button(this)
            Springs.attachPressFeedback(this)
            setOnClickListener { toggleLazy() }
        }

        // ----- groups WITH panels: compact button → extends into full panel -----
        // Brushes → BrushPanel (compact: Brush chip + Erase toggle chip)
        val brushesShell = groupedToolbar("Brushes", "BRUSHES",
            compact = listOf(
                iconChip(R.drawable.ic_brush, "Brush", { togglePanel(brushPanel) }),
                iconChip(R.drawable.ic_erase, "Erase", { toggleEraseOnBrush() }),
            ),
            extended = brushPanel,
            gravity = Gravity.BOTTOM or Gravity.START,
            snapshotter = snapshotter)
        // Shapes/Select → ShapesPanel
        val shapesShell = groupedToolbar("Shapes", "SHAPES",
            compact = listOf(iconChip(R.drawable.ic_shapes, "Shapes", { toggleShapesTool() })),
            extended = shapesPanel,
            gravity = Gravity.BOTTOM or Gravity.START,
            snapshotter = snapshotter)
        // Blend/Smudge → BlendPanel
        val blendShell = groupedToolbar("Blend", "BLEND",
            compact = listOf(iconChip(R.drawable.ic_blend, "Blend", { toggleBlendTool() })),
            extended = blendPanel,
            gravity = Gravity.BOTTOM or Gravity.START,
            snapshotter = snapshotter)
        // Layers → LayersPanel
        val layersShell = groupedToolbar("Layers", "LAYERS",
            compact = listOf(iconChip(R.drawable.ic_layers, "Layers", { togglePanel(layersPanel); layersPanel.refresh() })),
            extended = layersPanel,
            gravity = Gravity.END or Gravity.CENTER_VERTICAL,
            snapshotter = snapshotter)
        // Image → ImagePanel
        val imageShell = groupedToolbar("Image", "IMAGE",
            compact = listOf(iconChip(R.drawable.ic_image, "Image", {
                togglePanel(imagePanel)
                imagePanel.refresh()
                if (shellOf[imagePanel]?.isShowing == true) {
                    toast("Image panel - acts on the selected or latest image")
                }
            })),
            extended = imagePanel,
            gravity = Gravity.BOTTOM or Gravity.START,
            snapshotter = snapshotter)

        // ----- groups WITHOUT panels: inline button rows -----
        // Edit
        val editShell = buttonToolbar("Edit",
            listOf(
                iconChip(R.drawable.ic_undo, "Undo", { undoButton.performClick() }),
                iconChip(R.drawable.ic_redo, "Redo", { redoButton.performClick() }),
                iconChip(R.drawable.ic_clear, "Del", {
                    if (canvasView.viewModel.document.selection.deleteSelection()) {
                        canvasView.requestSceneRepaint()
                        toast("Selection deleted (Undo restores it)")
                    }
                }),
                iconChip(R.drawable.ic_flip, "Flip", { withSelection { it.flip(horizontal = true) } }),
                iconChip(R.drawable.ic_rot90, "Rot90", { withSelection { it.rotate(90f) } }),
                iconChip(R.drawable.ic_clear, "Clear", { confirmClearLayer() }),
            ),
            gravity = Gravity.BOTTOM or Gravity.START,
            snapshotter = snapshotter,
            kind = FloatingPanel.Kind.DOCK)
        // View/Export — anchored top-right so it never collides with Edit dock
        val viewShell = buttonToolbar("View/Export",
            listOf(
                iconChip(R.drawable.ic_demo, "Demo", {
                    canvasView.runDemo()
                    toast("Demo: flying through nested rings - touch to cancel")
                }),
                iconChip(R.drawable.ic_pins, "Pins", { togglePanel(pinsPanel); pinsPanel.refresh() }),
                iconChip(R.drawable.ic_zoom_out, "−", { canvasView.zoomBy(0.5f) }),
                iconChip(R.drawable.ic_zoom_in, "+", { canvasView.zoomBy(2f) }),
                iconChip(R.drawable.ic_import, "Import", { importLauncher.launch("image/*") }),
                iconChip(R.drawable.ic_png, "PNG", { exportPng() }),
                iconChip(R.drawable.ic_video, "Video", { exportVideo() }),
            ),
            gravity = Gravity.TOP or Gravity.END,
            snapshotter = snapshotter,
            kind = FloatingPanel.Kind.DOCK)

        // Place all shells in the overlay. Panel groups anchor bottom/start
        // (except Layers which is end-anchored); inline docks anchor bottom.
        // The dock inset accounts for the bottom-anchored shells' height.
        // (Each FloatingPanel manages its own rest position via SharedPreferences.)
    }

    /** A grouped toolbar: FloatingPanel with a header (label + chevron) and a
     *  content area that swaps between compact buttons and the extended panel. */
    private fun groupedToolbar(
        key: String,
        label: String,
        compact: List<View>,
        extended: View?,
        gravity: Int,
        snapshotter: BackdropSnapshotter,
    ): FloatingPanel {
        val shell = FloatingPanel(this, key, { p -> snapshotter.snapshot(p) })
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Header row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(GlassStyle.TEXT_PRIMARY)
            textSize = 11f
            setAllCaps(true)
        }
        val chevron = Button(this).apply {
            text = "▾"
            textSize = 12f
            GlassStyle.button(this)
            Springs.attachPressFeedback(this)
        }
        header.addView(labelView, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(chevron, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Content frame: holds compact buttons OR extended panel
        val content = FrameLayout(this)
        container.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        var extendedShown = false

        fun showCompact() {
            content.removeAllViews()
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setGravity(Gravity.CENTER_VERTICAL)
            }
            for (v in compact) row.addView(v)
            content.addView(row)
            chevron.text = "▾"
            extendedShown = false
        }

        fun showExtended() {
            content.removeAllViews()
            if (extended != null) content.addView(extended)
            chevron.text = "▴"
            extendedShown = true
        }

        chevron.setOnClickListener {
            if (extendedShown) showCompact() else showExtended()
        }
        // Tapping the label also extends
        labelView.setOnClickListener {
            if (!extendedShown) showExtended()
        }

        showCompact()
        shell.setContent(container)

        // Register in shellOf so togglePanel / dismiss works
        // (we register the panel view itself, not the shell)
        if (extended is BrushPanel) shellOf[extended] = shell
        if (extended is LayersPanel) shellOf[extended] = shell
        if (extended is PinsPanel) shellOf[extended] = shell
        if (extended is ShapesPanel) shellOf[extended] = shell
        if (extended is BlendPanel) shellOf[extended] = shell
        if (extended is ImagePanel) shellOf[extended] = shell

        overlay.addShell(shell, gravity,
            bottomInsetPx = if (gravity and Gravity.BOTTOM != 0) {
                (resources.displayMetrics.density * 14).toInt()
            } else 0)

        return shell
    }

    /** A simple inline button toolbar (no panel): a FloatingPanel with a row of chips. */
    private fun buttonToolbar(
        key: String,
        buttons: List<View>,
        gravity: Int,
        snapshotter: BackdropSnapshotter,
        kind: FloatingPanel.Kind = FloatingPanel.Kind.PANEL,
    ): FloatingPanel {
        val shell = FloatingPanel(this, key, { p -> snapshotter.snapshot(p) }, kind = kind)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.CENTER_VERTICAL)
        }
        for (v in buttons) container.addView(v)
        shell.setContent(container)
        overlay.addShell(shell, gravity,
            bottomInsetPx = if (gravity and Gravity.BOTTOM != 0) {
                (resources.displayMetrics.density * 14).toInt()
            } else 0)
        return shell
    }

    /** An icon chip for grouped toolbars: vector icon on top, small label below. */
    private fun iconChip(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val icon = ImageButton(this).also {
            it.setImageResource(iconRes)
            GlassStyle.iconButton(it)
            Springs.attachPressFeedback(it)
            it.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        GlassStyle.pressTap(this@MainActivity)
                        it.setColorFilter(Brand.BLUE)
                        false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        it.setColorFilter(GlassStyle.TEXT_PRIMARY)
                        false
                    }
                    else -> false
                }
            }
            it.setOnClickListener { onClick(); GlassStyle.selectionTap(this@MainActivity) }
            val size = (density * 24).toInt()
            it.layoutParams = LinearLayout.LayoutParams(size, size)
            it.setPadding(0, 0, 0, (density * 2).toInt())
        }
        row.addView(icon)
        val text = TextView(this).apply {
            text = label
            textSize = 9f
            setTextColor(GlassStyle.TEXT_SECONDARY)
            setAllCaps(true)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(text)
        return row
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
            colorSwatch.setBackgroundColor(canvasView.viewModel.currentBrush.color)
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
        if (::lazyButton.isInitialized) {
            // P4.4: the Lazy section button mirrors the stabilizer state
            lazyButton.text = if (canvasView.viewModel.lazyEnabled) "Lazy ON" else "Lazy"
        }
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
