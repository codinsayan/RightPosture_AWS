package com.example.rightposture.core.pose

import com.example.rightposture.core.Params

/**
 * §6.4 body reference frame. Rows of the world-to-body rotation `R = [x̂ᵀ; ŷᵀ; ẑᵀ]`.
 *
 * Makes every angle invariant to which way the patient faces and where they stand.
 */
data class BodyFrame(val xAxis: Vec3, val yAxis: Vec3, val zAxis: Vec3) {
    /** `v_body = R · v_world`. */
    fun toBody(v: Vec3) = Vec3(xAxis dot v, yAxis dot v, zAxis dot v)
}

enum class BodyFrameStatus {
    /** Built from this frame's landmarks. */
    FRESH,

    /** Degenerate landmarks; previous frame's `R` reused. Expected in sagittal views. */
    REUSED,

    /** No usable frame: degenerate for longer than the reuse limit, or none built yet. */
    INVALID,
}

data class BodyFrameResult(val frame: BodyFrame?, val status: BodyFrameStatus)

/**
 * Builds the body frame per landmark frame, applying §6.4's reuse rules across frames.
 *
 * Not thread-safe; one instance per capture stream.
 */
class BodyFrameTracker {
    private var last: BodyFrame? = null
    private var lastFreshNs: Long = 0

    fun update(frame: LandmarkFrame, timestampNs: Long): BodyFrameResult {
        val built = build(frame)
        if (built != null) {
            last = built
            lastFreshNs = timestampNs
            return BodyFrameResult(built, BodyFrameStatus.FRESH)
        }
        val previous = last ?: return BodyFrameResult(null, BodyFrameStatus.INVALID)
        val heldSeconds = (timestampNs - lastFreshNs) / 1_000_000_000.0
        return if (heldSeconds in 0.0..Params.BODY_FRAME_REUSE_LIMIT_SECONDS) {
            BodyFrameResult(previous, BodyFrameStatus.REUSED)
        } else {
            BodyFrameResult(null, BodyFrameStatus.INVALID)
        }
    }

    fun reset() {
        last = null
        lastFreshNs = 0
    }
}

/** Returns null when §6.4's degeneracy guards fire, meaning the caller should reuse or abstain. */
fun build(frame: LandmarkFrame): BodyFrame? {
    val required = intArrayOf(
        PoseLandmark.LEFT_HIP,
        PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_SHOULDER,
        PoseLandmark.RIGHT_SHOULDER,
    )
    if (required.any { frame.visibility[it] < Params.BODY_FRAME_MIN_VISIBILITY }) return null

    val leftHip = frame.worldLandmarks[PoseLandmark.LEFT_HIP]
    val rightHip = frame.worldLandmarks[PoseLandmark.RIGHT_HIP]
    val midHip = midpoint(leftHip, rightHip)
    val midShoulder = midpoint(
        frame.worldLandmarks[PoseLandmark.LEFT_SHOULDER],
        frame.worldLandmarks[PoseLandmark.RIGHT_SHOULDER],
    )

    val yRaw = midShoulder - midHip
    val yLength = yRaw.length()
    if (!yLength.isFinite() || yLength < Params.BODY_FRAME_MIN_TORSO_METRES) return null
    val y = yRaw * (1f / yLength)

    // Gram-Schmidt. Hip-to-hip is not exactly perpendicular to the torso axis (pelvic tilt,
    // landmark noise). Skipping this gives a basis whose inverse is not its transpose, and every
    // projected angle then carries a posture-dependent shear.
    val xRaw = rightHip - leftHip
    val xOrth = xRaw - y * (xRaw dot y)
    val xLength = xOrth.length()
    if (!xLength.isFinite() || xLength < Params.BODY_FRAME_MIN_HIP_WIDTH_METRES) return null
    val x = xOrth * (1f / xLength)

    return BodyFrame(x, y, x cross y)
}

private fun midpoint(a: Vec3, b: Vec3) = Vec3((a.x + b.x) / 2f, (a.y + b.y) / 2f, (a.z + b.z) / 2f)
