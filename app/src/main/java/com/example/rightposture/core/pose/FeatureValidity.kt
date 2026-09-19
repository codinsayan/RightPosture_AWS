package com.example.rightposture.core.pose

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params

/**
 * Section 6.6. A feature is valid for a frame iff:
 *
 *  1. every contributing landmark has `visibility >= 0.6` and `presence >= 0.6`
 *  2. every contributing vector has magnitude `>= 1e-3` m
 *  3. the computed value is finite and inside the feature's declared range
 *  4. the body frame was constructible this frame, or reused within 500 ms
 *
 * Abstention is a feature. Silence beats a wrong correction, so nothing here guesses through an
 * occlusion.
 */

/** Declared value ranges, section 6.5.3. Null bounds mean the plan declares no range. */
enum class FeatureKind(val min: Float?, val max: Float?) {
    UNSIGNED_ANGLE(0f, 180f),

    /** Section 6.5.2 already rejects anything past 90 degrees as a probable wrap. */
    SIGNED_PLANAR(-90f, 90f),

    /** Feature 12 is a dimensionless ratio. Section 6.5.3 declares no range, so only finiteness applies. */
    RATIO(null, null),
}

val FeatureId.kind: FeatureKind
    get() = when (this) {
        FeatureId.KNEE_FLEX_ACTIVE,
        FeatureId.KNEE_FLEX_PASSIVE,
        FeatureId.HIP_FLEX_ACTIVE,
        FeatureId.HIP_FLEX_PASSIVE,
        FeatureId.TRUNK_FLEXION,
        FeatureId.SHOULDER_FLEX_ACTIVE,
        FeatureId.ELBOW_FLEX_ACTIVE,
        FeatureId.ANKLE_DORSIFLEX_ACTIVE,
        -> FeatureKind.UNSIGNED_ANGLE

        FeatureId.SHOULDER_ELEVATION -> FeatureKind.RATIO

        FeatureId.KNEE_FRONTAL_ACTIVE,
        FeatureId.HIP_ABDUCTION_ACTIVE,
        FeatureId.TRUNK_LATERAL,
        FeatureId.TRUNK_ROTATION,
        FeatureId.PELVIC_OBLIQUITY,
        FeatureId.LUMBAR_FLEXION,
        -> FeatureKind.SIGNED_PLANAR
    }

/**
 * Landmarks each feature is computed from, in chain order, so that consecutive pairs are exactly
 * the contributing vectors rule 2 measures.
 */
