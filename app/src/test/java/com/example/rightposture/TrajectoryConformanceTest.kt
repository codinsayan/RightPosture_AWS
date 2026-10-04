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
 * §6.12.4, 11.1: what a checkpoint is a statement *about*.
 *
 * [PoseConformanceTest] established that a checkpoint is only credited when the whole pose was right
 * there rather than just the dominant angle. It credited one on a **single** conformant frame, which
 * answers "was the patient ever in the right position in this quarter of the range" — and a patient
 * who is out of position for three quarters of a bucket and passes through the right pose once on
 * the way answers yes.
 *
 * That is the compound-exercise hole reported from a device: the dominant joint travels legally, the
 * omitted joint happens to sweep through the right angle somewhere in each bucket, and the rep
 * counts. A checkpoint is now credited on a majority of its frames.
 *
 * 11.2 adds the backstop beneath that: a rep whose pose was wrong for most of it is refused even when
 * every checkpoint it was asked for passed, because checkpoints are only asked for as far as the rep
 * fully crossed - and an end-range hold sits past that line.
 */
class TrajectoryConformanceTest {

    private val start = 90.0
    private val peak = 20.0
    private val span = peak - start
    private val referencePeriod = 3.0
    private val fps = 30.0

    /** Drives the tracker with a conformance verdict decided per frame from `φ`. */
    private fun drive(
        values: List<Float?>,
        conformant: (Double) -> Boolean = { true },
    ): List<CompletedRep> {
        val tracker = PhaseTracker(PhaseConfig(start, peak, referencePeriod))
        val reps = mutableListOf<CompletedRep>()
        var timestampNs = 0L
        values.forEach { value ->
            val phi = if (value == null) 0.0 else (value - start) / span
            tracker.update(value, timestampNs, conformant(phi)).completedRep?.let { reps += it }
            timestampNs += (1_000_000_000.0 / fps).toLong()
        }
        return reps
    }

    private fun rest(seconds: Double = 0.7) = List((seconds * fps).toInt()) { start.toFloat() }

    private fun phiAt(u: Double) = 0.5 - 0.5 * cos(2 * PI * u)

    private fun smoothRep(durationSeconds: Double = referencePeriod): List<Float?> {
        val frames = (durationSeconds * fps).toInt()
        return (0 until frames).map { (start + span * phiAt(it.toDouble() / frames)).toFloat() }
    }

    // -------------------------------------------------------------------------------------
    // The majority rule
    // -------------------------------------------------------------------------------------

    @Test
    fun `a bucket the patient was mostly out of position in is not credited`() {
        // In position one frame in four, throughout. Under the single-frame rule this credited every
        // bucket on both legs and counted as a clean rep.
        var frame = 0
        val rep = drive(rest() + smoothRep() + rest(), conformant = { frame++ % 4 == 0 }).single()

        assertFalse("being in position a quarter of the time is not the movement: $rep", rep.valid)
        assertEquals(RepRejection.SKIPPED, rep.rejection)
        assertTrue("the rep should report which frames were off", rep.nonConformantFrames > 0)
    }

    @Test
    fun `a bucket the patient was mostly in position in is still credited`() {
        // The other side of the same rule, and the one that matters for not punishing a patient who
        // is doing the exercise: a clear majority has to pass.
        var frame = 0
        val rep = drive(rest() + smoothRep() + rest(), conformant = { frame++ % 4 != 0 }).single()

        assertTrue("three frames in four is the movement: $rep", rep.valid)
    }

    @Test
    fun `sparse landmark jitter still does not lose the rep`() {
        // Why the rule is a majority and not unanimity. One frame in seventeen outside tolerance is
        // noise, and a patient must not be charged for their phone's jitter.
        var frame = 0
        val rep = drive(rest() + smoothRep() + rest(), conformant = { frame++ % 17 != 0 }).single()

        assertTrue("one jittering frame in seventeen is not a skipped step: $rep", rep.valid)
        assertTrue("and it is still on the record", rep.nonConformantFrames > 0)
    }

    @Test
    fun `a rep performed correctly throughout is unaffected`() {
        val rep = drive(rest() + smoothRep() + rest()).single()

        assertTrue(rep.valid)
        assertEquals(0, rep.nonConformantFrames)
        assertEquals(Params.TRAJECTORY_CHECKPOINTS, rep.checkpointsVisited)
        assertEquals(1.0, rep.conformantFraction, 1e-9)
    }

    @Test
    fun `the threshold leaves room for the jitter it has to tolerate`() {
        // Sized against the jitter case above rather than picked. If these ever cross, the rule
        // starts rejecting noise instead of omissions, and the jitter test above is the only thing
        // that would say so.
        assertTrue(
            "one frame in seventeen sits at 0.94 and must stay clear of the bar",
            Params.TRAJECTORY_MIN_BUCKET_CONFORMANT_FRACTION in 0.5..0.8,
        )
    }

    // -------------------------------------------------------------------------------------
    // Overshoot: past the reference's extremum there is nothing left to disagree with
    // -------------------------------------------------------------------------------------

