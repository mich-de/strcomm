package com.s4me.tv.client.ui.search

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WHITESPACE = Regex("\\s+")

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

  private var job: Job? = null

  /** The catalogue's genres, for the pre-query "Sfoglia per genere" chips and for matching what's being
   *  typed against genre names. Cached by the channel once Home has loaded; if not yet, this is one
   *  request, taken in the background lane like every other fill-in fetch. */
  private val _genres = MutableStateFlow<List<GenreOption>>(emptyList())
  val genres: StateFlow<List<GenreOption>> = _genres.asStateFlow()

  init {
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.all.firstOrNull() ?: return@launch
      _genres.value = withContext(SiteTraffic.Background) { runCatching { channel.genres() }.getOrDefault(emptyList()) }
    }
  }

  /** Debounced live search — call on every keystroke. */
  fun onQueryChange(query: String) {
    job?.cancel()
    if (query.isBlank()) {
      _uiState.value = SearchUiState.Idle(historyStore.load())
      return
    }
    job = viewModelScope.launch {
      delay(320)
      runSearch(query, verifyPerson = false)
    }
  }

  fun search(query: String, verifyPerson: Boolean = false) {
    job?.cancel()
    job = viewModelScope.launch { runSearch(query, verifyPerson) }
  }

  private suspend fun runSearch(query: String, verifyPerson: Boolean) {
    if (query.isBlank()) return
    _uiState.value = SearchUiState.Loading
    val raw =
      withContextIO {
        coroutineScope {
          ChannelRegistry.all
            .map { channel -> async { runCatching { channel.search(query) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
        }
      }
    val results = if (verifyPerson) verifyCredited(raw, query) else raw
    if (results.isNotEmpty()) historyStore.add(query)
    _uiState.value =
      if (results.isEmpty()) SearchUiState.Error("Nessun risultato per “$query”") else SearchUiState.Success(results)
  }

  /** Keep only the raw hits that actually credit [personName] as cast or director (the site's
   *  search is fuzzy full-text), matching on a whitespace-normalised name, exact on a split token or
   *  as a substring of the joined credits. Each candidate costs one detail page, so the list is
   *  capped at [MAX_VERIFIED]: it used to verify up to 120 (8 at a time, plus a retry each), a burst
   *  of the kind that got every address this app used banned by the site (see SiteTraffic, which
   *  now paces them). Some of a very prolific actor's films sit past the cap and are left out. */
  private suspend fun verifyCredited(candidates: List<StreamItem>, personName: String): List<StreamItem> =
    withContext(SiteTraffic.Background) {
      val target = personName.trim().lowercase().replace(WHITESPACE, " ")
      coroutineScope {
        candidates
          .filter { it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES }
          .distinctBy { it.url }
          .take(MAX_VERIFIED)
          .map { item ->
            async(Dispatchers.IO) {
              val channel = ChannelRegistry.byId(item.channelId) ?: return@async null
              val detail = runCatching { channel.detail(item, withRatings = false) }.getOrNull() ?: return@async null
              val credits = listOfNotNull(detail.cast, detail.director).joinToString(", ").lowercase().replace(WHITESPACE, " ")
              val credited = credits.split(", ").any { it.trim() == target } || credits.contains(target)
              item.takeIf { credited }
            }
          }
          .awaitAll()
          .filterNotNull()
      }
    }

  private companion object {
    const val MAX_VERIFIED = 30
  }

  fun clearHistory() {
    historyStore.clear()
    _uiState.value = SearchUiState.Idle(emptyList())
  }

  private suspend fun <T> withContextIO(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
}
