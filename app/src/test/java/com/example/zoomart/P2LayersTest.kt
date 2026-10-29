package com.example.zoomart

import com.example.zoomart.model.DocumentCodec
import com.example.zoomart.model.DrawingDocument
import com.example.zoomart.model.DrawingViewModel
import com.example.zoomart.model.LayerBlend
import com.example.zoomart.model.StrokeData
import com.example.zoomart.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 Layers v2 suite (docs/P2_LAYERS_V2.md): duplicate (deep copy),
 * rename, reorder, lock (draw/erase/select/transform rejection), blend
 * modes, opacity undo, codec v3 extension (locked + blend string enum)
 * with lossless migration of pre-P2 files. All pure JVM.
 */
class P2LayersTest {

    private fun strokeAt(x: Float, y: Float = 0f): StrokeData {
        val s = StrokeData()
        s.addPoint(StrokePoint(x, y, 1f))
        s.addPoint(StrokePoint(x + 5f, y, 1f))
        return s
    }

    private fun commitStrokeTo(doc: DrawingDocument, x: Float): Boolean =
        doc.commitStroke(strokeAt(x))

    // ---------------- P2.1 duplicate ----------------

    @Test
    fun duplicateInsertsDeepCopyAboveSourceAndActivatesIt() {
        val doc = DrawingDocument()
        assertTrue(commitStrokeTo(doc, 100f))
        val source = doc.layers[1]
        val copy = doc.duplicateLayer(1)!!

        assertEquals(3, doc.layers.size)
        assertSame(source, doc.layers[1])
        assertSame("copy must sit directly above the source", copy, doc.layers[2])
        assertSame("duplicate becomes the active layer", copy, doc.activeLayer)
        assertEquals(2, doc.activeLayerIndex)
    }

    @Test
    fun duplicateCopiesNameStrokesAndImagesAsNewObjects() {
        val doc = DrawingDocument()
        doc.activeLayer.name = "Sketch"
        doc.activeLayer.opacity = 0.5f
        doc.activeLayer.blend = LayerBlend.SCREEN
        doc.activeLayer.locked = false
        assertTrue(commitStrokeTo(doc, 100f))
        doc.activeLayer.images.add(
            com.example.zoomart.model.PlacedImage("imports/a.png", 0f, 0f, 50f, 25f),
        )
        val source = doc.activeLayer
        val copy = doc.duplicateLayer(doc.activeLayerIndex)!!

        assertEquals("Sketch copy", copy.name)
        assertEquals(0.5f, copy.opacity, 0.0001f)
        assertEquals(LayerBlend.SCREEN, copy.blend)
        assertEquals(1, copy.strokes.size)
        assertEquals(1, copy.images.size)
        assertNotSame("strokes must be NEW StrokeData objects", source.strokes[0], copy.strokes[0])
        assertNotSame("images must be NEW PlacedImage objects", source.images[0], copy.images[0])
        assertEquals(source.strokes[0].points.size, copy.strokes[0].points.size)
        assertEquals(source.strokes[0].points[1].x, copy.strokes[0].points[1].x, 0.0001f)
        assertEquals(source.strokes[0].levelId, copy.strokes[0].levelId)
        assertEquals(source.strokes[0].brush, copy.strokes[0].brush)
        assertEquals(source.images[0].path, copy.images[0].path)
        assertEquals(source.images[0].x, copy.images[0].x, 0.0001f)
        // the copy is registered in the spatial index too
        assertEquals(
            2,
            doc.strokeIndex.queryVisible(90f, -10f, 110f, 10f).size,
        )
    }

    @Test
    fun duplicateIsIndependentMutatingEitherLeavesTheOtherIntact() {
        val doc = DrawingDocument()
        assertTrue(commitStrokeTo(doc, 100f))
        doc.activeLayer.images.add(
            com.example.zoomart.model.PlacedImage("imports/a.png", 0f, 0f, 50f, 25f),
        )
        val source = doc.activeLayer
        val copy = doc.duplicateLayer(1)!!
        val sourceStroke = source.strokes[0]
        val copyStroke = copy.strokes[0]

        // mutate the ORIGINAL's points -> the copy must not move
        sourceStroke.setPoints(listOf(StrokePoint(999f, 0f, 1f), StrokePoint(1004f, 0f, 1f)))
        assertEquals(100f, copyStroke.points[0].x, 0.0001f)
        assertEquals(100f, copyStroke.minX, 0.0001f)

        // mutate the COPY -> the original must not move
        copyStroke.setPoints(listOf(StrokePoint(-500f, 7f, 0.5f), StrokePoint(-495f, 7f, 0.5f)))
        assertEquals(999f, sourceStroke.points[0].x, 0.0001f)

        // mutating the copy's image leaves the original's image alone
        copy.images[0].x = -777f
        assertEquals(0f, source.images[0].x, 0.0001f)
        copy.images.clear()
        assertEquals(1, source.images.size)
    }

