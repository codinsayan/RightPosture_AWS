package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.live.CompletedRep
import com.example.rightposture.core.live.EnglishPhrases
import com.example.rightposture.core.live.ExerciseEngine
import com.example.rightposture.core.live.FrameResult
import com.example.rightposture.core.live.HindiPhrases
import com.example.rightposture.core.live.PhaseConfig
import com.example.rightposture.core.live.PhaseTracker
import com.example.rightposture.core.live.RepRejection
import com.example.rightposture.core.pose.TimedLandmarkFrame
import com.example.rightposture.core.pose.syntheticEndPoseDash
import com.example.rightposture.core.pose.syntheticTwoStepRepetitions
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.bundleFromAuthoring
import com.example.rightposture.core.reference.runAuthoringPipeline
import com.example.rightposture.core.rpose.RposeBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/**
 * The hole [TrajectoryTest] left open, reported from a device: *"I can just skip the whole exercise
 * and just do the final pose, and it will register that as a rep."*
 *
 * Everything §6.12.4 accepts a rep on - range, duration, checkpoints, rate, valid fraction - is a
 * function of `φ`, and `φ` is one joint: `segmentation.dominantFeature`, chosen at authoring as the
 * single largest swing. An exercise built from a combination of steps is not one joint, so the
 * patient who raises the arm and never bends the elbow moved the dominant joint through every
 * checkpoint at a legal speed, and was counted.
 *
 * A checkpoint is now only credited when the **pose** was right there, not just the dominant angle.
 */
class PoseConformanceTest {

    // -------------------------------------------------------------------------------------
    // The tracker, in isolation: conformance is what credits a checkpoint
    // -------------------------------------------------------------------------------------

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

    private fun smoothRep(durationSeconds: Double = referencePeriod): List<Float?> {
        val frames = (durationSeconds * fps).toInt()
        return (0 until frames).map {
            val u = it.toDouble() / frames
            (start + span * (0.5 - 0.5 * cos(2 * PI * u))).toFloat()
        }
    }

    @Test
    fun `a rep whose pose was never right credits no checkpoint`() {
        val rep = drive(rest() + smoothRep() + rest(), conformant = { false }).single()

        assertFalse("a movement performed in the wrong pose is not the movement", rep.valid)
        assertEquals(RepRejection.SKIPPED, rep.rejection)
        assertEquals(0, rep.checkpointsVisited)
        assertTrue("the rep must report that a joint was off", rep.nonConformantFrames > 0)
    }

    @Test
    fun `a step omitted in the middle of the range loses that checkpoint`() {
        // The patient is in the right pose at the ends and wrong through the middle - which is what
        // omitting an intermediate step looks like on a joint that is not the dominant one.
        val rep = drive(rest() + smoothRep() + rest(), conformant = { it < 0.25 || it > 0.75 }).single()

        assertFalse("the middle of the movement was not performed: $rep", rep.valid)
        assertEquals(RepRejection.SKIPPED, rep.rejection)
    }

    @Test
    fun `the movement performed in the right pose still counts`() {
        val rep = drive(rest() + smoothRep() + rest(), conformant = { true }).single()

        assertTrue("a faithful rep must count: $rep", rep.valid)
        assertEquals(0, rep.nonConformantFrames)
    }

    @Test
    fun `one bad frame in a bucket does not lose the rep`() {
        // Landmark jitter puts a single frame outside tolerance. A bucket stays credited once any
        // frame in it was conformant, precisely so that noise cannot cost a patient a good rep.
        var frame = 0
        val rep = drive(rest() + smoothRep() + rest(), conformant = { frame++ % 17 != 0 }).single()

        assertTrue("one jittering frame is not a skipped step: $rep", rep.valid)
    }

    @Test
    fun `conformance defaults to true, so a single-joint exercise is unaffected`() {
        // There is no second joint to disagree with when the watch list is the dominant feature
        // alone, and such an exercise must behave exactly as it did before this existed.
        val tracker = PhaseTracker(PhaseConfig(start, peak, referencePeriod))
        val reps = mutableListOf<CompletedRep>()
        var timestampNs = 0L
        (rest() + smoothRep() + rest()).forEach {
            tracker.update(it, timestampNs).completedRep?.let { rep -> reps += rep }
            timestampNs += (1_000_000_000.0 / fps).toLong()
        }

        assertTrue("the default must not reject anything: ${reps.single()}", reps.single().valid)
    }

    // -------------------------------------------------------------------------------------
    // The whole engine, against a two-step exercise
    // -------------------------------------------------------------------------------------

    /** The physio's movement: the knee sweeps **and** the elbow bends with it. */
    private fun bundle(): RposeBundle {
        val captured = syntheticTwoStepRepetitions(repCount = 8)
            .map { CapturedFrame(it.frame, it.timestampNs) }
        val result = runAuthoringPipeline(captured)
        assertNull(result.failure, result.failure)
        return bundleFromAuthoring(
            result = result,
            name = "Two step",
            id = "rp_two_step",
            createdAt = "2026-09-04T00:00:00Z",
            prescribedReps = 12,
        )!!
    }

