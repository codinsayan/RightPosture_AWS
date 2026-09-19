package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.live.ExerciseEngine
import com.example.rightposture.core.live.ExerciseStatus
import com.example.rightposture.core.live.FrameResult
import com.example.rightposture.core.live.PhaseState
import com.example.rightposture.core.live.QualityBand
import com.example.rightposture.core.live.qualityBand
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.SyntheticPoseConfig
import com.example.rightposture.core.pose.TimedLandmarkFrame
import com.example.rightposture.core.pose.syntheticKneeRepetitions
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.bundleFromAuthoring
import com.example.rightposture.core.reference.runAuthoringPipeline
import com.example.rightposture.core.rpose.FaultCondition
import com.example.rightposture.core.rpose.FaultDefinition
import com.example.rightposture.core.rpose.FaultSet
import com.example.rightposture.core.rpose.RposeBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.1's reducer test. The engine is driven by the same synthetic knee generator that authored the
 * reference, so this runs 1.2 through 4.5 end to end on both halves of the app - the first test
 * where the authoring side and the live side have to agree about the same movement.
 */
class ExerciseEngineTest {

    private fun frames(config: SyntheticPoseConfig) = syntheticKneeRepetitions(config)

    /**
     * The 3.1 generator emits an unbroken sinusoid with no pause between reps. §6.12.3 completes a
     * rep on `φ < 0.15` **and** `|v| < V_STALL`, and on an unbroken 2 s cycle the velocity is inside
     * that stall band for well under one frame, so reps can pass undetected. Real reps have a beat
     * at the bottom, so the live stream is given one. See the finding in the v5.1 changelog entry.
     */
    private fun withBeat(
        stream: List<TimedLandmarkFrame>,
        everyFrames: Int,
        beatFrames: Int = 15,
        hz: Double = 30.0,
    ): List<TimedLandmarkFrame> {
        val expanded = mutableListOf<LandmarkFrame>()
        stream.forEachIndexed { index, timed ->
            expanded += timed.frame
            // A beat at **both** extrema. Which end of the generator's cycle corresponds to phase 0
            // depends on which extremum segmentation picked as the rep boundary, and pausing at only
            // one of them leaves the tracker stuck in ASCEND forever. A patient pauses at both ends
            // of a knee extension anyway.
            if ((index + 1) % (everyFrames / 2) == 0) repeat(beatFrames) { expanded += timed.frame }
        }
        val step = (1_000_000_000.0 / hz).toLong()
        return expanded.mapIndexed { index, frame -> TimedLandmarkFrame(index * step, frame) }
    }

    /** A patient repeating the movement they were taught, at a human tempo. */
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

    private fun bundle(
        authoringConfig: SyntheticPoseConfig = SyntheticPoseConfig(repCount = 8),
        prescribedReps: Int = 12,
    ): RposeBundle {
        val captured = frames(authoringConfig).map { CapturedFrame(it.frame, it.timestampNs) }
        val result = runAuthoringPipeline(captured)
        assertNull(result.failure, result.failure)
        return bundleFromAuthoring(
            result = result,
            name = "Knee extension",
            id = "rp_test",
            createdAt = "2026-09-04T00:00:00Z",
            prescribedReps = prescribedReps,
        )!!
    }

    /** Runs a stream through an engine and keeps every published state. */
    private fun run(
        engine: ExerciseEngine,
        stream: List<TimedLandmarkFrame>,
        startNs: Long = 0L,
    ): List<FrameResult> = stream.map { engine.onFrame(it.frame, startNs + it.timestampNs) }

    /** Blanks every confidence, which is what a person leaving the frame looks like to 1.6. */
    private fun blinded(frame: LandmarkFrame) = frame.copy(
        visibility = FloatArray(frame.visibility.size) { 0f }.toList(),
        presence = FloatArray(frame.presence.size) { 0f }.toList(),
    )

    // -------------------------------------------------------------------------------------
    // Good
    // -------------------------------------------------------------------------------------

    @Test
    fun `the same movement it learned scores well and counts reps`() {
        val engine = ExerciseEngine(bundle())
        val states = run(engine, patient(6))

        val last = states.last()
        assertTrue("expected reps, got ${last.validReps}", last.validReps >= 3)
        assertEquals(0, last.partialReps)

        val scored = states.mapNotNull { it.quality }
        assertTrue("expected scored frames", scored.isNotEmpty())
        val median = scored.sorted()[scored.size / 2]
        assertTrue("patient repeating the reference should score well, got $median", median > 80.0)
        assertNotNull(last.setQuality)
    }

    @Test
    fun `phase advances through the movement rather than sticking`() {
        val engine = ExerciseEngine(bundle())
        val states = run(engine, patient(4))
        val visited = states.map { it.phaseState }.toSet()
        assertTrue("expected to descend", PhaseState.DESCEND in visited)
        assertTrue("expected to ascend", PhaseState.ASCEND in visited)
        assertTrue(states.any { it.phase > 40.0 })
    }

    @Test
    fun `tracking status is reported while a rep is under way`() {
        val engine = ExerciseEngine(bundle())
        val states = run(engine, patient(4))
        assertTrue(states.any { it.status == ExerciseStatus.TRACKING })
        assertTrue(states.any { it.status == ExerciseStatus.READY })
    }

    // -------------------------------------------------------------------------------------
    // Partial
    // -------------------------------------------------------------------------------------

    @Test
    fun `half-range reps are counted as partial, not as good reps`() {
        // The failure §6.17.3 and §6.12.4 both single out: a patient doing tiny reps must not score
        // like one doing full reps, and must not have them counted.
        val engine = ExerciseEngine(bundle())
        val last = run(
            engine,
            patient(6, minimumKneeAngleDegrees = 130f, maximumKneeAngleDegrees = 170f),
        ).last()
        assertEquals("no short rep may count", 0, last.validReps)
        assertNull("a set of nothing but partials has no score", last.setQuality)
    }

