package com.classmemory.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

@Immutable
data class ClassMemoryPalette(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val primary: Color,
    val onPrimary: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val success: Color,
    val successBackground: Color,
    val warning: Color,
    val warningBackground: Color,
    val error: Color,
    val errorBackground: Color,
)

private val LightPalette = ClassMemoryPalette(
    background = Color(0xFFF3F7F7), surface = Color.White, surfaceAlt = Color(0xFFE8F1F2),
    primary = Color(0xFF0D6471), onPrimary = Color.White, text = Color(0xFF173139),
    muted = Color(0xFF587078), border = Color(0xFFD4E2E4),
    success = Color(0xFF167052), successBackground = Color(0xFFE1F5EA),
    warning = Color(0xFF976111), warningBackground = Color(0xFFFFF2D9),
    error = Color(0xFFB13C49), errorBackground = Color(0xFFFCEAEC),
)

private val DarkPalette = ClassMemoryPalette(
    background = Color(0xFF10191D), surface = Color(0xFF1B282D), surfaceAlt = Color(0xFF25363C),
    primary = Color(0xFF79D5DC), onPrimary = Color(0xFF0E2F36), text = Color(0xFFE9F3F3),
    muted = Color(0xFFA9BEC3), border = Color(0xFF3C5157),
    success = Color(0xFF7CD0A9), successBackground = Color(0xFF1D3A33),
    warning = Color(0xFFF0C475), warningBackground = Color(0xFF3E3422),
    error = Color(0xFFFFB4B9), errorBackground = Color(0xFF44282D),
)

private val LocalClassMemoryPalette = compositionLocalOf { LightPalette }

object ClassMemoryColors {
    val current: ClassMemoryPalette
        @Composable get() = LocalClassMemoryPalette.current
}

object ClassMemorySpacing {
    val screen = 23.dp
    val section = 24.dp
    val card = 16.dp
    val item = 10.dp
}

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(15.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun ClassMemoryTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = palette.primary, onPrimary = palette.onPrimary,
        primaryContainer = palette.surfaceAlt, onPrimaryContainer = palette.primary,
        secondary = palette.primary, onSecondary = palette.onPrimary,
        secondaryContainer = palette.surfaceAlt, onSecondaryContainer = palette.text,
        tertiary = palette.warning, onTertiary = palette.background,
        tertiaryContainer = palette.warningBackground, onTertiaryContainer = palette.warning,
        background = palette.background, onBackground = palette.text,
        surface = palette.surface, onSurface = palette.text,
        surfaceVariant = palette.surfaceAlt, onSurfaceVariant = palette.muted,
        outline = palette.border, outlineVariant = palette.border,
        error = palette.error, onError = palette.background,
        errorContainer = palette.errorBackground, onErrorContainer = palette.error,
    ) else lightColorScheme(
        primary = palette.primary, onPrimary = palette.onPrimary,
        primaryContainer = palette.surfaceAlt, onPrimaryContainer = palette.primary,
        secondary = palette.primary, onSecondary = palette.onPrimary,
        secondaryContainer = palette.surfaceAlt, onSecondaryContainer = palette.text,
        tertiary = palette.warning, onTertiary = palette.onPrimary,
        tertiaryContainer = palette.warningBackground, onTertiaryContainer = palette.warning,
        background = palette.background, onBackground = palette.text,
        surface = palette.surface, onSurface = palette.text,
        surfaceVariant = palette.surfaceAlt, onSurfaceVariant = palette.muted,
        outline = palette.border, outlineVariant = palette.border,
        error = palette.error, onError = palette.onPrimary,
        errorContainer = palette.errorBackground, onErrorContainer = palette.error,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalClassMemoryPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
