package com.example.rightposture.core.live

import com.example.rightposture.core.Params
import com.example.rightposture.core.reference.PhaseTiming

/**
 * 10.8. What the ghost is doing.
 *
 * There is deliberately no "waiting" state. The first build parked the ghost at the start pose when
 * the patient stood still; on device that took away the one thing a patient who has stopped needs -
 * a demonstration of what to do next. The ghost now keeps demonstrating, and a still patient is
 * *prompted* instead ([GhostTick.patientIdle]).
 */
enum class GhostState { COUNT_IN, MOVING, RESTING, DONE }

/**
 * One frame of the clock.
 *
 * @property phase the phase to draw the ghost at, on the same `p ∈ [0, 100]` axis as the skeleton,
 *   or null in [GhostState.DONE], when nothing should be drawn.
 * @property countInSeconds seconds left to count in, while [state] is [GhostState.COUNT_IN].
 * @property patientIdle the patient has not moved for [Params.GHOST_IDLE_CYCLES] whole demonstrated
 *   reps. The ghost carries on; this is the cue to prompt them to move with it. Clears the moment
 *   they move.
 */
data class GhostTick(
    val state: GhostState,
    val phase: Double?,
    val countInSeconds: Double? = null,
    val patientIdle: Boolean = false,
)

/**
 * 10.8. The one clock in the live path: it advances the ghost through the physio's movement in
 * real time, on 10.3's time axis, so the ghost can *lead*.
 *
 * Everything else in the live pipeline is tempo-free on purpose - phase comes from the dominant
 * joint's value, never from time - which is what makes scoring fair to a slow patient. That is also
 * why scoring cannot drive a demonstration: a ghost that only moves when the patient does cannot
 * show them a movement they have not made. So this clock **leads and never scores**. The patient is
 * still scored on their own phase; how far they trail the ghost is [phaseLag], for display only.
 *
 * Pure and frame-driven: no wall clock, no coroutine. Time is whatever the camera's timestamps say,
 * with gaps longer than [Params.GHOST_MAX_FRAME_GAP_SECONDS] not counted.
 *
 * ```
 *   COUNT_IN ──► MOVING ──► RESTING ──► MOVING ── …
 *                                │
 *     any rep completed: resync to a fresh cycle;  set complete: DONE (from anywhere)
 * ```
 *
 * A patient who never moves is still shown the movement, rep after rep - they are the one who most
 * needs to see it - and [GhostTick.patientIdle] tells the screen to prompt them.
 */
class GhostClock(private val timing: PhaseTiming) {

    private var state = GhostState.COUNT_IN
    /** Seconds of counted time since the first frame. Frozen across long gaps. */
    private var now = 0.0
    private var lastTimestampNs: Long? = null
    /** When the current state began, in [now]'s units. */
    private var stateStart = 0.0
    /** Whether the patient left IDLE at any point during the cycle being demonstrated. */
    private var movedThisCycle = false
    private var idleCycles = 0

    /**
     * Advances to [timestampNs] and returns where the ghost should be.
     *
     * @param patientMoving the patient is mid-rep - the engine's phase state is not IDLE.
     * @param repCompleted the patient finished a rep this frame, valid or not. Either way they
     *   completed a movement cycle, and the ghost restarts with them.
     * @param setComplete the prescription is met. The ghost stops: one still demonstrating reps is
     *   an instruction to keep going past the prescription.
     */
    fun tick(
        timestampNs: Long,
        patientMoving: Boolean,
        repCompleted: Boolean,
        setComplete: Boolean,
    ): GhostTick {
        advanceTo(timestampNs)

        if (state == GhostState.DONE || setComplete) {
            state = GhostState.DONE
            return GhostTick(GhostState.DONE, phase = null)
        }

        // Resync. Without it a patient and a ghost drift apart over a set of ten, and the ghost
        // stops being followable just when fatigue makes it most useful.
        if (repCompleted) {
            idleCycles = 0
            startCycle()
        }
        if (patientMoving) {
            movedThisCycle = true
            idleCycles = 0
        }

        val elapsed = now - stateStart
        when (state) {
            GhostState.COUNT_IN -> if (elapsed >= Params.GHOST_COUNT_IN_SECONDS) startCycle()

            GhostState.MOVING -> if (elapsed >= timing.repDurationSeconds) {
                if (!movedThisCycle) idleCycles++
                enter(GhostState.RESTING)
            }

            GhostState.RESTING -> if (elapsed >= Params.GHOST_REST_SECONDS) startCycle()

            GhostState.DONE -> Unit
        }

        val idle = idleCycles >= Params.GHOST_IDLE_CYCLES
        return when (state) {
            GhostState.COUNT_IN -> GhostTick(
                GhostState.COUNT_IN,
                phase = 0.0,
                countInSeconds = (Params.GHOST_COUNT_IN_SECONDS - (now - stateStart)).coerceAtLeast(0.0),
            )
            GhostState.MOVING -> GhostTick(GhostState.MOVING, timing.phaseAt(now - stateStart), patientIdle = idle)
            GhostState.RESTING -> GhostTick(GhostState.RESTING, phase = 0.0, patientIdle = idle)
            GhostState.DONE -> GhostTick(GhostState.DONE, phase = null)
        }
    }

    private fun advanceTo(timestampNs: Long) {
        val last = lastTimestampNs
        lastTimestampNs = timestampNs
        if (last == null) return
        val dt = (timestampNs - last) / 1_000_000_000.0
        // Backwards, or a stall: the time did not pass for the patient watching, so not for the
        // ghost either.
        if (dt > 0.0 && dt <= Params.GHOST_MAX_FRAME_GAP_SECONDS) now += dt
    }

    private fun startCycle() {
        enter(GhostState.MOVING)
        movedThisCycle = false
    }

    private fun enter(next: GhostState) {
        state = next
        stateStart = now
    }
}

/**
 * How far the ghost is ahead of the patient, in phase, from -50 to 50. Positive means the patient is
 * behind.
 *
 * Phase 100 and phase 0 are the same pose - the end of one rep is the start of the next - so the
 * difference is taken round the cycle: a ghost at 2 and a patient at 98 are 4 apart, not 96.
 *
 * Display only. Feeding this into a score would turn hesitation into a form fault and abandon
 * §6.8.1's tempo invariance; §6.17.3's tempo penalty already judges tempo, once, per rep.
 */
fun phaseLag(ghostPhase: Double, patientPhase: Double): Double {
    val difference = (ghostPhase - patientPhase) % 100.0
    return when {
        difference >= 50.0 -> difference - 100.0
        difference < -50.0 -> difference + 100.0
        else -> difference
    }
}
