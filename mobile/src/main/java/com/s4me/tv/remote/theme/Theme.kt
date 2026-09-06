package com.s4me.tv.remote.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same brand palette as the TV app's theme/Color.kt — this is its phone-side companion, so it
// should read as the same product, not a differently-branded utility app.
private val AccentAmber = Color(0xFFFFA726)
private val SurfaceDark = Color(0xFF12141A)
private val BackgroundDark = Color(0xFF0B0D12)
private val OnDark = Color(0xFFECEDEE)
private val OnDarkMuted = Color(0xFFA0A4AC)

private val RemoteColorScheme =
  darkColorScheme(
    primary = AccentAmber,
    onPrimary = SurfaceDark,
    background = BackgroundDark,
    onBackground = OnDark,
    surface = SurfaceDark,
    onSurface = OnDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = OnDarkMuted,
  )

@Composable
fun StrCommRemoteTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = RemoteColorScheme, content = content)
}
