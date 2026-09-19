package com.example.rightposture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.core.rpose.HOLD_MODE

/**
 * Which kind of exercise a row is: a cyclic movement, or a §6.19 hold.
 *
 * Two things are being separated here that used to be indistinguishable on a list. A patient
 * opening Today saw four names and no way to know that one of them asks them to stand still for
 * thirty seconds while the others ask for ten repetitions - which is the difference between
 * arriving prepared and arriving confused.
 *
 * The words are the physio's, not the code's. `cyclic` is a term from the maths and nobody says it
 * aloud; the pair a patient reads is the pair they would say back.
 */
@Composable
fun CategoryTag(mode: String, modifier: Modifier = Modifier) {
    val hold = mode == HOLD_MODE
    val label = if (hold) "Hold" else "Exercise"
    val background = if (hold) HOLD_WASH else CYCLIC_WASH
    val foreground = if (hold) HOLD_INK else CYCLIC_INK
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = "$label exercise" },
    )
}

/**
 * The line under the name: what the patient is actually being asked to do.
 *
 * A hold prescribed as "3 × 1" is technically true and useless - one repetition of an unnamed
 * duration. §9.1 stores the duration in `prescription.hold_s` precisely so it can be said out loud
 * here instead.
 */
fun prescriptionLine(mode: String, sets: Int, reps: Int, holdSeconds: Int?): String = when {
    mode == HOLD_MODE && holdSeconds != null -> "$sets × ${holdSeconds}s hold"
    // A hold whose duration never made it into the bundle. Naming it as a hold without inventing a
    // number is better than printing a rep count that was never meant literally.
    mode == HOLD_MODE -> "$sets × hold"
    else -> "$sets × $reps"
}

private val HOLD_WASH = Color(0xFFE3EEF6)
private val HOLD_INK = Color(0xFF1B4F72)
private val CYCLIC_WASH = Color(0xFFE7F0E8)
private val CYCLIC_INK = Color(0xFF2E6B3A)
