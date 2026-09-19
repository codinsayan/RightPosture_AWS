package com.example.rightposture.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.rightposture.report.ChartPoint
import com.example.rightposture.report.segmentsOf
import com.example.rightposture.ui.theme.Space
import com.example.rightposture.ui.theme.Text as Type
import com.example.rightposture.ui.theme.Tokens

/**
 * A per-session trend, drawn the way `UI_screens/` draws one.
 *
 * The rule that shapes the whole component is that a session which could not be judged renders as a
 * **hollow marker with a dashed join**, never as a point at the floor. §6.6 makes abstention a
 * feature of the scoring, and a line that dips to zero for a set of four reps makes exactly the
 * claim the null exists to avoid - that the patient did badly, rather than that there was not
 * enough to measure.
 *
 * No charting library and no text inside the Canvas, matching `nav/Charts.kt`: the app has no
 * `TextMeasurer`, so labels are `Text` rows above and below, and the trend is also spelled out in
 * the content description so the chart is not silent to a screen reader (§11).
 */
@Composable
fun TrendChartCard(
    title: String,
    caption: String,
    points: List<ChartPoint>,
    xLabels: List<String>,
    modifier: Modifier = Modifier,
    note: String? = null,
    /** Fixed for quality (0..100); null lets the chart scale to the data, as range must. */
    fixedRange: ClosedFloatingPointRange<Float>? = null,
    /** The pass mark or goal, drawn as a dashed guide when there is one. */
    target: Float? = null,
    /**
     * §6.18.3's change point, as an x value. Drawn as a vertical rule, because "form started
     * slipping at rep 9" is the one claim on this chart a patient is meant to take away, and a
     * caption saying it would leave them counting markers to find it.
     */
    changePointX: Float? = null,
    /** Colour for the part of the trace at or after [changePointX]. */
    traceAfterChange: Color = Tokens.WarningInk,
    trace: Color = Tokens.SuccessInk,
    description: String = caption,
) {
    if (points.none { it.y != null }) return

    RpCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = Type.TitleMd, color = Tokens.TextPrimary)
            note?.let {
                Text(
                    it,
                    style = Type.LabelMonoSm,
                    color = Tokens.TextSecondary,
                    modifier = Modifier
                        .background(Tokens.CanvasSubtle, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }

        val values = points.mapNotNull { it.y }
        val low = fixedRange?.start ?: (values.min() - values.range() * 0.25f)
        val high = fixedRange?.endInclusive ?: (values.max() + values.range() * 0.25f)
        val span = (high - low).coerceAtLeast(1f)
        val firstX = points.minOf { it.x }
        val lastX = points.maxOf { it.x }
        val xSpan = (lastX - firstX).coerceAtLeast(1f)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Tokens.CanvasBg, RoundedCornerShape(16.dp))
                .padding(Space.sm)
                .semantics { contentDescription = description },
        ) {
            val inset = 10f
            fun px(x: Float) = inset + (size.width - 2 * inset) * (x - firstX) / xSpan
            fun py(y: Float) = size.height * (1f - (y - low) / span)

            target?.let {
                if (it in low..high) {
                    drawLine(
                        color = Tokens.OutlineVariant,
                        start = Offset(0f, py(it)),
                        end = Offset(size.width, py(it)),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 9f)),
                    )
                }
            }

            // A dashed line bridges an unjudged session so the eye follows the trend across the
            // gap, while the missing marker still says nothing was measured there.
            val judged = points.filter { it.y != null }
            judged.zipWithNext { a, b ->
                val gap = points.any { it.x > a.x && it.x < b.x }
                if (gap) {
                    drawLine(
                        color = Tokens.AbsentInk,
                        start = Offset(px(a.x), py(a.y!!)),
                        end = Offset(px(b.x), py(b.y!!)),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    )
                }
            }

            changePointX?.let { at ->
                drawLine(
                    color = traceAfterChange,
                    start = Offset(px(at), 0f),
                    end = Offset(px(at), size.height),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
            }

            segmentsOf(points).forEach { segment ->
                segment.zipWithNext { a, b ->
                    drawLine(
                        // The trace changes colour where quality changed direction, so the split is
                        // visible even to a reader who cannot separate the two hues.
                        color = if (changePointX != null && b.x >= changePointX) {
                            traceAfterChange
                        } else {
                            trace
                        },
                        start = Offset(px(a.x), py(a.y!!)),
                        end = Offset(px(b.x), py(b.y!!)),
                        strokeWidth = 6f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }

            points.forEach { point ->
                val centre = Offset(px(point.x), py(point.y ?: ((low + high) / 2f)))
                if (point.y == null) {
                    // Unjudged: hollow, grey, and deliberately not on the trace.
                    drawCircle(Tokens.AbsentInk, radius = 9f, center = centre, style = Stroke(width = 3f))
                } else {
                    val latest = point.x == judged.last().x
                    val ink = if (changePointX != null && point.x >= changePointX) {
                        traceAfterChange
                    } else {
                        trace
                    }
                    drawCircle(if (latest) ink else Tokens.SurfaceCard, radius = 10f, center = centre)
                    drawCircle(
                        if (latest) Tokens.SurfaceCard else ink,
                        radius = if (latest) 4f else 10f,
                        center = centre,
                        style = if (latest) Stroke(width = 3f) else Stroke(width = 5f),
                    )
                }
            }
        }

        if (xLabels.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                xLabels.forEachIndexed { index, label ->
                    Text(
                        label,
                        style = Type.LabelMonoSm,
                        color = when {
                            index == xLabels.lastIndex -> Tokens.TextPrimary
                            points.getOrNull(index)?.y == null -> Tokens.AbsentInk
                            else -> Tokens.TextSecondary
                        },
                    )
                }
            }
        }

        Text(caption, style = Type.BodySm, color = Tokens.TextSecondary)
    }
}

private fun List<Float>.range(): Float = (max() - min()).coerceAtLeast(1f)
