package com.s4me.tv.client.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.SearchHistoryStore
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
   *  search is fuzzy full-text). Two things had been quietly costing real credits: a hard `take(45)`
   *  cut — a person's own films rank LOW in a text search for their name, so the real ones sit past
   *  #45 — and a lone failed `detail()` fetch (45 fired at once) dropping that title silently.
   *  So: verify the whole list (bounded to 8 in flight, one retry each), and match on a
   *  whitespace-normalised name, exact on a split token or as a substring of the joined credits. */
  private suspend fun verifyCredited(candidates: List<StreamItem>, personName: String): List<StreamItem> {
    val target = personName.trim().lowercase().replace(WHITESPACE, " ")
    val gate = Semaphore(8)
    return coroutineScope {
      candidates
        .filter { it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES }
        .distinctBy { it.url }
        .take(120) // safety valve only — a real person's result list is far shorter
        .map { item ->
          async(Dispatchers.IO) {
            gate.withPermit {
              val channel = ChannelRegistry.byId(item.channelId) ?: return@withPermit null
              val detail =
                runCatching { channel.detail(item, withRatings = false) }.getOrNull()
                  ?: runCatching { channel.detail(item, withRatings = false) }.getOrNull()
                  ?: return@withPermit null
              val credits = listOfNotNull(detail.cast, detail.director).joinToString(", ").lowercase().replace(WHITESPACE, " ")
              val credited = credits.split(", ").any { it.trim() == target } || credits.contains(target)
              item.takeIf { credited }
            }
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

  private suspend fun <T> withContextIO(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
}
