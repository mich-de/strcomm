package com.s4me.tv.remote.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same brand palette as the TV app's theme/Color.kt — this is its phone-side companion, so it
// should read as the same product, not a differently-branded utility app: black, neutral greys and
// one red (Material Red 600, not Netflix's brand red).
private val AccentRed = Color(0xFFE53935)
private val SurfaceDark = Color(0xFF141414)
private val SurfaceDarkElevated = Color(0xFF232323)
private val BackgroundDark = Color(0xFF000000)
private val OnDark = Color(0xFFECECEC)
private val OnDarkMuted = Color(0xFFA3A3A3)

private val RemoteColorScheme =
  darkColorScheme(
    primary = AccentRed,
    onPrimary = OnDark,
    background = BackgroundDark,
    onBackground = OnDark,
    surface = SurfaceDark,
    onSurface = OnDark,
    surfaceVariant = SurfaceDarkElevated,
    onSurfaceVariant = OnDarkMuted,
    // M3's lavender tonal defaults otherwise show on chips and tonal buttons.
    secondaryContainer = Color(0xFF333333),
    onSecondaryContainer = OnDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = SurfaceDarkElevated,
  )

@Composable
fun StrCommRemoteTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = RemoteColorScheme, content = content)
}
