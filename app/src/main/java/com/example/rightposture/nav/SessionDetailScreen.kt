package com.example.rightposture.nav

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.rightposture.core.progression.QUALITY_BAR
import com.example.rightposture.data.FeatureSampleEntity
import com.example.rightposture.data.RepEntity
import com.example.rightposture.data.RightPostureDatabase
import com.example.rightposture.data.SessionEntity
import com.example.rightposture.llm.LlmBackend
import com.example.rightposture.llm.OnDeviceLlm
import com.example.rightposture.report.JointShift
import com.example.rightposture.report.RepReadout
import com.example.rightposture.report.jointShiftsOf
import com.example.rightposture.report.repQualitySeries
import com.example.rightposture.report.repReadoutsOf
import com.example.rightposture.ui.components.AttributionBar
import com.example.rightposture.ui.components.AttributionCard
import com.example.rightposture.ui.components.NoteBanner
import com.example.rightposture.ui.components.Pill
import com.example.rightposture.ui.components.RpCard
import com.example.rightposture.ui.components.SegmentedTabs
import com.example.rightposture.ui.components.TrendChartCard
import com.example.rightposture.ui.theme.Radius
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * §4.3's session detail: the full record of one set, for whoever wants it.
 *
 * Three tabs rather than one scroll, and the split is not cosmetic. This screen used to open with a
 * paragraph, four stat lines, two charts and twelve rep rows stacked on top of each other, which
 * meant the one thing most readers came for - what happened - was buried under the evidence for it.
 * Summary answers the question; Reps and Form hold the working.
 *
 * Summary is always the tab it opens on, including when a physio arrives from their activity feed.
 * The exception is a deep link from a "form slipped" flag, which opens **Form**, so a claim and the
 * chart behind it are one tap apart.
 */
@Composable
fun SessionDetailScreen(
    session: SessionEntity?,
    database: RightPostureDatabase,
    llm: OnDeviceLlm?,
    backend: LlmBackend?,
    onBack: () -> Unit,
    initialTab: SessionDetailTab = SessionDetailTab.SUMMARY,
) {
    var reps by remember(session?.id) { mutableStateOf<List<RepEntity>>(emptyList()) }
    var samples by remember(session?.id) { mutableStateOf<List<FeatureSampleEntity>>(emptyList()) }
    var exerciseName by remember(session?.id) { mutableStateOf<String?>(null) }
    var tab by remember(session?.id) { mutableIntStateOf(initialTab.ordinal) }

    LaunchedEffect(session?.id) {
        val id = session?.id ?: return@LaunchedEffect
        reps = withContext(Dispatchers.IO) { database.sessions().reps(id) }
        // One query, not one per rep. The whole set's samples are needed for both the attribution
        // and the summary facts, and asking per rep cost a round trip each.
        samples = withContext(Dispatchers.IO) { database.sessions().allFeatureSamples(id) }
        exerciseName = withContext(Dispatchers.IO) {
            session.exerciseId?.let { database.exercises().latestVersionOf(it)?.name }
        }
    }

    val jointShifts = remember(samples, session?.degradationOnsetRep) {
        jointShiftsOf(session?.degradationOnsetRep, samples)
    }
    val readouts = remember(reps) { repReadoutsOf(reps) }
    val summaryState by rememberGeneratedSummary(session, reps, database, llm)

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

            if (session == null) {
                RpCard {
                    Text("Session detail", style = Type.HeadlineSm, color = Tokens.TextPrimary)
                    Text(
                        "That set is no longer on this device.",
                        style = Type.BodyMd,
                        color = Tokens.TextSecondary,
                    )
                }
                return@Column
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Session detail", style = Type.HeadlineLg, color = Tokens.TextPrimary)
                Text(
                    exerciseName ?: session.exerciseId ?: "Unnamed exercise",
                    style = Type.BodyMd,
                    color = Tokens.TextSecondary,
                )
            }

            SegmentedTabs(
                labels = listOf("Summary", "Reps", "Form"),
                badges = listOf(null, readouts.size.takeIf { it > 0 }?.toString(), null),
                selectedIndex = tab,
                onSelect = { tab = it },
            )

            when (SessionDetailTab.entries[tab]) {
                SessionDetailTab.SUMMARY -> SummaryTab(session, reps, summaryState, backend)
                SessionDetailTab.REPS -> RepsTab(readouts)
                SessionDetailTab.FORM -> FormTab(session, reps, jointShifts)
            }

            Box(modifier = Modifier.height(Space.md))
        }
    }
}

