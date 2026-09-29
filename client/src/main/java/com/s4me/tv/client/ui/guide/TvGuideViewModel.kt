package com.s4me.tv.client.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.NowOnItalianTV
import com.s4me.tv.engine.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface TvGuideUiState {
  data object Loading : TvGuideUiState

  data class Success(val entries: List<NowOnItalianTV.Entry>) : TvGuideUiState

  data class Error(val message: String) : TvGuideUiState
}

/** The FULL guide behind Home's "Film stasera in TV" row — that row only shows the first 20
 *  entries that already resolved through search, so it can render immediately; this screen shows
 *  every entry [NowOnItalianTV] returns and only resolves a title through the catalog when you
 *  actually tap it (one at a time), instead of firing dozens of searches just to render the list. */
class TvGuideViewModel : ViewModel() {
  private val _uiState = MutableStateFlow<TvGuideUiState>(TvGuideUiState.Loading)
  val uiState: StateFlow<TvGuideUiState> = _uiState.asStateFlow()

  /** The entry currently being resolved (its stable row key), or null — see [rowKey]. */
  private val _resolving = MutableStateFlow<String?>(null)
  val resolving: StateFlow<String?> = _resolving.asStateFlow()

  init {
    load()
  }

  fun load() {
    _uiState.value = TvGuideUiState.Loading
    viewModelScope.launch(Dispatchers.IO) {
      val entries = runCatching { NowOnItalianTV.fetchTonight() }.getOrDefault(emptyList())
      _uiState.value =
        if (entries.isEmpty()) TvGuideUiState.Error("Guida non disponibile al momento") else TvGuideUiState.Success(entries)
    }
  }

  fun resolve(entry: NowOnItalianTV.Entry, onFound: (StreamItem) -> Unit, onNotFound: () -> Unit) {
    if (_resolving.value != null) return
    val key = rowKey(entry)
    _resolving.value = key
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.all.firstOrNull()
      val found =
        channel?.let { c ->
          runCatching {
            val results = c.search(entry.title).filter { it.kind == ItemKind.MOVIE }
            results.firstOrNull { it.title.equals(entry.title, ignoreCase = true) }
              ?: results.firstOrNull { it.title.contains(entry.title, ignoreCase = true) || entry.title.contains(it.title, ignoreCase = true) }
          }.getOrNull()
        }
      _resolving.value = null
      withContext(Dispatchers.Main) {
        if (found != null) onFound(found) else onNotFound()
      }
    }
  }

  companion object {
    fun rowKey(entry: NowOnItalianTV.Entry) = "${entry.time}|${entry.slug}|${entry.title}"
  }
}
