package com.example.rightposture.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The app's one theme.
 *
 * Dynamic colour is deliberately off, and there is no dark scheme. Both are usually good defaults
 * and both are wrong here: this palette carries clinical meaning - amber is "form slipped", grey is
 * "never measured", green is "passed the 80 bar" - and letting the device recolour it would let a
 * wallpaper decide what a warning looks like. §11 also requires the far-field HUD to keep a known
 * contrast ratio at 2-3 m, which a theme we do not control cannot promise.
 */
private val Scheme = lightColorScheme(
    primary = Tokens.PrimaryContainer,
    onPrimary = Tokens.OnPrimary,
    primaryContainer = Tokens.PrimaryContainer,
    onPrimaryContainer = Tokens.OnPrimary,
    secondary = Tokens.Secondary,
    onSecondary = Tokens.OnPrimary,
    background = Tokens.CanvasBg,
    onBackground = Tokens.TextPrimary,
    surface = Tokens.SurfaceCard,
    onSurface = Tokens.TextPrimary,
    surfaceVariant = Tokens.SurfaceCardWarm,
    onSurfaceVariant = Tokens.TextSecondary,
    outline = Tokens.OutlineVariant,
    outlineVariant = Tokens.CanvasSubtle,
    error = Tokens.DangerInk,
    onError = Tokens.OnPrimary,
    errorContainer = Tokens.DangerWash,
    onErrorContainer = Tokens.DangerInk,
)

@Composable
fun RightPostureTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = Typography,
        content = content,
    )
}
