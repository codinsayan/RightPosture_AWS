package com.example.rightposture.report

import com.example.rightposture.core.progression.medianOf
import com.example.rightposture.core.summary.SmoothnessDirection
import com.example.rightposture.data.RepEntity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * §6.18.5's attribution: what changed after form started slipping.
 *
 * §6.18.5 names the pattern - "rising roughness with falling ROM and slowing tempo is the textbook
 * fatigue pattern; naming it explicitly is what a physiotherapist will recognise instantly" - and
 * every column it needs has been on disk since 7.1. Nothing read them until now.
 */

/**
 * The three §6.18.5 quantities for one set, or nulls where they could not be computed.
 *
 * All whole numbers. A decimal would be a problem twice over: `checkSummary` counts sentences by
 * splitting on `[.!?]+`, so "1.8" reads as a sentence boundary and pushes a summary toward the
 * five-sentence rejection, and the validator matches numerals whole, so whitelisting "1" and "8"
 * would not admit "1.8" either.
 */
data class SetDeltas(
    /** Percent change in range after the change point. Negative means smaller. */
    val romPercent: Int?,
    /** Percent change in rep duration after the change point. Positive means slower. */
    val tempoPercent: Int?,
    val smoothness: SmoothnessDirection?,
)

/**
 * §6.18.5 across [onsetRep].
 *
 * Returns all-nulls when there is no change point. A delta measured relative to nothing is not a
 * finding, and this is what lets the summary template say "after that point" without ever printing
 * it in a set where no such point exists.
 *
 * ## Valid reps only, and this is not tidiness
 *
 * A rep rejected for `RANGE` has a small `romDegrees` *by definition* - that is the rejection
 * criterion, not an observation about the patient. Including rejected reps in the post-onset window
 * would manufacture a range collapse out of the criterion itself and report it as fatigue. The same
 * argument applies to `TOO_FAST` and `TOO_SLOW` against tempo.
 */
fun setDeltasOf(onsetRep: Int?, reps: List<RepEntity>): SetDeltas {
    if (onsetRep == null) return SetDeltas(null, null, null)

    val valid = reps.filter { it.valid }
    val pre = valid.filter { it.repIndex < onsetRep }
    val post = valid.filter { it.repIndex >= onsetRep }
    if (pre.isEmpty() || post.isEmpty()) return SetDeltas(null, null, null)

    return SetDeltas(
        romPercent = percentChange(pre.map { it.romDegrees }, post.map { it.romDegrees }),
        tempoPercent = percentChange(
            pre.map { it.durationMs.toDouble() },
            post.map { it.durationMs.toDouble() },
        ),
        smoothness = smoothnessShift(
            pre.mapNotNull { it.smoothnessSparc },
            post.mapNotNull { it.smoothnessSparc },
        ),
    )
}

/**
 * `(meanPost / meanPre - 1) * 100`, rounded, or null when it would not mean anything.
 *
 * Suppressed below [MIN_REPORTABLE_PERCENT] so ordinary rep-to-rep variation does not reach the
 * prompt dressed as a finding.
 */
private fun percentChange(pre: List<Double>, post: List<Double>): Int? {
    if (pre.isEmpty() || post.isEmpty()) return null
    val before = pre.average()
    if (abs(before) < 1e-6) return null
    val change = ((post.average() / before) - 1.0) * 100.0
    val rounded = change.roundToInt()
    return if (abs(rounded) < MIN_REPORTABLE_PERCENT) null else rounded
}

/**
 * Direction of the SPARC change, with no magnitude attached.
 *
 * SPARC is negative and **more negative means less smooth**, which is the sign convention most
 * likely to be inverted by someone reading the field name alone - so it has its own test rather
 * than a comment alone.
 *
 * No number reaches the prompt because a SPARC delta is naturally a decimal, and every numeral this
 * pipeline emits has to be an integer. Direction is also the whole of what a patient can act on.
 */
private fun smoothnessShift(pre: List<Double>, post: List<Double>): SmoothnessDirection? {
    if (pre.isEmpty() || post.isEmpty()) return null
    val shift = medianOf(post) - medianOf(pre)
    return when {
        shift > SMOOTHNESS_EPSILON -> SmoothnessDirection.SMOOTHER
        shift < -SMOOTHNESS_EPSILON -> SmoothnessDirection.LESS_SMOOTH
        else -> SmoothnessDirection.UNCHANGED
    }
}

/** Below this, a percent change is rep-to-rep noise rather than fatigue. */
const val MIN_REPORTABLE_PERCENT = 5

/** Below this much SPARC movement, smoothness is unchanged. */
private const val SMOOTHNESS_EPSILON = 0.2
