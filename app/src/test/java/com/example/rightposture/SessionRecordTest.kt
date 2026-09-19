package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.live.ExerciseEngine
import com.example.rightposture.core.live.FeatureAccumulator
import com.example.rightposture.core.live.FeatureSample
import com.example.rightposture.core.live.FeatureZ
import com.example.rightposture.core.live.RepRecord
import com.example.rightposture.core.live.RepRejection
import com.example.rightposture.core.live.SessionRecord
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.SyntheticPoseConfig
import com.example.rightposture.core.pose.TimedLandmarkFrame
import com.example.rightposture.core.pose.syntheticKneeRepetitions
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.bundleFromAuthoring
import com.example.rightposture.core.reference.runAuthoringPipeline
import com.example.rightposture.core.rpose.RposeBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6.1. The in-memory rep and session model §6.18 will read.
 *
 * The engine used to keep `(quality, valid)` per rep, which is enough to draw a counter and nothing
 * like enough for CUSUM: it needs the quality series in order, signed z per feature per rep for
 * attribution, and ROM and duration for the fatigue signature. All of those were computed every rep
 * and thrown away every rep. These tests are mostly about the two ways that aggregation can lie -
 * counting a rep that was never scored as a zero, and counting a partial rep as a decline.
 */
class SessionRecordTest {

    // -------------------------------------------------------------------------------------
    // Aggregation, on records built by hand so each case is exactly one thing
    // -------------------------------------------------------------------------------------

    private fun record(
        index: Int,
        quality: Double?,
        valid: Boolean,
        rejection: RepRejection? = null,
        features: Map<FeatureId, FeatureSample> = emptyMap(),
    ) = RepRecord(
        index = index,
        quality = quality,
        romDegrees = 90.0,
        durationSeconds = 2.0,
        phiMax = 1.0,
        valid = valid,
        rejection = rejection,
        features = features,
        velocityProfile = listOf(0.1, 0.2, 0.1),
    )

    private fun sample(feature: FeatureId, signed: Double, frames: Int = 10) = FeatureSample(
        feature = feature,
        meanZ = kotlin.math.abs(signed),
        maxZ = kotlin.math.abs(signed) + 1.0,
        meanSignedZ = signed,
        frames = frames,
    )

    @Test
    fun `valid, partial and unscored reps are counted as three different things`() {
        val session = SessionRecord(
            reps = listOf(
                record(1, 90.0, valid = true),
                record(2, 40.0, valid = false, rejection = RepRejection.RANGE),
                record(3, null, valid = false, rejection = RepRejection.UNSCORED),
            ),
            prescribedReps = 12,
        )

        assertEquals(1, session.validReps)
        assertEquals(2, session.partialReps)
        assertEquals(1, session.unscoredReps)
    }

    @Test
    fun `an unscored rep is absent from the quality series, not a zero in it`() {
        // The trap this whole model exists to avoid. A zero here reads to §6.18.3 as a collapse at
        // exactly the moment the app declined to measure anything.
        val session = SessionRecord(
            reps = listOf(
                record(1, 90.0, valid = true),
                record(2, null, valid = false, rejection = RepRejection.UNSCORED),
                record(3, 88.0, valid = true),
            ),
            prescribedReps = 12,
        )

        assertEquals(listOf(90.0, 88.0), session.validQualitySeries)
        assertFalse("no zero may appear", session.validQualitySeries.contains(0.0))
    }

    @Test
    fun `a partial rep is excluded from the series rather than dragging it down`() {
        // §6.12.4 already decided a partial is not a rep. Including its score would make a patient
        // who stopped short look like a patient who is deteriorating.
        val session = SessionRecord(
            reps = listOf(
                record(1, 90.0, valid = true),
                record(2, 20.0, valid = false, rejection = RepRejection.RANGE),
                record(3, 89.0, valid = true),
            ),
            prescribedReps = 12,
        )

        assertEquals(listOf(90.0, 89.0), session.validQualitySeries)
    }

    @Test
    fun `the quality series keeps rep order, which is what CUSUM indexes`() {
        val session = SessionRecord(
            reps = (1..5).map { record(it, 100.0 - it, valid = true) },
            prescribedReps = 12,
        )

        assertEquals(listOf(99.0, 98.0, 97.0, 96.0, 95.0), session.validQualitySeries)
    }

    @Test
    fun `set quality is the median over valid reps only`() {
        val session = SessionRecord(
            reps = listOf(
                record(1, 60.0, valid = true),
                record(2, 80.0, valid = true),
                record(3, 100.0, valid = true),
                record(4, 5.0, valid = false, rejection = RepRejection.RANGE),
                record(5, null, valid = false, rejection = RepRejection.UNSCORED),
            ),
            prescribedReps = 12,
        )

        assertEquals(80.0, session.setQuality!!, 1e-9)
    }

    @Test
    fun `an empty set has no quality, rather than a quality of zero`() {
        assertNull(SessionRecord.EMPTY.setQuality)
        assertNull(SessionRecord(listOf(record(1, null, valid = false)), 12).setQuality)
    }

