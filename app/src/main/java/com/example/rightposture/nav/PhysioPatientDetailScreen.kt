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
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.rightposture.core.progression.QUALITY_BAR
import com.example.rightposture.core.summary.ProgressFacts
import com.example.rightposture.core.summary.QualityTrend
import com.example.rightposture.core.summary.SummaryAudience
import com.example.rightposture.llm.LlmBackend
import com.example.rightposture.llm.OnDeviceLlm
import kotlin.math.roundToInt
import com.example.rightposture.report.ChartPoint
import com.example.rightposture.sync.LinkedPatient
import com.example.rightposture.sync.RemoteSession
import com.example.rightposture.ui.components.NoteBanner
import com.example.rightposture.ui.components.Pill
import com.example.rightposture.ui.components.RpCard
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

/**
 * §5.3's patient detail: everything this phone knows about one patient, in four tabs.
 *
 * New in the sense that a physio previously could not open a patient at all - the roster was a list
 * of names and nothing more, so "how is Elena doing?" had to be answered by scrolling the whole
 * activity feed and mentally filtering it.
 *
 * ## What this screen can and cannot see
 *
 * Everything here is derived from §14.1's report - the sessions the server already hands the physio
 * for their own exercises - filtered to one patient. That report carries the *outcome* of each set:
 * score, reps counted against reps prescribed, §6.18.3's degradation onset, and whether the set
 * finished. It does not carry per-rep rows, joint samples, range peaks, or the patient's
 * prescription.
 *
 * So three of the four tabs are real and the Programme tab is honest about being unreadable. That is
 * a deliberate choice over the alternative, which would be to fill it with the physio's *own*
 * assignments and let them read as the patient's. §1.6's rule about absent values is a rule about
 * clinical claims, not about charts: a programme this device cannot read must say so, because a
 * physio who believes they are looking at what a patient was told to do will act on it.
 */
@Composable
fun PhysioPatientDetailScreen(
    patient: LinkedPatient,
    sessions: List<RemoteSession>,
    exerciseNames: Map<String, String>,
    llm: OnDeviceLlm?,
    backend: LlmBackend?,
    onBack: () -> Unit,
) {
    var tab by remember(patient.id) { mutableIntStateOf(0) }
    // Newest first is how the server sends them and how the feed reads. Oldest first is what a
    // chart needs, and deriving it once here keeps the two orderings from being confused per tab.
    val oldestFirst = remember(sessions) { sessions.sortedBy { millisOf(it.startedAt) ?: 0L } }
    val stats = remember(sessions) { patientStatsOf(sessions) }

    var exerciseFilter by remember(patient.id) { mutableStateOf<String?>(null) }
    val exerciseIds = remember(sessions) {
        sessions.mapNotNull { it.exerciseId }.distinct()
    }

    Scaffold(containerColor = Tokens.CanvasBg) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.Gutter),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            DetailTopRow(onBack)

            PatientHeader(patient, stats)

            SegmentedTabs(
                labels = listOf("Overview", "Progress", "Programme", "Sessions"),
                badges = listOf(null, null, null, stats.sets.takeIf { it > 0 }?.toString()),
                selectedIndex = tab,
                onSelect = { tab = it },
            )

            if (sessions.isEmpty()) {
                // "Linked but silent" is its own answer, and a different one from "the code never
                // worked". Saying nothing is missing is the point of the sentence.
                RpCard(background = Tokens.SurfaceCardWarm) {
                    Text(
                        "No sets recorded yet",
                        style = Type.HeadlineSm,
                        color = Tokens.TextSecondary,
                    )
                    Text(
                        firstName(patient).replaceFirstChar { it.uppercase() } +
                            " is linked but has not finished a set yet. Nothing is missing " +
                            "here - there is nothing to show.",
                        style = Type.BodyMd,
                        color = Tokens.TextSecondary,
                    )
                }
                Box(modifier = Modifier.height(Space.xl))
                return@Column
            }

            when (tab) {
                0 -> OverviewTab(
                    patient = patient,
                    stats = stats,
                    oldestFirst = oldestFirst,
                    newestFirst = sessions,
                    exerciseNames = exerciseNames,
                    llm = llm,
                    backend = backend,
                    onOpenSessions = { tab = 3 },
                )
                1 -> PatientProgressTab(
                    oldestFirst = oldestFirst,
                    exerciseIds = exerciseIds,
                    exerciseNames = exerciseNames,
                    selected = exerciseFilter ?: exerciseIds.firstOrNull(),
                    onPick = { exerciseFilter = it },
                )
                2 -> ProgrammeTab(
                    patient = patient,
                    newestFirst = sessions,
                    exerciseNames = exerciseNames,
                )
                else -> PatientSessionsTab(
                    newestFirst = sessions,
                    exerciseIds = exerciseIds,
                    exerciseNames = exerciseNames,
                    selected = exerciseFilter,
                    onPick = { exerciseFilter = it },
                )
            }

            Box(modifier = Modifier.height(Space.xl))
        }
    }
}

