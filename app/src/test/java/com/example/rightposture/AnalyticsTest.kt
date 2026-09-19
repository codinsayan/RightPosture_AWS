package com.example.rightposture

import com.example.rightposture.data.FeatureSampleEntity
import com.example.rightposture.data.RepEntity
import com.example.rightposture.data.SessionEntity
import com.example.rightposture.report.ChartPoint
import com.example.rightposture.report.analyticsOf
import com.example.rightposture.report.jointShiftsOf
import com.example.rightposture.report.repQualitySeries
import com.example.rightposture.report.segmentsOf
import com.example.rightposture.report.sessionTrendSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind §14.1 and §14.2's charts.
 *
 * Every test here is really the same test asked six ways: **an absent measurement must not become a
 * zero.** That is §6.6's rule, and a chart is where breaking it does the most damage, because a line
 * touching the floor is read as a bad performance rather than as a missing one and nothing on screen
 * says which it was.
 */
class AnalyticsTest {

    private fun session(id: String, startedAt: Long, prescribed: Int = 10) = SessionEntity(
        id = id,
        assignmentId = null,
        patientUid = null,
        exerciseId = "ex",
        exerciseVersion = 1,
        startedAt = startedAt,
        endedAt = startedAt + 1,
        prescribedReps = prescribed,
        setScore = null,
        completionRate = null,
        degradationOnsetRep = null,
        deviceFps = null,
        delegate = null,
    )

    private fun rep(
        sessionId: String,
        index: Int,
        quality: Double?,
        valid: Boolean = true,
        rom: Double = 50.0,
    ) = RepEntity(
        sessionId = sessionId,
        repIndex = index,
        quality = quality,
        romDegrees = rom,
        durationMs = 1000,
        valid = valid,
        rejection = if (valid) null else "RANGE",
        smoothnessSparc = null,
        recordedAt = 0,
    )

    /** A session with enough valid reps to clear §6.21.1's six-rep bar. */
    private fun goodReps(sessionId: String, quality: Double) =
        (1..8).map { rep(sessionId, it, quality) }

    @Test
    fun `an unscored rep is a gap, not a zero point`() {
        val series = repQualitySeries(listOf(rep("s", 1, 90.0), rep("s", 2, null), rep("s", 3, 80.0)))

        assertNull("The unscored rep must carry no value at all", series[1].y)
        // The distinction the whole file exists for: nothing here may read as a score of zero.
        assertTrue(series.none { it.y == 0f })
    }

    @Test
    fun `the curve is plotted against rep index, so a rejected rep leaves a hole in x`() {
        // Reps 1, 2 and 4 finished; rep 3 was thrown out and never reached the table.
        val series = repQualitySeries(
            listOf(rep("s", 1, 90.0), rep("s", 2, 88.0), rep("s", 4, 70.0)),
        )

        // Plotted by list position this would read 0, 1, 2 and slide rep 4 into rep 3's slot,
        // quietly relabelling which rep the drop belongs to.
        assertEquals(listOf(1f, 2f, 4f), series.map { it.x })
    }

    @Test
    fun `segmentsOf never joins across a gap`() {
        val points = listOf(
            ChartPoint(1f, 90f),
            ChartPoint(2f, 88f),
            ChartPoint(3f, null),
            ChartPoint(4f, 70f),
        )

        val segments = segmentsOf(points)

        assertEquals(2, segments.size)
        // The two runs must not meet: a path drawn through the gap asserts a value for rep 3.
        assertEquals(2f, segments[0].last().x, 1e-6f)
        assertEquals(4f, segments[1].first().x, 1e-6f)
    }

    @Test
    fun `a lone measurement between two gaps survives as its own segment`() {
        val points = listOf(ChartPoint(1f, null), ChartPoint(2f, 88f), ChartPoint(3f, null))

        val segments = segmentsOf(points)

        // Still a fact, even though it cannot be drawn as a line.
        assertEquals(1, segments.size)
        assertEquals(1, segments[0].size)
    }

    @Test
    fun `a session that cannot be judged breaks the trend rather than dropping to zero`() {
        val short = session("short", 0)
        val full = session("full", 1)
        val data = analyticsOf(
            exerciseId = "ex",
            exerciseName = "Heel slide",
            sessions = listOf(short, full),
            repsBySession = mapOf(
                // Four valid reps is below §6.21.1's six, so CUSUM never applied and the session
                // is not evidence either way.
                "short" to (1..4).map { rep("short", it, 90.0) },
                "full" to goodReps("full", 85.0),
            ),
        )

        assertNull("An unjudgeable session must not be scored", data.quality[0].y)
        assertEquals(85f, data.quality[1].y!!, 1e-3f)
        assertEquals(1, data.judgedSessions)
    }

