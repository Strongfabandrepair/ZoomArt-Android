package com.example.zoomart.ui

import android.content.ClipData
import android.content.Context
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.zoomart.model.DrawingDocument
import com.example.zoomart.model.LayerBlend
import com.example.zoomart.model.LayerData
import com.example.zoomart.ui.glass.GlassStyle
import com.example.zoomart.ui.glass.Springs
import kotlin.math.abs

/**
 * Layers panel v2 (P2.4). Per row: active radio, tappable name (rename
 * dialog), visibility checkbox, LOCK toggle (locked rows show a padlock),
 * DUPLICATE, blend-mode cycle button (Normal -> Multiply -> Screen ->
 * Overlay), Up/Dn reorder buttons, delete, and an opacity slider whose
 * release records ONE undo step (pre-drag -> released value).
 *
 * The rows section collapses behind a header chevron. Every mutation goes
 * through an undoable DrawingDocument command (P2.1) so the toolbar's
 * Undo/Redo covers layer changes too.
 *
 * Reorder is Up/Dn buttons as the MVP; true long-press drag reorder is a
 * listed gap (docs/P2_LAYERS_V2.md).
 *
 * U-overhaul: dark-glass restyle (transparent body — the FloatingPanel
 * shell supplies the scrim; light text; glass buttons with spring press
 * feedback), the chevron collapse is spring-animated, and document
 * mutations REQUEST scene repaints via
 * [ZoomableCanvasView.requestSceneRepaint] — the decoupling rule: panels
 * never call invalidate() themselves.
 */
