package com.example.rightposture

import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.OneEuroFilter
import com.example.rightposture.core.pose.OneEuroLandmarkFilter
import com.example.rightposture.core.pose.POSE_LANDMARK_COUNT
import com.example.rightposture.core.pose.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class OneEuroFilterTest {
    @Test
    fun noisySineHighFrequencyJitterDrops() {
        val filter = OneEuroFilter()
        val input = List(180) {
            (0.2 * sin(2.0 * PI * it / 120.0) + if (it % 2 == 0) -0.02 else 0.02).toFloat()
        }
        val output = input.map { filter.filter(it, 1.0 / 30.0) }

        assertTrue(roughness(output.drop(30)) < roughness(input.drop(30)) * 0.25)
    }

    @Test
    fun betaTracksFastMovementWithoutOvershoot() {
        val adaptive = OneEuroFilter(beta = 0.02)
        val static = OneEuroFilter(beta = 0.0)
        repeat(10) {
            adaptive.filter(0f, 1.0 / 30.0)
            static.filter(0f, 1.0 / 30.0)
        }

        val adaptiveOutput = adaptive.filter(1f, 1.0 / 30.0)
        val staticOutput = static.filter(1f, 1.0 / 30.0)

        assertTrue(adaptiveOutput > staticOutput)
        assertTrue(adaptiveOutput in 0f..1f)
    }

    @Test
    fun gapAndRedetectionResetAllNinetyNineChannels() {
        val filter = OneEuroLandmarkFilter()
        filter.filter(frame(0f), 1_000_000_000L)
        filter.filter(frame(1f), 1_033_333_333L)

        val afterGap = filter.filter(frame(3f), 1_300_000_001L)
        assertTrue(afterGap.worldLandmarks.all { it == Vec3(3f, 4f, 5f) })

        filter.filter(frame(4f), 1_333_333_334L)
        val afterRedetection = filter.filter(frame(7f), 1_366_666_667L, personRedetected = true)
        assertTrue(afterRedetection.worldLandmarks.all { it == Vec3(7f, 8f, 9f) })
    }

    @Test
    fun confidenceAndImageChannelsRemainUnchanged() {
        val original = frame(1f)
        val result = OneEuroLandmarkFilter().filter(original, 1_000_000_000L)

        assertEquals(original.imageLandmarks, result.imageLandmarks)
        assertEquals(original.visibility, result.visibility)
        assertEquals(original.presence, result.presence)
    }

    private fun frame(value: Float) = LandmarkFrame(
        worldLandmarks = List(POSE_LANDMARK_COUNT) { Vec3(value, value + 1f, value + 2f) },
        imageLandmarks = List(POSE_LANDMARK_COUNT) { Vec3(0.5f, 0.5f, 0f) },
        visibility = List(POSE_LANDMARK_COUNT) { 0.9f },
        presence = List(POSE_LANDMARK_COUNT) { 0.8f },
    )

    private fun roughness(values: List<Float>) =
        values.windowed(3).sumOf { abs(it[2] - 2f * it[1] + it[0]).toDouble() }
}
