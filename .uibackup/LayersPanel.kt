package com.example.zoomart.ui

import android.content.Context
import android.view.Gravity
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
        setBackgroundColor(0xF2FFFFFF.toInt())
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
            setTextColor(0xFF16181D.toInt())
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        chevron.apply {
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            contentDescription = "collapse layers"
            setOnClickListener {
                collapsed = !collapsed
                refresh()
            }
        }
        header.addView(chevron, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(listContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(Button(context).apply {
            text = "+ Add layer"
            setOnClickListener {
                doc().addLayerAboveActive()
                refresh()
                onChanged()
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun doc(): DrawingDocument = view.viewModel.document

    /** Small touch-friendly button (default Material buttons are 88dp wide). */
    private fun compactButton(label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            val d = resources.displayMetrics.density
            setPadding((d * 12).toInt(), (d * 4).toInt(), (d * 12).toInt(), (d * 4).toInt())
            setOnClickListener { onClick() }
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
                view.invalidate()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun refresh() {
        chevron.text = if (collapsed) "\u25BC" else "\u25B2" // down / up chevron
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

            row.addView(TextView(context).apply {
                text = (if (layer.locked) "\uD83D\uDD12 " else "") + layer.name +
                    if (layer.strokes.isEmpty()) "" else " (" + layer.strokes.size + ")"
                setTextColor(0xFF16181D.toInt())
                contentDescription = "rename " + layer.name
                setOnClickListener { showRenameDialog(index, layer) }
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            row.addView(CheckBox(context).apply {
                contentDescription = "visibility " + layer.name
                isChecked = layer.isVisible
                setOnClickListener {
                    layer.isVisible = isChecked
                    view.invalidate()
                    onChanged()
                }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            row.addView(compactButton(if (layer.locked) "Unlock" else "Lock") {
                doc().setLayerLocked(index, !layer.locked)
                refresh()
                view.invalidate()
                onChanged()
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            row.addView(compactButton("Dup") {
                doc().duplicateLayer(index)
                refresh()
                view.invalidate()
                onChanged()
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            row.addView(compactButton(blendLabel(layer.blend)) {
                doc().setLayerBlend(index, layer.blend.cycled())
                refresh()
                view.invalidate()
                onChanged()
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            row.addView(compactButton("Up") {
                doc().moveLayer(index, index - 1)
                refresh()
                view.invalidate()
                onChanged()
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            row.addView(compactButton("Dn") {
                doc().moveLayer(index, index + 1)
                refresh()
                view.invalidate()
                onChanged()
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            row.addView(compactButton("X") {
                doc().removeLayer(index)
                refresh()
                view.invalidate()
                onChanged()
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            listContainer.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            val opacityRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            opacityRow.addView(TextView(context).apply {
                text = "  opacity"
                setTextColor(0xFF8A8F98.toInt())
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            opacityRow.addView(SeekBar(context).apply {
                max = 100
                progress = (layer.opacity * 100f).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) {
                            layer.opacity = p / 100f // live preview, no undo entry
                            view.invalidate()
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
}
