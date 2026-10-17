package com.s4me.tv.client.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Brand palette — same red accent as the TV app ("simil netflix anche nei colori": black, neutral
// greys, one red). Material's own Red 600, not Netflix's brand red. Dark is the primary look, but a
// light scheme is provided for Settings' "Chiaro".
private val Red = Color(0xFFE53935)
private val RedDark = Color(0xFFC62828)

private val DarkColors =
  darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    secondary = Red,
    onSecondary = Color.White,
    // Pure black with neutral greys, the way a streaming app sits behind its artwork.
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF232323),
    onSurfaceVariant = Color(0xFFB3B3B3),
    outline = Color(0xFF404040),
    // Tonal containers (selected chips, tonal buttons, the bottom bar) default to M3's lavender; these are the greys of
    // GreyButton and the poster rows instead.
    secondaryContainer = Color(0xFF333333),
    onSecondaryContainer = Color(0xFFF2F2F2),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF232323),
  )

private val LightColors =
  lightColorScheme(
    primary = RedDark,
    onPrimary = Color.White,
    secondary = RedDark,
    background = Color(0xFFFDFBF7),
    onBackground = Color(0xFF1A1C22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFFEDEEF2),
    onSurfaceVariant = Color(0xFF5A5F6A),
    secondaryContainer = Color(0xFFE7E8EC),
    onSecondaryContainer = Color(0xFF1A1C22),
  )

@Composable
fun StrCommClientTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  val colors = if (dark) DarkColors else LightColors
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as Activity).window
      WindowCompat.setDecorFitsSystemWindows(window, false)
      WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
    }
  }
  MaterialTheme(colorScheme = colors, typography = StrCommTypography, content = content)
}
