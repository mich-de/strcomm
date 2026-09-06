package com.s4me.tv.client.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s4me.tv.engine.Channel
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BrowseUiState {
  data object Loading : BrowseUiState

  data class Success(val items: List<StreamItem>, val hasMore: Boolean, val loadingMore: Boolean) : BrowseUiState

  data class Error(val message: String) : BrowseUiState
}

/** Children of [root] with paging. For the filterable catalog root ([root.kind] == LIST) it also
 *  owns a [BrowseFilter] (seeded from `root.extra`) and reloads when it changes — including the
 *  curated Academy Award lists, which are resolved through search rather than listed. */
class BrowseViewModel(private val root: StreamItem) : ViewModel() {
  private val _uiState = MutableStateFlow<BrowseUiState>(BrowseUiState.Loading)
  val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

  private val _filter = MutableStateFlow(BrowseFilter.fromExtra(root.extra))
  val filter: StateFlow<BrowseFilter> = _filter.asStateFlow()

  private val _genreOptions = MutableStateFlow<List<GenreOption>>(emptyList())
  val genreOptions: StateFlow<List<GenreOption>> = _genreOptions.asStateFlow()

  val filterable = root.kind == ItemKind.LIST
  val years: List<Int> = Calendar.getInstance().get(Calendar.YEAR).let { y -> (y downTo y - 30).toList() }

  private var page = 1
  private var loaded: List<StreamItem> = emptyList()

  @Volatile private var generation = 0

  init {
    loadFirst()
    if (filterable) {
      viewModelScope.launch(Dispatchers.IO) {
        val channel = ChannelRegistry.byId(root.channelId) ?: return@launch
        _genreOptions.value = runCatching { channel.genres() }.getOrDefault(emptyList())
      }
    }
  }

  fun setFilter(f: BrowseFilter) {
    _filter.value = f
    loadFirst()
  }

  private fun requestRoot() = if (filterable) root.copy(extra = _filter.value.toExtra()) else root

  fun loadFirst() {
    _uiState.value = BrowseUiState.Loading
    page = 1
    val gen = ++generation
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.byId(root.channelId) ?: run {
        _uiState.value = BrowseUiState.Error("Canale non trovato")
        return@launch
      }
      when (_filter.value.award) {
        "oscar" -> {
          loadCuratedOscars(channel, OSCAR_WINNER_ENTRIES, markWinners = false, cacheKey = "winners", gen = gen)
          return@launch
        }
        "oscar-nominees" -> {
          loadCuratedOscars(channel, OSCAR_NOMINEE_ENTRIES, markWinners = true, cacheKey = "nominees", gen = gen)
          return@launch
        }
      }
      runCatching { channel.list(requestRoot(), page) }
        .onSuccess {
          if (gen != generation) return@onSuccess
          loaded = it.items
          page++
          _uiState.value = BrowseUiState.Success(loaded, it.hasMore, false)
        }
        .onFailure { if (gen == generation) _uiState.value = BrowseUiState.Error(it.message ?: "Errore di caricamento") }
    }
  }

  fun loadMore() {
    val current = _uiState.value as? BrowseUiState.Success ?: return
    if (!current.hasMore || current.loadingMore) return
    _uiState.value = current.copy(loadingMore = true)
    val gen = generation
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.byId(root.channelId) ?: return@launch
      runCatching { channel.list(requestRoot(), page) }
        .onSuccess {
          if (gen != generation) return@onSuccess
          loaded = loaded + it.items
          page++
          _uiState.value = BrowseUiState.Success(loaded, it.hasMore, false)
        }
        .onFailure { if (gen == generation) _uiState.value = current.copy(loadingMore = false) }
    }
  }

  // --- Curated Oscar lists (resolved through search, emitted progressively, cached process-wide) ---

  private suspend fun loadCuratedOscars(channel: Channel, entries: List<OscarEntry>, markWinners: Boolean, cacheKey: String, gen: Int) {
    oscarCache[cacheKey]?.let {
      emitOscars(it, gen, stillLoading = false)
      return
    }
    val resolved = mutableListOf<StreamItem>()
    val chunks = entries.chunked(8)
    coroutineScope {
      chunks.forEachIndexed { index, chunk ->
        if (gen != generation) return@coroutineScope
        val batch = chunk.map { e -> async { resolveOscarEntry(channel, e, markWinners) } }.awaitAll().filterNotNull()
        resolved += batch
        emitOscars(resolved, gen, stillLoading = index < chunks.lastIndex)
      }
    }
    if (resolved.isNotEmpty() && gen == generation) oscarCache[cacheKey] = resolved.toList()
  }

  private suspend fun resolveOscarEntry(channel: Channel, entry: OscarEntry, markWinners: Boolean): StreamItem? =
    runCatching {
      val movies = channel.search(entry.title).filter { it.kind == ItemKind.MOVIE }
      val match =
        movies.firstOrNull { it.title.equals(entry.title, ignoreCase = true) && it.year == entry.year.toString() }
          ?: movies.firstOrNull { it.title.equals(entry.title, ignoreCase = true) }
          ?: movies.firstOrNull { it.title.contains(entry.title, ignoreCase = true) && it.year == entry.year.toString() }
          ?: movies.firstOrNull { it.year == entry.year.toString() }
      match?.copy(
        year = entry.year.toString(),
        title = if (markWinners && entry.winner) "🏆 ${match.title}" else match.title,
      )
    }.getOrNull()

  private fun emitOscars(items: List<StreamItem>, gen: Int, stillLoading: Boolean) {
    if (gen != generation) return
    val yearFilter = _filter.value.year
    val visible = items.distinctBy { it.url }.filter { yearFilter == null || it.year == yearFilter.toString() }
    _uiState.value = BrowseUiState.Success(visible, hasMore = false, loadingMore = stillLoading)
  }

  private companion object {
    private val oscarCache = ConcurrentHashMap<String, List<StreamItem>>()
  }
}
