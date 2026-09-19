package com.example.rightposture.core.summary

/**
 * 8.3's gate, as code: **no new medical advice and no fabricated measurement may enter a summary.**
 *
 * The model is asked for three or four sentences of encouragement over facts we computed. What it
 * must never do is invent a measurement, name a joint nobody watched, or cross from coaching into
 * clinical advice. None of those are stylistic preferences - a patient acts on this text, and the
 * app is not qualified to tell them anything a physiotherapist did not.
 *
 * Every rejection falls back to [summaryTemplate], so a refusal here is never silence.
 */

/**
 * What [checkSummary] needs to know about a fact sheet, whatever the fact sheet describes.
 *
 * Extracted when §4.4 and §5.3 grew summaries of a *run of sessions* rather than of one set. The
 * safety argument does not change with the subject - the model still phrases and our code still
 * decides - so the validator had to stop being tied to one fact type rather than being copied and
 * left to drift. A second copy of these rules is the failure mode worth avoiding here: the copy
 * that misses a rule is the one that ships a fabricated measurement.
 */
interface SummarisableFacts {
    /** Every digit run the text may contain, as the exact strings a model would write. */
    fun allowedNumbers(): Set<String>

    /** Units we actually measured in, so a spelled-out magnitude is not automatically refused. */
    fun allowedMagnitudeWords(): Set<String>

    /** The joints the text may name, because they are the ones actually measured. */
    fun allowedFeatureWords(): Set<String>

    /** Prose we computed ourselves, which the checks read as already-approved wording. */
    fun approvedPhrases(): String
}

sealed interface SummaryCheck {
    data class Accepted(val text: String) : SummaryCheck
    data class Rejected(val reasons: List<String>) : SummaryCheck
}

/**
 * Words that turn coaching into clinical advice.
 *
 * Split by why they are refused, because they are refused for different reasons and the list is
 * easier to defend - and to extend - when that is visible.
 */
private val DIAGNOSIS_WORDS = setOf(
    "diagnos", "injur", "tear", "sprain", "strain", "fracture", "arthritis", "tendinitis",
    "tendonitis", "inflammation", "inflamed", "impingement", "syndrome", "condition", "disease",
    "damage", "lesion", "rupture",
)

private val TREATMENT_WORDS = setOf(
    "medication", "medicine", "painkiller", "ibuprofen", "paracetamol", "prescribe",
    "prescription", "dose", "surgery", "surgical", "operation", "injection", "physiotherapy",
    "treatment", "therapy", "rehab", "massage", "ice pack", "heat pack",
)

/**
 * Language that tells a patient to push through pain, or to change their programme unilaterally.
 *
 * §6.21.5 reserves advancement for a clinician, and this is the wording side of the same rule: the
 * app may encourage, but it may not promote.
 */
private val UNSAFE_ADVICE_WORDS = setOf(
    "push through", "ignore the pain", "no pain no gain", "work through the pain",
    "increase the weight", "add weight", "heavier", "more sets", "extra sets",
    "skip", "stop doing", "give up", "you should see", "see a doctor", "consult",
)

/** Everything refused outright, whatever the fact sheet says. Used by the live stream guard. */
private val REFUSED_WORDS: List<String> by lazy {
    (DIAGNOSIS_WORDS + TREATMENT_WORDS + UNSAFE_ADVICE_WORDS).toList()
}

/** Anything shaped like a measurement: digits, and the units a model reaches for. */
internal val NUMBER_RUN = Regex("""\d+(?:\.\d+)?""")

private val SPELLED_MAGNITUDES = setOf(
    "percent", "degrees", "degree", "centimet", "millimet", "kilogram", "sigma", "standard deviation",
)

/**
 * Checks one generated summary against [facts].
 *
 * @param maxSentences §10.7 asks for 3-4 sentences. A model that runs to twelve has stopped
 *   summarising and started narrating, and the extra sentences are where invention lives.
 */
