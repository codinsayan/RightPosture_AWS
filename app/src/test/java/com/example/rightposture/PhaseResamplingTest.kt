package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.reference.phaseProfile
import com.example.rightposture.core.reference.resampleRep
import com.example.rightposture.core.reference.resampleUsableReps
import com.example.rightposture.core.reference.segmentReps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

class PhaseResamplingTest {
    private val dominant = FeatureId.KNEE_FLEX_ACTIVE

    @Test
    fun sameMotionAtDifferentTemposResamplesToTheSameTrajectory() {
        // The point of the whole iteration. A patient moving at a different tempo must land on the
        // same phase bins, otherwise every z-score is wrong in a tempo-dependent way (6.8.1).
        val slow = oneRep(samples = 120, amplitude = 40.0)
        val fast = oneRep(samples = 31, amplitude = 40.0)

        val slowBins = resampleRep(mapOf(dominant to slow), dominant, 0, 60, 120)!!.bins.getValue(dominant)
        val fastBins = resampleRep(mapOf(dominant to fast), dominant, 0, 15, 30)!!.bins.getValue(dominant)

        slowBins.indices.forEach { bin ->
            assertEquals("bin $bin", slowBins[bin], fastBins[bin], 1.0f)
        }
    }

    @Test
    fun everyValidFeatureGetsExactlyOneHundredAndOneFiniteBins() {
        val signals = mapOf(
            dominant to oneRep(120, 40.0),
            FeatureId.HIP_FLEX_ACTIVE to oneRep(120, 25.0),
            FeatureId.TRUNK_FLEXION to List(121) { 12f },
        )

        val rep = resampleRep(signals, dominant, 0, 60, 120)!!

        assertEquals(3, rep.bins.size)
        rep.bins.forEach { (id, bins) ->
            assertEquals("$id", Params.PHASE_BINS, bins.size)
            assertEquals(101, bins.size)
            bins.forEachIndexed { bin, value ->
                assertTrue("$id bin $bin was $value", value.isFinite())
            }
        }
    }

    @Test
    fun aFeatureWithANonFiniteSampleIsOmittedRatherThanPoisoningTheRep() {
        val broken = oneRep(120, 25.0).toMutableList().also { it[30] = Float.NaN }
        val signals = mapOf(
            dominant to oneRep(120, 40.0),
            FeatureId.HIP_FLEX_ACTIVE to broken,
        )

        val rep = resampleRep(signals, dominant, 0, 60, 120)!!

        assertTrue(rep.bins.containsKey(dominant))
        assertTrue(rep.bins.containsKey(FeatureId.HIP_FLEX_ACTIVE).not())
    }

    @Test
    fun phaseRunsZeroToOneHundredAndNeverGoesBackwards() {
        val profile = phaseProfile(oneRep(120, 40.0).map { it.toDouble() }, 0, 60, 120)!!

        assertEquals(0.0, profile.first(), 0.0001)
        assertEquals(50.0, profile[60], 0.0001)
        assertEquals(100.0, profile.last(), 0.0001)
        profile.zipWithNext().forEach { (a, b) ->
            assertTrue("phase went backwards: $a then $b", b >= a - 1e-9)
        }
    }

    @Test
    fun noiseInsideAHalfRepDoesNotMakePhaseGoBackwards() {
        // Wobble large enough to reverse the raw ratio. 6.8.3 says a reversal inside a half-rep is
        // noise or tremor, and must not be modelled as phase.
        val noisy = List(121) { index ->
            val base = -40.0 * cos(2.0 * PI * index / 120.0)
            val wobble = if (index % 7 == 0) 6.0 else 0.0
            (base + wobble).toFloat()
        }

        val profile = phaseProfile(noisy.map { it.toDouble() }, 0, 60, 120)!!

        profile.zipWithNext().forEach { (a, b) ->
            assertTrue("phase went backwards: $a then $b", b >= a - 1e-9)
        }
        assertEquals(50.0, profile[60], 0.0001)
    }

