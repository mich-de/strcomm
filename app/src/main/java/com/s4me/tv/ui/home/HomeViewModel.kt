package com.s4me.tv.ui.home

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
import kotlinx.coroutines.flow.update
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

class HomeViewModel(application: Application) : AndroidViewModel(application) {
  private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
  val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

  /** "Sfoglia" target for the header button — a fixed entry point, no fetch, so it's ready at once. */
  val catalogRoot: StreamItem? = ChannelRegistry.all.firstOrNull()?.catalogRoot()

  /** Genre chips for the shortcuts row; empty until the (cached, cheap) lookup resolves, and the
   *  row simply doesn't render while empty. */
  private val _genres = MutableStateFlow<List<GenreOption>>(emptyList())
  val genres: StateFlow<List<GenreOption>> = _genres.asStateFlow()

  /** Human-readable trace of the first Home load, shown line-by-line on the branded loading screen
   *  — so a slow load (a sluggish box, a laggy source site) reads as "it's working through the
   *  Netflix chart" instead of a frozen wordmark. Appended from several coroutines at once, so the
   *  order is completion order, which is exactly what "what's loading now" should show. */
  private val _progress = MutableStateFlow<List<String>>(emptyList())
  val progress: StateFlow<List<String>> = _progress.asStateFlow()

  private fun log(line: String) = _progress.update { it + line }

  private val progressStore = WatchProgressStore(application)
  private val watchlistStore = WatchlistStore(application)

