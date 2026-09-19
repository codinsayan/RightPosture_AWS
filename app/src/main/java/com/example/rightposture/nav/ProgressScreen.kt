package com.example.rightposture.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.rightposture.core.progression.QUALITY_BAR
import com.example.rightposture.core.summary.ProgressFacts
import com.example.rightposture.core.summary.QualityTrend
import com.example.rightposture.core.summary.SummaryAudience
import com.example.rightposture.llm.LlmBackend
import com.example.rightposture.llm.OnDeviceLlm
import kotlin.math.roundToInt
import com.example.rightposture.report.AnalyticsData
import com.example.rightposture.ui.components.EmptyState
import com.example.rightposture.ui.components.PatientTabs
import com.example.rightposture.ui.components.Pill
import com.example.rightposture.ui.components.RoleTabBar
import com.example.rightposture.ui.components.RpCard
import com.example.rightposture.ui.components.RpTopBar
import com.example.rightposture.ui.components.SectionHeader
import com.example.rightposture.ui.components.SegmentedTabs
import com.example.rightposture.ui.components.TrendChartCard
import com.example.rightposture.ui.theme.Radius
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One exercise the patient has actually performed, with how much history there is behind it. */
data class AnalyticsEntry(val exerciseId: String, val name: String, val sessions: Int)

/**
 * §4.4's "Progress" - the screen that answers "am I getting better?".
 *
 * Four tabs rather than one scroll, because the question has four different answers and a patient
 * checking in for thirty seconds wants the first one. Overview is the headline; Quality, Range and
 * History are the working.
 *
 * Progress is always **per exercise**, never averaged across them. A knee extension and a wall sit
 * are measured by different things, and a single number over both would be meaningless in a way
 * that is hard to see and easy to act on. The chip row above the tabs is how the exercise is chosen,
 * and switching keeps the current tab - if you are reading Range for one exercise you almost
 * certainly want Range for the next.
 */
