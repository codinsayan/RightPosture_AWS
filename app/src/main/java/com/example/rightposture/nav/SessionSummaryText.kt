package com.example.rightposture.nav

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import com.example.rightposture.core.summary.GeneratedSummary
import com.example.rightposture.core.summary.SummarySource
import com.example.rightposture.core.summary.ProgressFacts
import com.example.rightposture.core.summary.generateProgressSummary
import com.example.rightposture.core.summary.generateSummary
import com.example.rightposture.data.RepEntity
import com.example.rightposture.data.RightPostureDatabase
import com.example.rightposture.data.SessionEntity
import com.example.rightposture.llm.LlmBackend
import com.example.rightposture.llm.OnDeviceLlm
import com.example.rightposture.core.summary.numeralRanges
import com.example.rightposture.report.summaryFactsOf
import androidx.compose.material3.Text
import com.example.rightposture.ui.components.RpCard
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the summary paragraph is doing right now: still being written, or done.
 *
 * [partial] is the model's own output as it arrives, already passed through the live guard, so a
 * screen can show the paragraph being written rather than a spinner. It is deliberately separate
 * from [summary]: the partial text has only had the cheap half of the rules applied to it, and only
 * a [summary] has been through the full check. Nothing should ever promote one to the other.
 */
data class SummaryState(
    val summary: GeneratedSummary? = null,
    val writing: Boolean = false,
    val partial: String = "",
)

/**
 * §10.7's written summary, generated once per session.
 *
 * Shared by the post-set summary (§4.2) and the session detail's Summary tab (§4.3) so the two
 * cannot phrase the same set differently - which they would eventually, if each kept its own copy
 * of the fact-gathering.
 *
 * Deliberately non-blocking. Every fact the paragraph phrases is already on screen beside it, so a
 * model that never answers costs the patient a paragraph rather than the whole screen.
 */
@Composable
fun rememberGeneratedSummary(
    session: SessionEntity?,
    reps: List<RepEntity>,
    database: RightPostureDatabase,
    llm: OnDeviceLlm?,
): State<SummaryState> {
    val state = remember(session?.id) { mutableStateOf(SummaryState()) }

    LaunchedEffect(session?.id, reps) {
        val current = session ?: return@LaunchedEffect
        if (reps.isEmpty() || state.value.summary != null) return@LaunchedEffect
        state.value = SummaryState(writing = true)
        val facts = withContext(Dispatchers.IO) {
            summaryFactsOf(
                // The name the physio typed. Feeding the derived id in would put "rp_hands_on_3"
                // into a sentence written for a patient.
                exerciseName = current.exerciseId
                    ?.let { id -> database.exercises().latestVersionOf(id)?.name }
                    ?: "this exercise",
                session = current,
                reps = reps,
                samples = withContext(Dispatchers.IO) {
                    database.sessions().allFeatureSamples(current.id)
                },
                cues = database.events().cues(current.id),
            )
        }
        val generated = generateSummary(
            facts = facts,
            generator = llm,
            // Snapshot state is safe to write from the generation thread; the recomposition it
            // schedules is what puts each new word on screen.
            onPartial = { partial -> state.value = state.value.copy(partial = partial) },
            // One attempt. See [generateSummary]: a streamed retry makes the reader watch the
            // app change its mind, which costs more than the better wording would have gained.
            retryOnRejection = false,
        )
        generated.rejections.forEach { Log.i("RPSummary", "Draft refused: $it") }
        state.value = SummaryState(summary = generated, writing = false)
    }

    return state
}

/**
 * §10.7's written summary of a *run* of sessions, for §4.4's Progress and §5.3's patient detail.
 *
 * Non-blocking for the same reason the per-set one is: every number the paragraph phrases is on
 * screen beside it in a tile or a chart, so a model that never answers costs a paragraph rather
 * than the screen. Keyed on the facts themselves, so switching exercise or patient re-asks rather
 * than leaving the previous subject's prose above the new subject's charts - which would be a wrong
 * clinical reading, not a flicker.
 */
@Composable
fun rememberProgressSummary(
    facts: ProgressFacts?,
    llm: OnDeviceLlm?,
): State<SummaryState> {
    val state = remember(facts) { mutableStateOf(SummaryState()) }

    LaunchedEffect(facts) {
        val sheet = facts ?: return@LaunchedEffect
        state.value = SummaryState(writing = true)
        val summary = withContext(Dispatchers.Default) {
            generateProgressSummary(
                facts = sheet,
                generator = llm,
                onPartial = { partial -> state.value = state.value.copy(partial = partial) },
                retryOnRejection = false,
            )
        }
        summary.rejections.forEach { Log.i("RPSummary", "Draft refused: $it") }
        state.value = SummaryState(summary = summary, writing = false)
    }

    return state
}

