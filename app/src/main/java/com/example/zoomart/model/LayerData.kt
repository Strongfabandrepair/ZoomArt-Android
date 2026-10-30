package com.example.zoomart.model

/** A saved camera position ("pin point"), in the pin's own zoom level. */
data class Bookmark(
    val worldX: Float,      // level-local x (== root/world for levelId 0)
    val worldY: Float,
    val scale: Float,       // level-local scale
    val label: String,
    val levelId: Long = ZoomLevels.ROOT_ID,
)

/**
 * P2.3: per-layer compositing mode — layers are "stacked sheets of paper":
 * a non-NORMAL sheet composites over everything already drawn with this
 * mode (Android `BlendMode` on API 29+, `PorterDuff.Mode` fallback on
 * API 26-28; see ZoomableCanvasView.drawScene).
 */
enum class LayerBlend {
    NORMAL, MULTIPLY, SCREEN, OVERLAY;

    /** Panel blend-cycle order: Normal -> Multiply -> Screen -> Overlay -> Normal. */
    fun cycled(): LayerBlend = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * Codec read: unknown or absent strings fall back to NORMAL, so a
         * future mode added to the enum degrades gracefully instead of
         * making older session files unreadable.
         */
        fun fromName(s: String?): LayerBlend =
            if (s == null) NORMAL else runCatching { valueOf(s) }.getOrDefault(NORMAL)
    }
}

/** One drawable layer: an ordered list of world-space strokes. */
class LayerData(
    val id: Long = System.nanoTime(),
    var name: String = "Layer",
    var isVisible: Boolean = true,
    var opacity: Float = 1f,
    var locked: Boolean = false,                    // P2.2: rejects edits
    var blend: LayerBlend = LayerBlend.NORMAL,      // P2.3: compositing mode
) {
    val strokes = mutableListOf<StrokeData>()
    val images = mutableListOf<PlacedImage>()
    val shapes = mutableListOf<ShapeData>()      // P3.1: parametric shapes
    val smudges = mutableListOf<SmudgeData>()     // P3.3: smudge gestures

    /**
     * P2.1: deep copy for DuplicateLayer — NEW StrokeData objects with the
     * points/levelId copied in (the immutable BrushSpec is shared by
     * reference; it has no mutable fields) and NEW PlacedImage objects.
     * P3: shapes and smudge gestures copy as new objects too (new ShapeData
     * / SmudgeData / StampData), so duplicating a sheet with vector stamps
     * stays fully independent.
     * Mutating either layer afterwards leaves the other untouched.
     */
    fun deepCopy(): LayerData {
        val copy = LayerData(
            name = name, isVisible = isVisible, opacity = opacity,
            locked = locked, blend = blend,
        )
        for (s in strokes) {
            val ns = StrokeData(brush = s.brush)
            ns.levelId = s.levelId
            ns.taper = s.taper
            ns.setPoints(s.points.toList()) // recomputes the bbox too
            copy.strokes.add(ns)
        }
        for (img in images) {
            val imgCopy = PlacedImage(
                img.path, img.x, img.y, img.w, img.h, img.levelId,
                img.alpha, img.locked, img.rotation,
            )
            imgCopy.displayFilter = img.displayFilter
            imgCopy.flipH = img.flipH
            copy.images.add(imgCopy)
        }
        for (sh in shapes) copy.shapes.add(sh.deepCopy())
        for (sm in smudges) copy.smudges.add(sm.deepCopy())
        return copy
    }
}

/**
 * The whole drawing plus the undo/redo command stack. Stroke AND layer
 * operations go through reversible commands; a uniform-grid StrokeIndex
 * keeps viewport culling O(visible) for huge canvases and stays
 * synchronized for background exporters.
 */
class DrawingDocument {

    val layers = mutableListOf(
        LayerData(name = "Background"),
        LayerData(name = "Layer 1"),
    )
    var activeLayerIndex = 1
    val activeLayer: LayerData get() = layers[activeLayerIndex]

    val bookmarks = mutableListOf<Bookmark>()

    /**
     * P0: this document's zoom-level tree. Every stroke/image/bookmark
     * stores the id of the level it was created in; lookups go through
     * levelTree.byId. Restored documents rebuild their own tree (codec v3).
     */
    val levelTree = LevelTree()