// ----------------------------------------------------------------------------- Header

/**
 * Who this is, and when they were last seen.
 *
 * The name is the only thing on this screen the physio recognises before reading anything else, so
 * it is the largest thing on it. "Last set" sits beside it because the first question about a
 * patient record is almost always how current it is.
 */
@Composable
private fun PatientHeader(patient: LinkedPatient, stats: PatientStats) {
    RpCard(padding = Space.md) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).background(Tokens.PrimaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(initialsOf(patient), style = Type.TitleMd, color = Tokens.OnPrimary)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    patient.name ?: patient.email,
                    style = Type.HeadlineSm,
                    color = Tokens.TextPrimary,
                )
                if (patient.name != null) {
                    Text(patient.email, style = Type.BodySm, color = Tokens.TextSecondary)
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("Linked", Tokens.SuccessInk, Tokens.SuccessWash, icon = Icons.Filled.CheckCircle)
            Pill(
                label = stats.lastSeen?.let { "Last set ${relativeDay(it)}" } ?: "No sets yet",
                ink = Tokens.TextSecondary,
                wash = Tokens.SurfaceCardWarm,
            )
        }
    }
}

// --------------------------------------------------------------------------- Overview

/**
 * The thirty-second read on one patient: are they doing the work, and is it any good?
 *
 * The readiness card is deliberately *not* a verdict. §6.21 decides progression on three criteria
 * and this device can evaluate two of them; calling two out of three a recommendation would be the
 * same error as scoring a set on half its reps. So the card shows the evidence it has, names the
 * criterion it cannot reach, and stops - which is also what §6.21.3 does when no range goal exists.
 */