fun checkSummary(
    generated: String,
    facts: SummarisableFacts,
    maxSentences: Int = 5,
    maxWords: Int = 90,
): SummaryCheck {
    val text = generated.trim()
    val reasons = mutableListOf<String>()

    if (text.isEmpty()) {
        return SummaryCheck.Rejected(listOf("The model returned nothing."))
    }

    val lower = text.lowercase()

    // 1. Fabricated measurement. Every digit run must be a number we handed it.
    val allowed = facts.allowedNumbers()
    val invented = NUMBER_RUN.findAll(text).map { it.value }.filter { it !in allowed }.toList()
    if (invented.isNotEmpty()) {
        reasons += "Invented numbers: ${invented.distinct().joinToString(", ")}."
    }

    // 2. A magnitude spelled out in words dodges the digit check, so the units are refused too
    //    unless a change we computed already used them.
    //    A unit we *did* measure in has to be admitted explicitly, or whitelisting the number is
    //    not enough on its own: "12 percent smaller" would be refused here even with 12 allowed,
    //    and every delta-bearing summary would fall silently back to the template.
    val factsText = facts.approvedPhrases().lowercase()
    val measuredUnits = facts.allowedMagnitudeWords()
    SPELLED_MAGNITUDES.forEach { unit ->
        if (unit in lower && unit !in factsText && unit !in measuredUnits) {
            reasons += "States a measurement we did not compute: \"$unit\"."
        }
    }

    // 3. Clinical claims.
    DIAGNOSIS_WORDS.forEach { if (it in lower) reasons += "Reads as a diagnosis: \"$it\"." }
    TREATMENT_WORDS.forEach { if (it in lower) reasons += "Recommends treatment: \"$it\"." }
    UNSAFE_ADVICE_WORDS.forEach { if (it in lower) reasons += "Unsafe or out-of-scope advice: \"$it\"." }

    // 4. Naming a joint nobody watched. A summary that praises "your shoulder" after a knee
    //    exercise is describing a measurement that does not exist, even with no number attached.
    val watched = facts.allowedFeatureWords()
    val bodyParts = setOf(
        "knee", "hip", "ankle", "shoulder", "elbow", "wrist", "neck", "spine", "trunk",
        "back", "pelvis", "thigh", "calf", "foot", "arm", "leg",
    )
    bodyParts.forEach { part ->
        if (part in lower && watched.none { part in it } && part !in factsText) {
            reasons += "Names a joint this set did not measure: \"$part\"."
        }
    }

    // 5. Shape.
    val sentences = text.split(Regex("""[.!?]+""")).count { it.isNotBlank() }
    if (sentences > maxSentences) {
        reasons += "$sentences sentences; at most $maxSentences were asked for."
    }
    val words = text.split(Regex("""\s+""")).count { it.isNotBlank() }
    if (words > maxWords) reasons += "$words words is past the $maxWords-word bound."

    // 6. A model that leaks its instructions or its scaffolding.
    if ("```" in text || "{" in text || "as an ai" in lower || "i cannot" in lower) {
        reasons += "The reply contains scaffolding rather than a summary."
    }

    return if (reasons.isEmpty()) SummaryCheck.Accepted(text) else SummaryCheck.Rejected(reasons)
}


/**
 * How much of a partially-generated summary may be shown while the model is still writing.
 *
 * Streaming a summary as it arrives is worth doing - a paragraph that appears word by word is
 * visibly the phone thinking rather than a spinner - but it reopens the hole [checkSummary] exists
 * to close: the full check can only run on a finished paragraph, and by then a fabricated
 * measurement has already been read. "Your range improved by 15 percent" is a sentence a patient
 * believes and repeats, and it does no less damage for being on screen only briefly.
 *
 * So the stream is gated rather than trusted. Text is revealed up to, and never past:
 *
 *  - the first **completed** number that is not in the fact sheet. A digit run still being written
 *    is held back entirely, because "9" is not yet evidence that "93" is coming;
 *  - the first word that is refused outright - a diagnosis, a treatment, unsafe advice - none of
 *    which depend on the fact sheet and all of which can be spotted the moment they appear.
 *
 * Everything else - sentence count, word count, spelled-out units, joints nobody watched - is a
 * property of the finished text and is still checked by [checkSummary] when the stream ends. This
 * is deliberately the cheap half of the rules, chosen because these are the two that put a false
 * measurement or a clinical claim in front of a reader.
 */
fun safeVisiblePrefix(text: String, facts: SummarisableFacts): String {
    if (text.isEmpty()) return text
    val allowed = facts.allowedNumbers()
    var cut = text.length

    NUMBER_RUN.findAll(text).forEach { match ->
        val endsInsideText = match.range.last + 1 < text.length
        // A run touching the end of what has arrived so far may still be growing, so it is held
        // back rather than judged. Judging it early would flash "9" before "93" arrived, and then
        // have to take it away again.
        if (!endsInsideText || match.value !in allowed) {
            cut = minOf(cut, match.range.first)
        }
    }

    val lower = text.lowercase()
    REFUSED_WORDS.forEach { word ->
        val at = lower.indexOf(word)
        if (at >= 0) cut = minOf(cut, at)
    }

    return text.take(cut)
}
