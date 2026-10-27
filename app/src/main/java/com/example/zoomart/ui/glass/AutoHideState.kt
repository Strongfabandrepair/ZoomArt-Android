package com.example.zoomart.ui.glass

/**
 * U1 (UI overhaul): the auto-hide state machine that drives the
 * "panels slide away while you draw" behavior.
 *
 *   IDLE            panels fully shown
 *   ACTIVE          a stylus stroke / pinch is running -> panels hidden
 *   PENDING_RESTORE activity ended -> wait DEBOUNCE_MS, then show again
 *
 * Transitions (all covered by AutoHideStateTest):
 *   IDLE           + activity(true)   -> ACTIVE          (onHide)
 *   ACTIVE         + activity(false)  -> PENDING_RESTORE (schedules restore)
 *   ACTIVE         + panelTouched     -> IDLE             (onShow, keep drawing)
 *   PENDING_RESTORE+ activity(true)   -> ACTIVE           (cancels restore)
 *   PENDING_RESTORE+ panelTouched     -> IDLE             (onShow now)
 *   PENDING_RESTORE+ timer fires      -> IDLE             (onShow)
 *
 * A [generation] counter voids stale debounce timers: drawing that RESUMES
 * inside the 600 ms window (or a touch that restores early) invalidates the
 * pending restore so it can never fire late and yank the panels back while
 * the user is working.
 *
 * Pure Kotlin: [scheduler] decouples it from any clock/looper so the JVM
 * suite can drive the debounce by hand. The UI layer passes a Handler-based
 * scheduler; a touch on a faded panel restores immediately (the spec's
 * "touches on the faded panel still work and restore it").
 */
class AutoHideStateMachine(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val scheduler: (delayMs: Long, action: () -> Unit) -> Unit = { _, _ -> },
    private val onHide: () -> Unit = {},
    private val onShow: () -> Unit = {},
) {

    enum class State { IDLE, ACTIVE, PENDING_RESTORE }

    var state: State = State.IDLE
        private set

    /** True while panels are (or should be) slid out + faded. */
    val panelsHidden: Boolean get() = state == State.ACTIVE

    /**
     * Bumped on every transition out of PENDING_RESTORE / early restore —
     * voids any debounce timer still in flight, so a stroke that RESUMES
     * inside the 600 ms window (or a touch that restores early) can never
     * be followed by a stale timer yanking the panels back mid-work.
     */
    private var generation = 0

    /** Canvas reports stylus-stroke / pinch begin (true) or end (false). */
    fun canvasActivity(active: Boolean) {
        when (state) {
            State.IDLE -> if (active) {
                state = State.ACTIVE
                onHide()
            }
            State.ACTIVE -> if (!active) {
                state = State.PENDING_RESTORE
                val g = ++generation
                scheduler(debounceMs) { if (g == generation) restore() }
            }
            State.PENDING_RESTORE -> if (active) {
                ++generation // drawing resumed inside the debounce: stay hidden
                state = State.ACTIVE
            }
        }
    }

    /** A touch landed on a faded panel: bring everything back at once. */
    fun panelTouched() {
        when (state) {
            State.ACTIVE, State.PENDING_RESTORE -> {
                ++generation
                restore()
            }
            State.IDLE -> { /* already shown */ }
        }
    }

    private fun restore() {
        if (state == State.IDLE) return
        state = State.IDLE
        onShow()
    }

    companion object {
        /** The spec's "~600 ms" idle window before panels spring back. */
        const val DEFAULT_DEBOUNCE_MS = 600L
    }
}
