package com.example.rightposture

import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.SyntheticPoseConfig
import com.example.rightposture.core.pose.jointAngleDegrees
import com.example.rightposture.core.pose.syntheticKneeRepetitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticLandmarkGeneratorTest {
    @Test
    fun sameSeedReproducesNoiseAndDropout() {
        val config = SyntheticPoseConfig(
            repCount = 2,
            samplesPerRep = 10,
            noiseStandardDeviationMetres = 0.01f,
            frameDropoutProbability = 0.25,
            seed = 42,
        )

        assertEquals(syntheticKneeRepetitions(config), syntheticKneeRepetitions(config))
        assertNotEquals(
            syntheticKneeRepetitions(config),
            syntheticKneeRepetitions(config.copy(seed = 43)),
        )
    }

    @Test
    fun requestedRepCountAndRangeOfMotionAreMeasurable() {
        val config = SyntheticPoseConfig(
            repCount = 5,
            samplesPerRep = 20,
            minimumKneeAngleDegrees = 75f,
            maximumKneeAngleDegrees = 165f,
        )
        val angles = syntheticKneeRepetitions(config).map { sample ->
            val points = sample.frame.worldLandmarks
            jointAngleDegrees(
                points[PoseLandmark.RIGHT_HIP],
                points[PoseLandmark.RIGHT_KNEE],
                points[PoseLandmark.RIGHT_ANKLE],
            )!!
        }

        val peaks = angles.windowed(3).count { (before, value, after) ->
            value > before && value > after
        }
        assertEquals(config.repCount, peaks)
        assertEquals(90f, angles.max() - angles.min(), 0.01f)
        assertEquals(config.repCount * config.samplesPerRep + 1, angles.size)
    }

    @Test
    fun noiseAndWholeFrameDropoutAreInjectable() {
        val clean = syntheticKneeRepetitions(SyntheticPoseConfig(repCount = 1, samplesPerRep = 4))
        val noisy = syntheticKneeRepetitions(
            SyntheticPoseConfig(
                repCount = 1,
                samplesPerRep = 4,
                noiseStandardDeviationMetres = 0.01f,
                seed = 7,
            ),
        )
        val dropped = syntheticKneeRepetitions(
            SyntheticPoseConfig(repCount = 1, samplesPerRep = 4, frameDropoutProbability = 1.0),
        )

        assertNotEquals(clean.first().frame.worldLandmarks, noisy.first().frame.worldLandmarks)
        assertTrue(dropped.all { sample -> sample.frame.visibility.all { it == 0f } })
        assertTrue(dropped.all { sample -> sample.frame.presence.all { it == 0f } })
    }
}
