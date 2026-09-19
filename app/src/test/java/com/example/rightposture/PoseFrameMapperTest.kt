package com.example.rightposture

import com.example.rightposture.core.pose.POSE_LANDMARK_COUNT
import com.example.rightposture.pose.RawPoseLandmark
import com.example.rightposture.pose.mapPoseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoseFrameMapperTest {
    @Test
    fun mapsAllLandmarksAndImageConfidences() {
        val world = List(POSE_LANDMARK_COUNT) { RawPoseLandmark(it.toFloat(), it + 0.1f, it + 0.2f) }
        val image = List(POSE_LANDMARK_COUNT) {
            RawPoseLandmark(it + 0.3f, it + 0.4f, it + 0.5f, it / 100f, it / 200f)
        }

        val result = mapPoseFrame(world, image)!!

        assertEquals(POSE_LANDMARK_COUNT, result.worldLandmarks.size)
        assertEquals(POSE_LANDMARK_COUNT, result.imageLandmarks.size)
        assertEquals(world.map { it.x }, result.worldLandmarks.map { it.x })
        assertEquals(image.map { it.z }, result.imageLandmarks.map { it.z })
        assertEquals(image.map { it.visibility }, result.visibility)
        assertEquals(image.map { it.presence }, result.presence)
    }

    @Test
    fun rejectsIncompletePose() {
        val full = List(POSE_LANDMARK_COUNT) { RawPoseLandmark(0f, 0f, 0f) }

        assertNull(mapPoseFrame(full.dropLast(1), full))
        assertNull(mapPoseFrame(full, full.dropLast(1)))
    }
}
