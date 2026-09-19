package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.reference.BodyPosition
import com.example.rightposture.core.reference.ResampledRep
import com.example.rightposture.core.reference.buildReference
import com.example.rightposture.core.reference.derivativeOf
import com.example.rightposture.core.reference.smallSampleCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

class ReferenceBuilderTest {
    private val dominant = FeatureId.KNEE_FLEX_ACTIVE

    @Test
    fun everyReferenceArrayIsFiniteAndOneHundredAndOneLong() {
        val reference = buildReference(cleanReps(6), dominant).reference!!

        reference.features.forEach { (id, feature) ->
            listOf(feature.mean, feature.sigma, feature.derivative).forEach { array ->
                assertEquals("$id", Params.PHASE_BINS, array.size)
                array.forEachIndexed { bin, value ->
                    assertTrue("$id bin $bin was $value", value.isFinite())
                }
            }
        }
    }

    @Test
    fun oneOffsetRepBarelyMovesTheMedian() {
        val clean = buildReference(cleanReps(7), dominant).reference!!
        val withOutlier = cleanReps(7).toMutableList().also {
            it[3] = shiftedRep(it[3], 25f)
        }

        val shifted = buildReference(withOutlier, dominant).reference!!

        // The median must barely move. A mean would shift by 25/7 = 3.6 degrees.
        val worst = clean.features.getValue(dominant).mean
            .zip(shifted.features.getValue(dominant).mean) { a, b -> abs(a - b) }
            .max()
        assertTrue("median moved by $worst degrees", worst < 1.0f)
    }

    @Test
    fun smallSampleCorrectionMatchesThePlanTableExactly() {
        assertEquals(1.36, smallSampleCorrection(4), 0.0)
        assertEquals(1.21, smallSampleCorrection(5), 0.0)
        assertEquals(1.14, smallSampleCorrection(6), 0.0)
        assertEquals(1.10, smallSampleCorrection(7), 0.0)
        assertEquals(1.06, smallSampleCorrection(8), 0.0)
        assertEquals(1.06, smallSampleCorrection(20), 0.0)
    }

    @Test
    fun uprightSigmaCeilingIsSeventeenPointTwoFive() {
        // Wildly inconsistent reps drive raw sigma past the 15 degree cap everywhere.
        val reference = buildReference(scatteredReps(6), dominant).reference!!
        val feature = reference.features.getValue(dominant)

        assertTrue("raw sigma should exceed the cap", feature.medianSigmaRaw >= Params.SIGMA_CAP_DEGREES)
        assertEquals(17.25f, feature.sigma.max(), 0.001f)
        feature.sigma.forEach { assertTrue("$it exceeded 17.25", it <= 17.25f + 1e-3f) }
    }

    @Test
    fun nonUprightSigmaCeilingIsNineteenPointFive() {
        val reference = buildReference(scatteredReps(6), dominant, BodyPosition.NON_UPRIGHT).reference!!

        assertEquals(19.50f, reference.features.getValue(dominant).sigma.max(), 0.001f)
    }

    @Test
    fun inflationIsAppliedAfterTheClampNotInsideIt() {
        // If k_inflate were moved inside clamp(), the ceiling would be 15 rather than 17.25. This is
        // the refactor 6.9.4 explicitly warns against.
        val upright = buildReference(scatteredReps(6), dominant).reference!!
        val nonUpright = buildReference(scatteredReps(6), dominant, BodyPosition.NON_UPRIGHT).reference!!

        val a = upright.features.getValue(dominant).sigma.max()
        val b = nonUpright.features.getValue(dominant).sigma.max()

        assertTrue("upright ceiling $a should exceed the raw 15 degree cap", a > Params.SIGMA_CAP_DEGREES)
        assertEquals(Params.K_INFLATE_NON_UPRIGHT / Params.K_INFLATE, (b / a).toDouble(), 0.001)
    }

    @Test
    fun identicalRepsFallBackToTheSigmaFloor() {
        // MAD is exactly zero, so only the floor keeps sigma off the ground and stops a
        // divide-by-near-zero downstream.
        val reference = buildReference(cleanReps(6), dominant).reference!!
        val feature = reference.features.getValue(dominant)

        val expectedFloor = maxOf(Params.SIGMA_FLOOR_DEGREES, Params.SIGMA_FLOOR_ROM_FRACTION * feature.rom)
        feature.sigma.forEach {
            assertEquals((expectedFloor * Params.K_INFLATE).toFloat(), it, 0.001f)
        }
        assertTrue(feature.sigma.all { it > 0f })
    }

    @Test
    fun theFloorScalesWithRangeOfMotion() {
        // 80 degree ROM: 0.05 x 80 = 4 degrees, which beats the flat 2.5 degree floor.
        val wide = buildReference(cleanReps(6, amplitude = 40.0), dominant).reference!!
        // 20 degree ROM: 0.05 x 20 = 1 degree, so the flat floor wins.
        val narrow = buildReference(cleanReps(6, amplitude = 10.0), dominant).reference!!

        assertEquals((4.0 * Params.K_INFLATE).toFloat(), wide.features.getValue(dominant).sigma.max(), 0.01f)
        assertEquals(
            (Params.SIGMA_FLOOR_DEGREES * Params.K_INFLATE).toFloat(),
            narrow.features.getValue(dominant).sigma.max(),
            0.01f,
        )
    }

