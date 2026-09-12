package com.baylee.billnest.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF080B10)
private val Graphite = Color(0xFF0F141C)
private val Surface = Color(0xFF141B24)
private val SurfaceRaised = Color(0xFF1A2330)
private val SurfaceStrong = Color(0xFF202B39)
private val Slate = Color(0xFF2D3A49)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF9EADBD)
private val Teal = Color(0xFF5ED6C0)
private val TealStrong = Color(0xFF173F3A)
private val Blue = Color(0xFF79B8FF)
private val Green = Color(0xFF77D89B)
private val Amber = Color(0xFFF1BD63)
private val Coral = Color(0xFFFF7E86)

private val BillNestDarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF04201C),
    primaryContainer = TealStrong,
    onPrimaryContainer = Color(0xFFC8FFF4),
    secondary = Blue,
    onSecondary = Color(0xFF071A2E),
    secondaryContainer = Color(0xFF16324E),
    onSecondaryContainer = Color(0xFFD6EAFF),
    tertiary = Green,
    onTertiary = Color(0xFF062414),
    tertiaryContainer = Color(0xFF153B25),
    onTertiaryContainer = Color(0xFFCCF7D8),
    error = Coral,
    onError = Color(0xFF31070B),
    errorContainer = Color(0xFF4A1D23),
    onErrorContainer = Color(0xFFFFDADD),
    background = Ink,
    onBackground = TextPrimary,
    surface = Graphite,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    outline = Slate,
    outlineVariant = Color(0xFF202A36),
    inverseSurface = TextPrimary,
    inverseOnSurface = Ink,
    inversePrimary = Color(0xFF0E7768),
    scrim = Color(0xCC000000),
    surfaceTint = Teal
)

private val BillNestTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 27.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 29.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

private val BillNestShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

object BillNestColors {
    val appBackground = Ink
    val card = Surface
    val cardRaised = SurfaceRaised
    val cardStrong = SurfaceStrong
    val border = Slate
    val textSecondary = TextSecondary
    val accent = Teal
    val positive = Green
    val info = Blue
    val warning = Amber
    val danger = Coral
}

@Composable
fun BillNestTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BillNestDarkColors,
        typography = BillNestTypography,
        shapes = BillNestShapes,
        content = content
    )
}
