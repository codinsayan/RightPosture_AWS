package com.example.rightposture.core.filter

import com.example.rightposture.core.Params
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Fourth-order Butterworth low-pass applied forward/backward for zero phase. */
fun butterworthFiltFilt(
    values: List<Float>,
    sampleRateHz: Double,
    cutoffHz: Double = Params.BUTTERWORTH_CUTOFF_HZ,
): List<Float> {
    require(sampleRateHz.isFinite() && sampleRateHz > 0.0)
    require(cutoffHz.isFinite() && cutoffHz > 0.0 && cutoffHz < sampleRateHz / 2.0)
    require(values.all(Float::isFinite))
    if (values.size < 2) return values.toList()

    val padding = min(3 * Params.BUTTERWORTH_ORDER, values.lastIndex)
    val reflected = buildList(values.size + 2 * padding) {
        for (index in padding downTo 1) add(values[index].toDouble())
        values.forEach { add(it.toDouble()) }
        for (index in values.lastIndex - 1 downTo values.lastIndex - padding) {
            add(values[index].toDouble())
        }
    }
    val coefficients = butterworthSections(sampleRateHz, cutoffHz)
    val forward = filterCascade(reflected, coefficients)
    val backward = filterCascade(forward.asReversed(), coefficients).asReversed()
    return backward.subList(padding, padding + values.size).map(Double::toFloat)
}

private data class Biquad(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
)

private fun butterworthSections(sampleRateHz: Double, cutoffHz: Double): List<Biquad> {
    val omega = 2.0 * PI * cutoffHz / sampleRateHz
    return listOf(0.541196100146197, 1.306562964876377).map { quality ->
        val alpha = sin(omega) / (2.0 * quality)
        val a0 = 1.0 + alpha
        Biquad(
            b0 = (1.0 - cos(omega)) / 2.0 / a0,
            b1 = (1.0 - cos(omega)) / a0,
            b2 = (1.0 - cos(omega)) / 2.0 / a0,
            a1 = -2.0 * cos(omega) / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }
}

private fun filterCascade(values: List<Double>, sections: List<Biquad>): List<Double> =
    sections.fold(values) { current, coefficients -> filterBiquad(current, coefficients) }

private fun filterBiquad(values: List<Double>, c: Biquad): List<Double> {
    var x1 = values.first()
    var x2 = x1
    var y1 = x1
    var y2 = x1
    return values.map { x ->
        val y = c.b0 * x + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        y
    }
}
