package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.live.EnglishPhrases
import com.example.rightposture.core.live.ExerciseEngine
import com.example.rightposture.core.live.FrameResult
import com.example.rightposture.core.live.HindiPhrases
import com.example.rightposture.core.live.RepNote
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.SyntheticPoseConfig
import com.example.rightposture.core.pose.TimedLandmarkFrame
import com.example.rightposture.core.pose.syntheticKneeRepetitions
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.bundleFromAuthoring
import com.example.rightposture.core.reference.runAuthoringPipeline
import com.example.rightposture.core.rpose.RposeBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rep that **counts** and is still not the movement that was prescribed.
 *
 * §6.12.4 only ever decides whether a rep is a rep. Everything here is about the gap that leaves:
 * a patient who swings past the reference clears acceptance, gets counted, and - until this - was
 * told nothing, which teaches them the overshoot was correct.
 */
class RepNoteTest {

    private fun frames(config: SyntheticPoseConfig) = syntheticKneeRepetitions(config)

    private fun withBeat(
        stream: List<TimedLandmarkFrame>,
        everyFrames: Int,
        beatFrames: Int = 15,
        hz: Double = 30.0,
    ): List<TimedLandmarkFrame> {
        val expanded = mutableListOf<LandmarkFrame>()
        stream.forEachIndexed { index, timed ->
            expanded += timed.frame
            if ((index + 1) % (everyFrames / 2) == 0) repeat(beatFrames) { expanded += timed.frame }
        }
        val step = (1_000_000_000.0 / hz).toLong()
        return expanded.mapIndexed { index, frame -> TimedLandmarkFrame(index * step, frame) }
    }

    private fun patient(
        repCount: Int,
        minimumKneeAngleDegrees: Float = 80f,
        maximumKneeAngleDegrees: Float = 170f,
    ): List<TimedLandmarkFrame> {
        val config = SyntheticPoseConfig(
            repCount = repCount,
            minimumKneeAngleDegrees = minimumKneeAngleDegrees,
            maximumKneeAngleDegrees = maximumKneeAngleDegrees,
        )
        return withBeat(frames(config), config.samplesPerRep)
    }

    private fun bundle(maximumKneeAngleDegrees: Float = 170f): RposeBundle {
        val captured = frames(SyntheticPoseConfig(repCount = 8, maximumKneeAngleDegrees = maximumKneeAngleDegrees))
            .map { CapturedFrame(it.frame, it.timestampNs) }
        val result = runAuthoringPipeline(captured)
        assertNull(result.failure, result.failure)
        return bundleFromAuthoring(
            result = result,
            name = "Knee extension",
            id = "rp_test",
            createdAt = "2026-09-04T00:00:00Z",
            prescribedReps = 12,
        )!!
    }

    private fun run(engine: ExerciseEngine, stream: List<TimedLandmarkFrame>): List<FrameResult> =
        stream.map { engine.onFrame(it.frame, it.timestampNs) }

    // -------------------------------------------------------------------------------------

    @Test
    fun `a patient repeating the reference is not nagged`() {
        val engine = ExerciseEngine(bundle())
        val states = run(engine, patient(6))

        assertTrue("expected reps", states.last().validReps >= 3)
        // Nothing to say about a rep that was the movement. A note on every rep would be noise, and
        // noise is how a patient learns to ignore the app.
        assertTrue(
            "a faithful rep should carry no note",
            states.none { it.completedRep?.valid == true && it.lastRepNote == RepNote.Overshot },
        )
    }

    @Test
    fun `going past the reference counts the rep and says so`() {
        // The exact case reported on device: the patient swings well beyond what was recorded, the
        // rep satisfies §6.12.4, and it was previously counted in silence. Past the *end* of the
        // movement: the physio extended to 150 degrees, the patient goes all the way to 180. (This
        // test once bent the knee past the start instead, which read as overshoot only while
        // segmentation bounded reps at end range - see SegmentationTest, 2026-10-03.)
        val engine = ExerciseEngine(bundle(maximumKneeAngleDegrees = 150f))
        val states = run(engine, patient(6, maximumKneeAngleDegrees = 180f))

        val counted = states.mapNotNull { it.completedRep }.filter { it.valid }
        assertTrue("the overshooting rep must still count", counted.isNotEmpty())

        val notes = states.mapNotNull { it.lastRepNote }
        assertTrue("expected an overshoot note, got $notes", notes.contains(RepNote.Overshot))
    }

    @Test
    fun `the note survives long enough for a 30 Hz UI to read it`() {
        // Same trap as the partial-rep reason in v5.1.1: `completedRep` lives one frame.
        val engine = ExerciseEngine(bundle(maximumKneeAngleDegrees = 150f))
        val states = run(engine, patient(6, maximumKneeAngleDegrees = 180f))

        val firstNoteIndex = states.indexOfFirst { it.lastRepNote != null }
        assertTrue("expected a note at all", firstNoteIndex >= 0)
        assertNotNull(states[firstNoteIndex + 5].lastRepNote)
    }

    @Test
    fun `a rejected rep gets its rejection, not a note`() {
        val engine = ExerciseEngine(bundle())
        // Half range: rejected by §6.12.4 rather than counted.
        val states = run(engine, patient(6, minimumKneeAngleDegrees = 130f))

        states.forEach {
            val completed = it.completedRep ?: return@forEach
            if (!completed.valid) assertNull("a rejected rep has a reason, not a note", it.lastRepNote)
        }
    }

    // -------------------------------------------------------------------------------------
    // Wording
    // -------------------------------------------------------------------------------------

    @Test
    fun `every note has wording in both languages`() {
        val notes = listOf(
            RepNote.Overshot,
            RepNote.ShortOfRange,
            RepNote.Compensation(FeatureId.TRUNK_LATERAL),
        )
        notes.forEach {
            assertTrue(EnglishPhrases.repNote(it).isNotBlank())
            assertTrue(HindiPhrases.repNote(it).isNotBlank())
        }
    }

    @Test
    fun `a note says the rep counted, so it is not mistaken for a rejection`() {
        assertTrue(EnglishPhrases.repNote(RepNote.Overshot).contains("counted"))
        assertTrue(HindiPhrases.repNote(RepNote.Overshot).contains("गिना गया"))
    }

    @Test
    fun `every joint is named in a way a patient would recognise`() {
        FeatureId.entries.forEach {
            val english = EnglishPhrases.featureName(it)
            assertTrue(it.value, english.isNotBlank())
            // The raw feature id is a table key, not a word anyone says out loud.
            assertTrue(it.value, !english.contains("_"))
            assertTrue(it.value, HindiPhrases.featureName(it).isNotBlank())
        }
    }

    @Test
    fun `a compensation note names the joint, not the feature id`() {
        val note = RepNote.Compensation(FeatureId.TRUNK_LATERAL)
        assertEquals(true, EnglishPhrases.repNote(note).contains("torso"))
        assertEquals(false, EnglishPhrases.repNote(note).contains("trunk_lateral"))
    }
}
