package com.s4me.tv

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.s4me.tv.remote.RemoteControlAdvertiser
import com.s4me.tv.remote.RemoteControlProtocol
import com.s4me.tv.remote.RemoteControlServer
import com.s4me.tv.theme.StrCommTheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface

private const val MIN_SPLASH_DURATION_MS = 500L

class MainActivity : ComponentActivity() {
  private var remoteServer: RemoteControlServer? = null
  private var remoteAdvertiser: RemoteControlAdvertiser? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    val splashScreen = installSplashScreen()
    super.onCreate(savedInstanceState)

    // installSplashScreen() alone can dismiss in a single frame on a fast box — too quick to
    // register as a deliberate splash rather than a flicker. Holding it for a short fixed minimum
    // makes it actually readable without turning it into an artificial "loading gate" tied to
    // network state (HomeScreen's own spinner is what should communicate that wait, not this).
    val splashStartedAt = SystemClock.elapsedRealtime()
    splashScreen.setKeepOnScreenCondition { SystemClock.elapsedRealtime() - splashStartedAt < MIN_SPLASH_DURATION_MS }

    // Every AsyncImage in the app (posters, hero backdrops) uses the default singleton loader —
    // setting this once here, rather than per-call-site, is what makes every one of them fade in
    // instead of popping in as soon as bytes arrive, which was a big part of the "clunky" feel.
    SingletonImageLoader.setSafe { context -> ImageLoader.Builder(context).crossfade(true).crossfade(280).build() }

    setContent {
      StrCommTheme {
        Surface(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { MainNavigation() }
      }
    }

    startRemoteControl()
  }

  override fun onDestroy() {
    remoteAdvertiser?.stop()
    remoteServer?.stop()
    super.onDestroy()
  }

  // Tries the well-known port first so a phone can pair by typing just an IP if NSD discovery
  // ever fails; only falls back to an OS-assigned one on the rare device where that port is
  // somehow already taken (manual pairing then needs NSD to learn the real port instead).
  private fun startRemoteControl() {
    val server =
      runCatching { RemoteControlServer(RemoteControlProtocol.DEFAULT_PORT).apply { start() } }
        .getOrElse { runCatching { RemoteControlServer(0).apply { start() } }.getOrNull() }
        ?: return
    remoteServer = server
    remoteAdvertiser = RemoteControlAdvertiser(applicationContext).apply { start(server.listeningPort) }
  }
}