    private fun run(bundle: RposeBundle, stream: List<TimedLandmarkFrame>): List<FrameResult> {
        val engine = ExerciseEngine(bundle)
        return stream.map { engine.onFrame(it.frame, it.timestampNs) }
    }

    @Test
    fun `the exercise is authored as two joints, or this test proves nothing`() {
        val bundle = bundle()
        val watched = bundle.watchList.features.map { it.id }

        assertEquals(FeatureId.KNEE_FLEX_ACTIVE, bundle.manifest.segmentation.dominantFeature)
        assertTrue("the elbow must be watched: $watched", watched.contains(FeatureId.ELBOW_FLEX_ACTIVE))
    }

    @Test
    fun `performing both steps counts every rep`() {
        val states = run(bundle(), syntheticTwoStepRepetitions(repCount = 4))

        assertTrue("the physio's own movement must count: ${states.last().validReps}", states.last().validReps >= 2)
    }

    @Test
    fun `omitting the second step counts nothing`() {
        // The reported bug. The knee sweeps exactly as recorded - full range, legal duration, every
        // checkpoint, legal rate - and the elbow never moves. Before this it counted every rep.
        val states = run(bundle(), syntheticTwoStepRepetitions(repCount = 4, elbowSwingDegrees = 0f))

        assertEquals("the omitted step must not be counted", 0, states.last().validReps)
        assertTrue("and the patient must be told", states.last().partialReps > 0)
        assertEquals(RepRejection.SKIPPED, states.last().lastRejection)
    }

    @Test
    fun `going straight to the end pose and holding counts nothing`() {
        // A realistic dash, not a teleport: 0.4 s to the end pose, 1.5 s hold, 0.4 s back. It clears
        // range, duration, every checkpoint and the rate bound, which is why it used to count.
        val states = run(bundle(), syntheticEndPoseDash(repCount = 4))

        assertEquals("the shortcut must not be counted", 0, states.last().validReps)
        assertTrue(states.last().partialReps > 0)
    }

    @Test
    fun `the refusal names the joint that was left out`() {
        // "Follow the whole movement" is no help to a patient who believes they did: they moved the
        // joint they were watching. Only naming the forgotten one is actionable.
        val states = run(bundle(), syntheticTwoStepRepetitions(repCount = 4, elbowSwingDegrees = 0f))

        assertEquals(FeatureId.ELBOW_FLEX_ACTIVE, states.last().skippedFeature)
        assertTrue(EnglishPhrases.skippedFeature(FeatureId.ELBOW_FLEX_ACTIVE).contains("elbow"))
    }

    @Test
    fun `a rep refused for range is not blamed on a joint`() {
        // The joint is only named when a joint is what went wrong. Attaching it to every refusal
        // would send the patient to fix something that was already correct.
        val states = run(bundle(), syntheticTwoStepRepetitions(repCount = 4, kneeSwingDegrees = 40f, elbowSwingDegrees = 30f))

        states.forEach {
            if (it.lastRejection != null && it.lastRejection != RepRejection.SKIPPED) {
                assertNull("only a skipped rep names a joint", it.skippedFeature)
            }
        }
    }

    @Test
    fun `naming the omitted joint has wording in both languages`() {
        FeatureId.entries.forEach {
            assertTrue(it.value, EnglishPhrases.skippedFeature(it).isNotBlank())
            assertTrue(it.value, HindiPhrases.skippedFeature(it).isNotBlank())
            // The raw feature id is a table key, not a word anyone says out loud.
            assertFalse(it.value, EnglishPhrases.skippedFeature(it).contains("_"))
        }
    }

    @Test
    fun `the threshold is wide enough for a patient and far too tight for an omitted step`() {
        // Stated as a test rather than left to the comment in Params: the whole design rests on
        // there being a gap between "performing the step imperfectly" and "not performing it".
        assertTrue(Params.TRAJECTORY_CONFORMANCE_Z >= 2.0)
        assertTrue(Params.TRAJECTORY_CONFORMANCE_Z <= Params.Z_CLAMP)
    }

    // -------------------------------------------------------------------------------------

    @Test
    fun `every completed rep reports the rate it was judged on`() {
        // maxPhiRatePerSecond was read after clearRep() zeroed it, so every rep reported 0.0 - and
        // the two tests asserting it stayed under the bound passed against zero.
        val rep = drive(rest() + smoothRep() + rest()).single()

        assertTrue("a moving rep cannot have a zero peak rate: $rep", rep.maxPhiRatePerSecond > 0.0)
        assertNotNull(rep.rejection ?: "valid")
    }
}
