package com.example.zoomart.ui

import kotlin.math.abs

/**
 * v0.7.0 (docs/UI_STYLE_SPEC.md section 4): pure, deterministic geometry
 * for the brush panel's characteristic stroke previews — one recognizable
 * shape per preset ("tapered line for ballpoint, wide soft band for
 * highlighter, angled ribbon for calligraphy, dashed line, spray dots").
 *
 * Everything is NORMALIZED: x/y in [0,1] (0,0 = top-left of the preview
 * box), widths as a fraction of box height, alpha in [0,1]. The BrushPanel
 * preview view maps these to pixels; the color (ink vs the amber accent)
 * is applied by the caller, so this object stays JVM-testable
 * (BrushPreviewMathTest) and deterministic (seeded LCG for spray).
 */
object BrushPreviewMath {

    /** One stroke segment: a line from (x0,y0) to (x1,y1) at relative
     *  width [w] and alpha. */
    data class Seg(
        val x0: Float, val y0: Float,
        val x1: Float, val y1: Float,
        val w: Float,
        val alpha: Float,
    )

    /** The S-curve path most stroke previews travel (dense poly-line). */
    private fun curve(t: Float): FloatArray {
        // gentle S: y = 0.5 + 0.22 * sin(2*PI*t)
        val y = 0.5f + 0.22f * kotlin.math.sin((2.0 * Math.PI * t).toFloat())
        return floatArrayOf(t, y)
    }

    /** Emits [n] poly-line segments along the S-curve with a per-position
     *  width/alpha function — the shared skeleton for the band-like presets. */
    private fun band(n: Int, widthAt: (Float) -> Float, alphaAt: (Float) -> Float): List<Seg> {
        val out = ArrayList<Seg>(n)
        var prev = curve(0.02f)
        for (i in 1..n) {
            val t = 0.02f + (0.96f - 0.02f) * i / n
            val p = curve(t)
            out.add(Seg(prev[0], prev[1], p[0], p[1], widthAt(t), alphaAt(t)))
            prev = p
        }
        return out
    }

    /** Deterministic LCG so SPRAY (and only spray) scatter is stable
     *  across binds/frames/tests. */
    private class Lcg(seed: Long) {
        private var s = seed
        fun next(): Float {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            return ((s ushr 33) % 1000) / 999f
        }
    }

    /** The characteristic preview geometry for a preset NAME (the exact
     *  strings from BrushPresets.all; unknown names get the ballpoint
     *  taper so the panel never renders an empty preview). */
    fun segments(presetName: String): List<Seg> = when (presetName) {
        "Ballpoint" -> band(14, { t -> 0.05f + 0.13f * kotlin.math.sin((Math.PI * t).toFloat()) }, { 1f })
        "Marker" -> band(10, { 0.16f }, { 0.85f })
        "Soft Brush" -> band(10, { 0.30f }, { 0.45f })
        "Fine Ink" -> band(12, { 0.035f }, { 1f })
        "Highlighter" -> band(8, { 0.42f }, { 0.30f })
        "Calligraphy" -> band(16,
            // angled nib: width follows |cos(40deg + slope phase)|
            { t -> 0.05f + 0.25f * abs(
                kotlin.math.cos(Math.toRadians(40.0) + Math.PI * t.toDouble())).toFloat() },
            { 1f })
        "Dashed" -> {
            val out = ArrayList<Seg>()
            for (k in 0 until 5) {
                val s = 0.06f + k * 0.20f
                val e = s + 0.11f
                out.add(Seg(s, curve(s)[1], e, curve(e)[1], 0.10f, 1f))
            }
            out
        }
        "Spray" -> {
            val out = ArrayList<Seg>()
            val rnd = Lcg(0x5A17L)
            for (i in 0 until 44) {
                val t = 0.05f + 0.9f * i / 43f
                val (cx, cy) = curve(t)
                // scatter grows slightly toward the stroke end; clamped so
                // specks never leave the preview box (normalized contract)
                val spread = 0.10f + 0.10f * t
                val dx = ((rnd.next() - 0.5f) * 2f * spread).coerceIn(-cx, 1f - cx)
                val dy = ((rnd.next() - 0.5f) * 2f * spread).coerceIn(-cy, 1f - cy)
                out.add(Seg(cx + dx, cy + dy, cx + dx, cy + dy, 0.035f, 0.55f))
            }
            out
        }
        "Pencil" -> {
            val out = ArrayList<Seg>()
            val rnd = Lcg(0xC0FFEEL)
            // main grainy line
            for (i in 0 until 12) {
                val t0 = 0.03f + 0.94f * i / 12f
                val t1 = 0.03f + 0.94f * (i + 1) / 12f
                val a = curve(t0); val b = curve(t1)
                out.add(Seg(a[0], a[1], b[0], b[1], 0.06f, 0.55f))
            }
            // sparse grain specks around it
            for (i in 0 until 10) {
                val t = 0.05f + 0.9f * rnd.next()
                val (cx, cy) = curve(t)
                out.add(Seg(cx, cy + (rnd.next() - 0.5f) * 0.16f, cx, cy, 0.03f, 0.30f))
            }
            out
        }
        else -> band(14, { t -> 0.05f + 0.13f * kotlin.math.sin((Math.PI * t).toFloat()) }, { 1f })
    }
}
