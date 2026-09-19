package com.example.rightposture.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.rightposture.R

/**
 * The two families the mocks use.
 *
 * Jakarta carries everything a patient reads. Mono is reserved for values that are read as data
 * rather than as prose - invite codes, hold timers, scores in a label - because a fixed advance
 * width stops a counting number from shifting the layout under it every tick.
 *
 * Both are bundled rather than downloaded. §11 ships Hindi, and Devanagari is outside both
 * families' coverage; Android's fallback supplies those glyphs from the system font, which is the
 * behaviour we want - a missing glyph should degrade to something readable, not to a box.
 */
val Jakarta = FontFamily(
    Font(R.font.jakarta_400, FontWeight.Normal),
    Font(R.font.jakarta_500, FontWeight.Medium),
    Font(R.font.jakarta_600, FontWeight.SemiBold),
    Font(R.font.jakarta_700, FontWeight.Bold),
    Font(R.font.jakarta_800, FontWeight.ExtraBold),
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_400, FontWeight.Normal),
    Font(R.font.jetbrains_mono_500, FontWeight.Medium),
    Font(R.font.jetbrains_mono_700, FontWeight.Bold),
)

/**
 * The mocks' named scale.
 *
 * Kept as an object beside Material's [Typography] rather than folded into it, because two of these
 * styles exist for a reason Material has no slot for: the far-field styles are sized so the rep
 * count and hold timer stay legible from the 2-3 m a propped-up phone actually sits at (§11).
 * Calling that `displayLarge` would invite someone to use it for a page title.
 */
object Text {
    val DisplayHeroFarField = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.ExtraBold,
        fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-1.9).sp,
    )
    val DisplayTimerFarField = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-1.1).sp,
    )
    val DisplayCodeSpaced = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 11.2.sp,
    )
    val HeadlineLg = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.56).sp,
    )
    val HeadlineMd = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = (-0.22).sp,
    )
    val HeadlineSm = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 24.sp,
    )
    val TitleMd = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp,
    )
    val TitleSm = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp,
    )
    val BodyLg = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp,
    )
    val BodyMd = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp,
    )
    val BodySm = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp,
    )
    val LabelMonoSm = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.55.sp,
    )
    val LabelMonoHud = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Normal,
        fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.2.sp,
    )
}

/** Material's slots, filled from [Text] so any stock component inherits the same voice. */
val Typography = Typography(
    displayLarge = Text.DisplayHeroFarField,
    headlineLarge = Text.HeadlineLg,
    headlineMedium = Text.HeadlineMd,
    headlineSmall = Text.HeadlineSm,
    titleLarge = Text.HeadlineSm,
    titleMedium = Text.TitleMd,
    titleSmall = Text.TitleSm,
    bodyLarge = Text.BodyLg,
    bodyMedium = Text.BodyMd,
    bodySmall = Text.BodySm,
    labelLarge = Text.TitleSm,
    labelMedium = Text.BodySm,
    labelSmall = Text.LabelMonoSm,
)
