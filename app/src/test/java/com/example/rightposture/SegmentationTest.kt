package com.example.rightposture

import com.example.rightposture.core.reference.ExtremumKind
import com.example.rightposture.core.reference.segmentReps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SegmentationTest {
    private val samplesPerRep = 60

    @Test
    fun eightCleanRepsDetectEightAndKeepSix() {
        val result = segmentReps(cleanReps(8), samplesPerRep)

        assertEquals(8, result.detected.size)
        assertEquals(6, result.usable.size)
        assertNull(result.rejection)
    }

    @Test
    fun theDroppedRepsAreTheFirstAndLastSpecifically() {
        val result = segmentReps(cleanReps(8), samplesPerRep)

        assertEquals(result.detected.subList(1, 7), result.usable)
    }

    @Test
    fun midRepWobbleDoesNotSplitARep() {
        // A small second-harmonic ripple inside every rep: a naive local-extremum search sees three
        // maxima per rep, and only the 0.6 x T spacing rule collapses them back to one.
        val wobbly = List(8 * samplesPerRep + 2 * LEAD) { index ->
            val phase = 2.0 * PI * (index - LEAD) / samplesPerRep
            (-40.0 * cos(phase) + 4.0 * cos(3.0 * phase)).toFloat()
        }

        val result = segmentReps(wobbly, samplesPerRep)

        assertEquals(8, result.detected.size)
        assertEquals(6, result.usable.size)
        result.usable.forEach {
            assertEquals(samplesPerRep, it.lengthSamples)
        }
    }

    @Test
    fun lowAmplitudeNoisePeaksAreRejectedByProminence() {
        // Real reps plus a flat, jittery tail. The tail's wiggles are local maxima but their
        // prominence is far under 0.25 x swing, so no rep may be found there.
        val real = cleanReps(8)
        val tail = List(3 * samplesPerRep) { index -> 0.5f * sin(index * 1.7).toFloat() }

        val result = segmentReps(real + tail, samplesPerRep)

        assertEquals(8, result.detected.size)
        result.detected.forEach {
            assertTrue("rep at ${it.startIndex} strayed into the flat tail", it.endIndex <= real.size)
        }
    }

    @Test
    fun aDurationOutlierIsDropped() {
        // Nine reps where one interior rep runs at double length. After first/last removal, seven
        // remain and the stretched one is more than 0.35 x median from the median.
        val signal = repsOfLengths(List(9) { if (it == 4) samplesPerRep * 2 else samplesPerRep })

        val result = segmentReps(signal, samplesPerRep)

        assertEquals(9, result.detected.size)
        assertEquals(6, result.usable.size)
        assertNull(result.rejection)
        assertTrue(
            "the double-length rep must not survive",
            result.usable.none { it.lengthSamples > samplesPerRep + 5 },
        )
    }

    @Test
    fun fewerThanFourSurvivorsRejectsTheRecording() {
        // Five reps detected leaves three after first/last removal, one short of the bar.
        val result = segmentReps(cleanReps(5), samplesPerRep)

        assertEquals(5, result.detected.size)
        assertTrue(result.usable.isEmpty())
        assertNotNull(result.rejection)
        assertTrue(result.rejection!!, result.rejection!!.contains("4"))
    }

    @Test
    fun sixRepsIsExactlyTheMinimumThatSurvives() {
        val result = segmentReps(cleanReps(6), samplesPerRep)

        assertEquals(4, result.usable.size)
        assertNull(result.rejection)
    }

    @Test
    fun repBoundariesSitOnTheStartPose() {
        // Starts at a valley, so valley-to-valley boundaries match the pose at t = 0.
        val startingLow = segmentReps(cleanReps(8), samplesPerRep)
        // Same movement started half a rep later, i.e. from a peak.
        val startingHigh = List(8 * samplesPerRep + 2 * LEAD) { index ->
            (40.0 * cos(2.0 * PI * (index - LEAD) / samplesPerRep)).toFloat()
        }

        assertEquals(ExtremumKind.VALLEY, startingLow.boundaryKind)
        assertEquals(ExtremumKind.PEAK, segmentReps(startingHigh, samplesPerRep).boundaryKind)
    }

    @Test
    fun extremaAlternateAfterRepair() {
        val result = segmentReps(cleanReps(8), samplesPerRep)

        result.extrema.zipWithNext().forEach { (a, b) ->
            assertTrue("two ${a.kind} in a row at ${a.index} and ${b.index}", a.kind != b.kind)
            assertTrue("extrema out of order", a.index < b.index)
        }
    }

    @Test
    fun everySegmentIsForwardAndInsideTheRecording() {
        val signal = cleanReps(8)

        val result = segmentReps(signal, samplesPerRep)

        result.detected.forEach {
            assertTrue(it.startIndex in signal.indices)
            assertTrue(it.endIndex in signal.indices)
            assertTrue(it.lengthSamples > 0)
        }
    }

    @Test
    fun invalidInputIsRejectedRatherThanCrashing() {
        assertNotNull(segmentReps(cleanReps(8), 1).rejection)
        assertNotNull(segmentReps(listOf(1f, 2f), samplesPerRep).rejection)
        assertNotNull(
            segmentReps(cleanReps(8).toMutableList().also { it[10] = Float.NaN }, samplesPerRep)
                .rejection,
        )
    }

    /**
     * [lengths] consecutive reps, each a full cycle starting and ending at a valley, wrapped in a
     * short lead-in and lead-out.
     *
     * The padding is not decoration. A boundary extremum sitting on sample 0 or the last sample has
     * no outside neighbour and cannot be detected as a local extremum, so an unpadded signal loses
     * a rep at each end. Real recordings always have the physio settling in and reaching for stop,
     * which is exactly what section 6.7.6 step 1 assumes.
     */
    private fun repsOfLengths(lengths: List<Int>): List<Float> {
        fun sample(index: Int, length: Int) = (-40.0 * cos(2.0 * PI * index / length)).toFloat()
        val out = mutableListOf<Float>()
        repeat(LEAD) { out.add(sample(lengths.first() - LEAD + it, lengths.first())) }
        lengths.forEach { length -> repeat(length) { out.add(sample(it, length)) } }
        repeat(LEAD) { out.add(sample(it, lengths.last())) }
        return out
    }

    private fun cleanReps(reps: Int) = repsOfLengths(List(reps) { samplesPerRep })

    private companion object {
        const val LEAD = 15
    }
}
