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

  private suspend fun verifyCredited(candidates: List<StreamItem>, personName: String): List<StreamItem> =
    coroutineScope {
      candidates
        .filter { it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES }
        .take(45)
        .map { item ->
          async(Dispatchers.IO) {
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

  fun clearHistory() {
    historyStore.clear()
    _uiState.value = SearchUiState.Idle(emptyList())
  }

  private suspend fun <T> withContextIO(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
}
