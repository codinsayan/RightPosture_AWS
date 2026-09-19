package com.example.rightposture.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The mocks' spacing and radius scale.
 *
 * [KeepOutActiveHud] is the one that is not a taste decision: during a set nothing tappable may sit
 * within it of a screen edge (§6), because the patient is setting the phone down and reaching past
 * it, and a stray tap there ends the set.
 */
object Space {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** The horizontal page gutter every screen uses. */
    val Gutter = 20.dp

    /** Height of the role tab bar. */
    val BottomNavHeight = 72.dp

    /** §6's edge exclusion during a live set. */
    val KeepOutActiveHud = 32.dp
}

object Radius {
    val Card = RoundedCornerShape(24.dp)
    val CardSmall = RoundedCornerShape(16.dp)
    val Chip = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(999.dp)
}
