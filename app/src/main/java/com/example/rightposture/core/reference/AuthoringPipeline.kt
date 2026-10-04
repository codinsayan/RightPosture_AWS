package com.example.rightposture.core.reference

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.filter.butterworthFiltFilt
import com.example.rightposture.core.pose.BodyFrameStatus
import com.example.rightposture.core.pose.BodyFrameTracker
import com.example.rightposture.core.pose.FeatureContext
import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.classifyView
import com.example.rightposture.core.live.arrowLandmarks
import com.example.rightposture.core.pose.Side
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.contributingLandmarks
import com.example.rightposture.core.pose.validFeatures
import com.example.rightposture.core.pose.viewStability
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil

/**
 * Runs a captured recording end to end: features → filter → dominant feature → period → rep
 * segmentation → phase resampling → reference → watch list → validator.
 *
 * This is the first place iterations 1.2 through 3.8 are wired together rather than unit tested in
 * isolation, so it is also the first place a real recording can disagree with a synthetic one.
 */

/** One captured frame plus the timestamp it arrived with. */
data class CapturedFrame(val frame: LandmarkFrame, val timestampNs: Long)

data class AuthoringResult(
    val dominant: FeatureId?,
    /**
     * The side 6.2 resolved the movement onto. The `*_ACTIVE` feature ids are side-agnostic, so the
     * reference is meaningless without knowing which limb they were measured on - scoring the right
     * knee against a reference built from the left one produces a plausible number that is wrong.
     */
    val activeSide: Side = Side.RIGHT,
    /**
     * The view the physio actually recorded in, as the majority unambiguous classification over
     * the captured frames.
     *
     * §6.20.1 check 4 tests the live view against `required_view`, and until this was derived the
     * manifest carried a hardcoded `"sagittal"` for every exercise. A frontally recorded exercise
     * therefore produced a reference the patient could never calibrate against - the check compared
     * their view against a default nobody chose. Null when the recording never settled on one view,
     * which §3.8 check 6 already rejects.
     */
    val recordedView: CameraView? = null,
    /**
     * §6.16's rotation sign per feature, measured off this recording rather than guessed. Absent for
     * a feature the recording did not move enough to decide.
     */
    val arrowSigns: Map<FeatureId, Int> = emptyMap(),
    val periodSeconds: Double?,
    val segmentation: SegmentationResult?,
    val reference: Reference?,
    val watchList: WatchList?,
    val validation: ValidationResult?,
    /** Filtered dominant-feature trace, for the review chart. */
    val dominantTrace: List<Float>,
    /** Mean landmark visibility per feature, for the 3.9 bundle's `mean_visibility`. */
    val meanVisibility: Map<FeatureId, Float> = emptyMap(),
    /**
     * 10.2. The physio's body over one rep, for a ghost to draw. Null when too few reps had a
     * usable body frame throughout - which does not fail authoring: scoring needs none of it.
     */
    val skeleton: SkeletonReference? = null,
    /** Why the pipeline stopped early, when it did. Null when it ran to completion. */
    val failure: String?,
)

/** Intermediate rejection stays hidden; only a fully accepted pattern may stop recording early. */
fun AuthoringResult.readyForAutoStop(durationSeconds: Double): Boolean =
    durationSeconds >= Params.VALIDATOR_MIN_DURATION_SECONDS &&
        validation?.accepted == true

fun remainingAuthoringSeconds(durationSeconds: Double): Int =
    ceil((Params.AUTHORING_ACCEPTANCE_TIMEOUT_SECONDS - durationSeconds).coerceAtLeast(0.0)).toInt()

