package com.example.rightposture.core.live

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.reference.median
import kotlin.math.abs

/**
 * §6.19. Hold mode: a wall sit, a plank, a single-leg stand.
 *
 * "Simpler than cyclic — no phase axis at all." That absence is the whole design. Everything the
 * cyclic path derives from range of motion is meaningless here, because in a hold the range of
 * motion is near zero by construction, and §6.19 is explicit that applying the cyclic definitions
 * "would select on noise and produce a meaningless watch list".
 *
 * So this file deliberately does **not** reuse [PhaseTracker], [zScores]'s phase interpolation, or
 * the §6.10 watch-list salience. It reuses what is genuinely shared: the sigma floor, the frame
 * score, and the CUSUM machinery from §6.18.
 */

/** One joint's hold reference: a single mean and band, not a trajectory. */
data class HoldFeatureReference(
    val feature: FeatureId,
    /** `μ_j`, median over the stable window. */
    val mean: Double,
    /** `σ_j`, MAD-derived with the §6.9.4 floor applied. */
    val sigma: Double,
    /** `|μ_j − θ_j(neutral)|`, hold mode's analogue of range of motion. */
    val offsetFromNeutral: Double,
    /** §6.19's substituted salience, `1 / max(σ, floor)`. Tightness, not range. */
    val salience: Double,
)

data class HoldReference(
    val features: Map<FeatureId, HoldFeatureReference>,
    val dominant: FeatureId,
    /** Top K by the substituted salience. */
    val watchList: List<FeatureId>,
    val stableWindowSeconds: Double,
)

/** Why a hold recording was refused. §6.19's stability precondition, and the ordinary emptiness cases. */
enum class HoldAuthoringFailure {
    /** Shorter than the two seconds trimmed from each end, plus something to measure. */
    TOO_SHORT,

    /** No feature was valid across the stable window. */
    NO_FEATURES,

    /** §6.19: `MAD(θ_dom) > 8°` — "the physio wasn't actually holding still". */
    NOT_HELD_STILL,
}

data class HoldAuthoringResult(
    val reference: HoldReference? = null,
    val failure: HoldAuthoringFailure? = null,
    /** `MAD(θ_dom)` over the stable window, for the review screen to show what was refused. */
    val dominantMadDegrees: Double? = null,
)

/**
 * §6.19's reference construction.
 *
 * @param samples one map of valid features per frame, in order.
 * @param neutral `θ_j(neutral_standing)`, captured in a one-second neutral pose at the start of the
 *   physio's recording. §6.19 makes that a required step of the hold authoring flow: without it
 *   there is no way to ask how far from neutral a joint is being held, which is what replaces range
 *   of motion when selecting the dominant feature.
 */
