package com.example.zoomart.model

/**
 * Vector eraser: instead of masking pixels, it cuts the points of a stroke
 * that fall within the eraser radius of the eraser's path, splitting the
 * stroke into surviving fragments. The result stays pure vector data, so
 * erased edges re-render crisply at any zoom level.
 */
object StrokeEraser {

    /**
     * Returns the fragments of [stroke] that survive the eraser path
     * [centers] at [radius] world units. Fragments with fewer than 2 points
     * are dropped (they would be invisible). Brushes are preserved.
     */
    fun erase(stroke: StrokeData, centers: List<StrokePoint>, radius: Float): List<StrokeData> {
        val r2 = radius * radius
        val fragments = mutableListOf<StrokeData>()
        var current: StrokeData? = null
        for (p in stroke.points) {
            var hit = false
            for (c in centers) {
                val dx = c.x - p.x
                val dy = c.y - p.y
                if (dx * dx + dy * dy <= r2) {
                    hit = true
                    break
                }
            }
            if (hit) {
                if (current != null && current.points.size >= 2) fragments.add(current)
                current = null
            } else {
                if (current == null) current = StrokeData(brush = stroke.brush)
                current!!.levelId = stroke.levelId
                current!!.addPoint(p)
            }
        }
        if (current != null && current.points.size >= 2) fragments.add(current)
        return fragments
    }
}
