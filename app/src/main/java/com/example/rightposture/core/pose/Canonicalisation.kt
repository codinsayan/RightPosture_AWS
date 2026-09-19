package com.example.rightposture.core.pose

enum class CameraFacing { FRONT, REAR }

/**
 * The physio's recording geometry, carried in the reference manifest (§6.2).
 *
 * This is *not* an instruction to the patient's session about which camera to use. It describes
 * only how the reference was captured, so that the reference can be canonicalised once at build
 * time. Kept a separate type from [LiveCameraMetadata] so the two can never be passed
 * interchangeably.
 */
data class RecordingCameraMetadata(
    val cameraFacing: CameraFacing,
    val mirrored: Boolean,
)

/** The patient session's own camera geometry, read from CameraX at runtime (§6.2). */
data class LiveCameraMetadata(
    val cameraFacing: CameraFacing,
    val mirrored: Boolean,
)

fun canonicalise(frame: LandmarkFrame, metadata: RecordingCameraMetadata) =
    canonicalise(frame, metadata.mirrored)

fun canonicalise(frame: LandmarkFrame, metadata: LiveCameraMetadata) =
    canonicalise(frame, metadata.mirrored)

/**
 * §6.2: a mirrored feed needs both operations. Negating x without relabelling leaves a left knee
 * sitting at the right knee's position.
 *
 * World landmarks are metric and hip-centred, so mirroring is `x = -x`. Image landmarks are
 * normalised to [0, 1] for drawing only, so the same reflection there is `x = 1 - x`.
 */
private fun canonicalise(frame: LandmarkFrame, mirrored: Boolean): LandmarkFrame {
    if (!mirrored) return frame
    return LandmarkFrame(
        worldLandmarks = remap(frame.worldLandmarks) { it.copy(x = -it.x) },
        imageLandmarks = remap(frame.imageLandmarks) { it.copy(x = 1f - it.x) },
        visibility = remap(frame.visibility) { it },
        presence = remap(frame.presence) { it },
    )
}

private inline fun <T> remap(values: List<T>, transform: (T) -> T) =
    List(POSE_LANDMARK_COUNT) { index -> transform(values[MIRRORED_INDEX[index]]) }

/**
 * Index permutation that swaps every LEFT_* label with its RIGHT_* counterpart. It is its own
 * inverse, which is what makes canonicalising twice a no-op.
 */
val MIRRORED_INDEX: IntArray = IntArray(POSE_LANDMARK_COUNT) { it }.also { indices ->
    PoseLandmark.LATERAL_PAIRS.forEach { (left, right) ->
        indices[left] = right
        indices[right] = left
    }
}