@Composable
private fun OverviewTab(
    patient: LinkedPatient,
    stats: PatientStats,
    oldestFirst: List<RemoteSession>,
    newestFirst: List<RemoteSession>,
    exerciseNames: Map<String, String>,
    llm: OnDeviceLlm?,
    backend: LlmBackend?,
    onOpenSessions: () -> Unit,
) {
    val facts = remember(oldestFirst, exerciseNames) {
        patientFactsOf(oldestFirst, exerciseNames)
    }
    val summary by rememberProgressSummary(facts, llm)

    // First on the tab, because a physio opening a patient between appointments is asking one
    // question and the tiles below are the evidence for the answer rather than the answer.
    GeneratedSummaryCard(
        title = "Where this patient is",
        state = summary,
        backend = backend,
        writingLabel = "Writing the summary on this phone…",
    )

    SectionHeader(title = "Activity", note = "all recorded sets")
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        PatientStatTile(
            label = "Sets recorded",
            value = "${stats.sets}",
            note = "${stats.scored} scored",
            modifier = Modifier.weight(1f),
        )
        PatientStatTile(
            label = "Median quality",
            value = stats.medianQuality?.let { "%.0f".format(it) } ?: "—",
            unit = if (stats.medianQuality != null) "/100" else null,
            // §1.6. No scored set is not a median of zero, and a grey dash says so without
            // needing a sentence beside it.
            valueInk = scoreInk(stats.medianQuality),
            note = if (stats.medianQuality == null) "nothing scored yet" else null,
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        PatientStatTile(
            label = "Reps completed",
            value = if (stats.prescribedReps > 0) {
                "%.0f".format(100.0 * stats.countedReps / stats.prescribedReps)
            } else {
                "—"
            },
            unit = if (stats.prescribedReps > 0) "%" else null,
            note = "${stats.countedReps} of ${stats.prescribedReps}",
            modifier = Modifier.weight(1f),
        )
        PatientStatTile(
            label = "Day streak",
            value = "${stats.streak}",
            unit = if (stats.streak == 1) "day" else "days",
            note = "consecutive, to today",
            modifier = Modifier.weight(1f),
        )
    }

    QualityTrend(oldestFirst, title = "Quality per set", limit = 7)

    ReadinessCard(patient, oldestFirst)

    SectionHeader(title = "Most recent set", note = "${stats.sets} in total")
    newestFirst.firstOrNull()?.let { PatientSessionCard(it, exerciseNames) }
    Surface(
        onClick = onOpenSessions,
        shape = Radius.Pill,
        color = Tokens.SurfaceCardWarm,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "View all ${stats.sets} sets",
            style = Type.TitleSm,
            color = Tokens.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
    }
}

/**
 * §6.21's criteria, scored as far as this device can score them.
 *
 * Two of the three are answerable from the report: consecutive sets at or above the quality bar,
 * and whether the set is being completed. The range-goal criterion is not, because the goal lives
 * in a prescription this phone has no way to read - so it renders as absent rather than as failed,
 * which are opposite instructions to a clinician.
 */
@Composable
private fun ReadinessCard(patient: LinkedPatient, oldestFirst: List<RemoteSession>) {
    val lastThree = oldestFirst.takeLast(3)
    val scores = lastThree.mapNotNull { it.setScore }
    val qualityMet = scores.size == 3 && scores.all { it >= QUALITY_BAR }
    val completionMet = lastThree.size == 3 && lastThree.all {
        it.prescribedReps > 0 && it.countedReps >= it.prescribedReps
    }

    RpCard(background = Tokens.SurfaceCardWarm) {
        Text("Progression readiness", style = Type.HeadlineSm, color = Tokens.TextPrimary)
        Text(
            "Readiness cannot be decided on this screen, because one of §6.21's three criteria " +
                "is not readable here. The two that are readable are below, as evidence rather " +
                "than as a recommendation.",
            style = Type.BodyMd,
            color = Tokens.TextSecondary,
        )

        CriterionRow(
            met = qualityMet,
            text = if (scores.isEmpty()) {
                "Quality at or above ${QUALITY_BAR.toInt()} in three consecutive sets — no " +
                    "scored set to judge"
            } else {
                "Quality at or above ${QUALITY_BAR.toInt()} in three consecutive sets — " +
                    scores.joinToString(", ") { "%.0f".format(it) }
            },
        )
        CriterionRow(
            met = completionMet,
            text = "Full prescribed reps in three consecutive sets — " +
                lastThree.joinToString(", ") { "${it.countedReps}/${it.prescribedReps}" },
        )
        CriterionRow(
            met = null,
            text = "Range goal reached — this phone cannot read ${firstName(patient)}'s range " +
                "goal, so this criterion is unassessed",
        )

        Text(
            "The app flags; you decide. Advancing a programme is done where the programme lives.",
            style = Type.LabelMonoSm,
            color = Tokens.TextMuted,
        )
    }
}

