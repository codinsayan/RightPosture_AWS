package com.example.rightposture.authoring

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rightposture.core.reference.AuthoringResult
import com.example.rightposture.core.reference.CheckOutcome
import com.example.rightposture.core.reference.FeatureReference
import com.example.rightposture.core.reference.band
import com.example.rightposture.core.reference.boundaryIndices

private val PASS = Color(0xFF2E7D32)
private val FAIL = Color(0xFFC62828)
private val UNKNOWN = Color(0xFF9E6A00)
private val TRACE = Color(0xFF1565C0)
private val BAND = Color(0x332E7D32)
private val BOUNDARY = Color(0xFFC62828)

/**
 * §6.11 review. "Two seconds of visual confirmation is worth more than any amount of algorithmic
 * cleverness; a physio will spot a bad split instantly."
 *
 * The re-record button is unconditional, per §6.11 ("Provide a 'looks wrong — re-record' button.
 * Always."). It is shown even when every check passes, because the physio's eye overrules the
 * validator, not the other way round.
 */
@Composable
fun ReferenceReviewScreen(
    result: AuthoringResult,
    onReRecord: () -> Unit,
    onStartExercise: (() -> Unit)? = null,
    /**
     * 6.4's catalogue conformance. Advisory: the recording is usable, it just does not look like
     * the exercise that was chosen, and only the physio can settle which of the two is wrong.
     */
    warnings: List<String> = emptyList(),
    /**
     * What the accept button says. The physio path saves the reference; nothing else calls this
     * with an action that starts a set, so the wording is not fixed here.
     */
    saveLabel: String = "Use this - start exercise",
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Reference review", style = MaterialTheme.typography.headlineSmall)

        val validation = result.validation
        when {
            result.failure != null -> Verdict("Rejected", result.failure!!, FAIL)
            validation == null -> Verdict("Rejected", "The pipeline produced no result.", FAIL)
            !validation.accepted -> Verdict("Rejected", validation.firstFailure!!.message, FAIL)
            else -> Verdict(
                "Accepted",
                "${result.reference?.repCount ?: 0} clean reps, " +
                    "${result.watchList?.features?.size ?: 0} joints watched.",
                PASS,
            )
        }

        warnings.forEach {
            Text(it, color = UNKNOWN, style = MaterialTheme.typography.bodySmall)
        }

        if (result.dominantTrace.isNotEmpty()) {
            Text("Dominant joint: ${result.dominant?.value ?: "none"}", style = MaterialTheme.typography.titleSmall)
            Text(
                "Trace with rep boundaries. Check the red lines land where each rep really starts.",
                style = MaterialTheme.typography.bodySmall,
            )
            TraceChart(
                values = result.dominantTrace,
                boundaries = result.segmentation?.boundaryIndices() ?: emptyList(),
            )
        }

        result.reference?.features?.get(result.dominant)?.let { feature ->
            Text("Reference band (mean ± 3σ)", style = MaterialTheme.typography.titleSmall)
            Text(
                "One averaged rep, 0 to 100% through the movement.",
                style = MaterialTheme.typography.bodySmall,
            )
            BandChart(feature)
        }

        validation?.let { ChecksTable(it.checks) }
        result.watchList?.let { WatchListTable(it) }

        // Only offered on an accepted reference. Nothing may be prescribed off a rejected one.
        if (onStartExercise != null && result.validation?.accepted == true) {
            Button(onClick = onStartExercise, modifier = Modifier.fillMaxWidth()) {
                Text(saveLabel)
            }
        }

        Button(onClick = onReRecord, modifier = Modifier.fillMaxWidth()) {
            Text("Looks wrong — re-record")
        }
    }
}

@Composable
private fun Verdict(title: String, detail: String, colour: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().background(colour.copy(alpha = 0.12f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colour)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Dominant-feature trace with rep boundaries marked, per §6.11. */
@Composable
private fun TraceChart(values: List<Float>, boundaries: List<Int>) {
    val min = values.min()
    val max = values.max()
    val span = (max - min).takeIf { it > 1e-6f } ?: 1f

    Canvas(
        Modifier.fillMaxWidth().height(160.dp).background(Color(0x11000000)).semantics {
            contentDescription = "Dominant feature trace with ${boundaries.size} rep boundaries"
        },
    ) {
        fun x(index: Int) = size.width * index / (values.size - 1).coerceAtLeast(1)
        fun y(value: Float) = size.height * (1f - (value - min) / span)

        boundaries.forEach { index ->
            drawLine(BOUNDARY, Offset(x(index), 0f), Offset(x(index), size.height), strokeWidth = 2f)
        }
        val path = Path().apply {
            moveTo(x(0), y(values[0]))
            values.indices.drop(1).forEach { lineTo(x(it), y(values[it])) }
        }
        drawPath(path, TRACE, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    }
}

/** μ with the ±3σ band drawn on top, per §6.11. */
@Composable
private fun BandChart(feature: FeatureReference) {
    val (lower, upper) = feature.band()
    val min = lower.min()
    val max = upper.max()
    val span = (max - min).takeIf { it > 1e-6f } ?: 1f

    Canvas(
        Modifier.fillMaxWidth().height(160.dp).background(Color(0x11000000)).semantics {
            contentDescription = "Reference mean with three sigma band"
        },
    ) {
        fun x(bin: Int) = size.width * bin / (feature.mean.size - 1).coerceAtLeast(1)
        fun y(value: Float) = size.height * (1f - (value - min) / span)

        val bandPath = Path().apply {
            moveTo(x(0), y(upper[0]))
            upper.indices.drop(1).forEach { lineTo(x(it), y(upper[it])) }
            lower.indices.reversed().forEach { lineTo(x(it), y(lower[it])) }
            close()
        }
        drawPath(bandPath, BAND)

        val meanPath = Path().apply {
            moveTo(x(0), y(feature.mean[0]))
            feature.mean.indices.drop(1).forEach { lineTo(x(it), y(feature.mean[it])) }
        }
        drawPath(meanPath, PASS, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    }
}

@Composable
private fun ChecksTable(checks: List<com.example.rightposture.core.reference.ValidatorCheck>) {
    Text("Validator", style = MaterialTheme.typography.titleSmall)
    checks.forEach { check ->
        val (symbol, colour) = when (check.outcome) {
            CheckOutcome.PASS -> "PASS" to PASS
            CheckOutcome.FAIL -> "FAIL" to FAIL
            CheckOutcome.NOT_EVALUATED -> "SKIP" to UNKNOWN
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(symbol, color = colour, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Column {
                Text("${check.number}. ${check.name} — ${check.detail}", fontSize = 13.sp)
                if (check.message.isNotEmpty()) {
                    Text(check.message, color = colour, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun WatchListTable(watchList: com.example.rightposture.core.reference.WatchList) {
    Text("Watch list", style = MaterialTheme.typography.titleSmall)
    watchList.features.forEach {
        Text(
            "${it.id.value} — salience ${"%.1f".format(it.salience)}, " +
                "weight ${"%.0f".format(it.weight * 100)}%",
            fontSize = 13.sp,
        )
    }
    if (watchList.rejected.isNotEmpty()) {
        Text("Not watched", style = MaterialTheme.typography.titleSmall)
        watchList.rejected.forEach { (id, reason) ->
            Text("${id.value} — $reason", fontSize = 12.sp, color = UNKNOWN)
        }
    }
}