fun runAuthoringPipeline(
    captured: List<CapturedFrame>,
    activeSide: Side? = null,
    verticalWorld: Vec3? = Vec3(0f, 1f, 0f),
    bodyPosition: BodyPosition = BodyPosition.UPRIGHT,
): AuthoringResult {
    val empty = AuthoringResult(
        dominant = null,
        periodSeconds = null,
        segmentation = null,
        reference = null,
        watchList = null,
        validation = null,
        dominantTrace = emptyList(),
        failure = null,
    )
    if (captured.size < 2) return empty.copy(failure = "Nothing was recorded.")

    val durationSeconds = (captured.last().timestampNs - captured.first().timestampNs) / 1_000_000_000.0
    if (durationSeconds <= 0.0) return empty.copy(failure = "Recording timestamps are not usable.")
    val sampleRateHz = (captured.size - 1) / durationSeconds

    // Features per frame, honouring 1.6 validity. An invalid feature leaves a gap rather than a
    // fabricated value, and a feature with any gap is excluded from the reference entirely - the
    // authoring pipeline has the whole recording, so it can afford to be strict.
    val tracker = BodyFrameTracker()
    val bodyFrames = captured.map { (frame, timestampNs) -> tracker.update(frame, timestampNs) }
    fun extractFor(side: Side) = captured.zip(bodyFrames).map { (capturedFrame, bodyFrame) ->
        val frame = capturedFrame.frame
        if (bodyFrame.status == BodyFrameStatus.INVALID) {
            emptyMap()
        } else {
            validFeatures(frame, bodyFrame, FeatureContext(side, verticalWorld))
        }
    }

    val perSide = Side.entries.associateWith(::extractFor)
    val selectedSide = activeSide ?: resolveActiveSide(perSide)
    val perFrame = perSide.getValue(selectedSide)
    val maxGapSamples = (Params.MAX_FRAME_GAP_SECONDS * sampleRateHz).toInt().coerceAtLeast(1)
    val usableSignals = FeatureId.entries.mapNotNull { id ->
        bridgeShortGaps(perFrame.map { it[id] }, maxGapSamples)?.let { id to it }
    }.toMap()
    if (usableSignals.isEmpty()) {
        return empty.copy(
            failure = "No joint stayed visible for the whole recording. " +
                "Move the camera back so your whole body is in frame.",
        )
    }

    // 6.3.2: zero-phase Butterworth offline, not the live One Euro filter. The authoring pipeline
    // has the whole recording, so it can filter without introducing lag.
    //
    // The 6 Hz cutoff assumes a frame rate comfortably above 12 fps. A thermally throttled phone can
    // drop below that, at which point 6 Hz is past Nyquist and the filter would throw. Back the
    // cutoff off rather than crash, and say so if the recording is too slow to be usable at all.
    if (sampleRateHz < 10.0) {
        return empty.copy(
            failure = "The camera only managed ${String.format("%.0f", sampleRateHz)} fps. " +
                "Record somewhere brighter, or close other apps.",
        )
    }
    val cutoffHz = minOf(Params.BUTTERWORTH_CUTOFF_HZ, sampleRateHz / 2.0 * 0.8)
    val signals = usableSignals.mapValues { (_, values) ->
        butterworthFiltFilt(
            rejectIsolatedOutliers(values),
            sampleRateHz,
            cutoffHz,
        )
    }

    val selection = selectDominantFeatureAndPeriod(signals, sampleRateHz)
        ?: return empty.copy(failure = "This doesn't look like a repeated movement. Switch to Hold mode?")
    val dominant = selection.feature
    val trace = signals.getValue(dominant)
    val period = selection.period

    val segmentation = segmentReps(trace, period.lagSamples)
    if (segmentation.rejection != null) {
        return empty.copy(
            dominant = dominant,
            periodSeconds = period.seconds,
            segmentation = segmentation,
            dominantTrace = trace,
            failure = segmentation.rejection,
        )
    }

    val reps = resampleUsableReps(segmentation, signals, dominant)
    val build = buildReference(reps, dominant, bodyPosition)
    val reference = build.reference ?: return empty.copy(
        dominant = dominant,
        periodSeconds = period.seconds,
        segmentation = segmentation,
        dominantTrace = trace,
        failure = build.rejection,
    )

    val meanVisibility = meanVisibilityPerFeature(captured, selectedSide)
    val watchList = selectWatchList(reference, meanVisibility, dominant)
    val validation = validateReference(
        reference,
        watchList,
        dominant,
        RecordingSummary(
            durationSeconds = durationSeconds,
            repDurationsSeconds = segmentation.usable.map { it.lengthSamples / sampleRateHz },
            meanVisibility = meanVisibility,
            viewStability = viewStability(captured.map { it.frame }),
        ),
    )

    return AuthoringResult(
        dominant = dominant,
        activeSide = selectedSide,
        recordedView = dominantRecordedView(captured.map { it.frame }),
        arrowSigns = deriveArrowSigns(captured, perFrame, selectedSide),
        periodSeconds = period.seconds,
        segmentation = segmentation,
        reference = reference,
        watchList = watchList,
        validation = validation,
        dominantTrace = trace,
        meanVisibility = meanVisibility,
        skeleton = buildSkeletonReference(captured, bodyFrames, segmentation, trace, sampleRateHz, cutoffHz),
        failure = null,
    )
}