/** One criterion: met, not met, or - the case that matters most - not assessable. */
@Composable
private fun CriterionRow(met: Boolean?, text: String) {
    val ink = when (met) {
        true -> Tokens.SuccessInk
        false -> Tokens.WarningInk
        null -> Tokens.AbsentInk
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.SurfaceCard, Radius.CardSmall)
            .padding(Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        // A glyph as well as a colour, per §11: "not assessed" and "not met" must not be
        // distinguishable by hue alone.
        Text(
            when (met) {
                true -> "✓"
                false -> "✗"
                null -> "—"
            },
            style = Type.TitleMd,
            color = ink,
        )
        Text(text, style = Type.BodySm, color = Tokens.TextSecondary)
    }
}

// --------------------------------------------------------------------------- Progress

/**
 * The patient's own quality curve, per exercise.
 *
 * Per exercise and never averaged across them, for the same reason §4.4 is: a knee extension and a
 * wall sit are scored against different references, and a mean over both describes no movement that
 * exists.
 */
@Composable
private fun PatientProgressTab(
    oldestFirst: List<RemoteSession>,
    exerciseIds: List<String>,
    exerciseNames: Map<String, String>,
    selected: String?,
    onPick: (String) -> Unit,
) {
    if (exerciseIds.size > 1) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            exerciseIds.forEach { id ->
                ExerciseChip(
                    label = exerciseNames[id] ?: id,
                    selected = id == selected,
                    onClick = { onPick(id) },
                )
            }
        }
    }

    val forExercise = oldestFirst.filter { selected == null || it.exerciseId == selected }
    QualityTrend(
        sessions = forExercise,
        title = exerciseNames[selected] ?: "Quality per set",
        limit = 12,
    )

    val scored = forExercise.count { it.setScore != null }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        PatientStatTile(
            label = "Best set",
            value = forExercise.mapNotNull { it.setScore }.maxOrNull()
                ?.let { "%.0f".format(it) } ?: "—",
            modifier = Modifier.weight(1f),
        )
        PatientStatTile(
            label = "Unscored",
            value = "${forExercise.size - scored}",
            note = "of ${forExercise.size} · too few reps",
            modifier = Modifier.weight(1f),
        )
    }

    // The joint-attribution trend §5.3 asks for needs per-rep feature samples, which are uploaded
    // but not part of the report this screen reads. Saying so beats drawing an empty axis.
    NoteBanner(
        text = "Joint attribution is recorded on the patient's phone and is not part of the " +
            "report this screen reads, so it is not charted here.",
        ink = Tokens.TextSecondary,
        wash = Tokens.SurfaceCardWarm,
        icon = Icons.Filled.Info,
    )
}

/** The quality curve, with §1.6's hollow marker for a set that could not be judged. */
@Composable
private fun QualityTrend(sessions: List<RemoteSession>, title: String, limit: Int) {
    val window = sessions.takeLast(limit)
    if (window.isEmpty()) return

    val points = window.mapIndexed { index, session ->
        ChartPoint(index.toFloat(), session.setScore?.toFloat())
    }
    val judged = window.count { it.setScore != null }

    TrendChartCard(
        title = title,
        note = "Pass: ${QUALITY_BAR.toInt()}",
        caption = "Fixed 0 to 100, so two sets compare by eye. A hollow marker is a set with too " +
            "few reps to judge - a gap in the record, not a low score.",
        points = points,
        xLabels = window.map { shortDay(millisOf(it.startedAt)) },
        fixedRange = 0f..100f,
        target = QUALITY_BAR.toFloat(),
        trace = Tokens.Secondary,
        description = "Quality per set. $judged of ${window.size} sets scored.",
    )
}

@Composable
private fun ExerciseChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = Radius.Pill,
        color = if (selected) Tokens.PrimaryContainer else Tokens.SurfaceCardWarm,
    ) {
        Text(
            label,
            style = Type.TitleSm,
            color = if (selected) Tokens.OnPrimary else Tokens.TextSecondary,
            modifier = Modifier.padding(horizontal = Space.sm, vertical = 8.dp),
        )
    }
}

// -------------------------------------------------------------------------- Programme

