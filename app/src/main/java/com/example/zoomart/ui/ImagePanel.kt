package com.example.zoomart.ui

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.example.zoomart.model.DrawingDocument
import com.example.zoomart.model.ImageDisplayFilter
import com.example.zoomart.ui.glass.GlassStyle
import com.example.zoomart.ui.glass.Springs

/**
 * P4.3 Image panel — controls for the CURRENT image (the selected one when
 * the Select tool holds it, else the most recently placed image of the
 * active layer — see DrawingViewModel.currentImage):
 *
 *  - Transparency 0-100% (per-image alpha at draw time; live preview, ONE
 *    undo step per slider drag, like layer opacity);
 *  - Lock image (locked images ignore select/move — draw over them
 *    freely); undoable;
 *  - TRACING MODE: one tap sets alpha 40% + locks the image + creates a
 *    fresh layer above the image layer and makes it active — ONE undoable
 *    command;
 *  - Display filter cycle: None -> Grayscale -> High contrast — a
 *    render-time ColorMatrix on the image paint ONLY (the bitmap data is
 *    untouched; UI-state, not serialized, not undoable);
 *  - a persistent note about bitmap blur at deep zoom (the audit's UI
 *    requirement).
 *
 * Every control carries a contentDescription for uiautomator.
 */
class ImagePanel(
    context: Context,
    private val view: ZoomableCanvasView,
    private val onChanged: () -> Unit = {},
) : LinearLayout(context) {

    private lateinit var infoLine: TextView
    private lateinit var alphaBar: SeekBar
    private lateinit var lockToggle: Button
    private lateinit var traceButton: Button
    private lateinit var displayButton: Button

    /** Pre-drag alpha of the transparency slider (records the undo "before"). */
    private var pendingAlphaStart: Float? = null

    private fun vm() = view.viewModel
    private fun doc(): DrawingDocument = vm().document

    init {
        orientation = VERTICAL
        // U-overhaul: transparent glass body — the shell provides the scrim
        setBackgroundColor(0x00000000)
        val pad = (resources.displayMetrics.density * 10).toInt()
        setPadding(pad, pad, pad, pad)

        addView(TextView(context).apply {
            text = "Image"
            textSize = 16f
            setTextColor(GlassStyle.TEXT_PRIMARY)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // which image the controls act on (kept in sync by refresh())
        infoLine = TextView(context).apply {
            textSize = 12f
            setTextColor(GlassStyle.TEXT_SECONDARY)
            contentDescription = "image info"
        }
        addView(infoLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---- transparency 0-100% ----
        val alphaRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        alphaRow.addView(TextView(context).apply {
            text = "Opacity"
            setTextColor(GlassStyle.TEXT_PRIMARY)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.3f))
        alphaBar = SeekBar(context).apply {
            max = 100
            contentDescription = "image transparency slider"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        vm().currentImage()?.let { it.alpha = p / 100f } // live preview
                        view.requestSceneRepaint()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    pendingAlphaStart = vm().currentImage()?.alpha
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val img = vm().currentImage()
                    val before = pendingAlphaStart
                    pendingAlphaStart = null
                    // P4.3: ONE undoable step per drag (before -> released)
                    if (img != null && before != null) {
                        doc().setLayerImageAlpha(img, img.alpha, before)
                    }
                    onChanged()
                }
            })
        }
        alphaRow.addView(alphaBar, LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.7f))
        addView(alphaRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---- lock / tracing / display filter ----
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        lockToggle = Button(context).apply {
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            contentDescription = "image lock toggle"
            GlassStyle.button(this)
            setOnClickListener {
                val img = vm().currentImage()
                if (img == null) {
                    toastNoImage()
                } else {
                    doc().setImageLock(img, !img.locked)
                    refresh()
                    view.requestSceneRepaint()
                    onChanged()
                    Springs.pulse(this)
                }
            }
        }
        Springs.attachPressFeedback(lockToggle)
        row.addView(lockToggle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        traceButton = Button(context).apply {
            text = "Trace"
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            contentDescription = "image tracing mode"
            GlassStyle.button(this)
            setOnClickListener {
                val img = vm().currentImage()
                if (img == null) {
                    toastNoImage()
                } else {
                    vm().beginTracingMode(img)
                    refresh()
                    view.requestSceneRepaint()
                    onChanged()
                    Springs.pulse(this)
                    Toast.makeText(context,
                        "Tracing: image dimmed + locked, drawing on a new layer",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
        Springs.attachPressFeedback(traceButton)
        row.addView(traceButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        displayButton = Button(context).apply {
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            contentDescription = "image display filter"
            GlassStyle.button(this)
            setOnClickListener {
                val img = vm().currentImage()
                if (img == null) {
                    toastNoImage()
                } else {
                    img.displayFilter = img.displayFilter.cycled()
                    refresh()
                    view.requestSceneRepaint()
                    Springs.pulse(this)
                }
            }
        }
        Springs.attachPressFeedback(displayButton)
        row.addView(displayButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---- the persistent bitmap note (subtle) ----
        addView(TextView(context).apply {
            text = "Imported images are bitmaps — they blur when zoomed " +
                "far past their resolution. Drawn strokes stay sharp at any zoom."
            textSize = 10f
            setTextColor(GlassStyle.TEXT_SECONDARY)
            contentDescription = "bitmap note"
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        refresh()
    }

    private fun toastNoImage() {
        Toast.makeText(context,
            "No image yet — Import one first (or Select an image)",
            Toast.LENGTH_SHORT).show()
    }

    /** Syncs every control to the current image's state. */
    fun refresh() {
        val img = vm().currentImage()
        infoLine.text = when {
            img == null -> "No image yet — tap Import"
            doc().selection.selectedImages.contains(img) -> "Selected: " + img.path
            else -> img.path
        }
        alphaBar.isEnabled = img != null
        alphaBar.progress = ((img?.alpha ?: 1f) * 100f).toInt().coerceIn(0, 100)
        lockToggle.text = if (img?.locked == true) "Unlock" else "Lock"
        traceButton.isEnabled = img != null
        displayButton.text = when (img?.displayFilter ?: ImageDisplayFilter.NONE) {
            ImageDisplayFilter.NONE -> "Display: Off"
            ImageDisplayFilter.GRAYSCALE -> "Display: Gray"
            ImageDisplayFilter.HIGH_CONTRAST -> "Display: Contrast"
        }
        displayButton.isEnabled = img != null
    }
}
