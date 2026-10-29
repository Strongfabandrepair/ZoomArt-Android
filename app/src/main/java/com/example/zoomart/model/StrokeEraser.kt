package com.example.zoomart.model

import kotlin.math.*

/** Clips stroke segments against the swept eraser, including unsampled intersections. */
object StrokeEraser {
    fun erase(stroke: StrokeData, centers: List<StrokePoint>, radius: Float): List<StrokeData> {
        if (centers.isEmpty() || radius <= 0f || !radius.isFinite()) return listOf(stroke)
        val points = stroke.points
        if (points.isEmpty()) return listOf(stroke)
        val pieces = mutableListOf<StrokeData>()
        var current: StrokeData? = null
        var changed = false
        fun append(p: StrokePoint) {
            if (current == null) current = StrokeData(stroke.brush).also {
                it.levelId = stroke.levelId; it.taper = stroke.taper; pieces.add(it)
            }
            if (current!!.points.lastOrNull() != p) current!!.addPoint(p)
        }
        for (i in 0 until max(1, points.size - 1)) {
            val a = points[i]; val b = points[min(i + 1, points.lastIndex)]
            val dx = b.x.toDouble() - a.x; val dy = b.y.toDouble() - a.y
            // Conservative envelope for variable-width ink: avoid un-erased edge slivers.
            val r = radius + max(stroke.brush.widthForPressure(a.pressure01),
                stroke.brush.widthForPressure(b.pressure01)) * 0.5
            val cuts = mutableListOf<Pair<Double, Double>>()
            fun interval(lo: Double, hi: Double) {
                val l = max(0.0, lo); val h = min(1.0, hi)
                if (h > l) cuts.add(l to h)
            }
            fun circle(c: StrokePoint) {
                val x = a.x.toDouble() - c.x; val y = a.y.toDouble() - c.y
                val aa = dx * dx + dy * dy
                if (aa == 0.0) { if (x*x+y*y <= r*r) interval(0.0, 1.0); return }
                val bb = x*dx + y*dy; val cc = x*x+y*y-r*r
                val d = bb*bb-aa*cc
                if (d > 0) { val root = sqrt(d); interval((-bb-root)/aa, (-bb+root)/aa) }
            }
            centers.forEach(::circle)
            for (j in 1 until centers.size) {
                val c = centers[j-1]; val e = centers[j]
                val ex = e.x.toDouble()-c.x; val ey = e.y.toDouble()-c.y
                val length = hypot(ex, ey)
                if (length < 1e-12) continue
                val ux = ex/length; val uy = ey/length
                val x = a.x.toDouble()-c.x; val y = a.y.toDouble()-c.y
                var lo = 0.0; var hi = 1.0
                fun slab(start: Double, delta: Double, low: Double, high: Double): Boolean {
                    if (abs(delta) < 1e-15) return start in low..high
                    val t0 = (low-start)/delta; val t1 = (high-start)/delta
                    lo = max(lo, min(t0,t1)); hi = min(hi,max(t0,t1))
                    return hi > lo
                }
                if (slab(x*ux+y*uy, dx*ux+dy*uy, 0.0, length) &&
                    slab(-x*uy+y*ux, -dx*uy+dy*ux, -r, r)) interval(lo,hi)
            }
            fun at(t: Double) = StrokePoint((a.x+dx*t).toFloat(), (a.y+dy*t).toFloat(),
                (a.pressure01+(b.pressure01-a.pressure01)*t).toFloat())
            if (cuts.isEmpty()) { append(a); append(b); continue }
            changed = true
            var cursor = 0.0
            for ((lo,hi) in cuts.sortedBy { it.first }) {
                if (hi <= cursor) continue
                if (lo > cursor) { append(at(cursor)); append(at(lo)) }
                current = null
                cursor = max(cursor,hi)
            }
            if (cursor < 1.0) { append(at(cursor)); append(b) }
        }
        return if (!changed) listOf(stroke) else pieces.filter { it.points.size >= 2 }
    }
}
