package com.example.zoomart

import com.example.zoomart.model.DrawingDocument
import org.junit.Assert.assertEquals
import org.junit.Test

class LayerOpsTest {

    @Test
    fun addAndRemoveLayersKeepActiveIndexValid() {
        val doc = DrawingDocument()
        doc.addLayerAboveActive()
        assertEquals(3, doc.layers.size)
        assertEquals(2, doc.activeLayerIndex)
        doc.removeLayer(2)
        assertEquals(2, doc.layers.size)
        assertEquals(1, doc.activeLayerIndex)
        doc.removeLayer(0) // removing below active shifts the active index down
        assertEquals(1, doc.layers.size)
        assertEquals(0, doc.activeLayerIndex) // same layer stays active, now at index 0
        doc.removeLayer(0) // last layer is protected
        doc.removeLayer(0)
        assertEquals(1, doc.layers.size)
        doc.setActiveLayer(0)
        assertEquals(0, doc.activeLayerIndex)
    }
}
