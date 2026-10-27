package com.example.zoomart.ui

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.zoomart.model.DrawingDocument

/** Top panel listing bookmark pins: tap to fly there, X to remove. */
class PinsPanel(
    context: Context,
    private val view: ZoomableCanvasView,
    private val onChanged: () -> Unit,
) : LinearLayout(context) {

    private val listContainer = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xF2FFFFFF.toInt())
        val pad = (resources.displayMetrics.density * 10).toInt()
        setPadding(pad, pad, pad, pad)
        addView(TextView(context).apply {
            text = "Pins (double-tap canvas to add)"
            setTextColor(0xFF6B7075.toInt())
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(listContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refresh()
    }

    private fun doc(): DrawingDocument = view.viewModel.document

    fun refresh() {
        listContainer.removeAllViews()
        val bookmarks = doc().bookmarks
        if (bookmarks.isEmpty()) {
            listContainer.addView(TextView(context).apply {
                text = "(no pins yet)"
                setTextColor(0xFF9AA0A6.toInt())
            })
            return
        }
        bookmarks.forEachIndexed { index, b ->
            val depth = doc().levelTree.byId(b.levelId)?.depth ?: 0
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(Button(context).apply {
                text = if (depth == 0) {
                    b.label + "  (" + String.format("%.2f", b.scale) + "x)"
                } else {
                    b.label + "  (L" + depth + "·" + String.format("%.1f", b.scale) + "x)"
                }
                setOnClickListener { view.animateToBookmark(b) }
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(context).apply {
                text = "X"
                setOnClickListener {
                    doc().bookmarks.removeAt(index)
                    view.invalidate()
                    refresh()
                    onChanged()
                }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            listContainer.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }
}