class LayersPanel(
    context: Context,
    private val view: ZoomableCanvasView,
    private val onChanged: () -> Unit,
) : LinearLayout(context) {

    private val listContainer = LinearLayout(context).apply { orientation = VERTICAL }
    private val chevron = Button(context)

    /** P2.4: collapse state of the rows section. */
    var collapsed = false
        private set

    /** True while the rows section is expanded (test/inspection hook). */
    val rowsVisible: Boolean get() = !collapsed

    /** Pre-drag opacity of the slider being dragged (records the undo step's "before"). */
    private var pendingOpacityStart: Float? = null

    init {
        orientation = VERTICAL
        // U-overhaul: transparent glass body — the shell provides the scrim
        setBackgroundColor(0x00000000)
        val pad = (resources.displayMetrics.density * 10).toInt()
        setPadding(pad, pad, pad, pad)

        // header: title + collapse/expand chevron
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "Layers"
            textSize = 16f
            setTextColor(GlassStyle.TEXT_PRIMARY)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        chevron.apply {
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            contentDescription = "collapse layers"
            GlassStyle.button(this)
            setOnClickListener {
                collapsed = !collapsed
                syncChevron()
                animateCollapse()
                Springs.pulse(this)
            }
        }
        Springs.attachPressFeedback(chevron)
        header.addView(chevron, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(listContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // P5 gap: long-press drag reorder for layers.
        listContainer.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DROP -> {
                    val fromIndex = (event.localState as? Int) ?: return@setOnDragListener false
                    val toIndex = layerIndexAtY(event.y)
                    if (fromIndex != toIndex && toIndex >= 0) {
                        doc().moveLayer(fromIndex, toIndex)
                        refresh()
                        view.requestSceneRepaint()
                        onChanged()
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> true
                else -> true
            }
        }

        val addButton = Button(context).apply {
            text = "+ Add layer"
            GlassStyle.button(this)
            setOnClickListener {
                doc().addLayerAboveActive()
                refresh()
                onChanged()
                Springs.pulse(this)
            }
        }
        Springs.attachPressFeedback(addButton)
        addView(addButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun doc(): DrawingDocument = view.viewModel.document

    /** P2.4/U3: chevron label; collapse/expand springs the rows section. */
    private fun syncChevron() {
        chevron.text = if (collapsed) "\u25BC" else "\u25B2" // down / up chevron
    }

    private fun animateCollapse() {
        if (collapsed) {
            Springs.fadeThenGone(listContainer)
        } else {
            Springs.fadeBackIn(listContainer)
        }
    }

    /** Small touch-friendly button (default Material buttons are 88dp wide). */
    private fun compactButton(label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            GlassStyle.button(this)
            val d = resources.displayMetrics.density
            setPadding((d * 12).toInt(), (d * 4).toInt(), (d * 12).toInt(), (d * 4).toInt())
            setOnClickListener { onClick(); Springs.pulse(this) }
        }

    private fun blendLabel(blend: LayerBlend): String =
        blend.name.lowercase().replaceFirstChar { it.uppercase() }

    /** P2.4: tap-the-name rename dialog (blank input keeps the old name). */
    private fun showRenameDialog(index: Int, layer: LayerData) {
        val input = EditText(context).apply {
            id = android.R.id.edit
            setText(layer.name)
            setSelection(text.length)
            hint = "Layer name"
            setSingleLine()
        }
        AlertDialog.Builder(context)
            .setTitle("Rename layer")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                doc().renameLayer(index, input.text.toString())
                refresh()
                view.requestSceneRepaint()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun refresh() {
        syncChevron()
        listContainer.visibility = if (collapsed) GONE else VISIBLE
        listContainer.removeAllViews()
        if (collapsed) return

        val document = doc()
        document.layers.forEachIndexed { index, layer ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (index == document.activeLayerIndex) {
                setBackgroundColor(0x152E66F6) // active sheet highlight
            }

            row.addView(RadioButton(context).apply {
                contentDescription = "activate " + layer.name
                isChecked = index == document.activeLayerIndex
                setOnClickListener {
                    document.setActiveLayer(index)
                    refresh()
                    onChanged()
                }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            // P5 gap: long-press on a row starts a drag reorder.
            row.setOnLongClickListener {
                row.tag = index
                val clip = ClipData("layer", arrayOf("text/plain"), ClipData.Item(""))
                row.startDragAndDrop(clip, View.DragShadowBuilder(row), row, 0)
                true
            }

            row.addView(TextView(context).apply {
                text = (if (layer.locked) "\uD83D\uDD12 " else "") + layer.name +
                    if (layer.strokes.isEmpty()) "" else " (" + layer.strokes.size + ")"
                setTextColor(GlassStyle.TEXT_PRIMARY)
                contentDescription = "rename " + layer.name
                setOnClickListener { showRenameDialog(index, layer) }
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            row.addView(CheckBox(context).apply {
                contentDescription = "visibility " + layer.name
                isChecked = layer.isVisible
                setOnClickListener {
                    layer.isVisible = isChecked
                    view.requestSceneRepaint()
                    onChanged()
                }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            row.addView(compactButton(if (layer.locked) "Unlock" else "Lock") {
                doc().setLayerLocked(index, !layer.locked)
                refresh()
                view.requestSceneRepaint()
                onChanged()
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            row.addView(compactButton("Dup") {
                doc().duplicateLayer(index)
                refresh()
                view.requestSceneRepaint()
                onChanged()
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            row.addView(compactButton(blendLabel(layer.blend)) {
                doc().setLayerBlend(index, layer.blend.cycled())
                refresh()
                view.requestSceneRepaint()
                onChanged()
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            row.addView(compactButton("Up") {
                doc().moveLayer(index, index - 1)
                refresh()
                view.requestSceneRepaint()
                onChanged()
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            row.addView(compactButton("Dn") {
                doc().moveLayer(index, index + 1)
                refresh()
                view.requestSceneRepaint()
                onChanged()
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            row.addView(compactButton("X") {
                doc().removeLayer(index)
                refresh()
                view.requestSceneRepaint()
                onChanged()
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            listContainer.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            val opacityRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            opacityRow.addView(TextView(context).apply {
                text = "  opacity"
                setTextColor(GlassStyle.TEXT_SECONDARY)
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            opacityRow.addView(SeekBar(context).apply {
                max = 100
                progress = (layer.opacity * 100f).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) {
                            layer.opacity = p / 100f // live preview, no undo entry
                            view.requestSceneRepaint()
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {
                        pendingOpacityStart = layer.opacity
                    }
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        val before = pendingOpacityStart
                        pendingOpacityStart = null
                        if (before != null) {
                            // P2.1: one undoable step per drag (before -> released)
                            doc().setLayerOpacity(index, layer.opacity, before)
                        }
                        onChanged()
                    }
                })
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            listContainer.addView(opacityRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    /** P5 gap: given a Y coordinate in listContainer space, return the index
     *  of the layer row closest to it (rows are at even child indices; opacity
     *  rows are at odd indices). */
    private fun layerIndexAtY(y: Float): Int {
        val count = listContainer.childCount
        var bestLayer = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until count step 2) {
            val child = listContainer.getChildAt(i)
            val center = (child.top + child.bottom) / 2f
            val dist = abs(y - center)
            if (dist < bestDist) {
                bestDist = dist
                bestLayer = i / 2
            }
        }
        return bestLayer.coerceIn(0, listContainer.childCount / 2 - 1)
    }
}
