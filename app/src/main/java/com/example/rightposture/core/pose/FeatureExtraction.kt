package com.example.rightposture.core.pose

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params

enum class Side { LEFT, RIGHT }

/**
 * Per-frame inputs that the landmarks alone cannot supply.
 *
 * [verticalWorld] is the section 6.5.4 vertical reference, option (b): calibration confirms the
 * phone is within 10 degrees of upright, then image-vertical is treated as world-vertical for the
 * session. Null means no trustworthy vertical - a non-upright exercise, or a failed calibration
 * check - and every gravity-referenced feature abstains rather than returning a meaningless number.
 *
 * [activeSide] is supplied, not derived. Section 6.2 resolves the active side per rep from the
 * first 25% of the rep, which needs segmentation that does not exist until iteration 3.4.
 */
data class FeatureContext(
    val activeSide: Side,
    val verticalWorld: Vec3? = null,
)

/**
 * Section 6.5.3 feature set. An absent key means the feature is invalid for this frame; iteration
 * 1.6 turns that into the downstream mask.
 *
 * Section 6.5.3 prose says "14 scalar features" but its own numbered table lists 15. The table
 * wins, as recorded in v1.1.
 */
fun extractFeatures(
    frame: LandmarkFrame,
    basis: BodyFrame,
    context: FeatureContext,
): Map<FeatureId, Float> {
    val active = context.activeSide
    val passive = if (active == Side.LEFT) Side.RIGHT else Side.LEFT
    val world = frame.worldLandmarks

    fun point(side: Side, left: Int, right: Int) = world[if (side == Side.LEFT) left else right]
    fun direction(from: Vec3, to: Vec3) = basis.toBody(to - from)

    fun hip(s: Side) = point(s, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP)
    fun knee(s: Side) = point(s, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE)
    fun ankle(s: Side) = point(s, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE)
    fun shoulder(s: Side) = point(s, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER)
    fun elbow(s: Side) = point(s, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW)
    fun wrist(s: Side) = point(s, PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST)
    fun footIndex(s: Side) = point(s, PoseLandmark.LEFT_FOOT_INDEX, PoseLandmark.RIGHT_FOOT_INDEX)

    val leftHip = world[PoseLandmark.LEFT_HIP]
    val rightHip = world[PoseLandmark.RIGHT_HIP]
    val leftShoulder = world[PoseLandmark.LEFT_SHOULDER]
    val rightShoulder = world[PoseLandmark.RIGHT_SHOULDER]
    val midHip = midpoint(leftHip, rightHip)
    val midShoulder = midpoint(leftShoulder, rightShoulder)

    // Body-frame axes, expressed in body coordinates.
    val lateral = Vec3(1f, 0f, 0f)
    val longitudinal = Vec3(0f, 1f, 0f)
    val frontalNormal = Vec3(0f, 0f, 1f)
    val sagittalNormal = lateral

    val hipLine = direction(leftHip, rightHip)
    val shoulderLine = direction(leftShoulder, rightShoulder)
    val torsoLength = (midShoulder - midHip).length()
    val sideSign = FeatureSigns.lateralSign(active)

    val result = mutableMapOf<FeatureId, Float>()
    fun put(id: FeatureId, value: Float?) {
        if (value != null && value.isFinite()) result[id] = value * FeatureSigns.of(id)
    }

    // 1, 2 - unsigned knee flexion, both sides.
    put(FeatureId.KNEE_FLEX_ACTIVE, jointAngleDegrees(hip(active), knee(active), ankle(active)))
    put(FeatureId.KNEE_FLEX_PASSIVE, jointAngleDegrees(hip(passive), knee(passive), ankle(passive)))

    // 3 - shank vs thigh in the frontal plane. Positive = valgus (inward).
    put(
        FeatureId.KNEE_FRONTAL_ACTIVE,
        signedPlanarAngleDegrees(
            direction(hip(active), knee(active)),
            direction(knee(active), ankle(active)),
            frontalNormal,
        )?.times(sideSign),
    )

    // 4, 5 - unsigned hip flexion, both sides.
    put(FeatureId.HIP_FLEX_ACTIVE, jointAngleDegrees(shoulder(active), hip(active), knee(active)))
    put(
        FeatureId.HIP_FLEX_PASSIVE,
        jointAngleDegrees(shoulder(passive), hip(passive), knee(passive)),
    )

    // 6 - thigh vs the downward torso axis in the frontal plane. Standing straight reads about 0,
    // which keeps it clear of the 90-degree wrap guard.
    put(
        FeatureId.HIP_ABDUCTION_ACTIVE,
        signedPlanarAngleDegrees(
            longitudinal * -1f,
            direction(hip(active), knee(active)),
            frontalNormal,
        )?.times(sideSign),
    )

    // 7, 8 - the gravity-referenced features. Both abstain without a vertical. See the KNOWN ISSUE
    // note on FeatureSigns: section 6.5.4 claims 8 is body-intrinsic, but it cannot be.
    context.verticalWorld?.let { vertical ->
        val verticalBody = basis.toBody(vertical)
        put(FeatureId.TRUNK_FLEXION, angleBetweenDegrees(verticalBody, longitudinal))
        put(
            FeatureId.TRUNK_LATERAL,
            signedPlanarAngleDegrees(verticalBody, longitudinal, frontalNormal)?.times(sideSign),
        )
    }

    // 9 - shoulder line vs hip line about the longitudinal axis.
    put(
        FeatureId.TRUNK_ROTATION,
        signedPlanarAngleDegrees(hipLine, shoulderLine, longitudinal)?.times(sideSign),
    )

    // 10 - hip line vs the body-frame horizontal, in the frontal plane. Genuinely body-intrinsic:
    // Gram-Schmidt removes the longitudinal component from the basis but not from the raw hip line,
    // and that leftover component is exactly the hip drop or hike.
    put(
        FeatureId.PELVIC_OBLIQUITY,
        signedPlanarAngleDegrees(lateral, hipLine, frontalNormal)?.times(sideSign),
    )

    // 11, 13, 14 - unsigned three-point arm and ankle angles.
    put(FeatureId.SHOULDER_FLEX_ACTIVE, jointAngleDegrees(hip(active), shoulder(active), elbow(active)))
    put(FeatureId.ELBOW_FLEX_ACTIVE, jointAngleDegrees(shoulder(active), elbow(active), wrist(active)))
    put(
        FeatureId.ANKLE_DORSIFLEX_ACTIVE,
        jointAngleDegrees(knee(active), ankle(active), footIndex(active)),
    )

    // 12 - a ratio, not an angle: a shrug has no clean three-point definition. Normalising by torso
    // length makes it body-size invariant.
    if (torsoLength >= Params.MIN_VECTOR_LENGTH) {
        val elevation = direction(midHip, shoulder(active)) dot longitudinal
        put(FeatureId.SHOULDER_ELEVATION, elevation / torsoLength * 100f)
    }

    // 15 - coarse proxy. MediaPipe has no spine landmarks between the shoulders and the hips, so
    // this is inferred from the relative orientation of the only two lines available. Each line's
    // forward normal is taken as line x longitudinal.
    put(
        FeatureId.LUMBAR_FLEXION,
        signedPlanarAngleDegrees(
            hipLine cross longitudinal,
            shoulderLine cross longitudinal,
            sagittalNormal,
        ),
    )

    return result
}

/**
 * Appendix A sign table.
 *
 * Section 6.5.3 and Appendix A both say these must be determined **empirically on device** and
 * never inferred from the formulas: a guessed sign ships an arrow that confidently points the
 * wrong way.
 *
 * KNOWN ISSUE, unresolved: every entry below is +1, which is a placeholder and NOT a verified
 * value. The iteration 1.5 device gate exists to replace them, and it cannot run until the camera
 * and pose pipeline land in 2.1 and 2.2. No cue may ship against this table before that gate.
 */
object FeatureSigns {
    private val signs: Map<FeatureId, Float> = FeatureId.entries.associateWith { 1f }

    fun of(id: FeatureId) = signs.getValue(id)

    /**
     * Signed features are defined relative to the active side ("positive = valgus", "leaning
     * toward the active side"), so swapping the active side flips them. Also a placeholder
     * pending the same device gate.
     */
    fun lateralSign(active: Side) = if (active == Side.RIGHT) 1f else -1f
}

private fun midpoint(a: Vec3, b: Vec3) = Vec3((a.x + b.x) / 2f, (a.y + b.y) / 2f, (a.z + b.z) / 2f)
