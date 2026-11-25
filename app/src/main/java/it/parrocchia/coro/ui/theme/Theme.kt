package it.parrocchia.coro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import it.parrocchia.coro.domain.model.AppThemeMode

@Immutable
data class CoroColorPalette(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceHighlight: Color,
    val border: Color,
    val gold: Color,
    val goldDim: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val playGreen: Color,
    val playGreenFill: Color,
    val fadeAmber: Color,
    val fadeAmberFill: Color,
    val stopRed: Color,
    val stopRedFill: Color,
    val playedMuted: Color,
    val playedAccent: Color,
    val midiBadge: Color,
    val audioBadge: Color
)

private val DarkCoroPalette = CoroColorPalette(
    background = Color(0xFF0E131B),
    surface = Color(0xFF17202E),
    surfaceElevated = Color(0xFF202C3F),
    surfaceHighlight = Color(0xFF29384F),
    border = Color(0xFF33445E),
    gold = Color(0xFFF2B84B),
    goldDim = Color(0xFF47371A),
    textPrimary = Color(0xFFF4F7FB),
    textSecondary = Color(0xFFA9B7CB),
    playGreen = Color(0xFF258C59),
    playGreenFill = Color(0xFF36B877),
    fadeAmber = Color(0xFFD97D1A),
    fadeAmberFill = Color(0xFFFFA53B),
    stopRed = Color(0xFFC73636),
    stopRedFill = Color(0xFFFF5959),
    playedMuted = Color(0xFF1A2A24),
    playedAccent = Color(0xFF6CD69D),
    midiBadge = Color(0xFF3C6FD1),
    audioBadge = Color(0xFF8C52C9)
)

private val LightCoroPalette = CoroColorPalette(
    background = Color(0xFFF3F5FA),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFEBEEF6),
    surfaceHighlight = Color(0xFFDDE3EF),
    border = Color(0xFFC3CDDF),
    gold = Color(0xFF8F5E00),
    goldDim = Color(0xFFFFECC4),
    textPrimary = Color(0xFF121826),
    textSecondary = Color(0xFF4A5568),
    playGreen = Color(0xFF1E7E4E),
    playGreenFill = Color(0xFF2BA86A),
    fadeAmber = Color(0xFFC86D0D),
    fadeAmberFill = Color(0xFFE88A1F),
    stopRed = Color(0xFFC62828),
    stopRedFill = Color(0xFFE53935),
    playedMuted = Color(0xFFE4F3EA),
    playedAccent = Color(0xFF187542),
    midiBadge = Color(0xFF2B5EC2),
    audioBadge = Color(0xFF7B42B8)
)

private val LocalCoroPalette = staticCompositionLocalOf { DarkCoroPalette }

object CoroColors {
    val Background: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.background
    val Surface: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.surface
    val SurfaceElevated: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.surfaceElevated
    val SurfaceHighlight: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.surfaceHighlight
    val Border: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.border

    val Gold: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.gold
    val GoldDim: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.goldDim
    val TextPrimary: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.textPrimary
    val TextSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.textSecondary

    val PlayGreen: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.playGreen
    val PlayGreenFill: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.playGreenFill
    val FadeAmber: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.fadeAmber
    val FadeAmberFill: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.fadeAmberFill
    val StopRed: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.stopRed
    val StopRedFill: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.stopRedFill

    val PlayedMuted: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.playedMuted
    val PlayedAccent: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.playedAccent
    val MidiBadge: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.midiBadge
    val AudioBadge: Color
        @Composable @ReadOnlyComposable get() = LocalCoroPalette.current.audioBadge
}

private val CoroDarkColorScheme = darkColorScheme(
    primary = DarkCoroPalette.gold,
    onPrimary = Color(0xFF1C1405),
    primaryContainer = DarkCoroPalette.goldDim,
    onPrimaryContainer = DarkCoroPalette.gold,
    secondary = Color(0xFF7CB7FF),
    onSecondary = Color(0xFF081D36),
    secondaryContainer = DarkCoroPalette.surfaceElevated,
    onSecondaryContainer = DarkCoroPalette.textPrimary,
    background = DarkCoroPalette.background,
    onBackground = DarkCoroPalette.textPrimary,
    surface = DarkCoroPalette.surface,
    onSurface = DarkCoroPalette.textPrimary,
    surfaceVariant = DarkCoroPalette.surfaceElevated,
    onSurfaceVariant = DarkCoroPalette.textSecondary,
    outline = DarkCoroPalette.border,
    error = DarkCoroPalette.stopRed,
    onError = Color.White
)

private val CoroLightColorScheme = lightColorScheme(
    primary = LightCoroPalette.gold,
    onPrimary = Color.White,
    primaryContainer = LightCoroPalette.goldDim,
    onPrimaryContainer = Color(0xFF3B2500),
    secondary = Color(0xFF1D5BB8),
    onSecondary = Color.White,
    secondaryContainer = LightCoroPalette.surfaceElevated,
    onSecondaryContainer = LightCoroPalette.textPrimary,
    background = LightCoroPalette.background,
    onBackground = LightCoroPalette.textPrimary,
    surface = LightCoroPalette.surface,
    onSurface = LightCoroPalette.textPrimary,
    surfaceVariant = LightCoroPalette.surfaceElevated,
    onSurfaceVariant = LightCoroPalette.textSecondary,
    outline = LightCoroPalette.border,
    error = LightCoroPalette.stopRed,
    onError = Color.White
)

private val CoroTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold
    )
)

@Composable
fun isCoroDarkTheme(themeMode: AppThemeMode): Boolean = when (themeMode) {
    AppThemeMode.DARK -> true
    AppThemeMode.LIGHT -> false
    AppThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun CoroTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = isCoroDarkTheme(themeMode)
    val palette = if (useDark) DarkCoroPalette else LightCoroPalette
    val colorScheme = if (useDark) CoroDarkColorScheme else CoroLightColorScheme

    CompositionLocalProvider(LocalCoroPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CoroTypography,
            content = content
        )
    }
}
