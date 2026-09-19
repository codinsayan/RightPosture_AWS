package com.example.rightposture

import com.example.rightposture.core.Params
import com.example.rightposture.core.live.CompletedRep
import com.example.rightposture.core.live.PhaseConfig
import com.example.rightposture.core.live.PhaseState
import com.example.rightposture.core.live.PhaseTracker
import com.example.rightposture.core.live.PhaseUpdate
import com.example.rightposture.core.live.RepRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * §6.12. The synthetic traces here are a seated knee extension: 92.4° at rest, 171.8° at full
 * extension, reference period 3 s.
 */
class PhaseTrackerTest {

    private val start = 92.4
    private val peak = 171.8
    private val span = peak - start
    private val referencePeriod = 3.0
    private val fps = 30.0

    private fun tracker(
        thetaStart: Double = start,
        thetaPeak: Double = peak,
        period: Double = referencePeriod,
    ) = PhaseTracker(PhaseConfig(thetaStart, thetaPeak, period))

    /** Feeds frames at a fixed rate and collects what came back. */
    private class Feeder(private val tracker: PhaseTracker, private val fps: Double) {
        private var timestampNs = 0L
        val reps = mutableListOf<CompletedRep>()
        val updates = mutableListOf<PhaseUpdate>()
        var last: PhaseUpdate? = null
            private set

        fun push(value: Float?) {
            val update = tracker.update(value, timestampNs)
            last = update
            updates += update
            update.completedRep?.let { reps += it }
            timestampNs += (1_000_000_000.0 / fps).toLong()
        }

        fun pushAll(values: List<Float?>) = values.forEach(::push)
    }

    private fun feeder(tracker: PhaseTracker = tracker()) = Feeder(tracker, fps)

    /** One smooth out-and-back rep. `amplitude` 1.0 reaches the reference peak exactly. */
    private fun rep(durationSeconds: Double = referencePeriod, amplitude: Double = 1.0): List<Float?> {
        val frames = (durationSeconds * fps).toInt()
        return (0 until frames).map {
            val u = it.toDouble() / frames
            (start + span * amplitude * (0.5 - 0.5 * cos(2 * PI * u))).toFloat()
        }
    }

    /** Standing still at the start position, long enough for the velocity EMA to settle. */
    private fun rest(seconds: Double = 0.8): List<Float?> =
        List((seconds * fps).toInt()) { start.toFloat() }

    private fun set(reps: Int, durationSeconds: Double = referencePeriod, amplitude: Double = 1.0) =
        rest() + (0 until reps).flatMap { rep(durationSeconds, amplitude) + rest() }

    // ---------------------------------------------------------------------------------------
    // Counting
    // ---------------------------------------------------------------------------------------

    @Test
    fun `twenty clean reps count as exactly twenty`() {
        val feeder = feeder()
        feeder.pushAll(set(20))
        assertEquals(20, feeder.reps.size)
        assertTrue(feeder.reps.all { it.valid })
    }

    @Test
    fun `a slow rep still counts`() {
        val feeder = feeder()
        feeder.pushAll(set(1, durationSeconds = 2.5 * referencePeriod))
        assertEquals(1, feeder.reps.size)
        assertTrue(feeder.reps.single().valid)
    }

    @Test
    fun `a fast rep still counts`() {
        val feeder = feeder()
        feeder.pushAll(set(1, durationSeconds = 0.7 * referencePeriod))
        assertEquals(1, feeder.reps.size)
        assertTrue(feeder.reps.single().valid)
    }

    @Test
    fun `a pause at the top holds phase instead of letting it drift`() {
        val hold = List(30) { peak.toFloat() }
        val values = rest() + rep().take(45) + hold + rep().drop(45) + rest()
        val feeder = feeder()
        feeder.pushAll(values)

        val holding = feeder.updates.filter { it.state == PhaseState.BOTTOM_HOLD }
        assertTrue("expected to enter BOTTOM_HOLD", holding.isNotEmpty())
        // §6.12.5: p is frozen at its entry value for the whole hold.
        assertEquals(1, holding.map { it.phase }.distinct().size)
        assertEquals(1, feeder.reps.size)
        assertTrue(feeder.reps.single().valid)
    }

    // ---------------------------------------------------------------------------------------
    // Chatter - the failure §6.12.3 exists to prevent
    // ---------------------------------------------------------------------------------------

    @Test
    fun `mid-rep wobble does not split one rep into three`() {
        val frames = (referencePeriod * fps).toInt()
        val wobbly = (0 until frames).map {
            val u = it.toDouble() / frames
            val base = start + span * (0.5 - 0.5 * cos(2 * PI * u))
            (base + 2.0 * sin(2 * PI * 4.0 * u * referencePeriod)).toFloat()
        }

        // Confirm the trace really does reverse repeatedly, or the test proves nothing.
        val maxima = (1 until wobbly.size - 1).count {
            wobbly[it] > wobbly[it - 1] && wobbly[it] > wobbly[it + 1]
        }
        assertTrue("trace needs several local maxima, had $maxima", maxima >= 3)

        val feeder = feeder()
        feeder.pushAll(rest() + wobbly + rest())
        assertEquals(1, feeder.reps.size)
    }

