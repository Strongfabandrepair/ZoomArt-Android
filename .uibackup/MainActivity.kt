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
import com.example.zoomart.export.PngExporter
import com.example.zoomart.export.ZoomVideoExporter
import com.example.zoomart.model.CrashGuard
import com.example.zoomart.model.DocumentCodec
import com.example.zoomart.ui.BlendPanel
import com.example.zoomart.ui.BrushPanel
import com.example.zoomart.ui.ColorPickerDialog
import com.example.zoomart.ui.ImagePanel
import com.example.zoomart.ui.LayersPanel
import com.example.zoomart.ui.PinsPanel
import com.example.zoomart.ui.ShapesPanel
import com.example.zoomart.ui.ZoomableCanvasView
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    lateinit var canvasView: ZoomableCanvasView
        private set

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
            canvasView.invalidate()
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

        val content = FrameLayout(this)
        content.addView(canvasView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addOverlay(content, brushPanel, Gravity.BOTTOM)
        addOverlay(content, layersPanel, Gravity.END or Gravity.CENTER_VERTICAL)
        addOverlay(content, pinsPanel, Gravity.TOP or Gravity.START)
        addOverlay(content, shapesPanel, Gravity.BOTTOM or Gravity.START)
        addOverlay(content, blendPanel, Gravity.BOTTOM or Gravity.START)
        addOverlay(content, imagePanel, Gravity.BOTTOM or Gravity.START)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildToolbar(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        canvasView.onStateChanged = { refreshToolbar() }
        canvasView.onEyedrop = { color ->
            canvasView.viewModel.currentBrush =
                canvasView.viewModel.currentBrush.copy(color = color)
            brushPanel.syncFrom(canvasView.viewModel.currentBrush)
            updateSwatch()
        }

        loadSession()
        refreshToolbar()
        mainHandler.postDelayed(autosaveRunnable, 15_000L)
    }

    private fun addOverlay(content: FrameLayout, panel: View, gravity: Int) {
        panel.visibility = View.GONE
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            gravity,
        )
        val m = (resources.displayMetrics.density * 12).toInt()
        lp.setMargins(m, m, m, m)
        content.addView(panel, lp)
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
            canvasView.invalidate()
            refreshToolbar()
        }
        redoButton = button("Redo") {
            canvasView.viewModel.redo()
            canvasView.invalidate()
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
            if (imagePanel.visibility == View.VISIBLE) {
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
                canvasView.invalidate()
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
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT))
            // Real-device fix (Galaxy Tab S9 FE / One UI): the bottom toolbar
            // sits inside the system gesture zone, so horizontal swipes were
            // stolen as app-switch gestures. Exclude the toolbar (API 29+).
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
            setOnClickListener { onClick() }
        }

    /** P4.4: small gray divider label between toolbar sections. */
    private fun sectionLabel(title: String): android.widget.TextView =
        android.widget.TextView(this).apply {
            text = title
            textSize = 10f
            setTextColor(0xFF8A8F98.toInt())
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
                    canvasView.invalidate()
                    toast("Layer cleared (Undo restores it)")
                }
                refreshToolbar()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun togglePanel(panel: View) {
        val target = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        for (other in listOf(brushPanel, layersPanel, pinsPanel, shapesPanel, blendPanel, imagePanel)) {
            other.visibility = View.GONE
        }
        panel.visibility = target
    }

    /**
     * P3.1: the Shapes toolbar button toggles the SHAPE tool; the panel
     * opens alongside. Tapping it again returns to plain drawing.
     */
    private fun toggleShapesTool() {
        if (canvasView.tool == ZoomableCanvasView.Tool.SHAPE) {
            canvasView.tool = ZoomableCanvasView.Tool.DRAW
            shapesPanel.visibility = View.GONE
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
            blendPanel.visibility = View.GONE
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
        canvasView.invalidate()
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
            canvasView.invalidate()
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
                canvasView.invalidate()
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
            canvasView.invalidate()
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
