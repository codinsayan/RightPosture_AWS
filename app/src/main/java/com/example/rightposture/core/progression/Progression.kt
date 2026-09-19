package com.example.rightposture.core.progression

import kotlin.math.abs

/**
 * §6.21. Whether a patient is ready to move on, decided by arithmetic.
 *
 * Deliberately **not** an LLM decision, and deliberately not a distance between pose vectors.
 * §6.21.1 is explicit that a pose-space distance is uninterpretable and no clinician will trust it;
 * every metric here is a scalar a physiotherapist already reasons in. The model's only job, later,
 * is to phrase what this file computes - it never decides whether someone has progressed.
 *
 * Everything here is pure: no Android, no database, no clock. That is what lets §6.21.5's exact
 * "2 of the last 3" rule be tested rather than eyeballed on a device.
 */

/** §1.6 applied to a whole session: a metric that could not be computed is absent, never zero. */
data class SessionMetrics(
    val sessionId: String,
    /** §6.21.1: median of the top 3 reps' ROM. Null when no rep produced one. */
    val romPeak: Double?,
    /** Median quality over valid reps. Null when nothing was scored - **not** zero. */
    val qMean: Double?,
    val repsAtQuality: Int,
    /** Hold mode only: the longest unbroken hold, in seconds. */
    val holdSeconds: Double?,
    /**
     * §6.21.1's change point, with its two sentinels.
     *
     * `validReps + 1` when CUSUM ran and found nothing - "never degraded" has to sort as better
     * than "degraded on the last rep", and null would either crash the comparison or silently drop
     * the session. Null **only** when there were too few valid reps for CUSUM to apply, and a null
     * here excludes the session from the progression median rather than scoring it as perfect.
     */
    val degradationOnset: Int?,
    val completionRate: Double?,
    val validReps: Int,
    val prescribedReps: Int,
    /** §6.21.6. Reported by the patient, never inferred. */
    val painReported: Boolean = false,
) {
    /**
     * Whether this session may contribute to §6.21.2's median.
     *
     * A session CUSUM could not judge is not evidence either way. Including it as "never degraded"
     * would let a patient who managed three reps outrank one who managed ten.
     */
    val countsTowardProgression: Boolean
        get() = validReps >= MIN_VALID_REPS_FOR_CHANGE_POINT && qMean != null
}

/** §6.21.1: below this, CUSUM has nothing to work with. */
const val MIN_VALID_REPS_FOR_CHANGE_POINT = 6

/** §6.21.1's `reps_at_quality` threshold, and §6.21.5's quality bar. */
const val QUALITY_BAR = 80.0

/** §6.21.3's ε. Below this the goal is not meaningfully different from the baseline. */
const val GOAL_EPSILON = 1e-6

/** One metric's progress, or the honest statement that it cannot be computed. */
sealed interface Progress {
    data class Known(val fraction: Double) : Progress

    /**
     * §6.21.3's guard: the goal is within ε of the baseline, so the ratio is meaningless.
     *
     * A distinct value rather than 0.0 or 1.0, because a screen showing "100% progress" when the
     * physio simply never set a goal is a lie the patient would act on.
     */
    data object GoalNotSet : Progress

    /** Nothing has been measured yet. Distinct from zero progress, per §1.6. */
    data object NoData : Progress
}

/**
 * §6.21.2. The robust current value of one metric: median over the last three qualifying sessions.
 *
 * Median of sessions on top of median of reps. One bad day does not move the curve, and - the part
 * that matters clinically - neither does one heroic day.
 */
fun currentEstimate(sessions: List<SessionMetrics>, metric: (SessionMetrics) -> Double?): Double? {
    val values = sessions
        .filter { it.countsTowardProgression }
        .takeLast(PROGRESSION_WINDOW)
        .mapNotNull(metric)
    return if (values.isEmpty()) null else medianOf(values)
}

/** §6.21.2's window. */
const val PROGRESSION_WINDOW = 3

/**
 * §6.21.3, including the near-zero guard.
 *
 * Clamped to [0, 1] so a patient who overshoots the goal reads as complete rather than as 140%, and
 * one who has regressed below baseline reads as zero rather than negative.
 */
fun progressOf(current: Double?, baseline: Double?, goal: Double?): Progress {
    if (current == null || baseline == null || goal == null) return Progress.NoData
    if (abs(goal - baseline) < GOAL_EPSILON) return Progress.GoalNotSet
    return Progress.Known(((current - baseline) / (goal - baseline)).coerceIn(0.0, 1.0))
}