    @Test
    fun duplicateUndoRestoresAndRedoReinsertsTheSameCopy() {
        val doc = DrawingDocument()
        assertTrue(commitStrokeTo(doc, 100f))
        val source = doc.layers[1]
        val copy = doc.duplicateLayer(1)!!
        assertEquals(3, doc.layers.size)

        assertTrue(doc.undo())
        assertEquals(2, doc.layers.size)
        assertSame(source, doc.activeLayer)
        assertEquals(1, doc.activeLayerIndex)
        assertFalse(copy in doc.layers)

        assertTrue(doc.redo())
        assertEquals(3, doc.layers.size)
        assertSame("redo must re-insert the SAME duplicated layer", copy, doc.activeLayer)
        assertEquals(2, doc.activeLayerIndex)
        assertEquals(1, copy.strokes.size)
    }

    // ---------------- P2.1 rename ----------------

    @Test
    fun renameLayerIsUndoableAndRedoable() {
        val doc = DrawingDocument()
        doc.renameLayer(1, "Ink")
        assertEquals("Ink", doc.layers[1].name)
        assertTrue(doc.undo())
        assertEquals("Layer 1", doc.layers[1].name)
        assertTrue(doc.redo())
        assertEquals("Ink", doc.layers[1].name)
    }

    // ---------------- P2.1 reorder ----------------

    @Test
    fun moveLayerPreservesTheActiveLayerIdentity() {
        val doc = DrawingDocument()
        doc.setActiveLayer(1)
        val active = doc.activeLayer
        doc.moveLayer(1, 0)
        assertEquals(2, doc.layers.size)
        assertSame(doc.layers[0], active)
        assertSame("the moved layer stays active (identity preserved)", active, doc.activeLayer)
        assertEquals(0, doc.activeLayerIndex)
    }

    @Test
    fun moveLayerAcrossTheActiveKeepsActiveObjectAndIndexHonest() {
        val doc = DrawingDocument()
        doc.addLayerAboveActive() // [Background, Layer 1, Layer 3], active 2
        val active = doc.activeLayer
        // move Background (0) below the active layer (to index 2):
        // the active layer shifts down to 1 but stays the SAME object
        doc.moveLayer(0, 2)
        assertSame(active, doc.layers[1])
        assertSame(active, doc.activeLayer)
        assertEquals(1, doc.activeLayerIndex)
        assertEquals("Background", doc.layers[2].name)
        // and moving back restores everything
        doc.moveLayer(2, 0)
        assertSame(active, doc.activeLayer)
        assertEquals(2, doc.activeLayerIndex)
        assertEquals("Background", doc.layers[0].name)
    }

    @Test
    fun moveLayerUndoRedoRestoresOrderAndActive() {
        val doc = DrawingDocument()
        assertTrue(commitStrokeTo(doc, 100f))
        val active = doc.activeLayer
        doc.moveLayer(1, 0)
        assertTrue(doc.undo())
        assertEquals("Background", doc.layers[0].name)
        assertSame(active, doc.activeLayer)
        assertEquals(1, doc.activeLayerIndex)
        assertTrue(doc.redo())
        assertEquals(active.name, doc.layers[0].name)
        assertSame(active, doc.activeLayer)
    }

    @Test
    fun moveLayerRejectsDegenerateAndOutOfRangeIndices() {
        val doc = DrawingDocument()
        doc.moveLayer(1, 1)
        doc.moveLayer(-1, 0)
        doc.moveLayer(0, 5)
        assertFalse("degenerate moves must not push undo entries", doc.canUndo)
        assertEquals(2, doc.layers.size)
    }

    // ---------------- P2.1 lock / P2.2 lock semantics ----------------

    @Test
    fun setLayerLockedIsUndoableAndRedoable() {
        val doc = DrawingDocument()
        doc.setLayerLocked(1, true)
        assertTrue(doc.layers[1].locked)
        assertTrue(doc.undo())
        assertFalse(doc.layers[1].locked)
        assertTrue(doc.redo())
        assertTrue(doc.layers[1].locked)
    }

