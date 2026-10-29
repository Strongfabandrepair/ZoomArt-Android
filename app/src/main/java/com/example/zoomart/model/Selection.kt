package com.example.zoomart.model

import kotlin.math.cos
import kotlin.math.sin

/** Pure, JVM-testable point transforms used by the selection/transform tools. */
object StrokeTransforms {

    fun translate(pts: List<StrokePoint>, dx: Float, dy: Float): List<StrokePoint> =
        pts.map { StrokePoint(it.x + dx, it.y + dy, it.pressure01) }

    fun scaleAbout(pts: List<StrokePoint>, px: Float, py: Float, sx: Float, sy: Float): List<StrokePoint> =
        pts.map { StrokePoint((it.x - px) * sx + px, (it.y - py) * sy + py, it.pressure01) }

    fun rotateAbout(pts: List<StrokePoint>, px: Float, py: Float, deg: Float): List<StrokePoint> {
        val a = Math.toRadians(deg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return pts.map {
            val dx = it.x - px
            val dy = it.y - py
            StrokePoint(px + dx * c - dy * s, py + dx * s + dy * c, it.pressure01)
        }
    }
}

/**
 * P4.3: render-time display filter for a placed image — a ColorMatrix
 * applied to the image paint ONLY (the bitmap data is never modified).
 * UI-state, deliberately NOT serialized (see docs/P4_IMAGE_UI.md): a
 * restored session shows the untouched image.
 */
enum class ImageDisplayFilter {
    NONE, GRAYSCALE, HIGH_CONTRAST;

    /** Panel cycle order: None -> Grayscale -> Contrast -> None. */
    fun cycled(): ImageDisplayFilter = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(s: String?): ImageDisplayFilter =
            if (s == null) NONE else runCatching { valueOf(s) }.getOrDefault(NONE)
    }
}

/**
 * A raster image placed on the canvas (imported underlay), in the local
 *  space of its zoom level ([levelId]; root == legacy world space).
 *
 * P4.2/P4.3: [alpha] is per-image transparency (0..1, applied via Paint
 * alpha at draw), [locked] makes the image un-selectable/un-movable so the
 * pen can draw over it freely, [rotation] is degrees about the image
 * center (two-finger pinch), [displayFilter] is the render-time ColorMatrix
 * toggle. alpha/locked/rotation serialize (additive codec fields);
 * displayFilter is UI-state and does not.
 */
