package com.example.zoomart

import com.example.zoomart.model.DrawingDocument
import com.example.zoomart.model.StrokeData
import com.example.zoomart.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerUndoTest {

    private fun strokeAt(x: Float): StrokeData {
        val s = StrokeData()
        s.addPoint(StrokePoint(x, 0f, 1f))
        s.addPoint(StrokePoint(x + 5f, 0f, 1f))
        return s
    }

    @Test
    fun undoRestoresDeletedLayerWithStrokesAndActiveIndex() {
        val doc = DrawingDocument()
        doc.addLayerAboveActive() // [Background, Layer 1, Layer 3], active 2
        assertTrue(doc.commitStroke(strokeAt(100f)))
        assertEquals(1, doc.layers[2].strokes.size)

        doc.removeLayer(2)
        assertEquals(2, doc.layers.size)
        assertEquals(1, doc.activeLayerIndex)

        assertTrue(doc.undo())
        assertEquals(3, doc.layers.size)
        assertEquals(2, doc.activeLayerIndex)
        assertEquals(1, doc.layers[2].strokes.size) // strokes survived
        assertEquals(1, doc.strokeIndex.queryVisible(90f, -10f, 110f, 10f).size)
    }

    @Test
    fun undoAddLayerRemovesIt() {
        val doc = DrawingDocument()
        doc.addLayerAboveActive()
        assertEquals(3, doc.layers.size)
        assertTrue(doc.undo())
        assertEquals(2, doc.layers.size)
        assertEquals(1, doc.activeLayerIndex)
    }

    @Test
    fun redoReRemovesLayer() {
        val doc = DrawingDocument()
        doc.removeLayer(0)
        assertEquals(1, doc.layers.size)
        assertTrue(doc.undo())
        assertEquals(2, doc.layers.size)
        assertEquals(1, doc.activeLayerIndex) // prevActive restored
        assertTrue(doc.redo())
        assertEquals(1, doc.layers.size)
    }
}
