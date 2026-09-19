package com.example.rightposture

import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.POSE_LANDMARK_COUNT
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.classifyView
import com.example.rightposture.core.pose.viewStability
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewClassificationTest {
    @Test
    fun ratioClassifiesSagittalObliqueAndFrontalViews() {
        assertEquals(CameraView.SAGITTAL, classifyView(frame(0.24f)))
        assertEquals(CameraView.OBLIQUE, classifyView(frame(0.25f)))
        assertEquals(CameraView.OBLIQUE, classifyView(frame(0.35f)))
        assertEquals(CameraView.FRONTAL, classifyView(frame(0.36f)))
    }

    @Test
    fun ambiguousAndInvalidFramesCountAgainstStability() {
        val frames = List(9) { frame(0.5f) } + frame(0.3f)

        assertEquals(0.9, viewStability(frames), 1e-9)
        assertEquals(0.8, viewStability(List(8) { frame(0.5f) } + List(2) { frame(0.3f) }), 1e-9)
    }

    @Test
    fun lowConfidenceCannotClaimAStableView() {
        assertEquals(null, classifyView(frame(0.5f, confidence = 0.5f)))
        assertEquals(0.0, viewStability(List(4) { frame(0.5f, confidence = 0.5f) }), 0.0)
    }

    private fun frame(shoulderRatio: Float, confidence: Float = 0.99f): LandmarkFrame {
        val points = MutableList(POSE_LANDMARK_COUNT) { Vec3(0f, 0f, 0f) }
        points[PoseLandmark.LEFT_SHOULDER] = Vec3(-shoulderRatio / 2f, 0f, 0f)
        points[PoseLandmark.RIGHT_SHOULDER] = Vec3(shoulderRatio / 2f, 0f, 0f)
        points[PoseLandmark.LEFT_HIP] = Vec3(-0.15f, 1f, 0f)
        points[PoseLandmark.RIGHT_HIP] = Vec3(0.15f, 1f, 0f)
        return LandmarkFrame(
            worldLandmarks = points,
            imageLandmarks = points,
            visibility = List(POSE_LANDMARK_COUNT) { confidence },
            presence = List(POSE_LANDMARK_COUNT) { confidence },
        )
    }
}
