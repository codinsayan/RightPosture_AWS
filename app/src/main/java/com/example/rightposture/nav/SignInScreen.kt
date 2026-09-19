package com.example.rightposture.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private val MUTED = Color(0xFF6B6B6B)
private val BAD = Color(0xFFC62828)
private val WARN = Color(0xFFE08A00)

/**
 * §7.3's sign-in, with sign-up on the same screen because a demo has no separate onboarding.
 *
 * The role picker is still here for sign-up: §2.4's two roles are chosen once, and until 7.4 links
 * a patient to a physio there is nothing else that could determine it.
 */
@Composable
fun SignInScreen(
    busy: Boolean,
    error: String?,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, Role) -> Unit,
    onContinueOffline: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var role by remember { mutableStateOf(Role.PHYSIO) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("RightPosture", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (creating) "Create an account" else "Sign in",
                style = MaterialTheme.typography.titleMedium,
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            if (creating) {
                Text("I am the", style = MaterialTheme.typography.bodySmall, color = MUTED)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RoleChoice("Physiotherapist", role == Role.PHYSIO, Modifier.weight(1f)) {
                        role = Role.PHYSIO
                    }
                    RoleChoice("Patient", role == Role.PATIENT, Modifier.weight(1f)) {
                        role = Role.PATIENT
                    }
                }
            }

            error?.let {
                Text(it, color = BAD, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    if (creating) onSignUp(email.trim(), password, role)
                    else onSignIn(email.trim(), password)
                },
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        busy -> "Working…"
                        creating -> "Create account"
                        else -> "Sign in"
                    },
                )
            }

            TextButton(
                onClick = { creating = !creating },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (creating) "I already have an account" else "Create an account")
            }

            // §13.3 again: the whole patient session works with no network, so being unable to
            // sign in must never be the thing that stops someone exercising.
            TextButton(onClick = onContinueOffline, modifier = Modifier.fillMaxWidth()) {
                Text("Continue without an account")
            }
            Text(
                "Sessions stay on this phone until you sign in.",
                style = MaterialTheme.typography.bodySmall,
                color = MUTED,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                "Demo accounts",
                style = MaterialTheme.typography.titleSmall,
                color = WARN,
            )
            Text(
                "Fills the fields and signs in. If the account does not exist yet, switch to " +
                    "\"Create an account\" - the credentials are already filled in.",
                style = MaterialTheme.typography.bodySmall,
                color = MUTED,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        email = TEST_PHYSIO_EMAIL
                        password = TEST_PHYSIO_PASSWORD
                        role = Role.PHYSIO
                        // Filling the fields is half the job; the point of the button is to skip
                        // the typing *and* the tap. If sign-in is rejected because the account has
                        // not been created on this project yet, the fields are still populated and
                        // "Create an account" is one tap away.
                        if (creating) onSignUp(TEST_PHYSIO_EMAIL, TEST_PHYSIO_PASSWORD, Role.PHYSIO)
                        else onSignIn(TEST_PHYSIO_EMAIL, TEST_PHYSIO_PASSWORD)
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Physio") }

                OutlinedButton(
                    onClick = {
                        email = TEST_PATIENT_EMAIL
                        password = TEST_PATIENT_PASSWORD
                        role = Role.PATIENT
                        if (creating) onSignUp(TEST_PATIENT_EMAIL, TEST_PATIENT_PASSWORD, Role.PATIENT)
                        else onSignIn(TEST_PATIENT_EMAIL, TEST_PATIENT_PASSWORD)
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Patient") }
            }
        }
    }
}

/**
 * The demo credentials, in one place so the buttons and any future seeding cannot drift apart.
 *
 * These are deliberately weak and deliberately public - they exist so a demo can be driven quickly
 * on a project that holds nothing real. Nothing here should ever be pointed at a project with
 * patient data in it.
 */
const val TEST_PHYSIO_EMAIL = "test@gmail.com"
const val TEST_PHYSIO_PASSWORD = "123test"
const val TEST_PATIENT_EMAIL = "patient@gmail.com"
const val TEST_PATIENT_PASSWORD = "123patient"

@Composable
private fun RoleChoice(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}
