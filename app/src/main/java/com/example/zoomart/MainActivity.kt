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
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import com.example.zoomart.ui.ImagePanel
import com.example.zoomart.ui.LayersPanel
import com.example.zoomart.ui.PinsPanel
import com.example.zoomart.ui.ShapesPanel
import com.example.zoomart.ui.ZoomableCanvasView
import com.example.zoomart.ui.glass.BackdropSnapshotter
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
            { brush ->
                canvasView.viewModel.currentBrush = brush
                updateSwatch()
            },
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

        // U-overhaul: the toolbar is a floating glass dock (non-draggable:
        // it stays anchored bottom-center — documented in
        // docs/UI_OVERHAUL.md). It joins the same auto-hide pipeline.
        // It is built FIRST so bottom-anchored panels can be inset above
        // its measured height (the canvas is now full-bleed).
        val dock = FloatingPanel(this, "ToolbarDock",
            { p -> snapshotter.snapshot(p) }, kind = FloatingPanel.Kind.DOCK)
        dock.setContent(buildToolbar())
        val screenW = resources.displayMetrics.widthPixels
        dock.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(screenW, android.view.View.MeasureSpec.AT_MOST),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        val dockInset = dock.measuredHeight +
            (resources.displayMetrics.density * 14).toInt()

        fun host(key: String, gravity: Int, panel: View, bottomAnchored: Boolean = true): FloatingPanel {
            val shell = FloatingPanel(this, key, { p -> snapshotter.snapshot(p) })
            shell.setContent(panel)
            overlay.addShell(shell, gravity,
                bottomInsetPx = if (bottomAnchored) dockInset else 0)
            shellOf[panel] = shell
            return shell
        }
        host("BrushPanel", Gravity.BOTTOM or Gravity.START, brushPanel)
        host("LayersPanel", Gravity.END or Gravity.CENTER_VERTICAL, layersPanel, bottomAnchored = false)
        host("PinsPanel", Gravity.TOP or Gravity.START, pinsPanel, bottomAnchored = false)
        host("ShapesPanel", Gravity.BOTTOM or Gravity.START, shapesPanel)
        host("BlendPanel", Gravity.BOTTOM or Gravity.START, blendPanel)
        host("ImagePanel", Gravity.BOTTOM or Gravity.START, imagePanel)
        overlay.setDock(dock, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)

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

    private fun togglePanel(panel: View) {
        val shell = shellOf[panel] ?: return
        val wasOpen = shell.isShowing
        for (other in listOf(brushPanel, layersPanel, pinsPanel, shapesPanel, blendPanel, imagePanel)) {
            shellOf[other]?.dismiss()
        }
        if (wasOpen) {
            shell.dismiss()
        } else {
            shell.show()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        colorSwatch = Button(this).apply { setOnClickListener { showColorPicker() } }
        updateSwatch()

        undoButton = button("Undo") {
            canvasView.viewModel.undo()
            canvasView.requestSceneRepaint()
            refreshToolbar()
        }
        redoButton = button("Redo") {
            canvasView.viewModel.redo()
            canvasView.requestSceneRepaint()
            refreshToolbar()
        }
        val layersBtn = button("Layers") { togglePanel(layersPanel); layersPanel.refresh() }
        val brushBtn = button("Brush") { togglePanel(brushPanel) }
        val pinsBtn = button("Pins") { togglePanel(pinsPanel); pinsPanel.refresh() }
        val pickBtn = button("Pick") {
            canvasView.tool = ZoomableCanvasView.Tool.EYEDROPPER
            toast("Tap the canvas to sample a color")
        }
        val eraseBtn = button("Erase") { toggleErase() }
        val selBtn = button("Select") { toggleSelect() }
        val shapesBtn = button("Shapes") { toggleShapesTool() }
        val blendBtn = button("Blend") { toggleBlendTool() }
        val imageBtn = button("Image") {
            togglePanel(imagePanel)
            imagePanel.refresh()
            if (shellOf[imagePanel]?.isShowing == true) {
                toast("Image panel - acts on the selected or latest image")
            }
        }
        // P4.4 (audit UI): CLEAR the active layer behind a confirm dialog
        val clearBtn = button("Clear") { confirmClearLayer() }
        // P4.5 (audit UI): DEMO auto-draws nested rings + flies through them
        val demoBtn = button("Demo") {
            canvasView.runDemo()
            toast("Demo: flying through nested rings - touch to cancel")
        }
        // P4.4 (audit UI): the Lazy section - quick stabilizer toggle
        lazyButton = button("Lazy \u2713") { toggleLazy() }
        val delBtn = button("Del") {
            if (canvasView.viewModel.document.selection.deleteSelection()) {
                canvasView.requestSceneRepaint()
                toast("Selection deleted (Undo restores it)")
            }
        }
        val flipBtn = button("FlipH") { withSelection { it.flip(horizontal = true) } }
        val rotBtn = button("Rot90") { withSelection { it.rotate(90f) } }
        val zoomOutBtn = button("-") { canvasView.zoomBy(0.5f) }
        val zoomInBtn = button("+") { canvasView.zoomBy(2f) }
        val impBtn = button("Import") { importLauncher.launch("image/*") }
        val pngBtn = button("PNG") { exportPng() }
        val videoBtn = button("Video") { exportVideo() }

        // P4.4 (audit UI sections): Brushes / Blend / Shapes / Style / Lazy /
        // Layers / Image / Edit — lightweight divider labels between the
        // groups; one panel open at a time as before.
        fun addSection(label: String?, views: List<View>) {
            if (label != null) bar.addView(sectionLabel(label))
            for (v in views) {
                bar.addView(v, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        addSection("Brushes", listOf(brushBtn, eraseBtn))
        addSection("Blend", listOf(blendBtn))
        addSection("Shapes", listOf(shapesBtn))
        addSection("Style", listOf(colorSwatch, pickBtn))
        addSection("Lazy", listOf(lazyButton))
        addSection("Layers", listOf(layersBtn))
        addSection("Image", listOf(impBtn, imageBtn))
        addSection("Edit", listOf(selBtn, undoButton, redoButton, delBtn, flipBtn, rotBtn, clearBtn))
        // beyond the audit's eight sections: view/export utilities
        addSection(null, listOf(demoBtn, pinsBtn, zoomOutBtn, zoomInBtn, pngBtn, videoBtn))

        return HorizontalScrollView(this).apply {
            addView(bar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT))
            isHorizontalFadingEdgeEnabled = true
            // Real-device fix (Galaxy Tab S9 FE / One UI): the bottom toolbar
            // sits inside the system gesture zone, so horizontal swipes were
            // stolen as app-switch gestures. Exclude the dock (API 29+).
            post {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    systemGestureExclusionRects = listOf(
                        android.graphics.Rect(0, 0, width, height))
                }
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            GlassStyle.button(this) // U-overhaul: dark glass chip styling
            setOnClickListener { onClick() }
            Springs.attachPressFeedback(this) // U3: spring press feedback
        }

    /** P4.4/U-overhaul: small gray divider label between toolbar sections. */
    private fun sectionLabel(title: String): android.widget.TextView =
        android.widget.TextView(this).apply {
            text = title
            textSize = 10f
            setTextColor(GlassStyle.TEXT_SECONDARY)
            gravity = Gravity.CENTER_VERTICAL
            val padH = (resources.displayMetrics.density * 6).toInt()
            setPadding(padH, 0, padH, 0)
            contentDescription = "section " + title
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

    private fun toggleErase() {
        canvasView.tool =
            if (canvasView.tool == ZoomableCanvasView.Tool.ERASE) ZoomableCanvasView.Tool.DRAW
            else ZoomableCanvasView.Tool.ERASE
        toast(
            if (canvasView.tool == ZoomableCanvasView.Tool.ERASE)
                "Eraser ON - drag over strokes (size in Brush panel)"
            else "Back to drawing"
        )
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
        ColorPickerDialog(this, canvasView.viewModel.currentBrush.color) { color ->
            canvasView.viewModel.currentBrush =
                canvasView.viewModel.currentBrush.copy(color = color)
            brushPanel.syncFrom(canvasView.viewModel.currentBrush)
            updateSwatch()
        }.show()
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
            lazyButton.text = if (canvasView.viewModel.lazyEnabled) "Lazy \u2713" else "Lazy \u2717"
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
