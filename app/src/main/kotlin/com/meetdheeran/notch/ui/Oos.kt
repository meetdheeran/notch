package com.meetdheeran.notch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * OxygenOS 12's look, so Notch reads as part of the phone rather than an app on top of it.
 *
 * - Type: [FontFamily.Default] is the system typeface, which on a OnePlus is OnePlus Sans — so the island and the
 *   settings use the phone's own font (and follow it if the person changes the system font) without bundling one.
 * - Colour: OxygenOS 12 has no Material You; it keeps its own accent. [accent] starts as the OxygenOS default and is
 *   replaced at start-up by what the phone actually uses (see SystemAccent: on the OnePlus 7 it was #4F67D2).
 * - Shapes: big rounded cards (the quick-settings tiles are ~22 dp corners), springy, slightly long motion.
 */
object Oos {
    /** The phone's accent colour (OxygenOS default blue until the real one is read). */
    var accent by mutableStateOf(Color(0xFF2D7CF6))

    val font: FontFamily = FontFamily.Default

    /** Cards, panels and tiles. */
    const val CARD_RADIUS = 22
    const val TILE_RADIUS = 18

    // On the island (always black): white type and a few fixed signal colours.
    val good = Color(0xFF34C759)
    val warm = Color(0xFFFF9F0A)
    val cyan = Color(0xFF5AC8FA)
    val bad = Color(0xFFFF453A)
    val secondary = Color(0xFF9A9A9F)
    val tertiary = Color(0xFF636366)
}

/** Settings-screen colours that follow the phone's dark mode, OxygenOS-style (pure black in dark). */
object OosColors {
    val darkBg = Color(0xFF000000)
    val darkCard = Color(0xFF1C1C1E)
    val lightBg = Color(0xFFF2F2F7)
    val lightCard = Color(0xFFFFFFFF)
}

private fun Typography.inFont(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f), headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f, fontWeight = FontWeight.Medium),
    titleSmall = titleSmall.copy(fontFamily = f, fontWeight = FontWeight.Medium), bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f), labelSmall = labelSmall.copy(fontFamily = f),
)

/** The island's own text styles (on black, in the system font). */
object IslandType {
    val title = TextStyle(fontFamily = Oos.font, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)
    val body = TextStyle(fontFamily = Oos.font, fontSize = 12.sp, lineHeight = 16.sp)
    val label = TextStyle(fontFamily = Oos.font, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp)
    val small = TextStyle(fontFamily = Oos.font, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 13.sp)
    val big = TextStyle(fontFamily = Oos.font, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp)
}

@Composable
fun NotchTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val accent = Oos.accent
    val scheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = Color.White, background = OosColors.darkBg, surface = OosColors.darkCard,
        onBackground = Color.White, onSurface = Color.White, surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFF9A9A9F),
    ) else lightColorScheme(
        primary = accent, onPrimary = Color.White, background = OosColors.lightBg, surface = OosColors.lightCard,
        onBackground = Color.Black, onSurface = Color.Black, surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0xFF6C6C70),
    )
    MaterialTheme(colorScheme = scheme, typography = Typography().inFont(Oos.font), content = content)
}
