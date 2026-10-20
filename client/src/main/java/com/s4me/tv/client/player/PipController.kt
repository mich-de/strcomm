package com.s4me.tv.client.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges PlayerScreen (deep in the nav graph) and MainActivity (the only place that can actually
 * call enterPictureInPictureMode) — same shape as the TV app's PlaybackControlBridge, needed for
 * the same reason: no direct composition link between the two.
 *
 * [available] is true only while a real video is on screen and playing — set from PlayerScreen's
 * own isPlaying, not just "the player is open" — leaving the app on a paused video, the info
 * panel, or a track-selection sheet shouldn't pop a tiny floating window with nothing useful in it.
 * [isInPip] mirrors MainActivity's onPictureInPictureModeChanged so PlayerScreen can hide controls
 * that are pointless (and untappable — the system owns touch on a PiP window) once shrunk down.
 */
object PipController {
  private val _available = MutableStateFlow(false)
  val available: StateFlow<Boolean> = _available.asStateFlow()

  private val _isInPip = MutableStateFlow(false)
  val isInPip: StateFlow<Boolean> = _isInPip.asStateFlow()

  fun setAvailable(value: Boolean) {
    _available.value = value
  }

  fun setInPip(value: Boolean) {
    _isInPip.value = value
  }
}
