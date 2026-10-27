package com.example.zoomart.ui.glass

/**
 * U1 (UI overhaul): pure geometry math for the floating-panel system.
 *
 * Kept free of any android.* dependency so the clamping and auto-hide
 * slide vectors are JVM-unit-testable. All values are raw pixels in the
 * OverlayLayer's coordinate space (the same space the panels translate in).
 */
object PanelGeometry {

    /** How much of a panel must stay reachable on-screen after a drag (px). */
    const val MIN_VISIBLE_PX = 56f

    /**
     * Clamps a panel's top-left offset (x, y) inside its parent so the panel
     * can be dragged mostly off-screen but always keeps [MIN_VISIBLE_PX]
     * grabbable on both axes. Degenerate (not-yet-laid-out) parents return
     * the input untouched so early drag calls never zero positions.
     */
    fun clampOffset(
        x: Float,
        y: Float,
        parentW: Int,
        parentH: Int,
        panelW: Int,
        panelH: Int,
        minVisible: Float = MIN_VISIBLE_PX,
    ): FloatArray {
        if (parentW <= 0 || parentH <= 0) return floatArrayOf(x, y)
        val w = if (panelW <= 0) minVisible.toInt() else panelW
        val h = if (panelH <= 0) minVisible.toInt() else panelH
        val minX = -(w - minVisible)
        val maxX = parentW - minVisible
        val minY = -(h - minVisible)
        val maxY = parentH - minVisible
        return floatArrayOf(x.coerceIn(minX, maxX), y.coerceIn(minY, maxY))
    }

    /**
     * Auto-hide slide vector: the translation delta that pushes the panel
     * ~[outFraction] of its own size toward the NEAREST screen edge, leaving
     * a grabbable sliver on-screen (the spec's "out ~80% of panel size").
     * Ties prefer the horizontal edges (panels float top/bottom mostly).
     */
    fun hideVector(
        panelX: Float,
        panelY: Float,
        panelW: Float,
        panelH: Float,
        parentW: Float,
        parentH: Float,
        outFraction: Float = 0.8f,
    ): FloatArray {
        val cx = panelX + panelW / 2f
        val cy = panelY + panelH / 2f
        val toLeft = cx
        val toRight = if (parentW > 0) parentW - cx else Float.MAX_VALUE
        val toTop = cy
        val toBottom = if (parentH > 0) parentH - cy else Float.MAX_VALUE
        val dLeft = if (panelW > 0) toLeft else Float.MAX_VALUE
        val dRight = if (panelW > 0) toRight else Float.MAX_VALUE
        val dTop = if (panelH > 0) toTop else Float.MAX_VALUE
        val dBottom = if (panelH > 0) toBottom else Float.MAX_VALUE
        val min = minOf(dLeft, dRight, dTop, dBottom)
        val f = outFraction.coerceIn(0f, 1f)
        return when (min) {
            dLeft -> floatArrayOf(-panelW * f, 0f)
            dRight -> floatArrayOf(panelW * f, 0f)
            dTop -> floatArrayOf(0f, -panelH * f)
            else -> floatArrayOf(0f, panelH * f)
        }
    }
}
