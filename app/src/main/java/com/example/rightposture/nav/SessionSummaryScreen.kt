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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
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
import com.example.rightposture.data.RepEntity
import com.example.rightposture.data.RightPostureDatabase
import com.example.rightposture.data.SessionEntity
import com.example.rightposture.llm.LlmBackend
import com.example.rightposture.llm.OnDeviceLlm
import com.example.rightposture.report.AnalyticsData
import com.example.rightposture.ui.components.NoteBanner
import com.example.rightposture.ui.components.Pill
import com.example.rightposture.ui.components.PrimaryAction
import com.example.rightposture.ui.components.RpCard
import com.example.rightposture.ui.components.TrendChartCard
import com.example.rightposture.ui.theme.Radius
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Everything §4.2 needs about the set that just finished.
 *
 * [setIndex] and [setsPrescribed] are derived rather than stored: nothing in the schema records
 * that a session was "set 2 of 3", so the index is how many sets of this exercise were finished
 * today. That is an inference, and it is only made when there is a prescription to count against -
 * with no assignment there is no "of 3", so the screen says nothing about sets at all.
 */
data class SessionSummaryData(
    val session: SessionEntity,
    val exerciseName: String,
    val repsCounted: Int,
    val setIndex: Int?,
    val setsPrescribed: Int?,
    val restSeconds: Int?,
    val analytics: AnalyticsData?,
)

/**
 * §4.2's post-set summary: the emotional payoff screen.
 *
 * Reached automatically when a set ends, never by a tap the patient has to find, and it is the last
 * thing a tired person reads before putting the phone down. So the order is fixed: what you did,
 * what it came to, what to do next - and only then the trend.
 *
 * The set screen is gone from the stack by the time this appears. There is no way back into a
 * finished set, deliberately: the only exits are on to the next set, deeper into the detail, or
 * home.
 */
@Composable
fun SessionSummaryScreen(
    data: SessionSummaryData?,
    /** True until the set has been read back off disk. See the note in the body. */
    loading: Boolean,
    database: RightPostureDatabase,
    llm: OnDeviceLlm?,
    backend: LlmBackend?,
    onStartNextSet: () -> Unit,
    onOpenDetail: () -> Unit,
    onDone: () -> Unit,
) {
    if (data == null) {
        // Null means two different things and they must not be confused. While [loading] is true the
        // row is still being read off disk, and leaving now would cancel that read - which is
        // exactly what happened the first time this screen shipped: the effect that loads the set
        // and the effect that gives up on it fought each other in a loop.
        if (loading) {
            LoadingSummary()
        } else {
            // Genuinely not there: written, but unreadable. Going home beats a blank screen, and
            // the set is still on disk for the history to find.
            LaunchedEffect(Unit) { onDone() }
        }
        return
    }

    var reps by remember(data.session.id) { mutableStateOf<List<RepEntity>>(emptyList()) }
    LaunchedEffect(data.session.id) {
        reps = withContext(Dispatchers.IO) { database.sessions().reps(data.session.id) }
    }
    val summaryState by rememberGeneratedSummary(data.session, reps, database, llm)

    val moreSetsToDo = data.setIndex != null && data.setsPrescribed != null &&
        data.setIndex < data.setsPrescribed

    Scaffold(containerColor = Tokens.CanvasBg) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.Gutter),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            SummaryTopRow(data, onDone)

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Set summary", style = Type.HeadlineLg, color = Tokens.TextPrimary)
                Text(data.exerciseName, style = Type.BodyMd, color = Tokens.TextSecondary)
            }

            HeroResult(data)

            if (data.session.endedAt == null) {
                NoteBanner(
                    "This set was interrupted. Everything you did was still saved.",
                    ink = Tokens.WarningInk,
                    wash = Tokens.WarningWash,
                )
            }

            WrittenSummary(summaryState, backend, data)

            data.session.degradationOnsetRep?.let { onset ->
                NoteBanner("Form started slipping at rep $onset.", ink = Tokens.WarningInk)
            }

            if (moreSetsToDo) {
                RestAndUpNext(data, onStartNextSet)
            }

            data.analytics?.let { Trends(it) }

            PrimaryAction(
                label = if (moreSetsToDo) "Start next set" else "Back to today",
                onClick = if (moreSetsToDo) onStartNextSet else onDone,
                icon = if (moreSetsToDo) Icons.Filled.PlayArrow else Icons.Filled.Check,
            )

            Surface(
                onClick = onOpenDetail,
                color = Tokens.CanvasBg,
                shape = Radius.Pill,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(vertical = Space.sm),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "View full session breakdown",
                        style = Type.TitleSm,
                        color = Tokens.TextSecondary,
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Tokens.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Box(modifier = Modifier.height(Space.xs))
        }
    }
}

