package com.s4me.tv.client.ui.components

import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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

  // Re-applied once it plays: the player builds (and rebuilds) its own DOM, and the captions module only
  // exists from then on. The stylesheet part is idempotent.
  LaunchedEffect(playing) {
    if (playing) {
      webView.evaluateJavascript(PREVIEW_STYLE_JS, null)
      webView.evaluateJavascript(FIT_PICTURE_JS, null)
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
    // The player's title bar, prev/pause/next buttons and logo show over the first seconds of any
    // embed, and the picture is letterboxed to 16:9 inside a box that isn't: a billboard wants the
    // picture alone, filling its box. Styling goes in as soon as the page is there, before the
    // caller's still image fades out to reveal it.
    webViewClient =
      object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
          view.evaluateJavascript(PREVIEW_STYLE_JS, null)
        }
      }
    loadUrl(
      "https://www.youtube.com/embed/$youtubeId" +
        "?autoplay=1&mute=1&controls=0&playsinline=1&loop=1&playlist=$youtubeId&rel=0&iv_load_policy=3",
      mapOf("Referer" to "https://${context.packageName}"),
    )
  }

private suspend fun WebView.evaluate(script: String): String? = suspendCancellableCoroutine { cont ->
  evaluateJavascript(script) { cont.resume(it) }
}

/** Strips the embed down to the picture, in the embed page itself. The selectors are YouTube's own class
 *  names, checked against its player stylesheet: everything inside the player except the video's
 *  container goes (title bar, centre buttons, logo, captions, end cards — YouTube's own
 *  `ytp-player-in-bar-disable-ui` does the same), and the `<video>` is forced to fill the page with
 *  object-fit: cover instead of the letterbox box the player's script sizes it to (viewport units, since
 *  the container itself has no height of its own). The id selector outranks the player's own rules.
 *  The error card is hidden too but stays in the DOM, so PLAYER_STATE_JS still finds it. Captions are
 *  also asked to unload — best effort: if YouTube renames any of this, the embed just looks as it did. */
private const val PREVIEW_STYLE_JS =
  "(function(){if(!document.getElementById('sc-preview')){var s=document.createElement('style');s.id='sc-preview';" +
    "s.textContent='#movie_player>:not(.html5-video-container){display:none!important}" +
    "#movie_player .html5-main-video{position:absolute!important;left:0!important;top:0!important;" +
    "width:100vw!important;height:100vh!important;object-fit:cover!important;" +
    "transform:scale(var(--sc-zoom,1))!important;transition:transform .6s ease!important}';document.head.appendChild(s);}" +
    "var p=document.getElementById('movie_player');if(p&&p.unloadModule)p.unloadModule('captions');})()"

/** Many trailers carry their letterbox inside the picture — a 2.39:1 film in a 16:9 frame has black bars of
 *  its own, which `cover` can't know about and which showed as a black band across the top of the billboard.
 *  This looks for them in the page itself: for ~9 s it draws the frame to a tiny canvas twice a second, finds
 *  the first row with any light in it, keeps the smallest bar seen (a fade-in or a dark scene only ever
 *  looks like a bigger one) and, assuming the bars are symmetric (burnt-in subtitles can sit in the
 *  bottom one), zooms the picture by just enough to push them out of the box through `--sc-zoom`. Canvas
 *  reads of the player's own MSE video aren't tainted; if anything throws, the picture stays as it was. */
private const val FIT_PICTURE_JS =
  "(function(){if(window.__scFit)return;var v=document.querySelector('.html5-main-video');if(!v)return;window.__scFit=1;" +
    "var W=64,H=36,c=document.createElement('canvas');c.width=W;c.height=H;" +
    "var x=c.getContext('2d',{willReadFrequently:true}),bar=1,seen=0,t0=Date.now();" +
    "var id=setInterval(function(){try{if(v.readyState>1&&v.videoWidth>1){x.drawImage(v,0,0,W,H);" +
    "var d=x.getImageData(0,0,W,H).data,first=-1;" +
    "for(var r=0;r<H&&first<0;r++){var m=0;for(var q=0;q<W;q++){var o=(r*W+q)*4,l=Math.max(d[o],d[o+1],d[o+2]);if(l>m)m=l}if(m>40)first=r}" +
    "if(first>=0){seen++;bar=Math.min(bar,(first+1)/H);if(seen>=3){" +
    "var cw=innerWidth,ch=innerHeight,fh=v.videoHeight*Math.max(cw/v.videoWidth,ch/v.videoHeight);" +
    "var k=bar>0.04?Math.min(1.6,Math.max(1,ch/((1-2*bar)*fh))):1;" +
    "document.documentElement.style.setProperty('--sc-zoom',k.toFixed(3))}}}}catch(e){clearInterval(id)}" +
    "if(Date.now()-t0>9000)clearInterval(id)},500)})()"

/** Runs in the embed page itself: `movie_player.getPlayerState()` is 1 while playing, and `.ytp-error`
 *  is the card YouTube shows when a video can't be played here. */
private const val PLAYER_STATE_JS =
  "(function(){if(document.querySelector('.ytp-error'))return 'error';" +
    "var p=document.getElementById('movie_player');return p&&p.getPlayerState?p.getPlayerState():-2;})()"