@Composable
fun ProgressScreen(
    entries: List<AnalyticsEntry>,
    selected: AnalyticsData?,
    loading: Boolean,
    llm: OnDeviceLlm?,
    backend: LlmBackend?,
    onPick: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenToday: () -> Unit,
    onOpenMe: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = Tokens.CanvasBg,
        bottomBar = {
            RoleTabBar(
                destinations = PatientTabs,
                selectedIndex = 1,
                onSelect = { index ->
                    when (index) {
                        0 -> onOpenToday()
                        2 -> onOpenMe()
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            RpTopBar(subtitle = "Clinical Form Track")

            Column(
                modifier = Modifier.padding(horizontal = Space.Gutter),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Your progress", style = Type.HeadlineLg, color = Tokens.TextPrimary)
                    Text(
                        "Per exercise, because they are not the same movement",
                        style = Type.BodyMd,
                        color = Tokens.TextSecondary,
                    )
                }

                when {
                    // Before any set exists the four tabs would all be empty, which looks broken
                    // rather than new. One card replaces the lot, and it names the next action.
                    entries.isEmpty() && !loading -> EmptyState(
                        icon = Icons.Filled.Info,
                        message = "No sets recorded yet. Finish an exercise and your progress " +
                            "appears here - one chart per exercise, from your own sets.",
                        actionLabel = "Go to today's plan",
                        onAction = onOpenToday,
                    )

                    else -> {
                        if (entries.size > 1) {
                            ExerciseChips(entries, selected?.exerciseId, onPick)
                        }

                        SegmentedTabs(
                            labels = listOf("Overview", "Quality", "Range", "History"),
                            selectedIndex = tab,
                            onSelect = { tab = it },
                        )

                        when {
                            selected != null -> when (tab) {
                                0 -> OverviewTab(selected, llm, backend)
                                1 -> QualityTab(selected, onOpenSession)
                                2 -> RangeTab(selected)
                                else -> HistoryTab(selected, onOpenSession)
                            }
                            loading -> Text(
                                "Reading your sets…",
                                style = Type.BodyMd,
                                color = Tokens.TextSecondary,
                            )
                        }
                    }
                }

                Box(modifier = Modifier.height(Space.xs))
            }
        }
    }
}

@Composable
private fun ExerciseChips(
    entries: List<AnalyticsEntry>,
    selectedId: String?,
    onPick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        entries.forEach { entry ->
            val on = entry.exerciseId == selectedId
            Surface(
                onClick = { onPick(entry.exerciseId) },
                shape = Radius.Pill,
                color = if (on) Tokens.PrimaryContainer else Tokens.SurfaceCard,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.name,
                        style = Type.TitleSm,
                        color = if (on) Tokens.OnPrimary else Tokens.TextPrimary,
                    )
                    Text(
                        "${entry.sessions}",
                        style = Type.LabelMonoSm,
                        color = if (on) Tokens.OnPrimary else Tokens.TextSecondary,
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- Overview

@Composable
private fun OverviewTab(data: AnalyticsData, llm: OnDeviceLlm?, backend: LlmBackend?) {
    val stats = remember(data) { progressStatsOf(data) }
    val facts = remember(data, stats) { progressFactsOf(data, stats) }
    val summary by rememberProgressSummary(facts, llm)

    // Above the tiles rather than below them. The paragraph is the answer to "am I getting
    // better?"; the tiles are the working. A patient checking in for thirty seconds reads the
    // first thing on the screen and nothing else, so the first thing has to be the answer.
    GeneratedSummaryCard(
        title = "How it is going",
        state = summary,
        backend = backend,
        writingLabel = "Writing your summary on this phone…",
    )

    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        StatTile(
            label = "Sets this week",
            value = "${stats.setsThisWeek}",
            note = if (stats.unjudgedThisWeek > 0) {
                "${stats.unjudgedThisWeek} not scoreable"
            } else {
                "all scoreable"
            },
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "Median quality",
            value = stats.medianQuality?.let { "%.0f".format(it) } ?: "—",
            unit = stats.medianQuality?.let { "/ 100" },
            note = when {
                stats.medianQuality == null -> "nothing scored yet"
                stats.medianQuality >= QUALITY_BAR -> "at or above the pass mark"
                else -> "below the ${QUALITY_BAR.toInt()} pass mark"
            },
            noteInk = when {
                stats.medianQuality == null -> Tokens.AbsentInk
                stats.medianQuality >= QUALITY_BAR -> Tokens.SuccessInk
                else -> Tokens.WarningInk
            },
            valueInk = if (stats.medianQuality == null) Tokens.AbsentInk else Tokens.TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        StatTile(
            label = "Best range",
            value = stats.bestRange?.let { "%.0f°".format(it) } ?: "—",
            note = stats.rangeGain?.let { "%+.0f° since your first set".format(it) }
                ?: "not measured yet",
            noteInk = if ((stats.rangeGain ?: 0.0) > 0) Tokens.SuccessInk else Tokens.TextSecondary,
            valueInk = if (stats.bestRange == null) Tokens.AbsentInk else Tokens.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "Current streak",
            value = "${stats.streakDays}",
            unit = if (stats.streakDays == 1) "day" else "days",
            note = if (stats.streakDays == 0) "no set yet today" else "days in a row",
            modifier = Modifier.weight(1f),
        )
    }

    TrendChartCard(
        title = "Recent quality",
        note = "Pass ≥ ${QUALITY_BAR.toInt()}",
        caption = "One point per set. Hollow marks are sets with too little to judge.",
        points = data.quality,
        xLabels = emptyList(),
        fixedRange = 0f..100f,
        target = QUALITY_BAR.toFloat(),
        trace = Tokens.Secondary,
        description = "Quality per set. ${data.judgedSessions} of ${data.metrics.size} " +
            "sets could be scored, on a scale of 0 to 100.",
    )

    VerdictCard(data)
}

/**
 * §8.2's verdict card.
 *
 * The wording matters more than the colour. §6.21.5 reserves advancement for a clinician, and this
 * card is read by the patient, so "ready to progress" has to arrive as evidence for a conversation
 * rather than as permission. The line naming who decides is not optional decoration.
 */
@Composable
private fun VerdictCard(data: AnalyticsData) {
    val verdict = data.verdict
    val (label, ink, wash) = when {
        verdict.needsReview -> Triple("Needs review", Tokens.DangerInk, Tokens.DangerWash)
        verdict.readyToProgress -> Triple("Ready to progress", Tokens.SuccessInk, Tokens.SuccessWash)
        else -> Triple("Keep going", Tokens.TextSecondary, Tokens.CanvasSubtle)
    }

    RpCard(border = if (verdict.readyToProgress) Tokens.SuccessInk else null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(
                label = label,
                ink = ink,
                wash = wash,
                icon = when {
                    verdict.needsReview -> Icons.Filled.Warning
                    verdict.readyToProgress -> Icons.Filled.CheckCircle
                    else -> null
                },
            )
            Text(
                "over ${verdict.sessionsConsidered} sets",
                style = Type.LabelMonoSm,
                color = Tokens.TextMuted,
            )
        }
        verdict.reasons.forEach { reason ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .size(5.dp)
                        .background(ink, CircleShape),
                )
                Text(reason, style = Type.BodyMd, color = Tokens.TextSecondary)
            }
        }
        Text(
            "Your physiotherapist decides when to move you on. The app never advances anyone on " +
                "its own.",
            style = Type.BodySm,
            color = Tokens.TextMuted,
        )
    }
}

// ---------------------------------------------------------------------------- Quality

@Composable
private fun QualityTab(data: AnalyticsData, onOpenSession: (String) -> Unit) {
    TrendChartCard(
        title = "Quality per set",
        note = "Target: ${QUALITY_BAR.toInt()}",
        caption = "Fixed 0 to 100, so two sets can be compared by eye. A hollow marker is a set " +
            "with too few reps to judge - a gap, not a bad score.",
        points = data.quality,
        xLabels = emptyList(),
        fixedRange = 0f..100f,
        target = QUALITY_BAR.toFloat(),
        trace = Tokens.Secondary,
        description = "Quality per set. ${data.judgedSessions} of ${data.metrics.size} sets scored.",
    )

    RpCard {
        Text("What moves this number", style = Type.HeadlineSm, color = Tokens.TextPrimary)
        Text(
            "Quality compares each rep against your physiotherapist's own recording of the " +
                "movement. Reaching your full range and keeping an even pace are what it responds " +
                "to most; compensating elsewhere in the body late in a set is what pulls it down.",
            style = Type.BodyMd,
            color = Tokens.TextSecondary,
        )
    }

    SectionHeader(title = "Every set", note = "newest first")
    data.metrics.indices.reversed().forEach { index ->
        SessionRow(data, index, onOpenSession)
    }
}

// ------------------------------------------------------------------------------ Range

@Composable
private fun RangeTab(data: AnalyticsData) {
    val peak = data.romPeak.mapNotNull { it.y }.maxOrNull()

    TrendChartCard(
        title = "Range per set",
        note = peak?.let { "Peak: %.0f°".format(it) },
        caption = "Your best three reps in each set, in degrees. This chart scales to your own " +
            "numbers - range has no natural ceiling the way quality does.",
        points = data.romPeak,
        xLabels = emptyList(),
        trace = Tokens.SuccessInk,
        description = "Peak range per set, in degrees. Higher is further through the movement.",
    )

    // §6.21.3's guard, surfaced honestly. Nothing captures a range goal yet, so rather than
    // fabricating a percentage the screen says who sets one and what changes when they do.
    RpCard(background = Tokens.SurfaceCardWarm) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = Tokens.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text("No range goal set yet", style = Type.HeadlineSm, color = Tokens.TextSecondary)
        }
        Text(
            "Your physiotherapist sets the range you are working toward. Until they do, this " +
                "chart shows what you reached without judging it against a target.",
            style = Type.BodyMd,
            color = Tokens.TextSecondary,
        )
    }

    RpCard {
        Text("Why your best three reps?", style = Type.HeadlineSm, color = Tokens.TextPrimary)
        Text(
            "One rep can be lucky, and an average over a whole set is dragged down by the ones you " +
                "were still warming up for. The average of your best three is the range you can " +
                "reach repeatably, which is the thing that has to improve for the movement to get " +
                "easier.",
            style = Type.BodyMd,
            color = Tokens.TextSecondary,
        )
    }
}

