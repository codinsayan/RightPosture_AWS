package com.example.rightposture.core.summary

import kotlin.math.abs

/**
 * §10.7's fact sheet, for a *run* of sessions rather than one set.
 *
 * The same safety argument as [SummaryFacts] and for the same reason: the model phrases, our code
 * decides. Everything the paragraph is allowed to say is computed before the model is asked
 * anything, and [checkSummary] refuses any digit run that does not appear here - so a summary
 * cannot claim an improvement nobody measured.
 *
 * Two audiences share one fact sheet because they are looking at the same measurements. What
 * differs is who is being addressed and what they are expected to do about it, and that is
 * [audience]'s whole job: a patient is encouraged, a clinician is briefed, and neither is told the
 * other's sentence.
 */
data class ProgressFacts(
    /** What the run is *of*: one exercise's name, or a phrase naming a whole programme. */
    val subject: String,
    val audience: SummaryAudience,
    val sets: Int,
    /** Sets that carried enough evidence to be scored at all. §1.6: the rest are absent, not zero. */
    val scoredSets: Int,
    val medianQuality: Int?,
    val bestQuality: Int?,
    /** The most recent scored set, which is the one the reader just performed or just saw arrive. */
    val latestQuality: Int?,
    /** How many scored sets reached the pass mark. */
    val setsAtOrAboveBar: Int,
    val qualityBar: Int,
    val repsCompleted: Int,
    val repsPrescribed: Int,
    /** Consecutive days ending today. Zero is a real measurement here, not an absence. */
    val streakDays: Int,
    val trend: QualityTrend,
    /** Best peak range in degrees, when range was measured at all. */
    val bestRangeDegrees: Int? = null,
    /** Peak range now less the first measured peak, sign kept. Null when either end is missing. */
    val rangeGainDegrees: Int? = null,
) : SummarisableFacts {

    override fun allowedNumbers(): Set<String> = buildSet {
        add(sets.toString())
        add(scoredSets.toString())
        add(repsCompleted.toString())
        add(repsPrescribed.toString())
        add(streakDays.toString())
        add(setsAtOrAboveBar.toString())
        add(qualityBar.toString())
        medianQuality?.let {
            add(it.toString())
            // The scale, not a measurement - the same allowance §10.7 makes for one set.
            add(QUALITY_SCALE)
        }
        bestQuality?.let { add(it.toString()) }
        latestQuality?.let { add(it.toString()) }
        bestRangeDegrees?.let { add(it.toString()) }
        // `abs`, because the numeral regex never captures a leading minus: whitelisting "-4" would
        // admit nothing while still refusing the true sentence "4 degrees less".
        rangeGainDegrees?.let { add(abs(it).toString()) }
    }

    /**
     * Degrees are admitted only when a range was actually measured.
     *
     * Percent is never admitted. Nothing here is a percentage, and a model that reaches for one is
     * converting a count into a rate we did not compute - which is exactly the invention the
     * validator exists to catch.
     */
    override fun allowedMagnitudeWords(): Set<String> =
        if (bestRangeDegrees != null || rangeGainDegrees != null) {
            setOf("degrees", "degree")
        } else {
            emptySet()
        }

    /**
     * The words in the subject.
     *
     * An exercise called "Seated knee extension" puts "knee" into the prompt, so naming it back is
     * repetition rather than invention. Without this the body-part check would refuse every summary
     * of an exercise named after the joint it works, which is most of them.
     */
    override fun allowedFeatureWords(): Set<String> =
        subject.lowercase().split(" ", "-").filter { it.length > 2 }.toSet()

    override fun approvedPhrases(): String = subject
}

/** Who the paragraph is written for. It changes the person, not the facts. */
enum class SummaryAudience {
    /** The person who performed the sets. Addressed as "you". */
    PATIENT,

    /** The physiotherapist reading someone else's record. Addressed about "this patient". */
    CLINICIAN,
}

/**
 * Which way quality has moved across the run.
 *
 * A direction rather than a slope, for the reason [SmoothnessDirection] is: the slope is a decimal,
 * a decimal defeats the numeral whitelist, and "improving" is the whole of what a reader takes from
 * it anyway. [UNKNOWN] is its own value rather than being folded into [STEADY] - too few scored
 * sets to see a direction is not the same claim as a direction that is flat.
 */
enum class QualityTrend { IMPROVING, STEADY, DECLINING, UNKNOWN }

/**
 * §10.7's summary of a run of sessions: generate, validate, retry once, else fall back.
 *
 * Shares [checkSummary] and the retry shape with the per-set summary. Only the prompt and the
 * fallback wording differ, because only the subject does.
 */
suspend fun generateProgressSummary(
    facts: ProgressFacts,
    generator: TextGenerator?,
    onPartial: (String) -> Unit = {},
    /** See [generateSummary]: false means one attempt, then the template. */
    retryOnRejection: Boolean = true,
): GeneratedSummary {
    if (generator == null) {
        return GeneratedSummary(progressTemplate(facts), SummarySource.TEMPLATE)
    }

    val first = generator.run(progressPrompt(facts), facts, onPartial)
        ?: return GeneratedSummary(progressTemplate(facts), SummarySource.TEMPLATE)

    when (val checked = checkSummary(first, facts)) {
        is SummaryCheck.Accepted -> return GeneratedSummary(checked.text, SummarySource.MODEL)
        is SummaryCheck.Rejected -> {
            if (!retryOnRejection) {
                return GeneratedSummary(
                    progressTemplate(facts),
                    SummarySource.TEMPLATE,
                    checked.reasons,
                )
            }
            val second = generator.run(progressRetryPrompt(facts, checked.reasons), facts, onPartial)
                ?: return GeneratedSummary(
                    progressTemplate(facts),
                    SummarySource.TEMPLATE,
                    checked.reasons,
                )
            return when (val recheck = checkSummary(second, facts)) {
                is SummaryCheck.Accepted -> GeneratedSummary(
                    recheck.text,
                    SummarySource.MODEL_RETRY,
                    checked.reasons,
                )
                is SummaryCheck.Rejected -> GeneratedSummary(
                    progressTemplate(facts),
                    SummarySource.TEMPLATE,
                    checked.reasons + recheck.reasons,
                )
            }
        }
    }
}

