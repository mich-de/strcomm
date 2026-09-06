package com.s4me.tv.remote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.remote.PlaybackCommand
import com.s4me.tv.remote.PlaybackStatus
import com.s4me.tv.remote.RemoteControlProtocol
import com.s4me.tv.remote.data.TvPreferences
import com.s4me.tv.remote.discovery.DiscoveredTv
import com.s4me.tv.remote.discovery.TvDiscovery
import com.s4me.tv.remote.net.TvClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchState {
  data object Idle : SearchState

  data object Loading : SearchState

  data class Results(val items: List<StreamItem>) : SearchState
}

/** Seasons of a tapped SERIES, or episodes of a tapped SEASON — one shared shape since both are
 *  just "children of the node the phone drilled into." */
sealed interface ChildrenState {
  data object Loading : ChildrenState

  data class Results(val items: List<StreamItem>) : ChildrenState

  data object Empty : ChildrenState
}

// Consecutive failed reachability checks (see startPollingNowPlaying) before we treat the TV as
// gone and drop back to discovery. Each check already blocks on TvClient's connect timeout, so 3
// is several seconds of genuine silence — enough to ride out a brief Wi-Fi blip without nagging.
private const val MAX_REACHABILITY_FAILURES = 3

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
  private val discovery = TvDiscovery(application)
  private val client = TvClient()
  private val prefs = TvPreferences(application)

  private val _discovered = MutableStateFlow<List<DiscoveredTv>>(emptyList())
  val discovered: StateFlow<List<DiscoveredTv>> = _discovered.asStateFlow()

  private val _connectedTv = MutableStateFlow<DiscoveredTv?>(null)
  val connectedTv: StateFlow<DiscoveredTv?> = _connectedTv.asStateFlow()

  // The TV a connection attempt is in flight for (tapped card, typed IP, or the auto-reconnect on
  // launch) — the UI shows a "connecting to X…" screen while this is non-null and nothing's
  // connected yet, so a wrong IP doesn't silently land on a dead search screen.
  private val _connecting = MutableStateFlow<DiscoveredTv?>(null)
  val connecting: StateFlow<DiscoveredTv?> = _connecting.asStateFlow()

  private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
  val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

  private val _message = MutableStateFlow<String?>(null)
  val message: StateFlow<String?> = _message.asStateFlow()

  private val _childrenState = MutableStateFlow<ChildrenState>(ChildrenState.Loading)
  val childrenState: StateFlow<ChildrenState> = _childrenState.asStateFlow()

  private val _nowPlaying = MutableStateFlow<PlaybackStatus?>(null)
  val nowPlaying: StateFlow<PlaybackStatus?> = _nowPlaying.asStateFlow()

  private var searchJob: Job? = null
  private var childrenJob: Job? = null
  private var nowPlayingJob: Job? = null
  private var connectJob: Job? = null

  init {
    viewModelScope.launch { discovery.discover().collect { _discovered.value = it } }
    // Auto-reconnect to wherever we were last, silently — this exists for "app got killed in the
    // background, reopen it" and shouldn't pop an error toast if that TV is now off or elsewhere;
    // it just falls through to the discovery screen.
    prefs.lastTv?.let { connectTo(it, announceFailure = false) }
  }

  fun connect(tv: DiscoveredTv) {
    connectTo(tv)
  }

  fun connectManually(host: String) {
    val trimmed = host.trim().substringBefore(":")
    if (trimmed.isBlank()) return
    connectTo(DiscoveredTv(name = trimmed, host = trimmed, port = RemoteControlProtocol.DEFAULT_PORT))
  }

  /** Pings [candidate] first and only switches the UI over once it answers as a real StrComm TV,
   *  so a mistyped IP (or a TV that's since gone away) surfaces as a clear failure instead of a
   *  search screen where every query mysteriously returns nothing. */
  private fun connectTo(candidate: DiscoveredTv, announceFailure: Boolean = true) {
    connectJob?.cancel()
    _connecting.value = candidate
    connectJob =
      viewModelScope.launch {
        val name = client.handshake(candidate.host, candidate.port)
        _connecting.value = null
        if (name == null) {
          if (announceFailure) _message.value = "Impossibile connettersi a ${candidate.name}"
          return@launch
        }
        // Prefer the name the TV reports over a hand-typed IP or a stale saved label.
        val tv = candidate.copy(name = name)
        _connectedTv.value = tv
        _searchState.value = SearchState.Idle
        prefs.lastTv = tv
        startPollingNowPlaying(tv)
      }
  }

  /** User tapped "Cambia" — tear down and also forget this TV, so the next launch shows discovery
   *  rather than auto-reconnecting to one the user deliberately walked away from. */
  fun disconnect() {
    teardownConnection()
    prefs.lastTv = null
  }

  // Shared by the explicit disconnect and the "connection lost" path. The latter keeps
  // prefs.lastTv on purpose: a TV that dropped off Wi-Fi is exactly the case where retrying it on
  // the next launch is the helpful thing to do.
  private fun teardownConnection() {
    connectJob?.cancel()
    _connecting.value = null
    _connectedTv.value = null
    _searchState.value = SearchState.Idle
    nowPlayingJob?.cancel()
    _nowPlaying.value = null
  }

  // Polls rather than pushing over a socket — a "now playing" bar refreshing once a second is
  // plenty responsive for transport controls, and needs no long-lived connection to keep alive.
  // Doubles as the liveness check: /now-playing returning null is ambiguous (idle vs. gone), so a
  // null answer triggers a /ping, and enough pings in a row failing means the TV has dropped and
  // we bounce back to discovery rather than leaving the user tapping a dead remote.
  private fun startPollingNowPlaying(tv: DiscoveredTv) {
    nowPlayingJob?.cancel()
    nowPlayingJob =
      viewModelScope.launch {
        var failures = 0
        while (true) {
          val status = client.nowPlaying(tv.host, tv.port)
          when {
            status != null -> {
              _nowPlaying.value = status
              failures = 0
            }
            client.handshake(tv.host, tv.port) != null -> {
              _nowPlaying.value = null
              failures = 0
            }
            ++failures >= MAX_REACHABILITY_FAILURES -> {
              _message.value = "Connessione persa con ${tv.name}"
              teardownConnection()
              return@launch
            }
          }
          // Back off while idle (just liveness pings); stay snappy while something's playing so
          // the scrubber and play/pause state don't lag.
          delay(if (_nowPlaying.value != null) 1000 else 2500)
        }
      }
  }

  fun sendCommand(command: PlaybackCommand) {
    val tv = _connectedTv.value ?: return
    viewModelScope.launch { client.sendControl(tv.host, tv.port, command) }
  }

  fun onQueryChange(query: String) {
    searchJob?.cancel()
    if (query.isBlank()) {
      _searchState.value = SearchState.Idle
      return
    }
    val tv = _connectedTv.value ?: return
    searchJob =
      viewModelScope.launch {
        delay(400) // debounce so every keystroke doesn't fire its own request to the TV
        _searchState.value = SearchState.Loading
        _searchState.value = SearchState.Results(client.search(tv.host, tv.port, query))
      }
  }

  /** Called when a Seasons or Episodes screen is shown for [parent] — loads its children fresh
   *  each time rather than caching, since it's one cheap request and a season's episode list
   *  never needs a manual "refresh" affordance this way. */
  fun loadChildren(parent: StreamItem) {
    val tv = _connectedTv.value ?: return
    childrenJob?.cancel()
    _childrenState.value = ChildrenState.Loading
    childrenJob =
      viewModelScope.launch {
        val items = client.list(tv.host, tv.port, parent)
        _childrenState.value = if (items.isEmpty()) ChildrenState.Empty else ChildrenState.Results(items)
      }
  }

  fun send(item: StreamItem) {
    val tv = _connectedTv.value ?: return
    viewModelScope.launch {
      val ok = client.play(tv.host, tv.port, item)
      _message.value = if (ok) "Inviato a ${tv.name}" else "Impossibile raggiungere ${tv.name}"
    }
  }

  fun consumeMessage() {
    _message.value = null
  }
}
