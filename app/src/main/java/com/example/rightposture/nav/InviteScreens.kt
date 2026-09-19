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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rightposture.data.ExerciseEntity
import com.example.rightposture.sync.LinkedPatient

private val MUTED = Color(0xFF6B6B6B)
private val BAD = Color(0xFFC62828)
private val GOOD = Color(0xFF2E7D32)

/**
 * §0's physio half: mint a code, read it out, then prescribe to whoever redeemed it.
 *
 * Code and prescription live on one screen because they are one task. A physio who generates a code
 * and then has to go and find a different screen to assign from has been given two jobs where the
 * clinic workflow has one.
 */
@Composable
fun InvitePatientScreen(
    code: String?,
    codeExpiry: String?,
    patients: List<LinkedPatient>,
    exercises: List<ExerciseEntity>,
    busy: Boolean,
    message: String?,
    error: String?,
    onGenerate: () -> Unit,
    onRefresh: () -> Unit,
    onAssign: (patient: LinkedPatient, exercise: ExerciseEntity) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Invite a patient", style = MaterialTheme.typography.headlineSmall)

            if (code == null) {
                Text(
                    "Generate a code and read it out. Your patient enters it once on their own " +
                        "phone, and everything you prescribe reaches them from then on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MUTED,
                )
            } else {
                Text(
                    // Spaced out because this is read aloud across a desk, and six unbroken
                    // characters are read back wrong.
                    code.toCharArray().joinToString(" "),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    modifier = Modifier.semantics { contentDescription = "Invite code $code" },
                )
                codeExpiry?.let {
                    Text("Valid until $it", style = MaterialTheme.typography.bodySmall, color = MUTED)
                }
            }

            Button(onClick = onGenerate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (code == null) "Generate a code" else "Generate another")
            }

            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = BAD, style = MaterialTheme.typography.bodyMedium) }
            message?.let { Text(it, color = GOOD, style = MaterialTheme.typography.bodyMedium) }

            Text("Your patients", style = MaterialTheme.typography.titleMedium)
            if (patients.isEmpty()) {
                Text(
                    "Nobody has entered a code yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MUTED,
                )
            } else if (exercises.isEmpty()) {
                Text(
                    "Record an exercise before you can prescribe one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MUTED,
                )
            }

            patients.forEach { patient ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        patient.name ?: patient.email,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    exercises.forEach { exercise ->
                        OutlinedButton(
                            onClick = { onAssign(patient, exercise) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                // §9.6: the version is part of what is being prescribed, so it is
                                // shown. Assigning "v1" and "v2" of a name are different orders.
                                Text("${exercise.name} v${exercise.version}")
                                Text("Assign")
                            }
                        }
                    }
                }
            }

            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

/**
 * §0's patient half: enter the code once.
 *
 * "Skip for now" is not a courtesy. §13.3 and 7.1's airplane-mode gate both require that a patient
 * can train without ever reaching a server, so a linking step that cannot be passed over would be a
 * network dependency in front of the one flow that must not have one.
 */
@Composable
fun RedeemInviteScreen(
    busy: Boolean,
    error: String?,
    onRedeem: (String) -> Unit,
    onSkip: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Enter your code", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Your physiotherapist will give you a six-character code.",
                style = MaterialTheme.typography.bodyMedium,
                color = MUTED,
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Invite code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Invite code"
                },
            )
            Button(
                onClick = { onRedeem(typed) },
                enabled = !busy && typed.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Link me") }

            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = BAD, style = MaterialTheme.typography.bodyMedium) }

            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Skip for now")
            }
        }
    }
}
