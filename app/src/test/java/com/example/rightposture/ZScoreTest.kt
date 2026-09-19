package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.live.effectiveSigma
import com.example.rightposture.core.live.phaseUncertainty
import com.example.rightposture.core.live.referenceAt
import com.example.rightposture.core.live.zScoreOf
import com.example.rightposture.core.live.zScores
import com.example.rightposture.core.reference.BodyPosition
import com.example.rightposture.core.reference.FeatureReference
import com.example.rightposture.core.reference.Reference
import com.example.rightposture.core.reference.WatchList
import com.example.rightposture.core.reference.WatchedFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZScoreTest {

    private fun feature(
        mean: (Int) -> Float = { 90f + it },
        sigma: (Int) -> Float = { 4f },
        derivative: (Int) -> Float = { 0f },
    ) = FeatureReference(
        mean = List(Params.PHASE_BINS, mean),
        sigma = List(Params.PHASE_BINS, sigma),
        derivative = List(Params.PHASE_BINS, derivative),
        rom = 100.0,
        medianSigmaRaw = 4.0,
    )

    /** Flat reference: μ = 100, σ = 4, dμ = 0, so σ_eff is σ and a z-score is easy to reason about. */
    private fun flat() = feature(mean = { 100f }, sigma = { 4f }, derivative = { 0f })

    // -------------------------------------------------------------------------------------
    // §6.13.1 interpolation
    // -------------------------------------------------------------------------------------

    @Test
    fun `an exact bin returns that bin unchanged`() {
        val point = referenceAt(feature(), 40.0)
        assertEquals(130.0, point.mu, 1e-6)
        assertEquals(4.0, point.sigma, 1e-6)
    }

    @Test
    fun `a phase between bins interpolates all three arrays`() {
        val point = referenceAt(
            feature(mean = { 90f + it }, sigma = { 2f + it * 0.1f }, derivative = { it * 0.5f }),
            40.25,
        )
        assertEquals(130.25, point.mu, 1e-5)
        assertEquals(6.025, point.sigma, 1e-5)
        assertEquals(20.125, point.dMu, 1e-5)
    }

    @Test
    fun `the last bin does not index past the end`() {
        val point = referenceAt(feature(), 100.0)
        assertEquals(190.0, point.mu, 1e-6)
    }

    @Test
    fun `phase outside the grid is clamped rather than extrapolated`() {
        assertEquals(referenceAt(feature(), 0.0).mu, referenceAt(feature(), -5.0).mu, 1e-6)
        assertEquals(referenceAt(feature(), 100.0).mu, referenceAt(feature(), 130.0).mu, 1e-6)
    }

    // -------------------------------------------------------------------------------------
    // §6.13.2 phase uncertainty
    // -------------------------------------------------------------------------------------

    @Test
    fun `an older bundle with no v_ref falls back to a flat three phase units`() {
        assertEquals(3.0, phaseUncertainty(null, 0.6), 1e-9)
        assertEquals(3.0, phaseUncertainty(null, 0.001), 1e-9)
    }

    @Test
    fun `moving at or above the reference tempo does not widen the window`() {
        assertEquals(3.0, phaseUncertainty(0.6, 0.6), 1e-9)
        assertEquals(3.0, phaseUncertainty(0.6, 2.0), 1e-9)
    }

    @Test
    fun `a slow dominant joint widens the window, capped at three times`() {
        assertEquals(6.0, phaseUncertainty(0.6, 0.3), 1e-9)
        assertEquals(9.0, phaseUncertainty(0.6, 0.05), 1e-9)
        assertEquals(9.0, phaseUncertainty(0.6, 0.0), 1e-9)
    }

    // -------------------------------------------------------------------------------------
    // §6.13.2 inflation - the failure it prevents
    // -------------------------------------------------------------------------------------

    @Test
    fun `a flat reference is not widened at all`() {
        val point = referenceAt(flat(), 50.0)
        assertEquals(4.0, effectiveSigma(point, 3.0), 1e-9)
    }

    @Test
    fun `a steep reference stops a phase error looking like a fault`() {
        // Same 12 degree deviation, same 4 degree band. On a flat stretch that is a 3-sigma fault.
        // On a stretch climbing 3 degrees per phase unit it is well inside tolerance, because a
        // three-unit phase error alone explains it. This is the burst of spurious mid-rep faults
        // §6.13.2 exists to prevent.
        val onFlat = zScoreOf(FeatureId.KNEE_FLEX_ACTIVE, 112f, flat(), 50.0, 3.0)!!
        val steep = feature(mean = { 100f }, sigma = { 4f }, derivative = { 3f })
        val onSteep = zScoreOf(FeatureId.KNEE_FLEX_ACTIVE, 112f, steep, 50.0, 3.0)!!

        assertEquals(3.0, onFlat.z, 1e-6)
        assertTrue("steep phase must widen the band", onSteep.sigmaEff > onFlat.sigmaEff)
        assertTrue("12 degrees on a steep phase must not read as a fault", onSteep.z < 2.0)
    }

    @Test
    fun `a vanishing sigma is floored rather than dividing by almost zero`() {
        val degenerate = feature(mean = { 100f }, sigma = { 0f }, derivative = { 0f })
        val result = zScoreOf(FeatureId.KNEE_FLEX_ACTIVE, 100.1f, degenerate, 50.0, 3.0)!!
        assertEquals(Params.SIGMA_EFF_MIN_DEGREES, result.sigmaEff, 1e-9)
        assertEquals(0.2, result.z, 1e-5)
    }

    // -------------------------------------------------------------------------------------
    // §6.13.3 the z-score
    // -------------------------------------------------------------------------------------

    @Test
    fun `a known deviation gives the expected magnitude`() {
        assertEquals(2.0, zScoreOf(FeatureId.KNEE_FLEX_ACTIVE, 108f, flat(), 50.0, 3.0)!!.z, 1e-6)
        assertEquals(1.0, zScoreOf(FeatureId.KNEE_FLEX_ACTIVE, 104f, flat(), 50.0, 3.0)!!.z, 1e-6)
        assertEquals(0.0, zScoreOf(FeatureId.KNEE_FLEX_ACTIVE, 100f, flat(), 50.0, 3.0)!!.z, 1e-6)
    }

    /**
     * Appendix A: **positive z means the measured angle exceeds the reference**, for every feature.
     * For `knee_flex_active`, where a larger angle is a straighter leg, positive therefore reads as
     * "not bending enough" and the cue is "bend more". Getting this backwards is, per §6.13.3, the
     * single most common source of backwards cues, so it is asserted rather than assumed.
     */
    @Test
    fun `sign follows the measurement, above and below reference`() {
        val above = zScoreOf(FeatureId.KNEE_FLEX_ACTIVE, 108f, flat(), 50.0, 3.0)!!
        val below = zScoreOf(FeatureId.KNEE_FLEX_ACTIVE, 92f, flat(), 50.0, 3.0)!!
        assertTrue(above.z > 0)
        assertTrue(below.z < 0)
        assertEquals(-above.z, below.z, 1e-6)
    }

    @Test
    fun `every demo feature reads positive when it exceeds the reference`() {
        listOf(
            FeatureId.KNEE_FLEX_ACTIVE,
            FeatureId.HIP_FLEX_ACTIVE,
            FeatureId.TRUNK_FLEXION,
            FeatureId.ANKLE_DORSIFLEX_ACTIVE,
            FeatureId.SHOULDER_FLEX_ACTIVE,
        ).forEach { id ->
            val result = zScoreOf(id, 108f, flat(), 50.0, 3.0)!!
            assertTrue("$id should read positive above reference", result.z > 0)
            assertEquals(2.0, result.z, 1e-6)
        }
    }

    @Test
    fun `a non-finite measurement produces no score`() {
        assertNull(zScoreOf(FeatureId.KNEE_FLEX_ACTIVE, Float.NaN, flat(), 50.0, 3.0))
    }

    // -------------------------------------------------------------------------------------
    // Frame-level selection
    // -------------------------------------------------------------------------------------

    private fun reference() = Reference(
        features = mapOf(
            FeatureId.KNEE_FLEX_ACTIVE to flat(),
            FeatureId.TRUNK_FLEXION to flat(),
        ),
        repCount = 6,
        bodyPosition = BodyPosition.UPRIGHT,
    )

    private fun watchList() = WatchList(
        features = listOf(
            WatchedFeature(FeatureId.KNEE_FLEX_ACTIVE, 9.2, 0.6),
            WatchedFeature(FeatureId.TRUNK_FLEXION, 5.4, 0.4),
        ),
        rejected = emptyMap(),
    )

    @Test
    fun `only valid watch-listed features are scored`() {
        val scores = zScores(
            measured = mapOf(
                FeatureId.KNEE_FLEX_ACTIVE to 108f,
                // Watch-listed but abstained this frame (1.6) - absent, not zero.
                // TRUNK_FLEXION omitted on purpose.
                FeatureId.ELBOW_FLEX_ACTIVE to 140f,
            ),
            reference = reference(),
            watchList = watchList(),
            phase = 50.0,
            velocity = 0.6,
            vRef = 0.6,
        )
        assertEquals(setOf(FeatureId.KNEE_FLEX_ACTIVE), scores.keys)
        assertEquals(2.0, scores.getValue(FeatureId.KNEE_FLEX_ACTIVE).z, 1e-6)
    }

    @Test
    fun `a watch-listed feature the reference does not carry is skipped`() {
        val watchList = WatchList(
            features = watchList().features + WatchedFeature(FeatureId.LUMBAR_FLEXION, 3.0, 0.1),
            rejected = emptyMap(),
        )
        val scores = zScores(
            measured = mapOf(FeatureId.LUMBAR_FLEXION to 20f),
            reference = reference(),
            watchList = watchList,
            phase = 50.0,
            velocity = 0.6,
            vRef = 0.6,
        )
        assertTrue(scores.isEmpty())
    }

    @Test
    fun `a slow frame widens every feature's band together`() {
        fun scoresAt(velocity: Double) = zScores(
            measured = mapOf(FeatureId.KNEE_FLEX_ACTIVE to 108f),
            reference = Reference(
                mapOf(FeatureId.KNEE_FLEX_ACTIVE to feature(mean = { 100f }, sigma = { 4f }, derivative = { 2f })),
                6,
                BodyPosition.UPRIGHT,
            ),
            watchList = watchList(),
            phase = 50.0,
            velocity = velocity,
            vRef = 0.6,
        )
        val quick = scoresAt(0.6).getValue(FeatureId.KNEE_FLEX_ACTIVE)
        val crawling = scoresAt(0.05).getValue(FeatureId.KNEE_FLEX_ACTIVE)
        assertTrue(crawling.sigmaEff > quick.sigmaEff)
        assertTrue(crawling.z < quick.z)
    }
}
