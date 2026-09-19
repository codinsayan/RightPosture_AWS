package com.example.rightposture.core.live

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.Side
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * §6.16. Which way the patient should move, as pure image-space geometry.
 *
 * §6.16 carries an explicit correction to an earlier draft that is worth restating, because the
 * obvious implementation is the impossible one: MediaPipe emits world landmarks (metres,
 * hip-centred) and image landmarks (normalised 2D) as two **independent** outputs, with no
 * intrinsics and no projection relating them. There is no transform from a world-space target back
 * onto the screen. So everything here happens in image space, which is legitimate because
 * `required_view` already guarantees the plane of motion is roughly parallel to the image plane.
 *
 * The result is a planar approximation, and §6.16 says so: off-view the rotation magnitude
 * foreshortens. The quality bar stays driven by true 3D z-scores regardless - only the *picture* is
 * planar.
 */

/** A point in **pixels** on the drawn image. Not normalised: see [correctionArrow]. */
data class Point2(val x: Float, val y: Float) {
    operator fun minus(other: Point2) = Point2(x - other.x, y - other.y)
    operator fun plus(other: Point2) = Point2(x + other.x, y + other.y)
    fun length() = hypot(x.toDouble(), y.toDouble())
}

/** Where to draw the arrow, in the same pixel space that was passed in. */
data class Arrow(val from: Point2, val to: Point2) {
    val length get() = (to - from).length()
}

/** Why an arrow is being shown. The two kinds answer different questions and are earned differently. */
enum class ArrowKind {
    /**
     * "You are not where the movement should take you." Driven by range, on the dominant feature.
     *
     * The dominant feature defines phase, so it is compared against itself and its z is ~0 whatever
     * the patient does - a deviation arrow for it is structurally impossible. Range is the thing
     * that *is* meaningful about it: did the movement actually get there.
     */
    RANGE,

    /** §6.16 proper: "you are doing it, but some other joint is off." */
    COMPENSATION,
}

/** One arrow's worth of instruction, before it is turned into pixels. */
data class ArrowTarget(
    val feature: FeatureId,
    val kind: ArrowKind,
    /** `target - measured`, in degrees. */
    val deltaThetaDegrees: Double,
    /** [0, 1] of the maximum arrow length. */
    val lengthFraction: Double,
    /**
     * §6.16's `s`, measured off the reference recording that produced this bundle. Null when the
     * recording could not decide, in which case nothing is drawn.
     */
    val sign: Int?,
)

/** §6.16's length rule for a deviation arrow: `clamp(|z| / 6, 0.2, 1.0)`. */
fun lengthFractionForZ(z: Double): Double =
    (abs(z) / Params.ARROW_Z_FULL_SCALE).coerceIn(Params.ARROW_MIN_LENGTH_FRACTION, 1.0)

/**
 * Length for a range arrow, as a fraction of the reference movement's own span.
 *
 * Expressed in the exercise's own range rather than in sigma, because "how far is left to go" is a
 * question about the movement, not about how tightly the physio repeated it.
 */
fun lengthFractionForRange(errorDegrees: Double, spanDegrees: Double): Double {
    if (spanDegrees == 0.0 || !spanDegrees.isFinite()) return 0.0
    val fraction = abs(errorDegrees) / (Params.ARROW_RANGE_FULL_FRACTION * abs(spanDegrees))
    return fraction.coerceIn(Params.ARROW_MIN_LENGTH_FRACTION, 1.0)
}

/** §6.16 `rotate2D`. Positive degrees rotate from +x toward +y, which in image space is clockwise. */
fun rotate2D(v: Point2, degrees: Double): Point2 {
    val radians = Math.toRadians(degrees)
    val c = cos(radians)
    val s = sin(radians)
    return Point2(
        (v.x * c - v.y * s).toFloat(),
        (v.x * s + v.y * c).toFloat(),
    )
}

/**
 * §6.16's arrow for one feature.
 *
 * Takes **pixel** coordinates, not normalised ones, and the difference is load-bearing: normalised
 * image coordinates are scaled independently by width and height, so rotating in them shears the
 * limb instead of turning it. The caller converts once, when it maps landmarks onto the drawn image.
 *
 * @param joint `B`, the joint the segment rotates about.
 * @param distal `C`, the far end of the segment being corrected.
 * @param deltaThetaDegrees `dTheta = target - theta_j(t)`, the signed angular error. For a
 *   compensation arrow the target is `mu_j(p)`, per §6.16; for a range arrow it is the end of the
 *   movement the patient is currently travelling toward.
 * @param lengthFraction how far to draw, in [0, 1] of [maxLengthPx]. The caller computes it,
 *   because the two kinds of arrow measure "how wrong" in different units.
 * @param sign `s` in {+1, -1}, from [ArrowSigns]. Never guessed - see that table.
 * **Whether** an arrow should be shown at all is [ArrowSelector]'s decision, not this function's.
 * This only refuses what it cannot draw. Splitting the two matters: a z-gate here would blank the
 * arrow the instant the patient improved, cancelling the selector's exit dwell and reintroducing
 * exactly the flicker the dwell exists to stop.
 *
 * @return null when there is nothing geometrically drawable: no verified sign, a degenerate
 *   segment, a non-finite input, or a rotation of zero.
 */
