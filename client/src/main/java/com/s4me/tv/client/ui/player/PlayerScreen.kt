package com.s4me.tv.client.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.s4me.tv.client.R
import com.s4me.tv.client.download.LOCAL_SERVER_ID
import com.s4me.tv.client.player.PipController
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.DESKTOP_UA
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchProgressStore
import com.s4me.tv.engine.WatchStatsStore
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private const val SEEK_MS = 10_000L

// How far ahead of an episode's end to start resolving the next one, and how close to the end to
// auto-swap once it's resolved — see the "Netflix-style binge" block below.
private const val NEXT_UP_LOOKAHEAD_MS = 20_000L
private const val NEXT_UP_SWAP_MS = 300L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(item: StreamItem, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val activity = context as? Activity
  val view = LocalView.current

  DisposableEffect(Unit) {
    val prevOrientation = activity?.requestedOrientation
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    val controller = activity?.window?.let { WindowInsetsControllerCompat(it, view) }
    controller?.apply {
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      hide(WindowInsetsCompat.Type.systemBars())
    }
    onDispose {
      activity?.requestedOrientation = prevOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      controller?.show(WindowInsetsCompat.Type.systemBars())
    }
  }

  // Netflix-style binge: when an episode nears its end, the next one is resolved ahead of time and
  // swapped in automatically — no navigation round-trip, the whole player rebuilds keyed on
  // activeItem.url (mirrors :app's player). Movies (episode == null) never swap.
  var activeItem by remember { mutableStateOf(item) }

  val originId = remember(activeItem.url) { activeItem.originId?.takeIf { it.isNotBlank() } }
  var forcedApplied by remember(activeItem.url) { mutableStateOf(false) }
  var tracks by remember(activeItem.url) { mutableStateOf(Tracks.EMPTY) }

  // A downloaded title plays from its local .mkv (all audio + subtitle tracks are in the file, so
  // the Audio/CC sheet works exactly as when streaming) — never the network.
  val isLocal = activeItem.serverId == LOCAL_SERVER_ID

  val exoPlayer =
    remember(activeItem.url) {
      val builder = ExoPlayer.Builder(context)
      // Streams need the OkHttp + Referer HLS source; a local file plays through ExoPlayer's
      // default media source (content:// and file:// data sources, Matroska extractor) as-is.
      if (!isLocal) {
        val okHttp = OkHttpClient.Builder().build()
        val dsFactory =
          OkHttpDataSource.Factory(okHttp).setUserAgent(DESKTOP_UA).apply {
            activeItem.referer?.let { setDefaultRequestProperties(mapOf("Referer" to it)) }
          }
        builder.setMediaSourceFactory(HlsMediaSource.Factory(dsFactory))
      }
      val mediaItem =
        if (isLocal) MediaItem.Builder().setUri(activeItem.url).setMimeType("video/x-matroska").build()
        else MediaItem.fromUri(activeItem.url)
      builder
        .build()
        .apply {
          setMediaItem(mediaItem)
          // Italian audio + Italian subtitles as the language preference; the forced-subtitle
          // auto-pick below then narrows the subtitle choice to a forced track when one exists.
          trackSelectionParameters =
            trackSelectionParameters
              .buildUpon()
              .setPreferredAudioLanguage("it")
              .setPreferredTextLanguage("it")
              .build()
          playWhenReady = true
          prepare()
          originId?.let { id -> WatchProgressStore(context).positionFor(id)?.let { seekTo(it) } }
        }
    }

  val inPip by PipController.isInPip.collectAsStateWithLifecycle()
  var isPlaying by remember { mutableStateOf(true) }
  var isBuffering by remember { mutableStateOf(false) }
  var position by remember { mutableLongStateOf(0L) }
  var duration by remember { mutableLongStateOf(0L) }
  var buffered by remember { mutableLongStateOf(0L) }
  var controlsVisible by remember { mutableStateOf(true) }
  var showTracks by remember { mutableStateOf(false) }
  var showInfo by remember { mutableStateOf(false) }
  var locked by remember { mutableStateOf(false) }
  var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
  // FIT letterboxes to the real aspect; ZOOM crops to fill the screen ("riempi").
  var fillScreen by remember { mutableStateOf(false) }
  var speed by remember { mutableFloatStateOf(1f) }
  var stats by remember { mutableStateOf(PlaybackStats()) }
  var playerView by remember { mutableStateOf<PlayerView?>(null) }
  var errorRetries by remember(activeItem.url) { mutableIntStateOf(0) }
  var seekFlash by remember { mutableStateOf<SeekFlash?>(null) }

  var nextEpisode by remember(activeItem.url) { mutableStateOf<StreamItem?>(null) }
  var resolvingNext by remember(activeItem.url) { mutableStateOf(false) }
  var nextUpDismissed by remember(activeItem.url) { mutableStateOf(false) }

  // Only offer PiP while there's actually something playing — a paused video, the info panel or a
  // track sheet open aren't worth a floating window over. Cleared on the way out below so leaving
  // the player (however it happens — back, an error, the screen finishing) can't leave a stale
  // "available" flag that pops PiP the next time some OTHER screen goes to the background.
  LaunchedEffect(isPlaying) { PipController.setAvailable(isPlaying) }

  DisposableEffect(exoPlayer) {
    val listener =
      object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
          isPlaying = playing
        }

        override fun onPlaybackStateChanged(state: Int) {
          isBuffering = state == Player.STATE_BUFFERING
        }

        override fun onTracksChanged(t: Tracks) {
          tracks = t
          if (!forcedApplied) {
            selectForcedSubtitle(exoPlayer, t)?.let { forcedApplied = true }
          }
        }

        // A decoder reclaimed while the app was backgrounded (or a transient renderer fault) lands
        // here — re-prepare in place from the current position instead of dead-ending on a black
        // screen the play button can't revive. Capped so genuinely broken media doesn't loop.
        override fun onPlayerError(error: PlaybackException) {
          if (errorRetries >= 3) return
          errorRetries++
          val resumeAt = exoPlayer.currentPosition.coerceAtLeast(0)
          exoPlayer.prepare()
          if (resumeAt > 0) exoPlayer.seekTo(resumeAt)
          exoPlayer.playWhenReady = true
        }
      }
    exoPlayer.addListener(listener)
    onDispose {
      PipController.setAvailable(false)
      if (originId != null) {
        val pos = exoPlayer.currentPosition.coerceAtLeast(0)
        val dur = exoPlayer.duration
        // Save the navigable MOVIE/EPISODE (url = originId), NOT the resolved PLAYABLE — a
        // "Continua a guardare" tile must open Detail and re-resolve a fresh stream, not hand
        // ExoPlayer the stale content-id URL.
        if (dur > 0) {
          val origin = playbackOrigin(activeItem, originId)
          WatchProgressStore(context).save(origin, pos, dur)
          WatchStatsStore(context).recordIfCompleted(origin, pos, dur)
        }
      }
      exoPlayer.removeListener(listener)
      exoPlayer.release()
    }
  }

  // Standby / app-switch recovery. Screen-off tears down the TextureView surface, and Android can
  // reclaim the video decoder while we're backgrounded — the player then returns in STATE_IDLE (or
  // holding a PlaybackException) and the overlay's play button, which only flips playWhenReady,
  // can't restart it ("se il tablet va in standby e premo play non parte"). On the way back:
  // re-prepare from the last position if the pipeline died, and re-bind the recreated surface.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, exoPlayer) {
    var resumePlaying = true
    var wentAway = false
    val observer =
      LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_STOP -> {
            resumePlaying = exoPlayer.playWhenReady
            wentAway = true
            exoPlayer.pause()
            // Persist here too — onDispose won't run if the app is killed while backgrounded.
            if (originId != null) {
              val dur = exoPlayer.duration
              if (dur > 0) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0)
                val origin = playbackOrigin(activeItem, originId)
                WatchProgressStore(context).save(origin, pos, dur)
                WatchStatsStore(context).recordIfCompleted(origin, pos, dur)
              }
            }
          }
          Lifecycle.Event.ON_START -> {
            if (wentAway) {
              wentAway = false
              if (exoPlayer.playerError != null || exoPlayer.playbackState == Player.STATE_IDLE) {
                val resumeAt = exoPlayer.currentPosition.coerceAtLeast(0)
                exoPlayer.prepare()
                if (resumeAt > 0) exoPlayer.seekTo(resumeAt)
              }
              // The recreated TextureView surface needs re-attaching to the player.
              playerView?.let {
                it.player = null
                it.player = exoPlayer
              }
              exoPlayer.playWhenReady = resumePlaying
            }
          }
          else -> Unit
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(exoPlayer, activeItem.url) {
    while (true) {
      position = exoPlayer.currentPosition.coerceAtLeast(0)
      duration = exoPlayer.duration.coerceAtLeast(0)
      buffered = exoPlayer.bufferedPosition.coerceAtLeast(0)
      if (showInfo) stats = readStats(exoPlayer)

      // Netflix-style binge: resolve the next episode ~20s before this one ends, then swap it in
      // right at the end. Movies (episode == null) just end; a dismissed prompt never re-triggers
      // for this episode.
      // Not for a downloaded episode: the next one would silently stream instead of playing offline.
      if (activeItem.episode != null && !isLocal && !nextUpDismissed && duration > 0) {
        val remaining = duration - position
        if (nextEpisode == null && !resolvingNext && remaining in 0..NEXT_UP_LOOKAHEAD_MS) {
          resolvingNext = true
          val current = activeItem
          launch(Dispatchers.IO) {
            nextEpisode = runCatching { resolveNextEpisode(current) }.getOrNull()
            resolvingNext = false
          }
        }
        if (nextEpisode != null && remaining <= NEXT_UP_SWAP_MS) {
          activeItem = nextEpisode!!
        }
      }

      if (controlsVisible && !showTracks && !showInfo && isPlaying && System.currentTimeMillis() - lastInteraction > 3400) {
        controlsVisible = false
      }
      delay(500)
    }
  }

  LaunchedEffect(seekFlash) {
    if (seekFlash != null) {
      delay(650)
      seekFlash = null
    }
  }

  LaunchedEffect(speed) { exoPlayer.setPlaybackSpeed(speed) }

  // Nothing in the overlay is tappable in a PiP window anyway — Android owns touch there, only
  // recognising its own expand/dismiss gestures — so there's no point leaving it up to draw over
  // the tiny video for no reason.
  LaunchedEffect(inPip) {
    if (inPip) {
      controlsVisible = false
      showTracks = false
      showInfo = false
    }
  }

  fun touched() {
    lastInteraction = System.currentTimeMillis()
    controlsVisible = true
  }

  fun seekBy(deltaMs: Long) {
    if (locked) return
    exoPlayer.seekTo((exoPlayer.currentPosition + deltaMs).coerceAtLeast(0))
    seekFlash = SeekFlash(forward = deltaMs > 0, nonce = System.nanoTime())
    touched()
  }

  Box(
    modifier =
      modifier.fillMaxSize().background(Color.Black).pointerInput(locked) {
        detectTapGestures(
          onTap = {
            controlsVisible = !controlsVisible
            if (controlsVisible) touched()
          },
          onDoubleTap = { offset -> seekBy(if (offset.x < size.width / 2) -SEEK_MS else SEEK_MS) },
        )
      }
  ) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { ctx ->
        (android.view.LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView)
          .apply {
            player = exoPlayer
            keepScreenOn = true
          }
          .also { playerView = it }
      },
      update = {
        it.player = exoPlayer
        it.resizeMode =
          if (fillScreen) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
      },
    )

    if (isBuffering && !locked) {
      CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(44.dp))
    }

    seekFlash?.let { flash ->
      SeekFlashBubble(
        forward = flash.forward,
        modifier =
          Modifier.align(if (flash.forward) Alignment.CenterEnd else Alignment.CenterStart)
            .padding(horizontal = 72.dp),
      )
    }

    // Locked: only a small unlock affordance, no other controls.
    if (locked) {
      if (controlsVisible) {
        LockAffordance(locked = true, modifier = Modifier.align(Alignment.Center)) { locked = false; touched() }
      }
      return@Box
    }

    AnimatedVisibility(visible = controlsVisible) {
      Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
        // Top: back + compact content info (full detail lives in the Info panel now).
        Row(
          modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RoundIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", onClick = onBack)
          Spacer(Modifier.width(14.dp))
          InfoBlock(item = activeItem, modifier = Modifier.weight(1f))
        }

        // Center transport.
        Row(
          modifier = Modifier.align(Alignment.Center),
          horizontalArrangement = Arrangement.spacedBy(44.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RoundIconButton(icon = Icons.Filled.Replay10, contentDescription = "Indietro 10 secondi", size = 56.dp, iconSize = 30.dp, background = Color.Transparent) {
            seekBy(-SEEK_MS)
          }
          RoundIconButton(
            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pausa" else "Riproduci",
            size = 80.dp,
            iconSize = 38.dp,
            background = Color.White.copy(alpha = 0.16f),
          ) {
            exoPlayer.playWhenReady = !exoPlayer.playWhenReady
            touched()
          }
          RoundIconButton(icon = Icons.Filled.Forward10, contentDescription = "Avanti 10 secondi", size = 56.dp, iconSize = 30.dp, background = Color.Transparent) {
            seekBy(SEEK_MS)
          }
        }

        // Bottom: icon control row above a buffered-aware scrubber.
        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
          ) {
            ControlButton(icon = Icons.Filled.Subtitles, label = "Audio e CC") {
              tracks = exoPlayer.currentTracks
              showTracks = true
              touched()
            }
            ControlButton(icon = Icons.Filled.Speed, label = if (speed == 1f) "1×" else "${speed.toString().removeSuffix(".0")}×") {
              speed = nextSpeed(speed)
              touched()
            }
            ControlButton(icon = Icons.Filled.AspectRatio, label = if (fillScreen) "Riempi" else "Adatta") { fillScreen = !fillScreen; touched() }
            ControlButton(icon = Icons.Filled.Info, label = "Info", active = showInfo) { showInfo = !showInfo; touched() }
            ControlButton(icon = Icons.Filled.Lock, label = "Blocca") { locked = true; controlsVisible = false }
          }

          Box(modifier = Modifier.fillMaxWidth()) {
            // Buffered fill, drawn behind the Slider — the Slider's own inactive track is made
            // transparent below so this shows through for the un-buffered remainder.
            Box(
              modifier =
                Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f))
            ) {
              Box(
                modifier =
                  Modifier.fillMaxHeight()
                    .fillMaxWidth(if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f)
                    .background(Color.White.copy(alpha = 0.4f))
              )
            }
            Slider(
              value = if (duration > 0) position.toFloat() / duration else 0f,
              onValueChange = { f ->
                if (duration > 0) exoPlayer.seekTo((f * duration).toLong())
                touched()
              },
              colors =
                SliderDefaults.colors(
                  thumbColor = MaterialTheme.colorScheme.primary,
                  activeTrackColor = MaterialTheme.colorScheme.primary,
                  inactiveTrackColor = Color.Transparent,
                ),
            )
          }
          Row(modifier = Modifier.fillMaxWidth()) {
            Text(fmt(position), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(fmt(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
          }
        }
      }
    }

    AnimatedVisibility(
      visible = showInfo,
      enter = slideInHorizontally(animationSpec = tween(220)) { it } + fadeIn(tween(220)),
      exit = slideOutHorizontally(animationSpec = tween(180)) { it } + fadeOut(tween(180)),
      modifier = Modifier.align(Alignment.CenterEnd),
    ) {
      InfoPanel(
        item = activeItem,
        stats = stats,
        bufferedAheadMs = if (duration > 0) (buffered - position).coerceAtLeast(0) else 0,
        onClose = { showInfo = false },
      )
    }

    if (!showInfo && !inPip && nextEpisode != null && !nextUpDismissed && duration > 0 && (duration - position) <= NEXT_UP_LOOKAHEAD_MS) {
      NextUpCard(
        next = nextEpisode!!,
        secondsLeft = ((duration - position) / 1000).toInt().coerceAtLeast(0),
        onPlayNow = { activeItem = nextEpisode!! },
        onDismiss = { nextUpDismissed = true },
        modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
      )
    }

    if (showTracks) {
      val sheetState = rememberModalBottomSheetState()
      ModalBottomSheet(onDismissRequest = { showTracks = false }, sheetState = sheetState) {
        TrackSheet(
          exoPlayer = exoPlayer,
          tracks = tracks,
          onDone = { showTracks = false },
          modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
        )
      }
    }
  }
}