/** Which view of the set to open on. See the note on [SessionDetailScreen]. */
enum class SessionDetailTab { SUMMARY, REPS, FORM }

@Composable
internal fun DetailTopRow(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onBack,
            shape = CircleShape,
            color = Tokens.SurfaceCardWarm,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Tokens.TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- Summary

@Composable
private fun SummaryTab(
    session: SessionEntity,
    reps: List<RepEntity>,
    summaryState: SummaryState,
    backend: LlmBackend?,
) {
    val counted = reps.count { it.valid }

    GeneratedSummaryCard(
        title = "What happened in this set",
        state = summaryState,
        backend = backend,
        writingLabel = "Writing your summary on this phone…",
        emptyLabel = "No written summary for this set.",
    )

    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        StatCard(
            label = "Reps counted",
            value = "$counted",
            unit = session.prescribedReps.takeIf { it > 0 }?.let { "/ $it" },
            note = (reps.size - counted).takeIf { it > 0 }?.let { "$it did not count" },
            noteInk = Tokens.WarningInk,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = "Set score",
            value = session.setScore?.let { "%.0f".format(it) } ?: "—",
            unit = session.setScore?.let { "/ 100" },
            note = if (session.setScore == null) {
                "Not scored"
            } else if (session.setScore >= QUALITY_BAR) {
                "Above the ${QUALITY_BAR.toInt()} pass mark"
            } else {
                "Below the ${QUALITY_BAR.toInt()} pass mark"
            },
            noteInk = when {
                session.setScore == null -> Tokens.AbsentInk
                session.setScore >= QUALITY_BAR -> Tokens.SuccessInk
                else -> Tokens.WarningInk
            },
            valueInk = if (session.setScore == null) Tokens.AbsentInk else Tokens.TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }

    session.degradationOnsetRep?.let { onset ->
        // §6.18.3. The whole point of the fatigue machinery, so it is stated plainly rather than
        // left in a chart the reader has to interpret.
        NoteBanner("Form started slipping at rep $onset.", ink = Tokens.WarningInk)
    }

    if (session.endedAt == null) {
        NoteBanner(
            "This set was interrupted. Everything below was still saved.",
            ink = Tokens.WarningInk,
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    unit: String?,
    note: String?,
    noteInk: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    valueInk: androidx.compose.ui.graphics.Color = Tokens.TextPrimary,
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

// ------------------------------------------------------------------------------- Reps

/**
 * One row per rep.
 *
 * The row has to hold two independent facts without looking like it is contradicting itself: a rep
 * can score well and still not count. §8.4's rule - the score is a measurement of how the movement
 * looked, countability is a judgement about whether it was the movement at all - so the score keeps
 * its normal weight and the reason sits beneath in its own sentence.
 */
@Composable
private fun RepsTab(readouts: List<RepReadout>) {
    if (readouts.isEmpty()) {
        RpCard {
            Text("No reps recorded", style = Type.HeadlineSm, color = Tokens.TextPrimary)
            Text(
                "This set ended before any repetition was completed.",
                style = Type.BodyMd,
                color = Tokens.TextSecondary,
            )
        }
        return
    }

    val best = readouts.filter { it.quality != null }.maxByOrNull { it.quality!! }?.repIndex

    readouts.forEach { readout ->
        RpCard(
            padding = Space.md,
            border = if (readout.repIndex == best) Tokens.SuccessInk else null,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "%02d".format(readout.repIndex),
                        style = Type.LabelMonoSm,
                        color = Tokens.TextMuted,
                        modifier = Modifier
                            .background(Tokens.CanvasSubtle, CircleShape)
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                readout.quality?.toString() ?: "—",
                                style = Type.HeadlineSm,
                                color = if (readout.quality == null) {
                                    Tokens.AbsentInk
                                } else {
                                    Tokens.TextPrimary
                                },
                            )
                            Text(
                                if (readout.quality == null) " not scored" else " / 100",
                                style = Type.BodySm,
                                color = Tokens.TextSecondary,
                            )
                        }
                        if (readout.repIndex == best) {
                            Text("Best rep", style = Type.LabelMonoSm, color = Tokens.SuccessInk)
                        }
                    }
                }
                if (readout.counted) {
                    Pill("Counted", Tokens.SuccessInk, Tokens.SuccessWash, icon = Icons.Filled.Check)
                } else {
                    Pill("Did not count", Tokens.WarningInk, Tokens.WarningWash)
                }
            }
            readout.detail?.let {
                Text(
                    it,
                    style = Type.BodySm,
                    color = Tokens.TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Tokens.CanvasBg, Radius.CardSmall)
                        .padding(Space.sm),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------- Form

@Composable
private fun FormTab(
    session: SessionEntity,
    reps: List<RepEntity>,
    jointShifts: List<JointShift>,
) {
    val series = remember(reps) { repQualitySeries(reps) }
    val scored = series.count { it.y != null }

    if (series.isNotEmpty()) {
        TrendChartCard(
            title = "Quality across the set",
            note = session.degradationOnsetRep?.let { "Onset: rep $it" }
                ?: "Pass ≥ ${QUALITY_BAR.toInt()}",
            caption = buildString {
                append("One point per rep, on a fixed 0 to 100 scale. ")
                append("Hollow markers are reps that could not be scored")
                if (session.degradationOnsetRep != null) {
                    append("; the amber rule is where form started slipping")
                }
                append(".")
            },
            points = series,
            xLabels = series.map { it.x.toInt().toString() },
            fixedRange = 0f..100f,
            target = QUALITY_BAR.toFloat(),
            changePointX = session.degradationOnsetRep?.toFloat(),
            trace = Tokens.SuccessInk,
            description = "Quality per rep. $scored of ${series.size} reps scored, " +
                "on a scale of 0 to 100." +
                (session.degradationOnsetRep?.let { " Form started slipping at rep $it." } ?: ""),
        )
    }

    // §6.18.5's attribution. Omitted entirely when there is no change point or no shift worth
    // reporting - an empty "what changed" heading tells the reader nothing, and unlike a missing
    // list there is nothing they could do about it.
    if (jointShifts.isNotEmpty()) {
        AttributionCard(
            title = "Where the change came from",
            caption = "Deviation from the reference recording, by joint.",
        ) {
            jointShifts.forEach { shift ->
                AttributionBar(
                    name = shift.displayName,
                    shiftZ = shift.shiftZ,
                    trustworthy = shift.trustworthy,
                    explanation = explain(shift),
                )
            }
        }
    }

    if (series.isEmpty() && jointShifts.isEmpty()) {
        RpCard {
            Text("Nothing to chart", style = Type.HeadlineSm, color = Tokens.TextPrimary)
            Text(
                "This set did not produce enough measurements to show how form changed across it.",
                style = Type.BodyMd,
                color = Tokens.TextSecondary,
            )
        }
    }
}

/** The one line under a bar: what the number means, in words the patient would use. */
private fun explain(shift: JointShift): String = when {
    !shift.trustworthy ->
        "Too few frames to judge this joint, so nothing is being claimed about it."
    shift.shiftZ > 0 ->
        "Drifted further from the reference as the set went on."
    else ->
        "Moved back toward the reference as the set went on."
}
