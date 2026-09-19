package com.example.rightposture.core.live

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.reference.median
import kotlin.math.abs

/**
 * §6.18. Within-set fatigue and degradation detection.
 *
 * The plan calls this the differentiator and says to treat it as the centrepiece. What makes it
 * defensible rather than a vibe is that it never rests on one signal: CUSUM localises *where* a
 * decline began, a least-squares slope independently confirms *that* one happened, and only their
 * agreement is reported as degradation. Either alone fires on noise.
 *
 * Everything here is a pure function of a [SessionRecord], which 6.1 built for exactly this.
 */

enum class FatigueStatus {
    /** §6.18.1: fewer than six valid reps. The honest answer, never a change point from four numbers. */
    INSUFFICIENT_REPS,

    /** Assessed, and no corroborated decline. Includes a CUSUM alarm the slope did not confirm. */
    STABLE,

    /** §6.18.4: CUSUM alarmed **and** the slope agrees. */
    DEGRADED,
}

/** §6.18.5. One joint's drift across the change point, in units of the physio's own variation. */
data class FeatureDrift(
    val feature: FeatureId,
    /** `e_post − e_pre`, signed. §6.18.5's `Δ_j`. */
    val deltaSigma: Double,
    val meanSignedZBefore: Double,
    val meanSignedZAfter: Double,
)

/**
 * §6.18's whole output.
 *
 * Deliberately reports the CUSUM alarm and the slope **separately** as well as their conjunction.
 * §6.18.4 requires both to declare degradation, and a reader who cannot see which of the two failed
 * cannot tell a stable set from a noisy one that nearly fired.
 */
data class FatigueResult(
    val status: FatigueStatus,
    /** `B`, median of `Q_2..Q_4`. Null when there were not enough reps to assess. */
    val baseline: Double? = null,
    /** `s`, the MAD-derived scale with its floor applied. */
    val scale: Double? = null,
    /** The valid-rep ordinal at which `S_i > h`, or null if it never did. */
    val alarmRep: Int? = null,
    /**
     * §6.18.3's change point: the rep at which the decline began accumulating.
     *
     * A **valid-rep ordinal**, 1-based, because that is the number the patient's counter shows and
     * therefore the only number "deteriorated since Rep 5" can honestly mean.
     */
    val changePointRep: Int? = null,
    /** The same rep's [RepRecord.index], for a log or a database row that must point at the record. */
    val changePointRecordIndex: Int? = null,
    /** Least-squares fit of `Q_i` against `i`, in quality points per rep. */
    val slopePerRep: Double? = null,
    /** §6.18.5's top 2 by `|Δ_j|`. Empty when there is no change point to attribute across. */
    val attribution: List<FeatureDrift> = emptyList(),
    /** `(mean ROM_post / mean ROM_pre − 1) × 100`. Typically shrinks under fatigue. */
    val romChangePercent: Double? = null,
    /** `(mean T_post / mean T_pre − 1) × 100`. Typically slows. */
    val tempoChangePercent: Double? = null,
    /** `SPARC_post − SPARC_pre`. Negative is roughening. Null when SPARC was not computable. */
    val smoothnessChange: Double? = null,
    /** §6.18.6. Degradation declared **and** two consecutive reps under `0.7·B`. */
    val stopRecommended: Boolean = false,
) {
    val declaredDegradation: Boolean get() = status == FatigueStatus.DEGRADED

    companion object {
        val INSUFFICIENT = FatigueResult(FatigueStatus.INSUFFICIENT_REPS)
    }
}

/**
 * §6.18 end to end over one set.
 *
 * @param frameRateHz used only for §6.18.5's smoothness term, to put the velocity profile's
 *   spectrum on a frequency axis. Everything else is rate-independent.
 */
fun assessFatigue(
    session: SessionRecord,
    frameRateHz: Double = Params.NOMINAL_FPS.toDouble(),
): FatigueResult {
    val valid = session.reps.filter { it.valid }
    val quality = valid.mapNotNull { it.quality }

    // §6.18.1. Below six the honest answer is that we cannot tell, and a change point derived from
    // four numbers is worse than no change point: it would be believed.
    if (valid.size < SessionRecord.MIN_REPS_FOR_FATIGUE || quality.size != valid.size) {
        return FatigueResult.INSUFFICIENT
    }

    // §6.18.2. Q_1 is skipped: the first rep is the patient finding their position, and including
    // it drags the baseline down, which masks exactly the decline this is looking for.
    val baselineWindow = quality.subList(1, minOf(4, quality.size))
    val baseline = median(baselineWindow)
    val deviations = baselineWindow.map { abs(it - baseline) }
    val scale = maxOf(
        Params.MAD_TO_SIGMA * median(deviations),
        Params.FATIGUE_SCALE_FLOOR_POINTS,
    )

    // §6.18.3. One-sided: only decline is of interest, so improvement resets the sum rather than
    // banking credit against a later decline.
    val allowance = Params.CUSUM_ALLOWANCE_SIGMA * scale
    val threshold = Params.CUSUM_THRESHOLD_SIGMA * scale
    val sums = DoubleArray(quality.size)
    sums[0] = 0.0
    for (i in 1 until quality.size) {
        sums[i] = maxOf(0.0, sums[i - 1] + (baseline - quality[i] - allowance))
    }
    val alarmIndex = sums.indices.firstOrNull { sums[it] > threshold }

    val slope = leastSquaresSlope(quality)

    if (alarmIndex == null) {
        return FatigueResult(
            status = FatigueStatus.STABLE,
            baseline = baseline,
            scale = scale,
            slopePerRep = slope,
        )
    }

    // §6.18.3's change point: the last index before the alarm at which the sum was still zero, plus
    // one. S_1 is zero by definition and an alarm needs S_i > h > 0, so this always exists.
    val lastZero = (alarmIndex - 1 downTo 0).firstOrNull { sums[it] == 0.0 } ?: 0
    val changePointIndex = lastZero + 1

    // §6.18.4. Two independent signals agreeing is enough; either alone is not.
    val corroborated = slope < Params.CORROBORATING_SLOPE_POINTS_PER_REP
    val status = if (corroborated) FatigueStatus.DEGRADED else FatigueStatus.STABLE

    val before = valid.subList(0, changePointIndex)
    val after = valid.subList(changePointIndex, valid.size)

    return FatigueResult(
        status = status,
        baseline = baseline,
        scale = scale,
        alarmRep = alarmIndex + 1,
        changePointRep = changePointIndex + 1,
        changePointRecordIndex = valid[changePointIndex].index,
        slopePerRep = slope,
        attribution = attribute(before, after),
        romChangePercent = percentChange(before.map { it.romDegrees }, after.map { it.romDegrees }),
        tempoChangePercent = percentChange(
            before.map { it.durationSeconds },
            after.map { it.durationSeconds },
        ),
        smoothnessChange = smoothnessChange(before, after, frameRateHz),
        stopRecommended = corroborated && twoConsecutiveBelowSafety(quality, baseline),
    )
}