/**
 * What the patient has been performing, and a plain statement of what this is not.
 *
 * The prescription itself - sets, reps, rest, range goals - is stored against the patient and this
 * device has no read for it. What it *can* say, truthfully, is which of the physio's references the
 * patient's sets were scored against and at which version, which is most of what the tab is opened
 * for and is a fact rather than an inference.
 *
 * The distinction is laboured on the card on purpose. "Performing" and "prescribed" differ exactly
 * when something has gone wrong - a patient skipping an exercise, or working from a stale
 * assignment - and that is the case a physio must not mistake for the other.
 */
@Composable
private fun ProgrammeTab(
    patient: LinkedPatient,
    newestFirst: List<RemoteSession>,
    exerciseNames: Map<String, String>,
) {
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
            Text(
                "The prescription is not readable here",
                style = Type.HeadlineSm,
                color = Tokens.TextSecondary,
            )
        }
        Text(
            "Sets, reps, rest and range goals are stored against ${firstName(patient)}'s account, " +
                "and this phone has no way to read them back. Below is what they have actually " +
                "been performing, which is a different claim and is labelled as one.",
            style = Type.BodyMd,
            color = Tokens.TextSecondary,
        )
    }

    val performed = newestFirst
        .filter { it.exerciseId != null }
        .groupBy { it.exerciseId!! }

    SectionHeader(title = "Performing", note = "derived from completed sets")
    performed.forEach { (exerciseId, forExercise) ->
        val newest = forExercise.first()
        RpCard(padding = Space.md) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill("Exercise", Tokens.TagExerciseInk, Tokens.TagExerciseWash)
                // §9.6: the version is part of the identity. A patient still working against v1
                // while the physio has published v2 is exactly the thing this line makes visible.
                newest.exerciseVersion?.let {
                    Text("v$it", style = Type.LabelMonoSm, color = Tokens.TextSecondary)
                }
            }
            Text(
                exerciseNames[exerciseId] ?: exerciseId,
                style = Type.TitleMd,
                color = Tokens.TextPrimary,
            )
            Text(
                buildString {
                    append(if (forExercise.size == 1) "1 set" else "${forExercise.size} sets")
                    val prescribed = forExercise.map { it.prescribedReps }.filter { it > 0 }
                    if (prescribed.isNotEmpty()) {
                        append(" · ${prescribed.max()} reps prescribed per set")
                    }
                    newest.startedAt?.let { append(" · last ${readableInstant(it)}") }
                },
                style = Type.BodySm,
                color = Tokens.TextSecondary,
            )
        }
    }
}

// --------------------------------------------------------------------------- Sessions

/** Every set this patient has finished, grouped the way §4.4's History is. */
@Composable
private fun PatientSessionsTab(
    newestFirst: List<RemoteSession>,
    exerciseIds: List<String>,
    exerciseNames: Map<String, String>,
    selected: String?,
    onPick: (String?) -> Unit,
) {
    if (exerciseIds.size > 1) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            ExerciseChip("All exercises", selected == null) { onPick(null) }
            exerciseIds.forEach { id ->
                ExerciseChip(exerciseNames[id] ?: id, id == selected) { onPick(id) }
            }
        }
    }

    val shown = newestFirst.filter { selected == null || it.exerciseId == selected }
    groupByWeekOf(shown).forEach { (heading, group) ->
        SectionHeader(
            title = heading,
            note = if (group.size == 1) "1 set" else "${group.size} sets",
        )
        group.forEach { PatientSessionCard(it, exerciseNames) }
    }

    Text(
        "Every finished set is here, including the ones that could not be scored. Opening one " +
            "rep by rep needs the phone it was recorded on.",
        style = Type.BodySm,
        color = Tokens.TextSecondary,
        modifier = Modifier.padding(horizontal = Space.xxs),
    )
}

/**
 * One of the patient's sets.
 *
 * Not tappable, and that is not an omission. §4.3's session detail reads reps and joint samples out
 * of the local database, and these sets were recorded on someone else's phone - so a row that
 * opened would open onto "that set is no longer on this device". Everything the report does carry
 * is therefore on the row itself rather than behind it.
 */
