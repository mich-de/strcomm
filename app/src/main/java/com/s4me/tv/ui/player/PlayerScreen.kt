package com.s4me.tv.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.DESKTOP_UA
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.Net
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchProgressStore
import com.s4me.tv.remote.PlaybackCommand
import com.s4me.tv.remote.PlaybackControlBridge
import com.s4me.tv.remote.PlaybackStatus
import com.s4me.tv.ui.components.LoadingIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val TAG = "PlayerScreen"
private const val SEEK_STEP_MS = 10_000L

/** The Activity behind a Compose `LocalContext`, which on Android TV is wrapped in one or more
 *  ContextWrappers (theme wrapper, etc.) rather than being the Activity directly. */
private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

/**
 * Routes a resolved PLAYABLE item to a native ExoPlayer (serverId == "hls", the common case —
 * see StreamingCommunityChannel.resolveHlsUrl) or straight to the WebView fallback (serverId ==
 * "webview", used when that resolution failed). If native playback itself errors out at runtime
 * — a network shape ExoPlayer's HttpDataSource can't get through even though a plain HTTP client
 * resolved and fetched the manifest fine during [findVideos] — it falls back to the WebView
 * loading `item.extra` (the vixcloud embed page kept around for exactly this).
 */
@Composable
fun PlayerScreen(item: StreamItem, modifier: Modifier = Modifier) {
  if (item.url.isBlank()) {
    Box(modifier = modifier.fillMaxSize()) {
      Text(
        text = "Nessun link riproducibile trovato",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.align(Alignment.Center),
      )
    }
    return
  }

  // The item currently playing. Starts as the navigation argument and is swapped in place when an
  // episode ends and the next one auto-resolves — no navigation round-trip, the player just rebuilds
  // (everything below is keyed on activeItem.url).
  var activeItem by remember { mutableStateOf(item) }
  var switchingNext by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  // Neither PlayerView nor WebView tells Android "playback is happening" on its own, and the box's
  // own standby timer follows the same system idle signal as the screen timeout — with no remote
  // input for the length of a movie, both fire ("dopo 20 minuti... il box va in standby"). This is
  // the same FLAG_KEEP_SCREEN_ON Netflix/YouTube set while a video is on screen; scoped to the whole
  // PlayerScreen (not per-player) so it stays set across the auto-next-episode activeItem swap
  // instead of flickering off between episodes.
  //
  // `LocalContext.current` under androidx.tv's setContent is a ContextThemeWrapper, not the
  // Activity — the earlier `context as? Activity` was silently null, so the flag was never actually
  // added and the box kept sleeping. Unwrap the ContextWrapper chain to reach the real Activity;
  // the per-View keepScreenOn set on the PlayerView/WebView below is a second, independent guard
  // that doesn't depend on finding the Activity at all.
  val context = LocalContext.current
  DisposableEffect(Unit) {
    val window = context.findActivity()?.window
    window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
  }

  // The navigable movie/episode this playback belongs to, rebuilt from the fields findVideos
  // carried onto the PLAYABLE — used both as the "Continua a guardare" entry (progress key) and to
  // re-resolve a fresh embed URL for the WebView fallback.
  val originItem = remember(activeItem.originId) { playbackOrigin(activeItem) }
  var fellBackToWebView by remember(activeItem.url) { mutableStateOf(false) }

  // Netflix-style binge: when an episode plays to the end, resolve the next one in the same season
  // and swap it in automatically. Movies (episode == null) just end.
  fun onPlaybackEnded() {
    val current = activeItem
    if (switchingNext || current.episode == null) return
    switchingNext = true
    scope.launch {
      val next = withContext(Dispatchers.IO) { runCatching { resolveNextEpisode(current) }.getOrNull() }
      if (next != null) activeItem = next
      switchingNext = false
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    if (activeItem.serverId == "hls" && !fellBackToWebView) {
      NativeHlsPlayer(
        item = activeItem,
        originItem = originItem,
        onFatalError = { fellBackToWebView = true },
        onEnded = ::onPlaybackEnded,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      WebViewPlayer(item = activeItem, originItem = originItem, modifier = Modifier.fillMaxSize())
    }

    if (switchingNext) {
      Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {
        Text(
          text = "Prossimo episodio…",
          style = MaterialTheme.typography.headlineSmall,
          color = Color.White,
          modifier = Modifier.align(Alignment.Center),
        )
      }
    }
  }
}

/**
 * Finds the episode after [playable] in its season and resolves it to a fresh PLAYABLE.
 * Same-season only; the last episode of a season just ends. Null when anything is missing.
 */
private suspend fun resolveNextEpisode(playable: StreamItem): StreamItem? {
  val originId = playable.originId ?: return null
  val parts = originId.split("|")
  if (parts.size < 3) return null
  val seasonNumber = playable.season ?: return null
  val channel = ChannelRegistry.byId(playable.channelId) ?: return null
  val seasonItem =
    StreamItem(
      title = "Stagione $seasonNumber",
      url = "${parts[0]}|${parts[1]}|$seasonNumber",
      kind = ItemKind.SEASON,
      channelId = playable.channelId,
      seriesTitle = playable.seriesTitle,
      season = seasonNumber,
    )
  val episodes = channel.list(seasonItem).items
  val currentIndex = episodes.indexOfFirst { it.url == originId }
  if (currentIndex < 0) return null
  val nextEpisode = episodes.getOrNull(currentIndex + 1) ?: return null
  return channel.findVideos(nextEpisode).firstOrNull()
}

/** Rebuild the source MOVIE/EPISODE StreamItem from a PLAYABLE's carried fields. */
private fun playbackOrigin(item: StreamItem): StreamItem? =
  item.originId?.takeIf { it.isNotBlank() }?.let { id ->
    StreamItem(
      title = item.contentTitle ?: item.title,
      url = id,
      kind = if (item.episode != null) ItemKind.EPISODE else ItemKind.MOVIE,
      channelId = item.channelId,
      thumbnail = item.thumbnail,
      backdrop = item.backdrop,
      plot = item.plot,
      year = item.year,
      quality = item.quality,
      seriesTitle = item.seriesTitle,
      season = item.season,
      episode = item.episode,
    )
  }

@Composable
private fun NativeHlsPlayer(
  item: StreamItem,
  originItem: StreamItem?,
  onFatalError: () -> Unit,
  onEnded: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val progressStore = remember { WatchProgressStore(context) }
  val resumeFromMs = remember(item.originId) { originItem?.let { progressStore.positionFor(it.url) } ?: 0L }

  var showInfo by remember(item.url) { mutableStateOf(false) }
  var infoRequestId by remember(item.url) { mutableStateOf(0) }
  var showHint by remember(item.url) { mutableStateOf(true) }
  var feedback by remember(item.url) { mutableStateOf<String?>(null) }
  var positionMs by remember(item.url) { mutableStateOf(0L) }
  var durationMs by remember(item.url) { mutableStateOf(0L) }
  var videoInfo by remember(item.url) { mutableStateOf<String?>(null) }
  var showTracksMenu by remember(item.url) { mutableStateOf(false) }
  var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

  // The remote-control legend is only useful for the first moment on screen — leave it up during
  // actual viewing and it's just a caption burned into the movie.
  LaunchedEffect(item.url) {
    delay(4000)
    showHint = false
  }

  // Show-and-extend rather than toggle: a remote that sends key-repeat events while UP is held
  // would otherwise flip showInfo on/off/on with every repeat, landing in an unpredictable end
  // state. Each press just (re)starts this 6s countdown, so holding or mashing UP keeps the
  // overlay up instead of flickering it.
  LaunchedEffect(infoRequestId) {
    if (infoRequestId == 0) return@LaunchedEffect
    showInfo = true
    delay(6000)
    showInfo = false
  }

  val exoPlayer =
    remember(item.url) {
      // Fetch the .m3u8 and its segments through the app's own OkHttp client — the same one that
      // successfully loads every site page — instead of ExoPlayer's default HttpURLConnection stack.
      // vixcloud's CDN 403s the default stack (different TLS fingerprint, no session cookies) even
      // though the manifest is perfectly fetchable via OkHttp; routing playback through OkHttp keeps
      // it on the native player (which renders fine) instead of forcing the WebView fallback (which
      // can't composite video on this box's weak GPU). This is the fix for "il video non parte".
      val dataSourceFactory =
        OkHttpDataSource.Factory(Net.client)
          .setUserAgent(DESKTOP_UA)
          .setDefaultRequestProperties(mapOf("Referer" to (item.referer ?: "https://vixcloud.co/")))
      val mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(item.url))
      ExoPlayer.Builder(context).build().apply {
        addListener(
          object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
              Log.w(TAG, "native HLS playback failed for ${item.url}, falling back to WebView", error)
              onFatalError()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
              if (playbackState == Player.STATE_ENDED) onEnded()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
              val format = this@apply.videoFormat
              val codec = format?.sampleMimeType?.substringAfterLast('/')?.uppercase()
              val kbps = format?.bitrate?.takeIf { it > 0 }?.let { it / 1000 }
              videoInfo =
                listOfNotNull("${videoSize.width}×${videoSize.height}", codec, kbps?.let { "$it kbps" }).joinToString("  ·  ")
                  .takeIf { it.isNotBlank() }
            }
          }
        )
        setMediaSource(mediaSource)
        prepare()
        // Resume where the user left off (0 = start). seekTo before playback begins so there's no
        // visible jump — ExoPlayer just buffers straight to the resume point.
        if (resumeFromMs > 0) seekTo(resumeFromMs)
        playWhenReady = true
      }
    }

  // Persist progress while watching (every 10s) and once more on the way out, so "Continua a
  // guardare" reflects where you actually stopped. Only when we know the origin content id.
  if (originItem != null) {
    LaunchedEffect(exoPlayer) {
      while (true) {
        delay(10_000)
        val pos = exoPlayer.currentPosition.coerceAtLeast(0)
        val dur = exoPlayer.duration
        if (dur > 0 && pos > 0) progressStore.save(originItem, pos, dur)
      }
    }
  }

  // Lets the phone companion app show a "now playing" bar and send transport commands — see
  // RemoteControlServer's /now-playing and /control. Always-on (unlike the showInfo-gated poll
  // below, which only runs while the on-screen overlay is visible), since the phone's bar should
  // stay live whether or not that overlay happens to be up right now.
  LaunchedEffect(exoPlayer, item.url) {
    val seriesTitle = item.seriesTitle
    val contentTitle = item.contentTitle
    val title = seriesTitle ?: contentTitle ?: item.title
    while (true) {
      PlaybackControlBridge.publish(
        PlaybackStatus(
          title = title,
          isPlaying = exoPlayer.isPlaying,
          positionMs = exoPlayer.currentPosition.coerceAtLeast(0),
          durationMs = exoPlayer.duration.coerceAtLeast(0),
        )
      )
      delay(1000)
    }
  }

  LaunchedEffect(exoPlayer) {
    PlaybackControlBridge.commands.collect { command ->
      when (command) {
        PlaybackCommand.PLAY -> {
          exoPlayer.playWhenReady = true
          feedback = "▶"
        }
        PlaybackCommand.PAUSE -> {
          exoPlayer.playWhenReady = false
          feedback = "⏸"
        }
        PlaybackCommand.TOGGLE -> {
          exoPlayer.playWhenReady = !exoPlayer.playWhenReady
          feedback = if (exoPlayer.playWhenReady) "▶" else "⏸"
        }
        PlaybackCommand.SEEK_BACK -> {
          exoPlayer.seekTo((exoPlayer.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
          feedback = "⏪ 10s"
        }
        PlaybackCommand.SEEK_FORWARD -> {
          exoPlayer.seekTo(exoPlayer.currentPosition + SEEK_STEP_MS)
          feedback = "⏩ 10s"
        }
        // The actual exit is Navigation.kt popping the back stack (PlayerScreen has no
        // onNavigate/onBack of its own to call) — pausing here too avoids a beat of audio
        // continuing under the pop transition's fade.
        PlaybackCommand.STOP -> exoPlayer.playWhenReady = false
      }
    }
  }

  DisposableEffect(exoPlayer) {
    onDispose {
      PlaybackControlBridge.publish(null)
      if (originItem != null) {
        val pos = exoPlayer.currentPosition.coerceAtLeast(0)
        val dur = exoPlayer.duration
        if (dur > 0 && pos > 0) progressStore.save(originItem, pos, dur)
      }
      exoPlayer.release()
    }
  }

  // Only poll position/duration while the info overlay is actually visible.
  LaunchedEffect(showInfo, exoPlayer) {
    while (showInfo) {
      positionMs = exoPlayer.currentPosition.coerceAtLeast(0)
      durationMs = exoPlayer.duration.coerceAtLeast(0)
      delay(500)
    }
  }

  LaunchedEffect(feedback) {
    if (feedback != null) {
      delay(1200)
      feedback = null
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { ctx ->
        PlayerView(ctx).apply {
          playerViewRef = this
          player = exoPlayer
          useController = false
          // Independent of the window flag above: a visible attached View with keepScreenOn holds
          // the display awake on its own, no Activity lookup needed. Media3's PlayerView does not
          // set this itself.
          keepScreenOn = true
          isFocusable = true
          isFocusableInTouchMode = true
          setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
              KeyEvent.KEYCODE_DPAD_UP -> {
                infoRequestId++
                true
              }
              KeyEvent.KEYCODE_DPAD_DOWN -> {
                showTracksMenu = true
                true
              }
              KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                feedback = if (exoPlayer.playWhenReady) "▶" else "⏸"
                true
              }
              KeyEvent.KEYCODE_DPAD_LEFT -> {
                exoPlayer.seekTo((exoPlayer.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
                feedback = "⏪ 10s"
                true
              }
              KeyEvent.KEYCODE_DPAD_RIGHT -> {
                exoPlayer.seekTo(exoPlayer.currentPosition + SEEK_STEP_MS)
                feedback = "⏩ 10s"
                true
              }
              else -> false
            }
          }
          requestFocus()
        }
      },
      // `factory` only runs once for this AndroidView's lifetime — it does NOT re-run when the
      // auto-next-episode swap remembers a brand new ExoPlayer for the new item.url. Without this
      // `update`, the PlayerView stayed attached to the OLD (by-then-released) player forever: the
      // new player decoded and played the next episode's AUDIO just fine (that needs no Surface),
      // while the screen kept showing the previous episode's last video frame, frozen ("si ferma
      // sul episodio precedente mentre sento l'audio del episodio successivo"). Reassigning here on
      // every recomposition is cheap — PlayerView/ExoPlayer no-op when it's already the same player.
      // The same transition was also silently dropping D-pad focus ("non funziona i dpad, non vedo
      // info etc" right after the swap) — `requestFocus()` in `factory` only ever fires once, at
      // initial creation, so if focus drifted off the PlayerView during the "Prossimo episodio…"
      // recomposition (a sibling overlay Box appearing/disappearing is enough to disturb Android's
      // focus owner) nothing ever reclaimed it. Reasserting here is a cheap no-op when already focused.
      update = { view ->
        view.player = exoPlayer
        if (!view.hasFocus()) view.requestFocus()
      },
      onRelease = { it.player = null },
    )

    if (showInfo) {
      PlayerInfoOverlay(
        item = item,
        positionMs = positionMs,
        durationMs = durationMs,
        videoInfo = videoInfo,
        modifier = Modifier.align(Alignment.TopStart),
      )
    }
    feedback?.let { PlaybackFeedback(text = it, modifier = Modifier.align(Alignment.Center)) }
    if (showHint && !showInfo) {
      Text(
        text = "SU: info · GIÙ: audio/sottotitoli · CENTRO: play/pausa · ←/→ ±10s",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
      )
    }
    if (showTracksMenu) {
      TracksMenu(
        exoPlayer = exoPlayer,
        onClose = {
          showTracksMenu = false
          playerViewRef?.requestFocus()
        },
      )
    }
  }
}

