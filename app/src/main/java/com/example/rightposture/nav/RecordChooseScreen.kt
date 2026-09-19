package com.example.rightposture.nav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.ui.components.NoteBanner
import com.example.rightposture.ui.components.PrimaryAction
import com.example.rightposture.ui.components.RpCard
import com.example.rightposture.ui.components.WizardHeader
import com.example.rightposture.ui.theme.Radius
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens

/**
 * §5.5 step 1: which kind of movement is about to be recorded.
 *
 * This is the whole of the step, and deliberately so. The app offers **no list of exercises** - the
 * physiotherapist demonstrates whatever movement they are prescribing, and names it at step 5. A
 * shipped catalogue would imply the app approves a particular set of exercises, when the entire
 * design is that the physio's own recording is the clinical standard; it would also quietly limit a
 * clinician to movements somebody else chose.
 *
 * What does have to be settled before the camera opens is repeated-versus-hold, because §6.19's
 * hold is not a variant of the cyclic path. It has no phase axis and is measured with a timer
 * rather than a rep counter, so the two record different things in different ways. Choosing here
 * rather than discovering it when a recording is refused is what this step buys.
 */
@Composable
fun RecordChooseScreen(
    onCancel: () -> Unit,
    onContinue: (hold: Boolean) -> Unit,
) {
    var hold by remember { mutableStateOf(false) }

    Scaffold(containerColor = Tokens.CanvasBg) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            WizardHeader(
                title = "Record a reference",
                stepLabel = "Choose",
                step = 1,
                steps = 5,
                onCancel = onCancel,
            )

            Column(
                modifier = Modifier.padding(horizontal = Space.Gutter),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Text(
                    "What kind of movement are you about to demonstrate? Everything else - the " +
                        "name, and what counts as correct - comes from the recording itself.",
                    style = Type.BodyMd,
                    color = Tokens.TextSecondary,
                )

                ModeCard(
                    title = "Repeated movement",
                    summary = "A movement performed several times over: knee extensions, sit to " +
                        "stand, hip abduction.",
                    detail = "Record about eight clean repetitions at the pace you want them " +
                        "done. The app finds the rep boundaries itself and builds the band each " +
                        "patient repetition is scored against.",
                    selected = !hold,
                    onSelect = { hold = false },
                )

                ModeCard(
                    title = "Static hold",
                    summary = "A position held still: wall sits, planks, single-leg balance.",
                    detail = "Record the position and stay in it. There are no repetitions to " +
                        "count, so a hold is measured on how steadily the position is kept and " +
                        "for how long.",
                    selected = hold,
                    onSelect = { hold = true },
                )

                // §17's standing instruction, said once rather than per exercise. It used to come
                // from a catalogue entry, so removing the catalogue would otherwise have removed
                // the safety note along with it.
                NoteBanner(
                    text = "Demonstrate the movement the way you want your patients to do it, and " +
                        "stop if anything hurts. Whatever you record becomes the standard.",
                    ink = Tokens.WarningInk,
                    wash = Tokens.WarningWash,
                    icon = Icons.Filled.Warning,
                )

                RpCard(background = Tokens.SurfaceCardWarm, padding = Space.md) {
                    Text("Before you start", style = Type.TitleMd, color = Tokens.TextPrimary)
                    Text(
                        "Set the phone about two metres away with your whole body in frame, from " +
                            "head to feet, and turn so the joint that does the work faces the " +
                            "lens. A single camera measures depth badly, so a movement performed " +
                            "towards the phone is the one it reads worst.",
                        style = Type.BodySm,
                        color = Tokens.TextSecondary,
                    )
                }

                PrimaryAction(
                    label = "Continue to set up",
                    onClick = { onContinue(hold) },
                    icon = Icons.Filled.ArrowForward,
                )

                Box(modifier = Modifier.height(Space.md))
            }
        }
    }
}

/** One of the two pipelines, with enough about it to choose between them without guessing. */
@Composable
private fun ModeCard(
    title: String,
    summary: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        shape = Radius.Card,
        color = if (selected) Tokens.SurfaceCard else Tokens.SurfaceCardWarm,
        border = if (selected) BorderStroke(2.dp, Tokens.PrimaryContainer) else null,
        modifier = Modifier.fillMaxWidth().semantics {
            this.selected = selected
            contentDescription = title
        },
    ) {
        Column(
            modifier = Modifier.padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(title, style = Type.TitleMd, color = Tokens.TextPrimary)
                    Text(summary, style = Type.BodySm, color = Tokens.TextSecondary)
                }
                SelectionDot(selected)
            }
            // Only the chosen one explains itself. Both open at once is two paragraphs about a
            // decision that is one tap either way.
            if (selected) {
                Text(
                    detail,
                    style = Type.BodySm,
                    color = Tokens.TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Tokens.CanvasSubtle, Radius.CardSmall)
                        .padding(Space.sm),
                )
            }
        }
    }
}

@Composable
private fun SelectionDot(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(
                if (selected) Tokens.PrimaryContainer else Tokens.SurfaceCard,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Tokens.OnPrimary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
