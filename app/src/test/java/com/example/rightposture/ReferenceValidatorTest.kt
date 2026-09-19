package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.reference.BodyPosition
import com.example.rightposture.core.reference.CheckOutcome
import com.example.rightposture.core.reference.FeatureReference
import com.example.rightposture.core.reference.RecordingSummary
import com.example.rightposture.core.reference.Reference
import com.example.rightposture.core.reference.WatchList
import com.example.rightposture.core.reference.WatchedFeature
import com.example.rightposture.core.reference.durationDispersion
import com.example.rightposture.core.reference.validateReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceValidatorTest {
    private val dominant = FeatureId.KNEE_FLEX_ACTIVE

    @Test
    fun aGoodRecordingPassesEveryCheckThatCanRun() {
        val result = validate()

        assertTrue(result.firstFailure?.message ?: "", result.accepted)
        assertEquals(8, result.checks.size)
        assertEquals(
            (1..8).toList(),
            result.checks.map { it.number },
        )
    }

    // --- One focused case per rule ---

    @Test
    fun check1_tooFewRepsIsRejectedWithTheCount() {
        val result = validate(reference = referenceOf(repCount = 3))

        val failure = result.firstFailure!!
        assertEquals(1, failure.number)
        assertTrue(failure.message, failure.message.contains("3 clean reps"))
        assertTrue(failure.message, failure.message.contains("Record 8 reps"))
        assertFalse(result.accepted)
    }

    @Test
    fun check2_tooLittleMovementOffersHoldMode() {
        val result = validate(
            reference = referenceOf(features = mapOf(dominant to feature(rom = 14.9, medianSigma = 3.0))),
        )

        val failure = result.checks.first { it.number == 2 }
        assertEquals(CheckOutcome.FAIL, failure.outcome)
        assertTrue(failure.message, failure.message.contains("Hold mode"))
    }

    @Test
    fun check2_passesAtExactlyFifteenDegrees() {
        val result = validate(
            reference = referenceOf(features = mapOf(dominant to feature(rom = 15.0, medianSigma = 3.0))),
        )

        assertEquals(CheckOutcome.PASS, result.checks.first { it.number == 2 }.outcome)
    }

    @Test
    fun check3_raggedTempoIsRejected() {
        val ragged = listOf(1.0, 2.4, 1.1, 3.0, 1.2, 2.8)

        val result = validate(summary = summaryOf(repDurations = ragged))

        val failure = result.checks.first { it.number == 3 }
        assertEquals(CheckOutcome.FAIL, failure.outcome)
        assertTrue(failure.message, failure.message.contains("steady rhythm"))
    }

    @Test
    fun check3_steadyTempoPasses() {
        val steady = listOf(2.0, 2.05, 1.95, 2.0, 2.1, 1.9)

        val result = validate(summary = summaryOf(repDurations = steady))

        assertEquals(CheckOutcome.PASS, result.checks.first { it.number == 3 }.outcome)
    }

    @Test
    fun check3_usesRawMadOverMedianAtTheQuarterBoundary() {
        val atLimit = listOf(1.0, 1.5, 2.0, 2.5, 3.0)
        val aboveLimit = listOf(0.9, 1.4, 2.0, 2.6, 3.1)

        assertEquals(0.25, durationDispersion(atLimit)!!, 1e-9)
        assertEquals(
            CheckOutcome.PASS,
            validate(summary = summaryOf(repDurations = atLimit)).checks.first { it.number == 3 }.outcome,
        )
        assertEquals(
            CheckOutcome.FAIL,
            validate(summary = summaryOf(repDurations = aboveLimit)).checks.first { it.number == 3 }.outcome,
        )
    }

    @Test
    fun check4_needsTwoTightlyControlledFeatures() {
        val onlyOneTight = referenceOf(
            features = mapOf(
                dominant to feature(rom = 60.0, medianSigma = 4.0),
                FeatureId.HIP_FLEX_ACTIVE to feature(rom = 60.0, medianSigma = 13.0),
            ),
        )

        val failure = validate(reference = onlyOneTight).checks.first { it.number == 4 }

        assertEquals(CheckOutcome.FAIL, failure.outcome)
        assertTrue(failure.message, failure.message.contains("varied too much"))
        assertTrue(failure.detail, failure.detail.contains("1 feature"))
    }

    @Test
    fun check5_namesTheJointItCouldNotSee() {
        val result = validate(
            summary = summaryOf(visibility = mapOf(FeatureId.HIP_FLEX_ACTIVE to 0.4f)),
        )

        val failure = result.checks.first { it.number == 5 }
        assertEquals(CheckOutcome.FAIL, failure.outcome)
        assertTrue(failure.message, failure.message.contains("hip flex active"))
        assertTrue(failure.message, failure.message.contains("Move the camera back"))
    }

    @Test
    fun check5_onlyLooksAtWatchedJoints() {
        // An unwatched joint being invisible is not a reason to reject the recording.
        val result = validate(
            summary = summaryOf(visibility = mapOf(FeatureId.ANKLE_DORSIFLEX_ACTIVE to 0.1f)),
        )

        assertEquals(CheckOutcome.PASS, result.checks.first { it.number == 5 }.outcome)
    }

    @Test
    fun check6_missingMeasurementBlocksAcceptance() {
        val result = validate(summary = summaryOf(viewStability = null))
        val check = result.checks.first { it.number == 6 }

        assertEquals(CheckOutcome.NOT_EVALUATED, check.outcome)
        assertFalse(result.accepted)
        assertEquals(6, result.firstFailure!!.number)
        assertEquals(listOf(6), result.notEvaluated.map { it.number })
    }

    @Test
    fun check6_failsOnceStabilityIsMeasuredAndPoor() {
        val unstable = validate(summary = summaryOf(viewStability = 0.89))
        val stable = validate(summary = summaryOf(viewStability = 0.90))

        assertEquals(CheckOutcome.FAIL, unstable.checks.first { it.number == 6 }.outcome)
        assertTrue(unstable.checks.first { it.number == 6 }.message.contains("orientation"))
        assertEquals(CheckOutcome.PASS, stable.checks.first { it.number == 6 }.outcome)
    }

    @Test
    fun check7_needsThreeWatchedJoints() {
        val twoJoints = watchListOf(dominant, FeatureId.HIP_FLEX_ACTIVE)

        val failure = validate(watchList = twoJoints).checks.first { it.number == 7 }

        assertEquals(CheckOutcome.FAIL, failure.outcome)
        assertTrue(failure.message, failure.message.contains("enough joints"))
    }

    @Test
    fun check8_rejectsTooShortAndTooLongWithDifferentMessages() {
        val short = validate(summary = summaryOf(duration = 14.9)).checks.first { it.number == 8 }
        val long = validate(summary = summaryOf(duration = 180.1)).checks.first { it.number == 8 }
        val fine = validate(summary = summaryOf(duration = 15.0)).checks.first { it.number == 8 }

        assertEquals(CheckOutcome.FAIL, short.outcome)
        assertTrue(short.message, short.message.contains("too short"))
        assertEquals(CheckOutcome.FAIL, long.outcome)
        assertTrue(long.message, long.message.contains("too long"))
        assertEquals(CheckOutcome.PASS, fine.outcome)
    }

    // --- Reporting behaviour ---

    @Test
    fun theFirstFailureIsTheLowestNumberedOneAndIsStable() {
        // Several checks fail at once. 6.11 wants one specific reason led with, not a wall of them,
        // and the same one every time.
        val broken = validate(
            reference = referenceOf(repCount = 2, features = mapOf(dominant to feature(14.0, 20.0))),
            watchList = watchListOf(dominant),
            summary = summaryOf(duration = 5.0, repDurations = listOf(1.0, 5.0)),
        )

        repeat(3) {
            assertEquals(1, broken.firstFailure!!.number)
        }
        assertTrue(broken.checks.count { it.outcome == CheckOutcome.FAIL } >= 4)
    }

    @Test
    fun everyFailureCarriesAMessageAndEveryPassCarriesNone() {
        val mixed = validate(reference = referenceOf(repCount = 2))

        mixed.checks.forEach {
            when (it.outcome) {
                CheckOutcome.PASS -> assertTrue("${it.number} had a message", it.message.isEmpty())
                else -> assertTrue("${it.number} had no message", it.message.isNotEmpty())
            }
            assertTrue("${it.number} had no detail", it.detail.isNotEmpty())
        }
    }

    @Test
    fun durationDispersionNeedsAtLeastTwoReps() {
        assertNull(durationDispersion(emptyList()))
        assertNull(durationDispersion(listOf(2.0)))
        assertNotNull(durationDispersion(listOf(2.0, 2.0)))
        assertEquals(0.0, durationDispersion(listOf(2.0, 2.0, 2.0))!!, 1e-9)
    }

    // --- fixtures ---

    private fun validate(
        reference: Reference = referenceOf(),
        watchList: WatchList = watchListOf(dominant, FeatureId.HIP_FLEX_ACTIVE, FeatureId.TRUNK_FLEXION),
        summary: RecordingSummary = summaryOf(),
    ) = validateReference(reference, watchList, dominant, summary)

    private fun referenceOf(
        repCount: Int = 6,
        features: Map<FeatureId, FeatureReference> = mapOf(
            dominant to feature(rom = 60.0, medianSigma = 3.0),
            FeatureId.HIP_FLEX_ACTIVE to feature(rom = 40.0, medianSigma = 4.0),
            FeatureId.TRUNK_FLEXION to feature(rom = 20.0, medianSigma = 5.0),
        ),
    ) = Reference(features, repCount, BodyPosition.UPRIGHT)

    private fun watchListOf(vararg ids: FeatureId) = WatchList(
        ids.map { WatchedFeature(it, 10.0, 1.0 / ids.size) },
        emptyMap(),
    )

    private fun summaryOf(
        duration: Double = 20.0,
        repDurations: List<Double> = listOf(2.0, 2.05, 1.95, 2.0, 2.1, 1.9),
        visibility: Map<FeatureId, Float> = emptyMap(),
        viewStability: Double? = 1.0,
    ) = RecordingSummary(
        duration,
        repDurations,
        // Overrides on top of a fully-visible recording, so a test naming one joint does not
        // accidentally blank out every other watched joint.
        FeatureId.entries.associateWith { 0.95f } + visibility,
        viewStability,
    )

    private fun feature(rom: Double, medianSigma: Double) = FeatureReference(
        mean = List(Params.PHASE_BINS) { 0f },
        sigma = List(Params.PHASE_BINS) { medianSigma.toFloat() },
        derivative = List(Params.PHASE_BINS) { 0f },
        rom = rom,
        medianSigmaRaw = medianSigma,
    )
}
