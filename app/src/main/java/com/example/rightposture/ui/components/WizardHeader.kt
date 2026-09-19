package com.example.rightposture.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.ui.theme.Radius
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens

/**
 * The chrome on a linear wizard: where you are, how far there is to go, and one way out.
 *
 * §5.5's recording flow is five steps and used to present as one camera screen with buttons that
 * appeared and disappeared. A physio halfway through had no way to tell whether the next tap would
 * record, save, or throw away four minutes of setup - which is the wrong thing to be unsure about
 * while someone is standing in front of a phone waiting to be recorded.
 *
 * The bar is filled segments rather than a percentage, because the steps are discrete and the
 * physio is counting them. The count is also written out: a five-segment bar read at arm's length
 * is a texture, not a number.
 */
@Composable
fun WizardHeader(
    title: String,
    stepLabel: String,
    step: Int,
    steps: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.Gutter, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onCancel,
                shape = CircleShape,
                color = Tokens.SurfaceCardWarm,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Cancel recording a reference",
                        tint = Tokens.TextPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(title, style = Type.HeadlineSm, color = Tokens.TextPrimary)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stepLabel, style = Type.TitleSm, color = Tokens.TextPrimary)
            Text("Step $step of $steps", style = Type.LabelMonoSm, color = Tokens.TextSecondary)
        }

        Row(
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Step $step of $steps, $stepLabel"
            },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(steps) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            if (index < step) Tokens.PrimaryContainer else Tokens.SurfaceCardWarm,
                            Radius.Pill,
                        ),
                )
            }
        }
    }
}
