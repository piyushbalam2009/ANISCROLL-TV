package `in`.aniscroll.tv.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AniScrollDarkColorScheme = darkColorScheme(
    primary = TvColors.PrimaryText,
    onPrimary = TvColors.MainBackground,
    secondary = TvColors.SecondaryText,
    onSecondary = TvColors.MainBackground,
    tertiary = TvColors.SkipGreen,
    background = TvColors.MainBackground,
    onBackground = TvColors.PrimaryText,
    surface = TvColors.SecondaryBackground,
    onSurface = TvColors.PrimaryText,
    surfaceVariant = TvColors.CardBackground,
    onSurfaceVariant = TvColors.SecondaryText,
    outline = TvColors.Divider,
    error = TvColors.ErrorRed
)

@Composable
fun AniScrollTvTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AniScrollDarkColorScheme,
        typography = TvTypography,
        content = content
    )
}
