package com.example.rightposture.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.rpose.HOLD_MODE
import com.example.rightposture.core.rpose.readRpose
import com.example.rightposture.data.ExerciseEntity
import com.example.rightposture.report.displayNameOf
import com.example.rightposture.ui.components.NoteBanner
import com.example.rightposture.ui.components.PhysioTabs
import com.example.rightposture.ui.components.Pill
import com.example.rightposture.ui.components.PrimaryAction
import com.example.rightposture.ui.components.RoleTabBar
import com.example.rightposture.ui.components.RpCard
import com.example.rightposture.ui.components.RpTopBar
import com.example.rightposture.ui.components.SectionHeader
import com.example.rightposture.ui.prescriptionLine
import com.example.rightposture.ui.theme.Radius
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * §5.4's "Library": the references this physio has recorded, and what each one actually measures.
 *
 * The list used to be a name, a category pill and a version number. That is enough to *find* a
 * reference and not nearly enough to *trust* one, which is the question this screen exists to
 * answer: every set any patient ever performs is scored against one of these recordings, so a
 * physio prescribing from here is choosing the standard, not just the exercise.
 *
 * So each card now says what the recording contains - the prescription it carries, and which joints
 * §6.10 decided to watch. Those come out of the `.rpose` bundle on disk rather than from anything
 * new: the bundle is already read to perform a reference, and reading it to describe one adds no
 * source of truth.
 */
@Composable
fun PhysioLibraryScreen(
    exercises: List<ExerciseEntity>,
    onRecordNew: () -> Unit,
    onTestExercise: (ExerciseEntity) -> Unit,
    onOpenActivity: () -> Unit,
    onOpenPatients: () -> Unit,
    onOpenMe: () -> Unit,
) {
    // §9.6: a reference is (id, version), and every version stays on disk because older sessions
    // were scored against them. The list is about *exercises* though, so each id appears once, at
    // its newest version, and says how many versions stand behind it.
    val families = remember(exercises) {
        exercises.groupBy { it.id }
            .map { (_, versions) -> versions.maxBy { it.version } to versions.size }
            .sortedByDescending { it.first.createdAt }
    }
    val holds = families.count { it.first.mode == HOLD_MODE }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }

    val details = rememberReferenceDetails(families.map { it.first })

    val shown = families.filter { (exercise, _) ->
        when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.EXERCISES -> exercise.mode != HOLD_MODE
            LibraryFilter.HOLDS -> exercise.mode == HOLD_MODE
        }
    }

    Scaffold(
        containerColor = Tokens.CanvasBg,
        bottomBar = {
            RoleTabBar(
                destinations = PhysioTabs,
                selectedIndex = 2,
                onSelect = { index ->
                    when (index) {
                        0 -> onOpenActivity()
                        1 -> onOpenPatients()
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
                    Text("Library", style = Type.HeadlineLg, color = Tokens.TextPrimary)
                    Text(
                        libraryLine(families.size, holds),
                        style = Type.BodyMd,
                        color = Tokens.TextSecondary,
                    )
                }

                if (families.isEmpty()) {
                    EmptyLibrary(onRecordNew)
                    Box(modifier = Modifier.height(Space.xs))
                    return@Column
                }

                WhatAReferenceIs()

                PrimaryAction(
                    label = "Record a new exercise",
                    onClick = onRecordNew,
                    icon = Icons.Filled.Add,
                )

                if (holds > 0 && families.size > holds) {
                    LibraryFilters(
                        filter = filter,
                        all = families.size,
                        exercises = families.size - holds,
                        holds = holds,
                        onPick = { filter = it },
                    )
                }

                SectionHeader(title = "References", note = "newest first")
                shown.forEach { (exercise, versions) ->
                    ReferenceCard(
                        exercise = exercise,
                        versions = versions,
                        detail = details[exercise.key()],
                        onTest = { onTestExercise(exercise) },
                    )
                }

                Box(modifier = Modifier.height(Space.xs))
            }
        }
    }
}

/**
 * The one sentence that makes the rest of the screen mean something.
 *
 * Without it a reference reads as a saved video. It is the tolerance band every patient set is
 * measured against, and a physio who records a sloppy one has quietly changed what "good" means for
 * everybody they prescribe it to.
 */
@Composable
private fun WhatAReferenceIs() {
    RpCard(background = Tokens.SurfaceCardWarm, padding = Space.md) {
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
                "These recordings are the standard",
                style = Type.TitleMd,
                color = Tokens.TextPrimary,
            )
        }
        Text(
            "Every set a patient performs is scored on this phone against one of these, joint by " +
                "joint. Recording the movement the way you want it done is the whole of the " +
                "calibration - there is no second place to correct it afterwards.",
            style = Type.BodySm,
            color = Tokens.TextSecondary,
        )
    }
}

