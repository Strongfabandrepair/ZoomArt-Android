package com.example.zoomart.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.7.0 (docs/UI_STYLE_SPEC.md section 5): the pure hue-ring geometry
 * behind the restyled color wheel — angle -> hue, radius -> saturation,
 * both directions, plus the annulus mapping. Kept free of android.* so
 * the math is JVM-unit-testable (WheelGeometryTest).
 *
 * CONVENTION (fixes the 0.6.0 marker/touch 90-degree disagreement): the
 * hue angle uses plain math angles in SCREEN coordinates — 0deg = east
 * (dx>0, dy=0) = RED, increasing CLOCKWISE (because screen y grows down),
 * 90deg = south = 75% hue, 180deg = west = CYAN, 270deg = north = PURPLE.
 * Touch mapping and marker rendering share the SAME functions, so a touch
 * and its marker can never disagree again.
 */
object WheelGeometry {

    /** Hue [0,360) from a pointer delta in view coords (y grows down). */
    fun hueFromDelta(dx: Float, dy: Float): Float {
        var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        if (deg < 0) deg += 360.0
        return deg.toFloat() % 360f
    }

    /** Marker offset (dx, dy) for a hue at radius [r] — the exact inverse
     *  of [hueFromDelta] (same convention, no phase shift). */
    fun deltaFromHue(hue: Float, r: Float): FloatArray {
        val rad = Math.toRadians((hue % 360f).toDouble())
        return floatArrayOf(
            (r * cos(rad)).toFloat(),
            (r * sin(rad)).toFloat(),
        )
    }

    /** Saturation from a radial distance clamped to the annulus
     *  [inner, outer]: at the inner edge sat=0 (muted), at the rim sat=1. */
    fun saturationFromRadius(dist: Float, inner: Float, outer: Float): Float {
        if (outer <= inner) return 0f
        return ((dist - inner) / (outer - inner)).coerceIn(0f, 1f)
    }

    /** Inverse of [saturationFromRadius]: the radius a marker sits at. */
    fun radiusFromSaturation(sat: Float, inner: Float, outer: Float): Float {
        if (outer <= inner) return inner
        return inner + (outer - inner) * sat.coerceIn(0f, 1f)
    }

    /** The measured muted ring value (color-wheel.jpg: ring samples render
     *  at S=1.0, V=0.65..0.71 — the muted Infinite-Design wheel). */
    const val RING_VALUE = 0.65f

    /** Center-swatch radius as a fraction of the ring OUTER radius
     *  (measured: swatch r~23px vs ring outer 115px on a 353px panel). */
    const val CENTER_SWATCH_FRACTION = 0.22f

    /** Ring inner radius as a fraction of the outer radius (measured
     *  r~64..115px -> 0.56). */
    const val RING_INNER_FRACTION = 0.56f

    /** Round-trip check used by the tests: mapping a delta to a hue and
     *  back must return the direction of the original delta. */
    fun roundTripPreservesDirection(dx: Float, dy: Float, r: Float): Boolean {
        val hue = hueFromDelta(dx, dy)
        val back = deltaFromHue(hue, r)
        val angleError = abs(
            atan2(back[1].toDouble(), back[0].toDouble()) -
            atan2(dy.toDouble(), dx.toDouble()))
        val wrapped = if (angleError > PI) 2 * PI - angleError else angleError
        return wrapped < 1e-3
    }
}