fun buildHoldReference(
    samples: List<Map<FeatureId, Float>>,
    neutral: Map<FeatureId, Float>,
    sampleRateHz: Double = Params.NOMINAL_FPS.toDouble(),
    nonUpright: Boolean = false,
): HoldAuthoringResult {
    if (sampleRateHz <= 0.0 || !sampleRateHz.isFinite()) return HoldAuthoringResult(failure = HoldAuthoringFailure.TOO_SHORT)

    // "Discard the first and last 2 seconds (getting into and out of position)." What is left is the
    // hold itself; scoring the transitions would measure the patient walking to the wall.
    val trim = (Params.HOLD_TRIM_SECONDS * sampleRateHz).toInt()
    if (samples.size <= 2 * trim + MIN_STABLE_SAMPLES) {
        return HoldAuthoringResult(failure = HoldAuthoringFailure.TOO_SHORT)
    }
    val window = samples.subList(trim, samples.size - trim)

    val inflation = if (nonUpright) Params.K_INFLATE_NON_UPRIGHT else 1.0
    val references = FeatureId.entries.mapNotNull { id ->
        val values = window.mapNotNull { it[id]?.toDouble() }.filter { it.isFinite() }
        // A feature valid for a handful of frames out of a thirty-second hold is not a measurement
        // of that hold. §6.6's abstention rule, applied to the recording rather than to a frame.
        if (values.size < window.size * MIN_VALID_FRACTION) return@mapNotNull null

        val mean = median(values)
        val mad = median(values.map { abs(it - mean) })
        // §6.19: "the same floor/cap/inflation as 6.9.4". The ROM-fraction part of that floor is
        // dropped rather than computed, because a hold's ROM is near zero by construction and would
        // contribute nothing but a rounding error.
        val floor = if (id == FeatureId.LUMBAR_FLEXION) {
            Params.LUMBAR_SIGMA_FLOOR_DEGREES
        } else {
            Params.SIGMA_FLOOR_DEGREES
        }
        val sigma = (Params.MAD_TO_SIGMA * mad).coerceIn(floor, Params.SIGMA_CAP_DEGREES) * inflation
        val offset = neutral[id]?.let { abs(mean - it) } ?: 0.0
        HoldFeatureReference(
            feature = id,
            mean = mean,
            sigma = sigma,
            offsetFromNeutral = offset,
            // §6.19's substitute for ROM/sigma: tightness. A joint the physio held to within half a
            // degree is one they were controlling, whatever its range.
            salience = 1.0 / maxOf(sigma, floor),
        )
    }
    if (references.isEmpty()) return HoldAuthoringResult(failure = HoldAuthoringFailure.NO_FEATURES)

    // §6.19: "Use the largest ABSOLUTE angle, not the largest variance." How far from neutral a
    // joint is being held is what makes it the joint the hold is about.
    val dominant = references.maxByOrNull { it.offsetFromNeutral } ?: return HoldAuthoringResult(
        failure = HoldAuthoringFailure.NO_FEATURES,
    )

    // §6.19's stability precondition, checked on the dominant joint after it has been chosen.
    val dominantValues = window.mapNotNull { it[dominant.feature]?.toDouble() }
    val dominantMedian = median(dominantValues)
    val dominantMad = median(dominantValues.map { abs(it - dominantMedian) })
    if (dominantMad > Params.HOLD_MAX_DOMINANT_MAD_DEGREES) {
        return HoldAuthoringResult(
            failure = HoldAuthoringFailure.NOT_HELD_STILL,
            dominantMadDegrees = dominantMad,
        )
    }

    // §6.10 filters 1 and 4 are ROM-based and do not apply. Filters 2 and 3 do, and both are
    // already expressed through sigma, which the salience above is the reciprocal of.
    val watchList = references
        .sortedByDescending { it.salience }
        .take(Params.HOLD_WATCHLIST_SIZE)
        .map { it.feature }
        .let { if (it.contains(dominant.feature)) it else it.dropLast(1) + dominant.feature }

    return HoldAuthoringResult(
        reference = HoldReference(
            features = references.associateBy { it.feature },
            dominant = dominant.feature,
            watchList = watchList,
            stableWindowSeconds = window.size / sampleRateHz,
        ),
        dominantMadDegrees = dominantMad,
    )
}

/** What the hold is doing right now. */
enum class HoldState {
    /**
     * The patient is on their way into the position and nothing is being judged yet.
     *
     * This state exists because of a measured failure: without it the tracker starts scoring on the
     * frame the patient presses "start hold", while they are still standing up and walking to the
     * wall. Their knee is then tens of sigma from a reference that describes a wall sit, so the
     * break condition is satisfied within a second and the hold ends before it began - reproduced
     * at 1.03 s.
     */
    SETTLING,

    HOLDING,
    BROKEN,
}

data class HoldUpdate(
    val state: HoldState,
    /** Seconds since the position was reached, stopped at the break. Zero while settling. */
    val durationSeconds: Double,
    /** §6.17.1's frame score, over the hold watch list. */
    val quality: Double?,
    /** §6.19: fraction of hold time with every `|z| < 2`. Null before anything was scored. */
    val holdQuality: Double?,
    /** Largest `|dz/dt|` across watched joints, in sigma per second. */
    val worstDriftPerSecond: Double?,
    /** The joint that drift belongs to. */
    val driftFeature: FeatureId?,
    /** True on the frame the hold breaks, and on that frame only. */
    val brokeThisFrame: Boolean,
    /** §6.19: drift past the announce threshold, worth a correction. */
    val driftWarning: Boolean,
    /** The frame score series at 1 Hz, for §6.18's change point over a hold. */
    val qualitySamples: List<Double>,
)