/**
 * §6.16's `s`, derived from the recording instead of eyeballed.
 *
 * §6.16 says to determine the sign empirically per feature and view, and the iteration plan forbids
 * guessing it. Both were written as though a person has to sit with a phone and a Flip button - but
 * the sign is just the answer to "when this feature's angle increases, which way does the segment
 * turn on screen?", and a reference recording contains that answer for every feature at once.
 *
 * ```
 * s = sign( sum over frames of  d(measured) * d(imageAngle) )
 * ```
 *
 * Deriving it here rather than storing a constant table also makes it correct by construction for
 * things a table cannot capture: the front camera's mirroring, which flips it, and the required view,
 * which §6.16 says it depends on.
 *
 * A feature whose angle barely moved in the recording produces no sign at all, and 6.16 then draws
 * nothing for it - abstention, the same as everywhere else.
 */
/**
 * The view the recording was made in: the commonest unambiguous classification over its frames.
 *
 * Oblique frames are excluded rather than counted, exactly as [viewStability] excludes them - an
 * oblique reading is the classifier saying it cannot tell, and §6.20.2 rejects that view outright.
 * Null when no frame could be classified at all, which §3.8 check 6 already refuses.
 */
internal fun dominantRecordedView(frames: List<LandmarkFrame>): CameraView? = frames
    .mapNotNull(::classifyView)
    .filter { it != CameraView.OBLIQUE }
    .groupingBy { it }
    .eachCount()
    .maxByOrNull { it.value }
    ?.key

internal fun deriveArrowSigns(
    captured: List<CapturedFrame>,
    perFrame: List<Map<FeatureId, Float>>,
    side: Side,
): Map<FeatureId, Int> {
    val result = mutableMapOf<FeatureId, Int>()

    FeatureId.entries.forEach { id ->
        val landmarks = arrowLandmarks(id, side) ?: return@forEach
        var accumulator = 0.0
        var agreeing = 0
        var samples = 0

        // Compared across a short stride rather than adjacent frames. Both deltas then clear the
        // noise floor by a wide margin instead of hovering at it, which is what decides whether
        // there is anything to measure: adjacent frames yielded 16 usable pairs on a reference
        // recording where a stride of three yields 48, all agreeing.
        for (index in ARROW_SIGN_STRIDE until minOf(captured.size, perFrame.size)) {
            val previous = index - ARROW_SIGN_STRIDE
            val previousValue = perFrame[previous][id] ?: continue
            val value = perFrame[index][id] ?: continue
            val previousAngle = segmentAngleDegrees(captured[previous], landmarks) ?: continue
            val angle = segmentAngleDegrees(captured[index], landmarks) ?: continue

            val dMeasured = value - previousValue
            val dAngle = wrapDegrees(angle - previousAngle)
            // Below this, frame-to-frame noise dominates and the product carries no information
            // about the relationship - it just adds variance to the sum.
            if (abs(dMeasured) < ARROW_SIGN_MIN_DELTA || abs(dAngle) < ARROW_SIGN_MIN_DELTA) continue

            val product = dMeasured * dAngle
            accumulator += product
            samples++
            if (product > 0) agreeing++
        }

        if (samples < ARROW_SIGN_MIN_SAMPLES) return@forEach
        val agreement = maxOf(agreeing, samples - agreeing).toDouble() / samples
        // A feature that disagrees with itself half the time is one whose motion is not a planar
        // rotation of this segment. Claiming a sign for it would be the guess this exists to avoid.
        if (agreement < ARROW_SIGN_MIN_AGREEMENT) return@forEach
        if (accumulator == 0.0) return@forEach
        result[id] = if (accumulator > 0) 1 else -1
    }
    return result
}

