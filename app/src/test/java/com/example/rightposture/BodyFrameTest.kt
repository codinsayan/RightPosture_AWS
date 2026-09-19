package com.example.rightposture

import com.example.rightposture.core.pose.BodyFrameStatus
import com.example.rightposture.core.pose.BodyFrameTracker
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.POSE_LANDMARK_COUNT
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BodyFrameTest {
    @Test
    fun basisIsOrthonormal() {
        val basis = build(uprightFrame())!!

        listOf(basis.xAxis, basis.yAxis, basis.zAxis).forEach {
            assertEquals(1f, it.length(), 0.0001f)
        }
        assertEquals(0f, basis.xAxis dot basis.yAxis, 0.0001f)
        assertEquals(0f, basis.yAxis dot basis.zAxis, 0.0001f)
        assertEquals(0f, basis.xAxis dot basis.zAxis, 0.0001f)
    }

    @Test
    fun uprightPoseMapsWorldAxesOntoBodyAxes() {
        val basis = build(uprightFrame())!!

        // Hips span x, torso runs up y, so the basis should be the world axes.
        assertVec(Vec3(1f, 0f, 0f), basis.toBody(Vec3(1f, 0f, 0f)))
        assertVec(Vec3(0f, 1f, 0f), basis.toBody(Vec3(0f, 1f, 0f)))
        assertVec(Vec3(0f, 0f, 1f), basis.toBody(Vec3(0f, 0f, 1f)))
    }

    @Test
    fun angleIsInvariantToWhichWayThePatientFaces() {
        val facingForward = build(uprightFrame())!!
        val turnedNinety = build(uprightFrame(yawRadians = Math.PI / 2))!!
        val armInWorld = Vec3(0.3f, 0.4f, 0f)

        // Same arm relative to the body, rotated in the world: identical in body coordinates.
        val rotatedArm = Vec3(0f, 0.4f, -0.3f)
        assertVec(facingForward.toBody(armInWorld), turnedNinety.toBody(rotatedArm))
    }

    @Test
    fun translationDoesNotChangeTheBasis() {
        val here = build(uprightFrame())!!
        val overThere = build(uprightFrame(offset = Vec3(5f, -2f, 3f)))!!

        assertVec(here.xAxis, overThere.xAxis)
        assertVec(here.yAxis, overThere.yAxis)
        assertVec(here.zAxis, overThere.zAxis)
    }

    @Test
    fun tiltedPelvisIsOrthogonalisedRatherThanTrusted() {
        // Hips deliberately not perpendicular to the torso axis.
        val basis = build(uprightFrame(hipTilt = 0.15f))!!

        assertEquals(0f, basis.xAxis dot basis.yAxis, 0.0001f)
        assertEquals(1f, basis.xAxis.length(), 0.0001f)
    }

    @Test
    fun collapsedTorsoIsDegenerate() {
        assertNull(build(uprightFrame(torsoLength = 0.09f)))
        assertNotNull(build(uprightFrame(torsoLength = 0.11f)))
    }

    @Test
    fun edgeOnHipsAreDegenerate() {
        assertNull(build(uprightFrame(hipWidth = 0.04f)))
        assertNotNull(build(uprightFrame(hipWidth = 0.06f)))
    }

    @Test
    fun lowVisibilityOnAnyOfTheFourLandmarksIsDegenerate() {
        listOf(
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
        ).forEach { index ->
            assertNull(build(uprightFrame(lowVisibilityAt = index)))
        }
        assertNotNull(build(uprightFrame(lowVisibilityAt = PoseLandmark.LEFT_ANKLE)))
    }

    @Test
    fun nonFiniteLandmarkIsDegenerate() {
        assertNull(build(uprightFrame(offset = Vec3(Float.NaN, 0f, 0f))))
    }

    @Test
    fun degenerateFrameReusesPreviousBasisWithinHalfASecond() {
        val tracker = BodyFrameTracker()
        val good = tracker.update(uprightFrame(), 0)
        assertEquals(BodyFrameStatus.FRESH, good.status)

        val reused = tracker.update(uprightFrame(torsoLength = 0.05f), ms(400))

        assertEquals(BodyFrameStatus.REUSED, reused.status)
        assertSame(good.frame, reused.frame)
    }

    @Test
    fun reuseExpiresAfterHalfASecond() {
        val tracker = BodyFrameTracker()
        tracker.update(uprightFrame(), 0)

        assertEquals(BodyFrameStatus.REUSED, tracker.update(collapsed(), ms(500)).status)
        val expired = tracker.update(collapsed(), ms(501))

        assertEquals(BodyFrameStatus.INVALID, expired.status)
        assertNull(expired.frame)
    }

    @Test
    fun reuseWindowRestartsFromTheLastGoodFrame() {
        val tracker = BodyFrameTracker()
        tracker.update(uprightFrame(), 0)
        tracker.update(collapsed(), ms(400))
        tracker.update(uprightFrame(), ms(800))

        assertEquals(BodyFrameStatus.REUSED, tracker.update(collapsed(), ms(1100)).status)
    }

    @Test
    fun degenerateBeforeAnyGoodFrameIsInvalid() {
        assertEquals(BodyFrameStatus.INVALID, BodyFrameTracker().update(collapsed(), 0).status)
    }

    private fun ms(value: Long) = value * 1_000_000L

    private fun collapsed() = uprightFrame(torsoLength = 0.05f)

    private fun assertVec(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 0.0001f)
        assertEquals(expected.y, actual.y, 0.0001f)
        assertEquals(expected.z, actual.z, 0.0001f)
    }

    /**
     * Upright person: hips spanning world x, shoulders directly above. [yawRadians] turns them
     * about the world y axis, the way facing a different direction would.
     */
    private fun uprightFrame(
        torsoLength: Float = 0.5f,
        hipWidth: Float = 0.3f,
        hipTilt: Float = 0f,
        yawRadians: Double = 0.0,
        offset: Vec3 = Vec3(0f, 0f, 0f),
        lowVisibilityAt: Int = -1,
    ): LandmarkFrame {
        val half = hipWidth / 2f
        val points = mutableMapOf(
            PoseLandmark.LEFT_HIP to Vec3(-half, -hipTilt, 0f),
            PoseLandmark.RIGHT_HIP to Vec3(half, hipTilt, 0f),
            PoseLandmark.LEFT_SHOULDER to Vec3(-half, torsoLength, 0f),
            PoseLandmark.RIGHT_SHOULDER to Vec3(half, torsoLength, 0f),
        )
        val cos = Math.cos(yawRadians).toFloat()
        val sin = Math.sin(yawRadians).toFloat()
        return LandmarkFrame(
            worldLandmarks = List(POSE_LANDMARK_COUNT) { index ->
                val p = points[index] ?: Vec3(0f, 0f, 0f)
                Vec3(p.x * cos + p.z * sin, p.y, -p.x * sin + p.z * cos) + offset
            },
            imageLandmarks = List(POSE_LANDMARK_COUNT) { Vec3(0f, 0f, 0f) },
            visibility = List(POSE_LANDMARK_COUNT) { if (it == lowVisibilityAt) 0.4f else 0.9f },
            presence = List(POSE_LANDMARK_COUNT) { 0.9f },
        )
    }
}

private operator fun Vec3.plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