/**
 * Where the paragraph came from.
 *
 * Shown rather than hidden: §11.1's rubric is scored on demonstrable NPU use, and a physio reading a
 * summary should be able to tell that a model wrote it from a template standing in.
 */
fun attributionOf(summary: GeneratedSummary, backend: LlmBackend?): String = when (summary.source) {
    SummarySource.MODEL,
    SummarySource.MODEL_RETRY,
    -> "Written on this phone by Gemma" + (backend?.let { " on the ${it.name}" } ?: "")
    SummarySource.TEMPLATE -> "Summary from measurements only"
}

/**
 * Marks the measurements in a generated summary, the way a reader would with a highlighter pen.
 *
 * Safe because of what happens upstream, not because of anything here: the validator rejects any
 * digit run that is not in the fact sheet, so every numeral in a summary that reached this screen is
 * a number we computed. [numeralRanges] uses that same regex, so the two cannot drift apart.
 *
 * A pale yellow wash behind the numeral rather than a coloured numeral. The numbers are the part a
 * reader checks against the charts beside the paragraph, and recoloured digits inside running prose
 * are easy to read straight past - a highlight is found at a glance and survives being printed or
 * screenshotted in greyscale. The weight is kept as the second signal, so the emphasis does not
 * depend on colour alone. `SpanStyle` leaves the text itself unchanged for a screen reader.
 */
internal fun highlightNumbers(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    numeralRanges(text).forEach { range ->
        append(text.substring(last, range.first))
        withStyle(
            SpanStyle(
                fontWeight = FontWeight.SemiBold,
                color = Tokens.TextPrimary,
                background = Tokens.HighlightWash,
            ),
        ) {
            append(text.substring(range.first, range.last + 1))
        }
        last = range.last + 1
    }
    append(text.substring(last))
}

/**
 * The card that renders a generated paragraph, wherever one is shown.
 *
 * One composable rather than one per screen, so the three places a summary appears cannot end up
 * disagreeing about how a model's prose is presented - in particular about the attribution line,
 * which §11.1 asks for and which is the only thing on screen distinguishing a sentence Gemma wrote
 * from a sentence assembled from measurements.
 */
@Composable
fun GeneratedSummaryCard(
    title: String,
    state: SummaryState,
    backend: LlmBackend?,
    writingLabel: String = "Writing the summary…",
    /** Shown when there is nothing to summarise at all, which is not the same as still writing. */
    emptyLabel: String? = null,
) {
    RpCard {
        Text(title, style = Type.HeadlineSm, color = Tokens.TextPrimary)
        when {
            // The model's own words, arriving. Once anything has been produced the label goes: a
            // caption saying "writing…" above a paragraph that is visibly being written is telling
            // the reader something the screen has already told them.
            state.writing && state.partial.isNotBlank() -> {
                Text(
                    highlightNumbers(state.partial),
                    style = HighlightedProse,
                    color = Tokens.TextPrimary,
                )
                Text(writingLabel, style = Type.LabelMonoSm, color = Tokens.TextMuted)
            }
            state.writing -> Text(writingLabel, style = Type.BodyMd, color = Tokens.TextSecondary)
            state.summary != null -> {
                Text(
                    highlightNumbers(state.summary.text),
                    style = HighlightedProse,
                    color = Tokens.TextPrimary,
                )
                Text(
                    attributionOf(state.summary, backend),
                    style = Type.LabelMonoSm,
                    color = Tokens.TextMuted,
                )
            }
            emptyLabel != null -> Text(
                emptyLabel,
                style = Type.BodyLg,
                color = Tokens.TextPrimary,
            )
        }
    }
}

/**
 * Body text sized so a highlighter mark sits square on the line it marks.
 *
 * `BodyLg` gives 16sp type a 24sp line, and by default the extra eight points are hung above the
 * glyphs. A `SpanStyle` background is painted over the whole line box, so the marks came out
 * top-heavy and ragged - each one floating a few pixels above the number it was meant to be behind,
 * which reads as a rendering fault rather than as emphasis. Centring the text in its line and
 * keeping the leading on the first and last lines puts the mark where a pen would have left it.
 */
private val HighlightedProse = Type.BodyLg.copy(
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)
