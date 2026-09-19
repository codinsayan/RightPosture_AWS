package com.example.rightposture.core.pose

import com.example.rightposture.core.Params
import kotlin.math.sqrt

const val POSE_LANDMARK_COUNT = 33

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vec3(x * scalar, y * scalar, z * scalar)

    infix fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z

    infix fun cross(other: Vec3) = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun length() = sqrt(this dot this)
}

data class LandmarkFrame(
    val worldLandmarks: List<Vec3>,
    val imageLandmarks: List<Vec3>,
    val visibility: List<Float>,
    val presence: List<Float>,
) {
    init {
        require(worldLandmarks.size == POSE_LANDMARK_COUNT)
        require(imageLandmarks.size == POSE_LANDMARK_COUNT)
        require(visibility.size == POSE_LANDMARK_COUNT)
        require(presence.size == POSE_LANDMARK_COUNT)
    }
}

sealed interface FrameTiming {
    data class Continuous(val dtSeconds: Double) : FrameTiming
    data object Gap : FrameTiming
}

fun frameTiming(previousTimestampNs: Long, timestampNs: Long): FrameTiming {
    val dtSeconds = (timestampNs - previousTimestampNs) / 1_000_000_000.0
    return if (dtSeconds <= 0.0 || dtSeconds > Params.MAX_FRAME_GAP_SECONDS) {
        FrameTiming.Gap
    } else {
        FrameTiming.Continuous(dtSeconds)
    }
}
