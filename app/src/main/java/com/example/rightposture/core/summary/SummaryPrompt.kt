package com.example.rightposture.core.summary

import kotlin.math.abs

/**
 * §10.7's prompt, and the template that stands in when the model cannot be trusted or cannot run.
 *
 * The prompt is built from [SummaryFacts] alone. Nothing about the patient, the physio, or the
 * device goes into it - partly because none of it would improve the wording, and partly because a
 * prompt is the easiest place for information to escape a device it was supposed to stay on.
 */

/** §10.7's fact sheet, rendered for the model. */
fun summaryPrompt(facts: SummaryFacts): String = buildString {
    appendLine("Set complete. Data:")
    appendLine("- Exercise: ${facts.exerciseName}")
    appendLine("- Reps completed: ${facts.repsCompleted} of ${facts.repsPrescribed}")
    // §1.6 survives all the way into the prompt. Writing "0" for an unscored set would invite the
    // model to console the patient about a bad performance that was never measured.
    appendLine(
        "- Median quality: " +
            (facts.medianQuality?.let { "$it / 100" } ?: "not scored"),
    )
    if (facts.degradationOnsetRep != null) {
        appendLine("- Form degradation detected starting at rep ${facts.degradationOnsetRep}")
    } else {
        appendLine("- No form degradation detected")
    }
    // §6.18.5. Spelled "percent", never "%": the model will write the word, and it needs to have
    // seen it here for the validator's magnitude check to admit it. The direction words are ours,
    // taken from the sign, so a clumsy paraphrase stays clumsy rather than becoming backwards.
    facts.romChangePercent?.let {
        appendLine("- Range after that point: ${abs(it)} percent ${if (it < 0) "smaller" else "larger"}")
    }
    facts.tempoChangePercent?.let {
        appendLine("- Tempo after that point: ${abs(it)} percent ${if (it > 0) "slower" else "faster"}")
    }
    when (facts.smoothness) {
        SmoothnessDirection.LESS_SMOOTH -> appendLine("- The movement was less smooth after that point")
        SmoothnessDirection.SMOOTHER -> appendLine("- The movement was smoother after that point")
        SmoothnessDirection.UNCHANGED, null -> Unit
    }
    if (facts.repsNotCounted > 0) {
        appendLine(
            "- Reps that did not count: ${facts.repsNotCounted}" +
                (facts.topRejection?.let { " (most often, $it)" } ?: ""),
        )
    }
    if (facts.changes.isNotEmpty()) {
        appendLine("- Largest changes:")
        facts.changes.forEach { appendLine("    ${it.displayName}: ${it.description}") }
    }
    if (facts.cues.isNotEmpty()) {
        appendLine("- Cues given:")
        facts.cues.forEach {
            appendLine("    \"${it.cue}\" x${it.times}, resolved ${it.resolved} of ${it.times}")
        }
    }
    appendLine()
    // Bounds stated as the numbers the validator actually enforces. They used to be left vague
    // ("3-4 short sentences"), and an over-long draft is the commonest refusal there is - which,
    // now that a refusal costs the reader the whole paragraph rather than a silent retry, is worth
    // spending two lines of prompt to avoid.
    appendLine("Write 3 or 4 short sentences for the patient. Never more than 4, never over 80 words.")
    appendLine("Name the specific change. End with one concrete thing to do next session.")
    appendLine(
        "Every number you write must be copied exactly from the data above. Do not add, combine, " +
            "round or convert them, and do not invent any measurement.",
    )
    appendLine("No medical advice, no diagnosis, no treatment suggestions.")
    append("Plain sentences only - no lists, no headings, no JSON.")
}

/**
 * The prompt for a retry, with the first attempt's failures spelled out.
 *
 * §10.4's approach applied to summaries: one retry that names what went wrong beats two blind
 * attempts, because a small model given "you invented the number 15" usually stops inventing it.
 */
fun summaryRetryPrompt(facts: SummaryFacts, reasons: List<String>): String = buildString {
    append(summaryPrompt(facts))
    appendLine()
    appendLine()
    appendLine("Your previous answer was rejected for these reasons:")
    reasons.forEach { appendLine("- $it") }
    append("Write it again, fixing all of them.")
}

/**
 * §10.5's fallback, for summaries.
 *
 * Mechanical and a little flat, which is the point: it is built only from arithmetic, so it cannot
 * fabricate anything, and it exists so the app is never silent when the model will not load, will
 * not finish, or will not stay inside the rules. It ships and it is correct; it simply is not the
 * sentence anybody would put on a slide.
 */
fun summaryTemplate(facts: SummaryFacts): String = buildString {
    append("You finished ${facts.repsCompleted} of ${facts.repsPrescribed} reps")
    facts.medianQuality?.let { append(", with a median quality of $it out of 100") }
    append(". ")

    if (facts.degradationOnsetRep != null) {
        append("Your form started to slip around rep ${facts.degradationOnsetRep}. ")
    } else {
        append("Your form held all the way through. ")
    }

    facts.changes.firstOrNull()?.let {
        append("The biggest change was ${it.displayName.lowercase()}: ${it.description}. ")
    }

    // Exactly one delta sentence, and only ΔROM. The template's ceiling is 5 sentences and 90
    // words; with every fact present it already runs to 4 and about 46, so this spends the last
    // sentence of headroom. ΔTempo, ΔSmooth and the uncounted reps stay prompt-only for that
    // reason - the model can fit them into 3-4 sentences, a fixed template cannot.
    facts.romChangePercent?.let {
        append("Your range was ${abs(it)} percent ${if (it < 0) "smaller" else "larger"} after that point. ")
    }

    // The one forward-looking sentence §10.7 asks for, chosen by arithmetic rather than phrasing.
    append(
        when {
            facts.repsCompleted < facts.repsPrescribed ->
                "Next session, aim to finish all ${facts.repsPrescribed} reps."
            facts.degradationOnsetRep != null ->
                "Next session, try slowing down before rep ${facts.degradationOnsetRep}."
            else -> "Next session, keep the same pace."
        },
    )
}