    // -------------------------------------------------------------------------------------
    // Invalid
    // -------------------------------------------------------------------------------------

    @Test
    fun `an unseen person yields no score and no reps, never a zero`() {
        val engine = ExerciseEngine(bundle())
        val blank = patient(4).map { it.copy(frame = blinded(it.frame)) }
        val states = run(engine, blank)

        assertTrue(states.all { it.quality == null })
        assertEquals(0, states.last().validReps)
        assertTrue(states.all { it.status != ExerciseStatus.TRACKING })
    }

    @Test
    fun `losing the patient mid-set stops tracking and does not bank the rep`() {
        val engine = ExerciseEngine(bundle())
        val stream = patient(4)
        val cut = stream.size / 2
        val interrupted = stream.mapIndexed { index, timed ->
            if (index >= cut) timed.copy(frame = blinded(timed.frame)) else timed
        }
        val states = run(engine, interrupted)
        val repsBefore = states[cut - 1].validReps
        assertEquals(repsBefore, states.last().validReps)
        assertTrue(states.last().status != ExerciseStatus.TRACKING)
    }

    // -------------------------------------------------------------------------------------
    // Fault
    // -------------------------------------------------------------------------------------

    @Test
    fun `an active fault surfaces one cue at a time, no faster than the cooldown`() {
        val base = bundle()
        val watched = base.watchList.features.first().id
        // Both directions, and a threshold far below anything clinical. Phase is *derived from* the
        // dominant joint, so that joint sits near its own reference by construction and its z stays
        // small however wrongly the patient moves - faulting it at a realistic 3.0 would test
        // nothing here. 4.4 owns detection thresholds. What 5.1 owns is that a cue reaches the frame
        // state at all, and that §6.15's "speak exactly one" survives the trip through the engine.
        val faults = listOf("+", "-").map { sign ->
            FaultDefinition(
                id = "off_reference_" + (if (sign == "+") "high" else "low"),
                conditions = listOf(FaultCondition(watched, sign, 0.1)),
                phaseRange = 0 to 100,
                priority = 1.0,
                maskedBy = emptyList(),
                cueShort = "Straighten all the way.",
                cueFull = "Push your heel forward until your leg is fully straight.",
                cueAlt = "Reach your toes toward the far wall.",
                arrowJoint = "ankle",
                arrowAxis = "reference_position",
                generatedBy = "test",
                validated = false,
            )
        }
        val engine = ExerciseEngine(base.copy(faults = FaultSet(faults)))
        val states = run(engine, patient(6, minimumKneeAngleDegrees = 120f, maximumKneeAngleDegrees = 176f))

        val spokenAt = states.indices.filter { states[it].cue != null }
        assertTrue("a sustained deviation should be cued", spokenAt.isNotEmpty())
        assertEquals("Straighten all the way.", states[spokenAt.first()].cue!!.text)
        // §6.15.3 global cooldown is 3 s, which at 30 fps is 90 frames.
        spokenAt.zipWithNext().forEach { (first, second) ->
            assertTrue("cues $first and $second are too close together", second - first >= 90)
        }
    }

    @Test
    fun `no fault definitions means no cue, and scoring still works`() {
        val engine = ExerciseEngine(bundle())
        val states = run(engine, patient(4))
        assertTrue(states.all { it.cue == null })
        assertTrue(states.any { it.quality != null })
    }

    // -------------------------------------------------------------------------------------
    // Reset and bar semantics
    // -------------------------------------------------------------------------------------

    @Test
    fun `the set ends at the prescribed rep count instead of counting past it`() {
        val engine = ExerciseEngine(bundle(prescribedReps = 3))
        val states = run(engine, patient(10))

        val last = states.last()
        assertEquals(3, last.validReps)
        assertEquals(ExerciseStatus.SET_COMPLETE, last.status)
        assertTrue("the set should report itself complete", engine.isSetComplete)
        // Reps kept arriving after the third; none of them may be banked or cued.
        assertTrue(
            "no state may exceed the prescription",
            states.none { it.validReps > 3 },
        )
        assertTrue(
            "a completed set must not keep coaching",
            states.dropWhile { it.status != ExerciseStatus.SET_COMPLETE }.all { it.cue == null },
        )
    }

    @Test
    fun `reset clears the set`() {
        val engine = ExerciseEngine(bundle())
        run(engine, patient(6))
        assertTrue(engine.state.validReps > 0)
        engine.reset()
        assertEquals(FrameResult.EMPTY, engine.state)
    }

    @Test
    fun `the quality bar shows nothing rather than zero when there is no score`() {
        assertEquals(QualityBand.UNKNOWN, qualityBand(null))
        assertEquals(QualityBand.DANGER, qualityBand(0.0))
        assertEquals(QualityBand.DANGER, qualityBand(54.9))
        assertEquals(QualityBand.WARNING, qualityBand(55.0))
        assertEquals(QualityBand.WARNING, qualityBand(79.9))
        assertEquals(QualityBand.SUCCESS, qualityBand(80.0))
    }

    @Test
    fun `the bundle carries a usable phase definition`() {
        val manifest = bundle().manifest
        assertEquals(FeatureId.KNEE_FLEX_ACTIVE, manifest.segmentation.dominantFeature)
        assertTrue(manifest.phase.thetaPeak != manifest.phase.thetaStart)
        assertTrue("v_ref should be positive", manifest.phase.vRef > 0.0)
        assertTrue(manifest.phase.criticalWindow.first <= manifest.phase.criticalWindow.second)
    }
}
