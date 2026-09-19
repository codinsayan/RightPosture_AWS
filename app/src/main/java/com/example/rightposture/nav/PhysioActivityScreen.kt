package com.example.rightposture.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.rightposture.data.SessionEntity
import com.example.rightposture.report.ExerciseProgression
import com.example.rightposture.sync.RemoteSession
import com.example.rightposture.ui.components.EmptyState
import com.example.rightposture.ui.components.PhysioTabs
import com.example.rightposture.ui.components.Pill
import com.example.rightposture.ui.components.RoleTabBar
import com.example.rightposture.ui.components.RpCard
import com.example.rightposture.ui.components.RpTopBar
import com.example.rightposture.ui.components.SectionHeader
import com.example.rightposture.ui.theme.Radius
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * §5.1's "Activity" - the physio home, and the thirty-second read.
 *
 * The physio opens this between patients to answer one question: who did what, and does anyone need
 * me? So the screen has exactly three sections in that order - what needs attention, what patients
 * did, and what was recorded on this handset - and nothing else.
 *
 * What used to be here and is not any more: the exercise library, the record button, the invite
 * button, the linked-patient roster and a stray "switch role". Seven sections on one scroll put the
 * day's actual reading below five full-width buttons and off the bottom of the screen, which was
 * reported in as "I cannot see any recent sets". They now live on the Patients, Library and Me tabs,
 * which is what the tab bar at the bottom is for.
 */
@Composable
fun PhysioActivityScreen(
    remoteSessions: List<RemoteSession>,
    localSessions: List<SessionEntity>,
    exerciseNames: Map<String, String>,
    progression: List<ExerciseProgression>,
    onOpenSession: (String) -> Unit,
    onOpenFlag: (String) -> Unit,
    onOpenPatients: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenMe: () -> Unit,
) {
    val flags = progression.filter { it.verdict.needsReview || it.verdict.readyToProgress }

    Scaffold(
        containerColor = Tokens.CanvasBg,
        bottomBar = {
            RoleTabBar(
                destinations = PhysioTabs,
                selectedIndex = 0,
                onSelect = { index ->
                    when (index) {
                        1 -> onOpenPatients()
                        2 -> onOpenLibrary()
                        3 -> onOpenMe()
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
            RpTopBar(subtitle = "Physio")

            Column(
                modifier = Modifier.padding(horizontal = Space.Gutter),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Activity", style = Type.HeadlineLg, color = Tokens.TextPrimary)
                    Text(
                        activityLine(flags.size, remoteSessions.size + localSessions.size),
                        style = Type.BodyMd,
                        color = Tokens.TextSecondary,
                    )
                }

                // The flag strip sits at the very top, above the feed, because a patient who needs
                // attention is the only thing on this screen that is time-sensitive.
                if (flags.isEmpty()) {
                    CalmLine()
                } else {
                    SectionHeader(
                        title = "Needs clinician action",
                        note = if (flags.size == 1) "1 flag" else "${flags.size} flags",
                    )
                    flags.forEach { FlagCard(it, onOpenFlag) }
                }

                if (remoteSessions.isEmpty() && localSessions.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Info,
                        message = "No sets yet. Invite a patient and read them the code, and " +
                            "everything they do will appear here.",
                        actionLabel = "Invite a patient",
                        onAction = onOpenPatients,
                    )
                }

                if (remoteSessions.isNotEmpty()) {
                    SectionHeader(title = "Recent activity", note = "newest first")
                    remoteSessions.forEach { session ->
                        RemoteSessionCard(session, exerciseNames)
                    }
                }

                // Sets recorded on this handset are a separate, clearly secondary claim. "A patient
                // did this" and "this happened on the demo phone" are different facts, and mixing
                // them would put a physio's own test set into a patient's record at a glance.
                if (localSessions.isNotEmpty()) {
                    SectionHeader(title = "On this phone", note = "local recordings")
                    localSessions.forEach { session ->
                        LocalSessionCard(session, exerciseNames, onOpenSession)
                    }
                }

                Box(modifier = Modifier.height(Space.xs))
            }
        }
    }
}

/** When nothing needs attention, say so once and calmly rather than showing an empty heading. */
@Composable
private fun CalmLine() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.SuccessWash, Radius.Card)
            .padding(Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Tokens.SuccessInk,
            modifier = Modifier.size(20.dp),
        )
        Text(
            "Nobody needs review right now.",
            style = Type.TitleSm,
            color = Tokens.SuccessInk,
        )
    }
}

/**
 * One flag, with the numbers behind it.
 *
 * 8.4's gate is that a flag shows its supporting data, so the reasons sit on the card rather than
 * behind a tap. A physio asked to advance a patient needs to see why without opening a database -
 * and tapping through goes to the evidence, not to a confirmation dialog.
 */
