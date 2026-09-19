package com.example.rightposture.report

import com.example.rightposture.core.progression.SessionMetrics
import com.example.rightposture.core.summary.CueTally
import com.example.rightposture.core.summary.FeatureChange
import com.example.rightposture.core.summary.SummaryFacts
import com.example.rightposture.core.progression.QUALITY_BAR
import com.example.rightposture.core.progression.medianOf
import com.example.rightposture.core.progression.romPeakOf
import com.example.rightposture.data.CueEventEntity
import com.example.rightposture.data.FeatureSampleEntity
import com.example.rightposture.data.RepEntity
import com.example.rightposture.data.SessionEntity
import kotlin.math.abs

/**
 * Turns what is on disk into the two fact types the model and the progression rules consume.
 *
 * This is the seam §10.1 draws, made concrete: everything downstream of here is either arithmetic
 * (§6.21) or phrasing over facts computed above it (§10.7). Nothing below this file gets to decide
 * what happened during a set.
 */

/** §10.7's fact sheet, built from the rows a finished set left behind. */
fun summaryFactsOf(
    exerciseName: String,
    session: SessionEntity,
    reps: List<RepEntity>,
    samples: List<FeatureSampleEntity> = emptyList(),
    cues: List<CueEventEntity> = emptyList(),
): SummaryFacts {
    val valid = reps.filter { it.valid }
    val scored = valid.mapNotNull { it.quality }
    val notCounted = reps.filter { !it.valid }
    val deltas = setDeltasOf(session.degradationOnsetRep, reps)

    return SummaryFacts(
        exerciseName = exerciseName,
        repsCompleted = valid.size,
        repsPrescribed = session.prescribedReps,
        // §1.6 survives the trip to the model: nothing scored means null, never zero.
        medianQuality = if (scored.isEmpty()) null else medianOf(scored).toInt(),
        degradationOnsetRep = session.degradationOnsetRep,
        changes = changesAfterOnset(session.degradationOnsetRep, samples),
        cues = cueTallies(cues),
        repsNotCounted = notCounted.size,
        // The commonest reason, already phrased. These strings reach the validator via the model,
        // so their wording is constrained - see `rejectionReason`.
        topRejection = notCounted.groupingBy { it.rejection }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?.let(::rejectionReason),
        romChangePercent = deltas.romPercent,
        tempoChangePercent = deltas.tempoPercent,
        smoothness = deltas.smoothness,
    )
}

/**
 * §6.18.5's attribution, reduced to at most two sentences a patient can act on.
 *
 * Attribution is on the **signed** mean, because a joint drifting into valgus and one drifting out
 * of it are opposite findings that share a magnitude - averaging their absolute values would report
 * a problem while hiding which way it went.
 *
 * The numbers that appear in the phrasing are whitelisted alongside it, so the validator can tell a
 * measurement we computed from one the model invented.
 */
private fun changesAfterOnset(
    onsetRep: Int?,
    samples: List<FeatureSampleEntity>,
): List<FeatureChange> = jointShiftsOf(onsetRep, samples)
    .take(MAX_REPORTED_CHANGES)
    .map { shift ->
        FeatureChange(
            displayName = shift.displayName,
            // The direction comes from the sign, which is arithmetic. §10.1's guarantee is
            // exactly this: a bad paraphrase can be clumsy, never backwards.
            // The magnitude reaches the sentence as a word, not a number. §6.18.5's severity is
            // worth saying, but a z-score in a patient-facing summary would need "sigma" - which
            // the validator refuses, and rightly: nobody reading this can act on a z-score.
            description = if (shift.shiftZ > 0) {
                val severity = if (abs(shift.shiftZ) >= CLEAR_SHIFT) "clearly " else ""
                "drifted ${severity}further from the target after rep $onsetRep"
            } else {
                "moved back toward the target after rep $onsetRep"
            },
            numbers = setOf(onsetRep.toString()),
        )
    }

/**
 * §6.18.5's attribution with the magnitude kept.
 *
 * [changesAfterOnset] needs only the sign, because a sentence written for a patient says "drifted
 * further" and not "drifted 1.4 standard deviations further". The chart needs the magnitude, and
 * computing it twice in two places is how the two views start disagreeing - so it is computed once,
 * here, and the prompt-facing function narrows it.
 *
 * Sorted by severity, most severe first, with the low-confidence rows demoted rather than dropped.
 */
data class JointShift(
    val feature: String,
    val displayName: String,
    /** Signed: the sign is the direction, the magnitude is the severity. Median z, post minus pre. */
    val shiftZ: Double,
    val preFrames: Int,
    val postFrames: Int,
) {
    /**
     * Whether this shift rests on enough frames to rank.
     *
     * `frames` is the trust column - "a mean over 3 frames is not a mean over 90", as the entity
     * says - and without this test a three-frame artefact outranks a ninety-frame finding purely by
     * being noisier.
     */
    val trustworthy: Boolean
        get() = preFrames >= MIN_TRUSTED_FRAMES && postFrames >= MIN_TRUSTED_FRAMES
}