/** §6.21.3's default weights. Physio-adjustable, so they are a parameter rather than a constant. */
data class ProgressWeights(
    val rom: Double = 0.5,
    val quality: Double = 0.3,
    val repsAtQuality: Double = 0.2,
)

/**
 * §6.21.3's overall progress: the weighted mean of whichever components are actually known.
 *
 * Components that are [Progress.NoData] or [Progress.GoalNotSet] are dropped and the remaining
 * weights renormalised, rather than being folded in as zero. Treating "no goal set" as zero
 * progress would show a patient going backwards because their physio has not filled a field in.
 */
fun overallProgress(
    rom: Progress,
    quality: Progress,
    repsAtQuality: Progress,
    weights: ProgressWeights = ProgressWeights(),
): Progress {
    val parts = listOf(
        rom to weights.rom,
        quality to weights.quality,
        repsAtQuality to weights.repsAtQuality,
    ).mapNotNull { (progress, weight) ->
        (progress as? Progress.Known)?.let { it.fraction to weight }
    }
    if (parts.isEmpty()) return Progress.NoData
    val totalWeight = parts.sumOf { it.second }
    if (totalWeight < GOAL_EPSILON) return Progress.NoData
    return Progress.Known(parts.sumOf { it.first * it.second } / totalWeight)
}

/**
 * §6.21.4's intermediate target: linear interpolation in angle space, not a model.
 *
 * This is what the ghost overlay shows in early sessions, so the patient chases an achievable next
 * step rather than a full goal they cannot yet reach.
 */
fun intermediateTarget(baseline: List<Float>, goal: List<Float>, progress: Double): List<Float> {
    require(baseline.size == goal.size) {
        "baseline and goal must describe the same phase grid"
    }
    val p = progress.coerceIn(0.0, 1.0)
    return baseline.indices.map { i ->
        ((1 - p) * baseline[i] + p * goal[i]).toFloat()
    }
}

/** The outcome of §6.21.5 and §6.21.6, with the data that produced it attached. */
data class ProgressionVerdict(
    val readyToProgress: Boolean,
    val needsReview: Boolean,
    /** 8.4's gate: a flag has to show its working, so the reasons travel with it. */
    val reasons: List<String>,
    val sessionsConsidered: Int,
)

/**
 * §6.21.5 and §6.21.6, evaluated over the sessions given, newest last.
 *
 * ## One place the specification is ambiguous, and what was chosen
 *
 * §6.21.5 lists `progress_ROM ≥ 0.90` and `Q_mean ≥ 80` and then says "both conditions met in ≥ 2
 * of the last 3 sessions". Read literally the first two clauses are also standalone, which would
 * make them redundant. They are treated here as **per-session** conditions counted across the
 * window, because that is the reading in which every clause does work - and because a rule that
 * fires off a single session is exactly what §6.21.2's median exists to prevent.
 *
 * The degradation clause is evaluated on the robust current estimate rather than per session, since
 * it is the only clause §6.21.5 does not put inside the "2 of 3".
 *
 * **Never auto-advances.** This returns a flag for a clinician to act on. §6.21.5 is explicit that
 * requiring sign-off is the clinically correct answer, and it is not a detail worth trading away.
 */
