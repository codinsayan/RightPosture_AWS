package com.example.rightposture

import com.example.rightposture.core.progression.Progress
import com.example.rightposture.core.progression.ProgressWeights
import com.example.rightposture.core.progression.SessionMetrics
import com.example.rightposture.core.progression.currentEstimate
import com.example.rightposture.core.progression.evaluateProgression
import com.example.rightposture.core.progression.intermediateTarget
import com.example.rightposture.core.progression.overallProgress
import com.example.rightposture.core.progression.progressOf
import com.example.rightposture.core.progression.romPeakOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.21, and 8.4's gate that a flag shows its supporting data.
 *
 * The cases that matter are the guards: a goal nobody set, a session too short for CUSUM, a set
 * that scored nothing. Each of those has an obvious wrong answer that looks perfectly reasonable
 * on screen - "0% progress", "never degraded", "quality 0" - and each would put a real decision in
 * front of a clinician on a fact that was never measured.
 */
class ProgressionTest {

    private fun session(
        id: String,
        rom: Double? = 90.0,
        quality: Double? = 85.0,
        onset: Int? = 11,
        valid: Int = 10,
        prescribed: Int = 10,
        completion: Double? = 1.0,
        pain: Boolean = false,
    ) = SessionMetrics(
        sessionId = id,
        romPeak = rom,
        qMean = quality,
        repsAtQuality = valid,
        holdSeconds = null,
        degradationOnset = onset,
        completionRate = completion,
        validReps = valid,
        prescribedReps = prescribed,
        painReported = pain,
    )

    // -------------------------------------------------------------------------------------
    // §6.21.3's near-zero goal guard
    // -------------------------------------------------------------------------------------

    @Test
    fun `a goal equal to the baseline reports goal not set, never a division`() {
        // §6.21.3. Dividing here yields infinity or NaN, and whatever is rendered from that is a
        // number a physio would read as real.
        assertEquals(Progress.GoalNotSet, progressOf(current = 50.0, baseline = 40.0, goal = 40.0))
    }

    @Test
    fun `progress is clamped at both ends`() {
        // Overshooting reads as complete rather than 140%; regressing below baseline reads as zero
        // rather than negative, which would render as a bar pointing the wrong way.
        assertEquals(Progress.Known(1.0), progressOf(200.0, 0.0, 100.0))
        assertEquals(Progress.Known(0.0), progressOf(-50.0, 0.0, 100.0))
        assertEquals(Progress.Known(0.5), progressOf(50.0, 0.0, 100.0))
    }

    @Test
    fun `nothing measured is no data, not zero progress`() {
        // §1.6 at the progression layer. Zero progress is a claim about the patient; no data is a
        // claim about us.
        assertEquals(Progress.NoData, progressOf(null, 0.0, 100.0))
        assertEquals(Progress.NoData, progressOf(50.0, null, 100.0))
    }

    // -------------------------------------------------------------------------------------
    // §6.21.1's degradation sentinels
    // -------------------------------------------------------------------------------------

    @Test
    fun `a session too short for CUSUM is excluded, not scored as perfect`() {
        // The trap §6.21.1 calls out by name. Five valid reps cannot be judged, and counting them
        // as "never degraded" would let a patient who gave up early outrank one who finished.
        val short = session("short", valid = 5, onset = null)
        assertFalse(short.countsTowardProgression)

        val full = session("full", valid = 10)
        assertTrue(full.countsTowardProgression)

        // The short session must not move the median at all.
        val estimate = currentEstimate(listOf(full, short)) { it.romPeak }
        assertEquals(90.0, estimate!!, 1e-9)
    }

    @Test
    fun `an unscored session is excluded from the progression median`() {
        assertFalse(session("x", quality = null).countsTowardProgression)
    }

    // -------------------------------------------------------------------------------------
    // §6.21.2's robust estimate
    // -------------------------------------------------------------------------------------

    @Test
    fun `one heroic day does not move the curve any more than one bad day`() {
        // The point of median-of-sessions. Both of these must land on the same middle value.
        val withHeroic = listOf(session("a", rom = 60.0), session("b", rom = 62.0), session("c", rom = 120.0))
        val withBad = listOf(session("a", rom = 60.0), session("b", rom = 62.0), session("c", rom = 10.0))
        assertEquals(62.0, currentEstimate(withHeroic) { it.romPeak }!!, 1e-9)
        assertEquals(60.0, currentEstimate(withBad) { it.romPeak }!!, 1e-9)
    }