    @Test
    fun `completion counts valid reps against the prescription`() {
        val session = SessionRecord(
            reps = (1..6).map { record(it, 90.0, valid = it <= 3) },
            prescribedReps = 12,
        )

        assertEquals(0.25, session.completionRate, 1e-9)
    }

    // -------------------------------------------------------------------------------------
    // §6.18.1's prerequisite
    // -------------------------------------------------------------------------------------

    @Test
    fun `six valid reps is the boundary for assessing fatigue`() {
        val five = SessionRecord((1..5).map { record(it, 90.0, valid = true) }, 12)
        val six = SessionRecord((1..6).map { record(it, 90.0, valid = true) }, 12)

        assertFalse("five is not enough to claim a change point", five.hasEnoughRepsForFatigue)
        assertTrue(six.hasEnoughRepsForFatigue)
        assertEquals(6, SessionRecord.MIN_REPS_FOR_FATIGUE)
    }

    @Test
    fun `partial reps do not make up the fatigue quorum`() {
        // Six finished reps of which two were refused is four measurements, and §6.18.1's floor is
        // about how much evidence there is, not how many times the patient moved.
        val session = SessionRecord(
            reps = (1..6).map { record(it, 90.0, valid = it <= 4) },
            prescribedReps = 12,
        )

        assertFalse(session.hasEnoughRepsForFatigue)
    }

    // -------------------------------------------------------------------------------------
    // §6.18.5 attribution inputs
    // -------------------------------------------------------------------------------------

    @Test
    fun `signed z keeps its direction, because the two directions are different findings`() {
        val session = SessionRecord(
            reps = listOf(
                record(1, 90.0, true, features = mapOf(FeatureId.TRUNK_LATERAL to sample(FeatureId.TRUNK_LATERAL, -2.0))),
                record(2, 90.0, true, features = mapOf(FeatureId.TRUNK_LATERAL to sample(FeatureId.TRUNK_LATERAL, -2.0))),
            ),
            prescribedReps = 12,
        )

        assertEquals(-2.0, session.meanSignedZ(FeatureId.TRUNK_LATERAL)!!, 1e-9)
    }

    @Test
    fun `a mean over three frames does not outweigh a mean over ninety`() {
        val session = SessionRecord(
            reps = listOf(
                record(1, 90.0, true, features = mapOf(FeatureId.TRUNK_LATERAL to sample(FeatureId.TRUNK_LATERAL, 1.0, frames = 90))),
                record(2, 90.0, true, features = mapOf(FeatureId.TRUNK_LATERAL to sample(FeatureId.TRUNK_LATERAL, 5.0, frames = 10))),
            ),
            prescribedReps = 12,
        )

        // (1.0*90 + 5.0*10) / 100
        assertEquals(1.4, session.meanSignedZ(FeatureId.TRUNK_LATERAL)!!, 1e-9)
    }

    @Test
    fun `attribution can be taken either side of a change point`() {
        // §6.18.5's e_pre and e_post are the same statistic over two rep ranges, which is the only
        // reason this takes a range at all.
        val session = SessionRecord(
            reps = (1..6).map {
                record(it, 90.0, true, features = mapOf(FeatureId.TRUNK_LATERAL to sample(FeatureId.TRUNK_LATERAL, if (it < 4) 0.5 else 3.0)))
            },
            prescribedReps = 12,
        )

        assertEquals(0.5, session.meanSignedZ(FeatureId.TRUNK_LATERAL, 1..3)!!, 1e-9)
        assertEquals(3.0, session.meanSignedZ(FeatureId.TRUNK_LATERAL, 4..6)!!, 1e-9)
    }

    @Test
    fun `a feature nothing scored has no mean, rather than a mean of zero`() {
        val session = SessionRecord(listOf(record(1, 90.0, valid = true)), 12)

        assertNull(session.meanSignedZ(FeatureId.TRUNK_LATERAL))
    }

    @Test
    fun `only valid reps contribute to attribution`() {
        val session = SessionRecord(
            reps = listOf(
                record(1, 90.0, true, features = mapOf(FeatureId.TRUNK_LATERAL to sample(FeatureId.TRUNK_LATERAL, 1.0))),
                record(2, 10.0, false, RepRejection.RANGE, mapOf(FeatureId.TRUNK_LATERAL to sample(FeatureId.TRUNK_LATERAL, 9.0))),
            ),
            prescribedReps = 12,
        )

        assertEquals(1.0, session.meanSignedZ(FeatureId.TRUNK_LATERAL)!!, 1e-9)
        assertEquals(setOf(FeatureId.TRUNK_LATERAL), session.scoredFeatures())
    }

    // -------------------------------------------------------------------------------------
    // The per-rep accumulator
    // -------------------------------------------------------------------------------------

    private fun z(feature: FeatureId, value: Double) =
        mapOf(feature to FeatureZ(feature, value, 0.0, 0.0, 1.0))

