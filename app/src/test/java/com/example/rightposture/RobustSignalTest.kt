package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.pose.Side
import com.example.rightposture.core.reference.rejectIsolatedOutliers
import com.example.rightposture.core.reference.resolveActiveSide
import com.example.rightposture.core.reference.bridgeShortGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RobustSignalTest {
    @Test
    fun isolatedLandmarkJumpIsReplaced() {
        val values = MutableList(21) { 40f }.also { it[10] = 120f }

        assertEquals(40f, rejectIsolatedOutliers(values)[10], 0.001f)
    }

    @Test
    fun sustainedMovementIsNotFlattened() {
        val values = MutableList(21) { 40f }.also { list ->
            (7..13).forEach { list[it] = 100f }
        }

        assertEquals(100f, rejectIsolatedOutliers(values)[10], 0.001f)
    }

    @Test
    fun briefDropoutIsInterpolated() {
        assertEquals(
            listOf(0f, 10f, 20f, 30f),
            bridgeShortGaps(listOf(0f, null, null, 30f), maxGapSamples = 2),
        )
    }

    @Test
    fun longDropoutIsRejected() {
        assertNull(bridgeShortGaps(listOf(0f, null, null, null, 40f), maxGapSamples = 2))
    }

    @Test
    fun movingLeftLimbBecomesActiveSide() {
        val left = List(120) { index ->
            mapOf(FeatureId.ELBOW_FLEX_ACTIVE to (70f + 55f * sin(2.0 * PI * index / 30.0)).toFloat())
        }
        val right = List(120) { index ->
            mapOf(FeatureId.ELBOW_FLEX_ACTIVE to (90f + sin(2.0 * PI * index / 30.0)).toFloat())
        }

        assertEquals(Side.LEFT, resolveActiveSide(mapOf(Side.LEFT to left, Side.RIGHT to right)))
    }

    @Test
    fun similarBilateralMovementKeepsDeclaredFallback() {
        val movement = List(30) { index ->
            mapOf(FeatureId.KNEE_FLEX_ACTIVE to (90f + index % 10).toFloat())
        }

        assertEquals(Side.RIGHT, resolveActiveSide(mapOf(Side.LEFT to movement, Side.RIGHT to movement)))
    }
}
