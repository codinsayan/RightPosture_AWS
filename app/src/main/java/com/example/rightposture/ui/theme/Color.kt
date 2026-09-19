package com.example.rightposture.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette from `UI_screens/`, as named tokens.
 *
 * Names match the mocks' Tailwind tokens exactly, so a colour in a `code.html` can be found here
 * without translation. Screens should reference these through [RightPostureColors] rather than
 * naming a hex value inline - the point of the indirection is that "warning" can change once.
 *
 * Two families do real work and are worth knowing about:
 *
 * - **status-absent** is the colour of a measurement that was never taken. It exists because
 *   §1's clinical-safety rule says absent is not zero, and a value that cannot be scored has to
 *   render as visibly different from a low score rather than as a bar at the floor.
 * - **tag-exercise / tag-hold** separate the two exercise kinds everywhere they are listed, since
 *   a hold and a cyclic exercise are measured by different things entirely.
 */
object Tokens {
    // Ground and surfaces
    val CanvasBg = Color(0xFFFBF9F4)
    val CanvasSubtle = Color(0xFFF4EFEB)
    val SurfaceCard = Color(0xFFFFFFFF)
    val SurfaceCardWarm = Color(0xFFEFEAE2)
    val SurfaceTintSand = Color(0xFFF8F5EE)

    // Ink
    val TextPrimary = Color(0xFF1F2421)
    val TextSecondary = Color(0xFF6E726E)
    val TextMuted = Color(0xFF8C8F8C)
    val OutlineVariant = Color(0xFFC1C8C2)

    // Brand
    val Primary = Color(0xFF012D1D)
    val PrimaryContainer = Color(0xFF1B4332)
    val OnPrimary = Color(0xFFFFFFFF)
    val Secondary = Color(0xFF8E4D2A)

    // Status: success / warning / danger, each an ink and a wash
    val SuccessInk = Color(0xFF2E7D32)
    val SuccessWash = Color(0xFFE8F5E9)
    val WarningInk = Color(0xFFD97706)
    val WarningWash = Color(0xFFFEF3C7)
    val DangerInk = Color(0xFFC62828)
    val DangerWash = Color(0xFFFEE2E2)

    /** Not measured. Never used for a low score - see the note on this object. */
    val AbsentInk = Color(0xFF9E9E9E)
    val AbsentWash = Color(0xFFF3F4F6)

    // Exercise category
    val TagExerciseInk = Color(0xFF2E6B3A)
    val TagExerciseWash = Color(0xFFE7F0E8)
    val TagHoldInk = Color(0xFF1B4F72)
    val TagHoldWash = Color(0xFFE3EEF6)

    // Charts
    /**
     * The marker pen behind a measurement in generated prose.
     *
     * A wash rather than a text colour, because the numbers in a summary are the part a reader
     * checks against the charts beside it and colour alone is easy to miss in a paragraph. Mild on
     * purpose: it has to survive being read at length without turning the paragraph into a warning.
     */
    val HighlightWash = Color(0xFFFDF0B4)

    val ChartTrace = Color(0xFF1565C0)
    val ChartGround = Color(0x0D1F2421)
}
