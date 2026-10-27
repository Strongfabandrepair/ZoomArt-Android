package com.example.zoomart

import com.example.zoomart.model.DocumentCodec
import com.example.zoomart.model.DrawingViewModel
import com.example.zoomart.model.SmudgeData
import com.example.zoomart.model.SmudgeMath
import com.example.zoomart.model.StampData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3.3 BLEND/SMUDGE suite (docs/REVIEW_2026-10-18.md BLEND/SMUDGE
 * section): the 80/20 carried-color chain, stamp interval spacing in
 * level-local units, strength x pressure alpha, gesture commit/undo/redo
 * as ONE step, lock rejection, "not stroke-erasable", and codec round
 * trip. All pure JVM (samplers are scripted).
 */
class P3SmudgeTest {

    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()

    private fun channel(c: Int, shift: Int): Int = (c ushr shift) and 0xFF

    // ---------------- 80/20 carry chain ----------------

    @Test
    fun mixCarriedIsEightyPercentCarriedTwentyPercentSampled() {
        val m = SmudgeMath.mixCarried(BLACK, WHITE)
        assertEquals(51, channel(m, 16)) // 0.8*0 + 0.2*255
        assertEquals(51, channel(m, 8))
        assertEquals(51, channel(m, 0))
        assertEquals("alpha stays opaque", 255, channel(m, 24))

        val m2 = SmudgeMath.mixCarried(WHITE, BLACK)
        assertEquals(204, channel(m2, 16)) // 0.8*255
    }

    @Test
    fun repeatedMixingConvergesToTheSampledColor() {
        var carried = BLACK
        repeat(20) { carried = SmudgeMath.mixCarried(carried, WHITE) }
        assertTrue("20 steps of 0.8^k carry must approach white",
            channel(carried, 16) > 250 && channel(carried, 0) > 250)
    }

    // ---------------- deposit cadence + alpha ----------------

    @Test
    fun depositWaitsForTheScreenIntervalConvertedToLocalUnits() {
        // 4 screen px at camera scale 2 -> 2 local units
        assertFalse(SmudgeMath.shouldDeposit(0f, 0f, 1.9f, 0f, 4f, 2f))
        assertTrue(SmudgeMath.shouldDeposit(0f, 0f, 2.1f, 0f, 4f, 2f))
        assertTrue("diagonal distance counts",
            SmudgeMath.shouldDeposit(0f, 0f, 1.5f, 1.5f, 4f, 2f))
        assertTrue("interval 0 deposits every step",
            SmudgeMath.shouldDeposit(0f, 0f, 0.01f, 0f, 0f, 1f))
    }

    @Test
    fun stampAlphaIsStrengthTimesPressure() {
        assertEquals(0.25f, SmudgeMath.stampAlpha(0.5f, 0.5f), 0.0001f)
        assertEquals(1f, SmudgeMath.stampAlpha(1f, 1f), 0.0001f)
        assertEquals(0f, SmudgeMath.stampAlpha(0.7f, 0f), 0.0001f)
        assertEquals("clamped at 1", 1f, SmudgeMath.stampAlpha(1.5f, 1f), 0.0001f)
    }

    @Test
    fun stampRadiusConvertsScreenSizeToLocalUnits() {
        assertEquals(10f, SmudgeMath.radiusLocal(20f, 1f), 0.0001f)
        assertEquals("zoomed camera -> smaller local radius for the same screen size",
            2.5f, SmudgeMath.radiusLocal(20f, 4f), 0.0001f)
    }

    // ---------------- gesture: commit / undo / carry ----------------

    @Test
    fun smudgeGestureDepositsAlongTheDragAndCommitsAsOneUndoStep() {
        val vm = DrawingViewModel()
        val doc = vm.document
        vm.smudgeStrength = 0.5f
        vm.smudgeSizeScreen = 20f

        vm.beginSmudge(100f, 100f, 0.5f) { _, _ -> WHITE }
        val live = vm.inProgressSmudge!!
        assertEquals("gesture starts with one stamp under the pen", 1, live.stamps.size)
        assertEquals(WHITE, live.stamps[0].color)
        assertEquals("alpha = strength x pressure", 0.25f, live.stamps[0].alpha, 0.0001f)
        assertEquals("radius = size/2 in level-local units", 10f, live.stamps[0].radius, 0.0001f)
        assertEquals(0L, live.levelId)

        // sub-interval move deposits nothing; a full interval deposits
        assertFalse(vm.extendSmudge(102f, 100f, 0.5f) { _, _ -> WHITE })
        assertEquals(1, live.stamps.size)
        assertTrue(vm.extendSmudge(140f, 100f, 0.5f) { _, _ -> WHITE })
        assertEquals(2, live.stamps.size)

        val committed = vm.endSmudge()
        assertTrue(committed != null)
        assertEquals(1, doc.activeLayer.smudges.size)
        assertSame(committed, doc.activeLayer.smudges[0])

        assertTrue(doc.undo())
        assertEquals("undo removes the WHOLE gesture", 0, doc.activeLayer.smudges.size)
        assertTrue(doc.redo())
        assertEquals(1, doc.activeLayer.smudges.size)
        assertEquals(2, doc.activeLayer.smudges[0].stamps.size)
    }

