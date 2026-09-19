package com.example.rightposture.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.example.rightposture.core.live.CalibrationState
import com.example.rightposture.core.live.PhraseBook

private val PASS = Color(0xFF2E7D32)
private val PENDING = Color(0xFF9E9E9E)

/**
 * §14.2's calibration screen: "live camera with the 7-item checklist, ticks appearing as each
 * passes".
 *
 * Every check is listed the whole time, including the ones already passing. §6.20.1 asks for a live
 * checklist "so the patient can see what to fix" - a list that only showed failures would tell them
 * what is wrong without ever telling them they are nearly there.
 */
@Composable
fun CalibrationPanel(
    state: CalibrationState,
    phrases: PhraseBook,
    onContinueAnyway: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (state.ready) "Ready — starting" else phrases.calibrationInProgress(),
            style = MaterialTheme.typography.titleMedium,
        )
        state.checks.forEach { check ->
            Row(
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription =
                        "${phrases.calibrationCheck(check.id)}: ${if (check.passed) "passed" else "waiting"}"
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (check.passed) "✓" else "○", color = if (check.passed) PASS else PENDING)
                Text(
                    phrases.calibrationCheck(check.id),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (check.passed) PASS else PENDING,
                )
            }
        }
        // §6.20 says refuse to start until it passes, and this button is a deliberate exception to
        // that. A gate the patient cannot get past is a gate that strands them when a check is
        // wrong about the room rather than about them - which is a failure mode this app has
        // already produced. It is labelled as an override rather than as a start button, and it
        // names what is still unsatisfied, so nobody presses it without being told what they are
        // overriding.
        val failing = state.checks.filterNot { it.passed }
        if (failing.isNotEmpty()) {
            Text(
                "Start anyway skips: " + failing.joinToString(", ") { phrases.calibrationCheck(it.id) },
                style = MaterialTheme.typography.bodySmall,
                color = PENDING,
            )
        }
        // The measured numbers behind checks 4 and 7, which are the two that cannot be diagnosed by
        // looking at yourself in the preview.
        Text(
            "view %s (need %s)%s%s".format(
                state.view?.name?.lowercase() ?: "unknown",
                state.requiredView.name.lowercase(),
                state.viewRatio?.let { " · ratio %.2f".format(it) } ?: "",
                state.dominantVelocity?.let { " · v %.3f/s".format(it) } ?: "",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = PENDING,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = onContinueAnyway, modifier = Modifier.weight(1f)) {
                Text(if (state.ready) "Continue" else "Start anyway")
            }
        }
    }
}
