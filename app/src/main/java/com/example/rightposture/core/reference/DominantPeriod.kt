package com.example.rightposture.core.reference

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PeriodEstimate(
    val lagSamples: Int,
    val seconds: Double,
    val correlation: Double,
)

/**
 * Coarse §6.7.1 choice using 5th/95th percentile swing.
 *
 * Percentiles rather than min/max so one spurious frame cannot win the ranking. This is only the
 * first of §6.7.1's two passes; the second recomputes swing over clean segments and confirms or
 * switches the choice, and needs the segmentation that lands in 3.4.
 *
 * Note: swings are compared raw, as §6.7.1 specifies. Fourteen features are degrees, but
 * `shoulder_elevation` is a dimensionless ratio x100, so it is not strictly commensurable with the
 * rest. §6.7.1 does not address this and no normalisation was invented here; the plan's own
 * argument is that the principal joint's swing is an order of magnitude clear of the others.
 */
fun selectCoarseDominantFeature(signals: Map<FeatureId, List<Float>>): FeatureId? =
    rankCoarseDominantFeatures(signals).firstOrNull()

private fun rankCoarseDominantFeatures(signals: Map<FeatureId, List<Float>>): List<FeatureId> =
    FeatureId.entries
        .mapNotNull { id ->
            signals[id]
                ?.takeIf { it.size >= 2 && it.all(Float::isFinite) }
                ?.let { id to percentileSwing(it) }
        }
        .sortedByDescending { it.second }
        .map { it.first }

data class DominantPeriodSelection(
    val feature: FeatureId,
    val period: PeriodEstimate,
)

/**
 * Keeps the 6.7.1 swing ranking, but skips a higher-swing feature when it has no repeated period.
 * Real pose depth can make trunk rotation noisier than a clean limb motion; rejecting the whole
 * recording at that first noisy candidate hides the periodic movement already present below it.
 */
fun selectDominantFeatureAndPeriod(
    signals: Map<FeatureId, List<Float>>,
    sampleRateHz: Double,
): DominantPeriodSelection? = rankCoarseDominantFeatures(signals).firstNotNullOfOrNull { feature ->
    estimatePeriod(signals.getValue(feature), sampleRateHz)?.let { DominantPeriodSelection(feature, it) }
}

/** Returns null for the documented unconstrained-peak/hold fallback. */
/**
 * §6.7.2. Period is the lag of the **first** local maximum with `r >= 0.4`, not the global one:
 * on a highly periodic signal the global maximum can land on 2T and halve the rep count.
 *
 * Returns null when nothing qualifies. §6.7.2 calls that recording aperiodic, and the caller falls
 * back to unconstrained peak detection (for the validator to reject) or offers hold mode.
 */
fun estimatePeriod(signal: List<Float>, sampleRateHz: Double): PeriodEstimate? {
    if (!sampleRateHz.isFinite() || sampleRateHz <= 0.0) return null
    // §6.22 treats non-finite as invalid, not as a crash. A single bad sample in a recording must
    // not take the authoring pipeline down.
    if (!signal.all(Float::isFinite)) return null
    if (signal.size < 3) return null

    val mean = signal.sumOf(Float::toDouble) / signal.size
    val centred = signal.map { it - mean }
    val firstLag = (Params.AUTOCORRELATION_MIN_LAG_SECONDS * sampleRateHz)
        .roundToInt().coerceAtLeast(1)
    val lastLag = min(
        (Params.AUTOCORRELATION_MAX_LAG_SECONDS * sampleRateHz).roundToInt(),
        centred.size - 2,
    )
    if (firstLag > lastLag) return null

    // firstLag is at least round(0.5 s x fps), so for any real recording rate it exceeds 1 and
    // this lookback never reads lag 0, whose correlation is identically 1 and would swallow the
    // first candidate.
    var previous = correlation(centred, firstLag - 1)
    var current = correlation(centred, firstLag)
    for (lag in firstLag..lastLag) {
        val next = correlation(centred, lag + 1)
        if (current >= Params.R_MIN && current > previous && current >= next) {
            return PeriodEstimate(lag, lag / sampleRateHz, current)
        }
        previous = current
        current = next
    }
    return null
}

internal fun percentileSwing(values: List<Float>): Double {
    val sorted = values.map(Float::toDouble).sorted()
    return percentile(sorted, 0.95) - percentile(sorted, 0.05)
}

private fun percentile(sorted: List<Double>, fraction: Double): Double {
    val position = fraction * sorted.lastIndex
    val lower = floor(position).toInt()
    val upper = ceil(position).toInt()
    val weight = position - lower
    return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
}

private fun correlation(signal: List<Double>, lag: Int): Double {
    var product = 0.0
    var leftEnergy = 0.0
    var rightEnergy = 0.0
    for (index in 0 until signal.size - lag) {
        val left = signal[index]
        val right = signal[index + lag]
        product += left * right
        leftEnergy += left * left
        rightEnergy += right * right
    }
    val denominator = sqrt(leftEnergy * rightEnergy)
    return if (denominator == 0.0) 0.0 else product / denominator
}
