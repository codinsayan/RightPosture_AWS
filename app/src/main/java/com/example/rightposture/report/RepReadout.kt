package com.example.rightposture.report

import com.example.rightposture.core.live.RepRejection
import com.example.rightposture.data.RepEntity

/**
 * One rep, phrased for the person reading the report rather than for the code that wrote it.
 *
 * The report used to render `Rep 1: 94 · SKIPPED`, which looks like a bug and is not. Quality and
 * countability are two independent judgements - `ExerciseEngine.closeRep` computes the score from
 * the rep's frames and *then* asks the phase tracker whether the movement was the prescribed one -
 * so a rep can match the reference on every frame the scorer saw and still be refused for jumping
 * straight to the end position.
 *
 * The invariant, worth stating because any query over this data can get it wrong:
 *
 * - `rejection != null` if and only if `valid == false`
 * - `quality != null` is **orthogonal** to `valid`
 *
 * Never read `quality != null` as "this rep counted". Filter on [RepEntity.valid].
 */
data class RepReadout(
    val repIndex: Int,
    val headline: String,
    /** §1.6: null means nothing scored it, which is not a score of zero. */
    val quality: Int?,
    val counted: Boolean,
    /** The sentence under the headline, or null when the headline said everything. */
    val detail: String?,
)

/** Every rep of a set, in order, phrased. */
fun repReadoutsOf(reps: List<RepEntity>): List<RepReadout> =
    reps.sortedBy { it.repIndex }.map { rep ->
        val score = rep.quality?.toInt()
        val reason = rejectionReason(rep.rejection)

        RepReadout(
            repIndex = rep.repIndex,
            headline = buildString {
                append("Rep ${rep.repIndex}")
                append(
                    when {
                        score != null && rep.valid -> " · $score"
                        score != null -> " · scored $score · not counted"
                        rep.valid -> " · counted, not scored"
                        else -> " · not scored · not counted"
                    },
                )
            },
            quality = score,
            counted = rep.valid,
            detail = when {
                // The case that looked like a bug. Both halves get said, and the sentence explains
                // how they can both be true, because "94" and "not counted" beside each other with
                // no explanation is what sent someone looking for a defect that was not there.
                score != null && !rep.valid && reason != null ->
                    "It scored well frame by frame, but $reason - so it does not count toward your total."
                !rep.valid && reason != null -> "It did not count because $reason."
                // Reachable only if the engine's `valid = completed.valid && quality != null` ever
                // changes. Rendered honestly rather than crashing or implying a zero.
                rep.valid && score == null -> "It counted, but no frame in it could be scored."
                else -> null
            },
        )
    }

/**
 * A rejection code as a sentence a patient can read.
 *
 * Deliberately **not** reusing `EnglishPhrases.rejection`, which is imperative live coaching - "go
 * all the way down" - spoken during the set. A report is read afterwards and describes what
 * happened, so the tense and the person are both different.
 *
 * Resolved by name rather than `valueOf`, because the stored string is not guaranteed to be an enum
 * name: older rows and test fixtures carry free text, and `valueOf` would throw on real data. An
 * unrecognised value degrades to itself rather than taking the screen down.
 *
 * ## One constraint on the wording
 *
 * These phrases reach the summary prompt through `topRejection`, the model echoes them back, and the
 * validator then checks them. `SummaryValidator`'s `UNSAFE_ADVICE_WORDS` contains **"skip"**, so
 * `SKIPPED` must be described without that word or every summary mentioning it would be rejected -
 * twice, silently, falling back to the template with nothing on screen to say why. `strain`,
 * `damage`, `therapy`, `treatment`, `consult` and `heavier` are out for the same reason.
 */
fun rejectionReason(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return when (RepRejection.entries.firstOrNull { it.name == raw }) {
        RepRejection.RANGE -> "it did not reach the full range"
        RepRejection.TOO_FAST -> "it was faster than the movement allows"
        RepRejection.TOO_SLOW -> "it was slower than the movement allows"
        RepRejection.UNSEEN -> "the camera lost sight of you"
        RepRejection.UNSCORED -> "no frame in it could be scored"
        RepRejection.SKIPPED -> "it jumped to the end instead of following the whole movement"
        null -> raw.lowercase()
    }
}