/** The fact sheet as the model sees it, plus the rules it is held to. */
fun progressPrompt(facts: ProgressFacts): String = buildString {
    appendLine(
        when (facts.audience) {
            SummaryAudience.PATIENT ->
                "You are a physiotherapy assistant writing to a patient about their own recent " +
                    "sessions. Address them as \"you\"."
            SummaryAudience.CLINICIAN ->
                "You are a physiotherapy assistant briefing a physiotherapist about one patient's " +
                    "recent sessions. Refer to \"this patient\", never to \"you\"."
        },
    )
    appendLine()
    appendLine("FACTS (the only information you may use):")
    facts.lines().forEach { appendLine("- $it") }
    appendLine()
    appendLine("RULES:")
    appendLine("- Write 2 or 3 sentences of plain prose. Never more than 3, never over 60 words.")
    appendLine("- No lists, no headings, no markdown, no preamble. Start with the summary itself.")
    appendLine(
        "- Every number you write must be copied exactly from the facts above. Do not add, " +
            "combine, round or convert them, and never write a percentage.",
    )
    appendLine("- Do not diagnose, name a condition, or recommend any treatment.")
    appendLine(
        when (facts.audience) {
            SummaryAudience.PATIENT ->
                "- Do not tell them to change their programme. Their physiotherapist decides that."
            SummaryAudience.CLINICIAN ->
                "- Do not recommend advancing or holding the programme. State what the numbers " +
                    "show and leave the decision to the reader."
        },
    )
    appendLine("- Say plainly when something has not been measured. Never write it as a zero.")
    appendLine()
    append("Write the summary now.")
}

/** §10.4's second attempt, with the refusals named so the model can correct rather than reroll. */
fun progressRetryPrompt(facts: ProgressFacts, reasons: List<String>): String = buildString {
    appendLine("Your previous summary was rejected for these reasons:")
    reasons.forEach { appendLine("- $it") }
    appendLine()
    appendLine("Write it again, fixing every reason above.")
    appendLine()
    append(progressPrompt(facts))
}

/**
 * §10.5's fallback, assembled from the same facts.
 *
 * Written to pass [checkSummary] by construction, because the fallback that fails validation leaves
 * the screen with nothing at all - and a run of sessions with no paragraph looks like a bug rather
 * than like a model that did not load.
 */
fun progressTemplate(facts: ProgressFacts): String = buildString {
    val patient = facts.audience == SummaryAudience.PATIENT
    val subject = if (patient) "You have" else "This patient has"

    append("$subject finished ${facts.sets} sets of ${facts.subject}")
    if (facts.scoredSets < facts.sets) {
        append(", ${facts.scoredSets} of them with enough repetitions to score")
    }
    append(". ")

    if (facts.medianQuality != null) {
        append("The middle score across those is ${facts.medianQuality} out of $QUALITY_SCALE")
        append(", and ${facts.setsAtOrAboveBar} reached the ${facts.qualityBar} pass mark. ")
    } else {
        append("No set so far carried enough repetitions to be scored, so there is no score yet. ")
    }

    append(
        when (facts.trend) {
            QualityTrend.IMPROVING -> "Scores have been rising across the run."
            QualityTrend.DECLINING -> "Scores have been drifting down across the run."
            QualityTrend.STEADY -> "Scores have held steady across the run."
            QualityTrend.UNKNOWN -> "There are not yet enough scored sets to show a direction."
        },
    )
}

/** The fact sheet as bullet lines, shared by the prompt and its retry. */
private fun ProgressFacts.lines(): List<String> = buildList {
    add("Subject: $subject")
    add("Sets finished: $sets")
    add("Sets with enough repetitions to score: $scoredSets")
    if (medianQuality != null) {
        add("Middle score: $medianQuality out of $QUALITY_SCALE")
    } else {
        add("Middle score: not measured - no set carried enough repetitions to score")
    }
    bestQuality?.let { add("Best score: $it out of $QUALITY_SCALE") }
    latestQuality?.let { add("Most recent score: $it out of $QUALITY_SCALE") }
    add("Pass mark: $qualityBar out of $QUALITY_SCALE")
    add("Sets at or above the pass mark: $setsAtOrAboveBar")
    add("Repetitions completed: $repsCompleted of $repsPrescribed prescribed")
    add("Consecutive days ending today: $streakDays")
    bestRangeDegrees?.let { add("Best range reached: $it degrees") }
    rangeGainDegrees?.let {
        add(
            if (it >= 0) {
                "Range has grown by $it degrees since the first measured set"
            } else {
                "Range is ${abs(it)} degrees smaller than the first measured set"
            },
        )
    }
    add(
        "Direction of scores: " + when (trend) {
            QualityTrend.IMPROVING -> "rising"
            QualityTrend.DECLINING -> "falling"
            QualityTrend.STEADY -> "steady"
            QualityTrend.UNKNOWN -> "not enough scored sets to say"
        },
    )
}
