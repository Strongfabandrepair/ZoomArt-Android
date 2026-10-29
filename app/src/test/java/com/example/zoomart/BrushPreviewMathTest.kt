package com.example.zoomart

import com.example.zoomart.model.BrushPresets
import com.example.zoomart.ui.BrushPreviewMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.7.0 R3: the brush-preview geometry contract (docs/UI_STYLE_SPEC.md
 * section 4). Every preset in BrushPresets.all must expose a
 * deterministic, normalized characteristic stroke the BrushPanel can
 * render (ink normally, amber when selected — the brushes2.jpg language).
 */
class BrushPreviewMathTest {

    @Test
    fun everyPresetHasRenderableGeometry() {
        for ((name, _) in BrushPresets.all) {
            val segs = BrushPreviewMath.segments(name)
            assertTrue("$name must have segments", segs.isNotEmpty())
        }
    }

    @Test
    fun allGeometryIsNormalized() {
        for ((name, _) in BrushPresets.all) {
            for (s in BrushPreviewMath.segments(name)) {
                for (v in floatArrayOf(s.x0, s.y0, s.x1, s.y1)) {
                    assertTrue("$name coord $v must be within 0..1", v >= 0f && v <= 1f)
                }
                assertTrue("$name width ${s.w} must be positive", s.w > 0f)
                assertTrue("$name width ${s.w} must fit the box (<= 1)", s.w <= 1f)
                assertTrue("$name alpha ${s.alpha} in 0..1", s.alpha > 0f && s.alpha <= 1f)
            }
        }
    }

    @Test
    fun geometryIsDeterministic() {
        for ((name, _) in BrushPresets.all) {
            assertEquals(name, BrushPreviewMath.segments(name), BrushPreviewMath.segments(name))
        }
    }

    @Test
    fun characteristicShapesAreDistinct() {
        fun meanWidth(name: String): Float {
            val segs = BrushPreviewMath.segments(name)
            return segs.sumOf { it.w.toDouble() }.toFloat() / segs.size
        }
        val ink = meanWidth("Fine Ink")
        val marker = meanWidth("Marker")
        val highlighter = meanWidth("Highlighter")
        val soft = meanWidth("Soft Brush")
        // thin < marker-ish < soft band < highlighter band (the reference
        // row-previews are visually distinct at a glance)
        assertTrue("Fine Ink ($ink) must be the thinnest", ink < marker)
        assertTrue("Marker ($marker) < Soft Brush ($soft)", marker < soft)
        assertTrue("Soft Brush ($soft) < Highlighter ($highlighter)", soft < highlighter)
    }

    @Test
    fun ballpointTapersAndHighlighterIsSquarish() {
        // tapered: middle segment wider than first
        val ball = BrushPreviewMath.segments("Ballpoint")
        val first = ball.first().w
        val mid = ball[ball.size / 2].w
        assertTrue("ballpoint must taper (mid $mid > first $first)", mid > first)
        // highlighter: translucent band (alpha pinned to the preset's look)
        val hl = BrushPreviewMath.segments("Highlighter")
        assertTrue(hl.all { it.alpha <= 0.35f })
        assertTrue(hl.all { it.w >= 0.35f })
    }

    @Test
    fun dashedHasGapsAndSprayIsADotCloud() {
        val dashed = BrushPreviewMath.segments("Dashed")
        // 5 dashes, each a short horizontal run with gaps between
        assertEquals(5, dashed.size)
        for (i in 1 until dashed.size) {
            assertTrue("dash $i must start after the previous ends",
                dashed[i].x0 > dashed[i - 1].x1)
        }
        val spray = BrushPreviewMath.segments("Spray")
        // a dot cloud: many zero-length specks, scattered off the path
        assertTrue("spray must be dense (${spray.size})", spray.size >= 30)
        assertTrue("spray specks are dots", spray.all { it.x0 == it.x1 && it.y0 == it.y1 })
        val ys = spray.map { it.y0 }
        assertTrue("spray must scatter vertically", (ys.max()!! - ys.min()!!) > 0.1f)
    }

    @Test
    fun calligraphyRibbonVariesWithTheNibAngle() {
        val segs = BrushPreviewMath.segments("Calligraphy")
        val widths = segs.map { it.w }
        assertTrue("nib angle must vary the width",
            (widths.max()!! - widths.min()!!) > 0.15f)
    }

    @Test
    fun unknownPresetFallsBackToARenderableShape() {
        val segs = BrushPreviewMath.segments("Whatever-else")
        assertTrue(segs.isNotEmpty())
        assertTrue(segs.all { it.w > 0f && it.alpha > 0f })
    }
}
