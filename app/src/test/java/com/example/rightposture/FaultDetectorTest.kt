package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.live.FaultDetector
import com.example.rightposture.core.live.FaultUpdate
import com.example.rightposture.core.live.FeatureZ
import com.example.rightposture.core.live.unbindableFaults
import com.example.rightposture.core.reference.WatchList
import com.example.rightposture.core.reference.WatchedFeature
import com.example.rightposture.core.rpose.FaultCondition
import com.example.rightposture.core.rpose.FaultDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultDetectorTest {

    private val knee = FeatureId.KNEE_FLEX_ACTIVE
    private val lumbar = FeatureId.LUMBAR_FLEXION

    private fun fault(
        id: String = "knee_incomplete_extension",
        conditions: List<FaultCondition> = listOf(FaultCondition(knee, "+", 3.0)),
        phaseRange: Pair<Int, Int> = 0 to 100,
        priority: Double = 1.0,
    ) = FaultDefinition(
        id = id,
        conditions = conditions,
        phaseRange = phaseRange,
        priority = priority,
        maskedBy = emptyList(),
        cueShort = "Straighten all the way.",
        cueFull = "Push your heel forward until your leg is fully straight.",
        cueAlt = "Reach your toes toward the far wall.",
        arrowJoint = "ankle",
        arrowAxis = "reference_position",
        generatedBy = "llm",
        validated = true,
    )

    private fun z(vararg values: Pair<FeatureId, Double>) = values.associate {
        it.first to FeatureZ(it.first, it.second, 100.0, 100.0, 4.0)
    }

    /** Drives a detector at 30 fps and remembers everything it said. */
    private class Feeder(private val detector: FaultDetector) {
        private var timestampNs = 0L
        val onsets = mutableListOf<String>()
        val clears = mutableListOf<String>()
        var last: FaultUpdate? = null
            private set

        fun push(zScores: Map<FeatureId, FeatureZ>, phase: Double = 50.0, frames: Int = 1) {
            repeat(frames) {
                val update = detector.update(zScores, phase, timestampNs)
                last = update
                onsets += update.onsets.map { it.id }
                clears += update.clears.map { it.id }
                timestampNs += 33_333_333L
            }
        }

        fun activeIds() = last!!.active.map { it.definition.id }
    }

    private fun feeder(vararg faults: FaultDefinition) = Feeder(FaultDetector(faults.toList()))

    // -------------------------------------------------------------------------------------
    // §6.14.1 dwell
    // -------------------------------------------------------------------------------------

    @Test
    fun `a single-frame spike never fires`() {
        val feeder = feeder(fault())
        feeder.push(z(knee to 0.5), frames = 10)
        feeder.push(z(knee to 8.0), frames = 1)
        feeder.push(z(knee to 0.5), frames = 10)
        assertTrue(feeder.onsets.isEmpty())
    }

    @Test
    fun `a deviation shorter than the dwell never fires`() {
        // 8 frames at 30 fps is 267 ms, just under the 300 ms bar.
        val feeder = feeder(fault())
        feeder.push(z(knee to 4.0), frames = 8)
        feeder.push(z(knee to 0.0), frames = 5)
        assertTrue(feeder.onsets.isEmpty())
    }

    @Test
    fun `a sustained fault fires exactly once and stays active`() {
        val feeder = feeder(fault())
        feeder.push(z(knee to 4.0), frames = 60)
        assertEquals(listOf("knee_incomplete_extension"), feeder.onsets)
        assertEquals(listOf("knee_incomplete_extension"), feeder.activeIds())
        assertTrue(feeder.clears.isEmpty())
    }

    @Test
    fun `jitter across the threshold does not chatter`() {
        val feeder = feeder(fault())
        // Alternating either side of Z_ON for two seconds. Continuity is broken every frame, so the
        // dwell never completes and nothing is ever said.
        repeat(60) { index ->
            feeder.push(z(knee to if (index % 2 == 0) 3.1 else 2.9), frames = 1)
        }
        assertTrue(feeder.onsets.isEmpty())
        assertTrue(feeder.clears.isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // §6.14.1 hysteresis
    // -------------------------------------------------------------------------------------

    @Test
    fun `an active fault survives a drop between the two thresholds`() {
        val feeder = feeder(fault())
        feeder.push(z(knee to 4.0), frames = 20)
        assertEquals(1, feeder.onsets.size)
        // 2.0 is below Z_ON but above Z_OFF: the patient is still wrong, just less so.
        feeder.push(z(knee to 2.0), frames = 60)
        assertTrue(feeder.clears.isEmpty())
        assertEquals(1, feeder.activeIds().size)
    }

    @Test
    fun `recovery clears only after the exit dwell`() {
        val feeder = feeder(fault())
        feeder.push(z(knee to 4.0), frames = 20)
        // 12 frames is 400 ms, short of the 500 ms exit bar.
        feeder.push(z(knee to 0.5), frames = 12)
        assertTrue("must not clear early", feeder.clears.isEmpty())
        feeder.push(z(knee to 0.5), frames = 6)
        assertEquals(listOf("knee_incomplete_extension"), feeder.clears)
        assertTrue(feeder.activeIds().isEmpty())
    }

    @Test
    fun `a brief dip into the clear zone does not end the fault`() {
        val feeder = feeder(fault())
        feeder.push(z(knee to 4.0), frames = 20)
        feeder.push(z(knee to 0.5), frames = 6)
        feeder.push(z(knee to 4.0), frames = 6)
        feeder.push(z(knee to 0.5), frames = 6)
        assertTrue(feeder.clears.isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // §6.14.2 phase gating
    // -------------------------------------------------------------------------------------

    @Test
    fun `the same deviation outside the phase window never fires`() {
        val windowed = fault(phaseRange = 45 to 55)
        val inside = feeder(windowed).also { it.push(z(knee to 4.0), phase = 50.0, frames = 30) }
        val outside = feeder(windowed).also { it.push(z(knee to 4.0), phase = 5.0, frames = 30) }
        assertEquals(1, inside.onsets.size)
        assertTrue(outside.onsets.isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // §6.14.3 validity gate
    // -------------------------------------------------------------------------------------

    @Test
    fun `a fault never fires on a feature that abstained`() {
        val feeder = feeder(fault())
        feeder.push(emptyMap(), frames = 60)
        assertTrue(feeder.onsets.isEmpty())
    }

    @Test
    fun `losing sight of the joint retracts a standing fault`() {
        val feeder = feeder(fault())
        feeder.push(z(knee to 4.0), frames = 20)
        assertEquals(1, feeder.onsets.size)
        feeder.push(emptyMap(), frames = 1)
        assertEquals(listOf("knee_incomplete_extension"), feeder.clears)
        assertTrue(feeder.activeIds().isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // Multi-condition and sign
    // -------------------------------------------------------------------------------------

    @Test
    fun `a two-condition fault needs both, not either`() {
        // §6.14's butt-wink example: lumbar flexion at the bottom of the movement. Neither the
        // lumbar reading nor the knee reading means anything on its own.
        val buttWink = fault(
            id = "butt_wink",
            conditions = listOf(FaultCondition(lumbar, "+", 2.5), FaultCondition(knee, "+", 3.0)),
            phaseRange = 45 to 55,
        )
        val oneOnly = feeder(buttWink).also { it.push(z(lumbar to 4.0, knee to 0.2), frames = 30) }
        assertTrue(oneOnly.onsets.isEmpty())

        val both = feeder(buttWink).also { it.push(z(lumbar to 4.0, knee to 3.5), frames = 30) }
        assertEquals(listOf("butt_wink"), both.onsets)
    }

    @Test
    fun `a negative condition fires on the negative side only`() {
        val dropping = fault(id = "over_bent", conditions = listOf(FaultCondition(knee, "-", 3.0)))
        val wrongWay = feeder(dropping).also { it.push(z(knee to 4.0), frames = 30) }
        val rightWay = feeder(dropping).also { it.push(z(knee to -4.0), frames = 30) }
        assertTrue(wrongWay.onsets.isEmpty())
        assertEquals(listOf("over_bent"), rightWay.onsets)
    }

    @Test
    fun `two faults are tracked independently`() {
        val a = fault(id = "a", conditions = listOf(FaultCondition(knee, "+", 3.0)))
        val b = fault(id = "b", conditions = listOf(FaultCondition(lumbar, "+", 3.0)))
        val feeder = feeder(a, b)
        feeder.push(z(knee to 4.0, lumbar to 0.0), frames = 20)
        assertEquals(listOf("a"), feeder.activeIds())
        feeder.push(z(knee to 4.0, lumbar to 4.0), frames = 20)
        assertEquals(setOf("a", "b"), feeder.activeIds().toSet())
    }

    @Test
    fun `peak z is remembered for the life of the fault`() {
        val feeder = feeder(fault())
        feeder.push(z(knee to 3.2), frames = 20)
        feeder.push(z(knee to 5.5), frames = 5)
        feeder.push(z(knee to 3.2), frames = 5)
        assertEquals(5.5, feeder.last!!.active.single().peakAbsZ, 1e-9)
    }

    // -------------------------------------------------------------------------------------
    // Gate: no chatter over a synthetic sequence
    // -------------------------------------------------------------------------------------

    @Test
    fun `twenty noisy reps produce no chatter`() {
        val feeder = feeder(fault(phaseRange = 40 to 60))
        var said = 0
        repeat(20) { rep ->
            (0 until 90).forEach { frame ->
                val phase = frame * 100.0 / 89
                // Noise that grazes the threshold for a frame or two at a time, never longer.
                val noise = if (frame % 7 == 0) 3.4 else 1.0
                feeder.push(z(knee to noise), phase = phase, frames = 1)
            }
            said = feeder.onsets.size
        }
        assertEquals("a grazing deviation must never be announced", 0, said)
    }

    @Test
    fun `reset drops a standing fault`() {
        val detector = FaultDetector(listOf(fault()))
        val feeder = Feeder(detector)
        feeder.push(z(knee to 4.0), frames = 20)
        assertEquals(1, feeder.activeIds().size)
        detector.reset()
        feeder.push(z(knee to 0.0), frames = 1)
        assertTrue(feeder.activeIds().isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // Binding (§6.14)
    // -------------------------------------------------------------------------------------

    @Test
    fun `a fault naming an unwatched feature is reported rather than silently never firing`() {
        val watchList = WatchList(listOf(WatchedFeature(knee, 9.0, 1.0)), emptyMap())
        val problems = unbindableFaults(
            listOf(
                fault(),
                fault(id = "lumbar_thing", conditions = listOf(FaultCondition(lumbar, "+", 3.0))),
            ),
            watchList,
        )
        assertEquals(setOf("lumbar_thing"), problems.keys)
        assertTrue(problems.getValue("lumbar_thing").contains("lumbar_flexion"))
    }

    @Test
    fun `an inverted phase range is reported`() {
        val watchList = WatchList(listOf(WatchedFeature(knee, 9.0, 1.0)), emptyMap())
        val problems = unbindableFaults(listOf(fault(phaseRange = 60 to 40)), watchList)
        assertTrue(problems.values.single().contains("inverted"))
    }

    @Test
    fun `a well-formed fault set binds cleanly`() {
        val watchList = WatchList(
            listOf(WatchedFeature(knee, 9.0, 0.6), WatchedFeature(lumbar, 3.0, 0.4)),
            emptyMap(),
        )
        val problems = unbindableFaults(
            listOf(fault(), fault(id = "b", conditions = listOf(FaultCondition(lumbar, "+", 2.5)))),
            watchList,
        )
        assertTrue(problems.isEmpty())
        assertFalse(problems.containsKey("b"))
    }
}