private data class SeekFlash(val forward: Boolean, val nonce: Long)

@Composable
private fun SeekFlashBubble(forward: Boolean, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.size(112.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = if (forward) Icons.Filled.Forward10 else Icons.Filled.Replay10,
      contentDescription = null,
      tint = Color.White,
      modifier = Modifier.size(32.dp),
    )
    Text("10 secondi", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
  }
}

@Composable
private fun RoundIconButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  size: androidx.compose.ui.unit.Dp = 40.dp,
  iconSize: androidx.compose.ui.unit.Dp = 22.dp,
  background: Color = Color.White.copy(alpha = 0.14f),
  onClick: () -> Unit,
) {
  Box(
    modifier = modifier.size(size).clip(CircleShape).background(background).noRippleClick(onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(iconSize))
  }
}

@Composable
private fun ControlButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  active: Boolean = false,
  onClick: () -> Unit,
) {
  val tint = if (active) MaterialTheme.colorScheme.primary else Color.White
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.noRippleClick(onClick)) {
    Box(
      modifier =
        Modifier.size(46.dp)
          .clip(CircleShape)
          .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.12f)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
  }
}

@Composable
private fun LockAffordance(locked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Row(
    modifier =
      modifier
        .clip(RoundedCornerShape(50))
        .background(Color.White.copy(alpha = 0.18f))
        .noRippleClick(onClick)
        .padding(horizontal = 20.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(imageVector = if (locked) Icons.Filled.LockOpen else Icons.Filled.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    Text(if (locked) "Sblocca" else "Blocca", style = MaterialTheme.typography.labelLarge, color = Color.White)
  }
}

@Composable
private fun InfoBlock(item: StreamItem, modifier: Modifier = Modifier) {
  Column(modifier = modifier) {
    Text(
      text = item.seriesTitle ?: item.contentTitle ?: item.title,
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
      color = Color.White,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    val sub =
      if (item.episode != null) {
        listOfNotNull(
          item.season?.let { "Stagione $it" },
          item.episode?.let { "Episodio $it" },
          item.contentTitle?.takeIf { it != item.seriesTitle },
        ).joinToString(" · ")
      } else {
        listOfNotNull(item.year, item.quality).joinToString(" · ")
      }
    if (sub.isNotBlank()) {
      Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
    }
  }
}

/** Content info and streaming stats together in one panel — previously two separate, independently
 *  toggled overlays (an always-on plot block up top, a technical StatsPanel elsewhere). */
@Composable
private fun InfoPanel(item: StreamItem, stats: PlaybackStats, bufferedAheadMs: Long, onClose: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier =
      modifier
        .fillMaxHeight()
        .width(360.dp)
        .background(Color(0xFF12141A).copy(alpha = 0.97f))
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .verticalScroll(rememberScrollState())
        .padding(22.dp)
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(
        "INFORMAZIONI",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.5f),
      )
      RoundIconButton(icon = Icons.Filled.Close, contentDescription = "Chiudi", size = 32.dp, iconSize = 16.dp, onClick = onClose)
    }

    Spacer(Modifier.height(16.dp))

    Text(
      text = item.seriesTitle ?: item.contentTitle ?: item.title,
      style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
      color = Color.White,
    )
    val sub =
      if (item.episode != null) {
        listOfNotNull(item.season?.let { "Stagione $it" }, item.episode?.let { "Episodio $it" }, item.contentTitle?.takeIf { it != item.seriesTitle })
          .joinToString(" · ")
      } else {
        listOfNotNull(item.year, item.quality).joinToString(" · ")
      }
    if (sub.isNotBlank()) {
      Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(top = 4.dp))
    }
    item.plot?.takeIf { it.isNotBlank() }?.let {
      Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 12.dp))
    }
    item.cast?.takeIf { it.isNotBlank() }?.let { cast ->
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        cast.split(", ").forEach { name ->
          Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier =
              Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 11.dp, vertical = 5.dp),
          )
        }
      }
    }

    Spacer(Modifier.height(20.dp))
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
    Spacer(Modifier.height(16.dp))

    Text("STREAMING", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 4.dp))
    StatRow("Risoluzione", if (stats.width > 0) "${stats.width}×${stats.height}" else "—")
    StatRow("Bitrate video", stats.videoBitrate?.let { fmtBitrate(it) } ?: "—")
    StatRow("Codec video", listOfNotNull(stats.videoCodec, stats.frameRate?.let { "${it.toInt()} fps" }).joinToString(" · ").ifBlank { "—" })
    StatRow("Audio", listOfNotNull(stats.audioCodec, stats.audioChannels?.let { "${it}ch" }, stats.audioSampleRate?.let { "${it / 1000} kHz" }).joinToString(" · ").ifBlank { "—" })
    StatRow("Buffer", "%.0f s avanti".format(bufferedAheadMs / 1000f))
  }
}

