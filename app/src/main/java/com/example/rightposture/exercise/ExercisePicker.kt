package com.example.rightposture.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.catalog.DEMO_EXERCISES
import com.example.rightposture.catalog.DemoExercise
import com.example.rightposture.catalog.ExerciseMode

/**
 * §3.3's four, chosen before recording rather than inferred after it.
 *
 * The choice is what routes the physio to the right one of the two scoring paths, so it has to come
 * first: discovering that wall sit needed the hold flow only once eight reps have been refused is a
 * worse way to find out.
 */
@Composable
fun ExercisePicker(
    selected: DemoExercise?,
    onSelect: (DemoExercise) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Which exercise?", style = MaterialTheme.typography.titleSmall)
        DEMO_EXERCISES.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { exercise ->
                    val isSelected = exercise.id == selected?.id
                    val label = exercise.name +
                        if (exercise.mode == ExerciseMode.HOLD) " (hold)" else ""
                    if (isSelected) {
                        Button(
                            onClick = { onSelect(exercise) },
                            modifier = Modifier.weight(1f).semantics {
                                contentDescription = "${exercise.name}, selected"
                            },
                        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                    } else {
                        OutlinedButton(
                            onClick = { onSelect(exercise) },
                            modifier = Modifier.weight(1f),
                        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                    }
                }
                // An odd-length row would otherwise stretch its single button across the width and
                // read as a different kind of control to the ones above it.
                if (row.size == 1) Column(Modifier.weight(1f)) {}
            }
        }
        selected?.let {
            // Read while setting the phone up, so it is on the screen the physio is looking at
            // rather than behind an info button they will not press.
            Text(it.setup, style = MaterialTheme.typography.bodySmall)
            Text(
                it.safety,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE08A00),
            )
        }
    }
}
