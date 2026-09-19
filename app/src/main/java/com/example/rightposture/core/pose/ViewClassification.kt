package com.example.rightposture.core.pose

import com.example.rightposture.core.Params
import kotlin.math.sqrt

enum class CameraView { SAGITTAL, OBLIQUE, FRONTAL }

/** §6.20.2 distance-independent camera view classification in image coordinates. */
fun classifyView(frame: LandmarkFrame): CameraView? = viewRatio(frame)?.let { ratio ->
    when {
        ratio < Params.VIEW_RATIO_MIN -> CameraView.SAGITTAL
        ratio <= Params.VIEW_RATIO_MAX -> CameraView.OBLIQUE
        else -> CameraView.FRONTAL
    }
}

/**
 * `shoulderWidth / torsoHeight` in the image plane, or null when the four landmarks it needs are
 * not confident enough to trust. Exposed separately from [classifyView] so calibration can *show*
 * the number: "the camera is angled wrong" is not diagnosable on a phone without it.
 */
fun viewRatio(frame: LandmarkFrame): Double? {
    val required = intArrayOf(
        PoseLandmark.LEFT_SHOULDER,
        PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_HIP,
        PoseLandmark.RIGHT_HIP,
    )
    if (required.any {
            frame.visibility[it] < Params.VIS_MIN || frame.presence[it] < Params.PRESENCE_MIN
        }
    ) return null

    val leftShoulder = frame.imageLandmarks[PoseLandmark.LEFT_SHOULDER]
    val rightShoulder = frame.imageLandmarks[PoseLandmark.RIGHT_SHOULDER]
    val midShoulder = midpoint(leftShoulder, rightShoulder)
    val midHip = midpoint(
        frame.imageLandmarks[PoseLandmark.LEFT_HIP],
        frame.imageLandmarks[PoseLandmark.RIGHT_HIP],
    )
    val torsoHeight = distance2d(midShoulder, midHip)
    if (!torsoHeight.isFinite() || torsoHeight < Params.MIN_VECTOR_LENGTH) return null
    val ratio = distance2d(leftShoulder, rightShoulder) / torsoHeight
    if (!ratio.isFinite()) return null
    return ratio.toDouble()
}

/** Fraction of all frames agreeing on one unambiguous view; oblique/invalid frames count against it. */
fun viewStability(frames: List<LandmarkFrame>): Double {
    if (frames.isEmpty()) return 0.0
    val counts = frames.mapNotNull(::classifyView)
        .filter { it != CameraView.OBLIQUE }
        .groupingBy { it }
        .eachCount()
    return (counts.values.maxOrNull() ?: 0).toDouble() / frames.size
}

private fun midpoint(a: Vec3, b: Vec3) = Vec3((a.x + b.x) / 2f, (a.y + b.y) / 2f, 0f)

private fun distance2d(a: Vec3, b: Vec3): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