/**
 * §6.19's live half.
 *
 * Not a [PhaseTracker]: there is no phase, so there is no phase index, no reference trajectory to
 * interpolate and, per §6.19, **no phase-uncertainty inflation** - `sigma_eff` is just `sigma`.
 *
 * The hold begins when the patient reaches the position, not when they press a button. Those are
 * different moments and only the first one is the hold.
 */
class HoldTracker(private val reference: HoldReference) {

    private var startNs: Long? = null
    private var lastNs: Long? = null
    private var state = HoldState.SETTLING
    private var scoredFrames = 0
    private var withinFrames = 0
    private var breachSinceNs: Long? = null
    private var driftSinceNs: Long? = null
    private var inPositionSinceNs: Long? = null

    /** Frozen when the hold breaks, so the logged duration is the hold, not the time on screen. */
    private var brokenAtNs: Long? = null

    /** `(t, z)` per watched feature, for the §6.19 drift fit. */
    private val driftSamples = mutableMapOf<FeatureId, MutableList<Pair<Double, Double>>>()

    /** Frame score sampled at 1 Hz, which is what §6.19 runs the change-point machinery over. */
    private val qualitySamples = mutableListOf<Double>()
    private var lastSampleNs: Long? = null

    fun reset() {
        startNs = null
        lastNs = null
        state = HoldState.SETTLING
        scoredFrames = 0
        withinFrames = 0
        breachSinceNs = null
        driftSinceNs = null
        inPositionSinceNs = null
        brokenAtNs = null
        driftSamples.clear()
        qualitySamples.clear()
        lastSampleNs = null
    }

    /**
     * @param measured this frame's valid features. An absent watched feature is an abstention: it
     *   neither scores nor counts against the hold, per §6.6.
     */
    fun update(measured: Map<FeatureId, Float>, timestampNs: Long): HoldUpdate {
        val previous = lastNs
        if (previous != null && timestampNs <= previous) return snapshot(brokeThisFrame = false)
        lastNs = timestampNs

        if (state == HoldState.BROKEN) return snapshot(brokeThisFrame = false)

        val z = reference.watchList.mapNotNull { id ->
            val value = measured[id]?.toDouble() ?: return@mapNotNull null
            val feature = reference.features[id] ?: return@mapNotNull null
            if (!value.isFinite() || feature.sigma <= 0.0) return@mapNotNull null
            // §6.19: no phase-uncertainty inflation, because there is no phase to be uncertain about.
            id to (value - feature.mean) / feature.sigma
        }.toMap()

        if (state == HoldState.SETTLING) {
            // The position is reached when every watched joint is inside the same band §6.19 scores
            // the hold against, held briefly so that passing through the pose on the way somewhere
            // else does not start the clock.
            val inPosition = z.isNotEmpty() && z.values.all { abs(it) < Params.HOLD_QUALITY_Z }
            inPositionSinceNs = if (inPosition) (inPositionSinceNs ?: timestampNs) else null
            val settled = inPositionSinceNs != null &&
                (timestampNs - inPositionSinceNs!!) / 1_000_000_000.0 >= Params.HOLD_SETTLE_CONFIRM_SECONDS
            if (!settled) return snapshot(brokeThisFrame = false)
            state = HoldState.HOLDING
            startNs = timestampNs
        }

        val start = startNs ?: timestampNs.also { startNs = it }
        val elapsed = (timestampNs - start) / 1_000_000_000.0

        if (z.isNotEmpty()) {
            scoredFrames++
            if (z.values.all { abs(it) < Params.HOLD_QUALITY_Z }) withinFrames++
            z.forEach { (id, value) ->
                driftSamples.getOrPut(id) { mutableListOf() } += elapsed to value
            }
            if (lastSampleNs == null || (timestampNs - lastSampleNs!!) / 1_000_000_000.0 >= 1.0) {
                lastSampleNs = timestampNs
                frameScoreOf(z)?.let { qualitySamples += it }
            }
        }

        // §6.19's break: any watched |z| past 4 continuously for a second. Continuously, so a single
        // wobble does not end a hold the patient is still in.
        val breaching = z.values.any { abs(it) > Params.HOLD_BREAK_Z }
        breachSinceNs = if (breaching) (breachSinceNs ?: timestampNs) else null
        val broke = breachSinceNs != null &&
            (timestampNs - breachSinceNs!!) / 1_000_000_000.0 >= Params.HOLD_BREAK_SECONDS
        if (broke) {
            state = HoldState.BROKEN
            brokenAtNs = timestampNs
            return snapshot(brokeThisFrame = true)
        }

        // §6.19: above the end threshold the hold is ended rather than merely corrected. The patient
        // is losing the position progressively, which is the hold-mode equivalent of §6.18.6.
        //
        // Sustained, per §6.19's own wording, and only once there is enough hold to fit a rate over.
        // A slope measured across two seconds is dominated by whatever the patient did while
        // settling into the position, and reads as a steep trend that is really a transient.
        val drift = worstDrift()
        val drifting = elapsed >= Params.HOLD_DRIFT_MIN_WINDOW_SECONDS &&
            abs(drift?.second ?: 0.0) > Params.HOLD_DRIFT_END_SIGMA_PER_SECOND
        driftSinceNs = if (drifting) (driftSinceNs ?: timestampNs) else null
        val sustained = driftSinceNs != null &&
            (timestampNs - driftSinceNs!!) / 1_000_000_000.0 >= Params.HOLD_DRIFT_SUSTAINED_SECONDS
        if (sustained) {
            state = HoldState.BROKEN
            brokenAtNs = timestampNs
            return snapshot(brokeThisFrame = true)
        }

        return snapshot(brokeThisFrame = false)
    }