    /** Spatial index over all committed strokes, bucketed per level. */
    val strokeIndex = StrokeIndex()

    /** Selection/transform state bound to the active layer. */
    val selection = SelectionModel({ this })

    private interface Command {
        fun redo()
        fun undo()
    }

    private class AddStrokeCommand(
        private val doc: DrawingDocument,
        private val layer: LayerData,
        private val stroke: StrokeData,
    ) : Command {
        override fun redo() {
            layer.strokes.add(stroke)
            doc.strokeIndex.insert(layer, stroke)
        }
        override fun undo() {
            layer.strokes.remove(stroke)
            doc.strokeIndex.remove(stroke)
        }
    }

    /** P3.1: one committed shape = one undoable step on the active layer. */
    private class AddShapeCommand(
        private val layer: LayerData,
        private val shape: ShapeData,
    ) : Command {
        override fun redo() { layer.shapes.add(shape) }
        override fun undo() { layer.shapes.remove(shape) }
    }

    /** P3.3: one smudge GESTURE (its whole stamp trail) = one undoable step. */
    private class AddSmudgeCommand(
        private val layer: LayerData,
        private val smudge: SmudgeData,
    ) : Command {
        override fun redo() { layer.smudges.add(smudge) }
        override fun undo() { layer.smudges.remove(smudge) }
    }

    private class AddLayerCommand(
        private val doc: DrawingDocument,
        private val layer: LayerData,
        private val index: Int,
        private val prevActive: Int,
    ) : Command {
        override fun redo() {
            doc.layers.add(index, layer)
            doc.activeLayerIndex = index
            doc.strokeIndex.rebuild(doc.layers)
            doc.pruneSelectionToActiveLayer()
        }
        override fun undo() {
            doc.layers.removeAt(index)
            doc.activeLayerIndex = prevActive
            doc.strokeIndex.rebuild(doc.layers)
            doc.pruneSelectionToActiveLayer()
        }
    }

    private class RemoveLayerCommand(
        private val doc: DrawingDocument,
        private val layer: LayerData,
        private val index: Int,
        private val prevActive: Int,
    ) : Command {
        override fun redo() {
            doc.layers.removeAt(index)
            if (index < doc.activeLayerIndex) doc.activeLayerIndex -= 1
            doc.activeLayerIndex = doc.activeLayerIndex.coerceIn(0, doc.layers.size - 1)
            doc.strokeIndex.rebuild(doc.layers)
            doc.pruneSelectionToActiveLayer()
        }
        override fun undo() {
            doc.layers.add(index, layer)
            doc.activeLayerIndex = prevActive
            doc.strokeIndex.rebuild(doc.layers)
            doc.pruneSelectionToActiveLayer()
        }
    }

    /** P2.1: inserts a pre-built deep copy above its source, makes it active. */
    private class DuplicateLayerCommand(
        private val doc: DrawingDocument,
        private val copy: LayerData,
        private val index: Int,
        private val prevActive: Int,
    ) : Command {
        override fun redo() {
            doc.layers.add(index, copy)
            doc.activeLayerIndex = index
            doc.strokeIndex.rebuild(doc.layers)
            doc.pruneSelectionToActiveLayer()
        }
        override fun undo() {
            doc.layers.removeAt(index)
            doc.activeLayerIndex = prevActive
            doc.strokeIndex.rebuild(doc.layers)
            doc.pruneSelectionToActiveLayer()
        }
    }

    private class RenameLayerCommand(
        private val layer: LayerData,
        private val oldName: String,
        private val newName: String,
    ) : Command {
        override fun redo() { layer.name = newName }
        override fun undo() { layer.name = oldName }
    }

    /**
     * P2.1: moves a layer from [from] to [to] (final index), keeping the
     * ACTIVE LAYER's identity: [activeAtExecute] is the active LayerData at
     * execute time, and its new position becomes activeLayerIndex after
     * every move. The spatial index needs no rebuild — owners are keyed by
     * object identity and never change.
     */
    private class ReorderLayerCommand(
        private val doc: DrawingDocument,
        private val from: Int,
        private val to: Int,
        private val activeAtExecute: LayerData,
    ) : Command {
        private fun move(f: Int, t: Int) {
            val layer = doc.layers.removeAt(f)
            doc.layers.add(t, layer)
            doc.activeLayerIndex = doc.layers.indexOf(activeAtExecute)
                .coerceIn(0, doc.layers.size - 1)
            doc.pruneSelectionToActiveLayer()
        }
        override fun redo() = move(from, to)
        override fun undo() = move(to, from)
    }

