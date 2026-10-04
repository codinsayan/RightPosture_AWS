package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.pose.SyntheticPoseConfig
import com.example.rightposture.core.pose.syntheticKneeRepetitions
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.CheckOutcome
import com.example.rightposture.core.reference.runAuthoringPipeline
import com.example.rightposture.core.reference.readyForAutoStop
import com.example.rightposture.core.reference.remainingAuthoringSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End to end over the whole authoring path. Every stage from 1.2 to 3.8 is unit tested in
 * isolation; this is the first test that runs them in sequence, which is where a mismatched
 * assumption between two stages would show up.
 */
class AuthoringPipelineTest {
    @Test
    fun eightCleanRepsProduceAnAcceptedReference() {
        val result = runAuthoringPipeline(capture(SyntheticPoseConfig(repCount = 8)))

        assertNull(result.failure, result.failure)
        assertEquals(FeatureId.KNEE_FLEX_ACTIVE, result.dominant)
        assertEquals(2.0, result.periodSeconds!!, 0.2)

        // Six, not eight. The 3.1 generator emits exactly N complete cycles with no lead-in, so
        // the first and last rest poses sit on the recording's edges, where they have no outside
        // neighbour and cannot be detected - the finding recorded in v3.4. Boundaries sit at rest
        // (SegmentationTest, 2026-10-03), so only the seven interior rests bound reps. This was
        // seven while boundaries went to whichever extremum was more numerous, i.e. end range.
        assertEquals(6, result.segmentation!!.detected.size)
        assertEquals(4, result.reference!!.repCount)
        assertTrue(result.validation!!.checks.toString(), result.validation!!.accepted)
    }

    @Test
    fun everyReferenceArrayFromTheRealPathIsFiniteAndOneHundredAndOneLong() {
        val result = runAuthoringPipeline(capture(SyntheticPoseConfig(repCount = 8)))

        result.reference!!.features.forEach { (id, feature) ->
            listOf(feature.mean, feature.sigma, feature.derivative).forEach { array ->
                assertEquals("$id", Params.PHASE_BINS, array.size)
                array.forEach { assertTrue("$id had $it", it.isFinite()) }
            }
        }
    }

    @Test
    fun theWatchListIsWeightedAndSumsToOne() {
        val watchList = runAuthoringPipeline(capture(SyntheticPoseConfig(repCount = 8))).watchList!!

        assertTrue(watchList.features.isNotEmpty())
        assertEquals(1.0, watchList.features.sumOf { it.weight }, 1e-9)
        assertTrue(watchList.features.any { it.id == FeatureId.KNEE_FLEX_ACTIVE })
    }

    @Test
    fun viewConsistencyIsMeasuredAndPasses() {
        val validation = runAuthoringPipeline(capture(SyntheticPoseConfig(repCount = 8))).validation!!

        assertTrue(validation.notEvaluated.isEmpty())
        assertEquals(CheckOutcome.PASS, validation.checks.first { it.number == 6 }.outcome)
    }

    @Test
    fun tooFewRepsIsRejectedWithAnActionableMessage() {
        val result = runAuthoringPipeline(capture(SyntheticPoseConfig(repCount = 4)))

        assertNotNull(result.failure)
        assertTrue(result.failure!!, result.failure!!.contains("reps"))
    }

    @Test
    fun aStationaryPersonIsRejectedRatherThanScored() {
        // One pose held for the whole recording. The generator will not emit a zero-amplitude
        // config, so the frame is repeated with advancing timestamps instead. The period estimator
        // must find nothing periodic and the pipeline must say so rather than inventing reps.
        val held = syntheticKneeRepetitions(SyntheticPoseConfig(repCount = 8)).first()
        val still = List(480) { index ->
            CapturedFrame(held.frame, held.timestampNs + index * 33_333_333L)
        }

        val result = runAuthoringPipeline(still)

        assertNotNull(result.failure)
        assertNull(result.reference)
    }

    @Test
    fun anEmptyRecordingFailsCleanly() {
        assertNotNull(runAuthoringPipeline(emptyList()).failure)
        assertNotNull(runAuthoringPipeline(capture(SyntheticPoseConfig()).take(1)).failure)
    }

    @Test
    fun noisyLandmarksStillProduceAnAcceptedReference() {
        // The Butterworth stage exists for exactly this. Without it the extremum detector would
        // split reps on noise.
        val result = runAuthoringPipeline(
            capture(
                SyntheticPoseConfig(repCount = 8, noiseStandardDeviationMetres = 0.004f, seed = 11),
            ),
        )

        assertNull(result.failure, result.failure)
        assertEquals(6, result.segmentation!!.detected.size)
        assertTrue(result.validation!!.checks.toString(), result.validation!!.accepted)
    }

    @Test
    fun droppedFramesDoNotCrashThePipeline() {
        // Dropout sets confidence to zero, so 1.6 validity marks those frames invalid. Features
        // with any gap are excluded rather than interpolated over.
        val result = runAuthoringPipeline(
            capture(SyntheticPoseConfig(repCount = 8, frameDropoutProbability = 0.05, seed = 3)),
        )

        // Either it produced a reference or it said why not. It must not throw or return nonsense.
        assertTrue(result.failure != null || result.reference != null)
    }

    @Test
    fun theTraceAndBoundariesAreAvailableForTheReviewChart() {
        val result = runAuthoringPipeline(capture(SyntheticPoseConfig(repCount = 8)))

        assertTrue(result.dominantTrace.isNotEmpty())
        assertTrue(result.dominantTrace.all { it.isFinite() })
        result.segmentation!!.usable.forEach {
            assertTrue(it.startIndex in result.dominantTrace.indices)
            assertTrue(it.endIndex in result.dominantTrace.indices)
        }
    }

    @Test
    fun autoStopRequiresAcceptanceNotOnlyEnoughReps() {
        val accepted = runAuthoringPipeline(capture(SyntheticPoseConfig(repCount = 8)))
        val enoughButTooShort = runAuthoringPipeline(capture(SyntheticPoseConfig(repCount = 7)))

        assertTrue(accepted.readyForAutoStop(durationSeconds = 16.0))
        assertTrue(!accepted.readyForAutoStop(durationSeconds = 14.9))
        assertTrue(!enoughButTooShort.readyForAutoStop(durationSeconds = 30.0))
        assertEquals(60.0, Params.AUTHORING_ACCEPTANCE_TIMEOUT_SECONDS, 0.0)
    }

    @Test
    fun countdownRoundsUpAndStopsAtZero() {
        assertEquals(60, remainingAuthoringSeconds(0.0))
        assertEquals(60, remainingAuthoringSeconds(0.1))
        assertEquals(59, remainingAuthoringSeconds(1.0))
        assertEquals(1, remainingAuthoringSeconds(59.1))
        assertEquals(0, remainingAuthoringSeconds(60.0))
        assertEquals(0, remainingAuthoringSeconds(61.0))
    }

    private fun capture(config: SyntheticPoseConfig) =
        syntheticKneeRepetitions(config).map { CapturedFrame(it.frame, it.timestampNs) }
}