/** The half-second before the set has been read back. Says what it is doing, and nothing else. */
@Composable
private fun LoadingSummary() {
    Scaffold(containerColor = Tokens.CanvasBg) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Space.Gutter),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text("Set summary", style = Type.HeadlineLg, color = Tokens.TextPrimary)
            Text("Adding up your set…", style = Type.BodyMd, color = Tokens.TextSecondary)
        }
    }
}

@Composable
private fun SummaryTopRow(data: SessionSummaryData, onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onDone,
            shape = CircleShape,
            color = Tokens.SurfaceCardWarm,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = "Done",
                    tint = Tokens.TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (data.setIndex != null && data.setsPrescribed != null) {
            Pill(
                label = "Set ${data.setIndex} of ${data.setsPrescribed} complete",
                ink = Tokens.SuccessInk,
                wash = Tokens.SuccessWash,
            )
        }
        Box(modifier = Modifier.size(44.dp))
    }
}

/**
 * The two numbers the set came to.
 *
 * The quality card is where §1's first principle earns its keep. A null set score means the set
 * could not be judged - typically fewer than the six reps §6 needs - and it renders as a dash with
 * the reason written out, on a grey bar that is empty rather than at the floor. A zero here would
 * tell a patient they did badly when the truth is that nothing was measured.
 */
@Composable
private fun HeroResult(data: SessionSummaryData) {
    val prescribed = data.session.prescribedReps
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        RpCard(modifier = Modifier.weight(1f), padding = Space.md) {
            Text("Reps counted", style = Type.TitleSm, color = Tokens.TextSecondary)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${data.repsCounted}",
                    style = Type.HeadlineLg,
                    color = Tokens.TextPrimary,
                )
                if (prescribed > 0) {
                    Text(
                        " / $prescribed",
                        style = Type.BodyMd,
                        color = Tokens.TextSecondary,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            if (prescribed > 0) {
                val percent = (data.repsCounted * 100 / prescribed)
                Text(
                    "$percent% target volume",
                    style = Type.LabelMonoSm,
                    color = if (percent >= 80) Tokens.SuccessInk else Tokens.TextSecondary,
                )
            }
        }

        RpCard(modifier = Modifier.weight(1f), padding = Space.md) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text("Set quality", style = Type.TitleSm, color = Tokens.TextSecondary)
            }
            val score = data.session.setScore
            if (score == null) {
                Text("—", style = Type.HeadlineLg, color = Tokens.AbsentInk)
                Text(
                    "Not scored",
                    style = Type.LabelMonoSm,
                    color = Tokens.AbsentInk,
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "%.0f".format(score),
                        style = Type.HeadlineLg,
                        color = if (score >= QUALITY_BAR) Tokens.SuccessInk else Tokens.WarningInk,
                    )
                    Text(
                        " / 100",
                        style = Type.BodyMd,
                        color = Tokens.TextSecondary,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                QualityBar(score.toFloat())
            }
        }
    }

    if (data.session.setScore == null) {
        Text(
            "There were not enough scoreable reps in this set to judge it. That is a gap in the " +
                "measurement, not a mark against you.",
            style = Type.BodySm,
            color = Tokens.TextSecondary,
        )
    }
}

@Composable
private fun QualityBar(score: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(Tokens.CanvasSubtle, RoundedCornerShape(999.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = (score / 100f).coerceIn(0f, 1f))
                .height(8.dp)
                .background(
                    if (score >= QUALITY_BAR) Tokens.SuccessInk else Tokens.WarningInk,
                    RoundedCornerShape(999.dp),
                ),
        )
    }
}

