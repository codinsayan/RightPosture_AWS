package com.example.rightposture.core.live

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.rpose.FaultDefinition

/**
 * §6.15. Several faults will be active at once. **Speak exactly one.**
 */

/** §6.15.1. `masker` being active suppresses `masked`, however much larger the masked deviation is. */
data class MaskRule(val masker: FeatureId, val masked: FeatureId)

/**
 * §6.15.1, verbatim and in the plan's order.
 *
 * These are **clinical logic and are hardcoded on purpose**: §6.15.1 is explicit that asking a small
 * language model to reason about compensation chains is where the app would actually cause harm. The
 * visibly wrong joint is frequently not the joint to cue.
 *
 * The plan warns that an earlier draft used shortened names matching no feature, which would have
 * made every rule silently inert - the lookup returns null, no mask applies, and no error appears.
 * Using [FeatureId] rather than strings makes that specific failure impossible to compile, which is
 * a stronger guarantee than the startup assertion it replaces.
 *
 * **Unvalidated clinical logic** until a physiotherapist reviews the table. 4.5's own plan says so.
 */
val MASK_RULES: List<MaskRule> = listOf(
    MaskRule(FeatureId.PELVIC_OBLIQUITY, FeatureId.KNEE_FRONTAL_ACTIVE),
    MaskRule(FeatureId.ANKLE_DORSIFLEX_ACTIVE, FeatureId.TRUNK_FLEXION),
    MaskRule(FeatureId.LUMBAR_FLEXION, FeatureId.SHOULDER_FLEX_ACTIVE),
    MaskRule(FeatureId.SHOULDER_ELEVATION, FeatureId.ELBOW_FLEX_ACTIVE),
    // Covers the classic cheat in standing hip abduction - leaning the trunk away to fake range -
    // which is one of the four demo exercises and was exposed by the original four rules.
    MaskRule(FeatureId.TRUNK_LATERAL, FeatureId.HIP_ABDUCTION_ACTIVE),
)

/** §6.15.4 escalation ladder. */
enum class CueLevel { SHORT, FULL, RESET, END_SET }

data class Cue(
    val faultId: String,
    val level: CueLevel,
    val text: String,
    /** Which occurrence of this fault in the current set produced the cue. */
    val occurrence: Int,
    /** True when `cue_alt` was substituted because the previous phrasing did not land (§6.15.5). */
    val usedAlternative: Boolean,
    val arrowJoint: String,
    val arrowAxis: String,
)

/** §6.15.5. One utterance and what happened after it. */
data class CueEvent(
    val faultId: String,
    val rep: Int,
    val firedAtNs: Long,
    val zAtFire: Double,
    val level: CueLevel,
    val zAfterTwoSeconds: Double? = null,
    val zNextRep: Double? = null,
    val resolved: Boolean? = null,
)

const val RESET_CUE = "Let's reset. Stand up and set your feet."
const val END_SET_CUE = "Let's stop this set here. I'll note it for your physio."

/**
 * §6.15: masking, ranking, cooldowns, escalation, and the effect log.
 *
 * §6.15.4 is the part worth not softening: **never nag.** A patient cued on every rep stops
 * listening by rep four, and a fault surviving three cues means the exercise is too hard for them
 * today - which is information for the physio, not a cue problem.
 */
class CueArbiter(private val maskRules: List<MaskRule> = MASK_RULES) {

    private var lastUtteranceNs: Long? = null
    private val lastPerFaultNs = mutableMapOf<String, Long>()
    private val occurrences = mutableMapOf<String, Int>()
    private val pendingAlternative = mutableSetOf<String>()
    private val finished = mutableSetOf<String>()
    private val events = mutableListOf<CueEvent>()

    val log: List<CueEvent> get() = events.toList()

    /** New set: the escalation ladder is per set, so it starts again. The log does not. */
    fun resetSet() {
        occurrences.clear()
        pendingAlternative.clear()
        finished.clear()
        lastUtteranceNs = null
        lastPerFaultNs.clear()
    }

