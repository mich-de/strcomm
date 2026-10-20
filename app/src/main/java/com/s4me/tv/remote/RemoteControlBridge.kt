package com.s4me.tv.remote

import com.s4me.tv.engine.StreamItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Hands a StreamItem picked on the phone companion app from [RemoteControlServer]'s background
 *  request thread over to MainNavigation's collector on the main thread. No replay: a stale "play"
 *  re-delivered to a fresh collector (e.g. after a config change) would re-trigger navigation the
 *  user never asked for twice, so a send that truly races app startup is simply missed rather than
 *  risking a phantom repeat later — the buffer only covers that brief startup window. */
object RemoteControlBridge {
  private val _incoming = MutableSharedFlow<StreamItem>(extraBufferCapacity = 4)
  val incoming: SharedFlow<StreamItem> = _incoming.asSharedFlow()

  fun submit(item: StreamItem) {
    _incoming.tryEmit(item)
  }
}
