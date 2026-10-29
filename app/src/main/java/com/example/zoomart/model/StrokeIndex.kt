package com.example.zoomart.model

import java.util.IdentityHashMap

/**
 * Phase 5 uniform-grid spatial index, v2 (P0): cells are now keyed by
 * (levelId, cellX, cellY) — every zoom level owns its own bucket set, so a
 * level's viewport query only touches that level's grid. All methods stay
 * @Synchronized: background exporters query while the UI thread mutates.
 *
 * Strokes spanning more than MAX_CELLS cells go to a per-level oversize
 * list that is bbox-checked directly, so pathological spans cannot blow up
 * the grid.
 */
class StrokeIndex(private val cellSize: Float = 512f) {
    init { require(cellSize.isFinite() && cellSize > 0f) }

    private fun manageableSpan(x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
        val w = x1.toLong() - x0.toLong() + 1L
        val h = y1.toLong() - y0.toLong() + 1L
        return w in 1..MAX_CELLS && h in 1..MAX_CELLS && w * h <= MAX_CELLS
    }

    private fun padding(s: StrokeData): Float =
        s.brush.baseWidthWorld.coerceAtLeast(0f) + s.brush.scatterWorld.coerceAtLeast(0f)

    private class Buckets {
        val cells = HashMap<Long, MutableList<StrokeData>>()
        val oversize = mutableListOf<StrokeData>()
    }

    private val levels = HashMap<Long, Buckets>()
    private val owners = IdentityHashMap<StrokeData, LayerData>()
    private val cellsOf = IdentityHashMap<StrokeData, MutableList<Long>>()
    private val levelOf = IdentityHashMap<StrokeData, Long>()

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    private fun cellOf(world: Float): Int = Math.floor((world / cellSize).toDouble()).toInt()

    private fun bucketsFor(levelId: Long): Buckets = levels.getOrPut(levelId) { Buckets() }

    @Synchronized
    fun insert(layer: LayerData, stroke: StrokeData) {
        if (stroke.points.isEmpty()) return
        remove(stroke) // idempotent re-insert
        owners[stroke] = layer
        levelOf[stroke] = stroke.levelId
        val buckets = bucketsFor(stroke.levelId)
        val pad = padding(stroke)
        val minX = cellOf(stroke.minX - pad); val maxX = cellOf(stroke.maxX + pad)
        val minY = cellOf(stroke.minY - pad); val maxY = cellOf(stroke.maxY + pad)
        if (!manageableSpan(minX, maxX, minY, maxY)) {
            buckets.oversize.add(stroke)
            return
        }
        val keys = mutableListOf<Long>()
        for (cy in minY..maxY) {
            for (cx in minX..maxX) {
                val k = key(cx, cy)
                buckets.cells.getOrPut(k) { mutableListOf() }.add(stroke)
                keys.add(k)
            }
        }
        cellsOf[stroke] = keys
    }

    @Synchronized
    fun remove(stroke: StrokeData) {
        val levelId = levelOf.remove(stroke) ?: return
        val buckets = levels[levelId]
        cellsOf.remove(stroke)?.forEach { k ->
            buckets?.cells?.get(k)?.let { list ->
                list.remove(stroke)
                if (list.isEmpty()) buckets.cells.remove(k)
            }
        }
        buckets?.oversize?.remove(stroke)
        if (buckets != null && buckets.cells.isEmpty() && buckets.oversize.isEmpty()) levels.remove(levelId)
        owners.remove(stroke)
    }

    @Synchronized
    fun clear() {
        levels.clear(); owners.clear(); cellsOf.clear(); levelOf.clear()
    }

    @Synchronized
    fun rebuild(layers: List<LayerData>) {
        clear()
        for (layer in layers) for (stroke in layer.strokes) insert(layer, stroke)
    }

    /** Level ids that currently hold indexed strokes. */
    @Synchronized
    fun levelsPresent(): Set<Long> = levels.keys.filter { l ->
        val b = levels[l]
        (b?.cells?.isNotEmpty() == true) || (b?.oversize?.isNotEmpty() == true)
    }.toSet()

    /**
     * Legacy 4-arg query: strokes of the ROOT level intersecting the rect.
     * (Root == the old world space, so all pre-P0 call sites keep meaning.)
     */
    @Synchronized
    fun queryVisible(
        minX: Float, minY: Float, maxX: Float, maxY: Float,
    ): List<Pair<StrokeData, LayerData>> =
        queryVisibleLevel(ZoomLevels.ROOT_ID, minX, minY, maxX, maxY)

    /**
     * (stroke, owning layer) pairs of [levelId] intersecting the rect —
     * the rect is expressed in THAT level's local units. Deduped.
     */
    @Synchronized
    fun queryVisibleLevel(
        levelId: Long,
        minX: Float, minY: Float, maxX: Float, maxY: Float,
    ): List<Pair<StrokeData, LayerData>> {
        val buckets = levels[levelId] ?: return emptyList()
        val seen = LinkedHashMap<StrokeData, LayerData>()
        val cx0 = cellOf(minX); val cx1 = cellOf(maxX)
        val cy0 = cellOf(minY); val cy1 = cellOf(maxY)
        if (manageableSpan(cx0, cx1, cy0, cy1)) {
            for (cy in cy0..cy1) {
                for (cx in cx0..cx1) {
                    buckets.cells[key(cx, cy)]?.let { list ->
                        for (s in list) seen[s] = owners[s]!!
                    }
                }
            }
        } else {
            // extreme zoom-out: degenerate to a full scan rather than explode
            buckets.cells.values.forEach { list -> for (s in list) seen[s] = owners[s]!! }
        }
        for (s in buckets.oversize) {
            val pad = padding(s)
            if (s.maxX + pad >= minX && s.minX - pad <= maxX && s.maxY + pad >= minY && s.minY - pad <= maxY) {
                seen[s] = owners[s]!!
            }
        }
        // Grid buckets are broad-phase only. Cull their offscreen neighbours too.
        return seen.filterKeys { s ->
            val pad=padding(s)
            s.maxX+pad>=minX && s.minX-pad<=maxX && s.maxY+pad>=minY && s.minY-pad<=maxY
        }.map { it.key to it.value }
    }

    private companion object {
        const val MAX_CELLS = 4096L
    }
}