    // ---------------------------------------------------------------------------------------
    // Partial reps (§6.12.4)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a short rep is reported as partial rather than as a good rep`() {
        val feeder = feeder()
        feeder.pushAll(set(1, amplitude = 0.68))
        val rep = feeder.reps.single()
        assertFalse(rep.valid)
        assertTrue(rep.phiMax < Params.REP_ACCEPTANCE_PHASE_MAX)
        assertEquals(RepRejection.RANGE, rep.rejection)
    }

    /**
     * Pins a gap in §6.12 rather than papering over it. DESCEND leaves for ASCEND only once
     * `φ > 0.60`, so a rep that never passes 60% of reference ROM cannot reach rep-complete at all,
     * and §6.12.4's partial-rep message never fires for it. The patient who most needs "go all the
     * way down" is the one least likely to hear it.
     *
     * Left as the plan specifies. Fixing it means either lowering the ASCEND guard - which reopens
     * the turnaround chatter the guard exists to stop - or adding a separate abandoned-rep path,
     * and neither belongs in 4.1 without an owner decision.
     */
    @Test
    fun `a rep that never passes 60 percent is not reported at all`() {
        val feeder = feeder()
        feeder.pushAll(set(1, amplitude = 0.5))
        assertTrue(feeder.reps.isEmpty())
        assertEquals(PhaseState.DESCEND, feeder.last!!.state)
    }

    @Test
    fun `a rep at the acceptance boundary is judged on phi max alone`() {
        val below = feeder().also { it.pushAll(set(1, amplitude = 0.70)) }.reps.single()
        val above = feeder().also { it.pushAll(set(1, amplitude = 0.80)) }.reps.single()
        assertFalse(below.valid)
        assertTrue(above.valid)
    }

    @Test
    fun `a rep faster than the duration bound is partial`() {
        val feeder = feeder()
        feeder.pushAll(set(1, durationSeconds = 0.3 * referencePeriod))
        val rep = feeder.reps.single()
        assertFalse(rep.valid)
        assertEquals(RepRejection.TOO_FAST, rep.rejection)
    }

    @Test
    fun `a rep slower than the duration bound is partial`() {
        val feeder = feeder()
        feeder.pushAll(set(1, durationSeconds = 3.5 * referencePeriod))
        val rep = feeder.reps.single()
        assertFalse(rep.valid)
        assertEquals(RepRejection.TOO_SLOW, rep.rejection)
    }

    // ---------------------------------------------------------------------------------------
    // Abstention (1.6) and the person leaving frame
    // ---------------------------------------------------------------------------------------

    @Test
    fun `losing the patient mid-rep drops the rep instead of completing it`() {
        val values = rest() + rep().take(45) + List(60) { null } + rest()
        val feeder = feeder()
        feeder.pushAll(values)
        assertTrue("no rep should complete", feeder.reps.isEmpty())
        assertEquals(PhaseState.IDLE, feeder.last!!.state)
    }

    @Test
    fun `a brief dropout at the top does not lose the rep`() {
        val full = rep()
        val gapped = full.mapIndexed { index, value ->
            if (index in 42..56) null else value
        }
        val feeder = feeder()
        feeder.pushAll(rest() + gapped + rest())
        val rep = feeder.reps.single()
        assertTrue(rep.valid)
        assertTrue(rep.validFraction < 1.0)
        assertTrue(rep.validFraction >= 1.0 - Params.REP_DOMINANT_INVALID_MAX_FRACTION)
    }

    @Test
    fun `a hold held past the abandon timeout returns to idle with no rep`() {
        val stuck = List((25.0 * fps).toInt()) { peak.toFloat() }
        val feeder = feeder()
        feeder.pushAll(rest() + rep().take(45) + stuck)
        assertTrue(feeder.reps.isEmpty())
        assertEquals(PhaseState.IDLE, feeder.last!!.state)
    }

    // ---------------------------------------------------------------------------------------
    // Phase arithmetic (§6.12.2)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `p runs 0 to 50 going out and 50 to 100 coming back`() {
        val feeder = feeder()
        feeder.pushAll(rest() + rep() + rest())
        val descending = feeder.updates.filter { it.state == PhaseState.DESCEND }
        val ascending = feeder.updates.filter { it.state == PhaseState.ASCEND }
        assertTrue(descending.all { it.phase in 0.0..50.0 })
        assertTrue(ascending.all { it.phase in 50.0..100.0 })
        assertTrue("phase should reach the turnaround", descending.maxOf { it.phase } > 49.0)
    }

    @Test
    fun `overshoot past the reference peak stays observable`() {
        val feeder = feeder()
        feeder.pushAll(rest() + rep(amplitude = 1.4) + rest())
        val phiMax = feeder.updates.maxOf { it.phi }
        assertTrue("overshoot must be visible above 1", phiMax > 1.0)
        assertEquals(Params.PHASE_OVERSHOOT_MAX, phiMax, 1e-9)
    }

    // ---------------------------------------------------------------------------------------
    // Degenerate input
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an out-of-order frame is ignored rather than producing a negative dt`() {
        val tracker = tracker()
        val first = tracker.update(120f, 1_000_000_000L)
        val stale = tracker.update(160f, 999_000_000L)
        assertEquals(first.state, stale.state)
        assertEquals(first.phi, stale.phi, 1e-9)
    }

    @Test
    fun `a reference whose dominant joint does not move produces no reps`() {
        val feeder = feeder(tracker(thetaStart = 100.0, thetaPeak = 100.0))
        feeder.pushAll(set(3))
        assertTrue(feeder.reps.isEmpty())
        assertEquals(PhaseState.IDLE, feeder.last!!.state)
    }

    @Test
    fun `reset clears the rep in progress`() {
        val tracker = tracker()
        val feeder = Feeder(tracker, fps)
        feeder.pushAll(rest() + rep().take(45))
        assertEquals(PhaseState.DESCEND, feeder.last!!.state)
        tracker.reset()
        assertEquals(PhaseState.IDLE, tracker.currentState)
    }

    @Test
    fun `a completed rep is reported on exactly one frame`() {
        val feeder = feeder()
        feeder.pushAll(set(5))
        assertEquals(5, feeder.updates.count { it.completedRep != null })
        assertNull(feeder.reps.first().rejection)
    }
}
