package com.example.rightposture

import com.example.rightposture.core.filter.butterworthFiltFilt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.sin

class ButterworthFilterTest {
    private val sampleRateHz = 60.0
    private val samples = 600

    @Test
    fun preservesLowFrequencyMovement() {
        val low = signal(1.0)
        val filtered = butterworthFiltFilt(low, sampleRateHz)

        assertEquals(low.size, filtered.size)
        assertTrue(rms(error(filtered, low), edge = 60) < 0.002)
    }

    @Test
    fun removesHighFrequencyNoise() {
        val low = signal(1.0)
        val noisy = low.zip(signal(15.0)) { movement, noise -> movement + 0.3f * noise }
        val filtered = butterworthFiltFilt(noisy, sampleRateHz)

        assertTrue(rms(error(filtered, low), edge = 60) < rms(error(noisy, low), edge = 60) * 0.1)
    }

    @Test
    fun forwardBackwardPassIntroducesNoMeasurablePhaseShift() {
        val input = signal(3.0)
        val output = butterworthFiltFilt(input, sampleRateHz)
        val edge = 60
        val bestLag = (-5..5).maxBy { lag ->
            (edge until samples - edge).sumOf { index ->
                input[index].toDouble() * output[index + lag]
            }
        }

        assertEquals(0, bestLag)
    }

    private fun signal(frequencyHz: Double) = List(samples) { index ->
        sin(2.0 * PI * frequencyHz * index / sampleRateHz).toFloat()
    }

    private fun error(actual: List<Float>, expected: List<Float>) =
        actual.zip(expected) { a, e -> a - e }

    private fun rms(values: List<Float>, edge: Int): Double {
        val middle = values.subList(edge, values.size - edge)
        return sqrt(middle.sumOf { it.toDouble() * it } / middle.size)
    }
}
