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

// Brand palette — same amber accent as the TV app, tuned for a touch surface. Dark is the primary
// look (a streaming app), but a light scheme is provided so the system setting is respected.
private val Amber = Color(0xFFFFA726)
private val AmberDark = Color(0xFFB26A12)

private val DarkColors =
  darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF12141A),
    secondary = Amber,
    onSecondary = Color(0xFF12141A),
    // Near-black with neutral greys, the way a streaming app sits behind its artwork.
    background = Color(0xFF0A0A0B),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF141416),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF242427),
    onSurfaceVariant = Color(0xFFB3B3B3),
    outline = Color(0xFF3F3F44),
    // Tonal containers (selected chips, tonal buttons, the bottom bar) default to M3's lavender; these are the greys of
    // GreyButton and the poster rows instead.
    secondaryContainer = Color(0xFF3A3A3C),
    onSecondaryContainer = Color(0xFFF2F2F2),
    surfaceContainerLowest = Color(0xFF0A0A0B),
    surfaceContainerLow = Color(0xFF101012),
    surfaceContainer = Color(0xFF141416),
    surfaceContainerHigh = Color(0xFF1C1C1F),
    surfaceContainerHighest = Color(0xFF242427),
  )

private val LightColors =
  lightColorScheme(
    primary = AmberDark,
    onPrimary = Color.White,
    secondary = AmberDark,
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
  MaterialTheme(colorScheme = colors, content = content)
}