@Composable
private fun FlagCard(item: ExerciseProgression, onOpenFlag: (String) -> Unit) {
    val review = item.verdict.needsReview
    val ink = if (review) Tokens.DangerInk else Tokens.SuccessInk
    val wash = if (review) Tokens.DangerWash else Tokens.SuccessWash

    Surface(
        onClick = { onOpenFlag(item.exerciseId) },
        shape = Radius.Card,
        color = Tokens.SurfaceCard,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // A coloured edge rather than a coloured card: the reasons underneath have to stay
            // readable, and red text on a red field is the first thing to go.
            Box(modifier = Modifier.width(5.dp).fillMaxHeight().background(ink))
            Column(
                modifier = Modifier.padding(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pill(
                        label = if (review) "Needs review" else "Ready to progress",
                        ink = ink,
                        wash = wash,
                        icon = if (review) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Tokens.TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(item.exerciseName, style = Type.HeadlineSm, color = Tokens.TextPrimary)
                item.verdict.reasons.forEach { reason ->
                    Text(reason, style = Type.BodyMd, color = Tokens.TextSecondary)
                }
                Text(
                    "The app flags; you decide.",
                    style = Type.LabelMonoSm,
                    color = Tokens.TextMuted,
                )
            }
        }
    }
}

/** A patient's set, pulled from the server. */
@Composable
private fun RemoteSessionCard(session: RemoteSession, exerciseNames: Map<String, String>) {
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
            session.degradationOnsetRep != null -> "Form slipped at rep ${session.degradationOnsetRep}"
            else -> null
        },
        onClick = null,
    )
}

/** A set performed on this handset. */
@Composable
private fun LocalSessionCard(
    session: SessionEntity,
    exerciseNames: Map<String, String>,
    onOpenSession: (String) -> Unit,
) {
    SessionCardShell(
        title = session.exerciseId?.let { exerciseNames[it] ?: it } ?: "Unnamed exercise",
        subtitle = LOCAL_DATE.format(Date(session.startedAt)),
        score = session.setScore,
        warning = when {
            session.endedAt == null -> "Interrupted"
            session.degradationOnsetRep != null -> "Form slipped at rep ${session.degradationOnsetRep}"
            else -> null
        },
        onClick = { onOpenSession(session.id) },
    )
}

/** §8.3's session row: one shape used by every list of sessions in the app. */
@Composable
internal fun SessionCardShell(
    title: String,
    subtitle: String,
    score: Double?,
    warning: String?,
    onClick: (() -> Unit)?,
) {
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Space.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = Type.TitleMd, color = Tokens.TextPrimary)
                Text(subtitle, style = Type.BodySm, color = Tokens.TextSecondary)
                warning?.let {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Tokens.WarningInk,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(it, style = Type.BodySm, color = Tokens.WarningInk)
                    }
                }
            }
            ScoreBlock(score)
            if (onClick != null) {
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Tokens.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = Radius.Card,
            color = Tokens.SurfaceCard,
            modifier = Modifier.fillMaxWidth(),
        ) { body() }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Tokens.SurfaceCard, Radius.Card),
        ) { body() }
    }
}

@Composable
internal fun ScoreBlock(score: Double?) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            score?.let { "%.0f".format(it) } ?: "—",
            style = Type.HeadlineSm,
            color = scoreInk(score),
        )
        Text(
            if (score == null) "not scored" else "score",
            style = Type.LabelMonoSm,
            color = Tokens.TextMuted,
        )
    }
}

internal fun scoreInk(score: Double?): Color = when {
    score == null -> Tokens.AbsentInk
    score >= 80 -> Tokens.SuccessInk
    score >= 60 -> Tokens.WarningInk
    else -> Tokens.DangerInk
}

private fun activityLine(flags: Int, sets: Int): String = buildString {
    append(if (sets == 1) "1 set recorded" else "$sets sets recorded")
    if (flags > 0) append(" · $flags needing a look")
}

internal val LOCAL_DATE = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())

/**
 * A server timestamp, read the way the physio's own rows are.
 *
 * The server speaks ISO-8601 in UTC; the physio is standing in a room in a timezone. Printing the
 * raw string put "2026-09-05T20:54" beside "Sat 5 Sep · 20:54" on the same screen, which made two
 * identically-shaped facts look like they came from different systems - and left the reader doing
 * the timezone arithmetic. An unparseable value falls back to the first sixteen characters rather
 * than to nothing: a slightly ugly date is more use than a blank.
 */
internal fun readableInstant(iso: String): String = runCatching {
    LOCAL_DATE.format(ISO_UTC.parse(iso.take(19))!!)
}.getOrElse { iso.take(16).replace('T', ' ') }

internal val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
    timeZone = java.util.TimeZone.getTimeZone("UTC")
}