    private class SetLockCommand(
        private val layer: LayerData,
        private val before: Boolean,
        private val after: Boolean,
    ) : Command {
        override fun redo() { layer.locked = after }
        override fun undo() { layer.locked = before }
    }

    private class SetBlendCommand(
        private val layer: LayerData,
        private val before: LayerBlend,
        private val after: LayerBlend,
    ) : Command {
        override fun redo() { layer.blend = after }
        override fun undo() { layer.blend = before }
    }

    /** Records one opacity change; the panel records the pre-drag value as [before]. */
    private class SetLayerOpacityCommand(
        private val layer: LayerData,
        private val before: Float,
        private val after: Float,
    ) : Command {
        override fun redo() { layer.opacity = after }
        override fun undo() { layer.opacity = before }
    }

    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun execute(cmd: Command) {
        cmd.redo()
        undoStack.addLast(cmd)
        redoStack.clear()
    }

    /**
     * Commits a finished stroke; returns false (and discards) for < 2 points
     * or when the active layer is locked (P2.2 — the panel can flip the lock
     * mid-gesture via a second finger, so the final commit re-checks).
     */
    /** A symmetry gesture is a single atomic undo step. */
    fun commitStrokeGroup(strokes: List<StrokeData>): Boolean {
        if (activeLayer.locked || strokes.isEmpty() || strokes.any { it.points.size < 2 }) return false
        val commands = strokes.map { AddStrokeCommand(this, activeLayer, it) }
        execute(object : Command {
            override fun redo() { commands.forEach { it.redo() } }
            override fun undo() { commands.asReversed().forEach { it.undo() } }
        })
        return true
    }

    fun commitStroke(stroke: StrokeData): Boolean {
        if (stroke.points.size < 2) return false
        if (activeLayer.locked) return false
        execute(AddStrokeCommand(this, activeLayer, stroke))
        return true
    }

    /**
     * P3.1: commits a finished shape to the active layer (one undo step).
     * Degenerate (tapped) shapes and locked layers are rejected without
     * touching the undo stack — mirroring commitStroke's guards.
     */
    fun commitShape(shape: ShapeData): Boolean {
        if (shape.isDegenerate()) return false
        if (activeLayer.locked) return false
        execute(AddShapeCommand(activeLayer, shape))
        return true
    }

    /**
     * P3.3: commits a finished smudge gesture (all its stamps) as ONE
     * undo step. Empty gestures and locked layers are rejected.
     */
    fun commitSmudge(smudge: SmudgeData): Boolean {
        if (smudge.stamps.isEmpty()) return false
        if (activeLayer.locked) return false
        execute(AddSmudgeCommand(activeLayer, smudge))
        return true
    }

    fun undo(): Boolean {
        val cmd = undoStack.removeLastOrNull() ?: return false
        cmd.undo()
        pruneSelectionToActiveLayer()
        redoStack.addLast(cmd)
        return true
    }

    fun redo(): Boolean {
        val cmd = redoStack.removeLastOrNull() ?: return false
        cmd.redo()
        undoStack.addLast(cmd)
        pruneSelectionToActiveLayer()
        return true
    }

    // ----- selection transforms: applied live, recorded as ONE undo step -----

    /**
     * Records an already-applied batch of point transforms as one command.
     * P3.1: [shapePairs] carries anchor before/after records
     * (x0, y0, x1, y1) for transformed SHAPES in the same single undo step.
     * P4.2: [imagePairs] carries [x, y, w, h, rotation] before/after
     * records for transformed IMAGES in the same single undo step.
     */
    fun recordAppliedTransform(
        pairs: List<Pair<StrokeData, Pair<List<StrokePoint>, List<StrokePoint>>>>,
        shapePairs: List<Pair<ShapeData, Pair<FloatArray, FloatArray>>> = emptyList(),
        imagePairs: List<Pair<PlacedImage, Pair<FloatArray, FloatArray>>> = emptyList(),
    ) {
        val changed = pairs.filter { it.second.first != it.second.second }
        val changedShapes = shapePairs.filter { !it.second.first.contentEquals(it.second.second) }
        val changedImages = imagePairs.filter { !it.second.first.contentEquals(it.second.second) }
        if (changed.isEmpty() && changedShapes.isEmpty() && changedImages.isEmpty()) return
        // Live transforms change bounds; update the index before the next paint/query.
        for ((stroke, _) in changed) strokeIndex.insert(docOwnerOf(stroke), stroke)
        undoStack.addLast(TransformStrokesCommand(this, changed, changedShapes, changedImages))
        redoStack.clear()
    }

