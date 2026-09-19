package com.example.rightposture.pose

import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.POSE_LANDMARK_COUNT
import com.example.rightposture.core.pose.Vec3

data class RawPoseLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float = 0f,
    val presence: Float = 0f,
)

fun mapPoseFrame(
    world: List<RawPoseLandmark>,
    image: List<RawPoseLandmark>,
): LandmarkFrame? {
    if (world.size != POSE_LANDMARK_COUNT || image.size != POSE_LANDMARK_COUNT) return null
    return LandmarkFrame(
        worldLandmarks = world.map { Vec3(it.x, it.y, it.z) },
        imageLandmarks = image.map { Vec3(it.x, it.y, it.z) },
        visibility = image.map(RawPoseLandmark::visibility),
        presence = image.map(RawPoseLandmark::presence),
    )
}
