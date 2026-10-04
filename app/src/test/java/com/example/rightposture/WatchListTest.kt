package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.reference.BodyPosition
import com.example.rightposture.core.reference.FeatureReference
import com.example.rightposture.core.reference.Reference
import com.example.rightposture.core.reference.salienceOf
import com.example.rightposture.core.reference.selectWatchList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchListTest {
    private val dominant = FeatureId.KNEE_FLEX_ACTIVE

    // --- P3-15, the test section 6.10 requires by name ---

    @Test
    fun p3_15_largeRomWithHugeSigmaIsExcluded() {
        // ROM 60, sigma 22. Salience is 2.7 so it clears filter 4, and the ROM clears filter 1.
        // Only filter 2 can reject it, which is exactly why the plan asks for this case: if the
        // filter is inverted this fails immediately rather than three hours later as inexplicable
        // scoring behaviour.
        val watchList = selectWatchList(
            referenceOf(dominant to feature(rom = 60.0, medianSigma = 22.0)),
            mapOf(dominant to 0.95f),
            FeatureId.HIP_FLEX_ACTIVE,
        )

        assertTrue(
            "ROM 60 / sigma 22 must not be watched, got ${watchList.features.map { it.id }}",
            watchList.features.none { it.id == dominant },
        )
        assertTrue(watchList.rejected.getValue(dominant), watchList.rejected.getValue(dominant).contains("unconstrained"))
    }

    @Test
    fun p3_15_moderateRomWithTightSigmaIsIncluded() {
        val watchList = selectWatchList(
            referenceOf(dominant to feature(rom = 40.0, medianSigma = 3.0)),
            mapOf(dominant to 0.95f),
            FeatureId.HIP_FLEX_ACTIVE,
        )

        assertEquals(listOf(dominant), watchList.features.map { it.id })
    }

    // --- The four filters, one at a time ---

    @Test
    fun filterOneRejectsAJointThatBarelyMoved() {
        val watchList = watch(
            FeatureId.TRUNK_ROTATION to feature(rom = 7.9, medianSigma = 1.0),
            FeatureId.HIP_FLEX_ACTIVE to feature(rom = 8.0, medianSigma = 1.0),
        )

        assertFalse(watchList.features.any { it.id == FeatureId.TRUNK_ROTATION })
        assertTrue(watchList.features.any { it.id == FeatureId.HIP_FLEX_ACTIVE })
        assertTrue(watchList.rejected.getValue(FeatureId.TRUNK_ROTATION).contains("barely moved"))
    }

    @Test
    fun filterTwoKeepsSmallSigmaAndRejectsLargeSigma() {
        // Same ROM, so only sigma differs. Small sigma is good.
        val watchList = watch(
            FeatureId.HIP_FLEX_ACTIVE to feature(rom = 60.0, medianSigma = 4.0),
            FeatureId.TRUNK_ROTATION to feature(rom = 60.0, medianSigma = 18.0),
        )

        assertTrue(watchList.features.any { it.id == FeatureId.HIP_FLEX_ACTIVE })
        assertFalse(watchList.features.any { it.id == FeatureId.TRUNK_ROTATION })
    }

    @Test
    fun filterTwoUsesTheSameCapAsTheReferenceBuilder() {
        // Exactly at the cap fails; just under it passes. Tied to SIGMA_CAP_DEGREES rather than a
        // separate constant, so the two cannot drift apart (6.9.4).
        val subject = FeatureId.TRUNK_ROTATION
        val atCap = watchWithout(subject to feature(rom = 60.0, medianSigma = Params.SIGMA_CAP_DEGREES))
        val underCap = watchWithout(subject to feature(rom = 60.0, medianSigma = Params.SIGMA_CAP_DEGREES - 0.01))

        assertTrue(atCap.features.none { it.id == subject })
        assertTrue(underCap.features.any { it.id == subject })
    }

    @Test
    fun filterThreeRejectsAPoorlyObservedJoint() {
        val reference = referenceOf(
            FeatureId.HIP_FLEX_ACTIVE to feature(rom = 40.0, medianSigma = 3.0),
            FeatureId.ANKLE_DORSIFLEX_ACTIVE to feature(rom = 40.0, medianSigma = 3.0),
        )

        val watchList = selectWatchList(
            reference,
            mapOf(FeatureId.HIP_FLEX_ACTIVE to 0.70f, FeatureId.ANKLE_DORSIFLEX_ACTIVE to 0.69f),
            FeatureId.HIP_FLEX_ACTIVE,
        )

        assertTrue(watchList.features.any { it.id == FeatureId.HIP_FLEX_ACTIVE })
        assertFalse(watchList.features.any { it.id == FeatureId.ANKLE_DORSIFLEX_ACTIVE })
        assertTrue(
            watchList.rejected.getValue(FeatureId.ANKLE_DORSIFLEX_ACTIVE).contains("poorly observed"),
        )
    }

    @Test
    fun aFeatureWithNoVisibilityRecordedFailsRatherThanPassingByDefault() {
        val watchList = selectWatchList(
            referenceOf(dominant to feature(rom = 40.0, medianSigma = 3.0)),
            emptyMap(),
            FeatureId.HIP_FLEX_ACTIVE,
        )

        assertTrue(watchList.features.isEmpty())
        assertTrue(watchList.rejected.getValue(dominant).contains("poorly observed"))
    }

    @Test
    fun filterFourRejectsAJointThatMovesNoMoreThanItsOwnVariance() {
        // Salience exactly 2.0 passes, just under fails.
        val subject = FeatureId.TRUNK_ROTATION
        val passing = watchWithout(subject to feature(rom = 20.0, medianSigma = 10.0))
        val failing = watchWithout(subject to feature(rom = 19.9, medianSigma = 10.0))

        assertTrue(passing.features.any { it.id == subject })
        assertTrue(failing.features.none { it.id == subject })
        assertTrue(failing.rejected.getValue(subject).contains("salience"))
    }

    // --- Ranking, size, dominant inclusion, weights ---

    // The two size tests are written against the constant, not the number. A test that spells out
    // "five" asserts the constant's current value rather than the behaviour it controls, and 11.3
    // changed the value.

    @Test
    fun onlyTheTopWatchListSizeSurviveAndTheyAreRankedBySalience() {
        val candidates = rankedCandidates(Params.WATCH_LIST_SIZE + 2)
        val watchList = watch(*candidates.toTypedArray())

        assertEquals(Params.WATCH_LIST_SIZE, watchList.features.size)
        assertEquals(
            candidates.take(Params.WATCH_LIST_SIZE).map { it.first },
            watchList.features.map { it.id },
        )
        watchList.features.zipWithNext().forEach { (a, b) ->
            assertTrue("not ranked by salience", a.salience >= b.salience)
        }
    }

    @Test
    fun theDominantFeatureIsIncludedEvenWhenItRanksOutsideTheTopWatchListSize() {
        val candidates = rankedCandidates(Params.WATCH_LIST_SIZE + 1)
        val lastRanked = candidates.last().first
        val watchList = selectWatchList(
            referenceOf(*candidates.toTypedArray()),
            visibilityForAll(),
            lastRanked,
        )

        assertEquals(Params.WATCH_LIST_SIZE + 1, watchList.features.size)
        assertTrue(watchList.features.any { it.id == lastRanked })
    }

    @Test
    fun theSizeCapStillLeavesTheValidatorsFloorReachable() {
        // 3.8 refuses a reference whose watch list is shorter than its floor. A cap under the floor
        // would make every reference fail validation.
        assertTrue(Params.WATCH_LIST_SIZE >= Params.VALIDATOR_MIN_WATCH_LIST_SIZE)
    }

    @Test
    fun theDominantFeatureIsNotDuplicatedWhenItAlreadyRanksHighly() {
        val watchList = watch(
            FeatureId.KNEE_FLEX_ACTIVE to feature(rom = 60.0, medianSigma = 2.0),
            FeatureId.HIP_FLEX_ACTIVE to feature(rom = 60.0, medianSigma = 3.0),
        )

        assertEquals(1, watchList.features.count { it.id == dominant })
    }

    @Test
    fun theDominantFeatureIsIncludedEvenWhenItFailsAFilter() {
        // 6.10 adds the dominant feature after filtering, not before, so it survives rejection.
        // That is deliberate: phase itself is derived from this feature (6.8.2), so a reference
        // that does not score it is not scoring anything coherent.
        val watchList = watch(dominant to feature(rom = 60.0, medianSigma = 22.0))

        assertTrue(watchList.features.any { it.id == dominant })
        assertTrue("still reported as rejected", watchList.rejected.containsKey(dominant))
    }

    @Test
    fun weightsSumToOne() {
        listOf(1, 3, 5, 6).forEach { count ->
            val entries = FeatureId.entries.take(count).mapIndexed { index, id ->
                id to feature(rom = 60.0, medianSigma = 2.0 + index)
            }.toTypedArray()

            val watchList = watch(*entries)

            assertEquals("count=$count", 1.0, watchList.features.sumOf { it.weight }, 1e-9)
        }
    }

    @Test
    fun theTightlyControlledJointCarriesTheMostWeight() {
        val watchList = watch(
            FeatureId.KNEE_FLEX_ACTIVE to feature(rom = 60.0, medianSigma = 2.0),
            FeatureId.HIP_FLEX_ACTIVE to feature(rom = 60.0, medianSigma = 6.0),
        )

        val tight = watchList.features.first { it.id == FeatureId.KNEE_FLEX_ACTIVE }
        val loose = watchList.features.first { it.id == FeatureId.HIP_FLEX_ACTIVE }
        assertTrue("${tight.weight} should exceed ${loose.weight}", tight.weight > loose.weight)
        assertEquals(3.0, tight.weight / loose.weight, 1e-9)
    }

    @Test
    fun anEmptyWatchListProducesNoWeightsRatherThanNaN() {
        val watchList = selectWatchList(referenceOf(), emptyMap(), dominant)

        assertTrue(watchList.features.isEmpty())
    }

    @Test
    fun salienceUsesThePreClampSigmaNotTheStoredBand() {
        // Stored sigma is clamped at 15 and then inflated, so reading salience off it would both
        // compress the ranking and scale every value by k_inflate.
        val feature = feature(rom = 60.0, medianSigma = 30.0)

        assertEquals(2.0, salienceOf(feature), 1e-9)
    }

    @Test
    fun zeroSigmaUsesTheReferenceFloorInsteadOfLosingAPerfectlyConsistentFeature() {
        assertEquals(20.0, salienceOf(feature(rom = 60.0, medianSigma = 0.0)), 1e-9)
    }

    // --- fixtures ---

    /**
     * [count] features that all pass every filter, in strictly descending salience: same ROM, sigma
     * rising by one degree per rank.
     */
    private fun rankedCandidates(count: Int): List<Pair<FeatureId, FeatureReference>> {
        require(count <= FeatureId.entries.size) { "only ${FeatureId.entries.size} features exist" }
        return FeatureId.entries.take(count).mapIndexed { rank, id ->
            id to feature(rom = 60.0, medianSigma = 2.0 + rank)
        }
    }

    private fun watch(vararg entries: Pair<FeatureId, FeatureReference>) =
        selectWatchList(referenceOf(*entries), visibilityForAll(), dominant)

    /**
     * Names a dominant feature that is absent from the reference, so nothing is force-included and
     * the filter under test is the only thing deciding the outcome.
     */
    private fun watchWithout(vararg entries: Pair<FeatureId, FeatureReference>) =
        selectWatchList(referenceOf(*entries), visibilityForAll(), FeatureId.SHOULDER_ELEVATION)

    private fun visibilityForAll() = FeatureId.entries.associateWith { 0.95f }

    private fun referenceOf(vararg entries: Pair<FeatureId, FeatureReference>) =
        Reference(entries.toMap(), repCount = 6, bodyPosition = BodyPosition.UPRIGHT)

    private fun feature(rom: Double, medianSigma: Double) = FeatureReference(
        mean = List(Params.PHASE_BINS) { 0f },
        sigma = List(Params.PHASE_BINS) { medianSigma.toFloat() },
        derivative = List(Params.PHASE_BINS) { 0f },
        rom = rom,
        medianSigmaRaw = medianSigma,
    )
}