/** The segment's orientation on screen, in degrees, as the overlay sees it. */
private fun segmentAngleDegrees(
    captured: CapturedFrame,
    landmarks: Triple<Int, Int, Int>,
): Double? {
    val joint = captured.frame.imageLandmarks.getOrNull(landmarks.second) ?: return null
    val distal = captured.frame.imageLandmarks.getOrNull(landmarks.third) ?: return null
    val dx = (distal.x - joint.x).toDouble()
    val dy = (distal.y - joint.y).toDouble()
    if (abs(dx) < 1e-9 && abs(dy) < 1e-9) return null
    return Math.toDegrees(atan2(dy, dx))
}

private fun wrapDegrees(value: Double): Double {
    var wrapped = value
    while (wrapped > 180.0) wrapped -= 360.0
    while (wrapped < -180.0) wrapped += 360.0
    return wrapped
}

private const val ARROW_SIGN_STRIDE = 3
private const val ARROW_SIGN_MIN_DELTA = 0.5
private const val ARROW_SIGN_MIN_SAMPLES = 20
private const val ARROW_SIGN_MIN_AGREEMENT = 0.7

private val SIDE_MOVEMENT_FEATURES = setOf(
    FeatureId.KNEE_FLEX_ACTIVE,
    FeatureId.HIP_FLEX_ACTIVE,
    FeatureId.HIP_ABDUCTION_ACTIVE,
    FeatureId.SHOULDER_FLEX_ACTIVE,
    FeatureId.ELBOW_FLEX_ACTIVE,
    FeatureId.ANKLE_DORSIFLEX_ACTIVE,
)

/** Whole-recording approximation of 6.2; explicit caller side still overrides it. */
internal fun resolveActiveSide(perSide: Map<Side, List<Map<FeatureId, Float>>>): Side {
    fun score(side: Side): Double = SIDE_MOVEMENT_FEATURES.maxOf { id ->
        val values = perSide[side].orEmpty().mapNotNull { it[id] }
        if (values.size < 2) 0.0 else percentileSwing(values)
    }
    val left = score(Side.LEFT)
    val right = score(Side.RIGHT)
    return when {
        left > Params.ACTIVE_SIDE_RANGE_RATIO * right -> Side.LEFT
        right > Params.ACTIVE_SIDE_RANGE_RATIO * left -> Side.RIGHT
        else -> Side.RIGHT
    }
}

/** Mean landmark visibility per feature, averaged over its contributing landmarks and all frames. */
internal fun meanVisibilityPerFeature(
    captured: List<CapturedFrame>,
    activeSide: Side,
): Map<FeatureId, Float> = FeatureId.entries.associateWith { id ->
    val landmarks = contributingLandmarks(id, activeSide)
    val total = captured.sumOf { (frame, _) ->
        landmarks.sumOf { frame.visibility[it].toDouble() } / landmarks.size
    }
    (total / captured.size).toFloat()
}

/** Rep boundary sample indices, for drawing on the review chart. */
fun SegmentationResult.boundaryIndices(): List<Int> =
    (usable.map { it.startIndex } + usable.map { it.endIndex }).distinct().sorted()

/** `mean ± 3σ` at each phase bin, for the review band. */
fun FeatureReference.band(): Pair<List<Float>, List<Float>> =
    mean.indices.map { mean[it] - 3f * sigma[it] } to mean.indices.map { mean[it] + 3f * sigma[it] }
