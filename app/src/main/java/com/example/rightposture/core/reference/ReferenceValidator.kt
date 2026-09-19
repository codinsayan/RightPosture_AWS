package com.example.rightposture.core.reference

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import kotlin.math.abs

/**
 * §6.11. **Do not accept a recording just because the pipeline ran.**
 *
 * Every failure carries a specific, actionable message addressed to the physio. A system that knows
 * when it cannot score is a system you can trust when it does.
 */

enum class CheckOutcome { PASS, FAIL, NOT_EVALUATED }

data class ValidatorCheck(
    /** 1-8, matching the §6.11 table. */
    val number: Int,
    val name: String,
    val outcome: CheckOutcome,
    /** What the physio is told, and what to do about it. Empty on a pass. */
    val message: String,
    /** The measured value, for the review screen. */
    val detail: String,
)

data class ValidationResult(val checks: List<ValidatorCheck>) {
    val accepted: Boolean get() = checks.all { it.outcome == CheckOutcome.PASS }

    /** The message to lead with. §6.11 wants one specific reason, not a wall of them. */
    val firstFailure: ValidatorCheck? get() = checks.firstOrNull { it.outcome != CheckOutcome.PASS }

    /** Checks that could not run. These are not passes and must be shown as such. */
    val notEvaluated: List<ValidatorCheck> get() = checks.filter { it.outcome == CheckOutcome.NOT_EVALUATED }
}

/**
 * Everything §6.11 needs that is not already inside [Reference] or [WatchList].
 *
 * [viewStability] is the §6.20.2 view-classifier agreement fraction. Null reports
 * `NOT_EVALUATED` and blocks acceptance; a missing check must never become a pass.
 */
data class RecordingSummary(
    val durationSeconds: Double,
    /** Rep durations in seconds, for check 3. */
    val repDurationsSeconds: List<Double>,
    val meanVisibility: Map<FeatureId, Float>,
    val viewStability: Double? = null,
)

fun validateReference(
    reference: Reference,
    watchList: WatchList,
    dominant: FeatureId,
    summary: RecordingSummary,
): ValidationResult {
    val checks = mutableListOf<ValidatorCheck>()

    // 1 - surviving reps
    checks += check(
        1,
        "Surviving reps",
        reference.repCount >= Params.MIN_SURVIVING_REPS,
        "I could only identify ${reference.repCount} clean rep" +
            "${if (reference.repCount == 1) "" else "s"}. Record 8 reps at a steady pace.",
        "${reference.repCount} reps",
    )

    // 2 - dominant feature ROM
    val dominantRom = reference.features[dominant]?.rom ?: 0.0
    checks += check(
        2,
        "Dominant feature range",
        dominantRom >= Params.VALIDATOR_MIN_DOMINANT_ROM_DEGREES,
        "This doesn't look like a repeated movement. Switch to Hold mode?",
        "${format(dominantRom)}°",
    )

    // 3 - rep duration consistency. MAD, not standard deviation, for the same reason as 6.7.6.
    val dispersion = durationDispersion(summary.repDurationsSeconds)
    checks += check(
        3,
        "Rep timing consistency",
        dispersion != null && dispersion <= Params.VALIDATOR_MAX_DURATION_DISPERSION,
        "Your reps varied a lot in speed. Try to keep a steady rhythm.",
        dispersion?.let { "MAD/median ${format(it)}" } ?: "not measurable",
    )

    // 4 - band tightness
    val tight = reference.features.count { it.value.medianSigmaRaw < Params.VALIDATOR_TIGHT_SIGMA_DEGREES }
    checks += check(
        4,
        "Band tightness",
        tight >= Params.VALIDATOR_MIN_TIGHT_FEATURES,
        "Your reps varied too much for me to learn a reliable target. " +
            "Record again with more consistent form.",
        "$tight feature${if (tight == 1) "" else "s"} under " +
            "${Params.VALIDATOR_TIGHT_SIGMA_DEGREES.toInt()}°",
    )

    // 5 - landmark visibility across the watch list
    val unseen = watchList.features
        .map { it.id }
        .filter { (summary.meanVisibility[it] ?: 0f) < Params.WATCH_LIST_MIN_VISIBILITY }
    checks += check(
        5,
        "Landmark visibility",
        unseen.isEmpty(),
        "I couldn't see ${describe(unseen)} clearly. Move the camera back and check the framing.",
        if (unseen.isEmpty()) "all watched joints visible" else "${unseen.size} below 0.7",
    )

    // 6 - camera view consistency.
    checks += if (summary.viewStability == null) {
        ValidatorCheck(
            6,
            "Camera view consistency",
            CheckOutcome.NOT_EVALUATED,
            "View stability was not measured. Re-record after camera calibration.",
            "no classifier",
        )
    } else {
        check(
            6,
            "Camera view consistency",
            summary.viewStability >= Params.VALIDATOR_MIN_VIEW_STABILITY,
            "You changed orientation mid-recording. Stay in one position.",
            "${format(summary.viewStability * 100)}% stable",
        )
    }

    // 7 - watch list size
    checks += check(
        7,
        "Watch list size",
        watchList.features.size >= Params.VALIDATOR_MIN_WATCH_LIST_SIZE,
        "I couldn't identify enough joints to score reliably.",
        "${watchList.features.size} joints",
    )

    // 8 - recording duration
    val duration = summary.durationSeconds
    val tooShort = duration < Params.VALIDATOR_MIN_DURATION_SECONDS
    checks += check(
        8,
        "Recording duration",
        !tooShort && duration <= Params.VALIDATOR_MAX_DURATION_SECONDS,
        if (tooShort) "Recording too short." else "Recording too long.",
        "${format(duration)} s",
    )

    return ValidationResult(checks)
}

/** `MAD(T) / median(T)`, or null when there are too few reps to say anything. */
internal fun durationDispersion(durations: List<Double>): Double? {
    if (durations.size < 2) return null
    val centre = median(durations)
    if (centre <= 0.0) return null
    return median(durations.map { abs(it - centre) }) / centre
}

private fun check(
    number: Int,
    name: String,
    passed: Boolean,
    failureMessage: String,
    detail: String,
) = ValidatorCheck(
    number,
    name,
    if (passed) CheckOutcome.PASS else CheckOutcome.FAIL,
    if (passed) "" else failureMessage,
    detail,
)

/** Names the joints the physio needs to reframe for, rather than saying "some landmarks". */
private fun describe(ids: List<FeatureId>) = when {
    ids.isEmpty() -> "your movement"
    ids.size == 1 -> readable(ids.first())
    else -> ids.take(2).joinToString(" and ") { readable(it) }
}

private fun readable(id: FeatureId) = id.value.replace('_', ' ')

private fun format(value: Double) = String.format("%.1f", value)
