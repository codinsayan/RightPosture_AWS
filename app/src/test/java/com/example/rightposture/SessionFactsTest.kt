package com.example.rightposture

import com.example.rightposture.core.summary.SummaryCheck
import com.example.rightposture.core.summary.checkSummary
import com.example.rightposture.data.RepEntity
import com.example.rightposture.data.SessionEntity
import com.example.rightposture.report.degradationSentinel
import com.example.rightposture.report.displayNameOf
import com.example.rightposture.core.summary.SmoothnessDirection
import com.example.rightposture.report.setDeltasOf
import com.example.rightposture.report.sessionMetricsOf
import com.example.rightposture.report.summaryFactsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between what was recorded and what the model is allowed to say.
 *
 * Everything above this file is arithmetic and everything below it is phrasing, so a mistake here
 * is the one place a fabricated fact could enter legitimately - by being computed wrong rather than
 * invented. The abstention cases matter most: §1.6 means an unscored set has to stay unscored all
 * the way into the prompt.
 */
class SessionFactsTest {

    private fun session(
        prescribed: Int = 10,
        onset: Int? = null,
        completion: Double? = null,
    ) = SessionEntity(
        id = "s1",
        assignmentId = null,
        patientUid = null,
        exerciseId = "ex",
        exerciseVersion = 1,
        startedAt = 0,
        endedAt = 1,
        prescribedReps = prescribed,
        setScore = null,
        completionRate = completion,
        degradationOnsetRep = onset,
        deviceFps = null,
        delegate = null,
    )

    private fun rep(index: Int, quality: Double?, valid: Boolean = true, rom: Double = 50.0) =
        RepEntity(
            sessionId = "s1",
            repIndex = index,
            quality = quality,
            romDegrees = rom,
            durationMs = 1000,
            valid = valid,
            rejection = if (valid) null else "too fast",
            smoothnessSparc = null,
            recordedAt = 0,
        )

    @Test
    fun `an unscored set reaches the prompt as unscored, not as zero`() {
        // §1.6 carried all the way through. "Median quality 0" would have the model consoling a
        // patient about a performance nobody measured.
        val facts = summaryFactsOf("Heel slide", session(), listOf(rep(1, null), rep(2, null)))
        assertNull(facts.medianQuality)
    }

    @Test
    fun `only valid reps are counted as completed`() {
        val facts = summaryFactsOf(
            "Heel slide",
            session(),
            listOf(rep(1, 80.0), rep(2, 70.0, valid = false), rep(3, 90.0)),
        )
        assertEquals(2, facts.repsCompleted)
        // The rejected rep must not drag the median either.
        assertEquals(85, facts.medianQuality)
    }

    @Test
    fun `the numbers we compute are exactly the ones the validator will allow`() {
        // The contract between the adapter and the gate. If these drift apart, a true statement
        // gets rejected and the patient silently gets the template forever.
        val facts = summaryFactsOf("Heel slide", session(onset = 5), listOf(rep(1, 71.0)))
        val allowed = facts.allowedNumbers()
        assertTrue(allowed.toString(), "71" in allowed)
        assertTrue(allowed.toString(), "5" in allowed)
        assertTrue(allowed.toString(), "10" in allowed)
        assertTrue(allowed.toString(), "1" in allowed)
    }

    @Test
    fun `a summary built from real facts passes the gate`() {
        val facts = summaryFactsOf("Heel slide", session(onset = 5), listOf(rep(1, 71.0)))
        val text = "You finished 1 of 10 reps at 71 out of 100. Form slipped at rep 5. " +
            "Next time, ease off a little earlier."
        assertTrue(
            checkSummary(text, facts).toString(),
            checkSummary(text, facts) is SummaryCheck.Accepted,
        )
    }

    // -------------------------------------------------------------------------------------
    // §6.21.1's sentinels
    // -------------------------------------------------------------------------------------

    @Test
    fun `no degradation over a full set sorts above degrading on the last rep`() {
        // The sentinel §6.21.1 spells out. `validReps + 1` is what makes "never degraded" the
        // better value in a numeric comparison, rather than a null that drops the session.
        assertEquals(11, degradationSentinel(onset = null, validReps = 10))
        assertEquals(10, degradationSentinel(onset = 10, validReps = 10))
        assertTrue(degradationSentinel(null, 10)!! > degradationSentinel(10, 10)!!)
    }

    @Test
    fun `too few reps for CUSUM is null, which excludes the session`() {
        // Not zero and not "perfect". A five-rep set is a set nobody can judge.
        assertNull(degradationSentinel(onset = null, validReps = 5))
        assertNull(degradationSentinel(onset = 3, validReps = 5))
    }

    @Test
    fun `metrics carry the sentinels into the progression rules`() {
        val short = sessionMetricsOf(session(), (1..4).map { rep(it, 90.0) })
        assertNull(short.degradationOnset)
        assertTrue(!short.countsTowardProgression)

        val full = sessionMetricsOf(session(), (1..10).map { rep(it, 90.0) })
        assertEquals(11, full.degradationOnset)
        assertTrue(full.countsTowardProgression)
    }

    @Test
    fun `completion rate falls back to reps over prescribed when the session did not record one`() {
        val metrics = sessionMetricsOf(session(prescribed = 10), (1..6).map { rep(it, 90.0) })
        assertEquals(0.6, metrics.completionRate!!, 1e-9)
    }

