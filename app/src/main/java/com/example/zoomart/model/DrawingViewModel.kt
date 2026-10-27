package com.example.zoomart.model

/**
 * App-level state: camera + document + current brush + in-progress stroke.
 * Touch events arrive from the View already converted to WORLD coordinates;
 * this class holds no Android imports by design so the whole drawing
 * pipeline is verifiable on the JVM.
 */
class DrawingViewModel(
    val camera: Camera = Camera(),
    var document: DrawingDocument = DrawingDocument(),
) {
    var currentBrush: BrushSpec = BrushSpec()
    var inProgress: StrokeData? = null

    init {
        // The camera always tracks this document's level tree (P0).
        camera.level = document.levelTree.root
        // P1.3b: selection rects/deltas are expressed in the camera's frame.
        document.selection.camera = camera
    }

    fun beginStroke(worldX: Float, worldY: Float, pressure01: Float) {
        // P2.2: locked layers reject new strokes (visibility unaffected).
        if (document.activeLayer.locked) return
        // P0: the stroke lives in the CURRENT level, and brush width-like
        // fields are converted to level-local units at commit time so the
        // on-screen width stays constant across zoom levels.
        val stroke = StrokeData(brush = currentBrush.scaledForLocalScale(camera.scale))
        stroke.levelId = camera.level.id
        stroke.addPoint(StrokePoint(worldX, worldY, pressure01))
        inProgress = stroke
    }

    /** Returns true when the point passed the zoom-aware distance filter. */
    fun extendStroke(worldX: Float, worldY: Float, pressure01: Float): Boolean {
        val stroke = inProgress ?: return false
        val candidate = StrokePoint(worldX, worldY, pressure01)
        if (!StrokeSmoother.shouldAdd(stroke, candidate, camera)) return false
        stroke.addPoint(candidate)
        return true
    }

    /** Finishes the stroke; commits it unless it degenerated to a tap. */
    fun endStroke(): StrokeData? {
        val stroke = inProgress
        inProgress = null
        return if (stroke != null && document.commitStroke(stroke)) stroke else null
    }

    // ----- P3.2 lazy brush (screen-space rope feeding the stroke commit) -----

    /** Master toggle; the rope radius lives in [lazyBrush] (screen px 0-200). */
    var lazyEnabled = false
    val lazyBrush = LazyBrush()

    /** True when the stabilizer actually trails the pen (toggle + radius). */
    val lazyActive: Boolean get() = lazyEnabled && lazyBrush.radiusScreen > 0f

    /**
     * DRAW entry in SCREEN coordinates (the View always routes stylus
     * drawing through this; it converts to level-local itself). When the
     * stabilizer is on, the rope's brush point — not the raw pen — becomes
     * the stroke sample, converted through the camera so density is
     * correct at any zoom/level.
     */
    fun beginStrokeAtScreen(screenX: Float, screenY: Float, pressure01: Float) {
        if (lazyActive) {
            lazyBrush.begin(screenX, screenY)
            beginStroke(camera.screenToLocalX(screenX), camera.screenToLocalY(screenY), pressure01)
        } else {
            beginStroke(camera.screenToLocalX(screenX), camera.screenToLocalY(screenY), pressure01)
        }
    }

    /** Rope-fed extend; returns true when a point joined the stroke. */
    fun extendStrokeAtScreen(screenX: Float, screenY: Float, pressure01: Float): Boolean {
        if (!lazyActive) {
            return extendStroke(
                camera.screenToLocalX(screenX), camera.screenToLocalY(screenY), pressure01)
        }
        val b = lazyBrush.feed(screenX, screenY)
        return extendStroke(camera.screenToLocalX(b[0]), camera.screenToLocalY(b[1]), pressure01)
    }

    /**
     * Ends the stroke; when the stabilizer was active, the committed
     * points are REPLACED by their midpoint-quad-smoothed versions and
     * the stroke is flagged with the taper fraction so the renderer tapers
     * its ends (both are features of the lazy brush; eraser/select are
     * untouched — they act on geometry only).
     */
    fun endStrokeLazy(): StrokeData? {
        val stroke = inProgress ?: return null
        if (lazyActive && stroke.points.size >= 2) {
            val smoothed = LazyBrush.smoothPoints(stroke.points.toList())
            if (smoothed.size >= 2) {
                stroke.setPoints(smoothed)
                stroke.taper = LazyBrush.TAPER_FRACTION
            }
        }
        lazyBrush.end()
        return endStroke()
    }

    // ----- P3.1 shapes (drag with live rubber-band preview) -----

    var armedShapeType: ShapeType = ShapeType.RECTANGLE
    var shapeFillOn = true
    var shapeFillColor: Int = 0xFF2E66F6.toInt()
    var shapeOutlineColor: Int = 0xFF16181D.toInt()
    /** Outline weight in SCREEN px at 1x (converted to level-local at commit). */
    var shapeOutlineWidthScreen = 6f
    var shapeRoundness = 0.25f
    var shapePointCount = 5
    var shapeConstrain = false
    var shapeFromCenter = false

    /** Live rubber-band shape while a SHAPE gesture runs (rendered like inProgress). */
    var inProgressShape: ShapeData? = null
    private var shapeAnchorX = 0f
    private var shapeAnchorY = 0f

    /** Begins a shape drag at the level-local point under the pen. */
    fun beginShapeGesture(localX: Float, localY: Float) {
        if (document.activeLayer.locked) return // P2.2: locked layers take no content
        val shape = ShapeData(armedShapeType, camera.level.id)
        shape.fillOn = shapeFillOn
        shape.fillColor = shapeFillColor
        shape.outlineColor = shapeOutlineColor
        shape.cornerRoundness = shapeRoundness
        shape.pointCount = shapePointCount
        shape.setAnchors(localX, localY, localX, localY)
        shapeAnchorX = localX
        shapeAnchorY = localY
        inProgressShape = shape
    }

    /** Rubber-bands the drag corner (constrain / from-center applied live). */
    fun extendShapeGesture(localX: Float, localY: Float) {
        val shape = inProgressShape ?: return
        val a = ShapeGeometry.resolveAnchors(
            shape.type, shapeAnchorX, shapeAnchorY, localX, localY,
            shapeConstrain, shapeFromCenter,
        )
        shape.setAnchors(a[0], a[1], a[2], a[3])
    }

    /**
     * Commits the dragged shape (one undo step). The outline width
     * converts from screen px to level-local units at commit — the same
     * screen-relative contract as brush widths. Degenerate taps and
     * locked layers commit nothing.
     */
    fun endShapeGesture(): ShapeData? {
        val shape = inProgressShape
        inProgressShape = null
        if (shape == null || shape.isDegenerate()) return null
        shape.outlineWidthWorld = shapeOutlineWidthScreen / camera.scale
        return if (document.commitShape(shape)) shape else null
    }

    // ----- P3.3 smudge (sampled-color carry -> vector soft stamps) -----

    var smudgeStrength = 0.7f   // 0..1
    var smudgeSizeScreen = 40f  // stamp diameter in screen px
    var smudgeSoftness = 0.5f   // 0 = hard edge, 1 = fully soft falloff
    var smudgeIntervalScreenPx = SmudgeMath.DEFAULT_INTERVAL_SCREEN_PX

    /** Live gesture trail while smudging (rendered like inProgress). */
    var inProgressSmudge: SmudgeData? = null
    private var smudgeCarried = 0xFF shl 24
    private var smudgeLastX = 0f
    private var smudgeLastY = 0f

    /**
     * Begins a smudge gesture: [sampler] reads the COMPOSED color at
     * (screen px) — the View renders the full scene (blends included) to
     * one snapshot bitmap at gesture start and samples from it, which the
     * spec explicitly allows. The first stamp deposits the color under the
     * pen.
     */
    fun beginSmudge(screenX: Float, screenY: Float, pressure01: Float, sampler: (Int, Int) -> Int) {
        if (document.activeLayer.locked) return // P2.2
        smudgeCarried = 0xFF shl 24 or sampler(screenX.toInt(), screenY.toInt())
        val smudge = SmudgeData(camera.level.id)
        inProgressSmudge = smudge
        smudgeLastX = camera.screenToLocalX(screenX)
        smudgeLastY = camera.screenToLocalY(screenY)
        depositStamp(smudge, screenX, screenY, pressure01)
    }

    /**
     * Sample step: re-samples the composed color under the pen, mixes the
     * carried color 80/20, and deposits a stamp when the pen has moved at
     * least [smudgeIntervalScreenPx] screen px since the last deposit.
     * Returns true when a stamp landed.
     */
    fun extendSmudge(screenX: Float, screenY: Float, pressure01: Float, sampler: (Int, Int) -> Int): Boolean {
        val smudge = inProgressSmudge ?: return false
        val localX = camera.screenToLocalX(screenX)
        val localY = camera.screenToLocalY(screenY)
        if (!SmudgeMath.shouldDeposit(
                smudgeLastX, smudgeLastY, localX, localY,
                smudgeIntervalScreenPx, camera.scale)
        ) return false
        smudgeCarried = SmudgeMath.mixCarried(
            smudgeCarried, 0xFF shl 24 or sampler(screenX.toInt(), screenY.toInt()))
        depositStamp(smudge, screenX, screenY, pressure01)
        smudgeLastX = localX
        smudgeLastY = localY
        return true
    }

    private fun depositStamp(smudge: SmudgeData, screenX: Float, screenY: Float, pressure01: Float) {
        smudge.stamps.add(
            StampData(
                x = camera.screenToLocalX(screenX),
                y = camera.screenToLocalY(screenY),
                radius = SmudgeMath.radiusLocal(smudgeSizeScreen, camera.scale),
                color = smudgeCarried,
                alpha = SmudgeMath.stampAlpha(smudgeStrength, pressure01),
            )
        )
    }

    /** Commits the whole stamp trail as ONE undoable gesture. */
    fun endSmudge(): SmudgeData? {
        val smudge = inProgressSmudge
        inProgressSmudge = null
        if (smudge == null || smudge.stamps.isEmpty()) return null
        return if (document.commitSmudge(smudge)) smudge else null
    }

    // ----- eraser (splits strokes under the eraser path; one undo per gesture) -----

    var eraserRadiusWorld = 40f
    private val eraseCenters = mutableListOf<StrokePoint>()
    private val erasePairs = mutableListOf<Pair<StrokeData, List<StrokeData>>>()

    fun beginErase(worldX: Float, worldY: Float) {
        eraseCenters.clear()
        erasePairs.clear()
        eraseCenters.add(StrokePoint(worldX, worldY, 0f))
    }

    /**
     * P4.6 (the long-deferred carryover): CROSS-LEVEL ERASER. The eraser
     * circle is expressed in the CAMERA's frame; candidate strokes of the
     * active layer in every VISIBLE level (camera chain + children) are
     * matched by projecting their bboxes into the camera frame, and the
     * erase centers + radius are converted into each stroke's OWN frame
     * via the scale chain before splitting (radius_local = radius_cam x
     * camScale / k_level). Fragments inherit the erased stroke's levelId
     * (StrokeEraser copies it), so a cut child-level stroke stays child-
     * level content. Strokes of the camera's own level reduce to the
     * exact pre-P4 math. P2.2: a locked layer rejects the eraser entirely.
     */
    fun eraseTo(worldX: Float, worldY: Float): Boolean {
        val layer = document.activeLayer
        if (layer.locked) return false
        eraseCenters.add(StrokePoint(worldX, worldY, 0f))
        val cam = camera
        // screen-constant eraser: the radius in CAMERA-local units
        val radiusCam = eraserRadiusWorld / cam.scale
        // camera-frame bbox of the whole erase path, padded by the radius —
        // the cheap pre-filter before any per-level conversion
        var pMinX = Float.MAX_VALUE; var pMinY = Float.MAX_VALUE
        var pMaxX = -Float.MAX_VALUE; var pMaxY = -Float.MAX_VALUE
        for (c in eraseCenters) {
            pMinX = minOf(pMinX, c.x); pMinY = minOf(pMinY, c.y)
            pMaxX = maxOf(pMaxX, c.x); pMaxY = maxOf(pMaxY, c.y)
        }
        pMinX -= radiusCam; pMinY -= radiusCam; pMaxX += radiusCam; pMaxY += radiusCam
        val visibleById = HashSet<Long>()
        for (lvl in visibleLevels()) visibleById.add(lvl.id)
        var changed = false
        for (stroke in layer.strokes.toList()) {
            if (stroke.levelId !in visibleById) continue
            val lvl = cam.level.tree.byId(stroke.levelId) ?: continue
            val t = cam.transformTo(lvl)
            val k = t[0]
            if (!k.isFinite() || k <= 0f) continue
            // project the stroke bbox into the camera frame (P1.3b math)
            val kk = k / cam.scale
            val ox = (t[1] - cam.transX) / cam.scale
            val oy = (t[2] - cam.transY) / cam.scale
            val sMinX = stroke.minX * kk + ox
            val sMinY = stroke.minY * kk + oy
            val sMaxX = stroke.maxX * kk + ox
            val sMaxY = stroke.maxY * kk + oy
            if (sMaxX < pMinX || sMinX > pMaxX || sMaxY < pMinY || sMinY > pMaxY) continue
            // convert only the centers that can possibly reach this stroke
            val radiusLvl = radiusCam * cam.scale / k
            val centers = ArrayList<StrokePoint>(eraseCenters.size)
            for (c in eraseCenters) {
                val lx = (cam.localToScreenX(c.x) - t[1]) / k
                val ly = (cam.localToScreenY(c.y) - t[2]) / k
                if (lx + radiusLvl >= stroke.minX && lx - radiusLvl <= stroke.maxX &&
                    ly + radiusLvl >= stroke.minY && ly - radiusLvl <= stroke.maxY
                ) {
                    centers.add(StrokePoint(lx, ly, 0f))
                }
            }
            if (centers.isEmpty()) continue
            val fragments = StrokeEraser.erase(stroke, centers, radiusLvl)
            val erased = fragments.size != 1 || fragments[0].points.size != stroke.points.size
            if (erased && document.replaceStrokeDirect(layer, stroke, fragments)) {
                erasePairs.add(stroke to fragments)
                changed = true
            }
        }
        return changed
    }

    fun endErase() {
        document.recordAppliedErase(document.activeLayer, erasePairs.toList())
        eraseCenters.clear()
        erasePairs.clear()
    }

    // ----- selection / transform (active layer) -----

    /**
     * The level set a marquee can see: the camera's parent chain (root down
     * to the camera level) plus the camera level's children — the same set
     * the renderer composes (drawScene).
     */
    fun visibleLevels(): List<ZoomLevel> {
        val chain = ArrayList<ZoomLevel>()
        var l: ZoomLevel? = camera.level
        while (l != null) {
            chain.add(l)
            l = l.parent
        }
        val out = ArrayList<ZoomLevel>(chain.size + 4)
        for (i in chain.indices.reversed()) out.add(chain[i]) // root first
        out.addAll(document.levelTree.childrenOf(camera.level))
        return out
    }

    /**
     * P1.3b: marquee select across every VISIBLE level. The rect is in the
     * camera level's frame; strokes of other levels are hit-tested by
     * projecting their bboxes into it.
     */
    fun selectInWorldRect(minX: Float, minY: Float, maxX: Float, maxY: Float): Boolean =
        document.selection.selectInRectAcrossLevels(camera, visibleLevels(), minX, minY, maxX, maxY)

    fun placeImage(path: String, aspect: Float): PlacedImage? {
        if (document.activeLayer.locked) return null // P2.2: locked layers take no content
        val vp = camera.visibleLocalRect(1080f, 2340f) // fallback if view unsized
        val w = (vp[2] - vp[0]) * 0.6f
        val h = w / aspect
        val cx = (vp[0] + vp[2]) / 2f
        val cy = (vp[1] + vp[3]) / 2f
        val img = PlacedImage(path, cx - w / 2f, cy - h / 2f, w, h, camera.level.id)
        document.activeLayer.images.add(img)
        return img
    }

    // ----- P4.1/P4.3: image v2 -----

    /**
     * P4.1: the import path MainActivity uses — the bitmap lands on a NEW
     * layer above the active one, which becomes active (ONE undo step;
     * see DrawingDocument.importImageOnNewLayer). The legacy
     * [placeImage] stays for the pre-P4 active-layer contract the tests
     * pin.
     */
    fun importImageOnNewLayer(path: String, aspect: Float): PlacedImage? =
        document.importImageOnNewLayer(path, aspect, camera)

    /**
     * P4.3: the image the Image panel acts on — the SELECTED image when
     * the Select tool holds one, else the most recently placed image on
     * the active layer.
     */
    fun currentImage(): PlacedImage? {
        document.selection.selectedImages.firstOrNull()?.let { return it }
        return document.activeLayer.images.lastOrNull()
    }

    /** P4.3: one-tap tracing mode (alpha 40% + lock + fresh layer above). */
    fun beginTracingMode(image: PlacedImage): Boolean =
        document.beginTracingMode(image)

    // ----- P4.5: demo content (nested rings + camera tour targets) -----

    /** What [seedDemoContent] built — the flight's level targets per leg. */
    class DemoPlan(val levels: List<ZoomLevel>, val layers: List<LayerData>)

    /** Number of nested demo levels (root ring + 3 deeper rings). */
    val demoDepth: Int get() = DEMO_DEPTHS

    /** Half-extent of every demo ring, in its OWN level's local units. */
    val demoHalfLocal: Float get() = DEMO_HALF_LOCAL

    /**
     * P4.5: DEMO — programmatically draws nested content with the app's
     * own model APIs: a chain of [DEMO_DEPTHS] zoom levels rooted at the
     * origin (childAt origin reuse — repeated runs share levels instead
     * of forking duplicates), each carrying a FRESH layer with concentric
     * star/rect/ellipse shapes centered on (0,0) in that level's local
     * frame. Zooming in 100x per step reveals the next ring filling the
     * screen exactly like the previous one did (the classic endless-zoom
     * effect).
     *
     * UNDO DECISION (documented): seeded WITHOUT commands — demo scenery
     * is not a user action, so the undo stack stays clean and Undo/Redo
     * keep covering real edits only. The demo layers are ordinary layers:
     * delete/reorder/lock them in the Layers panel if unwanted.
     */
    fun seedDemoContent(): DemoPlan {
        val tree = camera.level.tree
        val levels = ArrayList<ZoomLevel>(DEMO_DEPTHS)
        var lvl = tree.root
        for (d in 0 until DEMO_DEPTHS) {
            if (d > 0) lvl = tree.childAt(lvl, 0f, 0f)
            levels.add(lvl)
        }
        val layers = ArrayList<LayerData>(DEMO_DEPTHS)
        for ((d, level) in levels.withIndex()) {
            // names count EXISTING layers so repeated runs stay distinguishable
            val layer = LayerData(name = "Demo " + (document.layers.size + 1))
            layer.shapes.add(
                demoShape(ShapeType.STAR, level.id, 0xFFE5484D.toInt(), filled = true, points = 5))
            layer.shapes.add(
                demoShape(ShapeType.RECTANGLE, level.id, 0xFF2E66F6.toInt(), filled = false, points = 5))
            layer.shapes.add(
                demoShape(ShapeType.ELLIPSE, level.id, 0xFF30A46C.toInt(), filled = false, points = 5))
            document.layers.add(layer)
            layers.add(layer)
        }
        return DemoPlan(levels, layers)
    }

    private fun demoShape(
        type: ShapeType,
        levelId: Long,
        color: Int,
        filled: Boolean,
        points: Int,
    ): ShapeData {
        val s = ShapeData(type, levelId)
        s.fillOn = filled
        s.fillColor = color
        s.outlineColor = color
        s.outlineWidthWorld = 6f
        s.pointCount = points
        s.setAnchors(-DEMO_HALF_LOCAL, -DEMO_HALF_LOCAL, DEMO_HALF_LOCAL, DEMO_HALF_LOCAL)
        return s
    }

    companion object {
        /** Demo ring count: the root ring plus three deeper rings. */
        const val DEMO_DEPTHS = 4

        /** Half-extent of a demo ring in its own level's local units. */
        const val DEMO_HALF_LOCAL = 300f
    }

    fun undo(): Boolean = document.undo()
    fun redo(): Boolean = document.redo()

    fun addBookmark(worldX: Float, worldY: Float, label: String): Bookmark =
        document.addBookmark(worldX, worldY, camera.scale, label, camera.level.id)

    fun serialize(): String = DocumentCodec.toJson(document, camera)

    /**
     * Restores a parsed session in place. The camera instance is kept (the
     * View may hold references to it); the document is swapped. If the file
     * contained no layers, a fallback layer keeps drawing safe.
     */
    fun restore(session: DocumentCodec.SessionData) {
        document = session.document
        document.selection.camera = camera // P1.3b: keep the camera wired
        camera.setTo(session.camera)
        if (document.layers.isEmpty()) {
            document.layers.add(LayerData(name = "Layer 1"))
        }
        document.activeLayerIndex = document.activeLayerIndex.coerceIn(0, document.layers.size - 1)
        // The camera must reference a level of the NEW document's tree.
        if (camera.level.tree !== document.levelTree) {
            camera.level = document.levelTree.byId(camera.level.id) ?: document.levelTree.root
        }
        inProgress = null
        inProgressShape = null
        inProgressSmudge = null
        document.reindex()
    }
}
