package com.example.rightposture

import com.example.rightposture.core.Params
import com.example.rightposture.core.live.GhostClock
import com.example.rightposture.core.live.GhostState
import com.example.rightposture.core.live.GhostTick
import com.example.rightposture.core.live.phaseLag
import com.example.rightposture.core.reference.PhaseTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 10.8. The ghost clock, driven frame by frame at 30 Hz the way the camera drives it. */
class GhostClockTest {

    // -------------------------------------------------------------------------------------
    // Count-in and the physio's own timing
    // -------------------------------------------------------------------------------------

    @Test
    fun `the count-in counts down at the start pose and then expires`() {
        val run = Run()
        val first = run.tick()
        assertEquals(GhostState.COUNT_IN, first.state)
        assertEquals(0.0, first.phase!!, 0.0)
        assertEquals(Params.GHOST_COUNT_IN_SECONDS, first.countInSeconds!!, 1e-9)

        val halfway = run.advance(Params.GHOST_COUNT_IN_SECONDS / 2)
        assertEquals(GhostState.COUNT_IN, halfway.state)
        assertEquals(Params.GHOST_COUNT_IN_SECONDS / 2, halfway.countInSeconds!!, 0.05)

        val after = run.advance(Params.GHOST_COUNT_IN_SECONDS / 2 + FRAME)
        assertEquals(GhostState.MOVING, after.state)
        assertTrue("starts from the start pose", after.phase!! < 3.0)
    }

    @Test
    fun `the ghost moves on the physio's timing, not linearly`() {
        // 10.3's point: a pause at end range is time spent at one phase. Linear advance would sweep
        // straight through it.
        val run = Run(timing = pausedTiming())
        run.countIn()
        // 42% and 57% of the rep: both inside the held stretch, 0.6 s apart.
        val atPause = run.advance(PAUSED_REP_SECONDS * 0.42)
        val laterInPause = run.advance(PAUSED_REP_SECONDS * 0.15)

        assertEquals(GhostState.MOVING, laterInPause.state)
        assertEquals(50.0, atPause.phase!!, 1.0)
        assertEquals("still holding the end position", 50.0, laterInPause.phase!!, 1.0)
    }

    @Test
    fun `a demonstrated rep is followed by a rest beat and then the next one`() {
        val run = Run()
        run.countIn()
        val end = run.advance(REP_SECONDS + FRAME, moving = true)
        assertEquals(GhostState.RESTING, end.state)
        assertEquals(0.0, end.phase!!, 0.0)

        val next = run.advance(Params.GHOST_REST_SECONDS + FRAME, moving = true)
        assertEquals(GhostState.MOVING, next.state)
        assertTrue(next.phase!! < 5.0)
    }

    // -------------------------------------------------------------------------------------
    // Resync
    // -------------------------------------------------------------------------------------

    @Test
    fun `a completed rep resynchronises the cycle`() {
        val run = Run()
        run.countIn()
        val midway = run.advance(REP_SECONDS * 0.7, moving = true)
        assertTrue(midway.phase!! > 60.0)

        val resynced = run.tick(moving = false, repCompleted = true)
        assertEquals(GhostState.MOVING, resynced.state)
        assertEquals("back to the start of a fresh cycle", 0.0, resynced.phase!!, 1e-9)
    }

    @Test
    fun `a slightly slow patient does not drift away over a set of ten`() {
        // The patient takes 10% longer per rep than the physio and goes straight into the next.
        // Without resync the ghost would gain a tenth of a rep every rep and be most of a rep
        // ahead by the end - exactly when fatigue makes it most useful.
        val run = Run()
        run.countIn()
        val patientRep = REP_SECONDS * 1.1
        var patientTime = 0.0
        val lags = mutableListOf<Double>()
        repeat(10) {
            while (patientTime < patientRep) {
                val tick = run.tick(moving = true)
                lags += phaseLag(tick.phase!!, 100.0 * patientTime / patientRep)
                patientTime += FRAME
                run.step()
            }
            run.tick(moving = false, repCompleted = true)
            patientTime = 0.0
        }

        val lastRep = lags.takeLast((patientRep / FRAME).toInt())
        assertTrue("lag in the last rep ${lastRep.maxOf { abs(it) }}", lastRep.all { abs(it) <= 15.0 })
    }

    // -------------------------------------------------------------------------------------
    // Engagement guard
    // -------------------------------------------------------------------------------------

    @Test
    fun `a patient who never moves is still shown the movement, and flagged idle`() {
        // Device feedback: a patient who has stopped needs the demonstration most. The ghost does
        // not park; it keeps going and says the patient is idle so the screen can prompt them.
        val run = Run()
        run.countIn()
        assertFalse("not idle before a whole rep has gone by", run.advance(REP_SECONDS * 0.5).patientIdle)

        var sawMovement = false
        var last = run.advance(REP_SECONDS)
        repeat((3 * (REP_SECONDS + Params.GHOST_REST_SECONDS) / FRAME).toInt()) {
            run.step()
            last = run.tick()
            if (last.state == GhostState.MOVING && last.phase!! > 40.0) sawMovement = true
            if (last.state != GhostState.COUNT_IN) assertTrue("flagged idle", last.patientIdle)
        }
        assertTrue("still demonstrating to a still patient", sawMovement)
    }