    @Test
    fun lockedLayerRejectsNewStrokesAtBothCommitPaths() {
        val vm = DrawingViewModel()
        val doc = vm.document
        doc.setLayerLocked(doc.activeLayerIndex, true)

        // ViewModel path: beginStroke must not even start a stroke
        vm.beginStroke(0f, 0f, 1f)
        assertNull("locked layer must not open an in-progress stroke", vm.inProgress)
        assertNull(vm.endStroke())
        assertEquals(0, doc.activeLayer.strokes.size)

        // Document path: direct commit is rejected too (lock can land
        // mid-gesture via the panel, e.g. a finger tapping LOCK while
        // the pen draws)
        assertFalse("commitStroke must reject a locked active layer", commitStrokeTo(doc, 10f))
        assertEquals(0, doc.activeLayer.strokes.size)
        // the ONLY undo entry is the lock itself; rejected strokes pushed nothing
        assertTrue(doc.undo())
        assertFalse(doc.layers[1].locked)
        assertFalse(doc.canUndo)

        // unlocking restores drawing
        vm.beginStroke(0f, 0f, 1f)
        vm.extendStroke(6f, 0f, 1f)
        vm.endStroke()
        assertEquals(1, doc.activeLayer.strokes.size)
    }

    @Test
    fun lockedLayerRejectsTheEraser() {
        val vm = DrawingViewModel()
        val doc = vm.document
        vm.beginStroke(0f, 0f, 1f)
        vm.extendStroke(20f, 0f, 1f)
        vm.endStroke()
        assertEquals(1, doc.activeLayer.strokes.size)

        doc.setLayerLocked(doc.activeLayerIndex, true)
        vm.beginErase(5f, 0f)
        assertFalse(vm.eraseTo(6f, 0f))
        assertFalse(vm.eraseTo(10f, 0f))
        vm.endErase()
        assertEquals("locked layer must keep its strokes", 1, doc.activeLayer.strokes.size)
        // undo stack holds exactly the committed stroke + the lock; the
        // rejected erase added NOTHING (two undoes drain it completely)
        assertTrue(doc.undo())               // pops the lock
        assertFalse(doc.activeLayer.locked)
        assertTrue(doc.undo())               // pops the stroke commit
        assertEquals(0, doc.activeLayer.strokes.size)
        assertFalse(doc.canUndo)
    }

    @Test
    fun lockedLayerRejectsMarqueeSelectionTransformsAndDelete() {
        val vm = DrawingViewModel()
        val doc = vm.document
        vm.beginStroke(0f, 0f, 1f)
        vm.extendStroke(20f, 0f, 1f)
        vm.endStroke()
        val stroke = doc.activeLayer.strokes[0]
        val x0 = stroke.minX

        // a fresh marquee on a locked layer selects nothing
        doc.setLayerLocked(doc.activeLayerIndex, true)
        assertFalse(vm.selectInWorldRect(-100f, -100f, 100f, 100f))
        assertTrue(doc.selection.isEmpty)

        // runtime guard for selections that OUTLIVE a lock: the lock
        // command's redo (undo of an unlock) restores locked=true without
        // touching the selection — the ops must still refuse to mutate
        doc.setLayerLocked(doc.activeLayerIndex, false)
        assertTrue(vm.selectInWorldRect(-5f, -5f, 25f, 5f))
        assertEquals(1, doc.selection.selected.size)
        doc.undo() // undoing unlock now also clears the stale selection
        assertTrue(doc.layers[doc.activeLayerIndex].locked)
        assertEquals(0, doc.selection.selected.size)
        // Deliberately inject a stale selection to retain coverage of the runtime guard.
        doc.selection.selected.add(stroke)

        val sel = doc.selection
        sel.beginTransform()
        sel.translateBy(50f, 0f)
        sel.endTransform()
        assertEquals("locked layer must reject transforms", x0, stroke.minX, 0.0001f)

        assertFalse("locked layer must reject delete-selection", sel.deleteSelection())
        assertEquals("locked layer must keep its strokes", 1, doc.activeLayer.strokes.size)
    }

    @Test
    fun lockedLayerRejectsImagePlacement() {
        val vm = DrawingViewModel()
        vm.document.setLayerLocked(vm.document.activeLayerIndex, true)
        assertNull(vm.placeImage("imports/x.png", 1f))
        assertTrue(vm.document.activeLayer.images.isEmpty())
    }

    @Test
    fun lockLeavesVisibilityUntouched() {
        val doc = DrawingDocument()
        doc.layers[1].isVisible = false
        doc.setLayerLocked(1, true)
        assertFalse("locking must not change visibility", doc.layers[1].isVisible)
        assertTrue(doc.layers[1].locked)
    }

