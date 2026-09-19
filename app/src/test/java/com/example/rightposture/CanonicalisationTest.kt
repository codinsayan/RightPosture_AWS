package com.example.rightposture

import com.example.rightposture.core.pose.CameraFacing
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.LiveCameraMetadata
import com.example.rightposture.core.pose.MIRRORED_INDEX
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.POSE_LANDMARK_COUNT
import com.example.rightposture.core.pose.RecordingCameraMetadata
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.canonicalise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CanonicalisationTest {
    private val mirroredRecording = RecordingCameraMetadata(CameraFacing.FRONT, mirrored = true)

    @Test
    fun mirroringTwiceRestoresOriginalFrame() {
        val original = frame()

        val restored = canonicalise(canonicalise(original, mirroredRecording), mirroredRecording)

        assertEquals(original.worldLandmarks, restored.worldLandmarks)
        assertEquals(original.visibility, restored.visibility)
        assertEquals(original.presence, restored.presence)
        original.imageLandmarks.zip(restored.imageLandmarks).forEach { (expected, actual) ->
            assertEquals(expected.x, actual.x, 0.000001f)
            assertEquals(expected.y, actual.y, 0f)
            assertEquals(expected.z, actual.z, 0f)
        }
    }

    @Test
    fun mirroredFrameFlipsXAndSwapsAllAnatomicalChannels() {
        val original = frame()

        val result = canonicalise(original, mirroredRecording)
        val expectedOrder = listOf(
            0, 4, 5, 6, 1, 2, 3, 8, 7, 10, 9,
            12, 11, 14, 13, 16, 15, 18, 17, 20, 19, 22, 21,
            24, 23, 26, 25, 28, 27, 30, 29, 32, 31,
        )

        assertEquals(expectedOrder.map { -original.worldLandmarks[it].x }, result.worldLandmarks.map { it.x })
        assertEquals(expectedOrder.map { original.visibility[it] }, result.visibility)
        assertEquals(Vec3(-12f, 112f, 212f), result.worldLandmarks[11])
        assertEquals(Vec3(0.88f, 0.12f, 0.12f), result.imageLandmarks[11])
        assertEquals(0.12f, result.visibility[11])
        assertEquals(0.22f, result.presence[11])
        assertEquals(Vec3(-11f, 111f, 211f), result.worldLandmarks[12])
        assertEquals(Vec3(0.89f, 0.11f, 0.11f), result.imageLandmarks[12])
    }

    @Test
    fun recordingAndLiveMetadataAreAppliedIndependently() {
        val original = frame()
        val rearRecording = RecordingCameraMetadata(CameraFacing.REAR, mirrored = false)
        val frontSession = LiveCameraMetadata(CameraFacing.FRONT, mirrored = true)

        assertSame(original, canonicalise(original, rearRecording))
        assertEquals(canonicalise(original, mirroredRecording), canonicalise(original, frontSession))
    }

    @Test
    fun mirrorTableIsAnInvolutionOverAllThirtyThreeLandmarks() {
        assertEquals(POSE_LANDMARK_COUNT, MIRRORED_INDEX.size)
        assertEquals(POSE_LANDMARK_COUNT, MIRRORED_INDEX.toSortedSet().size)
        MIRRORED_INDEX.forEachIndexed { index, mapped ->
            assertEquals(index, MIRRORED_INDEX[mapped])
        }
    }

    @Test
    fun everyLandmarkExceptNoseIsLaterallyPaired() {
        val paired = PoseLandmark.LATERAL_PAIRS.flatMap { listOf(it.first, it.second) }

        assertEquals(POSE_LANDMARK_COUNT - 1, paired.size)
        assertEquals(POSE_LANDMARK_COUNT - 1, paired.toSet().size)
        assertEquals(PoseLandmark.NOSE, (0 until POSE_LANDMARK_COUNT).single { it !in paired })
        assertEquals(PoseLandmark.NOSE, MIRRORED_INDEX[PoseLandmark.NOSE])
    }

    @Test
    fun namedJointsSwapSidesUnderMirroring() {
        val original = frame()

        val result = canonicalise(original, mirroredRecording)

        assertEquals(
            original.worldLandmarks[PoseLandmark.RIGHT_KNEE].copy(
                x = -original.worldLandmarks[PoseLandmark.RIGHT_KNEE].x,
            ),
            result.worldLandmarks[PoseLandmark.LEFT_KNEE],
        )
        assertEquals(
            original.visibility[PoseLandmark.RIGHT_HIP],
            result.visibility[PoseLandmark.LEFT_HIP],
        )
    }

    private fun frame() = LandmarkFrame(
        worldLandmarks = List(POSE_LANDMARK_COUNT) { Vec3(it.toFloat(), (it + 100).toFloat(), (it + 200).toFloat()) },
        imageLandmarks = List(POSE_LANDMARK_COUNT) { Vec3(it / 100f, it / 100f, it / 100f) },
        visibility = List(POSE_LANDMARK_COUNT) { it / 100f },
        presence = List(POSE_LANDMARK_COUNT) { (it + 10) / 100f },
    )
}
