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
import com.s4me.tv.engine.NowOnItalianTV
import com.s4me.tv.engine.SiteTraffic
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.TitleResolver
import com.s4me.tv.engine.UpcomingAtCinema
import com.s4me.tv.engine.WatchProgressStore
import com.s4me.tv.engine.WatchlistStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val CONTINUE_WATCHING_ID = "continue-watching"
internal const val WATCHLIST_ID = "watchlist"
internal const val TRENDING_ID = "netflix-trending"
internal const val JUSTWATCH_ID = "justwatch-trending"
internal const val NOW_ON_TV_ID = "now-on-italian-tv"
internal const val UPCOMING_ID = "upcoming-at-cinema"

/** The chart rows' fixed order under the personal rows — they land one by one after the Home is
 *  already up (see [HomeViewModel.load]), so each is slotted into place rather than appended. */
private val CHART_ORDER = listOf(TRENDING_ID, JUSTWATCH_ID, NOW_ON_TV_ID, UPCOMING_ID)

/** Uncached title searches one Home load may spend across all chart rows — see [TitleResolver.Budget]. */
private const val CHART_SEARCH_BUDGET = 15

/** "16 settembre 2026" -> "16 Set" — a full Italian month name is too wide for a poster badge. */
internal fun compactItalianDate(date: String): String {
  val parts = date.split(" ")
  if (parts.size < 2) return date
  return "${parts[0]} ${parts[1].take(3).replaceFirstChar { it.uppercase() }}"
}

sealed interface HomeUiState {
  data object Loading : HomeUiState

  data class Success(val heroes: List<StreamItem>, val sections: List<HomeSection>) : HomeUiState

  data class Error(val message: String) : HomeUiState
}

