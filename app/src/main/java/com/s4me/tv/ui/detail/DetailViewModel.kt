package com.s4me.tv.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DetailUiState {
  data object Loading : DetailUiState

  data class Success(val item: StreamItem, val sources: List<StreamItem>) : DetailUiState

  data class Error(val message: String) : DetailUiState
}

class DetailViewModel(private val item: StreamItem) : ViewModel() {
  private val _uiState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
  val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

  /** "Altri capitoli della saga" — the movie's TMDB collection siblings, resolved to catalogue
   *  items. Loads independently of the play sources and stays empty for a standalone film. */
  private val _related = MutableStateFlow<List<StreamItem>>(emptyList())
  val related: StateFlow<List<StreamItem>> = _related.asStateFlow()

  init {
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.byId(item.channelId)
      if (channel == null) {
        _uiState.value = DetailUiState.Error("Canale non trovato")
        return@launch
      }
      val enriched = runCatching { channel.detail(item) }.getOrDefault(item)
      launch { _related.value = runCatching { channel.collection(enriched) }.getOrDefault(emptyList()) }
      // Resolve from the enriched item, not the raw nav-arg one, so findVideos() has the full
      // genres/cast/director/runtime/tmdbId/imdbId to carry onto the PLAYABLE item for the player's
      // info overlay — resolution itself only reads url/kind, both untouched by detail().
      runCatching { channel.findVideos(enriched) }
        .onSuccess { sources ->
          if (sources.isEmpty()) {
            _uiState.value = DetailUiState.Error("Nessuna fonte trovata per questo contenuto")
            return@onSuccess
          }
          _uiState.value = DetailUiState.Success(enriched, sources)
        }
        .onFailure { _uiState.value = DetailUiState.Error(it.message ?: "Errore di caricamento") }
    }
  }
}
