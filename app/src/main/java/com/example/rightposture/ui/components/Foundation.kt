package com.example.rightposture.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.rightposture.ui.theme.Radius
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens

/**
 * The pieces every screen in `UI_screens/` is assembled from.
 *
 * These exist so the density budget in UI-SCREENS.md §9 is enforceable rather than aspirational: a
 * screen built from [RpCard] and [SectionHeader] has countable sections, and one that needs a
 * fourth kind of container is telling you it should have been two screens.
 */

/** The white card the mocks put almost everything in. */
@Composable
fun RpCard(
    modifier: Modifier = Modifier,
    background: Color = Tokens.SurfaceCard,
    border: Color? = null,
    padding: androidx.compose.ui.unit.Dp = Space.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (border != null) Modifier.border(1.dp, border, Radius.Card) else Modifier)
            .background(background, Radius.Card)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        content = content,
    )
}

/**
 * A small label on a wash.
 *
 * Used for exercise category, status and counts. Colour is never the only signal (§11) - every pill
 * carries its word, so red/green colour-blindness costs nothing.
 */
@Composable
fun Pill(
    label: String,
    ink: Color,
    wash: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: TextStyle = Type.LabelMonoSm,
) {
    Row(
        modifier = modifier
            .background(wash, Radius.Pill)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
        }
        Text(label, style = style, color = ink)
    }
}

/** A pill with no fill, for secondary metadata that should not compete with a status. */
@Composable
fun OutlinePill(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = Type.LabelMonoSm,
        color = Tokens.TextSecondary,
        modifier = modifier
            .background(Tokens.CanvasSubtle, Radius.Pill)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * The one primary action a screen is allowed (§9).
 *
 * Full width, 56dp, with the action's icon in a circle at the trailing edge - the shape the mocks
 * use for "Start Session", "Back to Today" and "Add an exercise" alike, so the primary action is
 * recognisable before it is read.
 */
@Composable
fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailingNote: String? = null,
    enabled: Boolean = true,
) {
    val background = if (enabled) Tokens.PrimaryContainer else Tokens.SurfaceCardWarm
    val foreground = if (enabled) Tokens.OnPrimary else Tokens.TextMuted
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = Radius.Pill,
        color = background,
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(label, style = Type.TitleMd, color = foreground)
                if (trailingNote != null) {
                    Text(trailingNote, style = Type.BodySm, color = foreground.copy(alpha = 0.7f))
                }
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(if (enabled) Tokens.SurfaceCard else Tokens.CanvasSubtle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) Tokens.PrimaryContainer else Tokens.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** A bordered, quieter action for anything that is not the screen's one primary. */
@Composable
fun SecondaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        shape = Radius.Pill,
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, Tokens.OutlineVariant),
        modifier = modifier.fillMaxWidth().height(44.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = Type.TitleSm, color = Tokens.TextPrimary)
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Tokens.TextPrimary,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp),
                )
            }
        }
    }
}

/** A section title with optional right-hand note, per the mocks' "Remaining Prescriptions · 2 pending review". */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.xxs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.HeadlineSm, color = Tokens.TextPrimary)
        if (note != null) Text(note, style = Type.BodySm, color = Tokens.TextSecondary)
        trailing?.invoke(this)
    }
}

/**
 * §8.7's empty state: one sentence, and the single action that resolves it.
 *
 * The action is required rather than optional on purpose. A list with nothing in it and nothing to
 * tap is a dead end, and UI-FLOWS.md's fourth rule is that no screen has one.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RpCard(modifier = modifier, background = Tokens.SurfaceCardWarm) {
        Box(
            modifier = Modifier.size(44.dp).background(Tokens.SurfaceCard, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Tokens.PrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Text(message, style = Type.BodyMd, color = Tokens.TextSecondary)
        SecondaryAction(actionLabel, onAction)
    }
}

/**
 * A note the patient should read but not act on immediately - the safety line on an exercise card,
 * the "we could not score this" explanation on a result.
 */
@Composable
fun NoteBanner(
    text: String,
    modifier: Modifier = Modifier,
    ink: Color = Tokens.WarningInk,
    wash: Color = Tokens.WarningWash,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(wash, RoundedCornerShape(14.dp))
            .padding(horizontal = Space.sm, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        }
        Text(text, style = Type.BodyMd, color = ink)
    }
}
