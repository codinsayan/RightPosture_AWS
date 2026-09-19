package com.example.rightposture.core.reference

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import kotlin.math.abs

/**
 * §6.8. Phase is defined from the **dominant feature's value**, never from elapsed time.
 *
 * §6.8.1 calls this the subtlest correctness issue in the system. If authoring indexed the
 * reference by time within the rep while the live pipeline estimated phase from the dominant
 * joint's value, a patient moving at a different tempo — the entire point of the product — would be
 * compared against the wrong part of the reference, and every z-score would be wrong in a
 * tempo-dependent way. The same definition therefore has to serve both pipelines, which is why it
 * lives in `core` rather than in the authoring path.
 */

/** One rep resampled onto the §6.8.4 phase grid. */
data class ResampledRep(
    /** 101 bins per feature, `p = 0..100`. Features invalid anywhere in the rep are absent. */
    val bins: Map<FeatureId, List<Float>>,
    /**
     * §6.8.4: absolute range of motion, `|θ_peak − θ_start|`, recorded **separately** from phase.
     * Phase is normalised against the rep's own extremum, so a short rep still spans 0 to 100 and
     * would otherwise hide its own undershoot.
     */
    val rom: Double,
)

/**
 * §6.8.2–6.8.3. Returns `p ∈ [0, 100]` for every frame from [start] to [end] inclusive, or null
 * when the rep is degenerate (the dominant feature never moved).
 *
 * `p` is non-decreasing by construction: `50φ` rising on the outbound half, `100 − 50φ` rising on
 * the return half, meeting at 50 where `φ = 1`.
 */
fun phaseProfile(dominant: List<Double>, start: Int, extremum: Int, end: Int): List<Double>? {
    if (start >= extremum || extremum >= end || end > dominant.lastIndex) return null

    val startValue = dominant[start]
    val peakValue = dominant[extremum]
    val span = peakValue - startValue
    if (!span.isFinite() || abs(span) < Params.MIN_VECTOR_LENGTH) return null

    fun phaseOf(index: Int) = ((dominant[index] - startValue) / span).coerceIn(0.0, 1.0)

    val profile = ArrayList<Double>(end - start + 1)

    // Outbound: cumulative maximum. Within a half-rep the joint genuinely moves one way, so a
    // reversal is noise or tremor and must not be modelled as phase (§6.8.3).
    var running = 0.0
    for (index in start..extremum) {
        running = maxOf(running, phaseOf(index))
        profile.add(50.0 * running)
    }

    // Return: cumulative minimum, mapped to 100 - 50φ so p keeps rising.
    running = 1.0
    for (index in (extremum + 1)..end) {
        running = minOf(running, phaseOf(index))
        profile.add(100.0 - 50.0 * running)
    }

    return profile
}

/**
 * §6.8.4. Resamples every feature in [signals] onto `P = 101` phase bins by linear interpolation.
 *
 * A feature carrying a non-finite sample anywhere inside the rep is omitted, so the gate "101 finite
 * bins per valid feature" holds for everything returned.
 */
fun resampleRep(
    signals: Map<FeatureId, List<Float>>,
    dominant: FeatureId,
    start: Int,
    extremum: Int,
    end: Int,
): ResampledRep? {
    val dominantSignal = signals[dominant]?.map(Float::toDouble) ?: return null
    val profile = phaseProfile(dominantSignal, start, extremum, end) ?: return null

    val bins = signals.mapNotNull { (id, signal) ->
        if (end > signal.lastIndex) return@mapNotNull null
        val window = signal.subList(start, end + 1)
        if (!window.all(Float::isFinite)) return@mapNotNull null
        id to interpolateOntoBins(profile, window)
    }.toMap()

    return ResampledRep(bins, abs(dominantSignal[extremum] - dominantSignal[start]))
}

/** Convenience over a whole segmentation: resamples every usable rep. */
fun resampleUsableReps(
    segmentation: SegmentationResult,
    signals: Map<FeatureId, List<Float>>,
    dominant: FeatureId,
): List<ResampledRep> = segmentation.usable.mapNotNull { segment ->
    val extremum = extremumInside(segmentation, segment) ?: return@mapNotNull null
    resampleRep(signals, dominant, segment.startIndex, extremum, segment.endIndex)
}

/** The mid-rep extremum, taken from the alternating sequence rather than recomputed. */
internal fun extremumInside(segmentation: SegmentationResult, segment: RepSegment): Int? =
    segmentation.extrema
        .firstOrNull { it.index > segment.startIndex && it.index < segment.endIndex }
        ?.index

private fun interpolateOntoBins(profile: List<Double>, values: List<Float>): List<Float> {
    val bins = ArrayList<Float>(Params.PHASE_BINS)
    var frame = 0

    for (bin in 0 until Params.PHASE_BINS) {
        val target = bin.toDouble()

        // profile is non-decreasing, so one forward walk covers every bin.
        while (frame < profile.lastIndex - 1 && profile[frame + 1] < target) frame++

        val lower = profile[frame]
        val upper = profile[frame + 1]
        val gap = upper - lower

        bins.add(
            when {
                target <= lower -> values[frame]
                target >= profile.last() -> values.last()
                // §6.22: the movement stalled, so interpolating would divide by ~zero. Take the
                // earlier frame. A held end-range position lands here, which is why the reference
                // legitimately shows very small sigma around bin 50.
                gap < 1e-6 -> values[frame]
                else -> {
                    val weight = ((target - lower) / gap).coerceIn(0.0, 1.0)
                    ((1.0 - weight) * values[frame] + weight * values[frame + 1]).toFloat()
                }
            },
        )
    }
    return bins
}