@Composable
private fun StatRow(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f))
    Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = Color.White)
  }
}

@Composable
private fun NextUpCard(next: StreamItem, secondsLeft: Int, onPlayNow: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  Column(modifier = modifier.width(320.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF12141A))) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant).noRippleClick(onPlayNow)) {
      AsyncImage(
        model = next.thumbnail ?: next.backdrop,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
      Box(
        modifier = Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = "Riproduci ora", tint = Color.White, modifier = Modifier.size(26.dp))
      }
      Text(
        "$secondsLeft",
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier =
          Modifier.align(Alignment.TopEnd)
            .padding(10.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
      )
    }
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        "PROSSIMO EPISODIO TRA ${secondsLeft}S",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.5f),
      )
      Text(
        text = listOfNotNull(next.season?.let { "S$it" }, next.episode?.let { "E$it" }).joinToString(" · ") +
          (next.contentTitle?.let { " — $it" } ?: ""),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onPlayNow, modifier = Modifier.weight(1f)) { Text("Riproduci ora") }
        OutlinedButton(onClick = onDismiss) { Text("Annulla") }
      }
    }
  }
}

@Composable
private fun TrackSheet(exoPlayer: ExoPlayer, tracks: Tracks, onDone: () -> Unit, modifier: Modifier = Modifier) {
  val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
  val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
  val textOff = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || text.none { it.isSelected }

  Column(modifier = modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("Audio", style = MaterialTheme.typography.titleMedium)
    if (audio.isEmpty()) Text("Traccia unica", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    audio.forEach { g ->
      TrackLine(label = trackDisplayName(g), selected = g.isSelected) {
        applyOverride(exoPlayer, g)
        onDone()
      }
    }

    Text("Sottotitoli", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    if (text.isEmpty()) {
      Text(
        "Nessun sottotitolo per questa fonte. Se stai appena avviando, riprova tra qualche secondo.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      TrackLine(label = "Disattivati", selected = textOff) {
        exoPlayer.trackSelectionParameters =
          exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        exoPlayer.seekTo(exoPlayer.currentPosition)
        onDone()
      }
      text.forEach { g ->
        val forced = (g.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_FORCED) != 0
        TrackLine(label = trackDisplayName(g) + if (forced) "  (forzati)" else "", selected = g.isSelected && !textOff) {
          applyOverride(exoPlayer, g)
          onDone()
        }
      }
    }
  }
}

data class PlaybackStats(
  val width: Int = 0,
  val height: Int = 0,
  val videoBitrate: Int? = null,
  val frameRate: Float? = null,
  val videoCodec: String? = null,
  val audioCodec: String? = null,
  val audioChannels: Int? = null,
  val audioSampleRate: Int? = null,
)

private fun readStats(player: ExoPlayer): PlaybackStats {
  fun selectedFormat(type: Int) =
    player.currentTracks.groups
      .firstOrNull { it.type == type && it.isSelected }
      ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
  val v = selectedFormat(C.TRACK_TYPE_VIDEO)
  val a = selectedFormat(C.TRACK_TYPE_AUDIO)
  val size = player.videoSize
  return PlaybackStats(
    width = size.width.takeIf { it > 0 } ?: v?.width?.takeIf { it > 0 } ?: 0,
    height = size.height.takeIf { it > 0 } ?: v?.height?.takeIf { it > 0 } ?: 0,
    videoBitrate = v?.let { (if (it.bitrate != Format.NO_VALUE) it.bitrate else it.averageBitrate).takeIf { b -> b > 0 } },
    frameRate = v?.frameRate?.takeIf { it > 0f },
    videoCodec = v?.let(::codecName),
    audioCodec = a?.let(::codecName),
    audioChannels = a?.channelCount?.takeIf { it > 0 },
    audioSampleRate = a?.sampleRate?.takeIf { it > 0 },
  )
}

private fun codecName(f: Format): String =
  when (f.sampleMimeType) {
    "video/avc" -> "H.264"
    "video/hevc" -> "H.265"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/av01" -> "AV1"
    "audio/mp4a-latm" -> "AAC"
    "audio/ac3" -> "AC-3"
    "audio/eac3" -> "E-AC-3"
    "audio/opus" -> "Opus"
    "audio/vorbis" -> "Vorbis"
    else -> f.sampleMimeType?.substringAfter('/')?.uppercase() ?: "—"
  }

private fun fmtBitrate(bps: Int): String =
  if (bps >= 1_000_000) "%.1f Mbps".format(bps / 1_000_000f) else "${bps / 1000} kbps"

private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun nextSpeed(cur: Float): Float {
  val i = SPEEDS.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }
  return if (i < 0) 1f else SPEEDS[(i + 1) % SPEEDS.size]
}

@Composable
private fun TrackLine(label: String, selected: Boolean, onClick: () -> Unit) {
  TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
    Text((if (selected) "✓  " else "     ") + label, modifier = Modifier.fillMaxWidth())
  }
}

