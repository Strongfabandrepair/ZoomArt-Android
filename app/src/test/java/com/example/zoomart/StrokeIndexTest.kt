package com.example.zoomart

import com.example.zoomart.model.DrawingDocument
import com.example.zoomart.model.LayerData
import com.example.zoomart.model.StrokeData
import com.example.zoomart.model.StrokeIndex
import com.example.zoomart.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeIndexTest {

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float): StrokeData {
        val s = StrokeData()
        s.addPoint(StrokePoint(x0, y0, 1f))
        s.addPoint(StrokePoint(x1, y1, 1f))
        return s
    }

    @Test
    fun queryReturnsOnlyNearbyStrokesWithOwners() {
        val layer = LayerData(name = "L")
        val a = stroke(0f, 0f, 10f, 10f)
        val b = stroke(10_000f, 10_000f, 10_010f, 10_010f)
        val idx = StrokeIndex()
        idx.insert(layer, a)
        idx.insert(layer, b)
        val near = idx.queryVisible(0f, 0f, 50f, 50f)
        assertEquals(1, near.size)
        assertSame(a, near[0].first)
        assertSame(layer, near[0].second)
        val far = idx.queryVisible(9_990f, 9_990f, 10_020f, 10_020f)
        assertEquals(1, far.size)
        assertSame(b, far[0].first)
        assertEquals(0, idx.queryVisible(5_000f, 5_000f, 6_000f, 6_000f).size)
    }

    @Test
    fun multiCellStrokesAppearOnce() {
        val s = stroke(0f, 0f, 1_300f, 10f) // spans cells 0..2 on x
        val idx = StrokeIndex()
        idx.insert(LayerData(), s)
        assertEquals(1, idx.queryVisible(0f, -100f, 1_400f, 100f).size)
    }

    @Test
    fun removeAndRebuild() {
        val layer = LayerData()
        val s = stroke(0f, 0f, 10f, 10f)
        val idx = StrokeIndex()
        idx.insert(layer, s)
        idx.remove(s)
        assertEquals(0, idx.queryVisible(0f, 0f, 50f, 50f).size)
        layer.strokes.add(s)
        idx.rebuild(listOf(layer))
        assertEquals(1, idx.queryVisible(0f, 0f, 50f, 50f).size)
    }

    @Test
    fun oversizeStrokesStayVisibleAnywhere() {
        val s = stroke(0f, 0f, 10_000_000f, 10f) // spans ~19531 cells -> oversize list
        val idx = StrokeIndex()
        idx.insert(LayerData(), s)
        assertEquals(1, idx.queryVisible(9_999_000f, -100f, 10_000_100f, 100f).size)
    }

    @Test
    fun documentUndoKeepsIndexInSync() {
        val doc = DrawingDocument()
        val s = stroke(0f, 0f, 10f, 0f)
        assertTrue(doc.commitStroke(s))
        assertEquals(1, doc.strokeIndex.queryVisible(-100f, -100f, 100f, 100f).size)
        assertTrue(doc.undo())
        assertEquals(0, doc.strokeIndex.queryVisible(-100f, -100f, 100f, 100f).size)
        assertTrue(doc.redo())
        assertEquals(1, doc.strokeIndex.queryVisible(-100f, -100f, 100f, 100f).size)
    }

    // ----- P0: level buckets isolate levels in the index -----

    @Test
    fun levelBucketsIsolateLevels() {
        val layer = LayerData(name = "L")
        val a = stroke(0f, 0f, 10f, 10f)              // root (levelId 0)
        val b = stroke(0f, 0f, 10f, 10f)              // level 7
        b.levelId = 7L
        val idx = StrokeIndex()
        idx.insert(layer, a)
        idx.insert(layer, b)

        // the legacy 4-arg query is the ROOT bucket: sees only a
        val rootHits = idx.queryVisible(-50f, -50f, 50f, 50f)
        assertEquals(1, rootHits.size)
        assertSame(a, rootHits[0].first)

        // per-level queries are isolated and return owners
        val level7 = idx.queryVisibleLevel(7L, -50f, -50f, 50f, 50f)
        assertEquals(1, level7.size)
        assertSame(b, level7[0].first)
        assertSame(layer, level7[0].second)
        assertEquals(0, idx.queryVisibleLevel(9L, -50f, -50f, 50f, 50f).size)

        // removing from one level leaves the other intact
        idx.remove(b)
        assertEquals(0, idx.queryVisibleLevel(7L, -50f, -50f, 50f, 50f).size)
        assertEquals(1, idx.queryVisible(-50f, -50f, 50f, 50f).size)
    }
}