/**
 * Audio-language and subtitle picker over the running player (remote's DOWN key). Reads the
 * currently loaded HLS renditions (vixcloud streams carry ita/eng audio and subtitles) and applies
 * a track override; subtitles can also be switched off entirely (their default).
 */
@Composable
private fun TracksMenu(exoPlayer: ExoPlayer, onClose: () -> Unit, modifier: Modifier = Modifier) {
  val tracks = remember { exoPlayer.currentTracks }
  val audioGroups = remember(tracks) { tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported } }
  val textGroups = remember(tracks) { tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported } }
  val textDisabled = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
  val firstFocusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocusRequester.requestFocus() } }
  BackHandler { onClose() }

  Box(modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {
    Column(
      modifier =
        Modifier.align(Alignment.CenterEnd)
          .padding(24.dp)
          .width(380.dp)
          .background(Color(0xFF161921), RoundedCornerShape(14.dp))
          .padding(24.dp)
          // Subtitle lists can be long (vixcloud ships many languages) — scrollable so D-pad
          // focus can reach every entry instead of walking off the clipped bottom.
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(text = "Audio", style = MaterialTheme.typography.titleMedium, color = Color.White)
      if (audioGroups.isEmpty()) {
        Text(text = "Traccia unica", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
      }
      audioGroups.forEachIndexed { index, group ->
        Button(
          onClick = {
            applyTrackOverride(exoPlayer, group)
            onClose()
          },
          modifier = if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier,
        ) {
          Text((if (group.isSelected) "✓  " else "") + trackDisplayName(group))
        }
      }

      if (textGroups.isNotEmpty()) {
        Text(text = "Sottotitoli", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(top = 10.dp))
        Button(
          onClick = {
            exoPlayer.trackSelectionParameters =
              exoPlayer.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            flushAfterTrackChange(exoPlayer)
            onClose()
          }
        ) {
          Text((if (textDisabled || textGroups.none { it.isSelected }) "✓  " else "") + "Disattivati")
        }
        textGroups.forEach { group ->
          Button(
            onClick = {
              applyTrackOverride(exoPlayer, group)
              onClose()
            }
          ) {
            Text((if (group.isSelected) "✓  " else "") + trackDisplayName(group))
          }
        }
      }

      Text(
        text = "INDIETRO per chiudere",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}

private fun trackDisplayName(group: Tracks.Group): String {
  val format = group.getTrackFormat(0)
  val language =
    format.language?.let { code -> Locale(code).getDisplayLanguage(Locale.ITALIAN).replaceFirstChar { it.uppercase() } }
  return format.label ?: language?.takeIf { it.isNotBlank() } ?: "Traccia"
}

private fun applyTrackOverride(player: ExoPlayer, group: Tracks.Group) {
  player.trackSelectionParameters =
    player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(group.type, false)
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
      .build()
  flushAfterTrackChange(player)
}

/** A same-position seek right after a track change: on this box's fragile decoder a mid-stream
 *  track reselection stalls the video pipeline (frozen/corrupted frames, audio marching on, no
 *  error raised) — the seek flushes the renderers and restarts them cleanly. */
private fun flushAfterTrackChange(player: ExoPlayer) {
  player.seekTo(player.currentPosition)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewPlayer(item: StreamItem, originItem: StreamItem?, modifier: Modifier = Modifier) {
  var showInfo by remember(item.url) { mutableStateOf(false) }
  var infoRequestId by remember(item.url) { mutableStateOf(0) }
  var showHint by remember(item.url) { mutableStateOf(true) }
  var feedback by remember(item.url) { mutableStateOf<String?>(null) }
  // The embed URL findVideos produced has a ~20s token — by the time the WebView fallback kicks in
  // (after a native 403), that token is dead (the site returns "410 Gone"). So re-derive a fresh
  // embed here before loading it; only fall back to whatever url the item carried if re-resolution
  // isn't possible. null = still resolving.
  var resolvedUrl by remember(item.url) { mutableStateOf<String?>(null) }
  LaunchedEffect(item.url) {
    val fresh =
      originItem?.let { o -> withContext(Dispatchers.IO) { runCatching { ChannelRegistry.byId(o.channelId)?.resolveEmbedUrl(o) }.getOrNull() } }
    resolvedUrl = fresh ?: item.extra ?: item.url
  }

  LaunchedEffect(feedback) {
    if (feedback != null) {
      delay(1200)
      feedback = null
    }
  }

  LaunchedEffect(resolvedUrl) {
    if (resolvedUrl == null) return@LaunchedEffect
    delay(4000)
    showHint = false
  }

  // Same show-and-extend rationale as NativeHlsPlayer: immune to key-repeat flicker.
  LaunchedEffect(infoRequestId) {
    if (infoRequestId == 0) return@LaunchedEffect
    showInfo = true
    delay(6000)
    showInfo = false
  }

  val pageUrl = resolvedUrl
  if (pageUrl == null) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
      LoadingIndicator(modifier = Modifier.align(Alignment.Center), label = "Preparo il video…")
    }
    return
  }

  Box(modifier = modifier.fillMaxSize()) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { ctx ->
        WebView(ctx).apply {
          // Same standby guard as the native PlayerView — the WebView fallback plays for just as
          // long with no D-pad input and must keep the box awake too.
          keepScreenOn = true
          // WebView paints white by default until page content covers it — on a video page
          // that's often never (the player sizes to the <video> element, not the full screen),
          // so without this fix the "background" the user sees behind/around the player is a
          // plain white rectangle instead of black.
          setBackgroundColor(AndroidColor.BLACK)
          // Hardware video decode inside a WebView renders to its own SurfaceView layer, which
          // punches through the normal view hierarchy — nested one level down inside a Compose
          // AndroidView, that overlay can end up compositing behind everything instead of on top
          // (logs show the decoder and surface connecting cleanly, just nothing lands on screen).
          // Forcing the WebView itself onto a software layer makes Chromium composite the video
          // frame inline instead of via that separate hardware overlay.
          setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          settings.mediaPlaybackRequiresUserGesture = false
          settings.userAgentString = DESKTOP_UA

          fun centerTap() {
            val x = width / 2f
            val y = height / 2f
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP, x, y, 0)
            dispatchTouchEvent(down)
            dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
          }

          fun seekVideoBy(deltaSeconds: Int) {
            evaluateJavascript(
              "(function(){var v=document.querySelector('video');if(v){v.currentTime=Math.max(0,v.currentTime+($deltaSeconds));}})();",
              null,
            )
          }

          val playerHost = Uri.parse(pageUrl).host

          webViewClient =
            object : WebViewClient() {
              override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d(TAG, "onPageFinished($url): view=${view.width}x${view.height}, nudging playback")
                val handler = Handler(Looper.getMainLooper())
                listOf(400L, 1200L, 2500L).forEach { delay -> handler.postDelayed({ centerTap() }, delay) }
              }

              // Ad scripts on the embed page hijack the nudge tap and navigate the WHOLE WebView
              // to their own landing page (observed: a Google Ads policy page) instead of letting
              // the tap reach the real play button underneath. Block any navigation that leaves
              // the player's own domain — legitimate playback never needs to — so the ad's click
              // gets eaten instead of taking over the screen.
              override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val targetHost = request.url.host
                if (playerHost != null && targetHost != null && targetHost != playerHost) {
                  Log.d(TAG, "blocked off-domain navigation to ${request.url}")
                  return true
                }
                return false
              }
            }

          isFocusable = true
          isFocusableInTouchMode = true
          setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
              KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                centerTap()
                true
              }
              KeyEvent.KEYCODE_DPAD_UP -> {
                infoRequestId++
                true
              }
              KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekVideoBy(-10)
                feedback = "⏪ 10s"
                true
              }
              KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekVideoBy(10)
                feedback = "⏩ 10s"
                true
              }
              else -> false
            }
          }

          loadUrl(pageUrl, mapOf("Referer" to (item.referer ?: "https://streamingcommunityz.taxi/")))
          requestFocus()
        }
      },
      onRelease = { it.destroy() },
    )

    if (showInfo) {
      PlayerInfoOverlay(item = item, positionMs = null, durationMs = null, videoInfo = null, modifier = Modifier.align(Alignment.TopStart))
    }
    feedback?.let { PlaybackFeedback(text = it, modifier = Modifier.align(Alignment.Center)) }
    if (showInfo) {
      Text(
        text = "SU: chiudi info",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
      )
    } else if (showHint) {
      Text(
        text = "Se il video non parte, premi OK sul telecomando · SU: info",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
      )
    }
  }
}

