package com.voicerewriter.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.voicerewriter.R

/**
 * Type system carried over from upstream OpenWispr (handoff): Mulish for UI/wordmark, IBM Plex Mono for the
 * uppercase eyebrow labels. Bundled OFL TTFs in res/font, so it's self-contained.
 */

val Mulish = FontFamily(
    Font(R.font.mulish_medium, FontWeight.Medium),
    Font(R.font.mulish_semibold, FontWeight.SemiBold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/** The "Soksak" wordmark: Mulish 600, tight tracking. */
val Wordmark = TextStyle(
    fontFamily = Mulish,
    fontWeight = FontWeight.SemiBold,
    fontSize = 26.sp,
    letterSpacing = (-0.02).em,
)

/** Mono eyebrow — uppercase state captions / section labels (wide tracking). */
val MonoEyebrow = TextStyle(
    fontFamily = PlexMono,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    letterSpacing = 0.18.em,
)

/**
 * Wide tracking is for Latin caps; Korean (the settings screen is translated) falls back to the system
 * font and the tracking pulls syllables apart ("화 자   게 이 트"). Drop it when the text has Hangul.
 */
fun TextStyle.trackedFor(text: String): TextStyle =
    if (hasHangul(text)) copy(letterSpacing = 0.em) else this

internal fun hasHangul(text: String): Boolean =
    text.any { it in '가'..'힣' || it in 'ᄀ'..'ᇿ' || it in '㄰'..'㆏' }

private val base = Typography()

val SoksakTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Mulish),
    displayMedium = base.displayMedium.copy(fontFamily = Mulish),
    displaySmall = base.displaySmall.copy(fontFamily = Mulish),
    headlineLarge = base.headlineLarge.copy(fontFamily = Mulish),
    headlineMedium = base.headlineMedium.copy(fontFamily = Mulish),
    headlineSmall = base.headlineSmall.copy(fontFamily = Mulish),
    titleLarge = base.titleLarge.copy(fontFamily = Mulish, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = Mulish, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = Mulish, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = Mulish),
    bodyMedium = base.bodyMedium.copy(fontFamily = Mulish),
    bodySmall = base.bodySmall.copy(fontFamily = Mulish),
    // Uppercase eyebrows (section headers / state captions) → IBM Plex Mono, wide tracking.
    labelLarge = base.labelLarge.copy(fontFamily = PlexMono, fontWeight = FontWeight.Medium, letterSpacing = 0.12.em),
    labelMedium = base.labelMedium.copy(fontFamily = PlexMono, fontWeight = FontWeight.Medium, letterSpacing = 0.16.em),
    labelSmall = base.labelSmall.copy(fontFamily = PlexMono, fontWeight = FontWeight.Medium, letterSpacing = 0.16.em),
)
