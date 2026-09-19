package com.example.rightposture

import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.jointAngleDegrees
import com.example.rightposture.core.pose.signedPlanarAngleDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AnglesTest {
    private val origin = Vec3(0f, 0f, 0f)
    private val zAxis = Vec3(0f, 0f, 1f)

    @Test
    fun rightAngleIsNinetyDegrees() {
        val angle = jointAngleDegrees(Vec3(1f, 0f, 0f), origin, Vec3(0f, 1f, 0f))

        assertEquals(90f, angle!!, 0.0001f)
    }

    @Test
    fun straightLimbIsOneHundredEighty() {
        val angle = jointAngleDegrees(Vec3(-1f, 0f, 0f), origin, Vec3(1f, 0f, 0f))

        assertEquals(180f, angle!!, 0.0001f)
    }

    @Test
    fun nearFullExtensionStaysAccurate() {
        // The acos form loses several degrees here on float32. atan2 must not.
        listOf(179.0, 179.5, 179.9).forEach { expected ->
            val radians = Math.toRadians(expected)
            val c = Vec3(-cos(radians).toFloat(), sin(radians).toFloat(), 0f)

            val angle = jointAngleDegrees(Vec3(-1f, 0f, 0f), origin, c)

            assertEquals(expected.toFloat(), angle!!, 0.01f)
        }
    }

    @Test
    fun angleIsUnsignedSoMirroredNeighbourGivesSameValue() {
        val a = Vec3(1f, 0f, 0f)

        val above = jointAngleDegrees(a, origin, Vec3(1f, 1f, 0f))
        val below = jointAngleDegrees(a, origin, Vec3(1f, -1f, 0f))

        assertEquals(45f, above!!, 0.0001f)
        assertEquals(above, below!!, 0f)
    }

    @Test
    fun zeroLengthLimbIsInvalid() {
        assertNull(jointAngleDegrees(origin, origin, Vec3(1f, 0f, 0f)))
        assertNull(jointAngleDegrees(Vec3(1f, 0f, 0f), origin, origin))
        assertNull(jointAngleDegrees(Vec3(1e-9f, 0f, 0f), origin, Vec3(1f, 0f, 0f)))
    }

    @Test
    fun nonFiniteLandmarkIsInvalid() {
        assertNull(jointAngleDegrees(Vec3(Float.NaN, 0f, 0f), origin, Vec3(1f, 0f, 0f)))
        assertNull(jointAngleDegrees(Vec3(Float.POSITIVE_INFINITY, 0f, 0f), origin, Vec3(1f, 0f, 0f)))
        assertNull(signedPlanarAngleDegrees(Vec3(Float.NaN, 0f, 0f), Vec3(1f, 0f, 0f), zAxis))
    }

    @Test
    fun signedAngleCarriesDirection() {
        val reference = Vec3(1f, 0f, 0f)

        val counterClockwise = signedPlanarAngleDegrees(reference, Vec3(1f, 1f, 0f), zAxis)
        val clockwise = signedPlanarAngleDegrees(reference, Vec3(1f, -1f, 0f), zAxis)

        assertEquals(45f, counterClockwise!!, 0.0001f)
        assertEquals(-45f, clockwise!!, 0.0001f)
    }

    @Test
    fun signedAngleFlipsWithNormalDirection() {
        val angle = signedPlanarAngleDegrees(Vec3(1f, 0f, 0f), Vec3(1f, 1f, 0f), Vec3(0f, 0f, -1f))

        assertEquals(-45f, angle!!, 0.0001f)
    }

    @Test
    fun outOfPlaneComponentIsProjectedAway() {
        val angle = signedPlanarAngleDegrees(Vec3(1f, 0f, 5f), Vec3(1f, 1f, -3f), zAxis)

        assertEquals(45f, angle!!, 0.0001f)
    }

    @Test
    fun unnormalisedNormalGivesSameAnswer() {
        val angle = signedPlanarAngleDegrees(Vec3(1f, 0f, 0f), Vec3(1f, 1f, 0f), Vec3(0f, 0f, 7f))

        assertEquals(45f, angle!!, 0.0001f)
    }

    @Test
    fun probableWrapBeyondNinetyDegreesIsInvalid() {
        assertEquals(
            90f,
            signedPlanarAngleDegrees(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), zAxis)!!,
            0.0001f,
        )
        assertNull(signedPlanarAngleDegrees(Vec3(1f, 0f, 0f), Vec3(-1f, 1f, 0f), zAxis))
        assertNull(signedPlanarAngleDegrees(Vec3(1f, 0f, 0f), Vec3(-1f, -1f, 0f), zAxis))
        assertNull(signedPlanarAngleDegrees(Vec3(1f, 0f, 0f), Vec3(-1f, 0f, 0f), zAxis))
    }

    @Test
    fun degenerateVectorsAreInvalid() {
        assertNull(signedPlanarAngleDegrees(Vec3(1f, 0f, 0f), Vec3(1f, 0f, 0f), origin))
        // Wholly out of plane: nothing left after projection.
        assertNull(signedPlanarAngleDegrees(Vec3(0f, 0f, 3f), Vec3(1f, 0f, 0f), zAxis))
    }
}
