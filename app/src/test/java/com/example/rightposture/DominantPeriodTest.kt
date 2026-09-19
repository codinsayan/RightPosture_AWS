package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.reference.estimatePeriod
import com.example.rightposture.core.reference.selectCoarseDominantFeature
import com.example.rightposture.core.reference.selectDominantFeatureAndPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class DominantPeriodTest {
    private val sampleRateHz = 30.0
    private val samplesPerRep = 60
    private val sampleCount = 8 * samplesPerRep

    @Test
    fun findsDominantFeatureAndFirstQualifyingPeriodPeak() {
        val knee = List(sampleCount) { index ->
            val alternatingAmplitude = if (index / samplesPerRep % 2 == 0) 40f else 25f
            alternatingAmplitude * sin(2.0 * PI * index / samplesPerRep).toFloat()
        }
        val signals = mapOf(
            FeatureId.KNEE_FLEX_ACTIVE to knee,
            FeatureId.TRUNK_FLEXION to List(sampleCount) { index ->
                3f * sin(2.0 * PI * index / samplesPerRep).toFloat()
            },
        )

        val dominant = selectCoarseDominantFeature(signals)
        val period = estimatePeriod(signals.getValue(dominant!!), sampleRateHz)!!

        assertEquals(FeatureId.KNEE_FLEX_ACTIVE, dominant)
        assertEquals(samplesPerRep, period.lagSamples)
        assertEquals(2.0, period.seconds, 0.0001)
    }

    @Test
    fun percentileSwingIgnoresSingleOutlier() {
        val knee = List(100) { index -> 30f * sin(2.0 * PI * index / 20.0).toFloat() }
        val contaminated = MutableList(100) { 0f }.also { it[50] = 1_000f }

        assertEquals(
            FeatureId.KNEE_FLEX_ACTIVE,
            selectCoarseDominantFeature(
                mapOf(
                    FeatureId.KNEE_FLEX_ACTIVE to knee,
                    FeatureId.TRUNK_FLEXION to contaminated,
                ),
            ),
        )
    }

    @Test
    fun skipsHighSwingAperiodicNoiseAndFindsPeriodicJoint() {
        val random = Random(19)
        val trunkNoise = List(sampleCount) { random.nextFloat() * 120f - 60f }
        val elbowCurl = List(sampleCount) { index ->
            35f * sin(2.0 * PI * index / samplesPerRep).toFloat()
        }
        val signals = mapOf(
            FeatureId.TRUNK_ROTATION to trunkNoise,
            FeatureId.ELBOW_FLEX_ACTIVE to elbowCurl,
        )

        assertEquals(FeatureId.TRUNK_ROTATION, selectCoarseDominantFeature(signals))
        assertEquals(
            FeatureId.ELBOW_FLEX_ACTIVE,
            selectDominantFeatureAndPeriod(signals, sampleRateHz)?.feature,
        )
    }

    @Test
    fun aperiodicSignalUsesDocumentedFallback() {
        val random = Random(7)
        val noise = List(600) { random.nextFloat() * 2f - 1f }

        assertNull(estimatePeriod(noise, sampleRateHz))
        assertNull(estimatePeriod(List(600) { 1f }, sampleRateHz))
    }

    @Test
    fun takesTheFirstQualifyingLocalMaximumNotTheGlobalOne() {
        // Every second rep carries an extra harmonic, so consecutive reps differ in *shape*. Lag T
        // then correlates less well than lag 2T, which matches like with like. A global-maximum
        // search would return 2T and report half the reps.
        val signal = List(sampleCount) { index ->
            val base = sin(2.0 * PI * index / samplesPerRep)
            val everyOtherRep = if (index / samplesPerRep % 2 == 0) 0.0 else 0.35
            (base + everyOtherRep * sin(4.0 * PI * index / samplesPerRep)).toFloat()
        }

        val period = estimatePeriod(signal, sampleRateHz)!!
        val atDouble = correlationAt(signal, 2 * samplesPerRep)

        assertEquals(samplesPerRep, period.lagSamples)
        assertTrue(
            "2T must out-correlate T for this test to mean anything: T=${period.correlation}, 2T=$atDouble",
            atDouble > period.correlation,
        )
    }

    @Test
    fun movementSlowerThanTheEightSecondBoundIsRejected() {
        // One rep per 12 s. No local maximum exists inside [0.5 s, 8 s], so the recording reads as
        // aperiodic and takes the fallback.
        val tooSlow = List(600) { index -> 30f * sin(2.0 * PI * index / 360.0).toFloat() }

        assertNull(estimatePeriod(tooSlow, sampleRateHz))
    }

    @Test
    fun movementFasterThanTheLowerBoundReportsAMultipleRatherThanAbstaining() {
        // 6 Hz, one rep every 5 samples: far under the 0.5 s lower bound. The bound does not reject
        // this. A signal of period 5 also correlates perfectly at every multiple of 5, and lag 15
        // (0.5 s) is the first one inside the search window, so that is what comes back.
        //
        // This is worth stating plainly: 6.7.2's lower bound limits what the search can *see*, it
        // does not screen out impossibly fast movement. The resulting rep count is wrong by a factor
        // of three here, and it is the 3.8 validator, not this function, that has to catch it.
        val tooFast = List(600) { index -> 30f * sin(2.0 * PI * index / 5.0).toFloat() }

        val period = estimatePeriod(tooFast, sampleRateHz)!!

        assertEquals(15, period.lagSamples)
        assertEquals(0.5, period.seconds, 0.0001)
    }

    @Test
    fun nonFiniteSamplesAbstainRatherThanCrash() {
        val clean = List(sampleCount) { index ->
            30f * sin(2.0 * PI * index / samplesPerRep).toFloat()
        }
        val withNaN = clean.toMutableList().also { it[100] = Float.NaN }

        assertNull(estimatePeriod(withNaN, sampleRateHz))
        assertNull(estimatePeriod(clean, Double.NaN))
        assertNull(estimatePeriod(clean, 0.0))
        assertEquals(
            FeatureId.KNEE_FLEX_ACTIVE,
            selectCoarseDominantFeature(
                mapOf(
                    FeatureId.KNEE_FLEX_ACTIVE to clean,
                    FeatureId.TRUNK_FLEXION to withNaN,
                ),
            ),
        )
    }

    @Test
    fun noSignalsMeansNoDominantFeature() {
        assertNull(selectCoarseDominantFeature(emptyMap()))
        assertNull(selectCoarseDominantFeature(mapOf(FeatureId.KNEE_FLEX_ACTIVE to listOf(1f))))
    }

    /** Same per-lag normalisation the estimator uses, for asserting where the global peak sits. */
    private fun correlationAt(signal: List<Float>, lag: Int): Double {
        val mean = signal.sumOf { it.toDouble() } / signal.size
        val centred = signal.map { it - mean }
        var product = 0.0
        var left = 0.0
        var right = 0.0
        for (index in 0 until centred.size - lag) {
            product += centred[index] * centred[index + lag]
            left += centred[index] * centred[index]
            right += centred[index + lag] * centred[index + lag]
        }
        return product / kotlin.math.sqrt(left * right)
    }
}