    @Test
    fun aStallDoesNotDivideByZero() {
        // Physio holds at end range for a third of the rep. Many frames collapse onto bins 49-51.
        val held = mutableListOf<Float>()
        repeat(40) { held.add((-40.0 * cos(PI * it / 40.0)).toFloat()) }
        repeat(41) { held.add(40f) }
        repeat(40) { held.add((40.0 * cos(PI * it / 40.0)).toFloat()) }

        val rep = resampleRep(mapOf(dominant to held), dominant, 0, 60, 120)!!
        val bins = rep.bins.getValue(dominant)

        assertEquals(101, bins.size)
        bins.forEachIndexed { bin, value -> assertTrue("bin $bin was $value", value.isFinite()) }
        assertEquals(40f, bins[50], 0.5f)
    }

    @Test
    fun undershootKeepsFullPhaseButRecordsTheSmallerRangeOfMotion() {
        // 6.8.4: phase is normalised per rep, so a short rep still spans 0..100 and would hide its
        // own undershoot. ROM is recorded separately precisely so it does not.
        val full = resampleRep(mapOf(dominant to oneRep(120, 40.0)), dominant, 0, 60, 120)!!
        val short = resampleRep(mapOf(dominant to oneRep(120, 22.0)), dominant, 0, 60, 120)!!

        assertEquals(80.0, full.rom, 0.5)
        assertEquals(44.0, short.rom, 0.5)

        // Phase coverage is identical despite the different range.
        assertEquals(full.bins.getValue(dominant).size, short.bins.getValue(dominant).size)
        assertTrue(
            "the short rep must still reach its own extremum at bin 50",
            abs(short.bins.getValue(dominant)[50] - 22f) < 1f,
        )
    }

    @Test
    fun aRepThatNeverMovedIsDegenerate() {
        assertNull(resampleRep(mapOf(dominant to List(121) { 30f }), dominant, 0, 60, 120))
        assertNull(phaseProfile(List(121) { 30.0 }, 0, 60, 120))
    }

    @Test
    fun malformedBoundariesReturnNullRatherThanThrowing() {
        val signal = oneRep(120, 40.0).map { it.toDouble() }

        assertNull(phaseProfile(signal, 60, 60, 120))
        assertNull(phaseProfile(signal, 0, 60, 60))
        assertNull(phaseProfile(signal, 0, 60, 500))
        assertNull(resampleRep(emptyMap(), dominant, 0, 60, 120))
    }

    @Test
    fun feedsStraightFromSegmentationWithoutRecomputingTheExtremum() {
        val lead = 15
        val signal = List(8 * 60 + 2 * lead) { index ->
            (-40.0 * cos(2.0 * PI * (index - lead) / 60.0)).toFloat()
        }
        val segmentation = segmentReps(signal, 60)

        val reps = resampleUsableReps(segmentation, mapOf(dominant to signal), dominant)

        assertEquals(6, segmentation.usable.size)
        assertEquals(6, reps.size)
        reps.forEach {
            assertEquals(101, it.bins.getValue(dominant).size)
            assertEquals(80.0, it.rom, 1.0)
        }
    }

    @Test
    fun repsOfDifferentDurationsProduceComparableBins() {
        // Same shape, three tempos, fed through the real path. Bin 50 is the extremum in all three.
        listOf(40, 60, 90).forEach { half ->
            val signal = oneRep(half * 2, 40.0)
            val rep = resampleRep(mapOf(dominant to signal), dominant, 0, half, half * 2)

            assertNotNull("half=$half", rep)
            assertEquals("half=$half", 40f, rep!!.bins.getValue(dominant)[50], 0.5f)
            assertEquals("half=$half", -40f, rep.bins.getValue(dominant)[0], 0.5f)
        }
    }

    /** One full cycle over [samples] steps, valley to peak to valley. */
    private fun oneRep(samples: Int, amplitude: Double) = List(samples + 1) { index ->
        (-amplitude * cos(2.0 * PI * index / samples)).toFloat()
    }
}