/** The generated paragraph, with its measurements emphasised and its origin named. */
@Composable
private fun WrittenSummary(
    state: SummaryState,
    backend: LlmBackend?,
    data: SessionSummaryData,
) {
    GeneratedSummaryCard(
        title = "What happened in this set",
        state = state,
        backend = backend,
        writingLabel = "Writing your summary on this phone…",
        emptyLabel = fallbackLine(data),
    )
}

/**
 * The rest countdown, and what it is counting down to.
 *
 * The timer is presentation only - nothing enforces it, and starting the next set early is a tap
 * away. §10 forbids telling anyone to push through anything, and a rest that blocked the button
 * would be doing exactly that.
 */
@Composable
private fun RestAndUpNext(data: SessionSummaryData, onStartNextSet: () -> Unit) {
    val total = data.restSeconds ?: 60
    var remaining by remember(data.session.id) { mutableIntStateOf(total) }
    LaunchedEffect(data.session.id) {
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
    }

    RpCard(background = Tokens.SurfaceCardWarm) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (remaining > 0) "Resting" else "Ready when you are",
                style = Type.TitleMd,
                color = Tokens.TextPrimary,
            )
            Text(
                "%d:%02d".format(remaining / 60, remaining % 60),
                style = Type.LabelMonoSm,
                color = Tokens.TextSecondary,
                modifier = Modifier
                    .background(Tokens.SurfaceCard, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Up next", style = Type.BodySm, color = Tokens.TextSecondary)
                Text(data.exerciseName, style = Type.TitleMd, color = Tokens.TextPrimary)
                Text(
                    "Set ${(data.setIndex ?: 0) + 1} of ${data.setsPrescribed}" +
                        (data.session.prescribedReps.takeIf { it > 0 }
                            ?.let { " · $it reps" } ?: ""),
                    style = Type.BodySm,
                    color = Tokens.TextSecondary,
                )
            }
            Surface(
                onClick = onStartNextSet,
                shape = CircleShape,
                color = Tokens.SurfaceCard,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.ArrowForward,
                        contentDescription = "Start the next set",
                        tint = Tokens.PrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * The two trends, drawn only where there is real history to draw.
 *
 * A single session produces a one-point chart, which says nothing and looks broken; the charts wait
 * until there are at least two sets to compare. Sessions that could not be judged appear as hollow
 * markers rather than being dropped, so the gap in the record stays visible.
 */
@Composable
private fun Trends(analytics: AnalyticsData) {
    if (analytics.quality.count { it.y != null } < 2) return

    val labels = analytics.quality.indices.map { index ->
        if (index == analytics.quality.lastIndex) "Latest" else "Set ${index + 1}"
    }

    TrendChartCard(
        title = "Quality per set",
        note = "Target: ${QUALITY_BAR.toInt()}",
        caption = "Your quality score across recent sets, on a fixed 0 to 100 scale.",
        points = analytics.quality,
        xLabels = labels,
        fixedRange = 0f..100f,
        target = QUALITY_BAR.toFloat(),
        trace = Tokens.Secondary,
        description = "Quality per set. ${analytics.judgedSessions} of " +
            "${analytics.quality.size} sets could be scored, on a scale of 0 to 100.",
    )

    val peak = analytics.romPeak.mapNotNull { it.y }.maxOrNull()
    TrendChartCard(
        title = "Range per set",
        note = peak?.let { "Peak: %.0f°".format(it) },
        caption = "Your best three reps in each set, in degrees.",
        points = analytics.romPeak,
        xLabels = labels,
        trace = Tokens.SuccessInk,
        description = "Range per set, in degrees. Higher is further through the movement.",
    )
}

/**
 * What the card says when the model produced nothing.
 *
 * Only the facts already on the screen, restated as a sentence. It is not an apology and it is not a
 * blank: §1's fourth principle wants every state to say something true.
 */
private fun fallbackLine(data: SessionSummaryData): String = buildString {
    append("You finished ${data.repsCounted}")
    if (data.session.prescribedReps > 0) append(" of ${data.session.prescribedReps}")
    append(if (data.repsCounted == 1) " rep." else " reps.")
    data.session.setScore?.let { append(" This set scored %.0f out of 100.".format(it)) }
        ?: append(" There was not enough to score this set.")
}