    @Test
    fun carriedColorMixesAcrossDepositSteps() {
        val vm = DrawingViewModel()
        // sampler paints black once the pen crosses x = 120
        val sampler: (Int, Int) -> Int = { x, _ -> if (x > 120) BLACK else WHITE }
        vm.beginSmudge(100f, 100f, 1f, sampler)
        val live = vm.inProgressSmudge!!
        assertEquals(WHITE, live.stamps[0].color)
        assertTrue(vm.extendSmudge(140f, 100f, 1f, sampler)) // samples BLACK
        val second = live.stamps[1].color
        assertEquals("0.8*white + 0.2*black = 204 per channel", 204, channel(second, 16))
        assertEquals(204, channel(second, 0))
        assertTrue(vm.extendSmudge(180f, 100f, 1f, sampler)) // samples BLACK again
        val third = live.stamps[2].color
        assertTrue("carry keeps darkening toward the sampled color (0.8*204 = ~163)",
            channel(third, 16) < 204 && channel(third, 16) >= 160)
    }

    @Test
    fun emptySmudgeGestureCommitsNothing() {
        val vm = DrawingViewModel()
        vm.beginSmudge(10f, 10f, 1f) { _, _ -> WHITE }
        vm.inProgressSmudge!!.stamps.clear()
        assertNull(vm.endSmudge())
        assertTrue(vm.document.activeLayer.smudges.isEmpty())
    }

    @Test
    fun lockedLayerRejectsSmudgeGestures() {
        val vm = DrawingViewModel()
        val doc = vm.document
        doc.setLayerLocked(doc.activeLayerIndex, true)
        vm.beginSmudge(10f, 10f, 1f) { _, _ -> WHITE }
        assertNull("locked layer must not open a smudge gesture", vm.inProgressSmudge)
        assertFalse(doc.commitSmudge(SmudgeData(0L).also {
            it.stamps.add(StampData(0f, 0f, 1f, BLACK, 0.5f))
        }))
        assertTrue(doc.activeLayer.smudges.isEmpty())
    }

    @Test
    fun smudgesAreNotStrokeErasable() {
        val vm = DrawingViewModel()
        val doc = vm.document
        vm.beginStroke(0f, 0f, 1f)
        vm.extendStroke(100f, 0f, 1f)
        vm.endStroke()
        val sm = SmudgeData(0L)
        sm.stamps.add(StampData(50f, 0f, 10f, BLACK, 1f))
        doc.activeLayer.smudges.add(sm)

        // erase right across the smudge's stamps
        vm.beginErase(0f, 0f)
        var changed = false
        for (x in 0..100 step 5) changed = vm.eraseTo(x.toFloat(), 0f) || changed
        vm.endErase()

        assertTrue("the stroke was erased", changed)
        assertEquals("smudges must survive the stroke eraser untouched",
            1, doc.activeLayer.smudges.size)
        assertEquals(10f, doc.activeLayer.smudges[0].stamps[0].radius, 0.0001f)
    }

    // ---------------- codec ----------------

    @Test
    fun smudgeCodecRoundTripIsLosslessAndStable() {
        val vm = DrawingViewModel()
        val doc = vm.document
        val sm = SmudgeData(0L)
        sm.stamps.add(StampData(11.5f, -22.25f, 6.75f, 0xFF3366AA.toInt(), 0.33f))
        sm.stamps.add(StampData(40f, 50f, 12f, 0xFF010203.toInt(), 1f))
        doc.activeLayer.smudges.add(sm)

        val json1 = vm.serialize()
        val restored = DocumentCodec.fromJson(json1).document.layers[1]
        assertEquals(1, restored.smudges.size)
        val rs = restored.smudges[0]
        assertEquals(0L, rs.levelId)
        assertEquals(2, rs.stamps.size)
        assertEquals(11.5f, rs.stamps[0].x, 0.0001f)
        assertEquals(-22.25f, rs.stamps[0].y, 0.0001f)
        assertEquals(6.75f, rs.stamps[0].radius, 0.0001f)
        assertEquals(0xFF3366AA.toInt(), rs.stamps[0].color)
        assertEquals(0.33f, rs.stamps[0].alpha, 0.0001f)
        assertEquals(0xFF010203.toInt(), rs.stamps[1].color)

        val session = DocumentCodec.fromJson(json1)
        assertEquals(json1, DocumentCodec.toJson(session.document, session.camera))
    }
}
