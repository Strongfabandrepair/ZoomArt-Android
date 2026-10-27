package com.example.zoomart.model

import kotlin.math.abs

/**
 * P0 — Nested zoom levels (docs/P0_ZOOM_LEVELS.md).
 *
 * A ZoomLevel is one float-safe coordinate frame. Its local (0,0) sits at
 * (originX, originY) in the PARENT frame, and one local unit maps to
 * 1/scaleToParent parent units — i.e. a child frame magnifies a small region
 * of its parent by a fixed factor (the promotion threshold, 100x). Local
 * stroke coordinates therefore stay within a few thousand units at ANY tree
 * depth, which keeps float precision (~7 significant digits) intact.
 */
class ZoomLevel(
    val id: Long,
    var originX: Float,
    var originY: Float,
    val scaleToParent: Float = ZoomLevels.THRESHOLD,
    val tree: LevelTree,
) {
    var parent: ZoomLevel? = null
    val children = mutableListOf<ZoomLevel>()

    val isRoot: Boolean get() = parent == null
    val depth: Int get() = if (parent == null) 0 else parent!!.depth + 1

    /** Child-local x -> parent-local x. */
    fun toParentX(x: Float): Float = originX + x / scaleToParent
    fun toParentY(y: Float): Float = originY + y / scaleToParent

    /** Parent-local x -> child-local x. */
    fun fromParentX(px: Float): Float = (px - originX) * scaleToParent
    fun fromParentY(py: Float): Float = (py - originY) * scaleToParent

    /** Product of scaleToParent over this level and all ancestors (double). */
    fun chainScaleProduct(): Double {
        var p = 1.0
        var l: ZoomLevel? = this
        while (l != null) {
            p *= l.scaleToParent.toDouble()
            l = l.parent
        }
        return p
    }

    override fun toString(): String = "ZoomLevel#$id(d$depth)"
}

/**
 * The level tree owned by one DrawingDocument. Each document gets its own
 * tree so tests and restored sessions never share mutable state; the global
 * [ZoomTree] object merely provides a default root for standalone Cameras
 * (the P0 sketch's singleton semantics, scoped per document).
 *
 * Level creation happens on the UI thread (pinch promote) while background
 * exporters walk the tree — mutation and traversal are synchronized, and
 * renderers snapshot [allLevels] / children lists.
 */
class LevelTree {

    val root: ZoomLevel = ZoomLevel(ZoomLevels.ROOT_ID, 0f, 0f, 1f, this)

    private val byIdMap = HashMap<Long, ZoomLevel>()
    private val ordered = mutableListOf<ZoomLevel>()
    private var nextId = 1L

    init {
        byIdMap[root.id] = root
        ordered.add(root)
    }

    @Synchronized
    fun byId(id: Long): ZoomLevel? = byIdMap[id]

    /** Levels in canonical order: root first, then creation order (parent
     *  always before child by construction). Stable across round trips. */
    @Synchronized
    fun allLevels(): List<ZoomLevel> = ordered.toList()

    @Synchronized
    fun levelCount(): Int = ordered.size

    /**
     * Finds an existing child of [parent] whose origin is within [tolerance]
     * parent units of (x, y), else creates one at that origin. Repeated
     * promotes at the same focus reuse the same level instead of forking
     * near-duplicate frames.
     */
    @Synchronized
    fun childAt(
        parent: ZoomLevel,
        x: Float,
        y: Float,
        tolerance: Float = ZoomLevels.CHILD_ORIGIN_TOLERANCE,
    ): ZoomLevel {
        for (c in parent.children) {
            if (abs(c.originX - x) <= tolerance && abs(c.originY - y) <= tolerance) return c
        }
        val child = ZoomLevel(newIdLocked(), x, y, ZoomLevels.THRESHOLD, this)
        attachLocked(child, parent)
        return child
    }

    /** Snapshot of a level's children (renderers iterate while the UI thread
     *  may promote into new children). */
    @Synchronized
    fun childrenOf(parent: ZoomLevel): List<ZoomLevel> = parent.children.toList()

    /** Codec use: rebuild a level with explicit ids; parent must exist. */
    @Synchronized
    fun attach(id: Long, parentId: Long, originX: Float, originY: Float, scaleToParent: Float): ZoomLevel {
        if (id == root.id || byIdMap.containsKey(id)) return byIdMap[id] ?: root
        val parent = byIdMap[parentId] ?: root
        val level = ZoomLevel(id, originX, originY, scaleToParent, parent.tree)
        attachLocked(level, parent)
        if (id >= nextId) nextId = id + 1
        return level
    }

    /** Drops every level except the root (before a v3 restore rebuild). */
    @Synchronized
    fun clear() {
        root.children.clear()
        byIdMap.clear()
        byIdMap[root.id] = root
        ordered.clear()
        ordered.add(root)
        nextId = 1L
    }

    private fun attachLocked(level: ZoomLevel, parent: ZoomLevel) {
        level.parent = parent
        parent.children.add(level)
        byIdMap[level.id] = level
        ordered.add(level)
        if (level.id >= nextId) nextId = level.id + 1
    }

    private fun newIdLocked(): Long = nextId++
}

/** Constants for the nested-level system. */
object ZoomLevels {
    const val ROOT_ID = 0L

    /** Fixed promotion threshold: localScale is kept within ~[1, 100]. */
    const val THRESHOLD = 100f

    /** childAt origin-reuse tolerance, in PARENT units (≈100 screen px at
     *  the promote boundary where parent scale == THRESHOLD). */
    const val CHILD_ORIGIN_TOLERANCE = 1f
}

/** Spec-sketch singleton: a default tree for Cameras without a document. */
object ZoomTree {
    val root: ZoomLevel get() = default.root
    val default = LevelTree()

    fun byId(id: Long): ZoomLevel? = default.byId(id)
    fun childAt(parent: ZoomLevel, x: Float, y: Float): ZoomLevel =
        parent.tree.childAt(parent, x, y)
}
