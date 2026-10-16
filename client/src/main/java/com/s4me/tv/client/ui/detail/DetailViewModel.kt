package com.s4me.tv.client.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s4me.tv.engine.Channel
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.SiteTraffic
import com.s4me.tv.engine.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DetailUiState {
  data object Loading : DetailUiState

  /** [item] enriched with plot/cast/ratings/trailer; [sources] are the resolved PLAYABLE streams. */
  data class Success(val item: StreamItem, val sources: List<StreamItem>) : DetailUiState

  data class Error(val message: String) : DetailUiState
}

/** Titles sharing [base]'s first catalogue genre, without [base] itself. Empty when no genre matches. */
private suspend fun similarTo(channel: Channel, base: StreamItem): List<StreamItem> {
  val names = base.genres?.split(",")?.map { it.trim() }.orEmpty()
  if (names.isEmpty()) return emptyList()
  val genre = channel.genres().firstOrNull { g -> names.any { it.equals(g.name, ignoreCase = true) } } ?: return emptyList()
  val root = channel.catalogRoot() ?: return emptyList()
  return channel
    .list(root.copy(extra = "genre=${genre.id}"), 1)
    .items
    .filter { it.url != base.url && (it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES) }
    .take(12)
}

class DetailViewModel(private val item: StreamItem) : ViewModel() {
  private val _uiState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
  val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

  /** Enriched item available before sources resolve, so the screen can render metadata early. */
  private val _preview = MutableStateFlow(item)
  val preview: StateFlow<StreamItem> = _preview.asStateFlow()

  /** "Altri capitoli della saga" — the movie's TMDB collection siblings, resolved to catalogue
   *  items. Lands after enrichment, independently of the play sources. Empty for a standalone film. */
  private val _related = MutableStateFlow<List<StreamItem>>(emptyList())
  val related: StateFlow<List<StreamItem>> = _related.asStateFlow()

  /** "Simili": other titles of this one's first genre — the closest thing to "More like this" the
   *  source offers. One catalogue page, taken in the background lane after the page has rendered. */
  private val _similar = MutableStateFlow<List<StreamItem>>(emptyList())
  val similar: StateFlow<List<StreamItem>> = _similar.asStateFlow()

  init {
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.byId(item.channelId)
      if (channel == null) {
        _uiState.value = DetailUiState.Error("Canale non trovato")
        return@launch
      }
      val enriched = runCatching { channel.detail(item) }.getOrDefault(item)
      _preview.value = enriched
      launch { _related.value = runCatching { channel.collection(enriched) }.getOrDefault(emptyList()) }
      launch { _similar.value = withContext(SiteTraffic.Background) { runCatching { similarTo(channel, enriched) }.getOrDefault(emptyList()) } }
      runCatching { channel.findVideos(enriched) }
        .onSuccess { sources ->
          _uiState.value =
            if (sources.isEmpty()) DetailUiState.Error("Nessuna fonte trovata per questo contenuto")
            else DetailUiState.Success(enriched, sources)
        }
        .onFailure { _uiState.value = DetailUiState.Error(it.message ?: "Errore di caricamento") }
    }
  }
}
