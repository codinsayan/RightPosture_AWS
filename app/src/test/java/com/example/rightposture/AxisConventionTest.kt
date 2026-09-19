package com.example.rightposture

import com.example.rightposture.pose.Axis
import com.example.rightposture.pose.AxisConvention
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.pose.AxisReading
import com.example.rightposture.pose.inferAxis
import com.example.rightposture.pose.inferAxisResult
import com.example.rightposture.pose.movingLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisConventionTest {
    @Test
    fun verifiedConventionMapsCanonicalAxesToPositiveSemantics() {
        val movement = Vec3(-0.4f, -0.5f, -0.6f)

        assertTrue(AxisConvention.VERIFIED)
        assertEquals(0.5f, AxisConvention.UP.component(movement), 0.0001f)
        assertEquals(0.4f, AxisConvention.ANATOMICAL_RIGHT.component(movement), 0.0001f)
        assertEquals(0.6f, AxisConvention.TOWARD_CAMERA.component(movement), 0.0001f)
        assertEquals("RIGHT", AxisConvention.REAR_PHYSICAL_RIGHT_LABEL)
        assertEquals("LEFT", AxisConvention.FRONT_PHYSICAL_RIGHT_LABEL)
    }

    @Test
    fun findsLargestChangedAxisAndSign() {
        val result = inferAxis(Vec3(0f, 0f, 0f), Vec3(0.01f, -0.20f, 0.02f))

        assertEquals(Axis.Y, result?.axis)
        assertEquals(-1, result?.sign)
    }

    @Test
    fun rejectsMovementBelowMinimumDelta() {
        assertNull(inferAxis(Vec3(0f, 0f, 0f), Vec3(0.01f, 0.02f, 0.01f)))
    }

    @Test
    fun rejectsAxisWhenTwoComponentsAreTooSimilar() {
        assertNull(inferAxis(Vec3(0f, 0f, 0f), Vec3(0.10f, 0.09f, 0f)))
    }

    @Test
    fun identifiesWhichLandmarkLabelMoved() {
        val still = reading(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f))
        val moved = reading(Vec3(0.2f, 0f, 0f), Vec3(0.01f, 0f, 0f))
        val captures = listOf(still, moved, still, moved, still, moved)

        assertEquals("RIGHT", movingLabel(captures))
    }

    @Test
    fun mirroredPhysicalRightUsesLeftLandmarksForAxes() {
        val zero = Vec3(0f, 0f, 0f)
        val leftMoved = reading(zero, Vec3(0f, -0.2f, 0f))
        val rightMoved = reading(zero, Vec3(0.2f, 0f, 0f))
        val depthMoved = reading(zero, Vec3(0f, 0f, -0.2f))
        val still = reading(zero, zero)

        val result = inferAxisResult(listOf(still, leftMoved, still, rightMoved, still, depthMoved))

        assertEquals("LEFT", result?.movingLabel)
        assertEquals(Axis.Y, result?.up?.axis)
        assertEquals(Axis.X, result?.anatomicalRight?.axis)
        assertEquals(Axis.Z, result?.towardCamera?.axis)
    }

    @Test
    fun captureRequiresShouldersAndWristsInsideImage() {
        val zero = Vec3(0f, 0f, 0f)
        assertTrue(reading(zero, zero).captureReady)
        val points = MutableList(33) { Vec3(0.5f, 0.5f, 0f) }.also { it[16] = Vec3(-0.1f, 0.5f, 0f) }
        assertFalse(reading(zero, zero, points).captureReady)
    }

    private fun reading(
        right: Vec3,
        left: Vec3,
        points: List<Vec3> = List(33) { Vec3(0.5f, 0.5f, 0f) },
    ) = AxisReading(
        wristWorld = right,
        shoulderWorld = Vec3(0f, 0f, 0f),
        leftWristWorld = left,
        leftShoulderWorld = Vec3(0f, 0f, 0f),
        wristImage = Vec3(0f, 0f, 0f),
        imageLandmarks = points,
        imageWidth = 480,
        imageHeight = 640,
    )

}
