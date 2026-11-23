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

val DarkBackground = Color(0xFF0D0F14)
val DarkSurface = Color(0xFF171A21)
val DarkSurfaceVariant = Color(0xFF212632)
val DarkBorder = Color(0xFF2C3344)

val AccentAmber = Color(0xFFFFB300)
val AccentEmerald = Color(0xFF00E5A0)
val AccentCyan = Color(0xFF38BDF8)
val WarningAmber = Color(0xFFF59E0B)
val ErrorRose = Color(0xFFF43F5E)

val TextPrimary = Color(0xFFF3F4F6)
val TextSecondary = Color(0xFF9CA3AF)

private val MonotonicDarkColorScheme = darkColorScheme(
    primary = AccentAmber,
    onPrimary = Color(0xFF14110A),
    secondary = AccentEmerald,
    onSecondary = Color(0xFF051A12),
    tertiary = AccentCyan,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    error = ErrorRose,
    onError = Color.White
)

private val MonotonicTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        letterSpacing = (-1.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp
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
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp
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