class PlacedImage(
    val path: String,
    var x: Float,
    var y: Float,
    var w: Float,
    var h: Float,
    var levelId: Long = ZoomLevels.ROOT_ID,
    var alpha: Float = 1f,
    var locked: Boolean = false,
    var rotation: Float = 0f,
) {
    /** UI-state display filter (grayscale / high-contrast render toggle). */
    var displayFilter: ImageDisplayFilter = ImageDisplayFilter.NONE

    /** P5 gap: horizontal mirror toggle for the image (FLIP tool). */
    var flipH: Boolean = false

    /** P4.2: applies a [x, y, w, h, rotation, flipH] record (undo/redo snapshots). */
    fun applyProps(p: FloatArray) {
        x = p[0]; y = p[1]; w = p[2]; h = p[3]; rotation = p[4]
        if (p.size >= 6) flipH = p[5] != 0f
    }

    /** P4.2: snapshot [x, y, w, h, rotation, flipH] (undo/redo records). */
    fun props(): FloatArray = floatArrayOf(x, y, w, h, rotation, if (flipH) 1f else 0f)

    /**
     * Axis-aligned extents in the image's level-local frame. A rotated
     * image's corners are rotated about the center before taking min/max,
     * so culling and selection stay correct at any angle.
     */
    fun bbox(): FloatArray {
        if (rotation == 0f) return floatArrayOf(x, y, x + w, y + h)
        val a = Math.toRadians(rotation.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        val cx = x + w / 2f
        val cy = y + h / 2f
        val hw = w / 2f
        val hh = h / 2f
        val xs = floatArrayOf(
            cx + -hw * c - -hh * s, cx + hw * c - -hh * s,
            cx + hw * c - hh * s, cx + -hw * c - hh * s,
        )
        val ys = floatArrayOf(
            cy + -hw * s + -hh * c, cy + hw * s + -hh * c,
            cy + hw * s + hh * c, cy + -hw * s + hh * c,
        )
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }
}

/**
 * Selection/transform state for the active layer, mirroring Infinite
 * Design's transform tool: marquee select, then translate (drag), scale
 * (pinch about the selection center), rotate (90-degree steps), flip.
 * Every gesture records exactly ONE undoable command.
 *
 * P1.3b: selections span zoom LEVELS. A marquee rect is expressed in the
 * camera level's frame; strokes of every VISIBLE level are hit-tested by
 * projecting their bounding boxes into that frame (Camera.transformTo
 * composition). Transforms apply per stroke in its OWN level frame:
 * translate converts the camera-frame delta by each level's on-screen
 * scale, and scale/rotate/flip pivot at each level group's own bbox —
 * so a mixed selection drags as one visual unit and undo/redo stays
 * exact per stroke.
 */
class SelectionModel(private val doc: () -> DrawingDocument) {

    val selected = mutableSetOf<StrokeData>()

    /**
     * P3.1: shapes of the ACTIVE layer that are part of the selection.
     * The same invariants as strokes apply: marquee/tap can only pick
     * active-layer shapes, lock rejects everything, and every gesture
     * (move / corner-resize / pinch) records ONE undo step covering both
     * strokes and shapes.
     */
    val selectedShapes = mutableSetOf<ShapeData>()

    /**
     * P4.2: images of the ACTIVE layer that are part of the selection.
     * Locked images are never picked (tap or marquee); every transform
     * gesture (move / corner-resize / pinch scale+rotate) records ONE
     * undo step covering strokes, shapes and images together.
     */
    val selectedImages = mutableSetOf<PlacedImage>()

    /**
     * The camera whose frame marquee rects and drag deltas are expressed in.
     * Wired by DrawingViewModel (the document itself has no camera); null
     * falls back to single-frame semantics (all selected strokes assumed to
     * share one frame — the pre-P1 behavior).
     */
    var camera: Camera? = null

    private class Snapshot(val stroke: StrokeData, val before: List<StrokePoint>)
    private class ShapeSnapshot(val shape: ShapeData, val before: FloatArray)

    /** P4.2: [x, y, w, h, rotation] before the gesture. */
    private class ImageSnapshot(val image: PlacedImage, val before: FloatArray)

    private var pending: MutableList<Snapshot>? = null
    private var pendingShapes: MutableList<ShapeSnapshot>? = null
    private var pendingImages: MutableList<ImageSnapshot>? = null

    val isEmpty: Boolean
        get() = selected.isEmpty() && selectedShapes.isEmpty() && selectedImages.isEmpty()

    fun clear() {
        selected.clear()
        selectedShapes.clear()
        selectedImages.clear()
    }

    /**
     * Selection bbox in the CAMERA level's frame (strokes AND shapes of
     * other levels projected in), or a raw union of stroke-frame bboxes
     * when no camera is wired. Matches what the View draws as the
     * selection rectangle.
     */
    fun bbox(): FloatArray? {
        if (isEmpty) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        val cam = camera
        if (cam == null) {
            for (s in selected) {
                minX = minOf(minX, s.minX); minY = minOf(minY, s.minY)
                maxX = maxOf(maxX, s.maxX); maxY = maxOf(maxY, s.maxY)
            }
            for (sh in selectedShapes) {
                val b = sh.bbox()
                minX = minOf(minX, b[0]); minY = minOf(minY, b[1])
                maxX = maxOf(maxX, b[2]); maxY = maxOf(maxY, b[3])
            }
            for (img in selectedImages) {
                val b = img.bbox()
                minX = minOf(minX, b[0]); minY = minOf(minY, b[1])
                maxX = maxOf(maxX, b[2]); maxY = maxOf(maxY, b[3])
            }
            return floatArrayOf(minX, minY, maxX, maxY)
        }
        for (s in selected) {
            val lvl = cam.level.tree.byId(s.levelId) ?: continue
            val p = projectBox(cam, lvl, s.minX, s.minY, s.maxX, s.maxY)
            minX = minOf(minX, p[0]); minY = minOf(minY, p[1])
            maxX = maxOf(maxX, p[2]); maxY = maxOf(maxY, p[3])
        }
        for (sh in selectedShapes) {
            val lvl = cam.level.tree.byId(sh.levelId) ?: continue
            val b = sh.bbox()
            val p = projectBox(cam, lvl, b[0], b[1], b[2], b[3])
            minX = minOf(minX, p[0]); minY = minOf(minY, p[1])
            maxX = maxOf(maxX, p[2]); maxY = maxOf(maxY, p[3])
        }
        for (img in selectedImages) {
            val lvl = cam.level.tree.byId(img.levelId) ?: continue
            val b = img.bbox()
            val p = projectBox(cam, lvl, b[0], b[1], b[2], b[3])
            minX = minOf(minX, p[0]); minY = minOf(minY, p[1])
            maxX = maxOf(maxX, p[2]); maxY = maxOf(maxY, p[3])
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /**
     * P1.3b: marquee hit-test across levels. [minX..maxY] is expressed in
     * [camera]'s level frame; candidate strokes come from the active layer
     * restricted to [levels] (the visible set: camera chain + children).
     * Each stroke's bbox is projected into the camera frame before the rect
     * test. True when the selection is non-empty.
     *
     * P3.1: active-layer SHAPES join the marquee with the same projection.
     *
     * P2.2: a LOCKED active layer rejects marquee selection outright.
     */
    fun selectInRectAcrossLevels(
        camera: Camera,
        levels: Collection<ZoomLevel>,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
    ): Boolean {
        clear()
        if (doc().activeLayer.locked) return false
        for (s in doc().activeLayer.strokes) {
            val lvl = camera.level.tree.byId(s.levelId) ?: continue
            if (lvl !in levels) continue
            val p = projectBox(camera, lvl, s.minX, s.minY, s.maxX, s.maxY)
            if (p[2] >= minX && p[0] <= maxX && p[3] >= minY && p[1] <= maxY) {
                selected.add(s)
            }
        }
        for (sh in doc().activeLayer.shapes) {
            val lvl = camera.level.tree.byId(sh.levelId) ?: continue
            if (lvl !in levels) continue
            val b = sh.bbox()
            val p = projectBox(camera, lvl, b[0], b[1], b[2], b[3])
            if (p[2] >= minX && p[0] <= maxX && p[3] >= minY && p[1] <= maxY) {
                selectedShapes.add(sh)
            }
        }
        // P4.2: active-layer IMAGES join the marquee (locked ones never do)
        for (img in doc().activeLayer.images) {
            if (img.locked) continue
            val lvl = camera.level.tree.byId(img.levelId) ?: continue
            if (lvl !in levels) continue
            val b = img.bbox()
            val p = projectBox(camera, lvl, b[0], b[1], b[2], b[3])
            if (p[2] >= minX && p[0] <= maxX && p[3] >= minY && p[1] <= maxY) {
                selectedImages.add(img)
            }
        }
        return !isEmpty
    }

    /**
     * P3.1: TAP hit-test — the topmost shape of the active layer (within
     * the VISIBLE levels) whose projected bbox contains (camX, camY),
     * within [toleranceScreenPx] of screen slack. Selects ONLY that shape
     * (strokes cleared), so a tap selects one shape cleanly. Returns the
     * shape or null. A locked layer selects nothing.
     */
    fun selectShapeAt(
        camera: Camera,
        levels: Collection<ZoomLevel>,
        camX: Float,
        camY: Float,
        toleranceScreenPx: Float = 24f,
    ): ShapeData? {
        clear()
        if (doc().activeLayer.locked) return null
        val tol = toleranceScreenPx / camera.scale
        // topmost = LAST drawn: iterate the layer's shape list in reverse
        for (sh in doc().activeLayer.shapes.asReversed()) {
            val lvl = camera.level.tree.byId(sh.levelId) ?: continue
            if (lvl !in levels) continue
            val b = sh.bbox()
            val p = projectBox(camera, lvl, b[0], b[1], b[2], b[3])
            if (camX >= p[0] - tol && camX <= p[2] + tol &&
                camY >= p[1] - tol && camY <= p[3] + tol
            ) {
                selectedShapes.add(sh)
                return sh
            }
        }
        return null
    }

    /**
     * P4.2: TAP hit-test — the topmost image of the active layer (within the
     * VISIBLE levels) whose projected bbox contains (camX, camY) within
     * [toleranceScreenPx] of screen slack. Selects ONLY that image (strokes
     * and shapes cleared), so a tap selects one image cleanly. A LOCKED
     * image is never selected — draw over it freely instead.
     */
    fun selectImageAt(
        camera: Camera,
        levels: Collection<ZoomLevel>,
        camX: Float,
        camY: Float,
        toleranceScreenPx: Float = 24f,
    ): PlacedImage? {
        clear()
        if (doc().activeLayer.locked) return null
        val tol = toleranceScreenPx / camera.scale
        // topmost = LAST placed: iterate the layer's image list in reverse
        for (img in doc().activeLayer.images.asReversed()) {
            if (img.locked) continue
            val lvl = camera.level.tree.byId(img.levelId) ?: continue
            if (lvl !in levels) continue
            val b = img.bbox()
            val p = projectBox(camera, lvl, b[0], b[1], b[2], b[3])
            if (camX >= p[0] - tol && camX <= p[2] + tol &&
                camY >= p[1] - tol && camY <= p[3] + tol
            ) {
                selectedImages.add(img)
                return img
            }
        }
        return null
    }

    /** Projects a bbox of [lvl]'s frame into the camera level's local frame. */
    private fun projectBox(
        cam: Camera,
        lvl: ZoomLevel,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
    ): FloatArray {
        val t = cam.transformTo(lvl) // lvl-local -> screen: [k, tx, ty]
        val k = t[0] / cam.scale
        val ox = (t[1] - cam.transX) / cam.scale
        val oy = (t[2] - cam.transY) / cam.scale
        return floatArrayOf(
            minX * k + ox, minY * k + oy,
            maxX * k + ox, maxY * k + oy,
        )
    }

    fun beginTransform() {
        doc().pruneSelectionToActiveLayer()
        if (doc().activeLayer.locked) return // P2.2: locked layers reject transforms
        if (pending != null) return // already inside a gesture (move+pinch combo)
        pending = mutableListOf()
        pendingShapes = mutableListOf()
        pendingImages = mutableListOf()
        for (s in selected) pending?.add(Snapshot(s, s.points.toList()))
        for (sh in selectedShapes) {
            pendingShapes?.add(ShapeSnapshot(sh, floatArrayOf(sh.x0, sh.y0, sh.x1, sh.y1)))
        }
        for (img in selectedImages) {
            pendingImages?.add(ImageSnapshot(img, img.props()))
        }
    }

    /**
     * Camera-frame (dx, dy) converted into each stroke's OWN frame: the
     * displacement is dx*camScale pixels on screen, which is deltaLocal *
     * kLocal pixels in the stroke's frame, so deltaLocal = dx * camScale /
     * kLocal. Strokes of the camera's level (and any unwired camera) keep
     * the raw delta. P3.1: selected SHAPES translate by the same factor.
     */
    fun translateBy(dx: Float, dy: Float) {
        val cam = camera
        for (snap in pending ?: return) {
            val f = frameFactor(cam, snap.stroke)
            snap.stroke.setPoints(StrokeTransforms.translate(snap.stroke.points, dx * f, dy * f))
        }
        for (snap in pendingShapes ?: return) {
            val f = shapeFrameFactor(cam, snap.shape)
            snap.shape.setAnchors(
                snap.shape.x0 + dx * f, snap.shape.y0 + dy * f,
                snap.shape.x1 + dx * f, snap.shape.y1 + dy * f,
            )
        }
        // P4.2: images drag with the same per-level frame conversion
        for (snap in pendingImages ?: return) {
            val f = imageFrameFactor(cam, snap.image)
            snap.image.x += dx * f
            snap.image.y += dy * f
        }
    }

    private fun frameFactor(cam: Camera?, stroke: StrokeData): Float {
        if (cam == null || stroke.levelId == cam.level.id) return 1f
        val lvl = cam.level.tree.byId(stroke.levelId) ?: return 1f
        val k = cam.transformTo(lvl)[0]
        if (!k.isFinite() || k <= 0f) return 1f
        return cam.scale / k
    }

    /** Same frame conversion as [frameFactor], for a shape's level. */
    private fun shapeFrameFactor(cam: Camera?, shape: ShapeData): Float {
        if (cam == null || shape.levelId == cam.level.id) return 1f
        val lvl = cam.level.tree.byId(shape.levelId) ?: return 1f
        val k = cam.transformTo(lvl)[0]
        if (!k.isFinite() || k <= 0f) return 1f
        return cam.scale / k
    }

    /** P4.2: same frame conversion, for an image's level. */
    private fun imageFrameFactor(cam: Camera?, image: PlacedImage): Float {
        if (cam == null || image.levelId == cam.level.id) return 1f
        val lvl = cam.level.tree.byId(image.levelId) ?: return 1f
        val k = cam.transformTo(lvl)[0]
        if (!k.isFinite() || k <= 0f) return 1f
        return cam.scale / k
    }

    fun scaleBy(factor: Float) = pivotPerLevel(
        imageTransform = { img, px, py ->
            img.x = px + (img.x - px) * factor
            img.y = py + (img.y - py) * factor
            img.w *= factor
            img.h *= factor
        },
    ) { pts, cx, cy -> StrokeTransforms.scaleAbout(pts, cx, cy, factor, factor) }

    fun scaleAbout(px: Float, py: Float, sx: Float, sy: Float) =
        pivotPerLevel { pts, _, _ -> StrokeTransforms.scaleAbout(pts, px, py, sx, sy) }

    fun rotate(deg: Float) = pivotPerLevel(
        // P4.2: an image's CENTER pivots with its level group and its
        // rotation angle accumulates — a rigid rotate for the bitmap.
        imageTransform = { img, px, py ->
            val a = Math.toRadians(deg.toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            val cx = img.x + img.w / 2f
            val cy = img.y + img.h / 2f
            img.x = px + (cx - px) * c - (cy - py) * s - img.w / 2f
            img.y = py + (cx - px) * s + (cy - py) * c - img.h / 2f
            img.rotation += deg
        },
    ) { pts, cx, cy -> StrokeTransforms.rotateAbout(pts, cx, cy, deg) }

    /** P5 gap: FLIP now mirrors images horizontally too (in addition to strokes/shapes).
     * Images toggle their flipH flag and mirror their x position around the pivot.
     * Use two-finger pinch (rotate+scale) for arbitrary rotation. */
    fun flip(horizontal: Boolean) = pivotPerLevel(
        pivotCorner = true,
        imageTransform = { img, px, py ->
            img.flipH = !img.flipH
            if (horizontal) img.x = 2f * px - img.x - img.w
            else {
                img.y = 2f * py - img.y - img.h
                img.rotation += 180f
            }
        },
    ) { pts, px, py ->
        if (horizontal) StrokeTransforms.scaleAbout(pts, px, py, -1f, 1f)
        else StrokeTransforms.scaleAbout(pts, px, py, 1f, -1f)
    }

    /**
     * P3.1: uniform corner-handle RESIZE about a pivot expressed in the
     * CAMERA level's frame (the corner OPPOSITE the dragged handle). Each
     * level group scales about the pivot projected into its own frame, so
     * mixed-level selections resize coherently in each frame (the same
     * honest per-level-group semantics as rotate/flip). Applied to the
     * pending snapshots (live), undone as ONE step.
     */
    fun scaleAboutCameraPoint(pivotCamX: Float, pivotCamY: Float, factor: Float) {
        val cam = camera ?: return
        for (snap in pending ?: return) {
            val lvl = cam.level.tree.byId(snap.stroke.levelId) ?: continue
            val (px, py) = camPointToLevel(cam, lvl, pivotCamX, pivotCamY)
            snap.stroke.setPoints(
                StrokeTransforms.scaleAbout(snap.stroke.points, px, py, factor, factor)
            )
        }
        for (snap in pendingShapes ?: return) {
            val lvl = cam.level.tree.byId(snap.shape.levelId) ?: continue
            val (px, py) = camPointToLevel(cam, lvl, pivotCamX, pivotCamY)
            val a = StrokeTransforms.scaleAbout(
                listOf(
                    StrokePoint(snap.shape.x0, snap.shape.y0, 0f),
                    StrokePoint(snap.shape.x1, snap.shape.y1, 0f),
                ),
                px, py, factor, factor,
            )
            snap.shape.setAnchors(a[0].x, a[0].y, a[1].x, a[1].y)
        }
        // P4.2: a selected image resizes uniformly about the SAME opposite
        // corner (pivot projected into the image's own level frame); the
        // rotation angle is preserved (uniform scale never distorts it).
        for (snap in pendingImages ?: return) {
            val lvl = cam.level.tree.byId(snap.image.levelId) ?: continue
            val (px, py) = camPointToLevel(cam, lvl, pivotCamX, pivotCamY)
            snap.image.x = px + (snap.image.x - px) * factor
            snap.image.y = py + (snap.image.y - py) * factor
            snap.image.w *= factor
            snap.image.h *= factor
        }
    }

    /** Camera-frame point -> [lvl]'s local frame (inverse of projectBox). */
    private fun camPointToLevel(cam: Camera, lvl: ZoomLevel, x: Float, y: Float): Pair<Float, Float> {
        if (lvl === cam.level) return x to y
        val t = cam.transformTo(lvl)
        val k = t[0] / cam.scale
        val ox = (t[1] - cam.transX) / cam.scale
        val oy = (t[2] - cam.transY) / cam.scale
        return ((x - ox) / k) to ((y - oy) / k)
    }

    /**
     * Applies [transform] to every pending stroke, pivoting at the center
     * (or the min corner when [pivotCorner]) of each LEVEL GROUP's own
     * bbox. Single-level selections reduce to the pre-P1 pivots exactly;
     * mixed-level selections transform each frame's strokes coherently in
     * that frame (a rigid group rotation across frames is impossible
     * without re-leveling content — each stroke pivots about its own
     * group's center instead).
     *
     * P3.1: pending SHAPES join their level group's bbox and transform with
     * the same pivot (their anchors scale about it).
     */
    private fun pivotPerLevel(
        pivotCorner: Boolean = false,
        imageTransform: ((PlacedImage, Float, Float) -> Unit)? = null,
        transform: (List<StrokePoint>, Float, Float) -> List<StrokePoint>,
    ) {
        val snaps = pending ?: return
        val groups = LinkedHashMap<Long, MutableList<Any>>()
        for (snap in snaps) groups.getOrPut(snap.stroke.levelId) { mutableListOf() }.add(snap)
        for (snap in pendingShapes ?: emptyList()) {
            groups.getOrPut(snap.shape.levelId) { mutableListOf() }.add(snap)
        }
        // P4.2: selected images join their level group's bbox; the
        // imageTransform handler decides whether they transform (flip
        // passes null — images keep their placement).
        if (imageTransform != null) {
            for (snap in pendingImages ?: emptyList()) {
                groups.getOrPut(snap.image.levelId) { mutableListOf() }.add(snap)
            }
        }
        for (group in groups.values) {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (m in group) {
                val b = when (m) {
                    is Snapshot -> floatArrayOf(m.stroke.minX, m.stroke.minY, m.stroke.maxX, m.stroke.maxY)
                    is ShapeSnapshot -> m.shape.bbox()
                    is ImageSnapshot -> m.image.bbox()
                    else -> continue
                }
                minX = minOf(minX, b[0]); minY = minOf(minY, b[1])
                maxX = maxOf(maxX, b[2]); maxY = maxOf(maxY, b[3])
            }
            val px = if (pivotCorner) minX else minX + (maxX - minX) / 2f
            val py = if (pivotCorner) minY else minY + (maxY - minY) / 2f
            for (m in group) {
                when (m) {
                    is Snapshot -> m.stroke.setPoints(transform(m.stroke.points, px, py))
                    is ShapeSnapshot -> {
                        val a = transform(
                            listOf(
                                StrokePoint(m.shape.x0, m.shape.y0, 0f),
                                StrokePoint(m.shape.x1, m.shape.y1, 0f),
                            ),
                            px, py,
                        )
                        m.shape.setAnchors(a[0].x, a[0].y, a[1].x, a[1].y)
                    }
                    is ImageSnapshot -> imageTransform?.invoke(m.image, px, py)
                }
            }
        }
    }

    /** Records the live transform as ONE undoable command (strokes + shapes
     *  + P4.2 images). */
    fun cancelTransform() {
        pending?.forEach { it.stroke.setPoints(it.before) }
        pendingShapes?.forEach { it.shape.setAnchors(it.before[0],it.before[1],it.before[2],it.before[3]) }
        pendingImages?.forEach { it.image.applyProps(it.before) }
        pending=null; pendingShapes=null; pendingImages=null
        doc().reindex()
    }

    fun endTransform() {
        val snaps = pending ?: return
        val shapeSnaps = pendingShapes
        val imageSnaps = pendingImages
        pending = null
        pendingShapes = null
        pendingImages = null
        if (snaps.isEmpty() && shapeSnaps.isNullOrEmpty() && imageSnaps.isNullOrEmpty()) return
        doc().recordAppliedTransform(
            snaps.map { it.stroke to (it.before to it.stroke.points.toList()) },
            (shapeSnaps ?: emptyList()).map {
                it.shape to (it.before to floatArrayOf(it.shape.x0, it.shape.y0, it.shape.x1, it.shape.y1))
            },
            (imageSnaps ?: emptyList()).map {
                it.image to (it.before to
                    it.image.props())
            },
        )
    }

    fun deleteSelection(): Boolean {
        if (isEmpty) return false
        if (doc().activeLayer.locked) return false // P2.2: locked layers keep their strokes
        val layer = doc().activeLayer
        val removed = selected.toList()
        val removedShapes = selectedShapes.toList()
        val removedImages = selectedImages.toList()
        doc().recordAppliedRemove(layer, removed, removedShapes, removedImages)
        clear()
        return true
    }
}
