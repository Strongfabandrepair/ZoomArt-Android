package com.example.zoomart.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * P3.1 — Parametric vector shapes (docs/REVIEW_2026-10-18.md SHAPES section).
 *
 * A shape is defined by two anchor points (the drag corners) in the OWNING
 * zoom level's local frame plus per-type style parameters, exactly like a
 * stroke: `levelId` tells the renderer which frame the floats live in, and
 * the outline width is stored in level-local units (converted from screen
 * px at commit time via the `BrushSpec.scaledForLocalScale` pattern), so a
 * shape keeps its on-screen weight at any zoom depth.
 */
enum class ShapeType {
    LINE, RECTANGLE, ROUNDED_RECT, ELLIPSE, TRIANGLE, POLYGON, STAR, ARROW;

    companion object {
        /**
         * Codec read: unknown strings degrade to null so the loader can SKIP
         * a forward-compat shape type instead of failing the whole session.
         */
        fun fromName(s: String?): ShapeType? =
            if (s == null) null else runCatching { valueOf(s) }.getOrNull()
    }
}

/**
 * One committed (or in-progress) shape. Anchors are the raw drag corners —
 * for box types they may be unordered; geometry normalizes via
 * [ShapeGeometry.rectOf]. For LINE/ARROW they are ordered (start -> end).
 */
class ShapeData(
    var type: ShapeType = ShapeType.RECTANGLE,
    var levelId: Long = ZoomLevels.ROOT_ID,
) {
    var x0 = 0f
    var y0 = 0f
    var x1 = 0f
    var y1 = 0f

    // style
    var fillOn = true
    var fillColor: Int = 0xFF2E66F6.toInt()
    var outlineColor: Int = 0xFF16181D.toInt()
    /** Outline weight in LEVEL-LOCAL units (screen px / camera scale at commit). */
    var outlineWidthWorld: Float = 4f
    /** ROUNDED_RECT corner radius as a fraction [0..1] of the smaller side/2. */
    var cornerRoundness: Float = 0.25f
    /** POLYGON sides / STAR points (>= 3). */
    var pointCount: Int = 5

    fun setAnchors(ax0: Float, ay0: Float, ax1: Float, ay1: Float) {
        x0 = ax0; y0 = ay0; x1 = ax1; y1 = ay1
    }

    /** Copies style + geometry from [other] into this shape (deepCopy use). */
    fun copyFrom(other: ShapeData) {
        type = other.type
        levelId = other.levelId
        x0 = other.x0; y0 = other.y0; x1 = other.x1; y1 = other.y1
        fillOn = other.fillOn
        fillColor = other.fillColor
        outlineColor = other.outlineColor
        outlineWidthWorld = other.outlineWidthWorld
        cornerRoundness = other.cornerRoundness
        pointCount = other.pointCount
    }

    fun deepCopy(): ShapeData {
        val s = ShapeData(type, levelId)
        s.copyFrom(this)
        return s
    }

    /**
     * Geometry extents in this shape's level-local frame, padded by half the
     * outline width. Used for viewport culling and selection projection.
     */
    fun bbox(): FloatArray = ShapeGeometry.bboxOf(this)

    /** True when the shape has zero extent (a tap, not a drag). */
    fun isDegenerate(): Boolean = when (type) {
        ShapeType.LINE, ShapeType.ARROW -> hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()) < 1e-4f
        else -> abs(x1 - x0) < 1e-4f || abs(y1 - y0) < 1e-4f
    }
}

/**
 * Pure, JVM-testable parametric geometry for all eight shape types.
 *
 * Per-type generation (anchors A=(x0,y0), B=(x1,y1); box = normalized rect):
 *  - LINE: open segment A->B.
 *  - RECTANGLE: the box.
 *  - ROUNDED_RECT: the box with corner arcs of radius
 *    `roundness * min(w,h)/2` (renderer draws it natively; arc vertices are
 *    exposed for tests/hit-testing).
 *  - ELLIPSE: inscribed in the box (renderer draws drawOval).
 *  - TRIANGLE: apex at top-center, base at the box's bottom corners.
 *  - POLYGON: regular N-gon (N = pointCount) inscribed in the box's
 *    ellipse, first vertex at the top (-90 degrees). "Regular" in the
 *    square-constrained sense; a non-square box stretches it.
 *  - STAR: N-point star (N = pointCount), outer radius = box half extents,
 *    inner radius = outer * [STAR_INNER_RATIO], first point at the top.
 *  - ARROW: shaft A->B plus a filled head triangle at B whose size is a
 *    fraction of the arrow length.
 *
 * Constrain (shift-like): square/circle for the box types, 15-degree
 * direction snapping for LINE/ARROW. From-center: the drag START becomes
 * the shape's center and the box grows symmetrically about it.
 */