    private class TransformStrokesCommand(
        private val doc: DrawingDocument,
        private val pairs: List<Pair<StrokeData, Pair<List<StrokePoint>, List<StrokePoint>>>>,
        private val shapePairs: List<Pair<ShapeData, Pair<FloatArray, FloatArray>>>,
        private val imagePairs: List<Pair<PlacedImage, Pair<FloatArray, FloatArray>>>,
    ) : Command {
        override fun redo() {
            for ((stroke, pair) in pairs) {
                doc.strokeIndex.remove(stroke)
                stroke.setPoints(pair.second)
                doc.strokeIndex.insert(doc.docOwnerOf(stroke), stroke)
            }
            for ((shape, pair) in shapePairs) shape.setAnchors(
                pair.second[0], pair.second[1], pair.second[2], pair.second[3])
            for ((img, pair) in imagePairs) img.applyProps(pair.second)
        }
        override fun undo() {
            for ((stroke, pair) in pairs.reversed()) {
                doc.strokeIndex.remove(stroke)
                stroke.setPoints(pair.first)
                doc.strokeIndex.insert(doc.docOwnerOf(stroke), stroke)
            }
            for ((shape, pair) in shapePairs.reversed()) shape.setAnchors(
                pair.first[0], pair.first[1], pair.first[2], pair.first[3])
            for ((img, pair) in imagePairs.reversed()) img.applyProps(pair.first)
        }
    }

    /**
     * Records an already-applied removal of strokes (delete selection).
     * P3.1: selected SHAPES ride the same single undo step.
     * P4.2: selected IMAGES ride it too.
     */
    fun recordAppliedRemove(
        layer: LayerData,
        removed: List<StrokeData>,
        removedShapes: List<ShapeData> = emptyList(),
        removedImages: List<PlacedImage> = emptyList(),
    ) {
        if (removed.isEmpty() && removedShapes.isEmpty() && removedImages.isEmpty()) return
        layer.strokes.removeAll(removed.toSet())
        layer.shapes.removeAll(removedShapes.toSet())
        layer.images.removeAll(removedImages.toSet())
        undoStack.addLast(RemoveStrokesCommand(this, layer, removed, removedShapes, removedImages))
        redoStack.clear()
        removed.forEach { strokeIndex.remove(it) }
    }

    private class RemoveStrokesCommand(
        private val doc: DrawingDocument,
        private val layer: LayerData,
        private val removed: List<StrokeData>,
        private val removedShapes: List<ShapeData>,
        private val removedImages: List<PlacedImage>,
    ) : Command {
        override fun redo() {
            layer.strokes.removeAll(removed.toSet())
            layer.shapes.removeAll(removedShapes.toSet())
            layer.images.removeAll(removedImages.toSet())
            removed.forEach { doc.strokeIndex.remove(it) }
        }
        override fun undo() {
            layer.strokes.addAll(removed)
            layer.shapes.addAll(removedShapes)
            layer.images.addAll(removedImages)
            removed.forEach { doc.strokeIndex.insert(layer, it) }
        }
    }

    private fun docOwnerOf(stroke: StrokeData): LayerData {
        for (layer in layers) if (stroke in layer.strokes) return layer
        return activeLayer
    }

    // ----- eraser: applied live during a gesture, recorded as ONE undo step -----

    /** Replaces a stroke with fragments NOW, no undo record (live erasing). */
    fun replaceStrokeDirect(layer: LayerData, original: StrokeData, replacements: List<StrokeData>): Boolean {
        if (original.points.size < 2) return false
        layer.strokes.remove(original)
        layer.strokes.addAll(replacements)
        strokeIndex.remove(original)
        replacements.forEach { strokeIndex.insert(layer, it) }
        return true
    }

