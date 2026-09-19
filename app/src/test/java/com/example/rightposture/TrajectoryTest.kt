package com.example.rightposture

import com.example.rightposture.core.Params
import com.example.rightposture.core.live.CompletedRep
import com.example.rightposture.core.live.PhaseConfig
import com.example.rightposture.core.live.PhaseTracker
import com.example.rightposture.core.live.RepRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/**
 * §6.12.4 accepts a rep on where it started, how far it went and how long it took. None of those
 * describe the **path**, so a patient who jumps straight to the end pose and holds there satisfies
 * every one of them - which is what was reported from a device.
 *
 * The aim is that the patient performs the exercise the way the physio recorded it, so the rep is
 * now also required to have been observed passing through each quarter of its own range, on both
 * legs of the movement.
 */
class TrajectoryTest {

    private val start = 90.0
    private val peak = 20.0
    private val span = peak - start
    private val referencePeriod = 3.0
    private val fps = 30.0

    private fun tracker() = PhaseTracker(PhaseConfig(start, peak, referencePeriod))

    private fun drive(values: List<Float?>): List<CompletedRep> {
        val tracker = tracker()
        val reps = mutableListOf<CompletedRep>()
        var timestampNs = 0L
        values.forEach { value ->
            tracker.update(value, timestampNs).completedRep?.let { reps += it }
            timestampNs += (1_000_000_000.0 / fps).toLong()
        }
        return reps
    }

    private fun rest(seconds: Double = 0.7) = List((seconds * fps).toInt()) { start.toFloat() }

    /** The movement the physio recorded: a smooth out-and-back. */
    private fun smoothRep(durationSeconds: Double = referencePeriod, amplitude: Double = 1.0): List<Float?> {
        val frames = (durationSeconds * fps).toInt()
        return (0 until frames).map {
            val u = it.toDouble() / frames
            (start + span * amplitude * (0.5 - 0.5 * cos(2 * PI * u))).toFloat()
        }
    }

    /** Straight to the end pose, hold there, straight back. Nothing in between. */
    private fun jumpRep(holdSeconds: Double = 2.0) = List((holdSeconds * fps).toInt()) { peak.toFloat() }

    // -------------------------------------------------------------------------------------

    @Test
    fun `jumping to the end pose and holding does not count`() {
        val rep = drive(rest() + jumpRep() + rest()).single()

        assertFalse("the shortcut must not be counted as a rep", rep.valid)
        assertEquals(RepRejection.SKIPPED, rep.rejection)
    }

    @Test
    fun `the shortcut reaches full range and a legal duration, and is still refused`() {
        // Worth stating explicitly: this rep passes every check §6.12.4 had before. If the range or
        // the duration were doing the work here, the checkpoints would be redundant.
        val rep = drive(rest() + jumpRep() + rest()).single()

        assertTrue("it reached the extremum", rep.phiMax >= Params.REP_ACCEPTANCE_PHASE_MAX)
        assertTrue(
            "it lasted a legal time",
            rep.durationSeconds >= Params.REP_DURATION_MIN_REF_FRACTION * referencePeriod &&
                rep.durationSeconds <= Params.REP_DURATION_MAX_REF_FRACTION * referencePeriod,
        )
        assertFalse(rep.valid)
    }

    @Test
    fun `the movement the physio recorded counts`() {
        val rep = drive(rest() + smoothRep() + rest()).single()

        assertTrue("a faithful rep must count: $rep", rep.valid)
        assertEquals(rep.checkpointsRequired, rep.checkpointsVisited)
    }

    @Test
    fun `a rep that stops a little short is short, not a skip`() {
        // 0.8 clears §6.12.4's 0.75 acceptance. Its path is complete over the range it covered, so
        // the checkpoints must not fire - the checkpoint test is about the path, not the extent.
        val rep = drive(rest() + smoothRep(amplitude = 0.8) + rest()).single()

        assertTrue("a slightly short but complete rep counts: $rep", rep.valid)
    }

    @Test
    fun `a rep below the acceptance range is refused for range, not for skipping`() {
        // 0.7 clears PHASE_ASCEND_MIN so the rep completes at all, and falls under the 0.75
        // acceptance so it is refused. Below PHASE_ASCEND_MIN the tracker never leaves DESCEND and
        // no rep is produced to judge.
        val rep = drive(rest() + smoothRep(amplitude = 0.7) + rest()).single()

        assertFalse(rep.valid)
        assertEquals(RepRejection.RANGE, rep.rejection)
    }

    @Test
    fun `checkpoints required scale with how far the rep actually went`() {
        val full = drive(rest() + smoothRep() + rest()).single()
        val short = drive(rest() + smoothRep(amplitude = 0.8) + rest()).single()

        assertTrue(
            "a shorter rep needs fewer checkpoints: ${short.checkpointsRequired} vs ${full.checkpointsRequired}",
            short.checkpointsRequired <= full.checkpointsRequired,
        )
        assertTrue("but always at least one", short.checkpointsRequired >= 1)
    }

    @Test
    fun `going out properly and dropping straight back is caught`() {
        // The two legs are tracked separately for this case. A single combined mask would be filled
        // by the good leg and the collapse on the way back would go unnoticed.
        val outward = smoothRep().take((referencePeriod * fps / 2).toInt())
        val collapse = List((0.8 * fps).toInt()) { start.toFloat() }
        val reps = drive(rest() + outward + collapse + rest())

        // Asserted over the whole list rather than under an `if (rep != null)`, which never ran.
        // A tracker that never completes the rep at all is an acceptable outcome here; what must
        // not happen is the collapse being banked as a good rep.
        assertTrue("a collapsed return is not the recorded movement: $reps", reps.none { it.valid })
    }

