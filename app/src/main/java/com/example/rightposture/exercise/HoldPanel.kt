package com.example.rightposture.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.core.live.HoldReference
import com.example.rightposture.core.live.HoldState
import com.example.rightposture.core.live.HoldUpdate
import com.example.rightposture.core.live.PhraseBook
import kotlin.math.roundToInt

private val GOOD = Color(0xFF2E7D32)
private val WARN = Color(0xFFE08A00)
private val BAD = Color(0xFFC62828)

/**
 * §6.19's live hold. A timer, not a rep counter - the thing the patient is trying to make bigger.
 */
@Composable
fun HoldPanel(
    update: HoldUpdate,
    reference: HoldReference,
    phrases: PhraseBook,
    onFinish: () -> Unit,
    /** How many holds have already broken in this session. Zero on the first attempt. */
    attempts: Int = 0,
    /** Seconds achieved by the attempt that just broke, kept across the re-arm. */
    lastSeconds: Int? = null,
    bestSeconds: Int = 0,
    /**
     * True while [update] is a **stopped** hold being held on screen, and the tracker underneath is
     * waiting for the patient to get back into the position. The clock shown is the one they
     * achieved, not a live one - so nothing here may imply it is still running.
     */
    awaitingRestart: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val seconds = update.durationSeconds.roundToInt()
        Text(
            "%d:%02d".format(seconds / 60, seconds % 60),
            style = MaterialTheme.typography.displaySmall,
            color = if (update.state == HoldState.BROKEN) BAD else GOOD,
            modifier = Modifier.semantics {
                contentDescription = "Hold time $seconds seconds"
            },
        )
        // §6.19's HoldQuality: the fraction of the hold spent inside 2 sigma. Null means nothing has
        // been scored yet, which is not the same as nothing being good enough.
        Text(
            when {
                // The clock has not started because the patient is not in the position yet, which
                // is a different thing to say than a quality of nothing.
                update.state == HoldState.SETTLING && attempts > 0 ->
                    "Get back into the position — the timer restarts from zero when you are in it."
                update.state == HoldState.SETTLING -> "Get into the position — the timer starts when you are in it."
                update.holdQuality != null -> "Held well ${(update.holdQuality!! * 100).roundToInt()}% of the time"
                else -> "Getting a reading…"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (update.state == HoldState.BROKEN) {
            Text(
                phrases.holdBroken(seconds),
                style = MaterialTheme.typography.titleMedium,
                color = BAD,
            )
            if (awaitingRestart) {
                // The clock above is stopped. This is the only thing on screen saying what would
                // make it move again, and the patient is across the room, so it says the condition
                // rather than an instruction to press something.
                Text(
                    "Get back into the recorded position and the timer starts again from zero.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WARN,
                )
            }
        } else if (update.driftWarning && update.driftFeature != null) {
            Text(
                phrases.holdDrift(update.driftFeature!!),
                style = MaterialTheme.typography.titleMedium,
                color = WARN,
            )
        }
        // Survives the re-arm, so a patient on their third attempt can see the first two rather
        // than a counter that silently went back to zero. While a stopped hold is on screen its
        // own duration is already the headline, so repeating it as "last" would be noise.
        if (attempts > 0 && lastSeconds != null) {
            Text(
                if (awaitingRestart) {
                    "Attempt $attempts · best ${bestSeconds}s"
                } else {
                    "Attempt ${attempts + 1} · last ${lastSeconds}s · best ${bestSeconds}s"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "Watching: " + reference.watchList.joinToString(", ") { phrases.featureName(it) },
            style = MaterialTheme.typography.bodySmall,
        )
        update.worstDriftPerSecond?.let {
            Text("drift %+.2f σ/s".format(it), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text(if (update.state == HoldState.BROKEN) "Done" else "Stop hold")
        }
    }
}

private fun Modifier.padding() = this.then(Modifier)
