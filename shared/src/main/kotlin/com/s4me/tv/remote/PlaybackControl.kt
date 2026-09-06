package com.s4me.tv.remote

import kotlinx.serialization.Serializable

/** Live snapshot of what's playing on the TV right now, polled by the phone's "now playing" bar. */
@Serializable
data class PlaybackStatus(
  val title: String,
  val isPlaying: Boolean,
  val positionMs: Long,
  val durationMs: Long,
)

/** Transport commands the phone can send while something is playing on the TV. Native ExoPlayer
 *  playback only — the WebView fallback player doesn't wire into this. */
enum class PlaybackCommand { PLAY, PAUSE, TOGGLE, SEEK_BACK, SEEK_FORWARD, STOP }