    @Test
    fun `an unscored session has no quality rather than a quality of zero`() {
        val metrics = sessionMetricsOf(session(), (1..10).map { rep(it, null) })
        assertNull(metrics.qMean)
        assertEquals(0, metrics.repsAtQuality)
        // §1.6: it must not count toward progression either, in either direction.
        assertTrue(!metrics.countsTowardProgression)
    }

    // -------------------------------------------------------------------------------------
    // Naming
    // -------------------------------------------------------------------------------------

    @Test
    fun `feature ids become something a patient can read`() {
        assertEquals("Knee flex", displayNameOf("knee_flex_active"))
        assertEquals("Trunk lean", displayNameOf("trunk_lean"))
        assertEquals("Hip abduction", displayNameOf("hip_abduction_passive"))
    }

    // -------------------------------------------------------------------------------------
    // 8.7: §6.18.5's deltas
    // -------------------------------------------------------------------------------------

    private fun timedRep(
        index: Int,
        rom: Double,
        durationMs: Int = 1000,
        sparc: Double? = null,
        valid: Boolean = true,
        rejection: String? = null,
    ) = RepEntity(
        sessionId = "s1",
        repIndex = index,
        quality = 80.0,
        romDegrees = rom,
        durationMs = durationMs,
        valid = valid,
        rejection = rejection,
        smoothnessSparc = sparc,
        recordedAt = 0,
    )

    @Test
    fun `deltas are absent when there is no change point`() {
        // A delta measured relative to nothing is not a finding, and this is what keeps the phrase
        // "after that point" from ever being orphaned.
        val deltas = setDeltasOf(null, listOf(timedRep(1, 90.0), timedRep(2, 40.0)))

        assertNull(deltas.romPercent)
        assertNull(deltas.tempoPercent)
        assertNull(deltas.smoothness)
    }

    @Test
    fun `the range delta uses only reps that counted`() {
        // A RANGE-rejected rep has a small romDegrees *by definition* - that is the rejection
        // criterion. Counting it as evidence would manufacture a collapse out of the criterion
        // itself and report the patient as fatiguing.
        val deltas = setDeltasOf(
            onsetRep = 3,
            reps = listOf(
                timedRep(1, 100.0),
                timedRep(2, 100.0),
                timedRep(3, 100.0),
                timedRep(4, 5.0, valid = false, rejection = "RANGE"),
            ),
        )

        assertNull("A rejected rep must not create a range change", deltas.romPercent)
    }

    @Test
    fun `a real range collapse is reported with its sign`() {
        val deltas = setDeltasOf(
            onsetRep = 3,
            reps = listOf(
                timedRep(1, 100.0),
                timedRep(2, 100.0),
                timedRep(3, 80.0),
                timedRep(4, 80.0),
            ),
        )

        assertEquals(-20, deltas.romPercent)
    }

    @Test
    fun `noise below the reporting floor is suppressed`() {
        val deltas = setDeltasOf(
            onsetRep = 3,
            reps = listOf(timedRep(1, 100.0), timedRep(2, 100.0), timedRep(3, 98.0), timedRep(4, 98.0)),
        )

        // Two percent is rep-to-rep variation, not fatigue.
        assertNull(deltas.romPercent)
    }

    @Test
    fun `a slower second half is a positive tempo delta`() {
        val deltas = setDeltasOf(
            onsetRep = 3,
            reps = listOf(
                timedRep(1, 90.0, durationMs = 1000),
                timedRep(2, 90.0, durationMs = 1000),
                timedRep(3, 90.0, durationMs = 1300),
                timedRep(4, 90.0, durationMs = 1300),
            ),
        )

        assertEquals(30, deltas.tempoPercent)
    }

    @Test
    fun `smoothness direction follows the SPARC sign`() {
        // SPARC is negative and more negative means less smooth. Getting this backwards would tell
        // a fatiguing patient their movement had improved.
        val rougher = setDeltasOf(
            onsetRep = 3,
            reps = listOf(
                timedRep(1, 90.0, sparc = -1.0),
                timedRep(2, 90.0, sparc = -1.0),
                timedRep(3, 90.0, sparc = -2.0),
                timedRep(4, 90.0, sparc = -2.0),
            ),
        )
        val smoother = setDeltasOf(
            onsetRep = 3,
            reps = listOf(
                timedRep(1, 90.0, sparc = -2.0),
                timedRep(2, 90.0, sparc = -2.0),
                timedRep(3, 90.0, sparc = -1.0),
                timedRep(4, 90.0, sparc = -1.0),
            ),
        )

        assertEquals(SmoothnessDirection.LESS_SMOOTH, rougher.smoothness)
        assertEquals(SmoothnessDirection.SMOOTHER, smoother.smoothness)
    }

    @Test
    fun `the facts carry the uncounted reps and why`() {
        val facts = summaryFactsOf(
            "Heel slide",
            session(onset = 3),
            listOf(
                rep(1, 90.0),
                rep(2, 90.0),
                rep(3, 90.0, valid = false),
                rep(4, 90.0, valid = false),
            ),
        )

        assertEquals(2, facts.repsNotCounted)
        // Phrased, not the raw stored token.
        assertEquals("too fast", facts.topRejection)
    }
}
