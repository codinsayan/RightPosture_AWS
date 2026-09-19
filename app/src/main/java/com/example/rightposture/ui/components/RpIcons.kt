package com.example.rightposture.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The handful of icons the mocks use that Compose's core Material set does not carry.
 *
 * Drawn here rather than pulled in with `material-icons-extended`, which is roughly nine megabytes
 * of vectors for the dozen we would actually use - and release builds here run with `isMinifyEnabled
 * = false`, so none of the unused ones would be stripped from the APK a patient installs.
 *
 * All of these are stroked rather than filled, matching the Material Symbols Outlined family the
 * mocks load.
 */
object RpIcons {

    /** The Progress tab: a rising trend line. */
    val Monitoring: ImageVector by lazy {
        stroked("Monitoring") {
            // Axis corner, then a line that climbs across three points.
            moveTo(3f, 3f); lineTo(3f, 21f); lineTo(21f, 21f)
            moveTo(7f, 16f); lineTo(11f, 11f); lineTo(14.5f, 14f); lineTo(20f, 7f)
        }
    }

    /** A finished measurement, used where a session has a score. */
    val Insights: ImageVector by lazy {
        stroked("Insights") {
            moveTo(4f, 20f); lineTo(4f, 13f)
            moveTo(9.5f, 20f); lineTo(9.5f, 8f)
            moveTo(15f, 20f); lineTo(15f, 15f)
            moveTo(20.5f, 20f); lineTo(20.5f, 4f)
        }
    }

    /** Range of motion: the ruler the mocks put beside a degree measurement. */
    val Straighten: ImageVector by lazy {
        stroked("Straighten") {
            moveTo(2.5f, 8f); lineTo(21.5f, 8f); lineTo(21.5f, 16f); lineTo(2.5f, 16f); close()
            moveTo(7f, 8f); lineTo(7f, 11.5f)
            moveTo(12f, 8f); lineTo(12f, 12.5f)
            moveTo(17f, 8f); lineTo(17f, 11.5f)
        }
    }

    /** The Library tab: a stack of recorded references. */
    val Library: ImageVector by lazy {
        stroked("Library") {
            moveTo(4f, 7.5f); lineTo(12f, 3.5f); lineTo(20f, 7.5f); lineTo(12f, 11.5f); close()
            moveTo(4f, 12f); lineTo(12f, 16f); lineTo(20f, 12f)
            moveTo(4f, 16.5f); lineTo(12f, 20.5f); lineTo(20f, 16.5f)
        }
    }

    /** The Patients tab: two people. */
    val People: ImageVector by lazy {
        stroked("People") {
            moveTo(9f, 11f); arcToRelative(3.2f, 3.2f, 0f, true, true, 0.01f, 0f); close()
            moveTo(3.5f, 20f); arcToRelative(5.5f, 5.5f, 0f, false, true, 11f, 0f)
            moveTo(16f, 4.6f); arcToRelative(3.2f, 3.2f, 0f, false, true, 0f, 6.2f)
            moveTo(17.5f, 14.8f); arcToRelative(5.5f, 5.5f, 0f, false, true, 3f, 5.2f)
        }
    }

    private fun stroked(
        name: String,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()
}