/** Content metadata overlay (title, series/episode context, full detail() enrichment, playback
 *  position and native video stats) shown by the remote's UP key — the only way to see "what am
 *  I watching" on a screen with no visible chrome otherwise. [videoInfo] (resolution/codec/bitrate)
 *  and [positionMs]/[durationMs] are native-ExoPlayer-only; the WebView fallback passes null. */
@Composable
private fun PlayerInfoOverlay(item: StreamItem, positionMs: Long?, durationMs: Long?, videoInfo: String?, modifier: Modifier = Modifier) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.88f), Color.Transparent)))
        .padding(horizontal = 32.dp, vertical = 24.dp)
  ) {
    val seriesTitle = item.seriesTitle
    val contentTitle = item.contentTitle
    Text(text = seriesTitle ?: contentTitle ?: item.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
    if (seriesTitle != null && contentTitle != null) {
      Text(text = contentTitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 2.dp))
    }
    val infoLine = listOfNotNull(item.year, item.quality, item.runtime?.let { "$it min" }).joinToString("  ·  ")
    if (infoLine.isNotBlank()) {
      Text(text = infoLine, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(top = 6.dp))
    }
    item.genres?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 2.dp)) }
    item.plot?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.8f),
        modifier = Modifier.padding(top = 8.dp).widthIn(max = 900.dp),
      )
    }
    item.cast?.let {
      Text(
        text = "Cast: $it",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.65f),
        modifier = Modifier.padding(top = 8.dp).widthIn(max = 900.dp),
      )
    }
    item.director?.let { Text(text = "Regia: $it", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(top = 2.dp)) }
    val externalIds = listOfNotNull(item.tmdbId?.let { "TMDB: $it" }, item.imdbId?.let { "IMDB: $it" })
    if (externalIds.isNotEmpty()) {
      Text(
        text = externalIds.joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(top = 6.dp),
      )
    }
    if (positionMs != null && durationMs != null && durationMs > 0) {
      val fraction = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
      Box(
        modifier =
          Modifier.padding(top = 14.dp).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
      ) {
        Box(modifier = Modifier.fillMaxWidth(fraction).height(4.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
      }
      val timeLine = listOfNotNull("${formatTime(positionMs)} / ${formatTime(durationMs)}", videoInfo).joinToString("  ·  ")
      Text(text = timeLine, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 6.dp))
    } else if (videoInfo != null) {
      Text(text = videoInfo, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 12.dp))
    }
  }
}

@Composable
private fun PlaybackFeedback(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = MaterialTheme.typography.headlineSmall,
    color = Color.White,
    modifier = modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 12.dp),
  )
}

private fun formatTime(ms: Long): String {
  val totalSec = ms / 1000
  val h = totalSec / 3600
  val m = (totalSec % 3600) / 60
  val s = totalSec % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