    @Test
    fun lumbarFlexionOverridesTheGeneralFloor() {
        val reps = cleanReps(6, amplitude = 5.0, extra = FeatureId.LUMBAR_FLEXION)

        val reference = buildReference(reps, dominant).reference!!

        val lumbar = reference.features.getValue(FeatureId.LUMBAR_FLEXION)
        val other = reference.features.getValue(FeatureId.HIP_FLEX_ACTIVE)
        assertEquals((Params.LUMBAR_SIGMA_FLOOR_DEGREES * Params.K_INFLATE).toFloat(), lumbar.sigma.max(), 0.01f)
        assertEquals((Params.SIGMA_FLOOR_DEGREES * Params.K_INFLATE).toFloat(), other.sigma.max(), 0.01f)
    }

    @Test
    fun sigmaIsSmoothedSoASingleTightBinCannotCreateASpuriousFault() {
        // One bin where the reps happen to agree exactly, surrounded by scatter. Without the
        // moving median and mean it would be a narrow notch in the band.
        val reps = scatteredReps(6).map { rep ->
            rep.copy(
                bins = rep.bins.mapValues { (_, bins) ->
                    bins.toMutableList().also { it[50] = 0f }
                },
            )
        }

        val sigma = buildReference(reps, dominant).reference!!.features.getValue(dominant).sigma

        val neighbourhood = (46..54).map { sigma[it] }
        assertTrue(
            "bin 50 notched to ${sigma[50]} against neighbours ${neighbourhood.max()}",
            sigma[50] > neighbourhood.max() * 0.9f,
        )
    }

    @Test
    fun shapeOutlierIsDropped() {
        // Five well-formed reps and one whose trajectory is a different shape entirely.
        val reps = cleanReps(6).toMutableList().also { it[2] = flatRep() }

        val build = buildReference(reps, dominant)

        assertEquals(5, build.usedReps)
        assertEquals(5, build.reference!!.repCount)
        assertNull(build.rejection)
    }

    @Test
    fun tooFewSurvivorsRejectsTheReference() {
        val build = buildReference(cleanReps(3), dominant)

        assertNull(build.reference)
        assertNotNull(build.rejection)
        assertTrue(build.rejection!!, build.rejection!!.contains("4"))
    }

    @Test
    fun derivativeIsCentralInsideAndOneSidedAtBothEnds() {
        val ramp = List(Params.PHASE_BINS) { it.toFloat() * 2f }

        val derivative = derivativeOf(ramp)

        assertEquals(Params.PHASE_BINS, derivative.size)
        derivative.forEach { assertEquals(2f, it, 0.0001f) }
    }

    @Test
    fun derivativeTracksTheMeanTrajectory() {
        val feature = buildReference(cleanReps(6), dominant).reference!!.features.getValue(dominant)

        // Cosine trajectory: steepest at the quarter points, flat at the extremes.
        assertTrue(abs(feature.derivative[50]) < abs(feature.derivative[25]))
        assertTrue(abs(feature.derivative[0]) < abs(feature.derivative[25]))
    }

    @Test
    fun aFeatureMissingFromOneRepIsLeftOutOfTheReference() {
        val reps = cleanReps(6).toMutableList()
        reps[1] = reps[1].copy(bins = reps[1].bins.filterKeys { it != FeatureId.HIP_FLEX_ACTIVE })

        val reference = buildReference(reps, dominant).reference!!

        assertTrue(reference.features.containsKey(dominant))
        assertTrue(reference.features.containsKey(FeatureId.HIP_FLEX_ACTIVE).not())
    }

    // --- fixtures ---

    private fun trajectory(amplitude: Double) =
        List(Params.PHASE_BINS) { bin -> (-amplitude * cos(2.0 * PI * bin / 100.0)).toFloat() }

    private fun cleanReps(
        count: Int,
        amplitude: Double = 40.0,
        extra: FeatureId? = null,
    ) = List(count) {
        val bins = mutableMapOf(
            dominant to trajectory(amplitude),
            FeatureId.HIP_FLEX_ACTIVE to trajectory(amplitude / 2),
        )
        extra?.let { bins[it] = trajectory(amplitude / 4) }
        ResampledRep(bins, 2 * amplitude)
    }

    /** Reps scattered far enough apart that raw sigma exceeds the 15 degree cap at every bin. */
    private fun scatteredReps(count: Int) = List(count) { index ->
        val offset = (index - count / 2) * 40f
        ResampledRep(
            mapOf(dominant to trajectory(40.0).map { it + offset }),
            80.0,
        )
    }

    private fun flatRep() = ResampledRep(
        mapOf(
            dominant to List(Params.PHASE_BINS) { 0f },
            FeatureId.HIP_FLEX_ACTIVE to List(Params.PHASE_BINS) { 0f },
        ),
        0.0,
    )

    private fun shiftedRep(rep: ResampledRep, by: Float) =
        rep.copy(bins = rep.bins.mapValues { (_, bins) -> bins.map { it + by } })
}
