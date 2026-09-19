package com.example.rightposture

import com.example.rightposture.nav.NavState
import com.example.rightposture.nav.Screen
import com.example.rightposture.nav.groupByWeekOf
import com.example.rightposture.nav.millisOf
import com.example.rightposture.nav.patientStatsOf
import com.example.rightposture.nav.streakOfDays
import com.example.rightposture.sync.RemoteSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * §5.3's derivations: the numbers a physio reads off a patient's detail screen.
 *
 * These are worth a JVM test rather than a look at the device because every one of them is a
 * clinical claim. A median that silently counts an unscored set as zero, or a streak that counts a
 * day nobody exercised, is wrong in a way that looks entirely plausible on screen.
 */
class PatientDetailStatsTest {

    private fun session(
        score: Double?,
        counted: Int = 10,
        prescribed: Int = 10,
        startedAt: String? = "2026-09-05T08:00:00+00:00",
    ) = RemoteSession(
        id = "s-${score}-$startedAt-$counted",
        patientId = "p1",
        exerciseId = "ex1",
        exerciseVersion = 1,
        startedAt = startedAt,
        endedAt = "2026-09-05T08:05:00+00:00",
        prescribedReps = prescribed,
        setScore = score,
        completionRate = null,
        degradationOnsetRep = null,
        countedReps = counted,
    )

    @Test
    fun `an unscored set is left out of the median rather than counted as zero`() {
        // §1.6. Three sets in the eighties and one that could not be scored is a patient doing
        // well, not a patient whose median has been dragged to 41.
        val stats = patientStatsOf(
            listOf(session(82.0), session(86.0), session(84.0), session(null)),
        )

        assertEquals(4, stats.sets)
        assertEquals(3, stats.scored)
        assertEquals(84.0, stats.medianQuality!!, 1e-9)
    }

    @Test
    fun `no scored set at all reads as absent, not as zero`() {
        val stats = patientStatsOf(listOf(session(null), session(null)))

        assertNull(stats.medianQuality)
        assertEquals(2, stats.sets)
        assertEquals(0, stats.scored)
    }

    @Test
    fun `the median resists one interrupted set`() {
        // The reason this is a median and not a mean: one abandoned set at 20 would pull a mean
        // under the pass mark and flag a patient who is fine.
        val stats = patientStatsOf(
            listOf(session(84.0), session(86.0), session(88.0), session(20.0)),
        )

        assertEquals(85.0, stats.medianQuality!!, 1e-9)
    }

    @Test
    fun `reps are summed across the whole history`() {
        val stats = patientStatsOf(
            listOf(
                session(80.0, counted = 10, prescribed = 12),
                session(80.0, counted = 12, prescribed = 12),
            ),
        )

        assertEquals(22, stats.countedReps)
        assertEquals(24, stats.prescribedReps)
    }

    @Test
    fun `a streak counts back from today and stops at the first gap`() {
        val day = TimeUnit.DAYS.toMillis(1)
        val now = System.currentTimeMillis()

        assertEquals(3, streakOfDays(listOf(now, now - day, now - 2 * day, now - 5 * day)))
    }

    @Test
    fun `a patient who has not exercised today has no streak`() {
        val day = TimeUnit.DAYS.toMillis(1)
        val now = System.currentTimeMillis()

        assertEquals(0, streakOfDays(listOf(now - day, now - 2 * day)))
        assertEquals(0, streakOfDays(emptyList()))
    }

    @Test
    fun `an unreadable timestamp is null rather than the epoch`() {
        // Zero would sort an undated row to the top of a history claiming January 1970.
        assertNull(millisOf(null))
        assertNull(millisOf("not a date"))
        assertEquals(
            true,
            (millisOf("2026-09-05T08:00:00+00:00") ?: 0L) > 0L,
        )
    }

    @Test
    fun `undated sets get their own history heading instead of being dropped`() {
        val groups = groupByWeekOf(listOf(session(80.0), session(80.0, startedAt = null)))
        val headings = groups.map { it.first }

        assertEquals(2, groups.sumOf { it.second.size })
        assertEquals(true, "Undated" in headings)
    }

    @Test
    fun `opening a patient and going back returns to the roster`() {
        val stack = NavState.startingAt(Screen.PhysioActivity)
            .navigateTo(Screen.PhysioPatients)
            .navigateTo(Screen.PhysioPatientDetail("p1"))

        assertEquals(Screen.PhysioPatientDetail("p1"), stack.current)
        assertEquals(Screen.PhysioPatients, stack.back()!!.current)
    }
}