/** Forced text track, preferring one whose language matches the selected audio. Returns the group
 *  it applied, or null if the title has no forced subtitles. */
private fun selectForcedSubtitle(player: ExoPlayer, tracks: Tracks): Tracks.Group? {
  val audioLang =
    tracks.groups
      .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
      ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
  val forcedGroups =
    tracks.groups.filter { g ->
      g.type == C.TRACK_TYPE_TEXT && g.isSupported && (g.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_FORCED) != 0
    }
  val pick = forcedGroups.firstOrNull { it.getTrackFormat(0).language == audioLang } ?: forcedGroups.firstOrNull() ?: return null
  player.trackSelectionParameters =
    player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
      .setOverrideForType(TrackSelectionOverride(pick.mediaTrackGroup, 0))
      .build()
  player.seekTo(player.currentPosition)
  return pick
}

private fun applyOverride(player: ExoPlayer, group: Tracks.Group) {
  player.trackSelectionParameters =
    player.trackSelectionParameters
      .buildUpon()
      .setTrackTypeDisabled(group.type, false)
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
      .build()
  player.seekTo(player.currentPosition)
}

private fun trackDisplayName(group: Tracks.Group): String {
  val format = group.getTrackFormat(0)
  val language =
    format.language?.let { code -> Locale(code).getDisplayLanguage(Locale.ITALIAN).replaceFirstChar { it.uppercase() } }
  return format.label ?: language?.takeIf { it.isNotBlank() } ?: "Traccia"
}

private fun Modifier.noRippleClick(onClick: () -> Unit): Modifier =
  this.then(Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) })

/** Rebuild the navigable MOVIE/EPISODE from a PLAYABLE's carried fields, mirroring the TV app. */
private fun playbackOrigin(playable: StreamItem, originId: String): StreamItem =
  StreamItem(
    title = playable.contentTitle ?: playable.title,
    url = originId,
    kind = if (playable.episode != null) ItemKind.EPISODE else ItemKind.MOVIE,
    channelId = playable.channelId,
    thumbnail = playable.thumbnail,
    backdrop = playable.backdrop,
    plot = playable.plot,
    year = playable.year,
    quality = playable.quality,
    seriesTitle = playable.seriesTitle,
    season = playable.season,
    episode = playable.episode,
  )

/**
 * Finds the episode after [playable] in its season and resolves it to a fresh PLAYABLE. Same-season
 * only; the last episode of a season just ends. Null when anything is missing. Mirrors :app's player.
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

private fun fmt(ms: Long): String {
  if (ms <= 0) return "0:00"
  val s = ms / 1000
  val h = s / 3600
  val m = (s % 3600) / 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%d:%02d".format(m, s % 60)
}