    @Test
    fun `the idle flag clears the moment the patient moves, without a jump`() {
        val run = Run()
        run.countIn()
        val idle = run.advance(REP_SECONDS + Params.GHOST_REST_SECONDS + REP_SECONDS * 0.3)
        assertTrue(idle.patientIdle)

        run.step()
        val moving = run.tick(moving = true)
        assertFalse(moving.patientIdle)
        assertEquals("the ghost carries on from where it was", idle.phase!!, moving.phase!!, 3.0)
    }

    @Test
    fun `a patient who keeps moving is never flagged idle`() {
        val run = Run()
        run.countIn()
        repeat((10 * (REP_SECONDS + Params.GHOST_REST_SECONDS) / FRAME).toInt()) {
            val tick = run.tick(moving = true)
            assertFalse("idle on a moving patient", tick.patientIdle)
            run.step()
        }
    }

    // -------------------------------------------------------------------------------------
    // Time
    // -------------------------------------------------------------------------------------

    @Test
    fun `a timestamp gap does not advance the ghost`() {
        val run = Run()
        run.countIn()
        val before = run.advance(REP_SECONDS * 0.3, moving = true)

        run.jump(5.0)
        val after = run.tick(moving = true)

        assertEquals(GhostState.MOVING, after.state)
        assertEquals("a stall is not time the patient watched", before.phase!!, after.phase!!, 1e-9)
    }

    @Test
    fun `a timestamp going backwards does not advance it either`() {
        val run = Run()
        run.countIn()
        val before = run.advance(REP_SECONDS * 0.3, moving = true)

        run.jump(-0.2)
        assertEquals(before.phase!!, run.tick(moving = true).phase!!, 1e-9)
    }

    // -------------------------------------------------------------------------------------
    // End of set
    // -------------------------------------------------------------------------------------

    @Test
    fun `the clock stops when the set is complete, and stays stopped`() {
        val run = Run()
        run.countIn()
        run.advance(REP_SECONDS * 0.5, moving = true)

        val done = run.tick(setComplete = true)
        assertEquals(GhostState.DONE, done.state)
        assertNull("nothing to draw", done.phase)

        val later = run.advance(10.0, moving = true)
        assertEquals(GhostState.DONE, later.state)
        assertNull(later.phase)
    }

    // -------------------------------------------------------------------------------------
    // Lag
    // -------------------------------------------------------------------------------------

    @Test
    fun `lag is measured round the cycle`() {
        assertEquals(20.0, phaseLag(60.0, 40.0), 1e-9)
        assertEquals(-20.0, phaseLag(40.0, 60.0), 1e-9)
        assertEquals("the end of one rep is the start of the next", 4.0, phaseLag(2.0, 98.0), 1e-9)
        assertEquals(-4.0, phaseLag(98.0, 2.0), 1e-9)
        assertEquals(0.0, phaseLag(100.0, 0.0), 1e-9)
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    private companion object {
        const val FRAME = 1.0 / 30.0
        const val REP_SECONDS = 2.0
        const val PAUSED_REP_SECONDS = 4.0
    }

    /** Phase rising evenly in time. */
    private fun linearTiming() = PhaseTiming(List(Params.PHASE_BINS) { it.toFloat() }, REP_SECONDS)

    /** Out over the first 40%, held at 50 for the middle 20%, back over the last 40%. */
    private fun pausedTiming() = PhaseTiming(
        List(Params.PHASE_BINS) { bin ->
            val t = bin / 100.0
            when {
                t < 0.4 -> (50.0 * t / 0.4).toFloat()
                t <= 0.6 -> 50f
                else -> (50.0 + 50.0 * (t - 0.6) / 0.4).toFloat()
            }
        },
        PAUSED_REP_SECONDS,
    )

    /** A camera feeding the clock one frame at a time. */
    private inner class Run(timing: PhaseTiming = linearTiming()) {
        private val clock = GhostClock(timing)
        private var timestampNs = 1_000_000_000L

        fun tick(moving: Boolean = false, repCompleted: Boolean = false, setComplete: Boolean = false): GhostTick =
            clock.tick(timestampNs, moving, repCompleted, setComplete)

        fun step() {
            timestampNs += (FRAME * 1e9).toLong()
        }

        fun jump(seconds: Double) {
            timestampNs += (seconds * 1e9).toLong()
        }

        /** Runs frames for [seconds] and returns the last tick. */
        fun advance(seconds: Double, moving: Boolean = false): GhostTick {
            var last: GhostTick? = null
            repeat((seconds / FRAME).toInt().coerceAtLeast(1)) {
                step()
                last = tick(moving)
            }
            return assertNotNullAndGet(last)
        }

        fun countIn() {
            tick()
            advance(Params.GHOST_COUNT_IN_SECONDS + FRAME)
        }
    }

    private fun assertNotNullAndGet(tick: GhostTick?): GhostTick {
        assertNotNull(tick)
        return tick!!
    }
}
