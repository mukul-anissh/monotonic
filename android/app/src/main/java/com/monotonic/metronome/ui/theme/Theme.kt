package com.monotonic.metronome.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ObsidianCanvas = Color(0xFF0D0E11)
val DarkBackground = ObsidianCanvas

val SurfaceGrouped = Color(0xFF16181D)
val SurfaceTonal = Color(0xFF181A20)
val SurfaceTonalElevated = Color(0xFF1C1F26)
val SurfaceTonalHigh = Color(0xFF22252E)

val BeatNormalSurface = Color(0xFF232630)
val BeatMuteSurface = Color(0xFF16181E)
val HairlineDivider = Color(0xFF232630)

val StudioAmber = Color(0xFFF5A623)
val ContrastSurface = Color(0xFFF2F3F5)
val ContrastInk = Color(0xFF0D0E11)

val TextPrimary = Color(0xFFF4F5F7)
val TextSecondary = Color(0xFF8E93A2)
val TextMuted = Color(0xFF5D6270)

// Semantic status dot / route-warning tokens only
val StatusEmerald = Color(0xFF34B37B)
val StatusAmber = Color(0xFFF5A623)
val StatusCoral = Color(0xFFE05A6D)

private val MonotonicDarkColorScheme = darkColorScheme(
    primary = StudioAmber,
    onPrimary = ContrastInk,
    secondary = ContrastSurface,
    onSecondary = ContrastInk,
    tertiary = StudioAmber,
    background = ObsidianCanvas,
    onBackground = TextPrimary,
    surface = SurfaceGrouped,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceTonal,
    onSurfaceVariant = TextSecondary,
    outline = HairlineDivider,
    error = StatusCoral,
    onError = Color.White
)

private val MonotonicTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 88.sp,
        lineHeight = 90.sp,
        letterSpacing = (-3).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    )
)

@Composable
fun MonotonicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MonotonicDarkColorScheme,
        typography = MonotonicTypography,
        content = content
    )
}
