package com.example.rightposture.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rightposture.ui.theme.Radius
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens

/**
 * The segmented pill used wherever one screen holds several views of the same thing.
 *
 * Three screens need it - the session detail, Progress, and the physio's patient detail - and all
 * three exist because their content outgrew one scroll rather than because tabs are decorative. A
 * tab here is always a different *view* of one subject, never a different subject; that is what
 * separates it from the role tab bar at the bottom of the screen.
 *
 * The count on a tab (12 reps, 2 flags) is part of the label and not a decoration: it tells the
 * reader whether the tab is worth opening before they open it.
 */
@Composable
fun SegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badges: List<String?> = emptyList(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Tokens.SurfaceCardWarm, Radius.Pill)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            TabPill(
                label = label,
                badge = badges.getOrNull(index),
                selected = selected,
                // Four tabs is the widest this bar gets (§5.3's patient detail), and at 14sp
                // "Programme" wraps onto a second line while a two-digit badge splits down the
                // middle. Stepping the label down rather than truncating it keeps every tab
                // readable - a tab called "Programm…" is worse than a slightly smaller one.
                compact = labels.size >= 4,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun RowScope.TabPill(
    label: String,
    badge: String?,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = Radius.Pill,
        color = if (selected) Tokens.PrimaryContainer else Tokens.SurfaceCardWarm,
        modifier = Modifier
            .weight(1f)
            .semantics {
                this.selected = selected
                contentDescription = if (badge == null) label else "$label, $badge"
            },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = if (compact) 2.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(
                if (compact) 3.dp else 5.dp,
                Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = if (compact) Type.TitleSm.copy(fontSize = 12.sp) else Type.TitleSm,
                textAlign = TextAlign.Center,
                maxLines = 1,
                color = if (selected) Tokens.OnPrimary else Tokens.TextSecondary,
            )
            if (badge != null) {
                Text(
                    badge,
                    style = Type.LabelMonoHud,
                    maxLines = 1,
                    color = if (selected) Tokens.OnPrimary else Tokens.TextSecondary,
                    modifier = Modifier
                        .background(
                            if (selected) Tokens.SurfaceCard.copy(alpha = 0.2f)
                            else Tokens.SurfaceCard,
                            Radius.Pill,
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }
}
