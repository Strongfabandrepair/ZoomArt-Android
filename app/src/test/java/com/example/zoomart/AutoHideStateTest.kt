package com.example.zoomart

import com.example.zoomart.ui.glass.AutoHideStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U1 (UI overhaul): pure JVM tests for the auto-hide state machine —
 * active -> hidden, end -> debounce -> restored, resume-inside-debounce
 * stays hidden, and a touch on a faded panel restores immediately.
 * The scheduler is captured by hand so the ~600 ms debounce is driven
 * deterministically without any clock.
 */
class AutoHideStateTest {

    private class Harness(debounceMs: Long = AutoHideStateMachine.DEFAULT_DEBOUNCE_MS) {
        val hideCalls = ArrayList<Int>()
        val showCalls = ArrayList<Int>()
        val scheduled = ArrayList<Pair<Long, () -> Unit>>()

        val machine = AutoHideStateMachine(
            debounceMs = debounceMs,
            scheduler = { delay, action -> scheduled.add(delay to action) },
            onHide = { hideCalls.add(1) },
            onShow = { showCalls.add(1) },
        )

        fun fireScheduled() {
            val pending = ArrayList(scheduled)
            scheduled.clear()
            pending.forEach { it.second() }
        }
    }

    @Test
    fun activityHidesPanelsAndEndDebouncesTheRestore() {
        val h = Harness()
        assertEquals(AutoHideStateMachine.State.IDLE, h.machine.state)

        h.machine.canvasActivity(true)
        assertEquals("stroke began -> panels hidden", 1, h.hideCalls.size)
        assertEquals(AutoHideStateMachine.State.ACTIVE, h.machine.state)
        assertTrue(h.machine.panelsHidden)

        h.machine.canvasActivity(false)
        assertEquals("restore is debounced, not immediate", 0, h.showCalls.size)
        assertEquals(AutoHideStateMachine.State.PENDING_RESTORE, h.machine.state)
        assertEquals("exactly one 600ms timer scheduled",
            1, h.scheduled.size)
        assertEquals(600L, h.scheduled[0].first)

        h.fireScheduled()
        assertEquals("debounce fired -> panels restored", 1, h.showCalls.size)
        assertEquals(AutoHideStateMachine.State.IDLE, h.machine.state)
        assertFalse(h.machine.panelsHidden)
    }

    @Test
    fun resumingInsideTheDebounceWindowStaysHidden() {
        val h = Harness()
        h.machine.canvasActivity(true)
        h.machine.canvasActivity(false) // PENDING_RESTORE

        h.machine.canvasActivity(true) // drawing resumed within 600ms
        assertEquals("no restore while drawing resumed", 0, h.showCalls.size)
        assertEquals(AutoHideStateMachine.State.ACTIVE, h.machine.state)

        h.fireScheduled() // the stale debounce timer is a no-op (state went ACTIVE)
        // the machine is IDLE-proof: still ACTIVE until a clean end
        assertEquals(AutoHideStateMachine.State.ACTIVE, h.machine.state)

        // a clean end + debounce restores exactly once
        h.machine.canvasActivity(false)
        h.fireScheduled()
        assertEquals(1, h.showCalls.size)
        assertEquals(AutoHideStateMachine.State.IDLE, h.machine.state)
    }

    @Test
    fun touchingAFadedPanelRestoresItImmediately() {
        val h = Harness()
        h.machine.canvasActivity(true)
        h.machine.canvasActivity(false) // PENDING_RESTORE with a live timer

        h.machine.panelTouched()
        assertEquals("touch restores now, not after the debounce",
            1, h.showCalls.size)
        assertEquals(AutoHideStateMachine.State.IDLE, h.machine.state)

        h.fireScheduled() // stale timer must not double-restore
        assertEquals("no double restore", 1, h.showCalls.size)
    }

    @Test
    fun touchWhileActiveRestoresAndNextStrokeHidesAgain() {
        val h = Harness()
        h.machine.canvasActivity(true)

        h.machine.panelTouched() // finger on the faded panel mid-stroke
        assertEquals(1, h.showCalls.size)
        assertEquals(AutoHideStateMachine.State.IDLE, h.machine.state)

        h.machine.canvasActivity(true) // the next stroke hides again
        assertEquals(2, h.hideCalls.size)
        assertTrue(h.machine.panelsHidden)
    }

    @Test
    fun idleEventsAndExtraEndsAreNoOps() {
        val h = Harness()
        h.machine.canvasActivity(false) // end with nothing running
        h.machine.panelTouched()        // touch with everything shown
        assertEquals(0, h.hideCalls.size)
        assertEquals(0, h.showCalls.size)
        assertEquals(AutoHideStateMachine.State.IDLE, h.machine.state)
        assertEquals(0, h.scheduled.size)
    }
}
