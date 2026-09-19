package com.example.rightposture.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens
import kotlin.math.abs
import kotlin.math.min

/**
 * §6.18.5's joint attribution: which joint moved, which way, and by how much.
 *
 * A diverging bar from a centre line, because the quantity has a sign that matters clinically -
 * drifting away from the reference and moving back toward it are opposite events, and a bar that
 * only encoded magnitude would make them look the same. Direction is carried by side *and* by the
 * words beside it, never by colour alone (§11).
 *
 * A joint with too few frames to judge draws no bar at all, only the centre line and the words "not
 * measured". §6.6's abstention again: silence about a joint is not a report that it was steady.
 */
@Composable
fun AttributionBar(
    name: String,
    /** Signed shift in standard deviations. Positive drifted away from the reference. */
    shiftZ: Double,
    trustworthy: Boolean,
    explanation: String,
    modifier: Modifier = Modifier,
    /** The z at which the bar reaches the edge. Anything larger is clamped, not rescaled. */
    fullScale: Double = 1.5,
) {
    val magnitude = if (trustworthy) min(abs(shiftZ) / fullScale, 1.0).toFloat() else 0f
    val ink = when {
        !trustworthy -> Tokens.AbsentInk
        shiftZ > 0 -> Tokens.WarningInk
        else -> Tokens.SuccessInk
    }
    val reading = when {
        !trustworthy -> "not measured"
        shiftZ > 0 -> "drifted %.1fσ".format(abs(shiftZ))
        else -> "held %.1fσ".format(abs(shiftZ))
    }

    Column(
        modifier = modifier.fillMaxWidth().semantics {
            contentDescription = "$name, $reading. $explanation"
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = Type.TitleSm, color = Tokens.TextPrimary)
            Text(reading, style = Type.LabelMonoSm, color = ink)
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .then(
                    if (trustworthy) {
                        Modifier.background(Tokens.CanvasSubtle, RoundedCornerShape(999.dp))
                    } else {
                        Modifier
                            .background(Tokens.AbsentWash, RoundedCornerShape(999.dp))
                            .border(1.dp, Tokens.OutlineVariant, RoundedCornerShape(999.dp))
                    },
                ),
        ) {
            val half = maxWidth / 2
            if (magnitude > 0f) {
                val length = half * magnitude
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = if (shiftZ > 0) half else half - length)
                        .width(length)
                        .height(12.dp)
                        .padding(horizontal = 1.dp)
                        .background(ink, RoundedCornerShape(999.dp)),
                )
            }
            // The centre line is drawn last so it stays visible across the bar.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = half)
                    .width(1.dp)
                    .height(20.dp)
                    .background(Tokens.OutlineVariant),
            )
        }

        Text(explanation, style = Type.BodySm, color = Tokens.TextSecondary)
    }
}

/** The heading a group of [AttributionBar]s sits under, with its own one-line explanation. */
@Composable
fun AttributionCard(
    title: String,
    caption: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    RpCard(modifier = modifier) {
        Text(title, style = Type.HeadlineSm, color = Tokens.TextPrimary)
        Text(caption, style = Type.BodySm, color = Tokens.TextSecondary)
        Column(
            modifier = Modifier.padding(top = Space.xxs),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            content()
        }
    }
}