    /**
     * Picks at most one cue for this moment.
     *
     * @param active the faults currently held down, from 4.4.
     * @return null when nothing should be said - which is most frames.
     */
    fun select(active: List<ActiveFault>, rep: Int, timestampNs: Long): Cue? {
        if (active.isEmpty()) return null

        val unmasked = active.filterNot { candidate -> isMasked(candidate, active) }
        if (unmasked.isEmpty()) return null

        // §6.15.2. Priority defaults to the watch-list weight and the physio may adjust it.
        val ranked = unmasked.sortedByDescending { it.currentAbsZ * it.definition.priority }

        // Per-fault cooldown skips that fault, not the whole frame: five seconds of silence about
        // the knee is not a reason to stay silent about the back.
        val chosen = ranked.firstOrNull {
            it.definition.id !in finished && offCooldown(it.definition.id, timestampNs)
        } ?: return null

        // Global cooldown is checked last, so a blocked frame does not consume an escalation step.
        val sinceAny = lastUtteranceNs?.let { seconds(timestampNs - it) }
        if (sinceAny != null && sinceAny < Params.GLOBAL_CUE_COOLDOWN_SECONDS) return null

        val id = chosen.definition.id
        val useAlternative = id in pendingAlternative
        val occurrence = if (useAlternative) {
            // §6.15.5: the previous phrasing did not land, so try the alternative wording at the same
            // rung before climbing. Escalating instead would punish the patient for our phrasing.
            pendingAlternative -= id
            occurrences.getValue(id)
        } else {
            (occurrences[id] ?: 0) + 1
        }
        occurrences[id] = occurrence

        val level = levelFor(occurrence)
        if (level == CueLevel.END_SET) finished += id

        lastUtteranceNs = timestampNs
        lastPerFaultNs[id] = timestampNs
        events += CueEvent(id, rep, timestampNs, chosen.currentAbsZ, level)

        return Cue(
            faultId = id,
            level = level,
            text = textFor(chosen.definition, level, useAlternative),
            occurrence = occurrence,
            usedAlternative = useAlternative,
            arrowJoint = chosen.definition.arrowJoint,
            arrowAxis = chosen.definition.arrowAxis,
        )
    }

    /**
     * §6.15.5. Report what happened two seconds after the most recent cue for this fault.
     *
     * When it did not improve, the next cue for that fault reuses the same rung with `cue_alt`.
     */
    fun recordFollowUp(faultId: String, zAfterTwoSeconds: Double, zNextRep: Double? = null) {
        val index = events.indexOfLast { it.faultId == faultId }
        if (index < 0) return
        val event = events[index]
        val improved = zAfterTwoSeconds < event.zAtFire
        events[index] = event.copy(
            zAfterTwoSeconds = zAfterTwoSeconds,
            zNextRep = zNextRep,
            resolved = zAfterTwoSeconds <= Params.Z_OFF,
        )
        if (!improved && (event.level == CueLevel.SHORT || event.level == CueLevel.FULL)) {
            pendingAlternative += faultId
        }
    }

    private fun offCooldown(id: String, timestampNs: Long): Boolean {
        val last = lastPerFaultNs[id] ?: return true
        return seconds(timestampNs - last) >= Params.PER_CUE_COOLDOWN_SECONDS
    }

    private fun isMasked(candidate: ActiveFault, active: List<ActiveFault>): Boolean {
        val others = active.filter { it.definition.id != candidate.definition.id }
        if (others.any { it.definition.id in candidate.definition.maskedBy }) return true

        val candidateFeatures = candidate.definition.conditions.map { it.feature }.toSet()
        val activeFeatures = others.flatMap { fault -> fault.definition.conditions.map { it.feature } }.toSet()
        return maskRules.any { it.masker in activeFeatures && it.masked in candidateFeatures }
    }

    private fun levelFor(occurrence: Int) = when (occurrence) {
        1 -> CueLevel.SHORT
        2 -> CueLevel.FULL
        3 -> CueLevel.RESET
        else -> CueLevel.END_SET
    }

    private fun textFor(fault: FaultDefinition, level: CueLevel, useAlternative: Boolean) = when {
        level == CueLevel.RESET -> RESET_CUE
        level == CueLevel.END_SET -> END_SET_CUE
        useAlternative -> fault.cueAlt
        level == CueLevel.SHORT -> fault.cueShort
        else -> fault.cueFull
    }

    private fun seconds(deltaNs: Long) = deltaNs / 1_000_000_000.0
}

/**
 * §6.15.1's "mandatory startup assertion", applied where it can still fail.
 *
 * The feature names in [MASK_RULES] are typed, so they cannot go stale. A fault's `masked_by` list
 * is free-form fault ids out of the `.rpose` bundle, and one naming a fault that does not exist
 * fails exactly the way §6.15.1 warns about: the mask never applies, contradictory cues appear, and
 * nothing indicates anything is wrong. Assert at startup, not at first use.
 */
fun assertCueSetup(faults: List<FaultDefinition>) {
    val ids = faults.map { it.id }.toSet()
    faults.forEach { fault ->
        fault.maskedBy.forEach { masker ->
            require(masker in ids) {
                "Unknown masking fault '" + masker + "' referenced by '" + fault.id + "'"
            }
        }
    }
}
