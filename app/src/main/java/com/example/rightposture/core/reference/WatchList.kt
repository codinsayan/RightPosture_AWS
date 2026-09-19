package com.example.rightposture.core.reference

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params

/**
 * §6.10. The physio never tells us which joints matter, so we infer it: **joints they controlled
 * tightly relative to how much they moved are the joints they cared about.**
 */

data class WatchedFeature(
    val id: FeatureId,
    /** `ROM / median σ`. High means a lot of movement held inside a narrow band. */
    val salience: Double,
    /** §6.10 frame-score weight, normalised across the watch list. */
    val weight: Double,
)

data class WatchList(
    val features: List<WatchedFeature>,
    /** Features that failed a filter, with the filter that rejected them. For the 3.8 review UI. */
    val rejected: Map<FeatureId, String>,
)

/**
 * `salience = ROM / median_p σ[p]`.
 *
 * Uses the **pre-clamp, pre-inflation** median sigma. §6.9.4 clamps stored sigma at 15° and then
 * inflates it, so measuring salience off the stored band would both compress the ranking at the top
 * and shift every value by `k_inflate`.
 */
fun salienceOf(feature: FeatureReference): Double {
    if (!feature.medianSigmaRaw.isFinite()) return 0.0
    val sigma = if (feature.medianSigmaRaw > 0.0) {
        feature.medianSigmaRaw
    } else {
        maxOf(Params.SIGMA_FLOOR_DEGREES, Params.SIGMA_FLOOR_ROM_FRACTION * feature.rom)
    }
    return feature.rom / sigma
}

/**
 * §6.10 selection.
 *
 * **Every filter below is a PASS condition.** §6.10 rewrote its own table for this reason: mixing
 * pass and fail phrasing across rows is how an inverted filter gets written, and an inverted filter
 * corrupts every downstream score.
 *
 * Filter 2 is the one §6.10 singles out as most likely to be inverted. Read it as: **small σ is
 * good.** A feature with median σ = 4° was tightly controlled and belongs here; one with median
 * σ = 18° wandered freely and must be excluded. It reuses `SIGMA_CAP_DEGREES` rather than a
 * separate constant, because §6.9.4 warns that if the cap moves and this threshold does not, the
 * two silently disagree.
 *
 * @param meanVisibility mean landmark visibility per feature over the recording, for filter 3.
 *   A feature absent from the map is treated as unobserved and fails.
 */
fun selectWatchList(
    reference: Reference,
    meanVisibility: Map<FeatureId, Float>,
    dominant: FeatureId,
): WatchList {
    val rejected = mutableMapOf<FeatureId, String>()

    val surviving = reference.features.mapNotNull { (id, feature) ->
        val salience = salienceOf(feature)
        val visibility = meanVisibility[id] ?: 0f

        val failure = when {
            feature.rom < Params.WATCH_LIST_MIN_ROM_DEGREES ->
                "ROM ${format(feature.rom)}° is under ${Params.WATCH_LIST_MIN_ROM_DEGREES}°: barely moved."
            feature.medianSigmaRaw >= Params.SIGMA_CAP_DEGREES ->
                "median σ ${format(feature.medianSigmaRaw)}° is at or over " +
                    "${Params.SIGMA_CAP_DEGREES}°: unconstrained."
            visibility < Params.WATCH_LIST_MIN_VISIBILITY ->
                "mean visibility ${format(visibility.toDouble())} is under " +
                    "${Params.WATCH_LIST_MIN_VISIBILITY}: poorly observed."
            salience < Params.MIN_SALIENCE ->
                "salience ${format(salience)} is under ${Params.MIN_SALIENCE}: moves no more than " +
                    "its own rep-to-rep variance."
            else -> null
        }

        if (failure != null) {
            rejected[id] = failure
            null
        } else {
            id to salience
        }
    }

    val top = surviving
        .sortedWith(compareByDescending<Pair<FeatureId, Double>> { it.second }.thenBy { it.first.ordinal })
        .take(Params.WATCH_LIST_SIZE)
        .toMutableList()

    // §6.10: the dominant feature is always included regardless of rank. It is what phase is
    // derived from (§6.8.2), so a reference that does not score it is scoring nothing coherent.
    if (top.none { it.first == dominant }) {
        reference.features[dominant]?.let { top.add(dominant to salienceOf(it)) }
    }

    val total = top.sumOf { it.second }
    val features = if (total > 0.0 && total.isFinite()) {
        top.map { (id, salience) -> WatchedFeature(id, salience, salience / total) }
    } else {
        // Degenerate: no usable salience anywhere. Share the weight equally rather than emitting
        // NaN weights that would poison every frame score.
        top.map { (id, salience) -> WatchedFeature(id, salience, 1.0 / top.size) }
    }

    return WatchList(features, rejected)
}

private fun format(value: Double) = String.format("%.1f", value)