@Composable
private fun LibraryFilters(
    filter: LibraryFilter,
    all: Int,
    exercises: Int,
    holds: Int,
    onPick: (LibraryFilter) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        FilterChip("All", all, filter == LibraryFilter.ALL) { onPick(LibraryFilter.ALL) }
        FilterChip("Exercises", exercises, filter == LibraryFilter.EXERCISES) {
            onPick(LibraryFilter.EXERCISES)
        }
        FilterChip("Holds", holds, filter == LibraryFilter.HOLDS) { onPick(LibraryFilter.HOLDS) }
    }
}

@Composable
private fun FilterChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = Radius.Pill,
        color = if (selected) Tokens.PrimaryContainer else Tokens.SurfaceCardWarm,
        modifier = Modifier.semantics { contentDescription = "$label, $count" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.sm, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = Type.TitleSm,
                color = if (selected) Tokens.OnPrimary else Tokens.TextSecondary,
            )
            Text(
                "$count",
                style = Type.LabelMonoSm,
                color = if (selected) Tokens.OnPrimary else Tokens.TextMuted,
            )
        }
    }
}

/**
 * One reference, described well enough to prescribe from.
 *
 * Tapping the card performs it. The physio decided what "correct" looks like, so they are the only
 * one who can tell whether the recording actually scores that way - and every future session is
 * measured against it, so finding out after prescribing is finding out too late.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReferenceCard(
    exercise: ExerciseEntity,
    versions: Int,
    detail: ReferenceDetail?,
    onTest: () -> Unit,
) {
    val hold = exercise.mode == HOLD_MODE
    RpCard(padding = Space.md) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(
                label = if (hold) "Hold" else "Exercise",
                ink = if (hold) Tokens.TagHoldInk else Tokens.TagExerciseInk,
                wash = if (hold) Tokens.TagHoldWash else Tokens.TagExerciseWash,
            )
            Text("v${exercise.version}", style = Type.LabelMonoSm, color = Tokens.TextSecondary)
            if (versions > 1) {
                // §9.6 again, from the other side: the older versions have not been replaced, they
                // are still what the sessions recorded against them are scored on.
                Text(
                    "$versions versions kept",
                    style = Type.LabelMonoSm,
                    color = Tokens.TextMuted,
                )
            }
        }

        Text(exercise.name, style = Type.HeadlineSm, color = Tokens.TextPrimary)
        Text(
            buildString {
                append("Recorded ${RECORDED_ON.format(Date(exercise.createdAt))}")
                detail?.createdBy?.takeIf { it.isNotBlank() }?.let { append(" · by $it") }
            },
            style = Type.BodySm,
            color = Tokens.TextSecondary,
        )

        when {
            detail == null -> Text(
                "Reading the recording…",
                style = Type.BodySm,
                color = Tokens.TextMuted,
            )

            // §8's row: a reference that will not open cannot be prescribed against, and the only
            // repair is a new recording. Saying so here is cheaper than the patient finding out
            // when Start does nothing.
            !detail.readable -> NoteBanner(
                text = "This recording could not be opened on this phone. Record a new version " +
                    "before prescribing it.",
                ink = Tokens.DangerInk,
                wash = Tokens.DangerWash,
                icon = Icons.Filled.Warning,
            )

            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    SpecTile(
                        label = "Prescribed by default",
                        value = prescriptionLine(
                            mode = exercise.mode,
                            sets = detail.sets,
                            reps = detail.reps,
                            holdSeconds = exercise.holdSeconds ?: detail.holdSeconds,
                        ),
                        note = "${detail.restSeconds}s rest",
                        modifier = Modifier.weight(1f),
                    )
                    SpecTile(
                        label = "Joints watched",
                        value = "${detail.watched.size}",
                        note = "chosen by §6.10",
                        modifier = Modifier.weight(1f),
                    )
                }

                if (detail.watched.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        detail.watched.take(4).forEach { joint ->
                            Text(
                                joint,
                                style = Type.LabelMonoSm,
                                color = Tokens.TextSecondary,
                                modifier = Modifier
                                    .background(Tokens.CanvasSubtle, Radius.Pill)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                        if (detail.watched.size > 4) {
                            Text(
                                "+${detail.watched.size - 4} more",
                                style = Type.LabelMonoSm,
                                color = Tokens.TextMuted,
                                modifier = Modifier.padding(vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        }

        Surface(
            onClick = onTest,
            shape = Radius.Pill,
            color = Tokens.SurfaceCardWarm,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Test ${exercise.name}"
            },
        ) {
            Row(
                modifier = Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Tokens.TextPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Text("Test this reference", style = Type.TitleSm, color = Tokens.TextPrimary)
            }
        }
    }
}

@Composable
private fun SpecTile(
    label: String,
    value: String,
    note: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Tokens.CanvasSubtle, Radius.CardSmall)
            .padding(Space.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = Type.LabelMonoSm, color = Tokens.TextMuted)
        Text(value, style = Type.TitleMd, color = Tokens.TextPrimary)
        Text(note, style = Type.BodySm, color = Tokens.TextSecondary)
    }
}

@Composable
private fun EmptyLibrary(onRecordNew: () -> Unit) {
    RpCard(background = Tokens.SurfaceCardWarm) {
        Text("Nothing recorded yet", style = Type.HeadlineSm, color = Tokens.TextSecondary)
        Text(
            "A recording becomes the reference every session is scored against, so it is worth " +
                "doing the movement the way you want it done. Nothing can be prescribed until one " +
                "exists.",
            style = Type.BodyMd,
            color = Tokens.TextSecondary,
        )
        PrimaryAction(
            label = "Record a new exercise",
            onClick = onRecordNew,
            icon = Icons.Filled.Add,
        )
    }
}

// ------------------------------------------------------------------------ Bundle reads

/** What a card says about a recording, once its bundle has been opened. */
internal data class ReferenceDetail(
    val sets: Int,
    val reps: Int,
    val restSeconds: Int,
    val holdSeconds: Int?,
    /** §6.10's watch list, in the physio's words rather than feature ids. */
    val watched: List<String>,
    val createdBy: String,
    /** False when the file would not open, which is a state the physio has to be told about. */
    val readable: Boolean,
)