// ---------------------------------------------------------------------------- History

@Composable
private fun HistoryTab(data: AnalyticsData, onOpenSession: (String) -> Unit) {
    val groups = remember(data) { groupByWeek(data) }
    if (groups.isEmpty()) return

    groups.forEach { (heading, indices) ->
        SectionHeader(
            title = heading,
            note = if (indices.size == 1) "1 set" else "${indices.size} sets",
        )
        indices.forEach { index -> SessionRow(data, index, onOpenSession) }
    }
    Text(
        "Every set you have finished is here, including the ones that could not be scored.",
        style = Type.BodySm,
        color = Tokens.TextSecondary,
        modifier = Modifier.padding(horizontal = Space.xxs),
    )
}

// ------------------------------------------------------------------------ Shared parts

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    note: String? = null,
    noteInk: Color = Tokens.TextSecondary,
    valueInk: Color = Tokens.TextPrimary,
) {
    RpCard(modifier = modifier, padding = Space.md) {
        Text(label, style = Type.TitleSm, color = Tokens.TextSecondary)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Type.HeadlineLg, color = valueInk)
            unit?.let {
                Text(
                    " $it",
                    style = Type.BodyMd,
                    color = Tokens.TextSecondary,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        note?.let { Text(it, style = Type.LabelMonoSm, color = noteInk) }
    }
}

/** §8.3's session row, the one shape every list of sessions uses. */
@Composable
private fun SessionRow(data: AnalyticsData, index: Int, onOpenSession: (String) -> Unit) {
    val metrics = data.metrics[index]
    val startedAt = data.startedAt.getOrNull(index)
    Surface(
        onClick = { onOpenSession(metrics.sessionId) },
        shape = Radius.Card,
        color = Tokens.SurfaceCard,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Space.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    startedAt?.let { DATE.format(Date(it)) } ?: "Undated set",
                    style = Type.TitleMd,
                    color = Tokens.TextPrimary,
                )
                Text(
                    buildString {
                        append("${metrics.validReps}")
                        if (metrics.prescribedReps > 0) append(" of ${metrics.prescribedReps}")
                        append(" reps")
                        metrics.romPeak?.let { append(" · %.0f° peak".format(it)) }
                    },
                    style = Type.BodySm,
                    color = Tokens.TextSecondary,
                )
                metrics.degradationOnset?.let {
                    Text(
                        "Form started slipping at rep $it",
                        style = Type.BodySm,
                        color = Tokens.WarningInk,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    metrics.qMean?.let { "%.0f".format(it) } ?: "—",
                    style = Type.HeadlineSm,
                    color = when {
                        metrics.qMean == null -> Tokens.AbsentInk
                        metrics.qMean >= QUALITY_BAR -> Tokens.SuccessInk
                        else -> Tokens.WarningInk
                    },
                )
                Text(
                    if (metrics.qMean == null) "not scored" else "score",
                    style = Type.LabelMonoSm,
                    color = Tokens.TextMuted,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Tokens.TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ------------------------------------------------------------------------- Derivations

/**
 * The four headline numbers on Overview.
 *
 * Every one of them is nullable where the underlying measurement can be absent, and none of them
 * substitutes a zero: a patient with no scoreable set yet has no median, not a median of nothing.
 */
private data class ProgressStats(
    val setsThisWeek: Int,
    val unjudgedThisWeek: Int,
    val medianQuality: Double?,
    val bestRange: Double?,
    /** Peak range now, less the peak of the first set that had one. Null when either is missing. */
    val rangeGain: Double?,
    val streakDays: Int,
)

private fun progressStatsOf(data: AnalyticsData): ProgressStats {
    val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
    val thisWeek = data.metrics.indices.filter { (data.startedAt.getOrNull(it) ?: 0L) >= weekAgo }

    val qualities = data.metrics.filter { it.countsTowardProgression }.mapNotNull { it.qMean }.sorted()
    val median = when {
        qualities.isEmpty() -> null
        qualities.size % 2 == 1 -> qualities[qualities.size / 2]
        else -> (qualities[qualities.size / 2 - 1] + qualities[qualities.size / 2]) / 2
    }

    val peaks = data.metrics.mapNotNull { it.romPeak }
    return ProgressStats(
        setsThisWeek = thisWeek.size,
        unjudgedThisWeek = thisWeek.count { !data.metrics[it].countsTowardProgression },
        medianQuality = median,
        bestRange = peaks.maxOrNull(),
        rangeGain = if (peaks.size >= 2) peaks.last() - peaks.first() else null,
        streakDays = streakOf(data.startedAt),
    )
}

/**
 * Consecutive days, counting back from today, on which at least one set was finished.
 *
 * Counting back from *today* rather than from the most recent set is deliberate: a streak that
 * survives a missed day is not a streak, and a number that quietly means "days in a row ending
 * whenever you last trained" would flatter the reader. Zero is an honest answer here - unlike a
 * score, a streak of none is a real measurement.
 */
/**
 * §10.7's fact sheet for one exercise's run of sessions.
 *
 * Assembled from the numbers already on the tiles beside it, so the paragraph and the tiles cannot
 * disagree - the paragraph is a reading of this screen, not a second measurement of the same sets.
 * Every absent value stays absent: a patient with nothing scored yet gets a sentence saying so
 * rather than a median of zero.
 */
private fun progressFactsOf(data: AnalyticsData, stats: ProgressStats): ProgressFacts {
    val scored = data.metrics.filter { it.countsTowardProgression }
    val qualities = scored.mapNotNull { it.qMean }
    return ProgressFacts(
        subject = data.exerciseName,
        audience = SummaryAudience.PATIENT,
        sets = data.metrics.size,
        scoredSets = data.judgedSessions,
        medianQuality = stats.medianQuality?.roundToInt(),
        bestQuality = qualities.maxOrNull()?.roundToInt(),
        latestQuality = qualities.lastOrNull()?.roundToInt(),
        setsAtOrAboveBar = qualities.count { it >= QUALITY_BAR },
        qualityBar = QUALITY_BAR.toInt(),
        repsCompleted = data.metrics.sumOf { it.validReps },
        repsPrescribed = data.metrics.sumOf { it.prescribedReps },
        streakDays = stats.streakDays,
        trend = trendOf(qualities),
        bestRangeDegrees = stats.bestRange?.roundToInt(),
        rangeGainDegrees = stats.rangeGain?.roundToInt(),
    )
}

/**
 * Which way quality has moved, as a direction.
 *
 * The first half of the run against the second, rather than first-against-last: two sets are two
 * measurements, and calling the difference between them a trend is how a single bad day becomes
 * "your scores are falling". Under four scored sets there is no halves comparison worth making, so
 * the answer is that there is not enough yet - which is a different claim from "steady".
 *
 * The threshold is two points on a hundred-point scale. Below that the two halves are the same
 * number as far as anyone reading a summary is concerned.
 */
private fun trendOf(qualities: List<Double>): QualityTrend {
    if (qualities.size < 4) return QualityTrend.UNKNOWN
    val half = qualities.size / 2
    val earlier = qualities.take(half).average()
    val later = qualities.drop(qualities.size - half).average()
    return when {
        later - earlier > 2.0 -> QualityTrend.IMPROVING
        earlier - later > 2.0 -> QualityTrend.DECLINING
        else -> QualityTrend.STEADY
    }
}

private fun streakOf(startedAt: List<Long>): Int {
    if (startedAt.isEmpty()) return 0
    val days = startedAt.map { dayOf(it) }.toSet()
    var day = dayOf(System.currentTimeMillis())
    var streak = 0
    while (day in days) {
        streak += 1
        day -= 1
    }
    return streak
}

/** Days since the epoch in the device's own zone, so "yesterday" means what the patient means. */
private fun dayOf(millis: Long): Long {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis / (24L * 60 * 60 * 1000)
}

/** History, newest first, split into this week, last week, and everything before. */
private fun groupByWeek(data: AnalyticsData): List<Pair<String, List<Int>>> {
    val today = dayOf(System.currentTimeMillis())
    val buckets = linkedMapOf<String, MutableList<Int>>()
    data.metrics.indices.reversed().forEach { index ->
        val started = data.startedAt.getOrNull(index) ?: return@forEach
        val age = today - dayOf(started)
        val heading = when {
            age < 7 -> "This week"
            age < 14 -> "Last week"
            else -> "Earlier"
        }
        buckets.getOrPut(heading) { mutableListOf() }.add(index)
    }
    return buckets.map { it.key to it.value }
}

private val DATE = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())
