package com.example.rightposture

import com.example.rightposture.core.Params
import com.example.rightposture.core.pose.syntheticTwoStepRepetitions
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.PhaseTiming
import com.example.rightposture.core.reference.endRangeShare
import com.example.rightposture.core.reference.runAuthoringPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 10.3. Phase over the physio's own clock, so a ghost that leads reproduces their pauses instead of
 * sliding through them.
 */
class PhaseTimingTest {

    private val fps = 30.0

    // -------------------------------------------------------------------------------------
    // Shape
    // -------------------------------------------------------------------------------------

    @Test
    fun `the profile never goes backwards and spans the whole movement`() {
        val timing = timingOf(pauseFrames = 15)
        val phase = timing.phaseByTime

        assertEquals(Params.PHASE_BINS, phase.size)
        phase.zipWithNext().forEachIndexed { index, (a, b) ->
            assertTrue("phase fell from $a to $b at sample $index", b >= a)
        }
        assertTrue("starts at the start: ${phase.first()}", phase.first() < 5f)
        assertTrue("reaches the turnaround: ${phase.max()}", phase.max() >= 50f)
        assertTrue("ends back at the start: ${phase.last()}", phase.last() > 95f)
    }

    @Test
    fun `the stored duration is the physio's rep length`() {
        // 60 frames of movement plus a 15-frame beat at each end, at 30 fps: 3 s a rep.
        val timing = timingOf(pauseFrames = 15)

        assertEquals(90 / fps, timing.repDurationSeconds, 0.2)
    }

    // -------------------------------------------------------------------------------------
    // The pause - the reason this exists
    // -------------------------------------------------------------------------------------

    @Test
    fun `a deliberate end-range pause is reproduced, not slid through`() {
        // The device gate, stated as a test: a two-second hold at end range. Replayed at the stored
        // duration, the ghost must sit at the turnaround for most of that time. Advanced linearly
        // through phase instead it would pass through p = 50 in a single frame.
        val timing = timingOf(pauseFrames = 60)
        val step = 0.05
        var longest = 0.0
        var run = 0.0
        var t = 0.0
        while (t <= timing.repDurationSeconds) {
            run = if (abs(timing.phaseAt(t) - 50.0) <= 3.0) run + step else 0.0
            longest = maxOf(longest, run)
            t += step
        }

        assertTrue("held at the turnaround for only ${"%.2f".format(longest)} s of a 2 s pause", longest >= 1.5)
    }

    @Test
    fun `a longer pause flattens the profile more`() {
        val short = timingOf(pauseFrames = 15).endRangeShare()
        val long = timingOf(pauseFrames = 60).endRangeShare()

        assertTrue("2 s pause $long should dwarf a 0.5 s beat $short", long > short + 0.08)
        assertTrue("a 2 s pause in a ~5 s rep is a large share: $long", long > 0.35)
    }

    @Test
    fun `the timing and the poses come from the same reps`() {
        // One median rep, not poses from one set of reps timed by another.
        val result = runAuthoringPipeline(capture(pauseFrames = 15))
        val skeleton = result.skeleton!!

        assertTrue(skeleton.repCount >= Params.MIN_SURVIVING_REPS)
        assertEquals(skeleton.timing.phaseByTime.size, skeleton.binCount)
    }

    // -------------------------------------------------------------------------------------
    // Reading it
    // -------------------------------------------------------------------------------------

    @Test
    fun `past the end of the rep the last sample is held, not wrapped`() {
        // The cycle boundary belongs to the ghost clock (10.8), which knows about the rest beat.
        val timing = PhaseTiming(listOf(0f, 50f, 100f), repDurationSeconds = 2.0)

        assertEquals(100.0, timing.phaseAt(2.0), 1e-9)
        assertEquals(100.0, timing.phaseAt(7.5), 1e-9)
        assertEquals(0.0, timing.phaseAt(-1.0), 1e-9)
    }

    @Test
    fun `between samples the phase is interpolated in time`() {
        val timing = PhaseTiming(listOf(0f, 50f, 50f, 100f), repDurationSeconds = 3.0)

        assertEquals(25.0, timing.phaseAt(0.5), 1e-6)
        assertEquals(50.0, timing.phaseAt(1.5), 1e-6)
        assertEquals(75.0, timing.phaseAt(2.5), 1e-6)
    }

    @Test
    fun `a profile that goes backwards is refused`() {
        // §6.8.3 makes phase non-decreasing upstream, so one that is not means the builder is
        // wrong, not that the physio moved oddly.
        val refused = runCatching { PhaseTiming(listOf(0f, 60f, 40f, 100f), repDurationSeconds = 3.0) }

        assertTrue(refused.isFailure)
    }

    @Test
    fun `a zero duration is refused`() {
        assertTrue(runCatching { PhaseTiming(listOf(0f, 100f), repDurationSeconds = 0.0) }.isFailure)
    }

    // -------------------------------------------------------------------------------------

    private fun capture(pauseFrames: Int) = syntheticTwoStepRepetitions(repCount = 8, beatFrames = pauseFrames)
        .map { CapturedFrame(it.frame, it.timestampNs) }

    private fun timingOf(pauseFrames: Int): PhaseTiming {
        val result = runAuthoringPipeline(capture(pauseFrames))
        assertNull(result.failure, result.failure)
        val skeleton = result.skeleton
        assertNotNull("authoring must produce a skeleton", skeleton)
        return skeleton!!.timing
    }
}
