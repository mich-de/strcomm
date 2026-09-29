package com.s4me.tv.client

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.s4me.tv.client.notifications.NewReleaseWorker
import com.s4me.tv.client.player.PipController
import com.s4me.tv.client.sync.ProgressSyncPreference
import com.s4me.tv.client.sync.ProgressSyncWorker
import com.s4me.tv.client.theme.StrCommClientTheme
import com.s4me.tv.client.theme.ThemeMode
import com.s4me.tv.client.theme.ThemePreference
import com.s4me.tv.engine.NewReleaseStore
import com.s4me.tv.engine.Tmdb
import com.s4me.tv.engine.TmdbApiKeyStore

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    ThemePreference.load(this)
    Tmdb.setApiKeyOverride(TmdbApiKeyStore(this).load())
    // Re-assert both schedules on every start rather than only when their Settings toggle first
    // turns them on — cheap (WorkManager no-ops an already-current periodic schedule) and makes
    // sure a previously enabled feature keeps running even if its WorkManager entry was ever lost.
    if (NewReleaseStore(this).enabled) NewReleaseWorker.schedule(this)
    if (ProgressSyncPreference.isEnabled(this)) {
      ProgressSyncWorker.schedule(this)
      // Plus one immediate run — opening the app on a different device than where you last
      // watched should pick up fresh progress right away, not after up to 30 minutes.
      ProgressSyncWorker.syncNow(this)
    }

    SingletonImageLoader.setSafe { context -> ImageLoader.Builder(context).crossfade(true).crossfade(200).build() }

    setContent {
      val mode by ThemePreference.mode.collectAsStateWithLifecycle()
      val dark =
        when (mode) {
          ThemeMode.SYSTEM -> isSystemInDarkTheme()
          ThemeMode.LIGHT -> false
          ThemeMode.DARK -> true
        }
      StrCommClientTheme(dark = dark) { ClientApp() }
    }
  }

  // Home/recents while a video is playing — the same moment YouTube/Netflix pop a floating
  // window instead of just stopping. PipController.available only tracks true while PlayerScreen
  // is both on screen AND actually playing (see its own doc), so this is a no-op the rest of the
  // time (paused on the video, or anywhere else in the app).
  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    if (PipController.available.value) {
      enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
    }
  }

  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    PipController.setInPip(isInPictureInPictureMode)
  }
}
