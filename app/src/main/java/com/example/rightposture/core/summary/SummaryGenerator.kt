package com.example.rightposture.core.summary

/**
 * Whatever can turn a prompt into text. Deliberately this small.
 *
 * The interface exists so the rules in [checkSummary] can be tested against every way a model
 * misbehaves - inventing numbers, wandering into diagnosis, running to twelve sentences, timing
 * out, refusing to load - without a phone, a 1.5 GB model file, or an NPU. None of those failures
 * can be produced on demand from a real model, which is exactly why they have to be injectable.
 */
fun interface TextGenerator {
    /**
     * Returns the model's text, or null if it could not produce any.
     *
     * Null rather than an exception because "the model did not answer" is an ordinary outcome here,
     * exactly as an unreachable server is in §13.3. The caller's response is the same either way:
     * fall back to the template and carry on.
     */
    suspend fun generate(prompt: String): String?
}

/**
 * A generator that can hand back its answer as it is produced.
 *
 * Separate from [TextGenerator] rather than folded into it, so a generator that cannot stream - a
 * test double, or a backend without an incremental API - stays a one-line lambda. Callers ask for
 * the streaming behaviour and take the blocking one when it is not offered.
 */
interface StreamingTextGenerator : TextGenerator {
    /**
     * Runs [prompt], calling [onPartial] with the text produced so far, and returns the whole of it.
     *
     * [onPartial] receives the **cumulative** text rather than each new fragment, because every
     * caller wants the former and deriving it in one place stops each of them getting the
     * bookkeeping subtly wrong. Null is returned exactly as [TextGenerator.generate] returns it.
     */
    suspend fun generateStreaming(prompt: String, onPartial: (String) -> Unit): String?
}

/** How a summary was produced. Surfaced so a demo can show which path ran, and so can a bug report. */
enum class SummarySource {
    /** The model answered and passed validation first time. */
    MODEL,

    /** The model answered, was rejected, and passed on the retry §10.4 prescribes. */
    MODEL_RETRY,

    /** §10.5. The model could not run, or would not stay inside the rules. */
    TEMPLATE,
}

data class GeneratedSummary(
    val text: String,
    val source: SummarySource,
    /** Why the model's attempts were refused, if they were. Empty on a clean first pass. */
    val rejections: List<String> = emptyList(),
)

/**
 * §10.7's summary: generate, validate, retry once, else fall back.
 *
 * The shape is §10.4's and §10.5's, and the reason it is worth the extra call is that the failure
 * this guards against is not a crash. An unvalidated summary that says "your knee improved 15%" is
 * a sentence a patient believes, repeats to their physiotherapist, and acts on - and nothing about
 * it looks like a bug.
 */
suspend fun generateSummary(
    facts: SummaryFacts,
    generator: TextGenerator?,
    onPartial: (String) -> Unit = {},
    /**
     * Whether §10.4's second attempt runs at all.
     *
     * True is the rule as written, and is right when the reader sees only the finished paragraph:
     * a refused draft costs a wait and nothing else. It is wrong once the draft is streamed,
     * because then the reader watches one paragraph appear, vanish, and be replaced by another -
     * and possibly by the template after that. Three texts for one summary reads as the app
     * changing its mind, so the screens ask for a single attempt and take the template if it fails.
     */
    retryOnRejection: Boolean = true,
): GeneratedSummary {
    if (generator == null) {
        return GeneratedSummary(summaryTemplate(facts), SummarySource.TEMPLATE)
    }

    val first = generator.run(summaryPrompt(facts), facts, onPartial)
        ?: return GeneratedSummary(summaryTemplate(facts), SummarySource.TEMPLATE)

    when (val checked = checkSummary(first, facts)) {
        is SummaryCheck.Accepted -> return GeneratedSummary(checked.text, SummarySource.MODEL)
        is SummaryCheck.Rejected -> {
            if (!retryOnRejection) {
                return GeneratedSummary(
                    summaryTemplate(facts),
                    SummarySource.TEMPLATE,
                    checked.reasons,
                )
            }
            val second = generator.run(summaryRetryPrompt(facts, checked.reasons), facts, onPartial)
                ?: return GeneratedSummary(
                    summaryTemplate(facts),
                    SummarySource.TEMPLATE,
                    checked.reasons,
                )
            return when (val recheck = checkSummary(second, facts)) {
                is SummaryCheck.Accepted -> GeneratedSummary(
                    recheck.text,
                    SummarySource.MODEL_RETRY,
                    checked.reasons,
                )
                // Twice is enough. §10.5 is explicit that the fallback exists so the app is never
                // silent, and a third attempt is just a longer wait for the same template.
                is SummaryCheck.Rejected -> GeneratedSummary(
                    summaryTemplate(facts),
                    SummarySource.TEMPLATE,
                    checked.reasons + recheck.reasons,
                )
            }
        }
    }
}


/**
 * Runs one attempt, streaming it when the generator can and falling back to a single answer when
 * it cannot.
 *
 * The partial text handed on is always [safeVisiblePrefix] of what has arrived, never the raw
 * stream: the full check cannot run until the paragraph is finished, and a fabricated measurement
 * does no less damage for being on screen only briefly. A generator with no streaming support
 * reports its answer once, at the end, which is the behaviour every caller had before streaming
 * existed.
 */
internal suspend fun TextGenerator.run(
    prompt: String,
    facts: SummarisableFacts,
    onPartial: (String) -> Unit,
): String? = if (this is StreamingTextGenerator) {
    generateStreaming(prompt) { soFar -> onPartial(safeVisiblePrefix(soFar, facts)) }
} else {
    generate(prompt)
}
