package com.example.zoomart

import com.example.zoomart.ui.glass.PanelGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U1 (UI overhaul): pure JVM tests for the floating-panel drag math —
 * offset clamping (the drag-end guarantee) and the auto-hide slide vector
 * (toward the NEAREST screen edge, ~80% of the panel's own size).
 */
class PanelGeometryTest {

    // ---------------- clamping ----------------

    @Test
    fun clampKeepsAMinimumSliverReachableOnEveryEdge() {
        val minV = 56f
        // pushed fully past the LEFT edge -> only minVisible stays on-screen
        var c = PanelGeometry.clampOffset(-500f, 100f, 1080, 2340, 300, 400, minV)
        assertEquals(-(300f - minV), c[0], 0.001f)
        assertEquals(100f, c[1], 0.001f)

        // past the RIGHT edge
        c = PanelGeometry.clampOffset(2000f, 100f, 1080, 2340, 300, 400, minV)
        assertEquals(1080f - minV, c[0], 0.001f)

        // past the TOP edge
        c = PanelGeometry.clampOffset(100f, -900f, 1080, 2340, 300, 400, minV)
        assertEquals(-(400f - minV), c[1], 0.001f)

        // past the BOTTOM edge
        c = PanelGeometry.clampOffset(100f, 5000f, 1080, 2340, 300, 400, minV)
        assertEquals(2340f - minV, c[1], 0.001f)
    }

    @Test
    fun clampLeavesInBoundsOffsetsUntouched() {
        val c = PanelGeometry.clampOffset(120f, 340f, 1080, 2340, 300, 400, 56f)
        assertEquals(120f, c[0], 0.001f)
        assertEquals(340f, c[1], 0.001f)
    }

    @Test
    fun clampDegenerateParentReturnsInput() {
        // not-yet-laid-out parents must never zero a dragged position
        val c = PanelGeometry.clampOffset(70f, 30f, 0, 0, 0, 0, 56f)
        assertEquals(70f, c[0], 0.001f)
        assertEquals(30f, c[1], 0.001f)
    }

    // ---------------- auto-hide slide vector ----------------

    @Test
    fun hideVectorSlidesTowardTheNearestHorizontalEdge() {
        // panel hugging the LEFT edge -> slides LEFT by 80% of its width
        var v = PanelGeometry.hideVector(12f, 800f, 300f, 400f, 1080f, 2340f)
        assertEquals(-300f * 0.8f, v[0], 0.001f)
        assertEquals(0f, v[1], 0.001f)

        // panel hugging the RIGHT edge -> slides RIGHT
        v = PanelGeometry.hideVector(1080f - 312f, 800f, 300f, 400f, 1080f, 2340f)
        assertEquals(300f * 0.8f, v[0], 0.001f)
        assertEquals(0f, v[1], 0.001f)
    }

    @Test
    fun hideVectorSlidesTowardTheNearestVerticalEdge() {
        // panel near the TOP -> slides UP by 80% of its height
        var v = PanelGeometry.hideVector(500f, 20f, 300f, 400f, 1080f, 2340f)
        assertEquals(0f, v[0], 0.001f)
        assertEquals(-400f * 0.8f, v[1], 0.001f)

        // panel near the BOTTOM (the dock) -> slides DOWN
        v = PanelGeometry.hideVector(500f, 2340f - 60f, 300f, 60f, 1080f, 2340f)
        assertEquals(0f, v[0], 0.001f)
        assertEquals(60f * 0.8f, v[1], 0.001f)
    }

    @Test
    fun hideVectorFractionClampsAndCenterTiePrefersHorizontal() {
        // outFraction is clamped 0..1
        val v = PanelGeometry.hideVector(12f, 800f, 300f, 400f, 1080f, 2340f, 5f)
        assertEquals(-300f, v[0], 0.001f)

        // dead center: distances tie at 540/1170 -> horizontal (left) wins
        val tie = PanelGeometry.hideVector(390f, 970f, 300f, 400f, 1080f, 2340f, 0.8f)
        assertEquals(-300f * 0.8f, tie[0], 0.001f)
        assertEquals(0f, tie[1], 0.001f)
    }
}
