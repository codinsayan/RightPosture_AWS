package com.example.rightposture.authoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Asks what the recording should be called, before anything is written.
 *
 * The name is collected here rather than defaulted because it is the only part of §9.1's identity a
 * recording cannot supply. Frames carry angles, not intent - "Recorded exercise" on every row is
 * what a physio with four references gets otherwise, and they are the person who has to tell them
 * apart later.
 *
 * Cancel leaves nothing behind. The reference stays on screen, so cancelling is a way back to the
 * review rather than a way to lose the recording.
 */
@Composable
fun SaveExerciseDialog(
    suggestedName: String,
    /**
     * The newest version already stored under a given name, or null if the name is unused.
     *
     * A lookup rather than a fixed value because the physio can retype the name inside the dialog,
     * and the warning has to follow what they actually typed - a stale "this will be version 3"
     * against a brand new name is worse than no warning at all.
     */
    existingVersionFor: (String) -> Int?,
    busy: Boolean,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    val trimmed = name.trim()
    val existingVersion = if (trimmed.isEmpty()) null else existingVersionFor(trimmed)

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Save this exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This name is what your patients will see on their Today screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MUTED_TEXT,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Exercise name") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                existingVersion?.let {
                    // §9.6: bundles are immutable, so re-using a name publishes v+1 rather than
                    // overwriting. Anything already prescribed against v$it keeps being scored
                    // against v$it, which is the whole reason versions exist.
                    Text(
                        "You already have an exercise called this. Saving publishes version " +
                            "${it + 1}. Sessions already recorded against version $it are " +
                            "unaffected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NOTICE_TEXT,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(trimmed) },
                enabled = !busy && trimmed.isNotEmpty(),
            ) { Text(if (busy) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
        },
    )
}

private val MUTED_TEXT = Color(0xFF6B6B6B)
private val NOTICE_TEXT = Color(0xFFE08A00)

/**
 * Turns a physio's chosen name into §9.1's stable `id`.
 *
 * The id has to survive being typed differently next time - "Heel Slide" and "heel slide" are the
 * same exercise to the person who named it, and treating them as two would silently fork the
 * version history that §9.6 depends on. Anything that is not a letter or digit collapses to a
 * single underscore so the id is also safe as a filename and as a Storage object path.
 */
fun exerciseIdFromName(name: String): String {
    val slug = name.trim().lowercase()
        .map { if (it.isLetterOrDigit()) it else '_' }
        .joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_')
    return if (slug.isEmpty()) "exercise" else "rp_" + slug.take(48)
}
