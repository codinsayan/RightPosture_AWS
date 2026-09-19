package com.example.rightposture.core.live

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.reference.FeatureReference
import com.example.rightposture.core.reference.Reference
import com.example.rightposture.core.reference.WatchList
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * §6.13. How far the patient is from the reference, in units of how much the physio actually varied.
 *
 * **The sign carries as much information as the magnitude** - it is what selects the cue. Positive z
 * means the measured angle **exceeds** the reference. Appendix A gives the physical meaning per
 * feature and is authoritative; §6.13.3 calls a backwards reading here the single most common source
 * of backwards cues, so nothing in this file infers a direction from a formula.
 */

/** The reference at one continuous phase position. */
data class ReferencePoint(
    val mu: Double,
    val sigma: Double,
    /** `dμ/dp`, the reference's slope at this phase. Drives the §6.13.2 inflation. */
    val dMu: Double,
)

data class FeatureZ(
    val id: FeatureId,
    val z: Double,
    val measured: Double,
    val mu: Double,
    /** The σ actually divided by: the band widened for phase uncertainty, then floored. */
    val sigmaEff: Double,
)

/**
 * §6.13.1. `p` is continuous; the reference is binned at integers, so interpolate between the two
 * surrounding bins rather than rounding to one.
 */
fun referenceAt(feature: FeatureReference, phase: Double): ReferencePoint {
    val bins = feature.mean.size
    val p = phase.coerceIn(0.0, (bins - 1).toDouble())
    val p0 = floor(p).toInt()
    val p1 = minOf(p0 + 1, bins - 1)
    val w = p - p0
    fun lerp(values: List<Float>) = (1 - w) * values[p0] + w * values[p1]
    return ReferencePoint(lerp(feature.mean), lerp(feature.sigma), lerp(feature.derivative))
}

/**
 * §6.13.2. `δp` in phase units.
 *
 * The phase estimate is uncertain by roughly ±3 units, and by more when the dominant joint is
 * crawling - a slow-moving joint pins phase down poorly. [vRef] is the reference movement's own
 * median `|dφ/dt|`, stored in the manifest as `phase.v_ref`.
 *
 * @param vRef null for an older `.rpose` that predates the field, which falls back to a flat 3.0
 *   exactly as §6.13.2 specifies. Guessing a value from the current rep would make the tolerance
 *   depend on the patient rather than on the reference.
 */
fun phaseUncertainty(vRef: Double?, velocity: Double): Double {
    if (vRef == null || !vRef.isFinite() || vRef <= 0.0) return Params.PHASE_UNCERTAINTY_UNITS
    val scale = (vRef / maxOf(abs(velocity), Params.PHASE_UNCERTAINTY_MIN_VELOCITY))
        .coerceIn(1.0, Params.PHASE_UNCERTAINTY_MAX_SCALE)
    return Params.PHASE_UNCERTAINTY_UNITS * scale
}

/**
 * §6.13.2 error propagation: `σ_eff = sqrt(σ² + (|dμ| · δp)²)`.
 *
 * This widens the tolerance exactly where the reference is changing fastest - mid-movement, where a
 * small phase error means a large expected-angle difference. §6.13.2: without it you get a burst of
 * spurious faults every rep at the steepest part of the movement.
 *
 * The floor is §6.22's `σ_eff < 0.5°` rule. A near-zero denominator turns a rounding error into a
 * ten-sigma fault.
 */
fun effectiveSigma(point: ReferencePoint, deltaP: Double): Double {
    val widened = sqrt(point.sigma * point.sigma + (abs(point.dMu) * deltaP) * (abs(point.dMu) * deltaP))
    return maxOf(widened, Params.SIGMA_EFF_MIN_DEGREES)
}

/**
 * §6.13.3, for one feature. Null when the reference does not carry the feature or the value is not
 * usable - abstaining beats coaching off a number we do not trust.
 */
fun zScoreOf(
    id: FeatureId,
    measured: Float,
    feature: FeatureReference,
    phase: Double,
    deltaP: Double,
): FeatureZ? {
    if (!measured.isFinite()) return null
    val point = referenceAt(feature, phase)
    if (!point.mu.isFinite() || !point.sigma.isFinite() || !point.dMu.isFinite()) return null
    val sigmaEff = effectiveSigma(point, deltaP)
    val z = (measured - point.mu) / sigmaEff
    if (!z.isFinite()) return null
    return FeatureZ(id, z, measured.toDouble(), point.mu, sigmaEff)
}

/**
 * §6.13.3 across the frame. Computed **only for valid, watch-listed features**: a feature the
 * patient's landmarks could not support (1.6) is absent from [measured] and therefore absent here,
 * and a feature outside the watch list was never something this reference scores.
 */
fun zScores(
    measured: Map<FeatureId, Float>,
    reference: Reference,
    watchList: WatchList,
    phase: Double,
    velocity: Double,
    vRef: Double?,
): Map<FeatureId, FeatureZ> {
    val deltaP = phaseUncertainty(vRef, velocity)
    return watchList.features.mapNotNull { watched ->
        val value = measured[watched.id] ?: return@mapNotNull null
        val feature = reference.features[watched.id] ?: return@mapNotNull null
        zScoreOf(watched.id, value, feature, phase, deltaP)?.let { watched.id to it }
    }.toMap()
}
