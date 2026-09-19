package com.example.rightposture.core.live

import com.example.rightposture.core.FeatureId

/**
 * §13.1's `Rep` and `FeatureSample` rows, in memory. §6.1 of the iteration plan is explicit that
 * there is no database yet: this is the shape 7.1 will persist and the shape 6.2 will read, built
 * now so that CUSUM is not blocked on Room.
 *
 * Everything here exists because §6.18 needs it, and nothing here exists for any other reason:
 *
 * | §6.18 needs | supplied by |
 * |---|---|
 * | `Q_i` over valid reps (6.18.2 baseline, 6.18.3 CUSUM, 6.18.4 slope) | [SessionRecord.validQualitySeries] |
 * | rep index of the change point | [RepRecord.index] |
 * | signed `z_j` per feature, per rep (6.18.5 attribution) | [FeatureSample.meanSignedZ] |
 * | `ROM_i` (6.18.5 ΔROM) | [RepRecord.romDegrees] |
 * | `T_i` (6.18.5 ΔTempo) | [RepRecord.durationSeconds] |
 * | the dominant angle's velocity profile (6.18.5 SPARC) | [RepRecord.velocityProfile] |
 * | `Q_i < 0.7·B` for two consecutive reps (6.18.6) | [SessionRecord.validQualitySeries] |
 */

/**
 * §13.1's `FeatureSample`, aggregated over the frames of one rep.
 *
 * Both the absolute and the signed mean are kept, and they answer different questions. §6.17.1
 * scores on `z²`, so magnitude is what quality is made of; §6.18.5 attributes on the **signed**
 * mean, because "the knee drifted 2σ further into valgus" and "the knee drifted 2σ out of it" are
 * opposite clinical findings that share a magnitude.
 */
data class FeatureSample(
    val feature: FeatureId,
    /** Mean `|z|`. */
    val meanZ: Double,
    /** Largest `|z|` in the rep, so a brief excursion is not averaged away. */
    val maxZ: Double,
    /** Mean `z`, sign preserved. §6.18.5's `e_j`. */
    val meanSignedZ: Double,
    /** Frames this feature was actually scored on. A mean over 3 frames is not a mean over 90. */
    val frames: Int,
)

/**
 * One finished rep, whatever became of it.
 *
 * [quality] is nullable and stays nullable. §1.6's rule is that absent means "no score", never
 * zero: a rep the tracker accepted but could not score is not a rep the patient performed badly,
 * and feeding a zero into §6.18.2's baseline would invent a collapse that never happened.
 */
data class RepRecord(
    /** 1-based, and counting **every** finished rep, so §6.18.3's change point indexes something real. */
    val index: Int,
    val quality: Double?,
    /** §6.17.3's `ROM_i`, in degrees. */
    val romDegrees: Double,
    val durationSeconds: Double,
    /** `φ_max`, kept because §6.12.4's acceptance and §6.18's ROM are not the same measurement. */
    val phiMax: Double,
    val valid: Boolean,
    /** Null when [valid]. §6.12.4's reason otherwise. */
    val rejection: RepRejection?,
    val features: Map<FeatureId, FeatureSample>,
    /**
     * `dφ/dt` per scored frame of the rep, for §6.18.5's smoothness term.
     *
     * Captured rather than reduced, because SPARC is a frequency-domain measure and there is no
     * scalar to carry forward in its place. §6.18.5 permits dropping smoothness entirely if it
     * proves fiddly; this is here so that decision stays open at 6.2 rather than being made by
     * default now, and it costs roughly ninety doubles per rep.
     */
    val velocityProfile: List<Double>,
) {
    /** A rep that finished and could not be scored at all - distinct from one that scored badly. */
    val unscored: Boolean get() = quality == null
}

/**
 * §13.1's `Session`, in memory, for one set.
 *
 * Immutable and rebuilt on each completed rep rather than mutated, for the same reason
 * [FrameResult] is: it crosses from the analysis thread to the UI, and a mutable list read during
 * a recomposition is a race by construction.
 */
