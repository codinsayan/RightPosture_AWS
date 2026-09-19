package com.example.rightposture.core.reference

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import kotlin.math.abs

/** §6.5.4: non-upright exercises inflate sigma further, because landmark quality degrades. */
enum class BodyPosition { UPRIGHT, NON_UPRIGHT }

/** The reference band for one feature, on the §6.8.4 101-bin phase grid. */
data class FeatureReference(
    /** §6.9.1 median trajectory. */
    val mean: List<Float>,
    /** §6.9.4 final band, floor and cap applied, then inflated. */
    val sigma: List<Float>,
    /** §6.9.6 central-difference derivative of [mean], for phase-uncertainty inflation at runtime. */
    val derivative: List<Float>,
    /** `max(mean) − min(mean)`. */
    val rom: Double,
    /**
     * Median over bins of the smoothed sigma **before** the §6.9.4 clamp and inflation.
     *
     * This is what §6.10 filter 2 tests, and it has to be the pre-clamp value: the clamp caps sigma
     * at 15°, so a post-clamp median could never exceed the threshold it is being compared against,
     * and the plan's own required test case (σ = 22°) would be unrepresentable. §6.9.4 warns that
     * the cap and the watch-list threshold must not drift apart; keeping the raw median here is how
     * that is guaranteed.
     */
    val medianSigmaRaw: Double,
)

data class Reference(
    val features: Map<FeatureId, FeatureReference>,
    val repCount: Int,
    val bodyPosition: BodyPosition,
)

data class ReferenceBuild(
    val reference: Reference?,
    /** Reps surviving §6.7.6 step 3. */
    val usedReps: Int,
    val rejection: String?,
)

/**
 * §6.9, plus the §6.7.6 step 3 shape-outlier rejection that could only run once reps were resampled
 * onto a common phase grid (3.5) and a median trajectory existed to compare them against.
 */
fun buildReference(
    reps: List<ResampledRep>,
    dominant: FeatureId,
    bodyPosition: BodyPosition = BodyPosition.UPRIGHT,
): ReferenceBuild {
    val kept = rejectShapeOutliers(reps, dominant)
    if (kept.size < Params.MIN_SURVIVING_REPS) {
        return ReferenceBuild(
            null,
            kept.size,
            "Only ${kept.size} reps survive shape-outlier rejection; " +
                "${Params.MIN_SURVIVING_REPS} are required.",
        )
    }

    val inflation = when (bodyPosition) {
        BodyPosition.UPRIGHT -> Params.K_INFLATE
        BodyPosition.NON_UPRIGHT -> Params.K_INFLATE_NON_UPRIGHT
    }

    // A feature is only referenceable if every surviving rep carries it.
    val featureIds = kept.first().bins.keys.filter { id -> kept.all { it.bins.containsKey(id) } }

    val features = featureIds.associateWith { id ->
        buildFeature(id, kept.map { it.bins.getValue(id) }, inflation)
    }

    return ReferenceBuild(Reference(features, kept.size, bodyPosition), kept.size, null)
}

/**
 * §6.7.6 step 3. Each rep's RMS deviation from the provisional median trajectory of the dominant
 * feature; drop anything beyond `median + 2.5 × MAD`.
 *
 * MAD rather than standard deviation, as §6.7.6 insists: with six to eight samples a single bad rep
 * inflates the standard deviation enough to hide inside its own threshold.
 */
internal fun rejectShapeOutliers(reps: List<ResampledRep>, dominant: FeatureId): List<ResampledRep> {
    val usable = reps.filter { it.bins.containsKey(dominant) }
    if (usable.size <= Params.MIN_SURVIVING_REPS) return usable

    val trajectories = usable.map { it.bins.getValue(dominant) }
    val provisional = medianAcross(trajectories)
    val deviations = trajectories.map { rmsDeviation(it, provisional) }

    val centre = median(deviations)
    val scale = Params.MAD_TO_SIGMA * median(deviations.map { abs(it - centre) })

    // A zero MAD is not a reason to skip rejection. It means most reps agree exactly, which makes
    // the threshold equal to the centre - so identical reps all pass (deviation 0 <= 0) while a rep
    // of a different shape still fails. Bailing out here instead would keep the one rep the step
    // exists to remove. Section 6.22's "MAD = 0 applies the sigma floor" rule is about dispersion,
    // not about this filter.
    val threshold = centre + Params.SHAPE_OUTLIER_MAD_MULTIPLIER * scale
    val kept = usable.filterIndexed { index, _ -> deviations[index] <= threshold }
    return if (kept.size >= Params.MIN_SURVIVING_REPS) kept else usable
}

