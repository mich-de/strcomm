package com.s4me.tv

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ConnectivityChecker
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.s4me.tv.engine.Net
import com.s4me.tv.engine.Tmdb
import com.s4me.tv.engine.TmdbApiKeyStore
import com.s4me.tv.remote.RemoteControlService
import com.s4me.tv.theme.StrCommTheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface

private const val MIN_SPLASH_DURATION_MS = 500L

class MainActivity : ComponentActivity() {
  @OptIn(ExperimentalCoilApi::class) // ConnectivityChecker override, see the image loader below
  override fun onCreate(savedInstanceState: Bundle?) {
    val splashScreen = installSplashScreen()
    super.onCreate(savedInstanceState)
    Tmdb.setApiKeyOverride(TmdbApiKeyStore(this).load())

    // installSplashScreen() alone can dismiss in a single frame on a fast box — too quick to
    // register as a deliberate splash rather than a flicker. Holding it for a short fixed minimum
    // makes it actually readable without turning it into an artificial "loading gate" tied to
    // network state (HomeScreen's own spinner is what should communicate that wait, not this).
    val splashStartedAt = SystemClock.elapsedRealtime()
    splashScreen.setKeepOnScreenCondition { SystemClock.elapsedRealtime() - splashStartedAt < MIN_SPLASH_DURATION_MS }

    // Every AsyncImage in the app (posters, hero backdrops) uses the default singleton loader —
    // setting this once here, rather than per-call-site, is what makes every one of them fade in
    // instead of popping in as soon as bytes arrive, which was a big part of the "clunky" feel.
    //
    // "Su Firestick non si vedono le copertine" — while the catalog itself loaded fine there. The
    // CDN checks out for any device (plain lossy WebP, same GlobalSign-rooted chain as the site),
    // so everything that differed between "catalog works" and "images don't" is removed here:
    // images now go through Net.client, the exact OkHttp stack (desktop User-Agent, cookie jar)
    // the working catalog requests use, instead of Coil's own bare client; Coil's separate
    // online/offline gate is bypassed (it refuses to touch the network whenever ConnectivityManager
    // misreports the active network, independently of whether OkHttp can actually reach it); and
    // hardware bitmaps are off on Amazon devices, a known trouble spot on some Fire TV GPUs. Any
    // image that still fails is logged under "StrCommImages" so one `adb logcat` pins it down.
    SingletonImageLoader.setSafe { context ->
      ImageLoader.Builder(context)
        .components {
          add(OkHttpNetworkFetcherFactory(callFactory = { Net.client }, connectivityChecker = { ConnectivityChecker.ONLINE }))
        }
        .allowHardware(!Build.MANUFACTURER.equals("Amazon", ignoreCase = true))
        .eventListener(
          object : EventListener() {
            override fun onError(request: ImageRequest, result: ErrorResult) {
              Log.w("StrCommImages", "immagine non caricata: ${request.data}", result.throwable)
            }
          }
        )
        .crossfade(true)
        .crossfade(280)
        .build()
    }

    setContent {
      StrCommTheme {
        Surface(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { MainNavigation() }
      }
    }

    // Runs as a foreground service (survives backgrounding — see RemoteControlService's own
    // comment) rather than being started/stopped here, so the local server outlives this
    // Activity instead of dying with it the moment the box's process gets reclaimed.
    RemoteControlService.start(this)
  }
}
