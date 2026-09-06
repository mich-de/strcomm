package com.s4me.tv.client.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.s4me.tv.engine.Channel
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.HomeSection
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.JustWatchTop10
import com.s4me.tv.engine.NetflixTop10
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchProgressStore
import com.s4me.tv.engine.WatchlistStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val CONTINUE_WATCHING_ID = "continue-watching"
internal const val WATCHLIST_ID = "watchlist"
internal const val TRENDING_ID = "netflix-trending"
internal const val JUSTWATCH_ID = "justwatch-trending"

sealed interface HomeUiState {
  data object Loading : HomeUiState

  data class Success(val heroes: List<StreamItem>, val sections: List<HomeSection>) : HomeUiState

  data class Error(val message: String) : HomeUiState
}

/** Same shape and data as the TV app's HomeViewModel — personal rows + the two external trending
 *  charts + the source site's own rows, plus hero enrichment landing as a follow-up state update. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
  private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
  val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

  val catalogRoot: StreamItem? = ChannelRegistry.all.firstOrNull()?.catalogRoot()

  private val _genres = MutableStateFlow<List<GenreOption>>(emptyList())
  val genres: StateFlow<List<GenreOption>> = _genres.asStateFlow()

  private val progressStore = WatchProgressStore(application)
  private val watchlistStore = WatchlistStore(application)

  init {
    load()
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.all.firstOrNull() ?: return@launch
      _genres.value = runCatching { channel.genres() }.getOrDefault(emptyList())
    }
  }

  /** Re-reads the local stores and swaps the personal rows in place — call when returning to Home. */
  fun refreshPersonalRows() {
    viewModelScope.launch(Dispatchers.IO) {
      val current = _uiState.value as? HomeUiState.Success ?: return@launch
      val catalog = current.sections.filterNot { it.channelId == CONTINUE_WATCHING_ID || it.channelId == WATCHLIST_ID }
      _uiState.value = current.copy(sections = personalSections() + catalog)
    }
  }

  private fun personalSections(): List<HomeSection> {
    val continueItems =
      progressStore.inProgress().take(10).map {
        // Normalise: entries must be navigable MOVIE/EPISODE so a tap opens Detail and re-resolves
        // a fresh stream. (Older builds stored the raw PLAYABLE, whose url is a dead content id.)
        val i = it.item
        val navigable =
          if (i.kind == ItemKind.PLAYABLE) i.copy(kind = if (i.episode != null) ItemKind.EPISODE else ItemKind.MOVIE) else i
        navigable.copy(progress = it.fraction)
      }
    val watchlistItems = watchlistStore.all().take(15)
    return listOfNotNull(
      HomeSection("Continua a guardare", CONTINUE_WATCHING_ID, continueItems).takeIf { continueItems.isNotEmpty() },
      HomeSection("La mia lista", WATCHLIST_ID, watchlistItems).takeIf { watchlistItems.isNotEmpty() },
    )
  }

  private suspend fun loadTrendingSection(): HomeSection? {
    cachedTrending?.let { return HomeSection("I titoli del momento", TRENDING_ID, it) }
    val channel = ChannelRegistry.all.firstOrNull() ?: return null
    val raw = runCatching { NetflixTop10.fetchItaly() }.getOrDefault(emptyList()).map { RawChartEntry(it.rank, it.title, it.isSeries) }
    val section = resolveChartSection("I titoli del momento", TRENDING_ID, raw, channel) ?: return null
    cachedTrending = section.items
    return section
  }

  private suspend fun loadJustWatchSection(): HomeSection? {
    cachedJustWatch?.let { return HomeSection("Popolari su tutte le piattaforme", JUSTWATCH_ID, it) }
    val channel = ChannelRegistry.all.firstOrNull() ?: return null
    val raw = runCatching { JustWatchTop10.fetchItaly() }.getOrDefault(emptyList()).map { RawChartEntry(it.rank, it.title, it.isSeries) }
    val section = resolveChartSection("Popolari su tutte le piattaforme", JUSTWATCH_ID, raw, channel) ?: return null
    cachedJustWatch = section.items
    return section
  }

  private data class RawChartEntry(val rank: Int, val title: String, val isSeries: Boolean)

  private suspend fun resolveChartSection(title: String, sectionId: String, raw: List<RawChartEntry>, channel: Channel): HomeSection? {
    if (raw.isEmpty()) return null
    val movies = raw.filter { !it.isSeries }.sortedBy { it.rank }
    val series = raw.filter { it.isSeries }.sortedBy { it.rank }
    val interleaved = movies.zip(series).flatMap { (m, s) -> listOf(m, s) } + movies.drop(series.size) + series.drop(movies.size)
    val resolved =
      coroutineScope { interleaved.map { entry -> async { resolveChartEntry(channel, entry) } }.awaitAll() }
        .filterNotNull()
        .distinctBy { it.url }
        .take(10)
        .mapIndexed { index, item -> item.copy(rank = index + 1) }
    if (resolved.isEmpty()) return null
    return HomeSection(title, sectionId, resolved)
  }

  private suspend fun resolveChartEntry(channel: Channel, entry: RawChartEntry): StreamItem? =
    runCatching {
      val wantedKind = if (entry.isSeries) ItemKind.SERIES else ItemKind.MOVIE
      val results = channel.search(entry.title).filter { it.kind == wantedKind }
      results.firstOrNull { it.title.equals(entry.title, ignoreCase = true) }
        ?: results.firstOrNull { it.title.contains(entry.title, ignoreCase = true) || entry.title.contains(it.title, ignoreCase = true) }
    }.getOrNull()

  private companion object {
    @Volatile private var cachedTrending: List<StreamItem>? = null
    @Volatile private var cachedJustWatch: List<StreamItem>? = null
  }

  fun load() {
    _uiState.value = HomeUiState.Loading
    viewModelScope.launch(Dispatchers.IO) {
      if (ChannelRegistry.all.isEmpty()) {
        _uiState.value = HomeUiState.Error("Nessun canale configurato")
        return@launch
      }
      val (channelSections, trendingSection, justWatchSection) =
        coroutineScope {
          val channelSectionsDeferred =
            async {
              ChannelRegistry.all
                .map { channel -> async { runCatching { channel.home() }.getOrElse { emptyList() } } }
                .awaitAll()
                .flatten()
            }
          val trendingDeferred = async { loadTrendingSection() }
          val justWatchDeferred = async { loadJustWatchSection() }
          Triple(channelSectionsDeferred.await(), trendingDeferred.await(), justWatchDeferred.await())
        }
      if (channelSections.isEmpty()) {
        _uiState.value = HomeUiState.Error("Nessun canale raggiungibile al momento")
        return@launch
      }
      val sections = personalSections() + listOfNotNull(trendingSection, justWatchSection) + channelSections
      val heroCandidates =
        channelSections.firstOrNull()
          ?.items
          ?.filter { it.backdrop != null && (it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES) }
          ?.take(6)
          .orEmpty()
      _uiState.value = HomeUiState.Success(heroCandidates, sections)

      heroCandidates.forEachIndexed { index, candidate ->
        val channel = ChannelRegistry.byId(candidate.channelId) ?: return@forEachIndexed
        val enriched = runCatching { channel.detail(candidate) }.getOrNull() ?: return@forEachIndexed
        val current = _uiState.value
        if (current is HomeUiState.Success && index < current.heroes.size) {
          _uiState.value = current.copy(heroes = current.heroes.toMutableList().also { it[index] = enriched })
        }
      }

      appendGenreRows()
    }
  }

  /** After the base Home is on screen, fill it out with a "best of" row per genre — a page each,
   *  fetched in parallel, appended one by one as they land so the screen keeps growing. */
  private suspend fun appendGenreRows() {
    val channel = ChannelRegistry.all.firstOrNull() ?: return
    val root = channel.catalogRoot() ?: return
    val genreList = runCatching { channel.genres() }.getOrDefault(emptyList()).take(10)
    if (genreList.isEmpty()) return
    coroutineScope {
      val deferred =
        genreList.map { g ->
          g to async(Dispatchers.IO) {
            runCatching { channel.list(root.copy(extra = "genre=${g.id}"), 1) }.getOrNull()?.items.orEmpty()
          }
        }
      for ((genre, job) in deferred) {
        val items = job.await().filter { it.thumbnail != null }
        if (items.size < 5) continue
        val current = _uiState.value as? HomeUiState.Success ?: return@coroutineScope
        val id = "genre-${genre.id}"
        if (current.sections.none { it.channelId == id }) {
          _uiState.value = current.copy(sections = current.sections + HomeSection(genre.name, id, items))
        }
      }
    }
  }
}
