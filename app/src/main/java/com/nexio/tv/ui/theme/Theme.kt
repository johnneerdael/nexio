package com.nexio.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.nexio.tv.domain.model.AppFont
import com.nexio.tv.domain.model.AppTheme

data class NexioExtendedColors(
    val backgroundElevated: Color,
    val backgroundCard: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val focusRing: Color,
    val focusBackground: Color,
    val rating: Color
)

val LocalNexioColors = staticCompositionLocalOf {
    NexioColorScheme(ThemeColors.Ocean)
}

val LocalNexioExtendedColors = staticCompositionLocalOf {
    NexioExtendedColors(
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF242424),
        textSecondary = Color(0xFFB3B3B3),
        textTertiary = Color(0xFF808080),
        focusRing = ThemeColors.Ocean.focusRing,
        focusBackground = ThemeColors.Ocean.focusBackground,
        rating = Color(0xFFFFD700)
    )
}

val LocalAppTheme = staticCompositionLocalOf { AppTheme.WHITE }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NexioTheme(
    appTheme: AppTheme = AppTheme.WHITE,
    appFont: AppFont = AppFont.INTER,
    content: @Composable () -> Unit
) {
    val palette = ThemeColors.getColorPalette(appTheme)
    val colorScheme = NexioColorScheme(palette)

    val materialColorScheme = darkColorScheme(
        primary = colorScheme.Primary,
        onPrimary = colorScheme.OnPrimary,
        secondary = colorScheme.Secondary,
        onSecondary = colorScheme.OnSecondary,
        background = colorScheme.Background,
        surface = colorScheme.Surface,
        surfaceVariant = colorScheme.SurfaceVariant,
        onBackground = colorScheme.TextPrimary,
        onSurface = colorScheme.TextPrimary,
        onSurfaceVariant = colorScheme.TextSecondary,
        error = colorScheme.Error
    )

    val extendedColors = NexioExtendedColors(
        backgroundElevated = colorScheme.BackgroundElevated,
        backgroundCard = colorScheme.BackgroundCard,
        textSecondary = colorScheme.TextSecondary,
        textTertiary = colorScheme.TextTertiary,
        focusRing = colorScheme.FocusRing,
        focusBackground = colorScheme.FocusBackground,
        rating = colorScheme.Rating
    )

    CompositionLocalProvider(
        LocalNexioColors provides colorScheme,
        LocalNexioExtendedColors provides extendedColors,
        LocalAppTheme provides appTheme
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = buildNexioTypography(getFontFamily(appFont)),
            content = content
        )
    }
}

object NexioTheme {
    val colors: NexioColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalNexioColors.current

    val extendedColors: NexioExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNexioExtendedColors.current

    val currentTheme: AppTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTheme.current
}