fun contributingLandmarks(id: FeatureId, active: Side): List<Int> {
    val passive = if (active == Side.LEFT) Side.RIGHT else Side.LEFT
    fun at(side: Side, left: Int, right: Int) = if (side == Side.LEFT) left else right

    val hipLine = listOf(PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP)
    val shoulderLine = listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER)
    val torso = shoulderLine + hipLine

    fun leg(side: Side) = listOf(
        at(side, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
        at(side, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE),
        at(side, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE),
    )

    return when (id) {
        FeatureId.KNEE_FLEX_ACTIVE, FeatureId.KNEE_FRONTAL_ACTIVE -> leg(active)
        FeatureId.KNEE_FLEX_PASSIVE -> leg(passive)

        FeatureId.HIP_FLEX_ACTIVE, FeatureId.HIP_ABDUCTION_ACTIVE -> listOf(
            at(active, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
            at(active, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
            at(active, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE),
        )

        FeatureId.HIP_FLEX_PASSIVE -> listOf(
            at(passive, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
            at(passive, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
            at(passive, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE),
        )

        FeatureId.TRUNK_FLEXION,
        FeatureId.TRUNK_LATERAL,
        FeatureId.TRUNK_ROTATION,
        FeatureId.LUMBAR_FLEXION,
        -> torso

        FeatureId.PELVIC_OBLIQUITY -> hipLine

        FeatureId.SHOULDER_FLEX_ACTIVE -> listOf(
            at(active, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
            at(active, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
            at(active, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW),
        )

        FeatureId.ELBOW_FLEX_ACTIVE -> listOf(
            at(active, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
            at(active, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW),
            at(active, PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST),
        )

        FeatureId.ANKLE_DORSIFLEX_ACTIVE -> listOf(
            at(active, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE),
            at(active, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE),
            at(active, PoseLandmark.LEFT_FOOT_INDEX, PoseLandmark.RIGHT_FOOT_INDEX),
        )

        FeatureId.SHOULDER_ELEVATION -> torso
    }
}

/**
 * Applies rules 1-4 on top of [extractFeatures]. Returns only features valid for this frame; a
 * missing key is the invalid flag.
 *
 * [bodyFrame] carries rule 4: `INVALID` means no feature can be trusted, so nothing is returned.
 */
fun validFeatures(
    frame: LandmarkFrame,
    bodyFrame: BodyFrameResult,
    context: FeatureContext,
): Map<FeatureId, Float> {
    val basis = bodyFrame.frame ?: return emptyMap()
    if (bodyFrame.status == BodyFrameStatus.INVALID) return emptyMap()

    return extractFeatures(frame, basis, context).filterKeys { id ->
        val landmarks = contributingLandmarks(id, context.activeSide)
        landmarksConfident(frame, landmarks) && vectorsLongEnough(frame, landmarks)
    }.filter { (id, value) -> inDeclaredRange(id, value) }
}

private fun landmarksConfident(frame: LandmarkFrame, landmarks: List<Int>) =
    landmarks.all { frame.visibility[it] >= Params.VIS_MIN && frame.presence[it] >= Params.PRESENCE_MIN }

private fun vectorsLongEnough(frame: LandmarkFrame, landmarks: List<Int>) =
    landmarks.zipWithNext().all { (a, b) ->
        val length = (frame.worldLandmarks[b] - frame.worldLandmarks[a]).length()
        length.isFinite() && length >= Params.FEATURE_MIN_VECTOR_METRES
    }

private fun inDeclaredRange(id: FeatureId, value: Float): Boolean {
    if (!value.isFinite()) return false
    val kind = id.kind
    if (kind.min != null && value < kind.min) return false
    if (kind.max != null && value > kind.max) return false
    return true
}

/**
 * Section 6.6 occlusion recovery. A feature that returns to validity is withheld until it has been
 * continuously valid for 300 ms, otherwise a flickering landmark produces a stream of spurious
 * faults at the recovery boundary.
 *
 * Not thread-safe; one instance per capture stream.
 */
class ValidityTracker {
    private val validSinceNs = mutableMapOf<FeatureId, Long>()
    private val invalidSinceNs = mutableMapOf<FeatureId, Long>()

    /** Features that are valid now *and* have cleared the recovery hold. */
    fun update(valid: Map<FeatureId, Float>, timestampNs: Long): Map<FeatureId, Float> {
        FeatureId.entries.forEach { id ->
            if (valid.containsKey(id)) {
                invalidSinceNs.remove(id)
                validSinceNs.putIfAbsent(id, timestampNs)
            } else {
                validSinceNs.remove(id)
                invalidSinceNs.putIfAbsent(id, timestampNs)
            }
        }
        return valid.filterKeys { id ->
            seconds(timestampNs - validSinceNs.getValue(id)) >= Params.VALIDITY_RECOVERY_SECONDS
        }
    }

    /** How long [id] has been invalid, or 0 if it is currently valid. Drives the section 6.6 UI rules. */
    fun invalidForSeconds(id: FeatureId, timestampNs: Long): Double =
        invalidSinceNs[id]?.let { seconds(timestampNs - it) } ?: 0.0

    /** Section 6.6: name the missing limb and suppress cues once a watched feature is out this long. */
    fun shouldWarn(watched: Collection<FeatureId>, timestampNs: Long) =
        watched.any { invalidForSeconds(it, timestampNs) > Params.OCCLUSION_WARN_SECONDS }

    /** Section 8.2: dominant feature gone this long means the patient left frame. Go idle, pause. */
    fun shouldGoIdle(dominant: FeatureId, timestampNs: Long) =
        invalidForSeconds(dominant, timestampNs) > Params.OCCLUSION_IDLE_SECONDS

    fun reset() {
        validSinceNs.clear()
        invalidSinceNs.clear()
    }

    private fun seconds(deltaNs: Long) = deltaNs / 1_000_000_000.0
}

/**
 * Section 6.6 frame score contract: an invalid feature is excluded and the remaining weights are
 * renormalised so the score stays on a 0-100 scale.
 *
 * Returns an empty map when nothing valid is left, which the caller must treat as "no score this
 * frame" rather than as a score of zero.
 */
fun renormaliseWeights(
    weights: Map<FeatureId, Float>,
    valid: Set<FeatureId>,
): Map<FeatureId, Float> {
    val kept = weights.filterKeys { it in valid }.filterValues { it > 0f && it.isFinite() }
    val total = kept.values.sum()
    if (total <= 0f || !total.isFinite()) return emptyMap()
    return kept.mapValues { (_, weight) -> weight / total }
}

/**
 * Section 6.6 rep contract: if more than 30% of a rep's frames had the dominant feature invalid,
 * the rep is counted but unscored, and excluded from the quality statistics and from CUSUM.
 */
fun repIsScorable(dominantValidFrames: Int, totalFrames: Int): Boolean {
    if (totalFrames <= 0) return false
    val invalidFraction = (totalFrames - dominantValidFrames).toDouble() / totalFrames
    return invalidFraction <= Params.REP_DOMINANT_INVALID_MAX_FRACTION
}
