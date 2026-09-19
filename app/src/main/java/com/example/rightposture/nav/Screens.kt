package com.example.rightposture.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.data.ExerciseEntity
import com.example.rightposture.data.SessionEntity
import com.example.rightposture.report.ExerciseProgression
import com.example.rightposture.ui.CategoryTag
import com.example.rightposture.sync.LinkedPatient
import com.example.rightposture.sync.RemoteSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MUTED = Color(0xFF6B6B6B)
private val WARN = Color(0xFFE08A00)

/**
 * §2.4's role choice.
 *
 * A screen rather than a setting, because the two roles see entirely different apps and guessing
 * wrong sends a patient into the authoring flow.
 */
@Composable
fun RolePickerScreen(onPick: (Role) -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("RightPosture", style = MaterialTheme.typography.headlineMedium)
            Text("Who is using this phone?", style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = { onPick(Role.PHYSIO) },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Continue as physiotherapist"
                },
            ) { Text("I'm the physiotherapist") }
            Text(
                "Record an exercise once. The app learns the target from how you do it.",
                style = MaterialTheme.typography.bodySmall,
                color = MUTED,
            )
            OutlinedButton(
                onClick = { onPick(Role.PATIENT) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("I'm the patient") }
            Text(
                "Do today's exercises with live coaching.",
                style = MaterialTheme.typography.bodySmall,
                color = MUTED,
            )
        }
    }
}

/**
 * §6.21's verdict for one exercise, with the data behind it.
 *
 * 8.4's gate is that a flag shows its supporting data, so the reasons are rendered beside the flag
 * rather than hidden behind a tap. A physio asked to advance a patient needs to see why without
 * opening a database.
 */
@Composable
private fun ProgressionRow(item: ExerciseProgression) {
    val accent = when {
        item.verdict.needsReview -> Color(0xFFC62828)
        item.verdict.readyToProgress -> Color(0xFF2E7D32)
        else -> MUTED
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            buildString {
                append(item.exerciseName)
                append(" - ")
                append(
                    when {
                        item.verdict.needsReview -> "needs review"
                        item.verdict.readyToProgress -> "ready to progress"
                        else -> "continuing"
                    },
                )
            },
            style = MaterialTheme.typography.titleSmall,
            color = accent,
        )
        item.verdict.reasons.forEach {
            Text("· $it", style = MaterialTheme.typography.bodySmall, color = MUTED)
        }
        // §6.21.5 is explicit that a clinician signs off. The app states the case and stops.
        if (item.verdict.readyToProgress) {
            Text(
                "Your call - the app never advances anyone on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MUTED,
            )
        }
    }
}

/**
 * §14.1's report row for somebody else's set.
 *
 * Not tappable, unlike the local rows. The detail screen reads reps out of Room, and these reps are
 * not in Room - opening one would show an empty set and look like the data had been lost. Pulling
 * the full detail per session belongs with 8.3's charts.
 */
@Composable
private fun RemoteSessionRow(session: RemoteSession) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            session.exerciseId ?: "Unnamed exercise",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            buildString {
                append("${session.countedReps} of ${session.prescribedReps} reps")
                // §1.6: a set with no score is not a set that scored zero, and the physio is the
                // one person for whom that distinction changes a decision.
                session.setScore?.let { append(" · score %.0f".format(it)) }
                    ?: append(" · not scored")
                if (session.unfinished) append(" · interrupted")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (session.unfinished) WARN else MUTED,
        )
        session.degradationOnsetRep?.let {
            // §6.18.3. Stated plainly rather than left in a chart, because it is the single most
            // actionable thing in the report.
            Text(
                "Form started slipping at rep $it",
                style = MaterialTheme.typography.bodySmall,
                color = WARN,
            )
        }
    }
}

@Composable
private fun SessionRow(session: SessionEntity, name: String?, onOpen: (String) -> Unit) {
    TextButton(onClick = { onOpen(session.id) }, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                // The name the physio typed, never the derived id. "rp_hands_on_3" is a filename,
                // and showing it to the person who called the exercise "hands-on 3" reads as a bug.
                name ?: session.exerciseId ?: "Unnamed exercise",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                buildString {
                    append(session.startedAt.asDate())
                    // An unfinished session is the interesting one: §13.1's killed-mid-set case.
                    // Saying so is better than showing a set score of nothing with no explanation.
                    if (session.endedAt == null) append(" · unfinished")
                    session.setScore?.let { append(" · score %.0f".format(it)) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (session.endedAt == null) WARN else MUTED,
            )
        }
    }
}

private fun Long.asDate(): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(this))
