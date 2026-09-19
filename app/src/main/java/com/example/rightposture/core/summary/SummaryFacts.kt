package com.example.rightposture.core.summary

import kotlin.math.abs

/**
 * §10.7. Everything the summary is allowed to say, computed before the model is asked anything.
 *
 * This type is the whole safety argument for 8.3, and it is the same argument §10.1 makes for cues:
 * **the model phrases, our code decides.** A hallucination can produce a clumsy sentence; it cannot
 * produce a measurement that was never taken, because the validator only admits numbers that appear
 * here.
 */
data class SummaryFacts(
    val exerciseName: String,
    val repsCompleted: Int,
    val repsPrescribed: Int,
    /** §1.6: null means no rep was scored. It is never rendered as zero. */
    val medianQuality: Int?,
    /** §6.18.3's change point, or null when form never slipped or CUSUM could not run. */
    val degradationOnsetRep: Int?,
    val changes: List<FeatureChange> = emptyList(),
    val cues: List<CueTally> = emptyList(),
    /** Reps that finished but did not count. Zero is a real answer here, not an absence. */
    val repsNotCounted: Int = 0,
    /** Why, in words, for the commonest reason. Carries no numeral by construction. */
    val topRejection: String? = null,
    /**
     * §6.18.5's ΔROM as whole percent, sign kept. Negative means the range shrank.
     *
     * Null whenever [degradationOnsetRep] is null: a delta measured relative to no change point is
     * a measurement nobody took, and keeping the two in lockstep is what lets the wording say
     * "after that point" without ever being orphaned.
     */
    val romChangePercent: Int? = null,
    /** §6.18.5's ΔTempo as whole percent, sign kept. Positive means slower. */
    val tempoChangePercent: Int? = null,
    /** §6.18.5's ΔSmooth, direction only - a SPARC delta is a decimal and no numeral may be. */
    val smoothness: SmoothnessDirection? = null,
) : SummarisableFacts {
    /**
     * Every number the summary may contain, as the exact strings a model would write.
     *
     * The validator rejects any other digit run. That is a blunt rule and it is deliberately blunt:
     * "you managed 8 of 10 reps" is checkable, whereas "your knee improved by roughly fifteen
     * percent" is a fabricated measurement dressed as encouragement, and no softer rule reliably
     * catches it.
     */
    override fun allowedNumbers(): Set<String> = buildSet {
        add(repsCompleted.toString())
        add(repsPrescribed.toString())
        medianQuality?.let {
            add(it.toString())
            // The scale, not a measurement. "71 out of 100" is the natural way to say a score and
            // the 100 asserts nothing about the patient; without it the template fails the very
            // check it exists to satisfy, which the fallback test caught.
            add(QUALITY_SCALE)
        }
        degradationOnsetRep?.let { add(it.toString()) }
        changes.forEach { addAll(it.numbers) }
        cues.forEach { cue ->
            add(cue.times.toString())
            add(cue.resolved.toString())
        }
        // `abs`, because NUMBER_RUN never captures a leading minus: whitelisting "-12" would admit
        // nothing while still rejecting the true sentence "12 percent smaller".
        romChangePercent?.let { add(abs(it).toString()) }
        tempoChangePercent?.let { add(abs(it).toString()) }
        // Guarded, so a set where every rep counted does not whitelist a zero that never appears.
        if (repsNotCounted > 0) add(repsNotCounted.toString())
    }

    /**
     * Units we actually measured in, so the validator's magnitude check admits them.
     *
     * The check that makes this necessary refuses any word in `SPELLED_MAGNITUDES` unless it already
     * appears in a [FeatureChange.description] - which is right, since a spelled-out unit dodges the
     * digit check entirely. But it means whitelisting the *number* is not enough: with only
     * [allowedNumbers] widened, a model correctly writing "12 percent smaller" is still rejected,
     * every delta-bearing summary falls back to the template, and the sole visible symptom is the
     * attribution line reading "Summary from measurements only".
     *
     * Only `percent` becomes reachable, and only when a percentage was computed. `degrees`, `sigma`
     * and `standard deviation` stay refused: ΔROM is a ratio, and the model is never handed a raw
     * angle or a z-score.
     */
    override fun allowedMagnitudeWords(): Set<String> =
        if (romChangePercent != null || tempoChangePercent != null) setOf("percent") else emptySet()

    /** The joints the model is allowed to name, because they are the ones actually measured. */
    override fun allowedFeatureWords(): Set<String> =
        changes.flatMap { it.displayName.lowercase().split(" ") }.filter { it.length > 2 }.toSet()

    /** The per-joint descriptions this set actually produced. */
    override fun approvedPhrases(): String = changes.joinToString(" ") { it.description }
}

/** The denominator quality is reported against. A constant, not something measured. */
const val QUALITY_SCALE = "100"

/**
 * §6.18.5's ΔSmooth, reduced to a direction.
 *
 * SPARC is negative and more negative means less smooth. The magnitude is deliberately dropped: it
 * is a decimal, and a decimal both defeats the numeral whitelist - which matches "1.8" whole - and
 * reads as a sentence boundary to the validator's sentence count.
 */
enum class SmoothnessDirection { SMOOTHER, LESS_SMOOTH, UNCHANGED }

/**
 * One measured change, already phrased by us.
 *
 * [description] is our sentence, not the model's. The model may reuse or reword it, but the fact it
 * describes was computed in §6.18.5 and the direction came from the sign of a z-score - which is
 * arithmetic, so a bad paraphrase can be clumsy without ever being backwards.
 */
data class FeatureChange(
    val displayName: String,
    val description: String,
    /** Numeric tokens appearing in [description], whitelisted for the validator. */
    val numbers: Set<String> = emptySet(),
)

/** §17's cue-effectiveness line, reduced to what a patient can act on. */
data class CueTally(
    val cue: String,
    val times: Int,
    val resolved: Int,
)