    @Test
    fun `the accumulator separates magnitude from direction`() {
        val accumulator = FeatureAccumulator()
        accumulator.add(z(FeatureId.TRUNK_LATERAL, 2.0))
        accumulator.add(z(FeatureId.TRUNK_LATERAL, -4.0))

        val sample = accumulator.samples().getValue(FeatureId.TRUNK_LATERAL)
        assertEquals("mean |z|", 3.0, sample.meanZ, 1e-9)
        assertEquals("mean z, which cancels", -1.0, sample.meanSignedZ, 1e-9)
        assertEquals("largest excursion", 4.0, sample.maxZ, 1e-9)
        assertEquals(2, sample.frames)
    }

    @Test
    fun `a non-finite z is a failed measurement, not a large one`() {
        val accumulator = FeatureAccumulator()
        accumulator.add(z(FeatureId.TRUNK_LATERAL, 2.0))
        accumulator.add(z(FeatureId.TRUNK_LATERAL, Double.NaN))
        accumulator.add(z(FeatureId.TRUNK_LATERAL, Double.POSITIVE_INFINITY))

        val sample = accumulator.samples().getValue(FeatureId.TRUNK_LATERAL)
        assertEquals(2.0, sample.meanSignedZ, 1e-9)
        assertEquals("only the usable frame counts", 1, sample.frames)
    }

    @Test
    fun `clearing the accumulator starts the next rep clean`() {
        val accumulator = FeatureAccumulator()
        accumulator.add(z(FeatureId.TRUNK_LATERAL, 5.0))
        accumulator.clear()

        assertTrue(accumulator.samples().isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // The engine actually producing it - 6.1's gate, "output contains every CUSUM prerequisite"
    // -------------------------------------------------------------------------------------

    private fun frames(config: SyntheticPoseConfig) = syntheticKneeRepetitions(config)

    private fun withBeat(stream: List<TimedLandmarkFrame>, everyFrames: Int): List<TimedLandmarkFrame> {
        val expanded = mutableListOf<LandmarkFrame>()
        stream.forEachIndexed { index, timed ->
            expanded += timed.frame
            if ((index + 1) % (everyFrames / 2) == 0) repeat(15) { expanded += timed.frame }
        }
        val step = (1_000_000_000.0 / 30.0).toLong()
        return expanded.mapIndexed { index, frame -> TimedLandmarkFrame(index * step, frame) }
    }

    private fun patient(repCount: Int): List<TimedLandmarkFrame> {
        val config = SyntheticPoseConfig(repCount = repCount)
        return withBeat(frames(config), config.samplesPerRep)
    }

    private fun bundle(): RposeBundle {
        val captured = frames(SyntheticPoseConfig(repCount = 8))
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

    private fun runSet(engine: ExerciseEngine, stream: List<TimedLandmarkFrame>) =
        stream.forEach { engine.onFrame(it.frame, it.timestampNs) }

    @Test
    fun `a real set produces every prerequisite CUSUM needs`() {
        val engine = ExerciseEngine(bundle())
        runSet(engine, patient(8))
        val session = engine.session

        assertTrue("expected reps: ${session.reps.size}", session.validReps >= 3)
        session.reps.filter { it.valid }.forEach { rep ->
            assertTrue("Q_i", rep.quality != null)
            assertTrue("ROM_i must be a real angle: $rep", rep.romDegrees > 0.0)
            assertTrue("T_i must be a real duration: $rep", rep.durationSeconds > 0.0)
            assertTrue("signed z per feature: $rep", rep.features.isNotEmpty())
            assertTrue("velocity profile for the smoothness term", rep.velocityProfile.isNotEmpty())
            rep.features.values.forEach { assertTrue("frames counted", it.frames > 0) }
        }
        assertTrue("the attribution loop needs features to iterate", session.scoredFeatures().isNotEmpty())
    }

    @Test
    fun `rep indices are one-based and contiguous, so a change point names a real rep`() {
        val engine = ExerciseEngine(bundle())
        runSet(engine, patient(8))

        assertEquals(engine.session.reps.indices.map { it + 1 }, engine.session.reps.map { it.index })
    }

    @Test
    fun `losing the person keeps the set recorded so far`() {
        // §13.1 persists a row per rep as it completes, precisely so an interrupted set is still
        // readable. The in-memory half has to behave the same way.
        val engine = ExerciseEngine(bundle())
        runSet(engine, patient(6))
        val before = engine.session.reps.size
        assertTrue(before > 0)

        engine.onPersonLost()

        assertEquals("reps already banked survive", before, engine.session.reps.size)
    }

    @Test
    fun `reset starts a genuinely new set`() {
        val engine = ExerciseEngine(bundle())
        runSet(engine, patient(6))
        assertTrue(engine.session.reps.isNotEmpty())

        engine.reset()

        assertEquals(0, engine.session.reps.size)
        assertNull(engine.session.setQuality)
        assertFalse(engine.session.hasEnoughRepsForFatigue)
    }

    @Test
    fun `the session agrees with the frame result the UI is drawing`() {
        // Two views of the same set. If they can disagree, one of them is lying to somebody.
        val engine = ExerciseEngine(bundle())
        runSet(engine, patient(8))

        assertEquals(engine.state.validReps, engine.session.validReps)
        assertEquals(engine.state.partialReps, engine.session.partialReps)
        assertEquals(engine.state.setQuality, engine.session.setQuality)
    }
}
