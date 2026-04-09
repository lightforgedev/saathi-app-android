package dev.lightforge.saathi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Saathi dark design system — pure dark + neon green
val SaathiGreen        = Color(0xFF3DFF7A)   // primary accent — neon green
val SaathiGreenDim     = Color(0xFF1A8C42)   // darker green for containers
val SaathiBackground   = Color(0xFF0D0D0D)   // near-black page background
val SaathiSurface      = Color(0xFF161616)   // card/sheet background
val SaathiSurface2     = Color(0xFF1E1E1E)   // elevated card
val SaathiSurface3     = Color(0xFF252525)   // highest surface
val SaathiBorder       = Color(0xFF2A2A2A)   // subtle dividers
val SaathiTextPrimary  = Color(0xFFFFFFFF)
val SaathiTextSecondary= Color(0xFF9E9E9E)
val SaathiError        = Color(0xFFFF5252)

private val DarkColorScheme = darkColorScheme(
    primary            = SaathiGreen,
    onPrimary          = Color(0xFF000000),
    primaryContainer   = SaathiGreenDim,
    onPrimaryContainer = SaathiGreen,
    secondary          = SaathiGreen,
    onSecondary        = Color(0xFF000000),
    secondaryContainer = Color(0xFF1A2B1E),
    onSecondaryContainer = SaathiGreen,
    tertiary           = SaathiGreen,
    onTertiary         = Color(0xFF000000),
    background         = SaathiBackground,
    onBackground       = SaathiTextPrimary,
    surface            = SaathiSurface,
    onSurface          = SaathiTextPrimary,
    surfaceVariant     = SaathiSurface2,
    onSurfaceVariant   = SaathiTextSecondary,
    outline            = SaathiBorder,
    error              = SaathiError,
    onError            = Color.White,
)

@Composable
fun SaathiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
