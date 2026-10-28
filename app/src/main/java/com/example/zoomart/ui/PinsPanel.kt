package com.example.zoomart.ui

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.zoomart.model.DrawingDocument
import com.example.zoomart.ui.glass.GlassStyle
import com.example.zoomart.ui.glass.Springs

/** Top panel listing bookmark pins: tap to fly there, X to remove. */
class PinsPanel(
    context: Context,
    private val view: ZoomableCanvasView,
    private val onChanged: () -> Unit,
) : LinearLayout(context) {

    private val listContainer = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        // U-overhaul: transparent glass body — the shell provides the scrim
        setBackgroundColor(0x00000000)
        val pad = (resources.displayMetrics.density * 10).toInt()
        setPadding(pad, pad, pad, pad)
        addView(TextView(context).apply {
            text = "Pins (long-press canvas to add)"
            setTextColor(GlassStyle.TEXT_SECONDARY)
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
                setTextColor(GlassStyle.TEXT_SECONDARY)
            })
            return
        }
        bookmarks.forEachIndexed { index, b ->
            val depth = doc().levelTree.byId(b.levelId)?.depth ?: 0
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val fly = Button(context).apply {
                text = if (depth == 0) {
                    b.label + "  (" + String.format("%.2f", b.scale) + "x)"
                } else {
                    b.label + "  (L" + depth + "·" + String.format("%.1f", b.scale) + "x)"
                }
                GlassStyle.button(this)
                setOnClickListener { view.animateToBookmark(b); Springs.pulse(this) }
            }
            Springs.attachPressFeedback(fly)
            row.addView(fly, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val del = Button(context).apply {
                text = "X"
                GlassStyle.button(this)
                setOnClickListener {
                    doc().bookmarks.removeAt(index)
                    view.requestSceneRepaint()
                    refresh()
                    onChanged()
                }
            }
            Springs.attachPressFeedback(del)
            row.addView(del, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            listContainer.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }
}
