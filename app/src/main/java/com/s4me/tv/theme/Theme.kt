package com.s4me.tv.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val S4meDarkColorScheme =
  darkColorScheme(
    primary = AccentAmber,
    onPrimary = SurfaceDark,
    secondary = AccentAmberDim,
    onSecondary = OnDark,
    background = BackgroundDark,
    onBackground = OnDark,
    surface = SurfaceDark,
    onSurface = OnDark,
    surfaceVariant = SurfaceDarkElevated,
    onSurfaceVariant = OnDarkMuted,
    border = AccentAmber,
  )

@Composable
fun StrCommTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = S4meDarkColorScheme, typography = TvTypography, content = content)
}