/**
 * §6.18.5. Mean **signed** z per feature either side of the change point, ranked by `|Δ|`.
 *
 * Signed throughout, because the direction is the finding: a knee drifting further into valgus and
 * one drifting out of it share a magnitude and are opposite clinical statements.
 */
internal fun attribute(before: List<RepRecord>, after: List<RepRecord>): List<FeatureDrift> {
    if (before.isEmpty() || after.isEmpty()) return emptyList()
    val features = (before + after).flatMap { it.features.keys }.toSet()
    return features.mapNotNull { feature ->
        val pre = weightedSignedZ(before, feature) ?: return@mapNotNull null
        val post = weightedSignedZ(after, feature) ?: return@mapNotNull null
        FeatureDrift(feature, post - pre, pre, post)
    }
        .sortedByDescending { abs(it.deltaSigma) }
        .take(Params.FATIGUE_ATTRIBUTION_COUNT)
}

/** Weighted by frames, so a mean over three frames does not outweigh one over ninety. */
private fun weightedSignedZ(reps: List<RepRecord>, feature: FeatureId): Double? {
    val samples = reps.mapNotNull { it.features[feature] }.filter { it.frames > 0 }
    if (samples.isEmpty()) return null
    val frames = samples.sumOf { it.frames }
    return samples.sumOf { it.meanSignedZ * it.frames } / frames
}

/** §6.18.4's `slope`: least-squares fit of `Q_i` against `i`, over the whole set. */
internal fun leastSquaresSlope(quality: List<Double>): Double {
    val n = quality.size
    if (n < 2) return 0.0
    val meanIndex = (n - 1) / 2.0
    val meanQuality = quality.average()
    var covariance = 0.0
    var variance = 0.0
    quality.forEachIndexed { index, value ->
        val dx = index - meanIndex
        covariance += dx * (value - meanQuality)
        variance += dx * dx
    }
    return if (variance <= 0.0) 0.0 else covariance / variance
}

/** `(mean_post / mean_pre − 1) × 100`, or null when the pre-mean cannot carry a ratio. */
internal fun percentChange(before: List<Double>, after: List<Double>): Double? {
    if (before.isEmpty() || after.isEmpty()) return null
    val pre = before.average()
    val post = after.average()
    if (!pre.isFinite() || !post.isFinite() || abs(pre) < 1e-9) return null
    return (post / pre - 1.0) * 100.0
}

/** §6.18.5's `ΔSmooth`, averaged per rep either side. Null when SPARC could not be computed. */
private fun smoothnessChange(
    before: List<RepRecord>,
    after: List<RepRecord>,
    frameRateHz: Double,
): Double? {
    val pre = before.mapNotNull { sparcOf(it, frameRateHz) }
    val post = after.mapNotNull { sparcOf(it, frameRateHz) }
    if (pre.isEmpty() || post.isEmpty()) return null
    return post.average() - pre.average()
}

private fun sparcOf(rep: RepRecord, frameRateHz: Double): Double? {
    val speed = speedOf(rep.velocityProfile)
    // The profile only collects frames that scored, so its own length over the rep's duration is a
    // better rate estimate than the nominal one - and the nominal one is the fallback.
    val rate = if (rep.durationSeconds > 0.0 && speed.size > 1) {
        speed.size / rep.durationSeconds
    } else {
        frameRateHz
    }
    return spectralArcLength(speed, rate)
}

/** §6.18.6's second condition: `Q_i < 0.7·B` for two consecutive reps. */
internal fun twoConsecutiveBelowSafety(quality: List<Double>, baseline: Double): Boolean {
    val limit = Params.FATIGUE_SAFETY_QUALITY_FRACTION * baseline
    return quality.zipWithNext().any { (first, second) -> first < limit && second < limit }
}
