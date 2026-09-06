package com.s4me.tv.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bridges the currently open PlayerScreen (a local, Compose-remembered ExoPlayer the HTTP server
 *  has no direct handle to) and RemoteControlServer's playback endpoints: PlayerScreen publishes
 *  [status] while playing and collects [commands]; the server only ever reads/writes through here.
 *  [status] is null whenever no native player is on screen. */
object PlaybackControlBridge {
  private val _status = MutableStateFlow<PlaybackStatus?>(null)
  val status: StateFlow<PlaybackStatus?> = _status.asStateFlow()

  private val _commands = MutableSharedFlow<PlaybackCommand>(extraBufferCapacity = 4)
  val commands: SharedFlow<PlaybackCommand> = _commands.asSharedFlow()

  fun publish(status: PlaybackStatus?) {
    _status.value = status
  }

  fun send(command: PlaybackCommand) {
    _commands.tryEmit(command)
  }
}
