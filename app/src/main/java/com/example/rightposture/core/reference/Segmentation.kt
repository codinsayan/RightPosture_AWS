package com.example.rightposture.core.reference

import com.example.rightposture.core.Params
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ExtremumKind { PEAK, VALLEY }

data class Extremum(val index: Int, val kind: ExtremumKind, val prominence: Double)

data class RepSegment(val startIndex: Int, val endIndex: Int) {
    val lengthSamples: Int get() = endIndex - startIndex
}

data class SegmentationResult(
    /** Which extremum the rep boundaries sit on (section 6.7.5). */
    val boundaryKind: ExtremumKind,
    /** The alternating peak/valley sequence after the section 6.7.4 repair. */
    val extrema: List<Extremum>,
    /** Every candidate rep, before any rejection. */
    val detected: List<RepSegment>,
    /** Survivors of section 6.7.6 steps 1, 2 and 4. Empty when the recording is rejected. */
    val usable: List<RepSegment>,
    /** Non-null when the recording is rejected; names the reason for the physio. */
    val rejection: String?,
)

/**
 * Sections 6.7.3 to 6.7.6: extrema, alternation repair, rep boundaries, outlier rejection.
 *
 * [periodSamples] comes from [estimatePeriod]. [signal] must be the filtered dominant feature over
 * the whole recording.
 *
 * Section 6.7.6 step 3 (shape outliers) is **not** applied here. It needs each rep resampled onto a
 * common phase grid, which arrives in 3.5; running it before that would mean two different notions
 * of rep shape.
 */
fun segmentReps(signal: List<Float>, periodSamples: Int): SegmentationResult {
    val empty = SegmentationResult(ExtremumKind.VALLEY, emptyList(), emptyList(), emptyList(), null)
    if (periodSamples < 2 || signal.size < 3 || !signal.all(Float::isFinite)) {
        return empty.copy(rejection = "Recording is too short or contains invalid samples.")
    }

    val values = signal.map(Float::toDouble)
    val swing = percentileSwingOf(values)
    val minProminence = Params.PEAK_MIN_PROMINENCE_SWING_FRACTION * swing
    val minSpacing = (Params.PEAK_MIN_SPACING_PERIOD_FRACTION * periodSamples).roundToInt()

    val peaks = acceptExtrema(values, ExtremumKind.PEAK, minSpacing, minProminence)
    val valleys = acceptExtrema(values, ExtremumKind.VALLEY, minSpacing, minProminence)
    val extrema = enforceAlternation(peaks + valleys, values)

    val boundaryKind = chooseBoundaryKind(extrema, values, periodSamples)
    val boundaries = extrema.filter { it.kind == boundaryKind }
    val detected = boundaries.zipWithNext { start, end -> RepSegment(start.index, end.index) }

    if (detected.size < 3) {
        return empty.copy(
            boundaryKind = boundaryKind,
            extrema = extrema,
            detected = detected,
            rejection = "Only ${detected.size} reps detected; at least " +
                "${Params.MIN_SURVIVING_REPS + 2} are needed once the first and last are dropped.",
        )
    }

    // Step 1. The first rep is the physio settling into position and the last is them reaching for
    // the stop button. Section 6.7.6 is explicit that this is reliably true rather than a heuristic
    // worth being clever about.
    val trimmed = detected.subList(1, detected.size - 1)

    // Step 2. Duration outliers against the median, not the mean: with six to eight reps a single
    // long rep drags the mean far enough to hide itself.
    val medianDuration = medianOf(trimmed.map { it.lengthSamples.toDouble() })
    val usable = trimmed.filter {
        abs(it.lengthSamples - medianDuration) <= Params.DURATION_OUTLIER_MEDIAN_FRACTION * medianDuration
    }

    // Step 4.
    val rejection = if (usable.size < Params.MIN_SURVIVING_REPS) {
        "Only ${usable.size} usable reps after outlier rejection; " +
            "${Params.MIN_SURVIVING_REPS} are required."
    } else {
        null
    }

    return SegmentationResult(
        boundaryKind = boundaryKind,
        extrema = extrema,
        detected = detected,
        usable = if (rejection == null) usable else emptyList(),
        rejection = rejection,
    )
}

/**
 * Section 6.7.3. Candidates ranked by prominence, then greedily accepted subject to the 0.6 x T
 * spacing rule — which the plan calls the single highest-value rule in the section, because it is
 * what stops mid-rep wobble splitting one rep into two.
 */