    // ---------------- P2.1 blend + P2.3 enum ----------------

    @Test
    fun blendEnumCyclesNormalMultiplyScreenOverlay() {
        assertEquals(LayerBlend.MULTIPLY, LayerBlend.NORMAL.cycled())
        assertEquals(LayerBlend.SCREEN, LayerBlend.MULTIPLY.cycled())
        assertEquals(LayerBlend.OVERLAY, LayerBlend.SCREEN.cycled())
        assertEquals(LayerBlend.NORMAL, LayerBlend.OVERLAY.cycled())
    }

    @Test
    fun setLayerBlendIsUndoableAndRedoable() {
        val doc = DrawingDocument()
        doc.setLayerBlend(1, LayerBlend.MULTIPLY)
        assertEquals(LayerBlend.MULTIPLY, doc.layers[1].blend)
        assertTrue(doc.undo())
        assertEquals(LayerBlend.NORMAL, doc.layers[1].blend)
        assertTrue(doc.redo())
        assertEquals(LayerBlend.MULTIPLY, doc.layers[1].blend)
    }

    // ---------------- P2.1 opacity command ----------------

    @Test
    fun setLayerOpacityRecordsBeforeAfterForUndo() {
        val doc = DrawingDocument()
        assertEquals(1f, doc.layers[1].opacity, 0.0001f)
        doc.setLayerOpacity(1, 0.4f)
        assertEquals(0.4f, doc.layers[1].opacity, 0.0001f)
        assertTrue(doc.undo())
        assertEquals("undo must restore the pre-change opacity", 1f, doc.layers[1].opacity, 0.0001f)
        assertTrue(doc.redo())
        assertEquals(0.4f, doc.layers[1].opacity, 0.0001f)
    }

    @Test
    fun opacitySliderStopRecordsThePreDragValueAsBefore() {
        val doc = DrawingDocument()
        // the panel applies live opacity during the drag, then records the
        // (pre-drag -> released) pair as ONE undo step
        val before = doc.layers[1].opacity
        doc.layers[1].opacity = 0.25f // live drag
        doc.setLayerOpacity(1, 0.25f, before)
        assertEquals(0.25f, doc.layers[1].opacity, 0.0001f)
        assertTrue(doc.undo())
        assertEquals("undo after a drag must jump back to the drag START", 1f, doc.layers[1].opacity, 0.0001f)
    }

    @Test
    fun noOpOpacityChangePushesNothing() {
        val doc = DrawingDocument()
        doc.setLayerOpacity(1, 1f, 1f)
        assertFalse(doc.canUndo)
    }

    // ---------------- selection saneness after layer ops ----------------

    @Test
    fun layerOpsPruneSelectionToTheActiveLayer() {
        val vm = DrawingViewModel()
        val doc = vm.document
        vm.beginStroke(0f, 0f, 1f)
        vm.extendStroke(10f, 0f, 1f)
        vm.endStroke()
        assertTrue(vm.selectInWorldRect(-5f, -5f, 15f, 5f))
        assertEquals(1, doc.selection.selected.size)

        // duplicating makes the COPY active; the old selection referenced
        // source strokes and must be pruned (duplicates have new identities,
        // so a stale selection could never act on them anyway)
        doc.duplicateLayer(doc.activeLayerIndex)
        assertTrue("selection must not outlive an active-layer change", doc.selection.isEmpty)

        // undoing the duplicate restores the source as active; the invariant
        // (selected strokes belong to the active layer) still holds — the
        // source-layer selection made above survives because the source
        // layer is active again
        doc.setActiveLayer(1)
        assertTrue(vm.selectInWorldRect(-5f, -5f, 15f, 5f))
        assertEquals(1, doc.selection.selected.size)
        doc.undo()
        assertEquals(2, doc.layers.size)
        assertSame(doc.layers[1], doc.activeLayer)
        assertEquals("selection stays active-layer-honest after undo", 1, doc.selection.selected.size)
        assertTrue(doc.selection.selected.all { it in doc.activeLayer.strokes })
    }

    @Test
    fun marqueeSelectionStaysScopedToTheActiveLayerAfterReorder() {
        val vm = DrawingViewModel()
        val doc = vm.document
        vm.beginStroke(0f, 0f, 1f)
        vm.extendStroke(10f, 0f, 1f)
        vm.endStroke()
        assertTrue(vm.selectInWorldRect(-5f, -5f, 15f, 5f))
        val selected = doc.selection.selected.first()

        doc.moveLayer(1, 0) // the selected stroke's layer moves but stays active
        assertSame(selected, doc.selection.selected.single())
        doc.selection.beginTransform()
        doc.selection.translateBy(4f, 0f)
        doc.selection.endTransform()
        assertEquals("transform after reorder must still hit the same stroke", 4f, selected.minX, 0.001f)
    }