/**
 * Opens each reference once and keeps what the cards quote.
 *
 * On the IO dispatcher and after first paint, so a library of twenty recordings does not hold the
 * screen. The card renders its name, category and version immediately - those are on the database
 * row - and fills in the prescription and joints when the file has been read. Nothing here is
 * cached across navigations because nothing here is expensive enough to be worth going stale.
 */
@Composable
private fun rememberReferenceDetails(
    exercises: List<ExerciseEntity>,
): Map<String, ReferenceDetail> {
    var details by remember { mutableStateOf<Map<String, ReferenceDetail>>(emptyMap()) }

    LaunchedEffect(exercises) {
        details = withContext(Dispatchers.IO) {
            exercises.associate { exercise -> exercise.key() to detailOf(exercise) }
        }
    }

    return details
}

private fun detailOf(exercise: ExerciseEntity): ReferenceDetail {
    val bundle = runCatching {
        File(exercise.rposePath).inputStream().use { readRpose(it) }
    }.getOrNull() ?: return ReferenceDetail(
        sets = 0,
        reps = 0,
        restSeconds = 0,
        holdSeconds = null,
        watched = emptyList(),
        createdBy = "",
        readable = false,
    )

    val prescription = bundle.manifest.prescription
    return ReferenceDetail(
        sets = prescription.sets,
        reps = prescription.reps,
        restSeconds = prescription.restSeconds,
        holdSeconds = prescription.holdSeconds,
        // Ordered by §6.10's own weighting, so the first joint named is the one that moves the
        // score most - which is the one a physio checking a recording should look at first.
        watched = bundle.watchList.features
            .sortedByDescending { it.weight }
            .map { jointLabel(it.id) },
        createdBy = bundle.manifest.createdBy,
        readable = true,
    )
}

/**
 * A watched feature, named for the person calibrating rather than the person exercising.
 *
 * [displayNameOf] drops the active/passive distinction on purpose - a patient reading "hip flex
 * passive" learns nothing they can act on. A physio reading a watch list does: the two are
 * different joints on different legs, and collapsing them printed "Hip flex" twice on the same
 * card, which reads as a rendering fault rather than as two features.
 */
private fun jointLabel(id: FeatureId): String {
    val base = displayNameOf(id.value)
    return if (id.value.endsWith("_passive")) "$base (other side)" else base
}

private fun ExerciseEntity.key(): String = "$id@$version"

private enum class LibraryFilter { ALL, EXERCISES, HOLDS }

private fun libraryLine(references: Int, holds: Int): String = buildString {
    append(if (references == 1) "1 reference" else "$references references")
    if (holds > 0) append(" · $holds ${if (holds == 1) "hold" else "holds"}")
}

private val RECORDED_ON = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