    @Test
    fun `a faster but complete rep still counts`() {
        // The bucket width is sized against the fastest rep the duration bound accepts, so speed
        // alone must never read as a skip.
        val fastest = Params.REP_DURATION_MIN_REF_FRACTION * referencePeriod * 1.05
        val rep = drive(rest() + smoothRep(durationSeconds = fastest) + rest()).single()

        assertTrue("a fast but continuous rep is not a skip: $rep", rep.rejection != RepRejection.SKIPPED)
    }

    @Test
    fun `a rep too fast to judge is reported as too fast, not as a skip`() {
        // Past the duration bound the sampling cannot tell a sprint from a jump, so the verdict has
        // to be the one that is certainly true.
        val rep = drive(rest() + smoothRep(durationSeconds = 0.3 * referencePeriod) + rest()).single()

        assertEquals(RepRejection.TOO_FAST, rep.rejection)
    }

    // -------------------------------------------------------------------------------------
    // The per-frame rate bound, which catches jumps too small to skip a whole checkpoint
    // -------------------------------------------------------------------------------------

    private fun at(phi: Double) = (start + span * phi).toFloat()

    /** A smooth run of frames from one phi to another, so nothing but the snap is a jump. */
    private fun ramp(from: Double, to: Double, seconds: Double): List<Float?> {
        val frames = (seconds * fps).toInt()
        return (1..frames).map { at(from + (to - from) * it.toDouble() / frames) }
    }

    /**
     * A rep that covers every checkpoint on both legs at a legal duration, with one snap **inside**
     * a single quarter - 0.30 to 0.45 in one frame, which is 4.5 phi/s against a bound of 3.33.
     *
     * The rate bound is the only thing wrong with it, which is the point: the previous version of
     * this test snapped across most of the range and produced no rep at all, so its assertion never
     * ran. A shortcut the tracker refuses to complete proves nothing about the shortcuts it does.
     */
    private fun partialJumpRep(): List<Float?> =
        ramp(0.0, 0.30, 0.5) + listOf(at(0.45)) + ramp(0.45, 1.0, 0.5) +
            List((0.2 * fps).toInt()) { at(1.0) } + ramp(1.0, 0.0, 1.0)

    @Test
    fun `a jump smaller than a checkpoint is still caught`() {
        // Checkpoints are quarter-width, so a patient can jump inside one and satisfy coverage.
        // The rate bound is what makes "follow the trajectory" hold at frame resolution.
        val rep = drive(rest() + partialJumpRep() + rest()).single()

        assertFalse("a mid-range snap is not the recorded movement: $rep", rep.valid)
        assertEquals(RepRejection.SKIPPED, rep.rejection)
        // Stated explicitly: the checkpoints were all satisfied, so the rate bound did the work.
        assertEquals(rep.checkpointsRequired, rep.checkpointsVisited)
        assertTrue(rep.maxPhiRatePerSecond > Params.TRAJECTORY_MAX_RATE_MULTIPLE * 2.0 / referencePeriod)
    }

    @Test
    fun `the same rep without the snap counts`() {
        // The control. Without it the test above could be passing for any of half a dozen reasons.
        val smooth = ramp(0.0, 0.30, 0.5) + ramp(0.30, 1.0, 0.6) +
            List((0.2 * fps).toInt()) { at(1.0) } + ramp(1.0, 0.0, 1.0)
        val rep = drive(rest() + smooth + rest()).single()

        assertTrue("only the snap may be what refused it: $rep", rep.valid)
    }

    @Test
    fun `the recorded movement stays well under the rate bound`() {
        val rep = drive(rest() + smoothRep() + rest()).single()
        val bound = Params.TRAJECTORY_MAX_RATE_MULTIPLE * 2.0 / referencePeriod

        assertTrue(
            "a faithful rep must not approach the bound: ${rep.maxPhiRatePerSecond} vs $bound",
            rep.maxPhiRatePerSecond < bound,
        )
    }

    @Test
    fun `the fastest legal rep stays under the rate bound`() {
        // The margin between a legitimate sprint and a jump is the whole basis for the multiple, so
        // it is asserted rather than left to the comment in Params.
        val fastest = Params.REP_DURATION_MIN_REF_FRACTION * referencePeriod * 1.05
        val rep = drive(rest() + smoothRep(durationSeconds = fastest) + rest()).single()
        val bound = Params.TRAJECTORY_MAX_RATE_MULTIPLE * 2.0 / referencePeriod

        assertTrue(
            "a legal sprint must stay under the bound: ${rep.maxPhiRatePerSecond} vs $bound",
            rep.maxPhiRatePerSecond < bound,
        )
    }

    @Test
    fun `a dropout is not mistaken for a jump`() {
        // The frames either side of a dropout are not adjacent, so their difference is not a rate.
        // §6.6 says the unseen part is the part we cannot judge; it must not be judged as a skip.
        val full = smoothRep()
        val blinded = full.mapIndexed { index, value ->
            if (index in 12..16) null else value
        }
        val reps = drive(rest() + blinded + rest())

        assertTrue(
            "a dropout must not read as a skip: $reps",
            reps.none { it.rejection == RepRejection.SKIPPED },
        )
    }

    @Test
    fun `every rep reports what it was measured against`() {
        val rep = drive(rest() + smoothRep() + rest()).single()

        assertTrue(rep.checkpointsRequired in 1..Params.TRAJECTORY_CHECKPOINTS)
        assertTrue(rep.checkpointsVisited <= rep.checkpointsRequired)
    }
}