    @Test
    fun `only the last three sessions count`() {
        val sessions = (1..6).map { session("s$it", rom = it * 10.0) }
        // 40, 50, 60 -> 50
        assertEquals(50.0, currentEstimate(sessions) { it.romPeak }!!, 1e-9)
    }

    @Test
    fun `ROM peak is the median of the top three, not the single best`() {
        // One overshoot, or one landmark glitch that inflated an angle, must not define the range.
        assertEquals(50.0, romPeakOf(listOf(10.0, 20.0, 40.0, 50.0, 200.0))!!, 1e-9)
        assertEquals(20.0, romPeakOf(listOf(20.0))!!, 1e-9)
    }

    // -------------------------------------------------------------------------------------
    // §6.21.5's advancement rule
    // -------------------------------------------------------------------------------------

    @Test
    fun `two of three qualifying sessions advance, one does not`() {
        // The exact boundary §6.21.5 specifies.
        val good = session("g", rom = 100.0, quality = 85.0, onset = 11)
        val poor = session("p", rom = 20.0, quality = 40.0, onset = 11)

        val twoGood = evaluateProgression(listOf(poor, good, good), romBaseline = 0.0, romGoal = 100.0)
        assertTrue(twoGood.reasons.toString(), twoGood.readyToProgress)

        val oneGood = evaluateProgression(listOf(poor, poor, good), romBaseline = 0.0, romGoal = 100.0)
        assertFalse(oneGood.readyToProgress)
    }

    @Test
    fun `hitting the range but fatiguing early is not ready`() {
        // §6.21.5's last clause, and the reason it is there: full range on reps 1-3 and collapse at
        // rep 4 of 10 is not a patient ready for more.
        val fatigued = session("f", rom = 100.0, quality = 85.0, onset = 4, prescribed = 10)
        val verdict = evaluateProgression(
            listOf(fatigued, fatigued, fatigued),
            romBaseline = 0.0,
            romGoal = 100.0,
        )
        assertFalse(verdict.readyToProgress)
        assertTrue(verdict.reasons.toString(), verdict.reasons.any { "slipping" in it })
    }

    @Test
    fun `a flag always carries the data behind it`() {
        // 8.4's gate, stated as a test: a physio being asked to advance a patient has to be able to
        // see why without opening the database.
        val good = session("g", rom = 100.0, quality = 85.0, onset = 11)
        val ready = evaluateProgression(listOf(good, good, good), 0.0, 100.0)
        assertTrue(ready.readyToProgress)
        assertTrue(ready.reasons.isNotEmpty())

        val notReady = evaluateProgression(listOf(session("p", rom = 5.0, quality = 30.0)), 0.0, 100.0)
        assertTrue(notReady.reasons.isNotEmpty())
    }

    @Test
    fun `no session with enough reps is not readiness`() {
        val verdict = evaluateProgression(
            listOf(session("s", valid = 3, onset = null), session("t", valid = 2, onset = null)),
            0.0,
            100.0,
        )
        assertFalse(verdict.readyToProgress)
        assertEquals(0, verdict.sessionsConsidered)
        assertTrue(verdict.reasons.isNotEmpty())
    }

    @Test
    fun `readiness never advances anything by itself`() {
        // §6.21.5 is explicit: notify, never auto-advance. The verdict is a flag and a list of
        // reasons - there is deliberately no method here that changes a prescription.
        val good = session("g", rom = 100.0, quality = 85.0, onset = 11)
        val verdict = evaluateProgression(listOf(good, good, good), 0.0, 100.0)
        // Compiler-generated fields are filtered out; what is being asserted is that the verdict
        // carries a flag and its evidence and nothing that could act on either.
        val fields = verdict::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
        assertEquals(
            listOf("readyToProgress", "needsReview", "reasons", "sessionsConsidered").sorted(),
            fields.sorted(),
        )
    }

    // -------------------------------------------------------------------------------------
    // §6.21.6's regression rule
    // -------------------------------------------------------------------------------------

    @Test
    fun `two consecutive low-quality sessions flag review, one does not`() {
        val bad = session("b", quality = 40.0)
        val ok = session("o", quality = 85.0)
        assertTrue(evaluateProgression(listOf(ok, bad, bad), 0.0, 100.0).needsReview)
        assertFalse(evaluateProgression(listOf(bad, ok, ok), 0.0, 100.0).needsReview)
    }