fun correctionArrow(
    joint: Point2,
    distal: Point2,
    deltaThetaDegrees: Double,
    lengthFraction: Double,
    sign: Int?,
    maxLengthPx: Double = Params.ARROW_MAX_PX,
): Arrow? {
    // §6.16: "Determine s empirically for each feature and store it in a constant table - the same
    // discipline as Appendix A, and the same failure mode if you guess: an arrow that confidently
    // points the wrong way." An unverified feature draws nothing at all.
    if (sign == null) return null
    if (!deltaThetaDegrees.isFinite() || !lengthFraction.isFinite()) return null

    val segment = distal - joint
    // A zero-length segment has no direction to rotate, which happens when the joint and the distal
    // landmark collapse onto each other under occlusion.
    if (segment.length() < 1e-3) return null

    val ghost = joint + rotate2D(segment, sign * deltaThetaDegrees)
    val direction = ghost - distal
    val magnitude = direction.length()
    if (magnitude < 1e-3) return null

    val length = lengthFraction.coerceIn(Params.ARROW_MIN_LENGTH_FRACTION, 1.0) * maxLengthPx
    val unitX = direction.x / magnitude
    val unitY = direction.y / magnitude
    return Arrow(
        from = distal,
        to = Point2(
            (distal.x + unitX * length).toFloat(),
            (distal.y + unitY * length).toFloat(),
        ),
    )
}

/**
 * The `(A, B, C)` landmark triple a feature's arrow rotates, per §6.16.
 *
 * Only features that *are* one limb segment turning about one joint appear here. `TRUNK_ROTATION`
 * is the clearest exclusion: it is a rotation about the longitudinal axis, which is out of the image
 * plane, so no in-plane arrow can depict it and pretending otherwise would draw a confident lie.
 */
fun arrowLandmarks(id: FeatureId, active: Side): Triple<Int, Int, Int>? {
    fun at(side: Side, left: Int, right: Int) = if (side == Side.LEFT) left else right
    val passive = if (active == Side.LEFT) Side.RIGHT else Side.LEFT

    fun leg(side: Side) = Triple(
        at(side, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
        at(side, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE),
        at(side, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE),
    )

    fun thigh(side: Side) = Triple(
        at(side, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
        at(side, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
        at(side, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE),
    )

    fun upperArm(side: Side) = Triple(
        at(side, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
        at(side, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
        at(side, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW),
    )

    fun forearm(side: Side) = Triple(
        at(side, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
        at(side, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW),
        at(side, PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST),
    )

    fun foot(side: Side) = Triple(
        at(side, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE),
        at(side, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE),
        at(side, PoseLandmark.LEFT_FOOT_INDEX, PoseLandmark.RIGHT_FOOT_INDEX),
    )

    return when (id) {
        FeatureId.KNEE_FLEX_ACTIVE, FeatureId.KNEE_FRONTAL_ACTIVE -> leg(active)
        FeatureId.KNEE_FLEX_PASSIVE -> leg(passive)
        FeatureId.HIP_FLEX_ACTIVE, FeatureId.HIP_ABDUCTION_ACTIVE -> thigh(active)
        FeatureId.HIP_FLEX_PASSIVE -> thigh(passive)
        FeatureId.SHOULDER_FLEX_ACTIVE -> upperArm(active)
        FeatureId.ELBOW_FLEX_ACTIVE -> forearm(active)
        FeatureId.ANKLE_DORSIFLEX_ACTIVE -> foot(active)

        // Whole-torso attitudes: the "segment" is the trunk itself, hip to shoulder, rotating about
        // the hip - which is what a patient leaning is actually doing.
        FeatureId.TRUNK_FLEXION, FeatureId.TRUNK_LATERAL -> Triple(
            at(active, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE),
            at(active, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
            at(active, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
        )

        // Out of the image plane, or not a segment rotation at all. No arrow is drawn.
        FeatureId.TRUNK_ROTATION,
        FeatureId.PELVIC_OBLIQUITY,
        FeatureId.LUMBAR_FLEXION,
        FeatureId.SHOULDER_ELEVATION,
        -> null
    }
}

/**
 * §6.16's per-feature rotation sign table.
 *
 * **Every entry is absent until it has been checked on a phone.** §6.16 requires `s` to be
 * determined empirically per feature and view, and the iteration plan says it in as many words: do
 * not ship guessed rotation signs. A missing entry means [correctionArrow] draws nothing, which is
 * the same abstention rule 1.6 applies to features it cannot measure - showing no arrow costs the
 * patient a hint, while showing a backwards one actively coaches them into the fault.
 *
 * `ArrowSignCheck` on the exercise screen is how entries get filled in.
 */
object ArrowSigns {
    /** Flipped to true only once every feature in [arrowLandmarks] has a checked sign. */
    const val VERIFIED = false

    private val signs: Map<FeatureId, Int> = emptyMap()

    /** The verified sign for a feature, or null if it has not been determined yet. */
    fun of(id: FeatureId): Int? = signs[id]
}