@Composable
private fun PatientSessionCard(session: RemoteSession, exerciseNames: Map<String, String>) {
    SessionCardShell(
        title = session.exerciseId?.let { exerciseNames[it] ?: it } ?: "Unnamed exercise",
        subtitle = buildString {
            append("${session.countedReps}")
            if (session.prescribedReps > 0) append(" of ${session.prescribedReps}")
            append(" reps")
            session.startedAt?.let { append(" · ${readableInstant(it)}") }
        },
        score = session.setScore,
        warning = when {
            session.unfinished -> "Interrupted"
            session.degradationOnsetRep != null ->
                "Form started slipping at rep ${session.degradationOnsetRep}"
            else -> null
        },
        onClick = null,
    )
}

// ---------------------------------------------------------------------- Shared pieces

@Composable
private fun PatientStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    note: String? = null,
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
        note?.let { Text(it, style = Type.LabelMonoSm, color = Tokens.TextMuted) }
    }
}

// ------------------------------------------------------------------------ Derivations

/** The handful of numbers the header and Overview quote, computed once per patient. */
internal data class PatientStats(
    val sets: Int,
    val scored: Int,
    val medianQuality: Double?,
    val countedReps: Int,
    val prescribedReps: Int,
    val streak: Int,
    val lastSeen: Long?,
)

/**
 * Summarises one patient's report rows.
 *
 * The median rather than the mean, because a single interrupted set at 20 drags a mean below the
 * pass mark and would have a physio reviewing a patient who is fine. Unscored sets are dropped
 * from the quality figure entirely rather than counted as zero - §1.6 again - which is why [scored]
 * is quoted beside it.
 */
internal fun patientStatsOf(sessions: List<RemoteSession>): PatientStats {
    val scores = sessions.mapNotNull { it.setScore }.sorted()
    val days = sessions.mapNotNull { millisOf(it.startedAt) }
    return PatientStats(
        sets = sessions.size,
        scored = scores.size,
        medianQuality = when {
            scores.isEmpty() -> null
            scores.size % 2 == 1 -> scores[scores.size / 2]
            else -> (scores[scores.size / 2 - 1] + scores[scores.size / 2]) / 2
        },
        countedReps = sessions.sumOf { it.countedReps },
        prescribedReps = sessions.sumOf { it.prescribedReps },
        streak = streakOfDays(days),
        lastSeen = days.maxOrNull(),
    )
}

/**
 * §10.7's fact sheet for one patient's run of sessions.
 *
 * Across the whole programme rather than per exercise, which is a departure from §4.4's rule and a
 * deliberate one: this screen is opened to ask about a *person*, and the tiles beside the paragraph
 * already aggregate the same way. The subject line names how many exercises are behind the numbers,
 * so a reader is not left thinking one score describes one movement.
 *
 * The trend is deliberately left [QualityTrend.UNKNOWN] when fewer than four sets have been scored:
 * two scores are two measurements, and calling the gap between them a direction is how one bad day
 * becomes "this patient is deteriorating".
 */