object ShapeGeometry {

    /** Star inner/outer vertex radius ratio (classic 5-point star ≈ 0.5). */
    const val STAR_INNER_RATIO = 0.5f

    /** Arrow head length as a fraction of the total arrow length. */
    const val ARROW_HEAD_FRACTION = 0.3f

    /** Arrow head half-width as a fraction of the head length. */
    const val ARROW_HEAD_HALF_RATIO = 0.5f

    const val CONSTRAIN_SNAP_DEG = 15f

    /** One open or closed sub-polyline of a shape's outline. */
    class Poly(val pts: List<FloatArray>, val closed: Boolean)

    // ---------------- drag resolution (constrain / from-center) ----------------

    /**
     * Resolves the raw drag corners into the shape's final anchors with the
     * two toggles applied (from-center FIRST, then constrain on the
     * centered geometry so a constrained centered box stays centered).
     * Returns [x0, y0, x1, y1].
     */
    fun resolveAnchors(
        type: ShapeType,
        ax0: Float, ay0: Float, ax1: Float, ay1: Float,
        constrain: Boolean,
        fromCenter: Boolean,
    ): FloatArray {
        var x0 = ax0
        var y0 = ay0
        var x1 = ax1
        var y1 = ay1
        if (fromCenter) {
            // the drag start becomes the CENTER; the shape grows symmetrically
            val dx = ax1 - ax0
            val dy = ay1 - ay0
            x0 = ax0 - dx
            y0 = ay0 - dy
            x1 = ax0 + dx
            y1 = ay0 + dy
        }
        if (constrain) {
            if (type == ShapeType.LINE || type == ShapeType.ARROW) {
                // 15-degree direction snap; length preserved
                val len = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
                if (len > 1e-5f) {
                    val step = Math.toRadians(CONSTRAIN_SNAP_DEG.toDouble())
                    val ang = (atan2(y1 - y0, x1 - x0) / step)
                    val snapped = (Math.round(ang).toDouble() * step)
                    x1 = x0 + len * cos(snapped).toFloat()
                    y1 = y0 + len * sin(snapped).toFloat()
                }
            } else if (fromCenter) {
                // square/circle about the CENTER: centered boxes stay
                // centered when constrained (half-extents equalized)
                val cx = (x0 + x1) / 2f
                val cy = (y0 + y1) / 2f
                val half = max(abs(x1 - x0), abs(y1 - y0)) / 2f
                x0 = cx - half
                x1 = cx + half
                y0 = cy - half
                y1 = cy + half
            } else {
                // square (rect-family) / circle (ellipse) / regular polygon:
                // equal sides about the drag START, direction signs preserved
                val w = max(abs(x1 - x0), abs(y1 - y0))
                x1 = x0 + (if (x1 >= x0) 1f else -1f) * w
                y1 = y0 + (if (y1 >= y0) 1f else -1f) * w
            }
        }
        return floatArrayOf(x0, y0, x1, y1)
    }

    // ---------------- per-type geometry ----------------

    /** Normalized [minX, minY, maxX, maxY] of the anchors (box types). */
    fun rectOf(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray =
        floatArrayOf(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))

    /** Center + radii of the ellipse inscribed in the anchors' box. */
    fun ellipseOf(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        val r = rectOf(x0, y0, x1, y1)
        val cx = (r[0] + r[2]) / 2f
        val cy = (r[1] + r[3]) / 2f
        return floatArrayOf(cx, cy, (r[2] - r[0]) / 2f, (r[3] - r[1]) / 2f)
    }

    /**
     * Point on the box-inscribed ellipse at angle [deg]
     * (0 = +x, counter-clockwise; matches the trig conventions above).
     */
    fun ellipsePoint(x0: Float, y0: Float, x1: Float, y1: Float, deg: Float): FloatArray {
        val e = ellipseOf(x0, y0, x1, y1)
        val a = Math.toRadians(deg.toDouble())
        return floatArrayOf(
            (e[0] + e[2] * cos(a)).toFloat(),
            (e[1] + e[3] * sin(a)).toFloat(),
        )
    }

