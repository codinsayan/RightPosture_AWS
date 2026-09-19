package com.example.rightposture.core.live

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.reference.FeatureReference
import com.example.rightposture.core.reference.WatchList
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * §6.17. Frame, rep and set scoring.
 *
 * The exponential shape is chosen in §6.17.1: forgiving of small deviations, sharp on real faults,
 * bounded in [0, 100] without clamping, and impossible for one bad joint to drag negative.
 */

/** One frame's score and where in the rep it happened. */
data class ScoredFrame(val phase: Double, val score: Double)

/** One finished rep. [valid] is §6.12.4's distinction, and it decides set membership. */
data class ScoredRep(val quality: Double, val valid: Boolean)

data class SetScore(
    /** Median quality over **valid** reps, or null when the set produced none. */
    val score: Double?,
    val completionRate: Double,
    val validReps: Int,
)

/**
 * §6.17.1. `100 · exp(−λ · Σ w z̃² / Σ w)`, over valid, watch-listed features only.
 *
 * Weights are renormalised across the features actually present, so a feature that abstained this
 * frame (1.6) neither contributes nor silently counts as agreement.
 *
 * @return null when nothing valid is left. 1.6's rule: empty means "no score", never zero - a zero
 *   reads as a perfect failure the patient earned by being invisible.
 */
fun frameScore(zScores: Map<FeatureId, FeatureZ>, watchList: WatchList): Double? {
    if (zScores.isEmpty()) return null
    val weights = watchList.features
        .filter { zScores.containsKey(it.id) }
        .associate { it.id to it.weight }
    val totalWeight = weights.values.sum()
    if (totalWeight <= 0.0) return null

    val weighted = weights.entries.sumOf { (id, weight) ->
        // §6.17.1: the clamp at 6 stops one catastrophic landmark glitch zeroing an otherwise good
        // rep. It caps the damage, it does not hide the fault - 6.14 still sees the raw z.
        val clamped = abs(zScores.getValue(id).z).coerceIn(0.0, Params.Z_CLAMP)
        weight * clamped * clamped
    }
    return 100.0 * exp(-Params.FRAME_SCORE_DECAY * weighted / totalWeight)
}

/**
 * §6.17.2. The contiguous phase range where the physio was most controlled - `σ_dominant[p]` in its
 * lowest tercile - which is where the therapeutic end-range work lives.
 *
 * Falls back to `[40, 60]` when derivation fails, including the case §6.17.2 does not name: a
 * reference with near-uniform sigma qualifies everywhere, and a "critical window" covering most of
 * the rep weights nothing relative to anything else. Treated as a failed derivation rather than
 * shipped as a window.
 */
fun criticalWindow(dominant: FeatureReference): IntRange {
    val default = Params.CRITICAL_WINDOW_DEFAULT_START..Params.CRITICAL_WINDOW_DEFAULT_END
    val sigma = dominant.sigma
    if (sigma.size < 3 || sigma.any { !it.isFinite() }) return default

    val sorted = sigma.sorted()
    val threshold = sorted[(sorted.size / 3).coerceAtMost(sorted.size - 1)]

    var bestStart = -1
    var bestEnd = -1
    var runStart = -1
    sigma.indices.forEach { bin ->
        if (sigma[bin] <= threshold) {
            if (runStart < 0) runStart = bin
            if (bin - runStart > bestEnd - bestStart) {
                bestStart = runStart
                bestEnd = bin
            }
        } else {
            runStart = -1
        }
    }

    if (bestStart < 0) return default
    val span = bestEnd - bestStart + 1
    if (span > sigma.size * Params.CRITICAL_WINDOW_MAX_GRID_FRACTION) return default
    return bestStart..bestEnd
}

/** §6.17.2. Double weight inside the critical window, single weight everywhere else. */
fun phaseWeight(phase: Double, critical: IntRange): Double =
    if (phase.roundToInt() in critical) Params.CRITICAL_PHASE_WEIGHT else 1.0

/**
 * §6.17.3 ROM penalty. 95% of the reference range earns full credit; below that the penalty scales
 * linearly.
 *
 * §6.17.3 calls this "the single most important line in the scoring function", and the reason is
 * worth keeping in front of whoever edits it: without it a patient who barely moves scores
 * beautifully, because they never leave the reference band near their own shortened turnaround.
 */
fun romPenalty(romDegrees: Double, referenceRom: Double): Double {
    if (referenceRom <= 0.0 || !romDegrees.isFinite()) return 0.0
    return (romDegrees / (Params.ROM_FULL_CREDIT_FRACTION * referenceRom)).coerceIn(0.0, 1.0)
}

/**
 * §6.17.3 tempo penalty.
 *
 * Rushing is penalised harder than going slow - floor 0.6 against 0.8 - because a momentum-driven
 * rep bypasses the muscle work entirely, while a laboured one does not.
 */
fun tempoPenalty(durationSeconds: Double, referencePeriodSeconds: Double): Double {
    if (referencePeriodSeconds <= 0.0 || !durationSeconds.isFinite() || durationSeconds <= 0.0) return 1.0
    val ratio = durationSeconds / referencePeriodSeconds
    return when {
        ratio < Params.TEMPO_FAST_RATIO ->
            (ratio / Params.TEMPO_FAST_RATIO).coerceIn(Params.TEMPO_FAST_PENALTY_FLOOR, 1.0)
        ratio > Params.TEMPO_SLOW_RATIO ->
            (Params.TEMPO_SLOW_RATIO / ratio).coerceIn(Params.TEMPO_SLOW_PENALTY_FLOOR, 1.0)
        else -> 1.0
    }
}

/**
 * §6.17.3. Phase-weighted mean frame score across the rep, then the two penalties.
 *
 * @return null when no frame in the rep could be scored at all.
 */
fun repScore(
    frames: List<ScoredFrame>,
    critical: IntRange,
    romDegrees: Double,
    referenceRom: Double,
    durationSeconds: Double,
    referencePeriodSeconds: Double,
): Double? {
    if (frames.isEmpty()) return null
    val totalWeight = frames.sumOf { phaseWeight(it.phase, critical) }
    if (totalWeight <= 0.0) return null
    val mean = frames.sumOf { phaseWeight(it.phase, critical) * it.score } / totalWeight
    return mean * romPenalty(romDegrees, referenceRom) * tempoPenalty(durationSeconds, referencePeriodSeconds)
}

/**
 * §6.17.4. Median over **valid** reps, so one dropped rep does not define the session, and partial
 * reps (§6.12.4) are excluded from the statistic entirely rather than dragging it down.
 */
fun setScore(reps: List<ScoredRep>, prescribedReps: Int): SetScore {
    val valid = reps.filter { it.valid }.map { it.quality }.sorted()
    val completion = if (prescribedReps <= 0) 0.0 else valid.size.toDouble() / prescribedReps
    if (valid.isEmpty()) return SetScore(null, completion, 0)
    val middle = valid.size / 2
    val median = if (valid.size % 2 == 1) {
        valid[middle]
    } else {
        (valid[middle - 1] + valid[middle]) / 2.0
    }
    return SetScore(median, completion, valid.size)
}