    /** Records an already-applied batch of replacements as one undoable command. */
    fun recordAppliedErase(layer: LayerData, pairs: List<Pair<StrokeData, List<StrokeData>>>) {
        if (pairs.isEmpty()) return
        undoStack.addLast(ReplaceStrokeCommand(this, layer, pairs))
        redoStack.clear()
    }

    private class ReplaceStrokeCommand(
        private val doc: DrawingDocument,
        private val layer: LayerData,
        private val pairs: List<Pair<StrokeData, List<StrokeData>>>,
    ) : Command {
        override fun redo() {
            for ((original, replacements) in pairs) doc.replaceStrokeDirect(layer, original, replacements)
        }
        override fun undo() {
            // reversed so chained replacements (fragments re-erased later in
            // the same gesture) unwind in the correct order
            for ((original, replacements) in pairs.reversed()) {
                layer.strokes.removeAll(replacements)
                layer.strokes.add(original)
                replacements.forEach { doc.strokeIndex.remove(it) }
                doc.strokeIndex.insert(layer, original)
            }
        }
    }

    // ----- layer management (undoable since Phase 5) -----

    /** Inserts a new layer directly above the active one and makes it active. */
    fun addLayerAboveActive(): LayerData {
        val layer = LayerData(name = "Layer " + (layers.size + 1))
        execute(AddLayerCommand(this, layer, activeLayerIndex + 1, activeLayerIndex))
        return layer
    }

    /** Removes a layer; the last remaining layer is protected. */
    fun removeLayer(index: Int) {
        if (layers.size <= 1 || index !in layers.indices) return
        val layer = layers[index]
        execute(RemoveLayerCommand(this, layer, index, activeLayerIndex))
    }

    /** Selection is intentionally not undoable (kept out of the command stack). */
    fun setActiveLayer(index: Int) {
        if (index in layers.indices && index != activeLayerIndex) {
            activeLayerIndex = index
            // P2: keep the selection invariant — selected strokes always
            // belong to the active layer (they're the only ones marquee can
            // pick, and the only ones delete/transform may act on).
            pruneSelectionToActiveLayer()
        }
    }

    /**
     * P2: drops selected strokes that no longer belong to the ACTIVE layer.
     * Called after every layer-structure command (add/remove/duplicate/
     * reorder) and on active-layer changes, so a stale selection can never
     * mutate or delete strokes of a layer the user is no longer editing.
     */
    fun pruneSelectionToActiveLayer() {
        val active = activeLayer
        if (active.locked) { selection.clear(); return }
        selection.selected.retainAll(active.strokes)
        selection.selectedShapes.retainAll(active.shapes)
        selection.selectedImages.retainAll(active.images)
        selection.selectedImages.removeAll { it.locked }
    }

    // ----- P2.1: layers v2 (duplicate / rename / reorder / lock / blend / opacity) -----

    /**
     * Deep-copies the layer at [index], inserts the copy directly ABOVE its
     * source and makes it active. The copy's strokes/images are new objects
     * (independent of the source), but its own name gets a " copy" suffix.
     */
    fun duplicateLayer(index: Int): LayerData? {
        if (index !in layers.indices) return null
        val source = layers[index]
        val copy = source.deepCopy()
        copy.name = source.name + " copy"
        execute(DuplicateLayerCommand(this, copy, index + 1, activeLayerIndex))
        return copy
    }

