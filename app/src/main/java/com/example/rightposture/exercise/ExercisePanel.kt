package com.example.rightposture.exercise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.core.live.ExerciseStatus
import com.example.rightposture.core.live.FatigueResult
import com.example.rightposture.core.live.FrameResult
import com.example.rightposture.core.live.EnglishPhrases
import com.example.rightposture.core.live.PhraseBook
import com.example.rightposture.core.live.QualityBand
import com.example.rightposture.core.live.qualityBand

private val DANGER = Color(0xFFC62828)
private val WARNING = Color(0xFFE08A00)
private val SUCCESS = Color(0xFF2E7D32)
private val UNKNOWN = Color(0xFF9E9E9E)

internal fun colourFor(band: QualityBand) = when (band) {
    QualityBand.SUCCESS -> SUCCESS
    QualityBand.WARNING -> WARNING
    QualityBand.DANGER -> DANGER
    QualityBand.UNKNOWN -> UNKNOWN
}

/**
 * §14.2 exercise screen, minus the ghost and the arrow, which are 5.3.
 *
 * §12.3 says to prioritise the quality bar: "the feedback loop the patient can actually see
 * working." So it is the largest thing here and it is the only element that changes colour.
 */
@Composable
fun ExercisePanel(
    state: FrameResult,
    prescribedReps: Int,
    onFinish: () -> Unit,
    phrases: PhraseBook = EnglishPhrases,
    /** §6.18's live assessment, so §6.18.6's stop can say when the decline began. */
    fatigue: FatigueResult? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        QualityBar(state)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${state.validReps} / $prescribedReps",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics {
                    contentDescription = "${state.validReps} of $prescribedReps reps completed"
                },
            )
            Text(
                state.setQuality?.let { "Set %.0f".format(it) } ?: "Set —",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // A partial rep is shown rather than hidden. §6.12.4: the patient is told it did not count,
        // and hiding it would make the counter look stuck for no visible reason.
        if (state.partialReps > 0) {
            Text(
                phrases.partialReps(state.partialReps),
                style = MaterialTheme.typography.bodySmall,
                color = WARNING,
            )
            // §6.12.4 writes a reason per rejected rep. Showing the count without it leaves the
            // patient to guess what to change.
            state.lastRejection?.let {
                // A joint left out and a range jumped are both SKIPPED, and a patient who moved the
                // joint they were watching cannot act on "follow the whole movement".
                val joint = state.skippedFeature
                val text = if (joint != null) phrases.skippedFeature(joint) else phrases.rejection(it)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = WARNING)
            }
        }

        // §6.18.6. Shown above everything else and in the alarm colour: the instruction is to
        // stop, and it must not read as one more coaching note among several.
        if (state.status == ExerciseStatus.SET_COMPLETE && fatigue?.stopRecommended == true) {
            Text(
                phrases.safetyStop(),
                style = MaterialTheme.typography.titleMedium,
                color = DANGER,
            )
            fatigue.changePointRep?.let {
                Text(
                    "Form started dropping at rep $it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DANGER,
                )
            }
        }
        // A rep that counted but was worth a word. Shown alongside the count rather than instead of
        // it: the rep did happen, and hiding the count would read as a rejection.
        state.lastRepNote?.let {
            Text(phrases.repNote(it), style = MaterialTheme.typography.bodyMedium, color = WARNING)
        }

        Text(phrases.status(state.status), style = MaterialTheme.typography.bodyMedium)

        state.cue?.let { cue ->
            Text(cue.text, style = MaterialTheme.typography.titleMedium, color = DANGER)
        }

        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.status == ExerciseStatus.SET_COMPLETE) "Done" else "Finish set")
        }
    }
}

/**
 * The bar reads empty when there is no score, and empty is not the same as zero - §6.6 and §6.17.1
 * both refuse to emit a zero for "we could not see you", so the bar must not draw one either.
 */
@Composable
private fun QualityBar(state: FrameResult) {
    val band = qualityBand(state.quality)
    val fraction = ((state.quality ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    val label = state.quality?.let { "Quality %.0f out of 100".format(it) } ?: "Quality unknown"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(Color(0x22000000))
            .semantics { contentDescription = label },
    ) {
        if (state.quality != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(24.dp)
                    .background(colourFor(band)),
            )
        }
    }
}

/**
 * Kept out of the composable so 5.1's semantics can be asserted in a JVM test. The wording itself
 * now lives in the phrase book, because it has to exist in every language the app speaks.
 */
internal fun statusLine(state: FrameResult, phrases: PhraseBook = EnglishPhrases): String =
    phrases.status(state.status)