    @Test
    fun `an excluded session keeps its place on the axis rather than vanishing`() {
        val data = analyticsOf(
            "ex",
            "Heel slide",
            listOf(session("a", 0), session("b", 1), session("c", 2)),
            mapOf(
                "a" to goodReps("a", 80.0),
                "b" to (1..3).map { rep("b", it, 90.0) },
                "c" to goodReps("c", 86.0),
            ),
        )

        // Three sessions happened, so three x positions. Dropping the middle one would compress the
        // axis and hide that the patient turned up.
        assertEquals(3, data.quality.size)
        assertEquals(listOf(0f, 1f, 2f), data.quality.map { it.x })
        assertNull(data.quality[1].y)
    }

    @Test
    fun `a single scored session still produces a drawable point`() {
        val data = analyticsOf("ex", "Heel slide", listOf(session("a", 0)), mapOf("a" to goodReps("a", 77.0)))

        assertEquals(1, data.quality.size)
        assertEquals(77f, data.quality[0].y!!, 1e-3f)
    }

    @Test
    fun `an exercise with no sessions produces empty series rather than throwing`() {
        val data = analyticsOf("ex", "Heel slide", emptyList(), emptyMap())

        assertTrue(data.quality.isEmpty())
        assertTrue(data.romPeak.isEmpty())
        assertEquals(0, data.judgedSessions)
    }

    @Test
    fun `analyticsOf orders oldest first, because the progression window depends on it`() {
        // Handed newest-first, the way recentSessions returns them.
        val data = analyticsOf(
            "ex",
            "Heel slide",
            listOf(session("late", 500), session("early", 100)),
            mapOf("early" to goodReps("early", 60.0), "late" to goodReps("late", 90.0)),
        )

        // §6.21.2's window is the *last* three and §6.21.6 counts consecutive sessions, so a
        // reversed list silently produces a different verdict from the same data.
        assertEquals(listOf(100L, 500L), data.startedAt)
        assertEquals(60f, data.quality[0].y!!, 1e-3f)
    }

    @Test
    fun `a metric that is absent is a gap even when the session was judged`() {
        // countsTowardProgression is true, but the metric itself is null.
        val data = analyticsOf("ex", "Heel slide", listOf(session("a", 0)), mapOf("a" to goodReps("a", 80.0)))
        val alwaysAbsent = sessionTrendSeries(data.metrics) { null }

        assertNull(alwaysAbsent[0].y)
    }

    private fun sample(rep: Int, feature: String, z: Double, frames: Int = 30) =
        FeatureSampleEntity(
            sessionId = "s1",
            repIndex = rep,
            feature = feature,
            meanZ = kotlin.math.abs(z),
            maxZ = kotlin.math.abs(z) + 1,
            meanSignedZ = z,
            frames = frames,
        )

    @Test
    fun `a joint shift keeps the magnitude the prompt discards`() {
        val shifts = jointShiftsOf(
            onsetRep = 3,
            samples = listOf(
                sample(1, "knee_flex_active", 0.0),
                sample(2, "knee_flex_active", 0.0),
                sample(3, "knee_flex_active", 2.0),
                sample(4, "knee_flex_active", 2.0),
            ),
        )

        assertEquals(1, shifts.size)
        assertEquals(2.0, shifts[0].shiftZ, 1e-6)
        assertEquals("Knee flex", shifts[0].displayName)
    }

    @Test
    fun `a joint measured on too few frames is not ranked first`() {
        val shifts = jointShiftsOf(
            onsetRep = 3,
            samples = listOf(
                // A huge shift resting on three frames either side.
                sample(1, "ankle_flex_active", 0.0, frames = 1),
                sample(2, "ankle_flex_active", 0.0, frames = 2),
                sample(3, "ankle_flex_active", 9.0, frames = 2),
                sample(4, "ankle_flex_active", 9.0, frames = 1),
                // A smaller shift the camera actually saw.
                sample(1, "knee_flex_active", 0.0, frames = 40),
                sample(2, "knee_flex_active", 0.0, frames = 40),
                sample(3, "knee_flex_active", 1.5, frames = 40),
                sample(4, "knee_flex_active", 1.5, frames = 40),
            ),
        )

        // A mean over 3 frames is not a mean over 90, and the noisier row must not lead purely by
        // being noisier.
        assertEquals("Knee flex", shifts[0].displayName)
        assertTrue(shifts[0].trustworthy)
        assertFalse(shifts[1].trustworthy)
    }

    @Test
    fun `no change point means no attribution to report`() {
        val shifts = jointShiftsOf(onsetRep = null, samples = listOf(sample(1, "knee_flex_active", 3.0)))

        // A shift measured relative to nothing is not a finding.
        assertTrue(shifts.isEmpty())
    }
}