    @Test
    fun `swinging past the reference is not counted as a skipped step`() {
        // `phaseIndex()` saturates at 50 once φ passes 1, so a patient going well beyond what the
        // physio recorded is compared against the turnaround while being past it, and every joint
        // that moves with the dominant one reads as deviating. That is an artefact of the overshoot,
        // which §6.12.4 and RepNote.Overshot already have their own and more useful things to say
        // about — counting it here would report one problem twice and pick the less actionable of
        // two true statements.
        //
        // Without the abstention this rep is refused as SKIPPED and RepNoteTest's two overshoot
        // cases fail with it.
        val frames = (referencePeriod * fps).toInt()
        val overshoot = (0 until frames).map {
            (start + span * 1.4 * phiAt(it.toDouble() / frames)).toFloat()
        }
        val rep = drive(rest() + overshoot + rest(), conformant = { it <= 1.0 }).single()

        assertTrue("the overshooting rep must still count: $rep", rep.valid)
        // 11.2's denominator is judged frames, not valid ones. Counting the unjudged overshoot
        // frames would dilute the fraction with frames nobody scored.
        assertEquals(1.0, rep.conformantFraction, 1e-9)
    }

    @Test
    fun `an overshoot does not launder a genuinely skipped step either`() {
        // The abstention must not become a way through the gate: a patient out of position on the
        // way out, where the reference still has something to say, is still refused however far
        // past the extremum they then swing.
        val frames = (referencePeriod * fps).toInt()
        val overshoot = (0 until frames).map {
            (start + span * 1.4 * phiAt(it.toDouble() / frames)).toFloat()
        }
        val rep = drive(rest() + overshoot + rest(), conformant = { it > 1.0 }).single()

        assertFalse("the observable part of the movement was wrong: $rep", rep.valid)
        assertEquals(RepRejection.SKIPPED, rep.rejection)
    }

    // -------------------------------------------------------------------------------------
    // 11.2: the whole rep, beneath the checkpoints
    // -------------------------------------------------------------------------------------

    /** Travels out to [peakPhi], holds there for [holdSeconds], and comes back. */
    private fun repWithHold(peakPhi: Double, holdSeconds: Double): List<Float?> {
        val legFrames = fps.toInt()
        fun at(phi: Double) = (start + span * phi).toFloat()
        val out = (0 until legFrames).map { at(peakPhi * (0.5 - 0.5 * cos(PI * it / legFrames))) }
        val hold = List((holdSeconds * fps).toInt()) { at(peakPhi) }
        val back = (0 until legFrames).map { at(peakPhi * (0.5 + 0.5 * cos(PI * it / legFrames))) }
        return out + hold + back
    }

    @Test
    fun `an end-range hold performed out of position is refused though every checkpoint passed`() {
        // The case the per-bucket rule cannot see. Peaking at 0.95, the rep fully crosses three
        // buckets and only those are required; the top one, where the whole hold sits, is never
        // examined. The travel is right and the hold is wrong - §6.17.2's critical window.
        val rep = drive(
            rest() + repWithHold(peakPhi = 0.95, holdSeconds = 2.5) + rest(),
            conformant = { it < 0.75 },
        ).single()

        assertEquals("every checkpoint asked for was passed: $rep", rep.checkpointsRequired, rep.checkpointsVisited)
        assertTrue("the top bucket is not one of them: $rep", rep.checkpointsRequired < Params.TRAJECTORY_CHECKPOINTS)
        assertFalse("but the patient spent most of the rep out of position: $rep", rep.valid)
        assertEquals(RepRejection.SKIPPED, rep.rejection)
        assertTrue(rep.conformantFraction < Params.TRAJECTORY_MIN_REP_CONFORMANT_FRACTION)
        // ExerciseEngine names the joint only when this is non-zero, which is what makes the
        // refusal "you left the elbow out" rather than "you jumped to the end".
        assertTrue(rep.nonConformantFrames > 0)
    }

    @Test
    fun `the same hold performed in position counts`() {
        val rep = drive(rest() + repWithHold(peakPhi = 0.95, holdSeconds = 2.5) + rest()).single()

        assertTrue("a faithful rep with a hold must count: $rep", rep.valid)
        assertEquals(1.0, rep.conformantFraction, 1e-9)
    }

    @Test
    fun `a brief lapse in a long hold is not a skipped step`() {
        // The backstop is for a rep that was mostly wrong, not one with a wobble in it.
        var frame = 0
        val rep = drive(
            rest() + repWithHold(peakPhi = 0.95, holdSeconds = 2.5) + rest(),
            conformant = { frame++ % 5 != 0 },
        ).single()

        assertTrue("four frames in five is the movement: $rep", rep.valid)
        assertTrue(rep.conformantFraction < 1.0)
    }

    @Test
    fun `frames nobody could see are neither conformant nor not`() {
        // Two frames in five are lost to dropouts, and the engine would have called those frames
        // non-conformant if asked. They must not count either way: §6.6, the unseen part is the
        // part we cannot judge. The rep falls through to UNSEEN instead of being accused of a
        // fault nobody saw.
        var index = 0
        val stream = rest() + smoothRep().mapIndexed { i, value -> if (i % 5 == 1 || i % 5 == 3) null else value } + rest()
        val rep = drive(stream, conformant = { stream[index++] != null }).single()

        assertEquals("$rep", RepRejection.UNSEEN, rep.rejection)
        assertEquals(1.0, rep.conformantFraction, 1e-9)
    }

    @Test
    fun `the whole-rep bar sits beneath the bucket bar`() {
        // A rep whose every bucket cleared the bucket bar has at least that share overall. At or
        // under it, this gate cannot reject a rep that was right everywhere the checkpoints looked;
        // above it, it would start doing exactly that.
        assertTrue(Params.TRAJECTORY_MIN_REP_CONFORMANT_FRACTION > 0.0)
        assertTrue(
            Params.TRAJECTORY_MIN_REP_CONFORMANT_FRACTION <= Params.TRAJECTORY_MIN_BUCKET_CONFORMANT_FRACTION,
        )
    }
}