    /** Triangle: apex at top-center, base at the bottom corners of the box. */
    fun triangleVertices(x0: Float, y0: Float, x1: Float, y1: Float): List<FloatArray> {
        val r = rectOf(x0, y0, x1, y1)
        val cx = (r[0] + r[2]) / 2f
        return listOf(
            floatArrayOf(cx, r[1]),
            floatArrayOf(r[0], r[3]),
            floatArrayOf(r[2], r[3]),
        )
    }

    /**
     * Regular N-gon inscribed in the box's ellipse, first vertex at the top
     * (-90 degrees), counter-clockwise.
     */
    fun polygonVertices(x0: Float, y0: Float, x1: Float, y1: Float, sides: Int): List<FloatArray> {
        val n = sides.coerceAtLeast(3)
        val out = ArrayList<FloatArray>(n)
        for (k in 0 until n) {
            out.add(ellipsePoint(x0, y0, x1, y1, -90f + k * 360f / n))
        }
        return out
    }

    /**
     * Star with [points] outer vertices: outer radius = box half extents,
     * inner radius = outer * [STAR_INNER_RATIO]. Vertices alternate
     * outer/inner starting at the top (-90 degrees), so vertex 2k is an
     * outer point and vertex 2k+1 an inner one (2N vertices total).
     */
    fun starVertices(x0: Float, y0: Float, x1: Float, y1: Float, points: Int): List<FloatArray> {
        val n = points.coerceAtLeast(3)
        val e = ellipseOf(x0, y0, x1, y1)
        val out = ArrayList<FloatArray>(n * 2)
        for (k in 0 until n * 2) {
            val outer = (k % 2 == 0)
            val f = if (outer) 1f else STAR_INNER_RATIO
            val deg = -90f + k * 180f / n
            val a = Math.toRadians(deg.toDouble())
            out.add(
                floatArrayOf(
                    (e[0] + e[2] * f * cos(a)).toFloat(),
                    (e[1] + e[3] * f * sin(a)).toFloat(),
                )
            )
        }
        return out
    }

    /**
     * Arrow head triangle: (headLeft, headRight) base corners beside the
     * shaft, with the tip at B. Head length = |AB| * [ARROW_HEAD_FRACTION],
     * half width = head length * [ARROW_HEAD_HALF_RATIO].
     */
    fun arrowHead(x0: Float, y0: Float, x1: Float, y1: Float): Pair<FloatArray, FloatArray> {
        val len = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
        if (len < 1e-5f) return floatArrayOf(x1, y1) to floatArrayOf(x1, y1)
        val ux = (x1 - x0) / len
        val uy = (y1 - y0) / len
        val headLen = len * ARROW_HEAD_FRACTION
        val half = headLen * ARROW_HEAD_HALF_RATIO
        val bx = x1 - ux * headLen
        val by = y1 - uy * headLen
        return floatArrayOf(bx - uy * half, by + ux * half) to
            floatArrayOf(bx + uy * half, by - ux * half)
    }

    /** Corner radius in local units of a ROUNDED_RECT. */
    fun cornerRadius(x0: Float, y0: Float, x1: Float, y1: Float, roundness: Float): Float {
        val r = rectOf(x0, y0, x1, y1)
        val half = minOf(r[2] - r[0], r[3] - r[1]) / 2f
        return (roundness.coerceIn(0f, 1f)) * half
    }

    /**
     * Arc vertices of one rounded-rect corner ([ARC_STEPS] samples),
     * starting at [startDeg] and sweeping [sweepDeg] counter-clockwise.
     * Exposed for geometry tests; the renderer uses canvas.drawRoundRect.
     */
    fun roundedRectArcVertices(
        x0: Float, y0: Float, x1: Float, y1: Float,
        roundness: Float, steps: Int = ARC_STEPS,
    ): List<FloatArray> {
        val r = rectOf(x0, y0, x1, y1)
        val cr = cornerRadius(x0, y0, x1, y1, roundness)
        // bottom-left corner circle center
        val cx = r[0] + cr
        val cy = r[3] - cr
        val out = ArrayList<FloatArray>(steps + 1)
        for (k in 0..steps) {
            val deg = 180.0 + 90.0 * k / steps
            val a = Math.toRadians(deg)
            out.add(
                floatArrayOf(
                    (cx + cr * cos(a)).toFloat(),
                    (cy - cr * sin(a)).toFloat(),
                )
            )
        }
        return out
    }

    const val ARC_STEPS = 8

