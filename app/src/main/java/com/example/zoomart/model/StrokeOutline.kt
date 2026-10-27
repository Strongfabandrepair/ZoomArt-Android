package com.example.zoomart.model

import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.atan2

/** Minimal path-building abstraction so outline geometry is JVM-testable. */
interface PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun close()
}

/**
 * Phase 5: builds a variable-width stroke as ONE closed filled outline
 * (round caps drawn by the caller): N segment draw calls -> 1 path fill.
 * Per-sample tangent = central difference of neighbours; outline vertices
 * sit at +/- halfWidth along the normal, so width follows pressure.
 *
 * P3.2: a positive [taper] fraction smoothsteps the effective width down
 * over the first/last fraction of the stroke's ARCLENGTH (LazyBrush taper),
 * computed from cumulative segment lengths so the ramp is zoom-honest.
 */
object StrokeOutline {

    /** Returns (left, right) edge vertex chains, or null for degenerate input. */
    fun outlineVertices(
        points: List<StrokePoint>,
        brush: BrushSpec,
        taper: Float = 0f,
    ): Pair<List<FloatArray>, List<FloatArray>>? {
        if (points.size < 2) return null
        val left = ArrayList<FloatArray>(points.size)
        val right = ArrayList<FloatArray>(points.size)
        val taperFactors = if (taper > 0f) LazyBrush.taperFactors(points, taper) else null
        var lastNx = 0f
        var lastNy = 1f
        for (i in points.indices) {
            val prev = points[maxOf(i - 1, 0)]
            val next = points[minOf(i + 1, points.size - 1)]
            val tx = next.x - prev.x
            val ty = next.y - prev.y
            val len = hypot(tx, ty)
            var nx: Float
            var ny: Float
            if (len >= 1e-6f) {
                nx = -ty / len
                ny = tx / len
                lastNx = nx
                lastNy = ny
            } else {
                nx = lastNx // duplicate point: keep previous normal
                ny = lastNy
            }
            if (brush.angleDeg != 0f) {
                // rotate the nib normal for flat calligraphy ribbons
                val a = Math.toRadians(brush.angleDeg.toDouble())
                val c = Math.cos(a).toFloat()
                val s = Math.sin(a).toFloat()
                val rx = nx * c - ny * s
                val ry = nx * s + ny * c
                nx = rx
                ny = ry
            }
            var half = brush.widthForPressure(points[i].pressure01) * 0.5f
            if (brush.angleDeg != 0f && len >= 1e-6f) {
                // Calligraphy: width varies with stroke direction relative to nib
                // angle. Perpendicular to nib = full width; parallel to nib =
                // minimum width (the nib's thin edge).
                val strokeAngle = atan2(ty, tx)
                val nibAngle = Math.toRadians(brush.angleDeg.toDouble()).toFloat()
                val dirFactor = abs(sin(strokeAngle - nibAngle))
                val minDirRatio = 0.25f
                half *= (minDirRatio + (1f - minDirRatio) * dirFactor)
            }
            if (taperFactors != null) half *= taperFactors[i]
            left.add(floatArrayOf(points[i].x + nx * half, points[i].y + ny * half))
            right.add(floatArrayOf(points[i].x - nx * half, points[i].y - ny * half))
        }
        return left to right
    }

    fun buildPath(
        points: List<StrokePoint>,
        brush: BrushSpec,
        sink: PathSink,
        taper: Float = 0f,
    ): Boolean {
        val (left, right) = outlineVertices(points, brush, taper) ?: return false
        sink.moveTo(left[0][0], left[0][1])
        for (i in 1 until left.size) sink.lineTo(left[i][0], left[i][1])
        for (i in right.size - 1 downTo 0) sink.lineTo(right[i][0], right[i][1])
        sink.close()
        return true
    }
}
