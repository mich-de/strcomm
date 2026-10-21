package com.s4me.tv.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.SearchHistoryStore
import com.s4me.tv.engine.SiteTraffic
import com.s4me.tv.engine.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SearchUiState {
  data class Idle(val history: List<String> = emptyList()) : SearchUiState

  data object Loading : SearchUiState

  data class Success(val results: List<StreamItem>) : SearchUiState

  data class Error(val message: String) : SearchUiState
}

class SearchViewModel(application: Application) : AndroidViewModel(application) {
  private val historyStore = SearchHistoryStore(application)
  private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle(historyStore.load()))
  val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

  /** The catalogue's genres: the "Sfoglia per genere" row before typing, and what a typed fragment
   *  ("comm") is matched against. Cached by the channel once Home has loaded; otherwise one request,
   *  taken in the background lane like every other fill-in fetch. */
  private val _genres = MutableStateFlow<List<GenreOption>>(emptyList())
  val genres: StateFlow<List<GenreOption>> = _genres.asStateFlow()

  init {
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.all.firstOrNull() ?: return@launch
      _genres.value = withContext(SiteTraffic.Background) { runCatching { channel.genres() }.getOrDefault(emptyList()) }
    }
  }

  fun search(query: String, verifyPerson: Boolean = false) {
    if (query.isBlank()) {
      _uiState.value = SearchUiState.Idle(historyStore.load())
      return
    }
    _uiState.value = SearchUiState.Loading
    viewModelScope.launch(Dispatchers.IO) {
      val raw =
        coroutineScope {
          ChannelRegistry.all
            .map { channel -> async { runCatching { channel.search(query) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
        }
      val results = if (verifyPerson) verifyCredited(raw, query) else raw
      if (results.isNotEmpty()) historyStore.add(query)
      _uiState.value =
        if (results.isEmpty()) SearchUiState.Error("Nessun risultato per “$query”") else SearchUiState.Success(results)
    }
  }

  /** The site's own search is fuzzy full-text, not a credit lookup — a full name still pulls in
   *  unrelated titles further down the raw results (verified live: "Vittorio Gassman" surfaced
   *  "Monella", whose actual cast has no Gassman in it at all). Re-fetching each candidate's own
   *  detail page and checking whether [personName] genuinely appears in ITS cast/director is the
   *  only reliable filter. Capped to bound the extra fetches per name click — 45, not 24: a
   *  prolific actor (De Niro) has real credits spread the full length of a 60-title raw list, and
   *  24 was silently dropping the back half ("Heat", "The Irishman", "Joker"…). `withRatings=false`
   *  keeps each fetch to a single request. Now 30: 45 detail pages fired at once is the kind of
   *  burst that got every address this app used banned by the site (see SiteTraffic, which paces
   *  them now) — a prolific actor loses a few titles past the cap. */
  private suspend fun verifyCredited(candidates: List<StreamItem>, personName: String): List<StreamItem> =
    withContext(SiteTraffic.Background) {
      coroutineScope {
        candidates
          .filter { it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES }
          .take(30)
          .map { item ->
            async {
              val channel = ChannelRegistry.byId(item.channelId) ?: return@async null
              val detail = runCatching { channel.detail(item, withRatings = false) }.getOrNull() ?: return@async null
              val credited =
                listOfNotNull(detail.cast, detail.director)
                  .flatMap { it.split(", ") }
                  .any { it.equals(personName, ignoreCase = true) }
              item.takeIf { credited }
            }
          }
          .awaitAll()
          .filterNotNull()
      }
    }

  fun clearHistory() {
    historyStore.clear()
    _uiState.value = SearchUiState.Idle(emptyList())
  }
}
