package com.example.zoomart.model

import kotlin.math.pow

/**
 * Parametric brush definition. Everything is expressed in WORLD units, so a
 * stroke re-renders vector-crisp at any zoom level.
 */
enum class StampShape { ROUND, STAR, LEAF, CHISEL }

data class BrushSpec(
    val baseWidthWorld: Float = 6f,     // stroke width (world units) at full pressure
    val pressureResponse: Float = 2f,  // >1 favours light touch; 0 ignores pressure
    val minWidthRatio: Float = 0.15f,  // width floor at zero pressure
    val color: Int = (0xFF shl 24) or 0x16181D,
    val opacity: Float = 1f,
    val angleDeg: Float = 0f,        // calligraphy: rotates the nib normal
    val dashOn: Float = 0f,          // dash pattern (0 = solid outline)
    val dashOff: Float = 0f,
    val scatterWorld: Float = 0f,    // spray: random offset per stamp
    val spacingWorld: Float = 0f,    // stamp spacing (0 = continuous outline)
    val pressureOpacity: Boolean = false, // P1.2: pressure drives alpha, not just width
    val minOpacity: Float = DEFAULT_MIN_OPACITY, // alpha floor at zero pressure
    val stampShape: StampShape = StampShape.ROUND,
    val stampFlipX: Boolean = false,
    val stampFlipY: Boolean = false,
    val erase: Boolean = false,      // v0.7.0: erase-as-modifier — carve instead of draw
) {
    /**
     * Maps normalized pressure [0..1] to a stroke width. The result is in the
     * units of [baseWidthWorld]: screen px at 1x for new strokes (commit-time
     * conversion), or level-local units for stored strokes.
     */
    fun widthForPressure(pressure01: Float): Float {
        val p = pressure01.coerceIn(0f, 1f)
        val shaped = if (pressureResponse <= 0f) 1f else p.pow(pressureResponse)
        return baseWidthWorld * (minWidthRatio + (1f - minWidthRatio) * shaped)
    }

    /**
     * P1.2: per-point render alpha. For brushes with [pressureOpacity] set
     * (pencil), alpha = opacity * lerp(minOpacity, 1, pressure) — light
     * touches render faint, hard presses render at the brush's full opacity
     * while the width stays near constant. Every other brush returns its
     * plain [opacity] regardless of pressure.
     */
    fun alphaForPressure(pressure01: Float): Float {
        if (!pressureOpacity) return opacity
        val p = pressure01.coerceIn(0f, 1f)
        return (opacity * (minOpacity + (1f - minOpacity) * p)).coerceIn(0f, 1f)
    }

    /**
     * P0/P1 screen-relative brush width: converts this spec's width-like
     * fields (defined as screen px at 1x) into level-LOCAL units for the
     * current camera zoom, so the on-screen stroke width stays constant
     * across all zoom levels. Applied once, at stroke-commit time.
     */
    fun scaledForLocalScale(localScale: Float): BrushSpec {
        if (localScale == 1f) return this
        val s = 1f / localScale
        return copy(
            baseWidthWorld = baseWidthWorld * s,
            dashOn = dashOn * s,
            dashOff = dashOff * s,
            scatterWorld = scatterWorld * s,
            spacingWorld = spacingWorld * s,
        )
    }

    companion object {
        /** Default [minOpacity] (also the codec's fallback when absent). */
        const val DEFAULT_MIN_OPACITY = 0.2f
    }
}

/** One sampled input point, stored in the owning LEVEL's local space. */
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure01: Float,
)

/**
 * A finished or in-progress stroke. Geometry lives in the local space of the
 * ZoomLevel whose id is [levelId] (the level the stroke was begun in) — the
 * invariant that keeps float precision intact at any zoom depth.
 */
class StrokeData(
    val brush: BrushSpec = BrushSpec(),
) {
    /** Owning zoom level (P0). Root (0) == legacy world space. */
    var levelId: Long = ZoomLevels.ROOT_ID
    val points = mutableListOf<StrokePoint>()
    var geometryRevision = 0L
        private set

    /**
     * P3.2: end-taper fraction (0 = none). Lazy-brush strokes commit with
     * LazyBrush.TAPER_FRACTION so their first/last ~10% of arclength
     * smoothstep the rendered width down to a point. Serialized as an
     * additive codec field (absent -> 0 -> pre-P3 rendering, unchanged).
     */
    var taper: Float = 0f

    // World-space bounding box, maintained incrementally for viewport culling.
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE

    fun addPoint(p: StrokePoint) {
        geometryRevision++
        points.add(p)
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }

    fun intersectsWorldRect(minXw: Float, minYw: Float, maxXw: Float, maxYw: Float): Boolean =
        !(maxX < minXw || minX > maxXw || maxY < minYw || minY > maxYw)

    /** Replaces all points and recomputes the bounding box (transforms). */
    fun setPoints(pts: List<StrokePoint>) {
        geometryRevision++
        points.clear()
        points.addAll(pts)
        minX = Float.MAX_VALUE; maxX = -Float.MAX_VALUE
        minY = Float.MAX_VALUE; maxY = -Float.MAX_VALUE
        for (p in pts) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
    }
}

/**
 * Rejects input points that moved less than a screen-pixel threshold, converted
 * to world units with the CURRENT zoom. Effect: stroke data stays dense
 * relative to what is visible, independent of zoom level.
 */
object StrokeSmoother {
    const val MIN_SEGMENT_SCREEN_PX = 1.5f

    fun shouldAdd(
        stroke: StrokeData,
        candidate: StrokePoint,
        camera: Camera,
        minScreenPx: Float = MIN_SEGMENT_SCREEN_PX,
    ): Boolean {
        val last = stroke.points.lastOrNull() ?: return true
        val eps = minScreenPx / camera.scale
        val dx = candidate.x - last.x
        val dy = candidate.y - last.y
        return dx * dx + dy * dy >= eps * eps
    }
}
