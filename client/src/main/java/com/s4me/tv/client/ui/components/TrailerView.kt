package com.s4me.tv.client.ui.components

import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A looping, chromeless YouTube embed used as an in-page preview (Home's hero, the title page's header).
 * It starts muted — mute=1 is what lets autoplay work at all — and [muted] can be turned off later from
 * the page's own button (a user gesture, which the player allows).
 *
 * YouTube only plays embeds that say who is embedding them, through the HTTP Referer: without one the
 * player shows "Errore 153 · errore di configurazione del video player". A URL loaded straight into a
 * WebView sends none, so it carries the app's identity in the form YouTube documents for native apps:
 * https://<app id>.
 *
 * MATCH_PARENT layout params are not optional: AndroidView attaches its view WRAP_CONTENT, and a
 * WRAP_CONTENT-tall WebView sizes itself to its content — it gives the page a zero-height layout
 * viewport, so everything 100% tall collapses (the trailer played in a 0 px box, painted black).
 *
 * Not every trailer can play embedded (embedding disabled, age-restricted, region-locked), so
 * [onPlaying] fires only once the embed's own player reports state 1; an error card, or no playback
 * within ~20 s, never fires it and the caller keeps showing its still image.
 */
@Composable
fun TrailerView(youtubeId: String, muted: Boolean, onPlaying: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val reportPlaying by rememberUpdatedState(onPlaying)
  val webView = remember(youtubeId) { trailerWebView(context, youtubeId) }
  var playing by remember(youtubeId) { mutableStateOf(false) }
  DisposableEffect(webView) { onDispose { webView.destroy() } }
  AndroidView(factory = { webView }, modifier = modifier)
  // A WebView doesn't follow the app's lifecycle by itself: without this, a trailer someone has
  // unmuted keeps playing its sound after they leave the app.
  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { webView.onPause() }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { webView.onResume() }

  // Asks the embedded player every half second whether it is actually playing (state 1).
  LaunchedEffect(webView) {
    repeat(40) {
      delay(500)
      when (webView.evaluate(PLAYER_STATE_JS)) {
        "1" -> {
          playing = true
          reportPlaying()
          return@LaunchedEffect
        }
        "\"error\"" -> return@LaunchedEffect
      }
    }
  }

  // Applies the mute state to the embedded player once it exists, and whenever it changes.
  LaunchedEffect(muted, playing) {
    if (playing) {
      webView.evaluateJavascript(
        "(function(){var p=document.getElementById('movie_player');if(p&&p.${if (muted) "mute" else "unMute"})p.${if (muted) "mute" else "unMute"}();})()",
        null,
      )
    }
  }
}

/** A preview, not a control: it never takes touches, so a drag that starts on it scrolls the page and
 *  a tap on it reaches whatever sits underneath (the hero opens its title). */
private class PassiveWebView(context: Context) : WebView(context) {
  override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
}

private fun trailerWebView(context: Context, youtubeId: String): WebView =
  PassiveWebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    setBackgroundColor(android.graphics.Color.BLACK)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    webChromeClient = WebChromeClient()
    loadUrl(
      "https://www.youtube.com/embed/$youtubeId" +
        "?autoplay=1&mute=1&controls=0&playsinline=1&loop=1&playlist=$youtubeId&rel=0&iv_load_policy=3",
      mapOf("Referer" to "https://${context.packageName}"),
    )
  }

private suspend fun WebView.evaluate(script: String): String? = suspendCancellableCoroutine { cont ->
  evaluateJavascript(script) { cont.resume(it) }
}

/** Runs in the embed page itself: `movie_player.getPlayerState()` is 1 while playing, and `.ytp-error`
 *  is the card YouTube shows when a video can't be played here. */
private const val PLAYER_STATE_JS =
  "(function(){if(document.querySelector('.ytp-error'))return 'error';" +
    "var p=document.getElementById('movie_player');return p&&p.getPlayerState?p.getPlayerState():-2;})()"