internal fun acceptExtrema(
    values: List<Double>,
    kind: ExtremumKind,
    minSpacing: Int,
    minProminence: Double,
): List<Extremum> {
    // Valleys are maxima of the negated signal, so one implementation covers both. Prominence is
    // a vertical distance and survives the negation unchanged.
    val oriented = if (kind == ExtremumKind.PEAK) values else values.map { -it }

    return localMaxima(oriented)
        .map { index -> Extremum(index, kind, prominenceAt(oriented, index)) }
        .filter { it.prominence >= minProminence }
        .sortedWith(compareByDescending<Extremum> { it.prominence }.thenBy { it.index })
        .fold(mutableListOf<Extremum>()) { accepted, candidate ->
            if (accepted.all { abs(candidate.index - it.index) >= minSpacing }) accepted.add(candidate)
            accepted
        }
        .sortedBy { it.index }
}

/** Indices that are at least as high as both neighbours; a flat top reports its first sample. */
private fun localMaxima(values: List<Double>): List<Int> =
    (1 until values.lastIndex).filter { values[it] > values[it - 1] && values[it] >= values[it + 1] }

/**
 * Prominence: the vertical drop required to reach a higher point in either direction. Walk out
 * both ways until something higher appears, tracking the lowest point along the way.
 */
private fun prominenceAt(values: List<Double>, index: Int): Double {
    val peak = values[index]

    fun lowestUntilHigher(range: IntProgression): Double {
        var lowest = peak
        for (i in range) {
            if (values[i] > peak) return lowest
            if (values[i] < lowest) lowest = values[i]
        }
        return lowest
    }

    val left = lowestUntilHigher(index - 1 downTo 0)
    val right = lowestUntilHigher(index + 1..values.lastIndex)
    return peak - maxOf(left, right)
}

/**
 * Section 6.7.4. Two peaks in a row means a valley was missed, so insert the lowest sample between
 * them; symmetrically for two valleys. If the repair lands on one of the endpoints there is nothing
 * real between them, so the offending pair is dropped instead.
 */
internal fun enforceAlternation(extrema: List<Extremum>, values: List<Double>): List<Extremum> {
    val ordered = extrema.sortedBy { it.index }
    if (ordered.size < 2) return ordered

    val result = mutableListOf(ordered.first())
    for (next in ordered.drop(1)) {
        val previous = result.last()
        if (previous.kind != next.kind) {
            result.add(next)
            continue
        }

        val missingKind = if (previous.kind == ExtremumKind.PEAK) ExtremumKind.VALLEY else ExtremumKind.PEAK
        val span = (previous.index + 1) until next.index
        val repairIndex = when {
            span.isEmpty() -> null
            missingKind == ExtremumKind.VALLEY -> span.minByOrNull { values[it] }
            else -> span.maxByOrNull { values[it] }
        }

        if (repairIndex == null) {
            // Still degenerate: drop the pair rather than invent a boundary. Keeping the stronger
            // of the two would silently bias the rep count.
            result.removeAt(result.lastIndex)
        } else {
            result.add(Extremum(repairIndex, missingKind, 0.0))
            result.add(next)
        }
    }
    return result
}

/**
 * Section 6.7.5, amended 2026-10-03. Rep boundaries sit on the **rest pose**: the extremum kind
 * whose typical value is closest to where the recording starts and ends.
 *
 * The plan's rule was "whichever kind yields more complete segments wins, the start pose breaks
 * ties". That made phase 0 an accident of where the recording was cut: a physio who starts and ends
 * at rest has one more end-range extremum than rest ones, so their reps were bounded at end range,
 * and phase 0 - `thetaStart`, and the ghost's first pose - became the arm *up*. Two recordings of the
 * same movement then disagreed by half a rep (found on the ghost check, 10.6's device gate). The
 * cost of the amendment is at most one fewer segment, which the rejection message already reports.
 */
internal fun chooseBoundaryKind(extrema: List<Extremum>, values: List<Double>, periodSamples: Int = 0): ExtremumKind {
    val peaks = extrema.filter { it.kind == ExtremumKind.PEAK }
    val valleys = extrema.filter { it.kind == ExtremumKind.VALLEY }
    if (peaks.isEmpty() || valleys.isEmpty()) return if (peaks.size >= valleys.size) ExtremumKind.PEAK else ExtremumKind.VALLEY

    // Rest, as the median of the recording's opening and closing quarter-rep: robust to one stray
    // sample at t = 0, and a recording that ends somewhere else still has its start to vote.
    val edge = (periodSamples / 4).coerceIn(1, values.size)
    val rest = medianOf(values.take(edge) + values.takeLast(edge))
    val peakDistance = abs(medianOf(peaks.map { values[it.index] }) - rest)
    val valleyDistance = abs(medianOf(valleys.map { values[it.index] }) - rest)
    return if (peakDistance < valleyDistance) ExtremumKind.PEAK else ExtremumKind.VALLEY
}

internal fun medianOf(values: List<Double>): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}

private fun percentileSwingOf(values: List<Double>): Double {
    val sorted = values.sorted()
    fun at(fraction: Double): Double {
        val position = fraction * sorted.lastIndex
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        val weight = position - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }
    return at(0.95) - at(0.05)
}
