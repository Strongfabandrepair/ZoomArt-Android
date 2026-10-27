package com.example.zoomart.model

/**
 * P3.3 — Blend/smudge tool (docs/REVIEW_2026-10-18.md BLEND/SMUDGE
 * section).
 *
 * A smudge gesture deposits soft VECTOR stamps — pure circles with a float
 * radius in the owning level's local frame, no bitmaps — so they stay
 * smooth at any zoom (spec note). One gesture = one [SmudgeData] stored on
 * the layer's content lists alongside strokes/images/shapes: layer lock
 * rules apply and the stamps are NOT stroke-erasable (the vector eraser
 * only fragments strokes).
 *
 * Color carry: on gesture start the composed color under the pen is
 * sampled once (the View renders the full scene to a snapshot bitmap);
 * at every deposit step the carried color updates
 *     carried = 0.8 * carried + 0.2 * sampled   (per channel)
 * and the stamp deposits the carried color at alpha
 *     strength slider x pen pressure.
 */
class StampData(
    val x: Float,
    val y: Float,
    /** Stamp radius in LEVEL-LOCAL units (screen px / camera scale at commit). */
    val radius: Float,
    /** Carried color, opaque ARGB. */
    val color: Int,
    /** Deposit alpha 0..1 = strength x pressure (layer opacity applies at render). */
    val alpha: Float,
)

/** One smudge gesture: an ordered trail of stamps in one level, on one layer. */
class SmudgeData(val levelId: Long = ZoomLevels.ROOT_ID) {
    val stamps = mutableListOf<StampData>()

    fun deepCopy(): SmudgeData {
        val s = SmudgeData(levelId)
        for (st in stamps) s.stamps.add(StampData(st.x, st.y, st.radius, st.color, st.alpha))
        return s
    }
}

/** Pure, JVM-testable smudge arithmetic. */
object SmudgeMath {

    /** Weight of the CARRIED color in each mixing step (spec: 80/20). */
    const val CARRIED_WEIGHT = 0.8f

    /**
     * carried = mix(carried, sampled, 0.20 new / 0.80 carried), per RGB
     * channel, HALF-UP ROUNDED so the geometric chain converges without
     * truncation bias (255 * 0.2 must land on 51, not 50). Alpha stays
     * opaque (deposit alpha is a separate scalar).
     */
    fun mixCarried(carried: Int, sampled: Int): Int {
        val w = CARRIED_WEIGHT
        val r = ((carried ushr 16) and 0xFF) * w + ((sampled ushr 16) and 0xFF) * (1f - w)
        val g = ((carried ushr 8) and 0xFF) * w + ((sampled ushr 8) and 0xFF) * (1f - w)
        val b = (carried and 0xFF) * w + (sampled and 0xFF) * (1f - w)
        return (0xFF shl 24) or
            ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            (b + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * True when the pen has moved at least [intervalScreenPx] screen
     * pixels (converted to level-local units at [cameraScale]) since the
     * last deposit — the stamp cadence ("small intervals").
     */
    fun shouldDeposit(
        lastLocalX: Float, lastLocalY: Float,
        localX: Float, localY: Float,
        intervalScreenPx: Float,
        cameraScale: Float,
    ): Boolean {
        if (intervalScreenPx <= 0f) return true
        val scale = if (cameraScale > 1e-6f) cameraScale else 1f
        val interval = intervalScreenPx / scale
        val dx = localX - lastLocalX
        val dy = localY - lastLocalY
        return dx * dx + dy * dy >= interval * interval
    }

    /** Stamp radius in level-local units for a screen-px size slider. */
    fun radiusLocal(sizeScreenPx: Float, cameraScale: Float): Float =
        (sizeScreenPx * 0.5f) / (if (cameraScale > 1e-6f) cameraScale else 1f)

    /** Deposit alpha = strength slider (0..1) x pen pressure (0..1). */
    fun stampAlpha(strength: Float, pressure01: Float): Float =
        (strength.coerceIn(0f, 1f) * pressure01.coerceIn(0f, 1f))

    /** Default deposit cadence in screen px (between stamps). */
    const val DEFAULT_INTERVAL_SCREEN_PX = 4f
}
