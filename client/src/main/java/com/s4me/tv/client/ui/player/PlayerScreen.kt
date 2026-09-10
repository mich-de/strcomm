package com.s4me.tv.client.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
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
import com.s4me.tv.client.R
import com.s4me.tv.engine.DESKTOP_UA
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchProgressStore
import java.util.Locale
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

private const val SEEK_MS = 10_000L

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

  val originId = remember(item.url) { item.originId?.takeIf { it.isNotBlank() } }
  var forcedApplied by remember(item.url) { mutableStateOf(false) }
  var tracks by remember(item.url) { mutableStateOf(Tracks.EMPTY) }

  val exoPlayer =
    remember(item.url) {
      val okHttp = OkHttpClient.Builder().build()
      val dsFactory =
        OkHttpDataSource.Factory(okHttp).setUserAgent(DESKTOP_UA).apply {
          item.referer?.let { setDefaultRequestProperties(mapOf("Referer" to it)) }
        }
      ExoPlayer.Builder(context)
        .setMediaSourceFactory(HlsMediaSource.Factory(dsFactory))
        .build()
        .apply {
          setMediaItem(MediaItem.fromUri(item.url))
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

  var isPlaying by remember { mutableStateOf(true) }
  var position by remember { mutableLongStateOf(0L) }
  var duration by remember { mutableLongStateOf(0L) }
  var buffered by remember { mutableLongStateOf(0L) }
  var controlsVisible by remember { mutableStateOf(true) }
  var showTracks by remember { mutableStateOf(false) }
  var showStats by remember { mutableStateOf(false) }
  var locked by remember { mutableStateOf(false) }
  var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
  // FIT letterboxes to the real aspect; ZOOM crops to fill the screen ("riempi").
  var fillScreen by remember { mutableStateOf(false) }
  var speed by remember { mutableFloatStateOf(1f) }
  var stats by remember { mutableStateOf(PlaybackStats()) }
  var playerView by remember { mutableStateOf<PlayerView?>(null) }
  var errorRetries by remember(item.url) { mutableIntStateOf(0) }

  DisposableEffect(exoPlayer) {
    val listener =
      object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
          isPlaying = playing
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
      if (originId != null) {
        val pos = exoPlayer.currentPosition.coerceAtLeast(0)
        val dur = exoPlayer.duration
        // Save the navigable MOVIE/EPISODE (url = originId), NOT the resolved PLAYABLE — a
        // "Continua a guardare" tile must open Detail and re-resolve a fresh stream, not hand
        // ExoPlayer the stale content-id URL.
        if (dur > 0) WatchProgressStore(context).save(playbackOrigin(item, originId), pos, dur)
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
                WatchProgressStore(context)
                  .save(playbackOrigin(item, originId), exoPlayer.currentPosition.coerceAtLeast(0), dur)
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

  LaunchedEffect(exoPlayer) {
    while (true) {
      position = exoPlayer.currentPosition.coerceAtLeast(0)
      duration = exoPlayer.duration.coerceAtLeast(0)
      buffered = exoPlayer.bufferedPosition.coerceAtLeast(0)
      if (showStats) stats = readStats(exoPlayer)
      if (controlsVisible && !showTracks && !showStats && isPlaying && System.currentTimeMillis() - lastInteraction > 3400) {
        controlsVisible = false
      }
      delay(500)
    }
  }

  LaunchedEffect(speed) { exoPlayer.setPlaybackSpeed(speed) }

  fun touched() {
    lastInteraction = System.currentTimeMillis()
    controlsVisible = true
  }

  Box(
    modifier =
      modifier.fillMaxSize().background(Color.Black).pointerInput(locked) {
        detectTapGestures(
          onTap = {
            controlsVisible = !controlsVisible
            if (controlsVisible) touched()
          },
          onDoubleTap = { offset ->
            if (locked) return@detectTapGestures
            if (offset.x < size.width / 2) exoPlayer.seekTo((exoPlayer.currentPosition - SEEK_MS).coerceAtLeast(0))
            else exoPlayer.seekTo(exoPlayer.currentPosition + SEEK_MS)
            touched()
          },
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

    // Locked: only a small unlock affordance, no other controls.
    if (locked) {
      if (controlsVisible) {
        OverlayButton("🔒 Sblocca", modifier = Modifier.align(Alignment.Center)) { locked = false; touched() }
      }
      return@Box
    }

    if (showStats) {
      StatsPanel(
        stats = stats,
        buffered = if (duration > 0) (buffered - position).coerceAtLeast(0) else 0,
        modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
      )
    }

    AnimatedVisibility(visible = controlsVisible) {
      Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
        // Top: back + the content info the user asked to see on tap.
        Row(
          modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
          verticalAlignment = Alignment.Top,
        ) {
          Text("‹", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(end = 16.dp).noRippleClick(onBack))
          InfoBlock(item = item, modifier = Modifier.weight(1f))
        }

        // Center transport.
        Row(
          modifier = Modifier.align(Alignment.Center),
          horizontalArrangement = Arrangement.spacedBy(40.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("⏪", style = MaterialTheme.typography.displaySmall, color = Color.White, modifier = Modifier.noRippleClick {
            exoPlayer.seekTo((exoPlayer.currentPosition - SEEK_MS).coerceAtLeast(0)); touched()
          })
          Text(
            if (isPlaying) "⏸" else "▶",
            style = MaterialTheme.typography.displayLarge,
            color = Color.White,
            modifier = Modifier.noRippleClick { exoPlayer.playWhenReady = !exoPlayer.playWhenReady; touched() },
          )
          Text("⏩", style = MaterialTheme.typography.displaySmall, color = Color.White, modifier = Modifier.noRippleClick {
            exoPlayer.seekTo(exoPlayer.currentPosition + SEEK_MS); touched()
          })
        }

        // Bottom: a control row above the scrubber.
        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OverlayButton("Sottotitoli / Audio") {
              tracks = exoPlayer.currentTracks
              showTracks = true
              touched()
            }
            OverlayButton(if (speed == 1f) "Velocità 1x" else "Velocità ${speed.toString().removeSuffix(".0")}x") {
              speed = nextSpeed(speed)
              touched()
            }
            OverlayButton(if (fillScreen) "Riempi" else "Adatta") { fillScreen = !fillScreen; touched() }
            OverlayButton(if (showStats) "Info ✓" else "Info") { showStats = !showStats; touched() }
            OverlayButton("🔒 Blocca") { locked = true; controlsVisible = false }
          }
          Slider(
            value = if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { f ->
              if (duration > 0) exoPlayer.seekTo((f * duration).toLong())
              touched()
            },
          )
          Row(modifier = Modifier.fillMaxWidth()) {
            Text(fmt(position), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(fmt(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
          }
        }
      }
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
    item.plot?.takeIf { it.isNotBlank() }?.let {
      Text(
        it,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.7f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp).widthIn(max = 520.dp),
      )
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

@Composable
private fun OverlayButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Text(
    text = label,
    style = MaterialTheme.typography.labelLarge,
    color = Color.White,
    maxLines = 1,
    modifier =
      modifier
        .clip(RoundedCornerShape(50))
        .background(Color.White.copy(alpha = 0.18f))
        .noRippleClick(onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp),
  )
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
private fun StatsPanel(stats: PlaybackStats, buffered: Long, modifier: Modifier = Modifier) {
  val video =
    buildList {
        if (stats.width > 0) add("${stats.width}×${stats.height}")
        stats.videoBitrate?.let { add(fmtBitrate(it)) }
        stats.videoCodec?.let { add(it) }
        stats.frameRate?.let { add("${it.toInt()} fps") }
      }
      .joinToString("  ·  ")
  val audio =
    buildList {
        stats.audioCodec?.let { add(it) }
        stats.audioChannels?.let { add("${it}ch") }
        stats.audioSampleRate?.let { add("${it / 1000} kHz") }
      }
      .joinToString("  ·  ")
  Column(
    modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text("Video:  ${video.ifBlank { "…" }}", color = Color.White, style = MaterialTheme.typography.labelSmall)
    if (audio.isNotBlank()) Text("Audio:  $audio", color = Color.White, style = MaterialTheme.typography.labelSmall)
    Text("Buffer:  %.0f s".format(buffered / 1000f), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
  }
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

private fun fmt(ms: Long): String {
  if (ms <= 0) return "0:00"
  val s = ms / 1000
  val h = s / 3600
  val m = (s % 3600) / 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%d:%02d".format(m, s % 60)
}