data class SessionRecord(
    val reps: List<RepRecord>,
    val prescribedReps: Int,
) {
    val validReps: Int get() = reps.count { it.valid }

    /** Finished, and refused by §6.12.4. */
    val partialReps: Int get() = reps.count { !it.valid }

    /** Finished, accepted by the tracker, and scoreless. A subset of [partialReps]. */
    val unscoredReps: Int get() = reps.count { it.unscored }

    /**
     * §6.18's input series: `Q_i` over **valid** reps, in order.
     *
     * Partial reps are excluded rather than zeroed. §6.12.4 already decided they are not reps, and
     * a zero dropped into the middle of this series would read to CUSUM as a catastrophic decline
     * at exactly the moment the patient did something the app declined to measure.
     */
    val validQualitySeries: List<Double>
        get() = reps.filter { it.valid }.mapNotNull { it.quality }

    /** §6.17.4. Median over valid reps; null until one exists. */
    val setQuality: Double? get() = setScore(scoredReps(), prescribedReps).score

    val completionRate: Double get() = setScore(scoredReps(), prescribedReps).completionRate

    /**
     * §6.18.1's prerequisite, asked here so that 6.2 has one place to read it and the UI has one
     * place to explain it. Below this the honest answer is "not enough reps to assess fatigue",
     * never a change point derived from four numbers.
     */
    val hasEnoughRepsForFatigue: Boolean get() = validReps >= MIN_REPS_FOR_FATIGUE

    /** Mean signed z per feature across valid reps, weighted by the frames each rep contributed. */
    fun meanSignedZ(feature: FeatureId, repIndices: IntRange? = null): Double? {
        val samples = reps
            .filter { it.valid && (repIndices == null || it.index in repIndices) }
            .mapNotNull { it.features[feature] }
            .filter { it.frames > 0 }
        if (samples.isEmpty()) return null
        val frames = samples.sumOf { it.frames }
        return samples.sumOf { it.meanSignedZ * it.frames } / frames
    }

    /** Every feature any valid rep scored, which is what §6.18.5 iterates over. */
    fun scoredFeatures(): Set<FeatureId> =
        reps.filter { it.valid }.flatMap { it.features.keys }.toSet()

    private fun scoredReps() = reps.mapNotNull { rec -> rec.quality?.let { ScoredRep(it, rec.valid) } }

    companion object {
        /** §6.18.1. */
        const val MIN_REPS_FOR_FATIGUE = 6

        val EMPTY = SessionRecord(emptyList(), 0)
    }
}

/**
 * Accumulates one rep's per-feature z statistics as its frames arrive.
 *
 * Mutable and internal to the engine: the record it produces is immutable, and nothing outside
 * [ExerciseEngine] ever sees this.
 */
internal class FeatureAccumulator {
    private val sumAbs = mutableMapOf<FeatureId, Double>()
    private val sumSigned = mutableMapOf<FeatureId, Double>()
    private val maxAbs = mutableMapOf<FeatureId, Double>()
    private val counts = mutableMapOf<FeatureId, Int>()

    fun add(zScores: Map<FeatureId, FeatureZ>) {
        zScores.forEach { (id, featureZ) ->
            val z = featureZ.z
            // A non-finite z is not a large deviation, it is a failed measurement. §6.22.
            if (!z.isFinite()) return@forEach
            val magnitude = kotlin.math.abs(z)
            sumAbs[id] = (sumAbs[id] ?: 0.0) + magnitude
            sumSigned[id] = (sumSigned[id] ?: 0.0) + z
            maxAbs[id] = maxOf(maxAbs[id] ?: 0.0, magnitude)
            counts[id] = (counts[id] ?: 0) + 1
        }
    }

    fun samples(): Map<FeatureId, FeatureSample> = counts.mapValues { (id, frames) ->
        FeatureSample(
            feature = id,
            meanZ = (sumAbs[id] ?: 0.0) / frames,
            maxZ = maxAbs[id] ?: 0.0,
            meanSignedZ = (sumSigned[id] ?: 0.0) / frames,
            frames = frames,
        )
    }

    fun clear() {
        sumAbs.clear()
        sumSigned.clear()
        maxAbs.clear()
        counts.clear()
    }
}