fun evaluateProgression(
    sessions: List<SessionMetrics>,
    romBaseline: Double?,
    romGoal: Double?,
): ProgressionVerdict {
    val reasons = mutableListOf<String>()
    val window = sessions.filter { it.countsTowardProgression }.takeLast(PROGRESSION_WINDOW)

    if (window.isEmpty()) {
        return ProgressionVerdict(
            readyToProgress = false,
            needsReview = needsReview(sessions, reasons),
            reasons = reasons.ifEmpty { listOf("No session yet has enough valid reps to judge.") },
            sessionsConsidered = 0,
        )
    }

    // A goal nobody set is not a goal the patient missed. Saying "range met in 0 of 3" when no
    // target exists reads as a failing patient, which is the opposite of the truth and exactly the
    // sort of misleading supporting data 8.4's gate is meant to prevent.
    val goalIsSet = window.any { progressOf(it.romPeak, romBaseline, romGoal) is Progress.Known }
    if (!goalIsSet) {
        reasons += "No range goal set for this exercise, so readiness cannot be assessed."
        window.last().qMean?.let {
            reasons += "Recent quality is ${"%.0f".format(it)} out of 100."
        }
        return ProgressionVerdict(
            readyToProgress = false,
            needsReview = needsReview(sessions, reasons),
            reasons = reasons,
            sessionsConsidered = window.size,
        )
    }

    // Per-session, per §6.21.5's "2 of the last 3".
    val qualifying = window.count { session ->
        val progress = progressOf(session.romPeak, romBaseline, romGoal)
        val romOk = progress is Progress.Known && progress.fraction >= READY_ROM_PROGRESS
        val qualityOk = (session.qMean ?: return@count false) >= QUALITY_BAR
        romOk && qualityOk
    }

    // §6.21.5's last clause, on the robust estimate. A patient who reaches the range but fatigues
    // at rep 4 of 10 is not ready, and this is the only clause that notices.
    val onset = currentEstimate(window) { it.degradationOnset?.toDouble() }
    val prescribed = window.last().prescribedReps
    val onsetBar = DEGRADATION_FRACTION * prescribed
    val onsetOk = onset != null && onset >= onsetBar

    val ready = qualifying >= READY_SESSIONS_REQUIRED && onsetOk

    if (ready) {
        reasons += "Range and quality both met in $qualifying of the last ${window.size} sessions."
        reasons += "Form held to rep ${onset?.let { "%.0f".format(it) } ?: "?"} of $prescribed."
    } else {
        if (qualifying < READY_SESSIONS_REQUIRED) {
            reasons += "Range and quality both met in $qualifying of the last ${window.size} " +
                "sessions; $READY_SESSIONS_REQUIRED are needed."
        }
        if (!onsetOk) {
            reasons += if (onset == null) {
                "Not enough valid reps to judge where form starts slipping."
            } else {
                "Form starts slipping at rep ${"%.0f".format(onset)} of $prescribed; " +
                    "${"%.0f".format(onsetBar)} is the bar."
            }
        }
    }

    return ProgressionVerdict(
        readyToProgress = ready,
        needsReview = needsReview(sessions, reasons),
        reasons = reasons,
        sessionsConsidered = window.size,
    )
}

/** §6.21.5's thresholds. */
const val READY_ROM_PROGRESS = 0.90
const val READY_SESSIONS_REQUIRED = 2
const val DEGRADATION_FRACTION = 0.8

/** §6.21.6's thresholds. */
const val REVIEW_QUALITY_FLOOR = 50.0
const val REVIEW_COMPLETION_FLOOR = 0.5
const val REVIEW_CONSECUTIVE = 2

/**
 * §6.21.6.
 *
 * "Consecutive" is taken over the sessions as recorded, including ones excluded from the
 * progression median. A patient managing three reps twice running is precisely who this rule is
 * for, and filtering them out first would make the regression flag blindest exactly when it matters.
 */
private fun needsReview(sessions: List<SessionMetrics>, reasons: MutableList<String>): Boolean {
    if (sessions.any { it.painReported }) {
        reasons += "Pain reported."
        return true
    }
    val recent = sessions.takeLast(REVIEW_CONSECUTIVE)
    if (recent.size < REVIEW_CONSECUTIVE) return false

    // §1.6: an unscored session is not a failing session. It breaks the run rather than extending
    // it, because "we could not measure this" is not evidence of getting worse.
    val lowQuality = recent.all { (it.qMean ?: return@all false) < REVIEW_QUALITY_FLOOR }
    if (lowQuality) {
        reasons += "Quality below ${REVIEW_QUALITY_FLOOR.toInt()} for $REVIEW_CONSECUTIVE sessions."
        return true
    }
    val lowCompletion = recent.all {
        (it.completionRate ?: return@all false) < REVIEW_COMPLETION_FLOOR
    }
    if (lowCompletion) {
        reasons += "Fewer than half the prescribed reps completed, $REVIEW_CONSECUTIVE sessions running."
        return true
    }
    return false
}

/** Median that does not mutate its input and averages the middle pair on an even count. */
internal fun medianOf(values: List<Double>): Double {
    require(values.isNotEmpty()) { "median of nothing is not a number" }
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/**
 * §6.21.1's `ROM_peak`: the median of the top three reps.
 *
 * Median of the top three rather than the single best, so one rep that overshot - or one landmark
 * glitch that inflated an angle - cannot define the patient's range on its own.
 */
fun romPeakOf(repRoms: List<Double>): Double? {
    if (repRoms.isEmpty()) return null
    return medianOf(repRoms.sortedDescending().take(3))
}
