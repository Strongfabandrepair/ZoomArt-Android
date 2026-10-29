package com.example.zoomart

import com.example.zoomart.model.*
import org.junit.Assert.*
import org.junit.Test

class ReviewRegressionTest {
    private fun stroke(x: Float = 0f, brush: BrushSpec = BrushSpec()) = StrokeData(brush).apply {
        addPoint(StrokePoint(x, 10f, 1f))
        addPoint(StrokePoint(x + 20f, 10f, 1f))
    }

    @Test fun brushAppearanceAndImageMirrorSurviveSaveAndDuplicate() {
        val vm = DrawingViewModel()
        val brush = BrushSpec(angleDeg = 35f, dashOn = 12f, dashOff = 5f,
            scatterWorld = 7f, spacingWorld = 3f, erase = true)
        vm.document.commitStroke(stroke(brush = brush))
        vm.document.activeLayer.images.add(PlacedImage("imports/test.png", 0f, 0f, 50f, 50f).apply { flipH = true })
        val restored = DocumentCodec.fromJson(vm.serialize()).document
        assertEquals(brush, restored.activeLayer.strokes.single().brush)
        assertTrue(restored.activeLayer.images.single().flipH)
        assertTrue(restored.activeLayer.deepCopy().images.single().flipH)
    }

    @Test fun flippedImageUndoAndRedoRestoreMirrorState() {
        val vm = DrawingViewModel()
        val image = vm.placeImage("test.png", 1f)!!
        val selection = vm.document.selection
        selection.selectedImages.add(image)
        selection.beginTransform(); selection.flip(true); selection.endTransform()
        assertTrue(image.flipH)
        vm.undo(); assertFalse(image.flipH)
        vm.redo(); assertTrue(image.flipH)
    }

    @Test fun switchingLayersDropsShapeSelectionAndUndoDropsRemovedObjects() {
        val vm = DrawingViewModel()
        vm.beginShapeGesture(10f, 10f); vm.extendShapeGesture(100f, 100f)
        val shape = vm.endShapeGesture()!!
        vm.document.selection.selectedShapes.add(shape)
        vm.document.setActiveLayer(0)
        assertTrue(vm.document.selection.isEmpty)
        vm.document.setActiveLayer(1)
        vm.document.selection.selectedShapes.add(shape)
        vm.undo()
        assertTrue(vm.document.selection.isEmpty)
    }

    @Test fun transformedStrokeIsIndexedAtNewPositionBeforeUndo() {
        val doc = DrawingDocument()
        val s = stroke()
        doc.commitStroke(s)
        doc.selection.selected.add(s)
        doc.selection.beginTransform(); doc.selection.translateBy(5000f, 0f); doc.selection.endTransform()
        assertTrue(doc.strokeIndex.queryVisible(4990f, 0f, 5030f, 30f).any { it.first === s })
        assertTrue(doc.strokeIndex.queryVisible(-10f, 0f, 30f, 30f).isEmpty())
        doc.undo()
        assertTrue(doc.strokeIndex.queryVisible(-10f, 0f, 30f, 30f).any { it.first === s })
    }

    @Test fun selectingWithoutMovingDoesNotConsumeUndoOrClearRedo() {
        val doc = DrawingDocument()
        val first = stroke(); doc.commitStroke(first); doc.commitStroke(stroke(100f))
        doc.undo()
        doc.selection.selected.add(first)
        doc.selection.beginTransform(); doc.selection.endTransform()
        assertTrue(doc.canRedo)
        doc.undo()
        assertTrue(doc.activeLayer.strokes.isEmpty())
    }

    @Test(timeout = 2000) fun extremeCoordinateSpansCannotOverflowGridBudget() {
        val index = StrokeIndex()
        val s = StrokeData().apply {
            addPoint(StrokePoint(-Float.MAX_VALUE, -Float.MAX_VALUE, 1f))
            addPoint(StrokePoint(Float.MAX_VALUE, Float.MAX_VALUE, 1f))
        }
        index.insert(LayerData(), s)
        assertEquals(1, index.queryVisible(-Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE).size)
        index.remove(s)
        assertTrue(index.levelsPresent().isEmpty())
    }

    @Test fun thickStrokeCrossingCellEdgeIsNotCulled() {
        val index = StrokeIndex()
        val s = stroke(520f, BrushSpec(baseWidthWorld = 80f))
        index.insert(LayerData(), s)
        assertTrue(index.queryVisible(490f, 0f, 500f, 30f).any { it.first === s })
    }

    @Test fun jsonSupportsUnicodeEscapesAndControlCharacters() {
        val vm = DrawingViewModel()
        vm.document.activeLayer.name = "Ink\u0001\b\n\u263a"
        assertEquals(vm.document.activeLayer.name,
            DocumentCodec.fromJson(vm.serialize()).document.activeLayer.name)
        val escaped = vm.serialize().replace("Ink", "\\u0049nk")
        assertEquals(vm.document.activeLayer.name, DocumentCodec.fromJson(escaped).document.activeLayer.name)
    }

    @Test fun corruptCameraAndTrailingDataAreRejected() {
        val json = DrawingViewModel().serialize()
        for (invalid in listOf(json + "junk", json.replace("\"scale\":1.0", "\"scale\":0.0"),
            json.replace("\"scale\":1.0", "\"scale\":1e999"))) {
            assertThrows(DocumentCodec.JsonError::class.java) { DocumentCodec.fromJson(invalid) }
        }
    }
}
