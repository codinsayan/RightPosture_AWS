package com.example.rightposture.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.catalog.demoExerciseById
import com.example.rightposture.core.rpose.HOLD_MODE
import com.example.rightposture.ui.components.NoteBanner
import com.example.rightposture.ui.components.EmptyState
import com.example.rightposture.ui.components.OutlinePill
import com.example.rightposture.ui.components.PatientTabs
import com.example.rightposture.ui.components.Pill
import com.example.rightposture.ui.components.PrimaryAction
import com.example.rightposture.ui.components.RoleTabBar
import com.example.rightposture.ui.components.RpCard
import com.example.rightposture.ui.components.RpTopBar
import com.example.rightposture.ui.components.SectionHeader
import com.example.rightposture.ui.theme.Radius
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens

/**
 * One row of §14.2's Today.
 *
 * Exists so Today can show two things that are not the same shape: 7.4's prescriptions, which come
 * from a physio with a rep and set count attached, and references recorded on this very phone,
 * which have neither. Both are startable, so both belong on the screen.
 */
data class TodayItem(
    val exerciseId: String,
    val exerciseVersion: Int,
    val name: String,
    val rposePath: String,
    /** `cyclic` or `hold`. What the row is tagged as, and how its prescription reads. */
    val mode: String,
    /** "3 x 10", or "3 x 30s hold"; null for something recorded on this device. */
    val prescription: String?,
    /** Set today, if one was. Null means not attempted today - never "attempted and scored zero". */
    val doneToday: TodayResult? = null,
)

/**
 * What a set finished today came to.
 *
 * [score] is nullable and that nullability is load-bearing: a set that ran but could not be scored
 * - fewer than six reps, the patient out of frame - has a result and no number, and §1's first
 * principle says the row must say so rather than print a zero.
 */
data class TodayResult(val score: Double?, val repsCompleted: Int, val repsPrescribed: Int?)

/**
 * §14.2's "Today" - the patient's landing screen.
 *
 * The screen has one job: start today's work with no navigation. So the first thing not yet done is
 * promoted out of the list into a card of its own carrying the setup instruction and the safety
 * note, and everything else is a compact row beneath it. A patient who opens the app and taps once
 * is in the right set.
 *
 * Anything recorded on this phone stays in its own group at the bottom. "Your physiotherapist asked
 * for this" and "this is on your phone" are different claims, and 7.1's airplane-mode gate depends
 * on the second still working with no account at all.
 */
@Composable
fun PatientTodayScreen(
    items: List<TodayItem>,
    linkedTo: String?,
    canLink: Boolean,
    onStart: (TodayItem) -> Unit,
    onOpenSession: (TodayItem) -> Unit,
    onEnterCode: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenMe: () -> Unit,
) {
    val prescribed = items.filter { it.prescription != null }
    val local = items.filter { it.prescription == null }
    // Once everything is done the hero does not vanish - it offers the first exercise again.
    // Finishing a set is a status, never a gate: how many times to repeat an exercise is the
    // patient's and their physiotherapist's decision, not the app's.
    val upNext = prescribed.firstOrNull { it.doneToday == null } ?: prescribed.firstOrNull()
    val remaining = prescribed.filter { it !== upNext }

    Scaffold(
        containerColor = Tokens.CanvasBg,
        bottomBar = {
            RoleTabBar(
                destinations = PatientTabs,
                selectedIndex = 0,
                onSelect = { index ->
                    when (index) {
                        1 -> onOpenProgress()
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
                    Text("Today's Plan", style = Type.HeadlineLg, color = Tokens.TextPrimary)
                    Text(
                        dayShape(prescribed, linkedTo),
                        style = Type.BodyMd,
                        color = Tokens.TextSecondary,
                    )
                }

                if (items.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Info,
                        message = if (canLink) {
                            "Nothing to do yet. Enter the code your physiotherapist gave you and " +
                                "your exercises will appear here."
                        } else {
                            "Nothing to do yet. Your physiotherapist records an exercise on this " +
                                "phone first, and it will appear here."
                        },
                        actionLabel = if (canLink) "Enter a code" else "Switch to physio mode",
                        onAction = if (canLink) onEnterCode else onOpenMe,
                    )
                }

                upNext?.let { UpNextCard(it, onStart) }

                if (remaining.isNotEmpty()) {
                    SectionHeader(
                        title = "Remaining prescriptions",
                        note = pendingNote(remaining),
                    )
                    remaining.forEach { item ->
                        ExerciseRow(item, onStart = onStart, onOpenSession = onOpenSession)
                    }
                }

                if (local.isNotEmpty()) {
                    SectionHeader(title = "On this phone", note = "Local recordings")
                    local.forEach { item ->
                        ExerciseRow(item, onStart = onStart, onOpenSession = onOpenSession)
                    }
                }

                if (canLink && linkedTo == null && items.isNotEmpty()) {
                    Text(
                        "Not linked to a physiotherapist. You can enter a code from Me at any time.",
                        style = Type.BodySm,
                        color = Tokens.TextSecondary,
                    )
                }

                Box(modifier = Modifier.padding(bottom = Space.xs))
            }
        }
    }
}

