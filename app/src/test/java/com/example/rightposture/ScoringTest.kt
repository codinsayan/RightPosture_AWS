package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.live.FeatureZ
import com.example.rightposture.core.live.ScoredFrame
import com.example.rightposture.core.live.ScoredRep
import com.example.rightposture.core.live.criticalWindow
import com.example.rightposture.core.live.frameScore
import com.example.rightposture.core.live.phaseWeight
import com.example.rightposture.core.live.repScore
import com.example.rightposture.core.live.romPenalty
import com.example.rightposture.core.live.setScore
import com.example.rightposture.core.live.tempoPenalty
import com.example.rightposture.core.reference.FeatureReference
import com.example.rightposture.core.reference.WatchList
import com.example.rightposture.core.reference.WatchedFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {

    private val knee = FeatureId.KNEE_FLEX_ACTIVE
    private val trunk = FeatureId.TRUNK_FLEXION
    private val hip = FeatureId.HIP_FLEX_ACTIVE

    private fun watchList(vararg weights: Pair<FeatureId, Double>) = WatchList(
        features = weights.map { WatchedFeature(it.first, 5.0, it.second) },
        rejected = emptyMap(),
    )

    private fun z(vararg values: Pair<FeatureId, Double>) = values.associate {
        it.first to FeatureZ(it.first, it.second, 100.0, 100.0, 4.0)
    }

    // -------------------------------------------------------------------------------------
    // §6.17.1 frame score - the calibration points the plan names
    // -------------------------------------------------------------------------------------

    @Test
    fun `every joint at two sigma scores about 55`() {
        val score = frameScore(
            z(knee to 2.0, trunk to -2.0, hip to 2.0),
            watchList(knee to 0.5, trunk to 0.3, hip to 0.2),
        )!!
        assertEquals(55.0, score, 1.0)
    }

    @Test
    fun `every joint at one sigma scores about 86`() {
        val score = frameScore(
            z(knee to 1.0, trunk to 1.0, hip to -1.0),
            watchList(knee to 0.5, trunk to 0.3, hip to 0.2),
        )!!
        assertEquals(86.0, score, 1.0)
    }

    @Test
    fun `a patient exactly on the reference scores 100`() {
        val score = frameScore(z(knee to 0.0, trunk to 0.0), watchList(knee to 0.6, trunk to 0.4))!!
        assertEquals(100.0, score, 1e-9)
    }

    @Test
    fun `sign does not affect the score, only the magnitude does`() {
        val above = frameScore(z(knee to 2.5), watchList(knee to 1.0))!!
        val below = frameScore(z(knee to -2.5), watchList(knee to 1.0))!!
        assertEquals(above, below, 1e-9)
    }

    @Test
    fun `a catastrophic glitch is clamped at six sigma`() {
        val glitch = frameScore(z(knee to 400.0), watchList(knee to 1.0))!!
        val atClamp = frameScore(z(knee to Params.Z_CLAMP), watchList(knee to 1.0))!!
        assertEquals(atClamp, glitch, 1e-9)
        assertTrue("clamped score must still be above zero", glitch > 0.0)
    }

    @Test
    fun `one bad joint cannot drag the score negative`() {
        val score = frameScore(
            z(knee to 50.0, trunk to 0.0, hip to 0.0),
            watchList(knee to 0.5, trunk to 0.3, hip to 0.2),
        )!!
        assertTrue(score in 0.0..100.0)
    }

    // -------------------------------------------------------------------------------------
    // §6.17.1 renormalisation over valid features
    // -------------------------------------------------------------------------------------

    @Test
    fun `an abstaining feature is renormalised out, not counted as agreement`() {
        val list = watchList(knee to 0.5, trunk to 0.3, hip to 0.2)
        // trunk abstained this frame (1.6). The remaining two must be scored as if they were the
        // whole watch list - not as if trunk had sat perfectly on the reference.
        val partial = frameScore(z(knee to 2.0, hip to 2.0), list)!!
        val equivalent = frameScore(z(knee to 2.0, hip to 2.0), watchList(knee to 0.5, hip to 0.2))!!
        assertEquals(equivalent, partial, 1e-9)
        assertEquals(55.0, partial, 1.0)
    }

    @Test
    fun `no valid feature means no score, not a zero`() {
        assertNull(frameScore(emptyMap(), watchList(knee to 1.0)))
    }

    // -------------------------------------------------------------------------------------
    // §6.17.2 phase weighting
    // -------------------------------------------------------------------------------------

    private fun reference(sigma: (Int) -> Float) = FeatureReference(
        mean = List(Params.PHASE_BINS) { 100f },
        sigma = List(Params.PHASE_BINS, sigma),
        derivative = List(Params.PHASE_BINS) { 0f },
        rom = 80.0,
        medianSigmaRaw = 4.0,
    )

    @Test
    fun `the critical window is where the physio was most controlled`() {
        // Sigma tightest at mid-rep and widening either side, which is what a real end-range hold
        // looks like. The lowest tercile is a third of the bins by definition, so the window is the
        // band around the minimum rather than only the few tightest bins.
        val window = criticalWindow(reference { 2f + 6f * kotlin.math.abs(it - 50) / 50f })
        assertEquals(33..67, window)
    }

    @Test
    fun `a near-uniform reference falls back to the default window`() {
        val window = criticalWindow(reference { 5f })
        assertEquals(
            Params.CRITICAL_WINDOW_DEFAULT_START..Params.CRITICAL_WINDOW_DEFAULT_END,
            window,
        )
    }

    @Test
    fun `the longest tight run wins over a shorter one`() {
        // 34 tight bins in two runs, so the tercile threshold lands exactly on them.
        val window = criticalWindow(reference { if (it in 10..17 || it in 50..75) 1f else 9f })
        assertEquals(50..75, window)
    }

    @Test
    fun `frames inside the critical window count double`() {
        val critical = 40..60
        assertEquals(2.0, phaseWeight(50.0, critical), 1e-9)
        assertEquals(1.0, phaseWeight(20.0, critical), 1e-9)
        assertEquals(2.0, phaseWeight(39.6, critical), 1e-9)

        // A rep good everywhere except the window must score worse than one bad only outside it.
        val badInside = repScore(
            listOf(ScoredFrame(50.0, 40.0), ScoredFrame(20.0, 100.0)),
            critical, 80.0, 80.0, 3.0, 3.0,
        )!!
        val badOutside = repScore(
            listOf(ScoredFrame(50.0, 100.0), ScoredFrame(20.0, 40.0)),
            critical, 80.0, 80.0, 3.0, 3.0,
        )!!
        assertTrue(badInside < badOutside)
    }

    // -------------------------------------------------------------------------------------
    // §6.17.3 penalties
    // -------------------------------------------------------------------------------------

    @Test
    fun `ninety-five percent of reference range earns full credit`() {
        assertEquals(1.0, romPenalty(76.0, 80.0), 1e-9)
        assertEquals(1.0, romPenalty(80.0, 80.0), 1e-9)
        assertEquals(1.0, romPenalty(95.0, 80.0), 1e-9)
    }

    @Test
    fun `a patient who barely moves loses credit proportionally`() {
        // The failure §6.17.3 calls the most common in form-scoring apps: half the range must not
        // score like full range just because the band was never left.
        assertEquals(0.5263, romPenalty(40.0, 80.0), 1e-4)
        assertEquals(0.0, romPenalty(0.0, 80.0), 1e-9)
    }

    @Test
    fun `tempo inside the band is not penalised`() {
        assertEquals(1.0, tempoPenalty(3.0, 3.0), 1e-9)
        assertEquals(1.0, tempoPenalty(2.1, 3.0), 1e-9)
        assertEquals(1.0, tempoPenalty(4.8, 3.0), 1e-9)
    }

    @Test
    fun `rushing is penalised harder than going slow`() {
        val twiceAsFast = tempoPenalty(1.5, 3.0)
        val twiceAsSlow = tempoPenalty(6.0, 3.0)
        assertEquals(0.7143, twiceAsFast, 1e-4)
        assertEquals(0.8, twiceAsSlow, 1e-9)
        assertTrue("momentum reps must cost more than laboured ones", twiceAsFast < twiceAsSlow)
    }

    @Test
    fun `tempo penalties bottom out at their floors`() {
        assertEquals(Params.TEMPO_FAST_PENALTY_FLOOR, tempoPenalty(0.3, 3.0), 1e-9)
        assertEquals(Params.TEMPO_SLOW_PENALTY_FLOOR, tempoPenalty(30.0, 3.0), 1e-9)
    }

    @Test
    fun `a short rep scores below an identical full-range rep`() {
        val frames = List(20) { ScoredFrame(it * 5.0, 90.0) }
        val full = repScore(frames, 40..60, 80.0, 80.0, 3.0, 3.0)!!
        val short = repScore(frames, 40..60, 40.0, 80.0, 3.0, 3.0)!!
        assertEquals(90.0, full, 1e-9)
        assertTrue(short < full)
        assertEquals(90.0 * 0.5263, short, 0.05)
    }

    @Test
    fun `a rep with no scorable frame has no score`() {
        assertNull(repScore(emptyList(), 40..60, 80.0, 80.0, 3.0, 3.0))
    }

    // -------------------------------------------------------------------------------------
    // §6.17.4 set score
    // -------------------------------------------------------------------------------------

    @Test
    fun `one bad rep does not define the set`() {
        val reps = listOf(88.0, 91.0, 12.0, 90.0, 89.0).map { ScoredRep(it, valid = true) }
        val result = setScore(reps, prescribedReps = 5)
        assertEquals(89.0, result.score!!, 1e-9)
        assertEquals(1.0, result.completionRate, 1e-9)
    }

    @Test
    fun `partial reps are excluded from the median and from completion`() {
        val reps = listOf(
            ScoredRep(90.0, valid = true),
            ScoredRep(20.0, valid = false),
            ScoredRep(88.0, valid = true),
            ScoredRep(10.0, valid = false),
            ScoredRep(92.0, valid = true),
        )
        val result = setScore(reps, prescribedReps = 5)
        assertEquals(90.0, result.score!!, 1e-9)
        assertEquals(3, result.validReps)
        assertEquals(0.6, result.completionRate, 1e-9)
    }

    @Test
    fun `an even number of valid reps takes the midpoint`() {
        val reps = listOf(80.0, 90.0).map { ScoredRep(it, valid = true) }
        assertEquals(85.0, setScore(reps, 2).score!!, 1e-9)
    }

    @Test
    fun `a set with no valid rep reports no score rather than zero`() {
        val reps = List(4) { ScoredRep(30.0, valid = false) }
        val result = setScore(reps, prescribedReps = 12)
        assertNull(result.score)
        assertEquals(0.0, result.completionRate, 1e-9)
    }
}