    private fun frameScoreOf(z: Map<FeatureId, Double>): Double? {
        if (z.isEmpty()) return null
        val weighted = z.values.sumOf {
            val clamped = abs(it).coerceIn(0.0, Params.Z_CLAMP)
            clamped * clamped
        }
        return 100.0 * kotlin.math.exp(-Params.FRAME_SCORE_DECAY * weighted / z.size)
    }

    /**
     * §6.19's drift: least-squares `dz/dt` per watched joint, largest by magnitude.
     *
     * Signed slope, magnitude ranked - a joint sagging and a joint stiffening are both drift, and
     * which one it is belongs in the cue rather than in the detection.
     */
    private fun worstDrift(): Pair<FeatureId, Double>? = driftSamples
        .mapNotNull { (id, points) ->
            if (points.size < MIN_DRIFT_SAMPLES) return@mapNotNull null
            val slope = leastSquaresSlopeOverTime(points) ?: return@mapNotNull null
            id to slope
        }
        .maxByOrNull { abs(it.second) }

    private fun snapshot(brokeThisFrame: Boolean): HoldUpdate {
        val drift = worstDrift()
        // Frozen at the break: §6.19 says "stop the timer, log holdDuration", and a timer that
        // keeps counting while the patient sits on the floor logs the wrong number.
        val end = brokenAtNs ?: lastNs
        val duration = if (startNs == null || end == null) 0.0 else (end - startNs!!) / 1_000_000_000.0
        val holding = state != HoldState.SETTLING
        return HoldUpdate(
            state = state,
            durationSeconds = if (holding) duration else 0.0,
            quality = null,
            holdQuality = if (scoredFrames == 0) null else withinFrames.toDouble() / scoredFrames,
            worstDriftPerSecond = if (holding) drift?.second else null,
            driftFeature = if (holding) drift?.first else null,
            brokeThisFrame = brokeThisFrame,
            driftWarning = holding && abs(drift?.second ?: 0.0) > Params.HOLD_DRIFT_SIGMA_PER_SECOND,
            qualitySamples = qualitySamples.toList(),
        )
    }

    private companion object {
        /** Below this a slope is fitting noise rather than describing a trend. */
        const val MIN_DRIFT_SAMPLES = 15
    }
}

/** Least squares of `z` against `t`, in sigma per second. Null when the times do not vary. */
internal fun leastSquaresSlopeOverTime(points: List<Pair<Double, Double>>): Double? {
    if (points.size < 2) return null
    val meanT = points.sumOf { it.first } / points.size
    val meanZ = points.sumOf { it.second } / points.size
    var covariance = 0.0
    var variance = 0.0
    points.forEach { (t, z) ->
        val dt = t - meanT
        covariance += dt * (z - meanZ)
        variance += dt * dt
    }
    if (variance <= 0.0) return null
    val slope = covariance / variance
    return if (slope.isFinite()) slope else null
}

private const val MIN_STABLE_SAMPLES = 15
private const val MIN_VALID_FRACTION = 0.5