    /**
     * Sub-polylines of a shape (renderer + hit-test use). ELLIPSE and
     * ROUNDED_RECT return their box polygon approximations here — the View
     * draws those two types with native canvas primitives so they stay
     * crisp at any zoom; the vertex lists exist for pure-JVM tests.
     */
    fun subPaths(shape: ShapeData): List<Poly> {
        val a = floatArrayOf(shape.x0, shape.y0)
        val b = floatArrayOf(shape.x1, shape.y1)
        return when (shape.type) {
            ShapeType.LINE -> listOf(Poly(listOf(a, b), closed = false))
            ShapeType.RECTANGLE -> {
                val r = rectOf(shape.x0, shape.y0, shape.x1, shape.y1)
                listOf(
                    Poly(
                        listOf(
                            floatArrayOf(r[0], r[1]), floatArrayOf(r[2], r[1]),
                            floatArrayOf(r[2], r[3]), floatArrayOf(r[0], r[3]),
                        ),
                        closed = true,
                    )
                )
            }
            ShapeType.ROUNDED_RECT -> listOf(
                Poly(
                    roundedRectPolygon(shape.x0, shape.y0, shape.x1, shape.y1, shape.cornerRoundness),
                    closed = true,
                )
            )
            ShapeType.ELLIPSE -> listOf(
                Poly(
                    polygonVertices(shape.x0, shape.y0, shape.x1, shape.y1, 24),
                    closed = true,
                )
            )
            ShapeType.TRIANGLE ->
                listOf(Poly(triangleVertices(shape.x0, shape.y0, shape.x1, shape.y1), closed = true))
            ShapeType.POLYGON ->
                listOf(Poly(polygonVertices(shape.x0, shape.y0, shape.x1, shape.y1, shape.pointCount), closed = true))
            ShapeType.STAR ->
                listOf(Poly(starVertices(shape.x0, shape.y0, shape.x1, shape.y1, shape.pointCount), closed = true))
            ShapeType.ARROW -> {
                val (hl, hr) = arrowHead(shape.x0, shape.y0, shape.x1, shape.y1)
                listOf(
                    Poly(listOf(a, b), closed = false),           // shaft
                    Poly(listOf(hl, b, hr), closed = true),       // head
                )
            }
        }
    }

    /** Closed polygon approximation of a rounded rect (4 corners' arcs). */
    fun roundedRectPolygon(
        x0: Float, y0: Float, x1: Float, y1: Float, roundness: Float,
    ): List<FloatArray> {
        val r = rectOf(x0, y0, x1, y1)
        val cr = cornerRadius(x0, y0, x1, y1, roundness)
        val pts = ArrayList<FloatArray>()
        fun arc(cx: Float, cy: Float, startDeg: Double) {
            for (k in 0 until ARC_STEPS) {
                val deg = startDeg + 90.0 * k / (ARC_STEPS - 1)
                val a = Math.toRadians(deg)
                // canvas y grows downward; sweep clockwise visually
                pts.add(floatArrayOf((cx + cr * cos(a)).toFloat(), (cy + cr * sin(a)).toFloat()))
            }
        }
        arc(r[0] + cr, r[1] + cr, 180.0)   // top-left
        arc(r[2] - cr, r[1] + cr, 270.0)  // top-right
        arc(r[2] - cr, r[3] - cr, 0.0)    // bottom-right
        arc(r[0] + cr, r[3] - cr, 90.0)   // bottom-left
        return pts
    }

    /**
     * Geometry bbox in level-local units — EXACT extents, no padding, so
     * selection handles sit on the geometry corners and corner-resize
     * pivots stay fixed. Renderers that cull add the outline half-width
     * slack themselves (drawScene), and exporters pad explicitly.
     */
    fun bboxOf(shape: ShapeData): FloatArray {
        var minX = minOf(shape.x0, shape.x1)
        var minY = minOf(shape.y0, shape.y1)
        var maxX = maxOf(shape.x0, shape.x1)
        var maxY = maxOf(shape.y0, shape.y1)
        if (shape.type == ShapeType.ARROW) {
            val (hl, hr) = arrowHead(shape.x0, shape.y0, shape.x1, shape.y1)
            minX = minOf(minX, minOf(hl[0], hr[0]))
            maxX = maxOf(maxX, maxOf(hl[0], hr[0]))
            minY = minOf(minY, minOf(hl[1], hr[1]))
            maxY = maxOf(maxY, maxOf(hl[1], hr[1]))
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }
}