    /** Renames the layer at [index] (blank names keep the current one). */
    fun renameLayer(index: Int, name: String) {
        if (index !in layers.indices) return
        val layer = layers[index]
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == layer.name) return
        execute(RenameLayerCommand(layer, layer.name, trimmed))
    }

    /**
     * Moves a layer from [from] to final index [to]. Out-of-range or
     * degenerate indices are no-ops (the panel's Up/Dn buttons rely on
     * that at the list edges). The active LAYER keeps its identity; only
     * its index follows. No-op moves push nothing onto the undo stack.
     */
    fun moveLayer(from: Int, to: Int) {
        if (from !in layers.indices || to !in layers.indices) return
        if (to == from) return
        execute(ReorderLayerCommand(this, from, to, activeLayer))
    }

    /**
     * P2.2: locks/unlocks the layer at [index]. Locking the ACTIVE layer
     * also drops its selection (a locked sheet accepts no selection edits;
     * visible content is unaffected).
     */
    fun setLayerLocked(index: Int, locked: Boolean) {
        if (index !in layers.indices) return
        val layer = layers[index]
        if (layer.locked == locked) return
        execute(SetLockCommand(layer, layer.locked, locked))
        if (locked && layer === activeLayer) selection.clear()
    }

    /** P2.3: sets the layer's compositing mode (no-op re-sets push nothing). */
    fun setLayerBlend(index: Int, blend: LayerBlend) {
        if (index !in layers.indices) return
        val layer = layers[index]
        if (layer.blend == blend) return
        execute(SetBlendCommand(layer, layer.blend, blend))
    }

    /**
     * P2.1: records one opacity change as an undo step. The panel supplies
     * the PRE-DRAG value as [before] when finishing a slider drag (the live
     * drag already applied the value); direct calls default to the current
     * opacity.
     */
    fun setLayerOpacity(index: Int, opacity: Float, before: Float? = null) {
        if (index !in layers.indices) return
        val layer = layers[index]
        val prior = (before ?: layer.opacity).coerceIn(0f, 1f)
        val after = opacity.coerceIn(0f, 1f)
        if (layer.opacity == after && prior == after) return
        execute(SetLayerOpacityCommand(layer, prior, after))
    }

    /** Rebuilds the spatial index (after bulk loads, e.g. restore). */
    fun reindex() = strokeIndex.rebuild(layers)

    // ----- P4.1/P4.3: image v2 (own-layer import, tracing, clear, alpha/lock) -----

    /** Finds the layer that owns [image], or null. */
    fun layerOf(image: PlacedImage): LayerData? {
        for (layer in layers) if (image in layer.images) return layer
        return null
    }

    /**
     * P4.1: IMAGE IMPORT V2 — the import places the bitmap on a NEW layer
     * directly above the active one (named "Image <n>"), centered on the
     * current view in the camera's current level, and makes that layer
     * active — so the image never buries the user's strokes and every v2
     * layer op (rename/duplicate/lock/reorder) applies to it. ONE undo
     * step: undo removes the whole layer and restores the previous active
     * layer, redo re-inserts it.
     */
    fun importImageOnNewLayer(path: String, aspect: Float, camera: Camera): PlacedImage? {
        val vp = camera.visibleLocalRect(1080f, 2340f) // fallback if view unsized
        val w = (vp[2] - vp[0]) * 0.6f
        val h = w / aspect
        val cx = (vp[0] + vp[2]) / 2f
        val cy = (vp[1] + vp[3]) / 2f
        val img = PlacedImage(path, cx - w / 2f, cy - h / 2f, w, h, camera.level.id)
        val layer = LayerData(name = "Image " + (layers.size + 1))
        execute(ImportImageCommand(this, layer, activeLayerIndex + 1, activeLayerIndex, img))
        return img
    }

    private class ImportImageCommand(
        private val doc: DrawingDocument,
        private val layer: LayerData,
        private val index: Int,
        private val prevActive: Int,
        private val image: PlacedImage,
    ) : Command {
        override fun redo() {
            doc.layers.add(index, layer)
            if (!layer.images.contains(image)) layer.images.add(image)
            doc.activeLayerIndex = index
            doc.strokeIndex.rebuild(doc.layers)
            doc.pruneSelectionToActiveLayer()
        }
        override fun undo() {
            doc.layers.removeAt(index)
            doc.activeLayerIndex = prevActive
            doc.strokeIndex.rebuild(doc.layers)
            doc.pruneSelectionToActiveLayer()
        }
    }

    /**
     * P4.3: TRACING MODE — one tap, ONE undoable command: the image drops
     * to 40% alpha, gets LOCKED (un-selectable, draw over it freely) and a
     * new empty layer is created directly above the image's layer and made
     * active, ready to trace on. Undo restores alpha, lock, the layer and
     * the previous active layer in a single step.
     */
    fun beginTracingMode(image: PlacedImage): Boolean {
        val owner = layerOf(image) ?: return false
        val index = layers.indexOf(owner)
        if (index !in layers.indices) return false
        val traceLayer = LayerData(name = owner.name + " trace")
        execute(TracingModeCommand(
            this, image, image.alpha, image.locked, traceLayer, index + 1, activeLayerIndex,
        ))
        return true
    }

    /** The alpha tracing mode drops the image to. */
    val tracingAlpha = 0.4f

    private class TracingModeCommand(
        private val doc: DrawingDocument,
        private val image: PlacedImage,
        private val beforeAlpha: Float,
        private val beforeLocked: Boolean,
        private val traceLayer: LayerData,
        private val index: Int,
        private val prevActive: Int,
    ) : Command {
        override fun redo() {
            image.alpha = doc.tracingAlpha
            image.locked = true
            if (!doc.layers.contains(traceLayer)) doc.layers.add(index, traceLayer)
            doc.activeLayerIndex = index
            doc.strokeIndex.rebuild(doc.layers)
            doc.pruneSelectionToActiveLayer()
        }
        override fun undo() {
            doc.layers.removeAt(index)
            doc.activeLayerIndex = prevActive
            image.alpha = beforeAlpha
            image.locked = beforeLocked
            doc.strokeIndex.rebuild(doc.layers)
            doc.pruneSelectionToActiveLayer()
        }
    }

    /**
     * P4.4: CLEAR LAYER — removes ALL content of the active layer (strokes,
     * shapes, smudges, images) as ONE undoable command; undo restores every
     * list exactly. The UI confirms before calling this. Returns false
     * (no undo entry) when the layer is already empty.
     */
    fun clearActiveLayer(): Boolean {
        val layer = activeLayer
        val strokes = layer.strokes.toList()
        val images = layer.images.toList()
        val shapes = layer.shapes.toList()
        val smudges = layer.smudges.toList()
        if (strokes.isEmpty() && images.isEmpty() && shapes.isEmpty() && smudges.isEmpty()) {
            return false
        }
        execute(ClearLayerCommand(this, layer, strokes, images, shapes, smudges))
        return true
    }

    private class ClearLayerCommand(
        private val doc: DrawingDocument,
        private val layer: LayerData,
        private val strokes: List<StrokeData>,
        private val images: List<PlacedImage>,
        private val shapes: List<ShapeData>,
        private val smudges: List<SmudgeData>,
    ) : Command {
        private fun clear() {
            layer.strokes.clear()
            layer.images.clear()
            layer.shapes.clear()
            layer.smudges.clear()
            strokes.forEach { doc.strokeIndex.remove(it) }
        }
        override fun redo() = clear()
        override fun undo() {
            layer.strokes.addAll(strokes)
            layer.images.addAll(images)
            layer.shapes.addAll(shapes)
            layer.smudges.addAll(smudges)
            doc.strokeIndex.rebuild(doc.layers)
        }
    }

    /**
     * P4.3: records one per-image transparency change (the panel supplies
     * the PRE-DRAG value as [before]; the live drag already applied the
     * new alpha) — the same one-step-per-drag contract as layer opacity.
     */
    fun setLayerImageAlpha(image: PlacedImage, after: Float, before: Float? = null) {
        val prior = (before ?: image.alpha).coerceIn(0f, 1f)
        val next = after.coerceIn(0f, 1f)
        if (image.alpha == next && prior == next) return
        execute(SetImageAlphaCommand(image, prior, next))
    }

    private class SetImageAlphaCommand(
        private val image: PlacedImage,
        private val before: Float,
        private val after: Float,
    ) : Command {
        override fun redo() { image.alpha = after }
        override fun undo() { image.alpha = before }
    }

    /** P4.3: locks/unlocks a placed image (no-op re-sets push nothing). */
    fun setImageLock(image: PlacedImage, locked: Boolean) {
        if (image.locked == locked) return
        execute(SetImageLockCommand(image, image.locked, locked))
    }

    private class SetImageLockCommand(
        private val image: PlacedImage,
        private val before: Boolean,
        private val after: Boolean,
    ) : Command {
        override fun redo() { image.locked = after }
        override fun undo() { image.locked = before }
    }

    fun addBookmark(worldX: Float, worldY: Float, scale: Float, label: String, levelId: Long = ZoomLevels.ROOT_ID): Bookmark {
        val b = Bookmark(worldX, worldY, scale, label, levelId)
        bookmarks.add(b)
        return b
    }
}