/** Same shape and data as the TV app's HomeViewModel — personal rows + the external chart rows +
 *  the source site's own rows, plus hero enrichment landing as a follow-up state update. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
  private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
  val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

  val catalogRoot: StreamItem? = ChannelRegistry.all.firstOrNull()?.catalogRoot()

  private val _genres = MutableStateFlow<List<GenreOption>>(emptyList())
  val genres: StateFlow<List<GenreOption>> = _genres.asStateFlow()

  private val progressStore = WatchProgressStore(application)
  private val watchlistStore = WatchlistStore(application)
  private val resolver = TitleResolver.get(application)

  init {
    load()
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.all.firstOrNull() ?: return@launch
      _genres.value = runCatching { channel.genres() }.getOrDefault(emptyList())
    }
  }

  /** Re-reads the local stores and swaps the personal rows in place — call when returning to Home.
   *  An atomic update: chart and genre rows may be landing from the load at the same moment. */
  fun refreshPersonalRows() {
    viewModelScope.launch(Dispatchers.IO) {
      val personal = personalSections()
      _uiState.update { state ->
        if (state !is HomeUiState.Success) state
        else state.copy(sections = personal + state.sections.filterNot { it.channelId == CONTINUE_WATCHING_ID || it.channelId == WATCHLIST_ID })
      }
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

  // Each chart loader keeps its row process-wide only when nothing was deferred by the budget while
  // building it — a partial row is rebuilt on the next load, when more of its titles are known.

  private suspend fun loadTrendingSection(budget: TitleResolver.Budget): HomeSection? {
    cachedTrending?.let { return HomeSection("I titoli del momento", TRENDING_ID, it) }
    val channel = ChannelRegistry.all.firstOrNull() ?: return null
    val raw = runCatching { NetflixTop10.fetchItaly() }.getOrDefault(emptyList()).map { RawChartEntry(it.rank, it.title, it.isSeries) }
    val deferredBefore = budget.deferred
    val section = resolveChartSection("I titoli del momento", TRENDING_ID, raw, channel, budget) ?: return null
    if (budget.deferred == deferredBefore) cachedTrending = section.items
    return section
  }

  private suspend fun loadJustWatchSection(budget: TitleResolver.Budget): HomeSection? {
    cachedJustWatch?.let { return HomeSection("Popolari su tutte le piattaforme", JUSTWATCH_ID, it) }
    val channel = ChannelRegistry.all.firstOrNull() ?: return null
    val raw = runCatching { JustWatchTop10.fetchItaly() }.getOrDefault(emptyList()).map { RawChartEntry(it.rank, it.title, it.isSeries) }
    val deferredBefore = budget.deferred
    val section = resolveChartSection("Popolari su tutte le piattaforme", JUSTWATCH_ID, raw, channel, budget) ?: return null
    if (budget.deferred == deferredBefore) cachedJustWatch = section.items
    return section
  }

  /** "Film stasera in TV": tonight's movie guide across Italian digital-terrestrial channels —
   *  see [NowOnItalianTV]. Keeps the guide's own broadcast-time order instead of re-ranking (it's
   *  always movies, so there's no series to interleave), and each resolved card's `quality` badge
   *  (normally a rating) becomes "21:15 · La7Cinema" — this row is about WHEN, not the score. */
  private suspend fun loadNowOnTvSection(budget: TitleResolver.Budget): HomeSection? {
    cachedNowOnTv?.let { return HomeSection("Film stasera in TV", NOW_ON_TV_ID, it) }
    val channel = ChannelRegistry.all.firstOrNull() ?: return null
    val raw = runCatching { NowOnItalianTV.fetchTonight() }.getOrDefault(emptyList()).take(20)
    val deferredBefore = budget.deferred
    val resolved =
      raw
        .mapNotNull { entry -> resolver.chartTitle(channel, entry.title, isSeries = false, budget)?.copy(quality = "${entry.time} · ${entry.channel}") }
        .distinctBy { it.url }
    if (resolved.isEmpty()) return null
    if (budget.deferred == deferredBefore) cachedNowOnTv = resolved
    return HomeSection("Film stasera in TV", NOW_ON_TV_ID, resolved)
  }

  /** "Prossimamente al cinema" — see [UpcomingAtCinema]: a THEATRICAL calendar, not "coming to
   *  this app's catalog" (the source site has no such concept). Most entries won't resolve yet;
   *  the ones that do tend to be re-releases already in the catalog. Same graceful-empty pattern
   *  as the other signal rows — omitted entirely on a night nothing resolves. Capped at 15: the
   *  calendar runs long, and each title past what's remembered is a search. */
  private suspend fun loadUpcomingSection(budget: TitleResolver.Budget): HomeSection? {
    cachedUpcoming?.let { return HomeSection("Prossimamente al cinema", UPCOMING_ID, it) }
    val channel = ChannelRegistry.all.firstOrNull() ?: return null
    val raw = runCatching { UpcomingAtCinema.fetchUpcoming() }.getOrDefault(emptyList()).take(15)
    val deferredBefore = budget.deferred
    val resolved =
      raw
        .mapNotNull { entry -> resolver.chartTitle(channel, entry.title, isSeries = false, budget)?.copy(quality = compactItalianDate(entry.date)) }
        .distinctBy { it.url }
    if (resolved.isEmpty()) return null
    if (budget.deferred == deferredBefore) cachedUpcoming = resolved
    return HomeSection("Prossimamente al cinema", UPCOMING_ID, resolved)
  }

  private data class RawChartEntry(val rank: Int, val title: String, val isSeries: Boolean)

  /** Movies and series interleaved into one ranking (top movie, top series, #2 movie, …), then
   *  resolved one at a time until ten are found — the row shows ten, and every entry not searched
   *  is a request the site never sees. */
  private suspend fun resolveChartSection(
    title: String,
    sectionId: String,
    raw: List<RawChartEntry>,
    channel: Channel,
    budget: TitleResolver.Budget,
  ): HomeSection? {
    if (raw.isEmpty()) return null
    val movies = raw.filter { !it.isSeries }.sortedBy { it.rank }
    val series = raw.filter { it.isSeries }.sortedBy { it.rank }
    val interleaved = movies.zip(series).flatMap { (m, s) -> listOf(m, s) } + movies.drop(series.size) + series.drop(movies.size)
    val resolved = mutableListOf<StreamItem>()
    for (entry in interleaved) {
      if (resolved.size == 10) break
      val hit = resolver.chartTitle(channel, entry.title, entry.isSeries, budget) ?: continue
      if (resolved.none { it.url == hit.url }) resolved += hit
    }
    if (resolved.isEmpty()) return null
    return HomeSection(title, sectionId, resolved.mapIndexed { index, item -> item.copy(rank = index + 1) })
  }

  private companion object {
    @Volatile private var cachedTrending: List<StreamItem>? = null
    @Volatile private var cachedJustWatch: List<StreamItem>? = null
    @Volatile private var cachedNowOnTv: List<StreamItem>? = null
    @Volatile private var cachedUpcoming: List<StreamItem>? = null
  }

  fun load() {
    _uiState.value = HomeUiState.Loading
    viewModelScope.launch(Dispatchers.IO) {
      if (ChannelRegistry.all.isEmpty()) {
        _uiState.value = HomeUiState.Error("Nessun canale configurato")
        return@launch
      }
      val channelSections =
        coroutineScope {
          ChannelRegistry.all
            .map { channel -> async { runCatching { channel.home() }.getOrElse { emptyList() } } }
            .awaitAll()
            .flatten()
        }
      if (channelSections.isEmpty()) {
        _uiState.value = HomeUiState.Error("Nessun canale raggiungibile al momento")
        return@launch
      }
      val heroCandidates =
        channelSections.firstOrNull()
          ?.items
          ?.filter { it.backdrop != null && (it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES) }
          ?.take(6)
          .orEmpty()
      _uiState.value = HomeUiState.Success(heroCandidates, personalSections() + channelSections)

      // The Home is on screen from here on, and everything below only fills it in — so it runs in
      // SiteTraffic's background lane, single file and paced, instead of the ~100-request burst at
      // launch that got every address this app used banned. The hero on screen first, then the
      // chart rows (mostly remembered titles after the first launch), the other heroes, genre rows.
      withContext(SiteTraffic.Background) {
        heroCandidates.firstOrNull()?.let { enrichHero(0, it) }
        val budget = TitleResolver.Budget(CHART_SEARCH_BUDGET)
        loadTrendingSection(budget)?.let { placeChartRow(it) }
        loadJustWatchSection(budget)?.let { placeChartRow(it) }
        loadNowOnTvSection(budget)?.let { placeChartRow(it) }
        loadUpcomingSection(budget)?.let { placeChartRow(it) }
        heroCandidates.drop(1).forEachIndexed { i, hero -> enrichHero(i + 1, hero) }
        appendGenreRows()
      }
    }
  }

  /** Chart rows land after the Home is up: slot each under the personal rows in [CHART_ORDER] so
   *  the page doesn't reshuffle as they arrive. */
  private fun placeChartRow(row: HomeSection) =
    _uiState.update { state ->
      if (state !is HomeUiState.Success) return@update state
      val isPersonal = { s: HomeSection -> s.channelId == CONTINUE_WATCHING_ID || s.channelId == WATCHLIST_ID }
      val charts =
        (state.sections.filter { it.channelId in CHART_ORDER && it.channelId != row.channelId } + row)
          .sortedBy { CHART_ORDER.indexOf(it.channelId) }
      val rest = state.sections.filterNot { isPersonal(it) || it.channelId in CHART_ORDER }
      state.copy(sections = state.sections.filter(isPersonal) + charts + rest)
    }

  private suspend fun enrichHero(index: Int, candidate: StreamItem) {
    val channel = ChannelRegistry.byId(candidate.channelId) ?: return
    val enriched = runCatching { channel.detail(candidate) }.getOrNull() ?: return
    _uiState.update { state ->
      if (state is HomeUiState.Success && index < state.heroes.size) {
        state.copy(heroes = state.heroes.toMutableList().also { it[index] = enriched })
      } else {
        state
      }
    }
  }

  /** After the base Home is on screen, fill it out with a "best of" row per genre — one page
   *  each, fetched one at a time (the caller runs this in the background lane) and appended as each
   *  lands, so the screen keeps growing. */
  private suspend fun appendGenreRows() {
    val channel = ChannelRegistry.all.firstOrNull() ?: return
    val root = channel.catalogRoot() ?: return
    val genreList = runCatching { channel.genres() }.getOrDefault(emptyList()).take(10)
    for (genre in genreList) {
      val items =
        runCatching { channel.list(root.copy(extra = "genre=${genre.id}"), 1) }.getOrNull()?.items.orEmpty().filter { it.thumbnail != null }
      if (items.size < 5) continue
      val id = "genre-${genre.id}"
      _uiState.update { state ->
        if (state !is HomeUiState.Success || state.sections.any { it.channelId == id }) state
        else state.copy(sections = state.sections + HomeSection(genre.name, id, items))
      }
    }
  }
}
