package com.example.rightposture.core.reference

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.live.HoldAuthoringResult
import com.example.rightposture.core.live.buildHoldReference
import com.example.rightposture.core.pose.BodyFrameStatus
import com.example.rightposture.core.pose.BodyFrameTracker
import com.example.rightposture.core.pose.FeatureContext
import com.example.rightposture.core.pose.Side
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.validFeatures

/**
 * §6.19's authoring entry point, taking the same [CapturedFrame] stream the cyclic pipeline takes.
 *
 * Kept beside [runAuthoringPipeline] rather than inside it: the two share their front end (frames
 * in, body frame, §1.6 validity) and nothing after it. Folding hold mode into the cyclic pipeline
 * as a flag would put a branch through every stage of a pipeline whose stages are all about phase.
 */

/** Per-frame valid features, the shape both §6.19 and the cyclic path start from. */
fun featuresPerFrame(
    captured: List<CapturedFrame>,
    side: Side = Side.RIGHT,
    verticalWorld: Vec3? = Vec3(0f, 1f, 0f),
): List<Map<FeatureId, Float>> {
    val tracker = BodyFrameTracker()
    return captured.map { (frame, timestampNs) ->
        val bodyFrame = tracker.update(frame, timestampNs)
        if (bodyFrame.status == BodyFrameStatus.INVALID) {
            emptyMap()
        } else {
            validFeatures(frame, bodyFrame, FeatureContext(side, verticalWorld))
        }
    }
}

/**
 * `θ_j(neutral_standing)`: the median of each feature over the neutral capture.
 *
 * §6.19 makes this a required step of the hold authoring flow. Without it the dominant feature is
 * undefined, because "how far from neutral is this joint being held" is what replaces range of
 * motion — so a hold recorded without a neutral pose cannot be authored at all, rather than being
 * authored against an assumed neutral that no physio agreed to.
 */
fun neutralPose(captured: List<CapturedFrame>, side: Side = Side.RIGHT): Map<FeatureId, Float> {
    val frames = featuresPerFrame(captured, side)
    return FeatureId.entries.mapNotNull { id ->
        val values = frames.mapNotNull { it[id]?.toDouble() }.filter { it.isFinite() }
        if (values.isEmpty()) null else id to median(values).toFloat()
    }.toMap()
}

/** Frame rate from the capture's own timestamps, falling back to nominal for a degenerate stream. */
fun sampleRateOf(captured: List<CapturedFrame>): Double {
    if (captured.size < 2) return Params.NOMINAL_FPS.toDouble()
    val seconds = (captured.last().timestampNs - captured.first().timestampNs) / 1_000_000_000.0
    if (seconds <= 0.0) return Params.NOMINAL_FPS.toDouble()
    return (captured.size - 1) / seconds
}

fun runHoldAuthoring(
    captured: List<CapturedFrame>,
    neutral: Map<FeatureId, Float>,
    side: Side = Side.RIGHT,
    nonUpright: Boolean = false,
): HoldAuthoringResult = buildHoldReference(
    samples = featuresPerFrame(captured, side),
    neutral = neutral,
    sampleRateHz = sampleRateOf(captured),
    nonUpright = nonUpright,
)
