package it.parrocchia.coro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object CoroColors {
    val Background = Color(0xFF0E131B)
    val Surface = Color(0xFF17202E)
    val SurfaceElevated = Color(0xFF202C3F)
    val SurfaceHighlight = Color(0xFF29384F)
    val Border = Color(0xFF33445E)

    val Gold = Color(0xFFF2B84B)
    val GoldDim = Color(0xFF47371A)
    val TextPrimary = Color(0xFFF4F7FB)
    val TextSecondary = Color(0xFFA9B7CB)

    val PlayGreen = Color(0xFF258C59)
    val PlayGreenFill = Color(0xFF36B877)
    val FadeAmber = Color(0xFFD97D1A)
    val FadeAmberFill = Color(0xFFFFA53B)
    val StopRed = Color(0xFFC73636)
    val StopRedFill = Color(0xFFFF5959)

    val PlayedMuted = Color(0xFF1A2A24)
    val PlayedAccent = Color(0xFF6CD69D)
    val MidiBadge = Color(0xFF3C6FD1)
    val AudioBadge = Color(0xFF8C52C9)
}

private val CoroDarkColorScheme = darkColorScheme(
    primary = CoroColors.Gold,
    onPrimary = Color(0xFF1C1405),
    primaryContainer = CoroColors.GoldDim,
    onPrimaryContainer = CoroColors.Gold,
    secondary = Color(0xFF7CB7FF),
    onSecondary = Color(0xFF081D36),
    secondaryContainer = CoroColors.SurfaceElevated,
    onSecondaryContainer = CoroColors.TextPrimary,
    background = CoroColors.Background,
    onBackground = CoroColors.TextPrimary,
    surface = CoroColors.Surface,
    onSurface = CoroColors.TextPrimary,
    surfaceVariant = CoroColors.SurfaceElevated,
    onSurfaceVariant = CoroColors.TextSecondary,
    outline = CoroColors.Border,
    error = CoroColors.StopRed,
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
fun CoroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CoroDarkColorScheme,
        typography = CoroTypography,
        content = content
    )
}
