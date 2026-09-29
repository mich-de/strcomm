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
  private val resolver = TitleResolver.get(application)

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
      val personal = personalSections()
      // Atomic: hero enrichment may be landing from the load at the same moment.
      _uiState.update { current ->
        if (current !is HomeUiState.Success) current
        else current.copy(sections = personal + current.sections.filterNot { it.channelId == CONTINUE_WATCHING_ID || it.channelId == WATCHLIST_ID })
      }
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
   *  doesn't repeat ~1.5MB of Netflix fetching — but only when complete: a row the search budget cut
   *  short is rebuilt next load, when more of its titles are remembered ([TitleResolver]). */
  private suspend fun loadTrendingSection(budget: TitleResolver.Budget): HomeSection? {
    cachedTrending?.let {
      log("Netflix Top 10 · da cache (${it.size})")
      return HomeSection("I titoli del momento", TRENDING_ID, it)
    }
    val channel = ChannelRegistry.all.firstOrNull() ?: return null
    log("Netflix Top 10 Italia · scarico la classifica…")
    val raw = runCatching { NetflixTop10.fetchItaly() }.getOrDefault(emptyList()).map { RawChartEntry(it.rank, it.title, it.isSeries) }
    log("Netflix Top 10 · ${raw.size} voci, cerco i titoli nel catalogo…")
    val deferredBefore = budget.deferred
    val section = resolveChartSection("I titoli del momento", TRENDING_ID, raw, channel, tag = "Netflix", budget)
    if (section == null) {
      log("Netflix Top 10 · nessun titolo trovato, salto la riga")
      return null
    }
    if (budget.deferred == deferredBefore) cachedTrending = section.items
    log("Netflix Top 10 · pronta (${section.items.size} titoli)")
    return section
  }

  /** "Popolari su tutte le piattaforme": JustWatch's own public Streaming Charts — unlike
   *  Netflix's, a CROSS-platform popularity ranking (built from JustWatch's own users' activity
   *  across every service, not one platform's catalog), so it tends to surface a different mix
   *  than the Netflix row above. Same ranking-signal-only role, same process-wide cache. */
  private suspend fun loadJustWatchSection(budget: TitleResolver.Budget): HomeSection? {
    cachedJustWatch?.let {
      log("JustWatch · da cache (${it.size})")
      return HomeSection("Popolari su tutte le piattaforme", JUSTWATCH_ID, it)
    }
    val channel = ChannelRegistry.all.firstOrNull() ?: return null
    log("JustWatch Streaming Charts · scarico la classifica…")
    val raw = runCatching { JustWatchTop10.fetchItaly() }.getOrDefault(emptyList()).map { RawChartEntry(it.rank, it.title, it.isSeries) }
    log("JustWatch · ${raw.size} voci, cerco i titoli nel catalogo…")
    val deferredBefore = budget.deferred
    val section = resolveChartSection("Popolari su tutte le piattaforme", JUSTWATCH_ID, raw, channel, tag = "JustWatch", budget)
    if (section == null) {
      log("JustWatch · nessun titolo trovato, salto la riga")
      return null
    }
    if (budget.deferred == deferredBefore) cachedJustWatch = section.items
    log("JustWatch · pronta (${section.items.size} titoli)")
    return section
  }

  /** "Film stasera in TV": tonight's movie guide across Italian digital-terrestrial channels —
   *  see [NowOnItalianTV]. A different kind of ranking signal than the two charts above (a TV
   *  guide's own broadcast-time order, not a popularity rank, and always movies), so it skips
   *  [resolveChartSection]'s movie/series interleaving and goes straight to [resolveChartEntry]
   *  per title. Each resolved card's `quality` badge (normally a rating) becomes "21:15 · La7Cinema"
   *  instead — this row exists to say WHEN something's on, which matters more here than its score. */
  private suspend fun loadNowOnTvSection(budget: TitleResolver.Budget): HomeSection? {
    cachedNowOnTv?.let {
      log("Film stasera in TV · da cache (${it.size})")
      return HomeSection("Film stasera in TV", NOW_ON_TV_ID, it)
    }
    val channel = ChannelRegistry.all.firstOrNull() ?: return null
    log("Film stasera in TV · scarico la guida…")
    val raw = runCatching { NowOnItalianTV.fetchTonight() }.getOrDefault(emptyList()).take(20)
    log("Film stasera in TV · ${raw.size} voci, cerco i titoli nel catalogo…")
    val deferredBefore = budget.deferred
    val resolved =
      raw
        .mapNotNull { entry ->
          resolveChartEntry(channel, RawChartEntry(0, entry.title, isSeries = false), tag = "TV", budget)
            ?.copy(quality = "${entry.time} · ${entry.channel}")
        }
        .distinctBy { it.url }
    if (resolved.isEmpty()) {
      log("Film stasera in TV · nessun titolo trovato, salto la riga")
      return null
    }
    if (budget.deferred == deferredBefore) cachedNowOnTv = resolved
    log("Film stasera in TV · pronta (${resolved.size} titoli)")
    return HomeSection("Film stasera in TV", NOW_ON_TV_ID, resolved)
  }

  /** "Prossimamente al cinema" — see [UpcomingAtCinema]: a THEATRICAL calendar, not "coming to
   *  this app's catalog" (the source site has no such concept — its own home() sliders are only
   *  trending/latest/top10). Most entries won't resolve yet; what does tends to be re-releases
   *  already in the catalog. Same graceful-empty pattern as the row above. Capped at 15: the
   *  calendar runs long, and every title past what's remembered is a search. */
  private suspend fun loadUpcomingSection(budget: TitleResolver.Budget): HomeSection? {
    cachedUpcoming?.let {
      log("Prossimamente al cinema · da cache (${it.size})")
      return HomeSection("Prossimamente al cinema", UPCOMING_ID, it)
    }
    val channel = ChannelRegistry.all.firstOrNull() ?: return null
    log("Prossimamente al cinema · scarico il calendario…")
    val raw = runCatching { UpcomingAtCinema.fetchUpcoming() }.getOrDefault(emptyList()).take(15)
    log("Prossimamente al cinema · ${raw.size} voci, cerco i titoli nel catalogo…")
    val deferredBefore = budget.deferred
    val resolved =
      raw
        .mapNotNull { entry ->
          resolveChartEntry(channel, RawChartEntry(0, entry.title, isSeries = false), tag = "Cinema", budget)
            ?.copy(quality = compactItalianDate(entry.date))
        }
        .distinctBy { it.url }
    if (resolved.isEmpty()) {
      log("Prossimamente al cinema · nessun titolo trovato, salto la riga")
      return null
    }
    if (budget.deferred == deferredBefore) cachedUpcoming = resolved
    log("Prossimamente al cinema · pronta (${resolved.size} titoli)")
    return HomeSection("Prossimamente al cinema", UPCOMING_ID, resolved)
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
   *  view ("Key ... was already used"). Resolved one at a time until ten are found — the row shows
   *  ten, and every entry not searched is a request the site never sees (see SiteTraffic). */
  private suspend fun resolveChartSection(
    title: String,
    sectionId: String,
    raw: List<RawChartEntry>,
    channel: Channel,
    tag: String,
    budget: TitleResolver.Budget,
  ): HomeSection? {
    if (raw.isEmpty()) return null
    val movies = raw.filter { !it.isSeries }.sortedBy { it.rank }
    val series = raw.filter { it.isSeries }.sortedBy { it.rank }
    val interleaved = movies.zip(series).flatMap { (m, s) -> listOf(m, s) } + movies.drop(series.size) + series.drop(movies.size)
    val resolved = mutableListOf<StreamItem>()
    for (entry in interleaved) {
      if (resolved.size == 10) break
      val hit = resolveChartEntry(channel, entry, tag, budget) ?: continue
      if (resolved.none { it.url == hit.url }) resolved += hit
    }
    if (resolved.isEmpty()) return null
    return HomeSection(title, sectionId, resolved.mapIndexed { index, item -> item.copy(rank = index + 1) })
  }

  /** One chart entry through [TitleResolver.chartTitle] (exact title first, then a loose contains
   *  either way, never a blind first result — see there), remembered across launches. "—" in the
   *  splash log covers both "not on the site" and "left for a later load by the budget". */
  private suspend fun resolveChartEntry(channel: Channel, entry: RawChartEntry, tag: String, budget: TitleResolver.Budget): StreamItem? {
    val hit = resolver.chartTitle(channel, entry.title, entry.isSeries, budget)
    log("$tag · ${entry.title} → ${hit?.title ?: "—"}")
    return hit
  }

  private companion object {
    @Volatile private var cachedTrending: List<StreamItem>? = null
    @Volatile private var cachedJustWatch: List<StreamItem>? = null
    @Volatile private var cachedNowOnTv: List<StreamItem>? = null
    @Volatile private var cachedUpcoming: List<StreamItem>? = null
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
      // The site's own rows and the chart rows load side by side, but the charts' title searches go
      // through SiteTraffic's background lane — single file, paced, remembered across launches
      // (TitleResolver) and capped per load — instead of ~60 searches fired at once, the burst that
      // got every address this app used banned ("si apre e poi mi banna nuovamente").
      var channelSections: List<HomeSection> = emptyList()
      var chartSections: List<HomeSection> = emptyList()
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
        // One after another, in row order, so the shared search budget goes to the top rows first.
        val chartsDeferred =
          async(SiteTraffic.Background) {
            val budget = TitleResolver.Budget(CHART_SEARCH_BUDGET)
            listOfNotNull(loadTrendingSection(budget), loadJustWatchSection(budget), loadNowOnTvSection(budget), loadUpcomingSection(budget))
          }
        channelSections = channelSectionsDeferred.await()
        chartSections = chartsDeferred.await()
      }
      if (channelSections.isEmpty()) {
        _uiState.value = HomeUiState.Error("Nessun canale raggiungibile al momento")
        return@launch
      }

      // Personal rows lead: "Continua a guardare" (last 10, with resume bars) then "La mia lista",
      // then the external-chart highlight rows (when they resolved), then the site's own rows.
      val personal = personalSections()
      if (personal.isNotEmpty()) log("Le tue righe · ${personal.joinToString(", ") { it.title }}")
      val sections = personal + chartSections + channelSections
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

      // Enrichment is fill-in work nobody waits on: background lane, one detail page at a time.
      withContext(SiteTraffic.Background) {
        heroCandidates.forEachIndexed { index, candidate ->
          val channel = ChannelRegistry.byId(candidate.channelId) ?: return@forEachIndexed
          val enriched = runCatching { channel.detail(candidate) }.getOrNull() ?: return@forEachIndexed
          _uiState.update { current ->
            if (current is HomeUiState.Success && index < current.heroes.size) {
              current.copy(heroes = current.heroes.toMutableList().also { it[index] = enriched })
            } else {
              current
            }
          }
        }
      }
    }
  }
}