private fun buildFeature(
    id: FeatureId,
    trajectories: List<List<Float>>,
    inflation: Double,
): FeatureReference {
    val n = trajectories.size

    // 6.9.1 median, not mean: with N = 5-7 one atypical rep that survived rejection still moves a
    // mean noticeably.
    val mean = medianAcross(trajectories)

    // 6.9.2 MAD-based scale, consistent with a median centre, times the small-sample correction.
    val correction = smallSampleCorrection(n)
    val sigmaRaw = List(Params.PHASE_BINS) { bin ->
        val residuals = trajectories.map { abs(it[bin] - mean[bin]).toDouble() }
        Params.MAD_TO_SIGMA * median(residuals) * correction
    }

    // 6.9.3 median first to kill single-bin spikes, mean second to make the curve continuous -
    // 6.13 differentiates the mean and needs sigma to behave smoothly alongside it.
    val smoothed = movingAverage(movingMedian(sigmaRaw, Params.SIGMA_MEDIAN_WINDOW), Params.SIGMA_MEAN_WINDOW)

    val rom = (mean.max() - mean.min()).toDouble()
    val floor = maxOf(
        if (id == FeatureId.LUMBAR_FLEXION) {
            // 6.5.3: this feature is a coarse proxy with wider intrinsic noise, so it overrides the
            // general floor rather than generating spurious faults.
            Params.LUMBAR_SIGMA_FLOOR_DEGREES
        } else {
            Params.SIGMA_FLOOR_DEGREES
        },
        Params.SIGMA_FLOOR_ROM_FRACTION * rom,
    )

    // 6.9.4 order of operations is deliberate: inflation is applied AFTER the clamp, not inside it.
    // The 15 degree cap says "the physio was not controlling this joint" - a statement about the
    // recording. The inflation says "the patient is not the physio" - a statement about transfer.
    // Independent corrections, both apply, so the effective ceiling is 17.25 upright and 19.50
    // non-upright. Moving k_inflate inside clamp() during a refactor would break the 6.10 watch-list
    // threshold, which is tuned against the pre-inflation cap.
    val sigma = smoothed.map {
        (it.coerceIn(floor, Params.SIGMA_CAP_DEGREES) * inflation).toFloat()
    }

    return FeatureReference(mean, sigma, derivativeOf(mean), rom, median(smoothed))
}

/** §6.9.2 finite-sample correction; MAD is a noisy estimator below N = 8. */
internal fun smallSampleCorrection(n: Int): Double = when {
    n <= 4 -> 1.36
    n == 5 -> 1.21
    n == 6 -> 1.14
    n == 7 -> 1.10
    else -> 1.06
}

/** §6.9.6 central difference, one-sided at both ends. */
internal fun derivativeOf(mean: List<Float>): List<Float> = List(mean.size) { bin ->
    when (bin) {
        0 -> mean[1] - mean[0]
        mean.lastIndex -> mean[bin] - mean[bin - 1]
        else -> (mean[bin + 1] - mean[bin - 1]) / 2f
    }
}

private fun medianAcross(trajectories: List<List<Float>>): List<Float> =
    List(Params.PHASE_BINS) { bin ->
        median(trajectories.map { it[bin].toDouble() }).toFloat()
    }

private fun rmsDeviation(trajectory: List<Float>, reference: List<Float>): Double {
    val sum = trajectory.indices.sumOf { bin ->
        val d = (trajectory[bin] - reference[bin]).toDouble()
        d * d
    }
    return kotlin.math.sqrt(sum / trajectory.size)
}

internal fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}

/** Reflective padding at both ends, so the window never shortens and the ends are not biased. */
internal fun movingMedian(values: List<Double>, window: Int): List<Double> =
    slide(values, window) { median(it) }

internal fun movingAverage(values: List<Double>, window: Int): List<Double> =
    slide(values, window) { it.average() }

private fun slide(values: List<Double>, window: Int, reduce: (List<Double>) -> Double): List<Double> {
    val half = window / 2
    return values.indices.map { centre ->
        reduce((centre - half..centre + half).map { reflected(values, it) })
    }
}

/** Reflect an out-of-range index back inside, so the window never shortens at the ends. */
private fun reflected(values: List<Double>, index: Int): Double {
    val folded = when {
        index < 0 -> -index
        index > values.lastIndex -> 2 * values.lastIndex - index
        else -> index
    }
    return values[folded.coerceIn(0, values.lastIndex)]
}
