package com.example.zoomart.model

import kotlin.math.hypot

/**
 * P3.2 — Lazy brush / stabilizer (docs/REVIEW_2026-10-18.md LAZY BRUSH
 * section).
 *
 * Pure, JVM-testable rope model: the BRUSH POINT trails the PEN by at most
 * `radiusScreen` screen pixels. `feed` implements the classic rope: when the
 * pen is inside the radius the brush holds still; when it escapes, the
 * brush moves just enough along the pen direction so the distance is
 * EXACTLY the radius. Radius 0 degenerates to the raw pen (test-pinned).
 *
 * The View feeds SCREEN coordinates (the rope is a screen-space feel), the
 * ViewModel converts the brush point into the camera level's local frame
 * per sample, so stroke density stays correct at any zoom/level. While a
 * lazy gesture runs, the View draws the overlay: a thin line from the pen
 * tip to the brush point, a small filled circle at the brush point, and a
 * faint ring of the rope radius (screen space, after the scene).
 */
class LazyBrush(var radiusScreen: Float = 0f) {
    enum class Mode { ROPE, WEIGHTED, FINISH }
    var mode = Mode.ROPE

    /** Current brush point in SCREEN px (starts at the pen). */
    var brushX = 0f
        private set
    var brushY = 0f
        private set

    /** True between [begin] and [end] (a lazy gesture is in flight). */
    var active = false
        private set

    /** Anchors the brush point at the pen for a new gesture. */
    fun begin(penX: Float, penY: Float) {
        brushX = penX
        brushY = penY
        active = true
    }

    /**
     * Rope step: returns the new brush point [x, y] (screen px) after the
     * pen moved to (penX, penY). Pen inside the radius -> the brush point
     * is returned unchanged (static); pen at any distance beyond -> the
     * brush sits at exactly `radius` along the pen direction; radius <= 0
     * -> the brush point IS the pen.
     */
    fun feed(penX: Float, penY: Float): FloatArray {
        if (mode == Mode.FINISH) { brushX=penX; brushY=penY; return floatArrayOf(penX,penY) }
        if (mode == Mode.WEIGHTED && radiusScreen > 0f) {
            val alpha=1f/(1f+radiusScreen/12f)
            brushX+=(penX-brushX)*alpha; brushY+=(penY-brushY)*alpha
            return floatArrayOf(brushX,brushY)
        }
        val r = radiusScreen
        if (r <= 0f) {
            brushX = penX
            brushY = penY
            return floatArrayOf(penX, penY)
        }
        val dx = penX - brushX
        val dy = penY - brushY
        val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (d <= r) return floatArrayOf(brushX, brushY)
        val t = (d - r) / d
        brushX += dx * t
        brushY += dy * t
        return floatArrayOf(brushX, brushY)
    }

    fun end() {
        active = false
    }

    companion object {
        /** Taper end fraction: width ramps over the first/last 10% of the
         *  stroke's arclength (smoothstep), reaching ~0 at the very ends. */
        const val TAPER_FRACTION = 0.1f

        /** Samples emitted per quadratic segment in [smoothPoints]. */
        const val SMOOTH_SAMPLES = 4

        /**
         * Midpoint quadratic smoothing (the "quadTo" feel): the smoothed
         * curve through points p0..pn consists of
         *   - a linear half-segment p0 -> mid(p0,p1),
         *   - for each interior pi: a quadratic Bezier from mid(p(i-1),pi)
         *     with control pi to mid(pi,p(i+1)),
         *   - a linear half-segment mid(p(n-1),pn) -> pn.
         * Each quadratic is sampled [samples] times; pressure carried from
         * the segment's control point (pressure cadence = raw points).
         * Degenerate inputs (< 2 points) return the input unchanged.
         */
        fun smoothPoints(points: List<StrokePoint>, samples: Int = SMOOTH_SAMPLES): List<StrokePoint> {
            if (points.size < 2) return points
            val out = ArrayList<StrokePoint>(points.size * samples)
            fun mid(a: StrokePoint, b: StrokePoint) =
                StrokePoint((a.x + b.x) / 2f, (a.y + b.y) / 2f, (a.pressure01 + b.pressure01) / 2f)

            val m0 = mid(points[0], points[1])
            out.add(points[0])
            for (s in 1..samples) {
                val t = s.toFloat() / samples
                out.add(StrokePoint(
                    points[0].x + (m0.x - points[0].x) * t,
                    points[0].y + (m0.y - points[0].y) * t,
                    points[0].pressure01 + (m0.pressure01 - points[0].pressure01) * t,
                ))
            }
            for (i in 1 until points.size - 1) {
                val m1 = mid(points[i - 1], points[i])
                val m2 = mid(points[i], points[i + 1])
                val c = points[i]
                for (s in 0 until samples) {
                    val t = s.toFloat() / samples
                    // quadratic Bezier: (1-t)^2*M1 + 2(1-t)t*C + t^2*M2
                    val u = 1f - t
                    out.add(StrokePoint(
                        u * u * m1.x + 2f * u * t * c.x + t * t * m2.x,
                        u * u * m1.y + 2f * u * t * c.y + t * t * m2.y,
                        c.pressure01,
                    ))
                }
            }
            val mn2 = points[points.size - 2]
            val last = points[points.size - 1]
            val mLast = mid(mn2, last)
            for (s in 0 until samples) {
                val t = s.toFloat() / samples
                out.add(StrokePoint(
                    mLast.x + (last.x - mLast.x) * t,
                    mLast.y + (last.y - mLast.y) * t,
                    mLast.pressure01 + (last.pressure01 - mLast.pressure01) * t,
                ))
            }
            out.add(last)
            return out
        }

        /**
         * Width taper factor at arclength position [t] in [0..1] for a
         * stroke whose ends ramp over [fraction] of its length: 0 at the
         * very ends, smoothstepped to 1 within the ramp, 1 in the middle.
         * fraction <= 0 -> always 1 (no taper).
         */
        fun taperFactorAt(t: Float, fraction: Float = TAPER_FRACTION): Float {
            if (fraction <= 0f) return 1f
            val c = t.coerceIn(0f, 1f)
            val ramp = (minOf(c, 1f - c) / fraction).coerceIn(0f, 1f)
            return ramp * ramp * (3f - 2f * ramp) // smoothstep
        }

        /**
         * Per-point taper factors over a polyline, from cumulative
         * arclength (the honest "first/last ~10% of the stroke").
         */
        fun taperFactors(points: List<StrokePoint>, fraction: Float = TAPER_FRACTION): FloatArray {
            val f = FloatArray(points.size)
            if (points.isEmpty()) return f
            if (fraction <= 0f) {
                f.fill(1f)
                return f
            }
            val cum = DoubleArray(points.size)
            var total = 0.0
            for (i in 1 until points.size) {
                total += hypot(
                    (points[i].x - points[i - 1].x).toDouble(),
                    (points[i].y - points[i - 1].y).toDouble(),
                )
                cum[i] = total
            }
            if (total <= 1e-9) {
                f.fill(1f)
                return f
            }
            for (i in f.indices) {
                f[i] = taperFactorAt((cum[i] / total).toFloat(), fraction)
            }
            return f
        }
    }
}