/** §6.18.5's per-joint shift across the change point, most severe first. */
fun jointShiftsOf(
    onsetRep: Int?,
    samples: List<FeatureSampleEntity>,
): List<JointShift> {
    if (onsetRep == null || samples.isEmpty()) return emptyList()

    val before = samples.filter { it.repIndex < onsetRep }.groupBy { it.feature }
    val after = samples.filter { it.repIndex >= onsetRep }.groupBy { it.feature }

    return after.keys
        .mapNotNull { feature ->
            val pre = before[feature] ?: return@mapNotNull null
            val post = after[feature] ?: return@mapNotNull null
            if (pre.isEmpty() || post.isEmpty()) return@mapNotNull null
            val shift = medianOf(post.map { it.meanSignedZ }) - medianOf(pre.map { it.meanSignedZ })
            if (abs(shift) < MIN_REPORTABLE_SHIFT) return@mapNotNull null
            JointShift(
                feature = feature,
                displayName = displayNameOf(feature),
                shiftZ = shift,
                preFrames = pre.sumOf { it.frames },
                postFrames = post.sumOf { it.frames },
            )
        }
        // Trustworthy findings first, then by severity. A shift measured on too few frames still
        // gets shown - the physio may want to know the camera struggled - but it never leads.
        .sortedWith(compareByDescending<JointShift> { it.trustworthy }.thenByDescending { abs(it.shiftZ) })
}

/**
 * One joint's signed z across the reps of a set, for the per-joint chart.
 *
 * Feature samples exist only for reps that had at least one scored frame, so a rep with no row is a
 * gap rather than a zero - the same §6.6 rule the quality curve follows, for the same reason.
 */
fun jointSeriesOf(
    samples: List<FeatureSampleEntity>,
    feature: String,
    repIndexes: List<Int>,
): List<ChartPoint> {
    val byRep = samples.filter { it.feature == feature }.associateBy { it.repIndex }
    return repIndexes.sorted().map { index ->
        ChartPoint(x = index.toFloat(), y = byRep[index]?.meanSignedZ?.toFloat())
    }
}

/** Below this many frames on either side of the change point, a shift is shown but never ranked. */
const val MIN_TRUSTED_FRAMES = 5

/** Below this the shift is noise, and naming it would invent a finding. */
private const val MIN_REPORTABLE_SHIFT = 0.5

/** Above this a drift is worth calling out as pronounced rather than merely present. */
private const val CLEAR_SHIFT = 1.0

/** §10.7 asks for "largest changes", not all of them. Two is what fits in four sentences. */
private const val MAX_REPORTED_CHANGES = 2

/**
 * `knee_flex_active` becomes "Knee flex".
 *
 * The side and the active/passive distinction are dropped on purpose: they are modelling detail,
 * and a patient reading "knee flex active left" learns nothing they can act on.
 */
fun displayNameOf(featureId: String): String = featureId
    .removeSuffix("_active")
    .removeSuffix("_passive")
    .split("_")
    .filter { it.isNotEmpty() }
    .joinToString(" ")
    .replaceFirstChar { it.uppercase() }

/** §17's cue-effectiveness line, collapsed per cue. */
private fun cueTallies(cues: List<CueEventEntity>): List<CueTally> =
    cues.groupBy { it.cueVariant }
        .map { (variant, events) ->
            CueTally(
                cue = variant,
                times = events.size,
                resolved = events.count { it.resolved },
            )
        }
        .sortedByDescending { it.times }

/**
 * §6.21.1's metrics for one session.
 *
 * The two sentinels are applied here rather than left to the caller, because getting them wrong is
 * silent: a short session scored as "never degraded" outranks a full one, and nothing on screen
 * says why.
 */
fun sessionMetricsOf(
    session: SessionEntity,
    reps: List<RepEntity>,
    painReported: Boolean = false,
): SessionMetrics {
    val valid = reps.filter { it.valid }
    val scored = valid.mapNotNull { it.quality }

    return SessionMetrics(
        sessionId = session.id,
        romPeak = romPeakOf(valid.map { it.romDegrees }),
        qMean = if (scored.isEmpty()) null else medianOf(scored),
        repsAtQuality = scored.count { it >= QUALITY_BAR },
        holdSeconds = null,
        degradationOnset = degradationSentinel(session.degradationOnsetRep, valid.size),
        completionRate = session.completionRate
            ?: if (session.prescribedReps > 0) valid.size.toDouble() / session.prescribedReps else null,
        validReps = valid.size,
        prescribedReps = session.prescribedReps,
        painReported = painReported,
    )
}

/**
 * §6.21.1's two sentinels, in one place.
 *
 * `validReps + 1` when nothing degraded, so "never degraded" sorts above "degraded on the last
 * rep". Null when there were too few reps for CUSUM to say anything, which excludes the session
 * from the progression median rather than crediting it with a perfect run.
 */
internal fun degradationSentinel(onset: Int?, validReps: Int): Int? = when {
    validReps < com.example.rightposture.core.progression.MIN_VALID_REPS_FOR_CHANGE_POINT -> null
    onset == null -> validReps + 1
    else -> onset
}

/** §6.21's verdict for one exercise, carrying the name a physio will recognise. */
data class ExerciseProgression(
    val exerciseId: String,
    val exerciseName: String,
    val verdict: com.example.rightposture.core.progression.ProgressionVerdict,
)