    @Test
    fun `two consecutive half-finished sessions flag review`() {
        val short = session("s", completion = 0.3, quality = 85.0)
        assertTrue(evaluateProgression(listOf(short, short), 0.0, 100.0).needsReview)
    }

    @Test
    fun `reported pain flags review on its own`() {
        // Reported, never inferred. The app has no way to detect pain and must not pretend it does.
        assertTrue(evaluateProgression(listOf(session("p", pain = true)), 0.0, 100.0).needsReview)
    }

    @Test
    fun `an unscored session breaks a bad run rather than extending it`() {
        // §1.6 again. "We could not measure this" is not evidence of getting worse, and treating it
        // as such would flag patients for review because their phone was in a dark room.
        val bad = session("b", quality = 40.0)
        val unscored = session("u", quality = null)
        assertFalse(evaluateProgression(listOf(bad, unscored), 0.0, 100.0).needsReview)
    }

    // -------------------------------------------------------------------------------------
    // §6.21.3's overall progress and §6.21.4's interpolation
    // -------------------------------------------------------------------------------------

    @Test
    fun `overall progress drops unknown components instead of scoring them zero`() {
        // A physio who has not set a quality goal must not make their patient look like they are
        // going backwards.
        val overall = overallProgress(
            rom = Progress.Known(1.0),
            quality = Progress.GoalNotSet,
            repsAtQuality = Progress.NoData,
        )
        assertEquals(Progress.Known(1.0), overall)
    }

    @Test
    fun `overall progress weights the components it does have`() {
        val overall = overallProgress(
            rom = Progress.Known(1.0),
            quality = Progress.Known(0.0),
            repsAtQuality = Progress.NoData,
            weights = ProgressWeights(rom = 0.5, quality = 0.3, repsAtQuality = 0.2),
        )
        // 0.5 and 0.3 renormalise to 0.625 and 0.375.
        assertEquals(0.625, (overall as Progress.Known).fraction, 1e-9)
    }

    @Test
    fun `nothing known at all is no data`() {
        assertEquals(
            Progress.NoData,
            overallProgress(Progress.NoData, Progress.GoalNotSet, Progress.NoData),
        )
    }

    @Test
    fun `the intermediate target interpolates and stays inside the endpoints`() {
        // §6.21.4. Ten lines, not a model - and the endpoints have to be exact, because at progress
        // 0 the patient should be chasing the baseline they can already reach.
        val baseline = listOf(0f, 10f)
        val goal = listOf(100f, 110f)
        assertEquals(baseline, intermediateTarget(baseline, goal, 0.0))
        assertEquals(goal, intermediateTarget(baseline, goal, 1.0))
        assertEquals(listOf(50f, 60f), intermediateTarget(baseline, goal, 0.5))
        // Progress out of range cannot push the target past the goal.
        assertEquals(goal, intermediateTarget(baseline, goal, 5.0))
    }
}

/**
 * The no-goal case, separated out because it is the state every exercise is in until a
 * goal-setting screen exists - which is to say, all of them today.
 */
class ProgressionNoGoalTest {

    private fun session(id: String, quality: Double? = 85.0) = SessionMetrics(
        sessionId = id,
        romPeak = 90.0,
        qMean = quality,
        repsAtQuality = 10,
        holdSeconds = null,
        degradationOnset = 11,
        completionRate = 1.0,
        validReps = 10,
        prescribedReps = 10,
    )

    @Test
    fun `no goal says so instead of reporting a failed range`() {
        // "Range met in 0 of 3" against a goal nobody set describes a failing patient, which is the
        // opposite of the truth. §6.21.3's guard exists so a missing goal never becomes a number.
        val verdict = evaluateProgression(
            listOf(session("a"), session("b"), session("c")),
            romBaseline = 90.0,
            romGoal = null,
        )
        assertFalse(verdict.readyToProgress)
        assertTrue(verdict.reasons.toString(), verdict.reasons.any { "No range goal set" in it })
        assertFalse(verdict.reasons.toString(), verdict.reasons.any { "0 of" in it })
    }

    @Test
    fun `review flags still work without any goal`() {
        // §6.21.6 needs no goal at all, so it must keep working while §6.21.5 cannot answer.
        val verdict = evaluateProgression(
            listOf(session("a", quality = 30.0), session("b", quality = 20.0)),
            romBaseline = null,
            romGoal = null,
        )
        assertTrue(verdict.needsReview)
        assertTrue(verdict.reasons.toString(), verdict.reasons.any { "Quality below" in it })
    }
}
