package com.example.rightposture.core.live

import com.example.rightposture.core.Params
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * §6.18.5's smoothness term: spectral arc length.
 *
 * The plan is emphatic about what this replaces. An earlier draft specified jerk as a third time
 * derivative; differentiating a noisy 30 Hz signal three times amplifies noise by roughly the cube
 * of the frequency, so the result measures landmark jitter rather than movement quality - "a number
 * that looks meaningful and is not". SPARC is computed in the frequency domain and is therefore
 * robust to exactly that noise.
 *
 * ```
 * SPARC = −∫ sqrt( (1/ω_c)² + (dM̂/dω)² ) dω     over [0, ω_c]
 * ```
 *
 * More negative means less smooth. §6.18.5 reports `ΔSmooth = SPARC_post − SPARC_pre`, so a
 * decrease is movement roughening.
 */

/**
 * @param speedProfile the **speed** of the dominant joint, one sample per frame.
 *
 *   Speed, not signed velocity, and this is not a detail. SPARC normalises the magnitude spectrum
 *   by its value at DC, and a rep's signed velocity is positive on the way out and negative on the
 *   way back, so its DC term is approximately zero and the normalisation divides by nothing. The
 *   movement-science literature applies SPARC to the speed profile for this reason.
 * @param sampleRateHz frames per second over the rep.
 * @return null when the rep is too short to have a spectrum, the sample rate is unusable, or the
 *   signal has no DC component to normalise against. §6.18.5 explicitly permits dropping the
 *   smoothness term; a null here is that, per rep, rather than a fabricated number.
 */
fun spectralArcLength(speedProfile: List<Double>, sampleRateHz: Double): Double? {
    val n = speedProfile.size
    if (n < MIN_SAMPLES) return null
    if (!sampleRateHz.isFinite() || sampleRateHz <= 0.0) return null
    if (speedProfile.any { !it.isFinite() }) return null

    val bins = n / 2
    if (bins < 2) return null

    // The DC term is the sum, so a profile that is all zeros - a joint that never moved - has
    // nothing to normalise by and no smoothness to speak of.
    val magnitudes = (0..bins).map { k -> magnitudeAt(speedProfile, k) }
    val dc = magnitudes[0]
    if (dc <= 1e-12) return null
    val normalised = magnitudes.map { it / dc }

    val deltaHz = sampleRateHz / n
    if (!deltaHz.isFinite() || deltaHz <= 0.0) return null

    // §6.18.5 words this as "the frequency where magnitude first falls below 0.05". Taken
    // literally that is the *first* downward crossing, and it defeats the metric: a movement
    // spectrum is not monotonic, so a tremor sits in its own lobe well above the first dip. A
    // half-sine rep plus a 9-cycle tremor first falls below 0.05 at bin 2 while the tremor peaks at
    // bin 9, so the literal band stops short of the roughness and the rougher movement scores as
    // the smoother one - measured, and the reason this reads the other way round.
    //
    // The published definition (Balasubramanian et al.) takes the **last** frequency still at or
    // above the threshold, which is what makes §6.18.5's own claim - "a decrease indicates
    // roughening movement" - true. That is what is implemented.
    val capBin = minOf(bins, (Params.SPARC_MAX_FREQUENCY_HZ / deltaHz).toInt())
    if (capBin < 2) return null
    val cutoffBin = (capBin downTo 1)
        .firstOrNull { normalised[it] >= Params.SPARC_MAGNITUDE_CUTOFF }
        ?: return null
    if (cutoffBin < 2) return null

    val omegaC = cutoffBin * deltaHz
    if (!omegaC.isFinite() || omegaC <= 0.0) return null

    // Rectangle sum over the retained bins. The arc length integrand is already a magnitude, so a
    // finer quadrature buys nothing against the uncertainty in the sample rate itself.
    var arc = 0.0
    for (k in 0 until cutoffBin) {
        val slope = (normalised[k + 1] - normalised[k]) / deltaHz
        arc += sqrt((1.0 / omegaC) * (1.0 / omegaC) + slope * slope) * deltaHz
    }
    return if (arc.isFinite()) -arc else null
}

/** One bin of the magnitude spectrum. O(n) per bin is ample: a rep is a few hundred samples. */
private fun magnitudeAt(signal: List<Double>, k: Int): Double {
    val n = signal.size
    var real = 0.0
    var imaginary = 0.0
    for (index in 0 until n) {
        val angle = -2.0 * PI * k * index / n
        real += signal[index] * cos(angle)
        imaginary += signal[index] * sin(angle)
    }
    return sqrt(real * real + imaginary * imaginary)
}

/** Signed velocity to the speed profile SPARC needs. */
internal fun speedOf(velocityProfile: List<Double>): List<Double> =
    velocityProfile.filter { it.isFinite() }.map { abs(it) }

private const val MIN_SAMPLES = 8
