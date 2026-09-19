package com.example.rightposture.core.live

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.reference.WatchList
import com.example.rightposture.core.rpose.FaultCondition
import com.example.rightposture.core.rpose.FaultDefinition
import kotlin.math.abs

/**
 * §6.14. A fault is a **named pattern**, not a bare z-score.
 *
 * The definitions are the same `FaultDefinition` records that live in the `.rpose` bundle (3.9), so
 * what fires here is exactly what the physio reviewed and the file stores. A second live-only fault
 * type would be a second place for the two to disagree.
 */

/** A fault currently held down. */
data class ActiveFault(
    val definition: FaultDefinition,
    val onsetNs: Long,
    /** Largest `|z|` seen on the triggering feature since onset. */
    val peakAbsZ: Double,
    /** `|z|` on this frame. 4.5 ranks on current severity, not on how bad it once was. */
    val currentAbsZ: Double,
)

data class FaultUpdate(
    val active: List<ActiveFault>,
    /** Faults that became active on this frame. Emitted once per activation, never repeated. */
    val onsets: List<FaultDefinition>,
    /** Faults that stopped being active on this frame. */
    val clears: List<FaultDefinition>,
)

/**
 * §6.14 detection with §6.14.1 hysteresis and dwell.
 *
 * §6.14.1 is blunt about why the dwell exists: it "is the difference between a demo that feels
 * magical and one that feels broken". Without it the app fires on every frame of jitter.
 */
class FaultDetector(private val faults: List<FaultDefinition>) {

    private class State {
        var active = false
        var onsetNs = 0L
        var peakAbsZ = 0.0
        var enterSinceNs: Long? = null
        var exitSinceNs: Long? = null
    }

    private val states = faults.associate { it.id to State() }

    fun reset() = states.values.forEach {
        it.active = false
        it.peakAbsZ = 0.0
        it.enterSinceNs = null
        it.exitSinceNs = null
    }

    fun update(
        zScores: Map<FeatureId, FeatureZ>,
        phase: Double,
        timestampNs: Long,
    ): FaultUpdate {
        val onsets = mutableListOf<FaultDefinition>()
        val clears = mutableListOf<FaultDefinition>()
        val active = mutableListOf<ActiveFault>()

        faults.forEach { fault ->
            val state = states.getValue(fault.id)

            // §6.14.3, and it is absolute: no fault may fire on an invalid feature. Ever. If the
            // contributing landmarks are occluded we do not know, and we say nothing - which
            // applies to a claim already standing just as much as to a new one.
            val observable = fault.conditions.all { zScores.containsKey(it.feature) }
            if (!observable) {
                if (state.active) {
                    state.active = false
                    clears += fault
                }
                state.enterSinceNs = null
                state.exitSinceNs = null
                return@forEach
            }

            val maxAbsZ = fault.conditions.maxOf { abs(zScores.getValue(it.feature).z) }

            if (state.active) {
                // Exit on the *low* threshold, held. The asymmetry between Z_ON and Z_OFF is what
                // stops a fault flickering while the patient hovers at the boundary.
                if (maxAbsZ <= Params.Z_OFF) {
                    val since = state.exitSinceNs ?: timestampNs.also { state.exitSinceNs = it }
                    if (seconds(timestampNs - since) >= Params.FAULT_EXIT_DWELL_SECONDS) {
                        state.active = false
                        state.exitSinceNs = null
                        clears += fault
                        return@forEach
                    }
                } else {
                    state.exitSinceNs = null
                }
                state.peakAbsZ = maxOf(state.peakAbsZ, maxAbsZ)
                active += ActiveFault(fault, state.onsetNs, state.peakAbsZ, maxAbsZ)
                return@forEach
            }

            // §6.14.2. The same 40 degrees of trunk lean is correct mid-squat and a fault at the
            // top: same joint, same value, opposite verdicts. So the window gates entry.
            val inPhase = phase >= fault.phaseRange.first && phase <= fault.phaseRange.second
            val triggered = inPhase && fault.conditions.all { holds(it, zScores.getValue(it.feature).z) }
            if (!triggered) {
                state.enterSinceNs = null
                return@forEach
            }

            val since = state.enterSinceNs ?: timestampNs.also { state.enterSinceNs = it }
            if (seconds(timestampNs - since) >= Params.FAULT_ENTRY_DWELL_SECONDS) {
                state.active = true
                state.onsetNs = timestampNs
                state.peakAbsZ = maxAbsZ
                state.enterSinceNs = null
                state.exitSinceNs = null
                onsets += fault
                active += ActiveFault(fault, timestampNs, maxAbsZ, maxAbsZ)
            }
        }

        return FaultUpdate(active, onsets, clears)
    }

    private fun seconds(deltaNs: Long) = deltaNs / 1_000_000_000.0

    /**
     * A condition is directional. `"+"` means the measured angle must exceed the reference by
     * `z_on`, `"-"` means it must fall below by the same margin. Appendix A gives what each
     * direction physically means; nothing here infers it.
     */
    private fun holds(condition: FaultCondition, z: Double): Boolean = when (condition.sign) {
        "+" -> z >= condition.zOn
        "-" -> z <= -condition.zOn
        else -> abs(z) >= condition.zOn
    }
}

/**
 * §6.14: "fault condition names are bound and asserted at startup alongside the mask rules."
 *
 * The feature name itself is already a [FeatureId], so a typo cannot compile. What can still go
 * wrong is a fault naming a feature this reference does not watch - it would simply never fire, and
 * a fault that silently never fires is worse than one that errors.
 *
 * @return fault id to the reason it cannot fire, empty when every fault binds.
 */
fun unbindableFaults(faults: List<FaultDefinition>, watchList: WatchList): Map<String, String> {
    val watched = watchList.features.map { it.id }.toSet()
    return faults.mapNotNull { fault ->
        val missing = fault.conditions.map { it.feature }.filterNot { it in watched }
        when {
            fault.conditions.isEmpty() -> fault.id to "has no conditions"
            missing.isNotEmpty() -> fault.id to "not on the watch list: " + missing.joinToString { it.value }
            fault.phaseRange.first > fault.phaseRange.second ->
                fault.id to "phase range is inverted"
            else -> null
        }
    }.toMap()
}
