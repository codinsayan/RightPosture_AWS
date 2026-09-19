package com.example.rightposture.report

import com.example.rightposture.core.progression.ProgressionVerdict
import com.example.rightposture.core.progression.SessionMetrics
import com.example.rightposture.core.progression.evaluateProgression
import com.example.rightposture.data.RepEntity
import com.example.rightposture.data.SessionEntity

/**
 * §14.1 and §14.2's charts, reduced to arithmetic.
 *
 * Everything here is pure - no Android, no database, no clock - because the decisions that can be
 * *wrong* are all in this file, and the composables downstream only scale and draw. A chart that
 * lies is a clinical error, so the lying has to be testable on the JVM.
 *
 * The whole file exists to enforce one rule from §6.6: **an absent measurement is a gap, never a
 * zero.** Three separate things can be absent - an unscored rep, a rep that never happened, and a
 * session too short to judge - and all three have to reach the canvas as holes rather than as points
 * on the floor.
 */

/**
 * One point on a line, where `y == null` *is* the abstention.
 *
 * Modelling the gap in the point rather than by omitting it is deliberate: the x positions have to
 * stay meaningful whether or not a value exists there, and a caller that filters nulls out early
 * loses the ability to say "rep 3 happened and could not be scored".
 */
data class ChartPoint(val x: Float, val y: Float?)

/**
 * Splits a series into the contiguous runs that actually hold measurements.
 *
 * The single place §6.6's no-interpolation rule lives. A path drawn straight through a gap asserts
 * a value nobody recorded, and it is the most natural bug in the world to write - `points.forEach {
 * lineTo(...) }` does it silently. Every chart draws `segmentsOf(points).forEach { drawPath }` so
 * that one test pins the rule for all of them at once.
 *
 * Single-point runs are kept: a lone measurement between two gaps is still a fact, and the caller
 * draws it as a dot rather than a line.
 */
fun segmentsOf(points: List<ChartPoint>): List<List<ChartPoint>> {
    val segments = mutableListOf<List<ChartPoint>>()
    var current = mutableListOf<ChartPoint>()
    points.forEach { point ->
        if (point.y == null) {
            if (current.isNotEmpty()) {
                segments += current
                current = mutableListOf()
            }
        } else {
            current += point
        }
    }
    if (current.isNotEmpty()) segments += current
    return segments
}

/**
 * §14.2's quality-per-rep series.
 *
 * Plotted against [RepEntity.repIndex], **not** list position. `repIndex` is 1-based and counts every
 * finished rep including the rejected ones, so a set where rep 3 was thrown out has reps 1, 2, 4 -
 * and using list position would slide rep 4 into rep 3's slot and quietly relabel the data.
 *
 * A rep with no quality yields `y = null`, per §1.6. It is not scored, which is a different claim
 * from scoring zero, and the difference is the whole reason the column is nullable.
 */
fun repQualitySeries(reps: List<RepEntity>): List<ChartPoint> =
    reps.sortedBy { it.repIndex }
        .map { ChartPoint(x = it.repIndex.toFloat(), y = it.quality?.toFloat()) }

/**
 * A metric across sessions, oldest first.
 *
 * A session that cannot be judged - fewer than six valid reps, so §6.21.1's CUSUM never applied -
 * yields `y = null` rather than being dropped or floored. Dropping it would compress the x axis and
 * hide that a session happened; flooring it would claim the patient scored nothing. It is drawn as
 * a gap with a marker: present, and unjudged.
 */
fun sessionTrendSeries(
    metrics: List<SessionMetrics>,
    value: (SessionMetrics) -> Double?,
): List<ChartPoint> = metrics.mapIndexed { index, session ->
    ChartPoint(
        x = index.toFloat(),
        y = if (session.countsTowardProgression) value(session)?.toFloat() else null,
    )
}

/** Everything the analytics screen draws for one exercise. */
data class AnalyticsData(
    val exerciseId: String,
    val exerciseName: String,
    /** Oldest first. §6.21's window depends on this and quietly gives a different answer reversed. */
    val metrics: List<SessionMetrics>,
    val startedAt: List<Long>,
    /** Median quality per session, on a fixed 0..100 scale. */
    val quality: List<ChartPoint>,
    /** §6.21.1's peak range per session. No natural ceiling, so the chart scales to the data. */
    val romPeak: List<ChartPoint>,
    val verdict: ProgressionVerdict,
) {
    /** Sessions that carried enough evidence to be judged at all. */
    val judgedSessions: Int get() = metrics.count { it.countsTowardProgression }
}

/**
 * Builds one exercise's analytics from rows already read off disk.
 *
 * Takes the reps pre-grouped rather than a database, so the whole thing stays pure and the caller
 * owns the two queries. The baseline is picked exactly as `progressionFor` does - the first
 * qualifying session's ROM, per §6.21.3's `B_m` - so the verdict here and the verdict on the physio
 * home screen cannot disagree.
 *
 * `romGoal` is null because nothing captures a goal yet; §6.21.3's guard turns that into an explicit
 * "no goal set" rather than a fabricated percentage.
 */
fun analyticsOf(
    exerciseId: String,
    exerciseName: String,
    sessions: List<SessionEntity>,
    repsBySession: Map<String, List<RepEntity>>,
): AnalyticsData {
    val ordered = sessions.sortedBy { it.startedAt }
    val metrics = ordered.map { session ->
        sessionMetricsOf(session, repsBySession[session.id].orEmpty())
    }
    val baseline = metrics.firstOrNull { it.countsTowardProgression }?.romPeak

    return AnalyticsData(
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        metrics = metrics,
        startedAt = ordered.map { it.startedAt },
        quality = sessionTrendSeries(metrics) { it.qMean },
        romPeak = sessionTrendSeries(metrics) { it.romPeak },
        verdict = evaluateProgression(metrics, romBaseline = baseline, romGoal = null),
    )
}