    // ---------------- P2.5 codec v3 extension ----------------

    @Test
    fun codecRoundTripsLockAndBlendByteStable() {
        val vm = DrawingViewModel()
        val doc = vm.document
        vm.beginStroke(0f, 0f, 0.5f)
        vm.extendStroke(8f, 3f, 1f)
        vm.endStroke()
        doc.layers[1].name = "Ink"
        doc.setLayerLocked(1, true)
        doc.setLayerBlend(1, LayerBlend.OVERLAY)
        doc.setLayerOpacity(1, 0.65f)

        val json1 = vm.serialize()
        val session = DocumentCodec.fromJson(json1)
        val restored = session.document.layers[1]
        assertTrue("locked must survive the round trip", restored.locked)
        assertEquals(LayerBlend.OVERLAY, restored.blend)
        assertEquals("Ink", restored.name)
        assertEquals(0.65f, restored.opacity, 0.0001f)
        assertEquals(1, restored.strokes.size)

        val json2 = DocumentCodec.toJson(session.document, session.camera)
        assertEquals("locked/blend round trips must be byte-stable", json1, json2)
    }

    @Test
    fun preP2V3SessionSnippetMigratesWithDefaults() {
        // exact shape the pre-P2 v3 writer emitted: layers have
        // name/visible/opacity/images/strokes, NO locked/blend fields
        val v3pre = "{\"version\":3," +
            "\"camera\":{\"levelId\":0,\"scale\":2.0,\"transX\":-12.5,\"transY\":40.0}," +
            "\"activeLayer\":1," +
            "\"levels\":[{\"id\":0,\"parentId\":-1,\"originX\":0.0,\"originY\":0.0,\"scaleToParent\":100.0}]," +
            "\"layers\":[" +
            "{\"name\":\"Background\",\"visible\":true,\"opacity\":1.0,\"images\":[],\"strokes\":[]}," +
            "{\"name\":\"Layer 1\",\"visible\":false,\"opacity\":0.75,\"images\":[]," +
            "\"strokes\":[{\"brush\":{\"width\":9.5,\"response\":2.0,\"minRatio\":0.15," +
            "\"color\":-12303292,\"opacity\":1.0,\"pOpacity\":false,\"minOp\":0.2}," +
            "\"level\":0,\"points\":[10.0,20.0,0.5,30.5,-40.25,0.75]}]}]," +
            "\"bookmarks\":[]}"
        val session = DocumentCodec.fromJson(v3pre)
        val layer = session.document.layers[1]
        assertFalse("absent locked must default to false", layer.locked)
        assertEquals("absent blend must default to NORMAL", LayerBlend.NORMAL, layer.blend)
        assertFalse(layer.isVisible)
        assertEquals(0.75f, layer.opacity, 0.0001f)
        assertEquals(1, layer.strokes.size)
        assertEquals(30.5f, layer.strokes[0].points[1].x, 0.0001f)

        // re-serializing now materializes locked/blend and stays stable
        val json2 = DocumentCodec.toJson(session.document, session.camera)
        assertTrue(json2.contains("\"locked\":false"))
        assertTrue(json2.contains("\"blend\":\"NORMAL\""))
        assertEquals(json2, DocumentCodec.toJson(
            DocumentCodec.fromJson(json2).document,
            DocumentCodec.fromJson(json2).camera,
        ))
    }

    @Test
    fun unknownBlendStringFallsBackToNormal() {
        val json = "{\"camera\":{},\"layers\":[{\"name\":\"L\",\"blend\":\"HUE\"," +
            "\"strokes\":[]}]}"
        val session = DocumentCodec.fromJson(json)
        assertEquals(LayerBlend.NORMAL, session.document.layers[0].blend)
    }

    @Test
    fun layerStateSurvivesViewModelRestore() {
        val vm = DrawingViewModel()
        vm.document.setLayerLocked(1, true)
        vm.document.setLayerBlend(1, LayerBlend.SCREEN)
        vm.document.renameLayer(1, "Guide")
        val payload = vm.serialize()
        vm.restore(DocumentCodec.fromJson(payload))
        assertTrue(vm.document.layers[1].locked)
        assertEquals(LayerBlend.SCREEN, vm.document.layers[1].blend)
        assertEquals("Guide", vm.document.layers[1].name)
    }
}
