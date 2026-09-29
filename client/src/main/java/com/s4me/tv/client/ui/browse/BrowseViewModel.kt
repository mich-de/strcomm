package com.s4me.tv.client.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.s4me.tv.engine.BrowseFilterStore
import com.s4me.tv.engine.Channel
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.CountryOption
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.SiteTraffic
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.TitleResolver
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BrowseUiState {
  data object Loading : BrowseUiState

  data class Success(val items: List<StreamItem>, val hasMore: Boolean, val loadingMore: Boolean) : BrowseUiState

  data class Error(val message: String) : BrowseUiState
}

/** Children of [root] with paging. For the filterable catalog root ([root.kind] == LIST) it also
 *  owns a [BrowseFilter] (seeded from `root.extra`, falling back to the last remembered filter for
 *  the generic entry point — see [BrowseFilterStore]) and reloads when it changes — including the
 *  curated Academy Award lists, which are resolved through search rather than listed. */
class BrowseViewModel(application: Application, private val root: StreamItem) : AndroidViewModel(application) {
  private val filterStore = BrowseFilterStore(application)
  private val resolver = TitleResolver.get(application)

  private val _uiState = MutableStateFlow<BrowseUiState>(BrowseUiState.Loading)
  val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

  // A deep link (root.extra set) always wins; only the plain "Sfoglia" entry consults the store.
  private val _filter =
    MutableStateFlow(
      if (root.kind == ItemKind.LIST && root.extra.isNullOrBlank()) BrowseFilter.fromExtra(filterStore.load())
      else BrowseFilter.fromExtra(root.extra),
    )
  val filter: StateFlow<BrowseFilter> = _filter.asStateFlow()

  private val _genreOptions = MutableStateFlow<List<GenreOption>>(emptyList())
  val genreOptions: StateFlow<List<GenreOption>> = _genreOptions.asStateFlow()

  private val _countryOptions = MutableStateFlow<List<CountryOption>>(emptyList())
  val countryOptions: StateFlow<List<CountryOption>> = _countryOptions.asStateFlow()

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
        _countryOptions.value = runCatching { channel.countries() }.getOrDefault(emptyList())
      }
    }
  }

  fun setFilter(f: BrowseFilter) {
    _filter.value = f
    if (filterable) filterStore.save(f.toExtra())
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
    // One title at a time in SiteTraffic's background lane — 35–89 searches fired 8 at a time was a
    // burst of the kind that got this app's addresses banned by the site. Matches are remembered
    // (TitleResolver), so only the first opening pays, and the list grows on screen as they land.
    withContext(SiteTraffic.Background) {
      entries.forEachIndexed { index, entry ->
        if (gen != generation) return@withContext
        resolveOscarEntry(channel, entry, markWinners)?.let { resolved += it }
        if (index % 4 == 3 || index == entries.lastIndex) emitOscars(resolved, gen, stillLoading = index < entries.lastIndex)
      }
    }
    if (resolved.isNotEmpty() && gen == generation) oscarCache[cacheKey] = resolved.toList()
  }

  private suspend fun resolveOscarEntry(channel: Channel, entry: OscarEntry, markWinners: Boolean): StreamItem? =
    runCatching {
      val year = entry.year.toString()
      val match =
        resolver.resolve(channel, key = "oscar:${entry.title.lowercase()}:$year", query = entry.title) { results ->
          val movies = results.filter { it.kind == ItemKind.MOVIE }
          movies.firstOrNull { it.title.equals(entry.title, ignoreCase = true) && it.year == year }
            ?: movies.firstOrNull { it.title.equals(entry.title, ignoreCase = true) }
            ?: movies.firstOrNull { it.title.contains(entry.title, ignoreCase = true) && it.year == year }
            ?: movies.firstOrNull { it.year == year }
        }
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
