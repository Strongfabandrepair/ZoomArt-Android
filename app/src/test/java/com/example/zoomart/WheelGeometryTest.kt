package com.example.zoomart

import com.example.zoomart.ui.WheelGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.7.0 R4: the hue-ring geometry contract (docs/UI_STYLE_SPEC.md
 * section 5). Pure JVM — no android.*. These tests pin the convention
 * that FIXES the 0.6.0 bug where the marker drew 90deg away from where
 * the touch sampled: touch and marker now share the same math.
 */
class WheelGeometryTest {

    @Test
    fun hueConventionIsEastRedClockwise() {
        // 0deg = east = red
        assertEquals(0f, WheelGeometry.hueFromDelta(1f, 0f), 1e-4f)
        // screen y grows DOWN, so +90deg (clockwise on screen) = south
        assertEquals(90f, WheelGeometry.hueFromDelta(0f, 1f), 1e-4f)
        assertEquals(180f, WheelGeometry.hueFromDelta(-1f, 0f), 1e-4f)
        assertEquals(270f, WheelGeometry.hueFromDelta(0f, -1f), 1e-4f)
    }

    @Test
    fun hueAlwaysWithinRange() {
        assertTrue(WheelGeometry.hueFromDelta(0f, 0f) in 0f..360f)
        // diagonal below the axis wraps into [0,360)
        val h = WheelGeometry.hueFromDelta(-0.001f, -1f)
        assertTrue("hue $h must be >= 0", h >= 0f && h < 360f)
    }

    @Test
    fun touchAndMarkerAgreeOnDirection() {
        // THE regression: 0.6.0 sampled hue at the touched point but drew
        // the marker 90deg away. Round-tripping must preserve direction.
        val probes = listOf(
            floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(-1f, -1f),
            floatArrayOf(0.7f, -0.7f), floatArrayOf(-0.2f, 0.98f),
        )
        for (d in probes) {
            assertTrue(
                "delta(${d[0]},${d[1]}) must round-trip",
                WheelGeometry.roundTripPreservesDirection(d[0], d[1], 10f))
        }
    }

    @Test
    fun markerDeltaIsTheExactInverseOfHue() {
        for (hue in intArrayOf(0, 47, 90, 133, 180, 225, 270, 315, 359)) {
            val d = WheelGeometry.deltaFromHue(hue.toFloat(), 42f)
            assertEquals(hue.toFloat(), WheelGeometry.hueFromDelta(d[0], d[1]), 1e-3f)
        }
    }

    @Test
    fun saturationMapsTheAnnulus() {
        // inner edge -> 0 (muted center side), rim -> 1 (vivid)
        assertEquals(0f, WheelGeometry.saturationFromRadius(56f, 56f, 100f), 1e-4f)
        assertEquals(1f, WheelGeometry.saturationFromRadius(100f, 56f, 100f), 1e-4f)
        assertEquals(0.5f, WheelGeometry.saturationFromRadius(78f, 56f, 100f), 1e-3f)
        // clamped outside the annulus
        assertEquals(0f, WheelGeometry.saturationFromRadius(3f, 56f, 100f), 1e-4f)
        assertEquals(1f, WheelGeometry.saturationFromRadius(999f, 56f, 100f), 1e-4f)
    }

    @Test
    fun radiusIsTheInverseOfSaturation() {
        for (s in floatArrayOf(0f, 0.25f, 0.5f, 0.9f, 1f)) {
            val r = WheelGeometry.radiusFromSaturation(s, 56f, 100f)
            assertEquals(s, WheelGeometry.saturationFromRadius(r, 56f, 100f), 1e-4f)
        }
        // degenerate annulus never divides by zero
        assertEquals(5f, WheelGeometry.radiusFromSaturation(0.5f, 5f, 5f), 1e-4f)
    }

    @Test
    fun measuredFractionsMatchTheReferenceSpec() {
        // pinned from color-wheel.jpg (docs/UI_STYLE_SPEC.md section 5):
        // ring V=0.65 muted, inner radius 0.56R, center swatch 0.22R.
        assertEquals(0.65f, WheelGeometry.RING_VALUE, 1e-4f)
        assertEquals(0.56f, WheelGeometry.RING_INNER_FRACTION, 1e-4f)
        assertEquals(0.22f, WheelGeometry.CENTER_SWATCH_FRACTION, 1e-4f)
        assertTrue("inner ring must clear the center swatch",
            WheelGeometry.RING_INNER_FRACTION > WheelGeometry.CENTER_SWATCH_FRACTION * 2f)
    }
}