/**
 * The exercise to do next, promoted out of the list.
 *
 * It carries the two things a patient needs *before* starting rather than during: where to put the
 * phone, and what it is unsafe to keep doing. Both come from the catalogue, which is the only place
 * that knows them - a recording cannot contain "stop if the knee hurts rather than works".
 */
@Composable
private fun UpNextCard(item: TodayItem, onStart: (TodayItem) -> Unit) {
    val catalog = demoExerciseById(item.exerciseId)
    RpCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryPill(item.mode)
            item.prescription?.let { OutlinePill(it) }
        }
        Text(item.name, style = Type.HeadlineMd, color = Tokens.TextPrimary)

        catalog?.setup?.let { setup ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = Tokens.TagExerciseInk,
                    modifier = Modifier.size(18.dp).padding(top = 2.dp),
                )
                Text(setup, style = Type.BodyMd, color = Tokens.TextSecondary)
            }
        }

        catalog?.safety?.let { safety ->
            NoteBanner(text = safety, icon = Icons.Filled.Info)
        }

        item.doneToday?.let {
            Text(
                "Done today · ${resultLine(it).removePrefix("Completed · ")}. You can do it again.",
                style = Type.BodySm,
                color = Tokens.TextSecondary,
            )
        }

        PrimaryAction(
            label = if (item.doneToday == null) "Start session" else "Start another set",
            onClick = { onStart(item) },
            icon = Icons.Filled.PlayArrow,
            modifier = Modifier.semantics { contentDescription = "Start ${item.name}" },
        )
    }
}

/** A compact row: done today with its result, or waiting with a Start. */
@Composable
private fun ExerciseRow(
    item: TodayItem,
    onStart: (TodayItem) -> Unit,
    onOpenSession: (TodayItem) -> Unit,
) {
    val done = item.doneToday
    Surface(
        onClick = { if (done != null) onOpenSession(item) else onStart(item) },
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
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryPill(item.mode)
                    item.prescription?.let {
                        Text(it, style = Type.LabelMonoSm, color = Tokens.TextSecondary)
                    }
                }
                Text(item.name, style = Type.TitleMd, color = Tokens.TextPrimary)
                if (done != null) {
                    Text(
                        resultLine(done),
                        style = Type.BodySm,
                        color = if (done.score == null) Tokens.AbsentInk else Tokens.SuccessInk,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Done is a status, not a gate. The tick reports what happened; the Start beside it
                // stays because a patient may repeat an exercise as often as they want to.
                if (done != null) {
                    Box(
                        modifier = Modifier.size(36.dp).background(Tokens.SuccessWash, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Completed today",
                            tint = Tokens.SuccessInk,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Surface(
                    onClick = { onStart(item) },
                    shape = Radius.Pill,
                    color = Tokens.CanvasSubtle,
                    modifier = Modifier.semantics {
                        contentDescription = if (done == null) {
                            "Start ${item.name}"
                        } else {
                            "Start another set of ${item.name}"
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .padding(start = Space.md, end = 10.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (done == null) "Start" else "Again",
                            style = Type.TitleSm,
                            color = Tokens.TextPrimary,
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Tokens.TextPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryPill(mode: String) {
    val hold = mode == HOLD_MODE
    Pill(
        label = if (hold) "Hold" else "Exercise",
        ink = if (hold) Tokens.TagHoldInk else Tokens.TagExerciseInk,
        wash = if (hold) Tokens.TagHoldWash else Tokens.TagExerciseWash,
    )
}

/**
 * "3 exercises today · about 12 minutes · prescribed by Dr Chen".
 *
 * The estimate is deliberately coarse and hedged with "about", because it is arithmetic on a rep
 * count rather than a measurement of this patient. A precise-looking number that is wrong every
 * time reads as untrustworthy, which §10 says costs more than it gains.
 */
private fun dayShape(prescribed: List<TodayItem>, linkedTo: String?): String {
    if (prescribed.isEmpty()) {
        return linkedTo?.let { "Prescribed by $it" } ?: "Nothing prescribed yet"
    }
    val count = prescribed.size
    val minutes = (count * 4).coerceAtLeast(2)
    return buildString {
        append(if (count == 1) "1 exercise today" else "$count exercises today")
        append(" · about $minutes minutes")
        linkedTo?.let { append(" · prescribed by $it") }
    }
}

private fun pendingNote(remaining: List<TodayItem>): String? {
    val pending = remaining.count { it.doneToday == null }
    return if (pending == 0) "all done today" else "$pending still to do"
}

/** "10 of 12 reps · score 84", or the honest version when nothing could be scored. */
private fun resultLine(result: TodayResult): String {
    val reps = result.repsPrescribed
        ?.let { "${result.repsCompleted} of $it reps" }
        ?: "${result.repsCompleted} reps"
    val score = result.score?.let { "score %.0f".format(it) } ?: "not scored"
    return "Completed · $reps · $score"
}