private fun patientFactsOf(
    oldestFirst: List<RemoteSession>,
    exerciseNames: Map<String, String>,
): ProgressFacts {
    val scores = oldestFirst.mapNotNull { it.setScore }
    val exercises = oldestFirst.mapNotNull { it.exerciseId }.distinct()
    val sorted = scores.sorted()
    val median = when {
        sorted.isEmpty() -> null
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    return ProgressFacts(
        subject = when (exercises.size) {
            0 -> "their programme"
            1 -> exerciseNames[exercises.first()] ?: "their programme"
            else -> "their programme of ${exercises.size} exercises"
        },
        audience = SummaryAudience.CLINICIAN,
        sets = oldestFirst.size,
        scoredSets = scores.size,
        medianQuality = median?.roundToInt(),
        bestQuality = scores.maxOrNull()?.roundToInt(),
        latestQuality = scores.lastOrNull()?.roundToInt(),
        setsAtOrAboveBar = scores.count { it >= QUALITY_BAR },
        qualityBar = QUALITY_BAR.toInt(),
        repsCompleted = oldestFirst.sumOf { it.countedReps },
        repsPrescribed = oldestFirst.sumOf { it.prescribedReps },
        streakDays = streakOfDays(oldestFirst.mapNotNull { millisOf(it.startedAt) }),
        trend = patientTrendOf(scores),
    )
}

private fun patientTrendOf(scores: List<Double>): QualityTrend {
    if (scores.size < 4) return QualityTrend.UNKNOWN
    val half = scores.size / 2
    val earlier = scores.take(half).average()
    val later = scores.drop(scores.size - half).average()
    return when {
        later - earlier > 2.0 -> QualityTrend.IMPROVING
        earlier - later > 2.0 -> QualityTrend.DECLINING
        else -> QualityTrend.STEADY
    }
}

/** Consecutive days ending today on which at least one set was finished. */
internal fun streakOfDays(startedAt: List<Long>): Int {
    if (startedAt.isEmpty()) return 0
    val days = startedAt.map { dayNumber(it) }.toSet()
    var day = dayNumber(System.currentTimeMillis())
    var streak = 0
    while (day in days) {
        streak += 1
        day -= 1
    }
    return streak
}

private fun dayNumber(millis: Long): Long {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis / (24L * 60 * 60 * 1000)
}

/** History split the way §4.4's does, so the two histories read identically. */
internal fun groupByWeekOf(
    newestFirst: List<RemoteSession>,
): List<Pair<String, List<RemoteSession>>> {
    val today = dayNumber(System.currentTimeMillis())
    val buckets = linkedMapOf<String, MutableList<RemoteSession>>()
    newestFirst.forEach { session ->
        val started = millisOf(session.startedAt)
        val heading = when {
            started == null -> "Undated"
            today - dayNumber(started) < 7 -> "This week"
            today - dayNumber(started) < 14 -> "Last week"
            else -> "Earlier"
        }
        buckets.getOrPut(heading) { mutableListOf() }.add(session)
    }
    return buckets.map { it.key to it.value }
}

/**
 * A server timestamp as epoch milliseconds, or null when it cannot be read.
 *
 * Null rather than zero: a row whose date is unreadable belongs in the "Undated" bucket, not on the
 * first of January 1970 at the top of the history.
 */
internal fun millisOf(iso: String?): Long? =
    iso?.let { runCatching { ISO_UTC.parse(it.take(19))?.time }.getOrNull() }

private fun relativeDay(millis: Long): String {
    val age = dayNumber(System.currentTimeMillis()) - dayNumber(millis)
    return when {
        age <= 0L -> "today"
        age == 1L -> "yesterday"
        age < 7L -> "$age days ago"
        else -> LOCAL_DATE.format(Date(millis)).substringBefore(" ·")
    }
}

private fun shortDay(millis: Long?): String =
    millis?.let { SHORT_DAY.format(Date(it)) }.orEmpty()

private fun initialsOf(patient: LinkedPatient): String {
    val source = patient.name?.takeIf { it.isNotBlank() } ?: patient.email
    val parts = source.trim().split(" ", ".", "_").filter { it.isNotBlank() }
    return when (parts.size) {
        0 -> "?"
        1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

/**
 * What to call this patient in a sentence.
 *
 * Falls back to "this patient" rather than to the email's local part. Real rosters contain
 * addresses like `patient@` and `admin@`, and "stored against patient's account" reads as a
 * rendering bug rather than as a name - which is a bad look on the one card whose whole job is to
 * be believed about what it cannot see.
 */
private fun firstName(patient: LinkedPatient): String =
    patient.name?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: "this patient"

private val SHORT_DAY = SimpleDateFormat("d MMM", Locale.getDefault())
