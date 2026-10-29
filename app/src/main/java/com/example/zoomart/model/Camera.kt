package com.example.zoomart.model

/**
 * Camera v2 (P0): a (level, localScale, localTrans) triple instead of one
 * world-space float frame. Pure Kotlin — no Android imports, JVM-testable.
 *
 * screen <-> local:  screen = local * scale + trans   (CURRENT level only)
 * local <-> root:    recursive walk of the parent chain, accumulated in
 *                    Double so 30-deep round trips stay exact (<0.01 units).
 *
 * Pinching keeps working in local units. When localScale crosses the
 * promotion threshold (100), [applyLevelTransitions] promotes the camera to
 * a child level centered at the focus point; below 1f it demotes to the
 * parent. Both transitions are computed so EVERY point keeps its screen
 * position across the hop (no jump; exact inverses of each other).
 *
 * Constructor order keeps legacy positional use `Camera(scale, transX, transY)`
 * (a root-level camera) source-compatible with the exporters and old tests.
 */
class Camera(
    var scale: Float = 1f,
    var transX: Float = 0f,
    var transY: Float = 0f,
    var level: ZoomLevel = ZoomTree.root,
) {
    /** Spec alias: localScale of the current level, kept within ~[1, 100]. */
    var localScale: Float
        get() = scale
        set(value) { scale = value }

    // ----- screen <-> current-level local -----

    fun localToScreenX(localX: Float): Float = localX * scale + transX
    fun localToScreenY(localY: Float): Float = localY * scale + transY
    fun screenToLocalX(screenX: Float): Float = (screenX - transX) / scale
    fun screenToLocalY(screenY: Float): Float = (screenY - transY) / scale

    fun panBy(dxScreen: Float, dyScreen: Float) {
        transX += dxScreen
        transY += dyScreen
    }

    /** Zoom keeping the local point under the given SCREEN focal fixed.
     *  The upper clamp allows a transient overshoot past the promotion
     *  threshold so [applyLevelTransitions] can hand off to the child level
     *  with exact continuity (the overshoot is normalized within the same
     *  gesture event). */
    fun zoomAt(fx: Float, fy: Float, newScale: Float) {
        val minS = if (level.isRoot) MIN_SCALE else MIN_LOCAL_SCALE
        val clamped = newScale.coerceIn(minS, OVERSHOOT_SCALE)
        val ratio = clamped / scale
        transX = fx - (fx - transX) * ratio
        transY = fy - (fy - transY) * ratio
        scale = clamped
    }

    /**
     * Promotes/demotes after a zoom so localScale stays within [1, 100].
     * Call with the last screen focus (pinch midpoint / zoom-button center).
     * Returns true when the level changed.
     */
    fun applyLevelTransitions(focusScreenX: Float, focusScreenY: Float): Boolean {
        var changed = false
        while (scale >= ZoomLevels.THRESHOLD) {
            promote(focusScreenX, focusScreenY)
            changed = true
        }
        while (scale < 1f && !level.isRoot) {
            demote()
            changed = true
        }
        return changed
    }

    /**
     * Promotes into a child level centered on the local point under the
     * screen focus. Continuity: for every point p, screen_before(p) ==
     * screen_after(p) — derived by composing x_parent = origin + x_child /
     * scaleToParent with the old camera: scale' = scale / S,
     * trans' = focusLocal * scale + trans.
     */
    fun promote(focusScreenX: Float, focusScreenY: Float) {
        val parent = level
        val lx = screenToLocalX(focusScreenX)
        val ly = screenToLocalY(focusScreenY)
        val child = parent.tree.childAt(parent, lx, ly)
        val sL = scale
        val newScale = sL / child.scaleToParent
        val newTransX = lx * sL + transX
        val newTransY = ly * sL + transY
        level = child
        scale = newScale
        transX = newTransX
        transY = newTransY
    }

    /** Exact inverse of promote: back to the parent level, every point keeps
     *  its screen position. No-op at the root. */
    fun demote() {
        val parent = level.parent ?: return
        val child = level
        val newScale = scale * child.scaleToParent
        val newTransX = transX - child.originX * newScale
        val newTransY = transY - child.originY * newScale
        level = parent
        scale = newScale
        transX = newTransX
        transY = newTransY
    }

    // ----- current-level viewport -----

    /** Local rect [minX, minY, maxX, maxY] of the current level on screen. */
    fun visibleLocalRect(screenWidth: Float, screenHeight: Float): FloatArray =
        floatArrayOf(
            screenToLocalX(0f),
            screenToLocalY(0f),
            screenToLocalX(screenWidth),
            screenToLocalY(screenHeight),
        )

    // ----- cross-level transforms (Double accumulation: exact at depth 30+) -----

    /** Local point of [lvl] -> root coordinates. */
    fun localToRootX(lvl: ZoomLevel, x: Float): Double {
        var v = x.toDouble()
        var l = lvl
        while (l.parent != null) {
            v = l.originX + v / l.scaleToParent
            l = l.parent!!
        }
        return v
    }

    fun localToRootY(lvl: ZoomLevel, y: Float): Double {
        var v = y.toDouble()
        var l = lvl
        while (l.parent != null) {
            v = l.originY + v / l.scaleToParent
            l = l.parent!!
        }
        return v
    }

    /** Root coordinate -> local point of [lvl] (inverse chain). */
    fun rootToLocalX(lvl: ZoomLevel, rootX: Double): Float {
        val chain = ArrayList<ZoomLevel>()
        var l = lvl
        chain.add(l)
        while (l.parent != null) {
            l = l.parent!!
            chain.add(l)
        }
        var v = rootX
        for (i in chain.size - 2 downTo 0) {
            val node = chain[i]
            v = (v - node.originX) * node.scaleToParent
        }
        return v.toFloat()
    }

    fun rootToLocalY(lvl: ZoomLevel, rootY: Double): Float {
        val chain = ArrayList<ZoomLevel>()
        var l = lvl
        chain.add(l)
        while (l.parent != null) {
            l = l.parent!!
            chain.add(l)
        }
        var v = rootY
        for (i in chain.size - 2 downTo 0) {
            val node = chain[i]
            v = (v - node.originY) * node.scaleToParent
        }
        return v.toFloat()
    }

    /** Cumulative on-screen magnification of root units (100^depth aware). */
    fun effectiveScaleToRoot(): Double {
        var e = scale.toDouble()
        var l = level
        while (l.parent != null) {
            e /= l.scaleToParent.toDouble()
            l = l.parent!!
        }
        return e
    }

    /**
     * On-screen pixels per ROOT unit: scale * 100^depth. This is the total
     * zoom the user perceives and what the zoom readout displays — unlike
     * [effectiveScaleToRoot] (scale / 100^depth, kept for the P0 zoom-out
     * integrity test), it GROWS as the camera descends levels.
     */
    fun rootMagnification(): Double = scale.toDouble() * level.chainScaleProduct()

    /**
     * [k, tx, ty] mapping [target]'s local coordinates straight to screen:
     * screenX = localX * k + tx, screenY = localY * k + ty. Ancestors walk
     * down the parent chain multiplying by scaleToParent; descendants walk up
     * dividing by it about each origin. Float math stays bounded — each chain
     * step multiplies/divides by 100 once, never 100^depth at a time.
     */
    fun transformTo(target: ZoomLevel): FloatArray {
        if (target === level) return floatArrayOf(scale, transX, transY)
        val ancestors = generateSequence(level) { it.parent }.toSet()
        val targetPath = mutableListOf<ZoomLevel>()
        var common = target
        while (common !in ancestors) {
            targetPath.add(common)
            common = requireNotNull(common.parent) { "Levels belong to different trees" }
        }
        var k = scale.toDouble(); var x = transX.toDouble(); var y = transY.toDouble()
        var node = level
        while (node !== common) {
            k *= node.scaleToParent
            x -= node.originX * k; y -= node.originY * k
            node = checkNotNull(node.parent)
        }
        for (child in targetPath.asReversed()) {
            x += child.originX * k; y += child.originY * k
            k /= child.scaleToParent
        }
        return floatArrayOf(k.toFloat(), x.toFloat(), y.toFloat())
    }

    /**
     * Rebases the camera onto [target] (any level of the same tree), keeping
     * the ROOT point currently under screen (sx, sy) fixed on screen and
     * preserving the effective magnification. Used by pin flights and the
     * video exporter to hop levels without visible jumps.
     */
    fun snapToLevel(target: ZoomLevel, sx: Float, sy: Float) {
        if (target === level) return
        val rootX = localToRootX(level, screenToLocalX(sx))
        val rootY = localToRootY(level, screenToLocalY(sy))
        val effective = effectiveScaleToRoot()
        val newScale = (effective * target.chainScaleProduct()).toFloat()
        val lx = rootToLocalX(target, rootX)
        val ly = rootToLocalY(target, rootY)
        level = target
        scale = newScale
        transX = sx - lx * newScale
        transY = sy - ly * newScale
    }

    /** Copies another camera's state into this instance (keeps references alive). */
    fun setTo(other: Camera) {
        scale = other.scale
        transX = other.transX
        transY = other.transY
        level = other.level
    }

    /**
     * Camera for animation position t in [0,1] toward [other]. Scale
     * interpolates in LOG space so zoom speed feels perceptually linear.
     *
     * Same level: translation interpolates linearly (legacy, exact).
     *
     * Cross-level: logarithmic magnification and decimal coordinates relative
     * to the common ancestor avoid overflowing deep root-space doubles.
     * Each frame chooses a level along the flight path with a stable local scale.
     *
     * [animateToBookmark] and the MP4 pin-tour exporter both ride this.
     */
    fun interpolatedTo(other: Camera, t: Float): Camera {
        val c = t.coerceIn(0f, 1f)
        if (c <= 0f) return Camera(scale, transX, transY, level)
        if (c >= 1f) return Camera(other.scale, other.transX, other.transY, other.level)
        if (other.level === level) {
            val ratio = other.scale.toDouble() / scale.toDouble()
            return Camera(
                scale = (scale.toDouble() * Math.pow(ratio, c.toDouble())).toFloat(),
                transX = (transX.toDouble() + (other.transX.toDouble() - transX) * c).toFloat(),
                transY = (transY.toDouble() + (other.transY.toDouble() - transY) * c).toFloat(),
                level = level,
            )
        }
        if (other.level.tree !== level.tree) return if (c < 0.5f)
            Camera(scale,transX,transY,level) else Camera(other.scale,other.transX,other.transY,other.level)
        // Log magnification never forms 100^depth, and decimal coordinates retain
        // small offsets below deep common ancestors instead of rounding them away.
        val logs=mutableMapOf<ZoomLevel,Double>()
        fun logScale(node: ZoomLevel): Double {
            val chain=generateSequence(node) { it.parent }.takeWhile { it !in logs }.toList()
            for(entry in chain.asReversed()) logs[entry] = if(entry.parent==null) 0.0
                else logs.getValue(entry.parent!!)+kotlin.math.ln(entry.scaleToParent.toDouble())
            return logs.getValue(node)
        }
        val logFrom=kotlin.math.ln(scale.toDouble())+logScale(level)
        val logTo=kotlin.math.ln(other.scale.toDouble())+logScale(other.level)
        val logMag=logFrom+(logTo-logFrom)*c
        val path=flightPathTo(other.level)
        val target=path.minBy { node ->
            val local=logMag-logScale(node)
            if(local>=0 && local<kotlin.math.ln(ZoomLevels.THRESHOLD.toDouble())) 0.0
            else kotlin.math.abs(local-kotlin.math.ln(10.0))
        }
        val common=path.minBy { it.depth }
        val precision=java.math.MathContext(4096,java.math.RoundingMode.HALF_EVEN)
        fun decimal(value: Double)=java.math.BigDecimal.valueOf(value)
        fun coordinate(camera: Camera, x: Boolean): java.math.BigDecimal {
            var node=camera.level
            var value=decimal(-(if(x) camera.transX else camera.transY).toDouble()).divide(decimal(camera.scale.toDouble()),precision)
            while(node !== common) {
                value=value.divide(decimal(node.scaleToParent.toDouble()),precision)
                    .add(decimal((if(x) node.originX else node.originY).toDouble()),precision)
                node=checkNotNull(node.parent)
            }
            return value
        }
        fun local(x: Boolean): Float {
            val start=coordinate(this,x);val end=coordinate(other,x)
            var value=start.add(end.subtract(start,precision).multiply(decimal(c.toDouble()),precision),precision)
            val down=generateSequence(target) { it.parent }.takeWhile { it !== common }.toList().asReversed()
            for(node in down) value=value.subtract(decimal((if(x) node.originX else node.originY).toDouble()),precision)
                .multiply(decimal(node.scaleToParent.toDouble()),precision)
            return value.multiply(decimal(kotlin.math.exp(logMag-logScale(target))),precision).negate().toFloat()
        }
        return Camera(kotlin.math.exp(logMag-logScale(target)).toFloat(),local(true),local(false),target)
    }

    /**
     * Path levels from this camera's level to [target]: up to the common
     * ancestor, then down to the target. Adjacent entries differ by exactly
     * one scaleToParent hop.
     */
    private fun flightPathTo(target: ZoomLevel): List<ZoomLevel> {
        val path = ArrayList<ZoomLevel>()
        val targetChain = HashSet<ZoomLevel>()
        var t: ZoomLevel? = target
        while (t != null) {
            targetChain.add(t)
            t = t.parent
        }
        var l: ZoomLevel? = level
        while (l != null && l !in targetChain) {
            path.add(l)
            l = l.parent
        }
        if (l != null) path.add(l) // the common ancestor itself
        val down = ArrayList<ZoomLevel>()
        var d: ZoomLevel? = target
        while (d != null && d !== l) {
            down.add(d)
            d = d.parent
        }
        for (i in down.indices.reversed()) path.add(down[i])
        if (path.isEmpty()) path.add(level)
        return path
    }

    /** The path level whose implied localScale at [mag] sits in [1, THRESHOLD). */
    private fun flightLevelFor(mag: Double, target: ZoomLevel): ZoomLevel {
        var best = level
        var bestScore = Double.MAX_VALUE
        for (lvl in flightPathTo(target)) {
            val ls = mag / lvl.chainScaleProduct()
            val score = if (ls >= 1.0 && ls < ZoomLevels.THRESHOLD.toDouble()) {
                0.0
            } else if (ls > 0.0) {
                Math.abs(Math.log10(ls) - 1.0) // distance from mid-band log10(10)
            } else {
                Double.MAX_VALUE
            }
            if (score < bestScore) {
                bestScore = score
                best = lvl
            }
        }
        return best
    }

    private fun isAncestorOfCamera(candidate: ZoomLevel): Boolean {
        var l: ZoomLevel? = level
        while (l != null) {
            if (l === candidate) return true
            l = l.parent
        }
        return false
    }

    /** Next node on the path from [ancestor] down to the camera's level. */
    private fun childToward(ancestor: ZoomLevel): ZoomLevel {
        var l = level
        while (l.parent != null && l.parent !== ancestor) l = l.parent!!
        return l
    }

    companion object {
        /** Zoom-out floor at the ROOT level (legacy sessions, wide exports). */
        const val MIN_SCALE = 0.05f

        /** Non-root local floor before a demote takes over. */
        const val MIN_LOCAL_SCALE = 0.02f

        /** Transient zoom overshoot above the threshold before promote. */
        const val OVERSHOOT_SCALE = 10_000f
    }
}
