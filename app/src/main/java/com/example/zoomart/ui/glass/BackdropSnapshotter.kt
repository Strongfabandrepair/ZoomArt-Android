package com.example.zoomart.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.example.zoomart.ui.ZoomableCanvasView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * U1 (UI overhaul): renders the canvas region BEHIND a floating panel
 * into a small bitmap, reusing the canvas's own drawScene render path
 * (the same one that paints the screen, the eyedropper and the exports).
 *
 * Performance contract: called ONLY on panel SHOW and drag END, at a
 * reduced scale (~1/8). Between snapshots the panel's backdrop is a
 * static bitmap — pan/zoom of the canvas below costs NOTHING extra per
 * frame (this is the "snapshot-on-show, not per frame" guarantee the
 * gfxinfo pass in docs/UI_OVERHAUL.md verifies).
 *
 * TRUE backdrop blur (View.setRenderEffect) blurs the view's OWN content,
 * so the blur target is this backdrop ImageView (snapshot + blur + dark
 * scrim on top = the frosted-glass look). API 31+ only; API 26-30 fall
 * back to scrim-only glass (FloatingPanel.refreshBackdrop tiering).
 */
class BackdropSnapshotter(private val canvasView: ZoomableCanvasView) {

    /**
     * Returns the canvas pixels behind [panel] at [scale] their size, or
     * null when the canvas is not laid out yet (headless tests, first
     * frames) — callers keep scrim-only glass in that case.
     */
    fun snapshot(panel: View, scale: Float = SNAPSHOT_SCALE): Bitmap? {
        if (canvasView.width <= 0 || canvasView.height <= 0) return null
        val left = (panel.left + panel.translationX).roundToInt()
        val top = (panel.top + panel.translationY).roundToInt()
        val l = left.coerceIn(0, canvasView.width - 1)
        val t = top.coerceIn(0, canvasView.height - 1)
        val r = (left + panel.width).coerceIn(l + 1, canvasView.width)
        val b = (top + panel.height).coerceIn(t + 1, canvasView.height)
        val w = max(1, ((r - l) * scale).roundToInt())
        val h = max(1, ((b - t) * scale).roundToInt())
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-l.toFloat(), -t.toFloat())
        canvasView.drawScene(canvas, includeInProgress = false)
        canvas.restore()
        return bmp
    }

    companion object {
        /** ~1/8 scale: enough for a blurred backdrop, cheap to render. */
        const val SNAPSHOT_SCALE = 0.125f
    }
}
