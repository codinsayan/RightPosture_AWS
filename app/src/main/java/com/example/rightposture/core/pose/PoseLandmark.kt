package com.example.rightposture.core.pose

/**
 * MediaPipe Pose Landmarker index constants (§6.2).
 *
 * Named so that the mirror table in [canonicalise] and the body-frame geometry in later
 * iterations read as anatomy rather than as magic integers.
 */
object PoseLandmark {
    const val NOSE = 0
    const val LEFT_EYE_INNER = 1
    const val LEFT_EYE = 2
    const val LEFT_EYE_OUTER = 3
    const val RIGHT_EYE_INNER = 4
    const val RIGHT_EYE = 5
    const val RIGHT_EYE_OUTER = 6
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val MOUTH_LEFT = 9
    const val MOUTH_RIGHT = 10
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_PINKY = 17
    const val RIGHT_PINKY = 18
    const val LEFT_INDEX = 19
    const val RIGHT_INDEX = 20
    const val LEFT_THUMB = 21
    const val RIGHT_THUMB = 22
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val LEFT_HEEL = 29
    const val RIGHT_HEEL = 30
    const val LEFT_FOOT_INDEX = 31
    const val RIGHT_FOOT_INDEX = 32

    /** Every (left, right) anatomical pair. Landmark 0 (nose) is unpaired. */
    val LATERAL_PAIRS: List<Pair<Int, Int>> = listOf(
        LEFT_EYE_INNER to RIGHT_EYE_INNER,
        LEFT_EYE to RIGHT_EYE,
        LEFT_EYE_OUTER to RIGHT_EYE_OUTER,
        LEFT_EAR to RIGHT_EAR,
        MOUTH_LEFT to MOUTH_RIGHT,
        LEFT_SHOULDER to RIGHT_SHOULDER,
        LEFT_ELBOW to RIGHT_ELBOW,
        LEFT_WRIST to RIGHT_WRIST,
        LEFT_PINKY to RIGHT_PINKY,
        LEFT_INDEX to RIGHT_INDEX,
        LEFT_THUMB to RIGHT_THUMB,
        LEFT_HIP to RIGHT_HIP,
        LEFT_KNEE to RIGHT_KNEE,
        LEFT_ANKLE to RIGHT_ANKLE,
        LEFT_HEEL to RIGHT_HEEL,
        LEFT_FOOT_INDEX to RIGHT_FOOT_INDEX,
    )
}
