package com.voicerewriter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Soksak's "dusk" palette: lavender → violet → deep dusk, cool mist surfaces and plum ink, matching
 * the launcher icon's plum (#3B2F4A). Replaced the upstream OpenWispr "muted sunset" colours on
 * 2026-11-10 so the fork no longer looks like the original app. Centralized so every screen shares
 * one identity; the bubble and model menu (plain Views) mirror these values in their own constants.
 */

// ---- brand constants (also used for gradients / the bubble) ----
val BrandLavender = Color(0xFFA894DD)
val BrandViolet = Color(0xFF6E56A8) // primary
val BrandVioletDeep = Color(0xFF574289)
val BrandDusk = Color(0xFF4B3A6B)
val BrandPlum = Color(0xFF3B2F4A)
val MarkLight = Color(0xFFF7F5FB)

/** The signature dusk gradient (≈140°): lavender → violet → dusk. */
val DuskBrush: Brush
    get() = Brush.linearGradient(0f to BrandLavender, 0.52f to BrandViolet, 1f to BrandDusk)

private val LightColors = lightColorScheme(
    primary = BrandViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECE6F6),
    onPrimaryContainer = Color(0xFF2E2440),
    secondary = BrandDusk,
    onSecondary = MarkLight,
    secondaryContainer = Color(0xFFE9E3F2),
    onSecondaryContainer = Color(0xFF2E2440),
    tertiary = BrandLavender,
    onTertiary = Color(0xFF2E2440),
    background = Color(0xFFF5F3F8),             // mist
    onBackground = Color(0xFF2E2A38),           // ink
    surface = Color(0xFFFDFCFE),               // card
    onSurface = Color(0xFF2E2A38),
    surfaceVariant = Color(0xFFECE8F2),         // tint panel
    onSurfaceVariant = Color(0xFF6B6478),       // ink soft
    outline = Color(0xFFE1DCE9),               // hairline
    outlineVariant = Color(0xFFE1DCE9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBA6EE),               // lighter violet for contrast on night
    onPrimary = Color(0xFF2A1F45),
    primaryContainer = Color(0xFF4A3A6B),
    onPrimaryContainer = Color(0xFFECE6F6),
    secondary = BrandLavender,
    onSecondary = Color(0xFF2A1F45),
    secondaryContainer = Color(0xFF3E3352),
    onSecondaryContainer = Color(0xFFEEEAF4),
    tertiary = BrandLavender,
    background = Color(0xFF1E1A26),
    onBackground = Color(0xFFEEEAF4),
    surface = Color(0xFF282233),
    onSurface = Color(0xFFEEEAF4),
    surfaceVariant = Color(0xFF352E42),
    onSurfaceVariant = Color(0xFFC5BDD3),
    outline = Color(0xFF4A4258),
    outlineVariant = Color(0xFF352E42),
)

@Composable
fun SoksakTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = SoksakTypography,
        content = content,
    )
}