  init {
    load()
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.all.firstOrNull() ?: return@launch
      _genres.value = runCatching { channel.genres() }.getOrDefault(emptyList())
    }
  }

  /**
   * Re-reads the local stores and swaps the personal rows ("Continua a guardare", "La mia lista")
   * in place, leaving the (network-derived) catalog rows untouched. Called every time Home comes
   * back to the foreground, so a title just watched or just added to the list shows up — with a
   * fresh progress bar — without restarting the app.
   */
  fun refreshPersonalRows() {
    viewModelScope.launch(Dispatchers.IO) {
      val current = _uiState.value as? HomeUiState.Success ?: return@launch
      val catalogSections = current.sections.filterNot { it.channelId == CONTINUE_WATCHING_ID || it.channelId == WATCHLIST_ID }
      _uiState.value = current.copy(sections = personalSections() + catalogSections)
    }
  }

  /** The user's own rows, rebuilt from the local stores: resume row first, then the watchlist. */
  private fun personalSections(): List<HomeSection> {
    val continueItems = progressStore.inProgress().take(10).map { it.item.copy(progress = it.fraction) }
    val watchlistItems = watchlistStore.all().take(15)
    return listOfNotNull(
      HomeSection("Continua a guardare", CONTINUE_WATCHING_ID, continueItems).takeIf { continueItems.isNotEmpty() },
      HomeSection("La mia lista", WATCHLIST_ID, watchlistItems).takeIf { watchlistItems.isNotEmpty() },
    )
  }

  /** "I titoli del momento": Netflix's own public Italy Top 10, used purely as a ranking signal —
   *  every resolved item still plays through this app's own channel, same as the curated Oscar row
   *  in BrowseViewModel. Cached process-wide (the chart doesn't change intra-session) so a retry
   *  doesn't repeat ~1.5MB of Netflix fetching plus the resolve-searches. */
  private suspend fun loadTrendingSection(): HomeSection? {
    cachedTrending?.let {
      log("Netflix Top 10 · da cache (${it.size})")
      return HomeSection("I titoli del momento", TRENDING_ID, it)
    }
    val channel = ChannelRegistry.all.firstOrNull() ?: return null
    log("Netflix Top 10 Italia · scarico la classifica…")
    val raw = runCatching { NetflixTop10.fetchItaly() }.getOrDefault(emptyList()).map { RawChartEntry(it.rank, it.title, it.isSeries) }
    log("Netflix Top 10 · ${raw.size} voci, cerco i titoli nel catalogo…")
    val section = resolveChartSection("I titoli del momento", TRENDING_ID, raw, channel, tag = "Netflix")
    if (section == null) {
      log("Netflix Top 10 · nessun titolo trovato, salto la riga")
      return null
    }
    cachedTrending = section.items
    log("Netflix Top 10 · pronta (${section.items.size} titoli)")
    return section
  }

  /** "Popolari su tutte le piattaforme": JustWatch's own public Streaming Charts — unlike
   *  Netflix's, a CROSS-platform popularity ranking (built from JustWatch's own users' activity
   *  across every service, not one platform's catalog), so it tends to surface a different mix
   *  than the Netflix row above. Same ranking-signal-only role, same process-wide cache. */
  private suspend fun loadJustWatchSection(): HomeSection? {
    cachedJustWatch?.let {
      log("JustWatch · da cache (${it.size})")
      return HomeSection("Popolari su tutte le piattaforme", JUSTWATCH_ID, it)
    }
    val channel = ChannelRegistry.all.firstOrNull() ?: return null
    log("JustWatch Streaming Charts · scarico la classifica…")
    val raw = runCatching { JustWatchTop10.fetchItaly() }.getOrDefault(emptyList()).map { RawChartEntry(it.rank, it.title, it.isSeries) }
    log("JustWatch · ${raw.size} voci, cerco i titoli nel catalogo…")
    val section = resolveChartSection("Popolari su tutte le piattaforme", JUSTWATCH_ID, raw, channel, tag = "JustWatch")
    if (section == null) {
      log("JustWatch · nessun titolo trovato, salto la riga")
      return null
    }
    cachedJustWatch = section.items
    log("JustWatch · pronta (${section.items.size} titoli)")
    return section
  }

  private data class RawChartEntry(val rank: Int, val title: String, val isSeries: Boolean)

  /** Shared by both external chart rows above. Movies and series are each their OWN chart on the
   *  source site (both numbered 1-10) — showing them back to back restarted the count twice ("i
   *  titoli devono essere da 1 a 10 in ordine"), so they're interleaved (top movie, top series, #2
   *  movie, #2 series, …) into ONE combined ranking before resolving, and the displayed rank 1-10
   *  is this row's own final position, not the source's per-category number — an unresolved title
   *  (dropped rather than shown under the wrong art) would otherwise leave a gap or a second "#1".
   *  `distinctBy(url)` matters for the same reason it did for Netflix alone: a chart can list the
   *  same series twice under different seasons, which resolves to the one title this site actually
   *  has — two rows sharing a key crashed the row's LazyRow outright the instant it scrolled into
   *  view ("Key ... was already used"). */
  private suspend fun resolveChartSection(
    title: String,
    sectionId: String,
    raw: List<RawChartEntry>,
    channel: Channel,
    tag: String,
  ): HomeSection? {
    if (raw.isEmpty()) return null
    val movies = raw.filter { !it.isSeries }.sortedBy { it.rank }
    val series = raw.filter { it.isSeries }.sortedBy { it.rank }
    val interleaved = movies.zip(series).flatMap { (m, s) -> listOf(m, s) } + movies.drop(series.size) + series.drop(movies.size)
    val resolved =
      coroutineScope { interleaved.map { entry -> async { resolveChartEntry(channel, entry, tag) } }.awaitAll() }
        .filterNotNull()
        .distinctBy { it.url }
        .take(10)
        .mapIndexed { index, item -> item.copy(rank = index + 1) }
    if (resolved.isEmpty()) return null
    return HomeSection(title, sectionId, resolved)
  }

  /** Exact-title match first, then a loose contains either direction (a handful of titles differ
   *  slightly between the chart source's name and the site's own listing) — no blind "first
   *  result" fallback: the site's search is fuzzy full-text and degrades into unrelated titles
   *  past the first few hits (verified earlier this session against real cast-name searches), so
   *  an unresolved entry is dropped rather than risk showing the wrong title under a rank badge. */
  private suspend fun resolveChartEntry(channel: Channel, entry: RawChartEntry, tag: String): StreamItem? =
    runCatching {
      val wantedKind = if (entry.isSeries) ItemKind.SERIES else ItemKind.MOVIE
      val results = channel.search(entry.title).filter { it.kind == wantedKind }
      val hit =
        results.firstOrNull { it.title.equals(entry.title, ignoreCase = true) }
          ?: results.firstOrNull { it.title.contains(entry.title, ignoreCase = true) || entry.title.contains(it.title, ignoreCase = true) }
      log("$tag · ${entry.title} → ${hit?.title ?: "non trovato"}")
      hit
    }.getOrNull()

  private companion object {
    @Volatile private var cachedTrending: List<StreamItem>? = null
    @Volatile private var cachedJustWatch: List<StreamItem>? = null
  }

  fun load() {
    _uiState.value = HomeUiState.Loading
    _progress.value = emptyList()
    viewModelScope.launch(Dispatchers.IO) {
      if (ChannelRegistry.all.isEmpty()) {
        _uiState.value = HomeUiState.Error("Nessun canale configurato")
        return@launch
      }
      log("Sorgenti · ${ChannelRegistry.all.joinToString(", ") { it.displayName }}")
      // The two external chart rows (see loadTrendingSection/loadJustWatchSection) are separate
      // network round-trips with nothing to do with the catalog rows below — run all three
      // alongside each other, not sequentially, so they don't add to Home's load time.
      val (channelSections, trendingSection, justWatchSection) =
        coroutineScope {
          val channelSectionsDeferred =
            async {
              ChannelRegistry.all
                .map { channel ->
                  async {
                    log("${channel.displayName} · carico le righe…")
                    val rows = runCatching { channel.home() }.getOrElse { emptyList() }
                    val names = rows.take(6).joinToString(", ") { it.title }
                    log("${channel.displayName} · ${rows.size} righe" + if (names.isNotEmpty()) " ($names)" else "")
                    rows
                  }
                }
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

      // Personal rows lead: "Continua a guardare" (last 10, with resume bars) then "La mia lista",
      // then the two external-chart highlight rows (when they resolved), then the site's own rows.
      val personal = personalSections()
      if (personal.isNotEmpty()) log("Le tue righe · ${personal.joinToString(", ") { it.title }}")
      val sections = personal + listOfNotNull(trendingSection, justWatchSection) + channelSections
      log("Home pronta · ${sections.size} righe, ${sections.sumOf { it.items.size }} titoli")
      // Hero carousel: the first few playable picks with a backdrop from the actual catalog rows
      // (not "Continua a guardare"), Netflix-billboard style. Shown right away with whatever fields
      // they already have (poster, title, year) instead of making Home wait on detail() round-trips
      // for plots — each enrichment lands as a follow-up state update, swapped into the list in place.
      val heroCandidates =
        channelSections.firstOrNull()
          ?.items
          ?.filter { it.backdrop != null && (it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES) }
          ?.take(5)
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
    }
  }
}
