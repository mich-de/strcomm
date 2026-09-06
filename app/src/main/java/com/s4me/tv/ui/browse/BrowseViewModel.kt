package com.s4me.tv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem
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

data class BrowseFilter(
  val type: String? = null,
  val genreId: Int? = null,
  val year: Int? = null,
  /** null = most popular (site default), "release" = by release date, "score" = highest rated. */
  val sort: String? = null,
  /** "oscar" = curated Best Picture winners, "oscar-nominees" = winners + nominees (2015+),
   *  either replacing the archive listing. */
  val award: String? = null,
) {
  /** Query-string-shaped encoding stashed in [StreamItem.extra] — see Channel.list()'s doc. */
  fun toExtra(): String? {
    val parts = buildList {
      type?.let { add("type=$it") }
      genreId?.let { add("genre=$it") }
      year?.let { add("year=$it") }
      sort?.let { add("sort=$it") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("&")
  }

  companion object {
    /** Inverse of [toExtra] — lets a caller deep-link into Browse already filtered (Home's genre
     *  shortcuts pass `genre=<id>` this way). Unknown keys are ignored. */
    fun fromExtra(extra: String?): BrowseFilter {
      if (extra.isNullOrBlank()) return BrowseFilter()
      val map =
        extra.split("&").mapNotNull { pair ->
          pair.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
      return BrowseFilter(
        type = map["type"],
        genreId = map["genre"]?.toIntOrNull(),
        year = map["year"]?.toIntOrNull(),
        sort = map["sort"],
      )
    }
  }
}

/** Academy Award Best Picture winners (film release year → the SITE's searchable title: the
 *  original title where the site keeps it, the Italian retitle where it renames — every entry was
 *  validated against the live search so the exact/contains tiers land on the right film; a bare
 *  original title left "Balla coi lupi"-style renames to the fragile year-only tier, which is how
 *  "The Secret Agent" once resolved to Mission: Impossible). The site carries no award metadata at
 *  all (verified: any `award` param is ignored), so "i premiati con gli Oscar" can only be a
 *  curated list resolved through the site's own search. One entry per year because the Academy
 *  crowns exactly one Best Picture; the winner for year N is only announced at the following
 *  March's ceremony, so the newest possible entry always trails the current year by one.
 *  Missing on the site (re-add if it appears): 2011 "The Artist". */
private val BEST_PICTURE_WINNERS = listOf(
  2025 to "Una battaglia dopo l’altra",
  2024 to "Anora",
  2023 to "Oppenheimer",
  2022 to "Everything Everywhere All at Once",
  2021 to "CODA",
  2020 to "Nomadland",
  2019 to "Parasite",
  2018 to "Green Book",
  2017 to "The Shape of Water",
  2016 to "Moonlight",
  2015 to "Spotlight",
  2014 to "Birdman",
  2013 to "12 anni schiavo",
  2012 to "Argo",
  2010 to "Il discorso del re",
  2009 to "The Hurt Locker",
  2008 to "The Millionaire",
  2007 to "Non è un paese per vecchi",
  2006 to "The Departed",
  2005 to "Crash - Contatto fisico",
  2004 to "Million Dollar Baby",
  2003 to "Il Signore degli Anelli - Il ritorno del re",
  2002 to "Chicago",
  2001 to "A Beautiful Mind",
  2000 to "Gladiator",
  1999 to "American Beauty",
  1998 to "Shakespeare in Love",
  1997 to "Titanic",
  1996 to "Il paziente inglese",
  1995 to "Braveheart",
  1994 to "Forrest Gump",
  1993 to "La Lista di Schindler",
  1992 to "Gli spietati",
  1991 to "Il silenzio degli innocenti",
  1990 to "Balla coi lupi",
)

/** Best Picture NOMINEES per film year (the winner is NOT repeated here — it gets prepended from
 *  [BEST_PICTURE_WINNERS]). Only the expanded-field era from 2015 on: earlier years add ~5 titles
 *  each for diminishing interest, and every title costs one site search on first open. */
private val BEST_PICTURE_NOMINEES: Map<Int, List<String>> = mapOf(
  // Titles follow the same site-validated convention as BEST_PICTURE_WINNERS.
  // Missing on the site (re-add if it appears): 2018 "Roma" (Netflix-only).
  2025 to listOf("Bugonia", "F1", "Frankenstein", "Hamnet", "Marty Supreme", "L'agente segreto", "Sentimental Value", "I peccatori", "Train Dreams"),
  2024 to listOf("The Brutalist", "A Complete Unknown", "Conclave", "Dune - Parte due", "Emilia Pérez", "Io sono ancora qui", "I ragazzi della Nickel", "The Substance", "Wicked"),
  2023 to listOf("American Fiction", "Anatomia di una caduta", "Barbie", "The Holdovers", "Killers of the Flower Moon", "Maestro", "Past Lives", "Povere creature!", "La zona d'interesse"),
  2022 to listOf("Niente di nuovo sul fronte occidentale", "Avatar - La via dell'acqua", "Gli spiriti dell’isola", "Elvis", "The Fabelmans", "Tár", "Top Gun: Maverick", "Triangle of Sadness", "Women Talking"),
  2021 to listOf("Belfast", "Don't Look Up", "Drive My Car", "Dune", "King Richard", "Licorice Pizza", "Nightmare Alley", "Il potere del cane", "West Side Story"),
  2020 to listOf("The Father", "Judas and the Black Messiah", "Mank", "Minari", "Una donna promettente", "Sound of Metal", "Il processo ai Chicago 7"),
  2019 to listOf("Le Mans '66 - La grande sfida", "The Irishman", "Jojo Rabbit", "Joker", "Piccole donne", "Storia di un matrimonio", "1917", "C'era una volta a… Hollywood"),
  2018 to listOf("Black Panther", "BlacKkKlansman", "Bohemian Rhapsody", "La favorita", "A Star Is Born", "Vice"),
  2017 to listOf("Chiamami col tuo nome", "L'ora più buia", "Dunkirk", "Get Out", "Lady Bird", "Il filo nascosto", "The Post", "Tre manifesti a Ebbing, Missouri"),
  2016 to listOf("Arrival", "Barriere", "Hacksaw Ridge", "Hell or High Water", "Il diritto di contare", "La La Land", "Lion", "Manchester by the Sea"),
  2015 to listOf("La grande scommessa", "Il ponte delle spie", "Brooklyn", "Mad Max: Fury Road", "The Martian", "Revenant - Redivivo", "Room"),
)

private data class OscarEntry(val year: Int, val title: String, val winner: Boolean = false)

private val OSCAR_WINNER_ENTRIES: List<OscarEntry> =
  BEST_PICTURE_WINNERS.map { (year, title) -> OscarEntry(year, title, winner = true) }

/** Newest year first; within a year the winner leads, then the nominees in list order. */
private val OSCAR_NOMINEE_ENTRIES: List<OscarEntry> =
  BEST_PICTURE_NOMINEES.keys.sortedDescending().flatMap { year ->
    val winner = BEST_PICTURE_WINNERS.firstOrNull { it.first == year }?.second
    listOfNotNull(winner?.let { OscarEntry(year, it, winner = true) }) +
      BEST_PICTURE_NOMINEES.getValue(year).map { OscarEntry(year, it) }
  }

class BrowseViewModel(private val root: StreamItem) : ViewModel() {
  private val _uiState = MutableStateFlow<BrowseUiState>(BrowseUiState.Loading)
  val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

  private val _genreOptions = MutableStateFlow<List<GenreOption>>(emptyList())
  val genreOptions: StateFlow<List<GenreOption>> = _genreOptions.asStateFlow()

  // Seeded from root.extra so a deep link into Browse (Home's genre shortcuts) opens already
  // filtered, and the filter chips reflect it. Plain roots (a series' seasons) carry no extra and
  // start blank as before.
  private val _filter = MutableStateFlow(BrowseFilter.fromExtra(root.extra))
  val filter: StateFlow<BrowseFilter> = _filter.asStateFlow()

  // A series landing here (its "detail" IS this seasons screen, there's no separate DetailScreen
  // for it) arrives from Home/Search with just a poster/title — no plot/genres/cast, which were
  // never fetched. Seasons don't need this: listSeasons() already lifts each season's own plot
  // from the same lightweight data listSeasons() fetches, no extra call needed (see there).
  private val _seriesInfo = MutableStateFlow<StreamItem?>(null)
  val seriesInfo: StateFlow<StreamItem?> = _seriesInfo.asStateFlow()

  // A season's own synopsis (root.plot) comes from the source site in English for titles it never
  // localised; this is the Italian one, resolved async, and the screen prefers it when present.
  private val _seasonPlotIt = MutableStateFlow<String?>(null)
  val seasonPlotIt: StateFlow<String?> = _seasonPlotIt.asStateFlow()

  private var nextPage = 1
  private var loadedItems: List<StreamItem> = emptyList()

  /** Bumped on every loadFirstPage; in-flight loads compare against it before emitting, so a slow
   *  earlier load (the nominees list emits progressively for many seconds) can never overwrite the
   *  results of a filter the user switched to meanwhile. */
  @Volatile private var loadGeneration = 0

  init {
    loadFirstPage()
    // Only the actual "Sfoglia" catalog root filters by genre — fetching genre options while
    // browsing a series' seasons would just be a wasted request on an already-loaded-enough screen.
    if (root.kind == ItemKind.LIST) {
      viewModelScope.launch(Dispatchers.IO) {
        val channel = ChannelRegistry.byId(root.channelId) ?: return@launch
        _genreOptions.value = runCatching { channel.genres() }.getOrDefault(emptyList())
      }
    }
    if (root.kind == ItemKind.SERIES) {
      viewModelScope.launch(Dispatchers.IO) {
        val channel = ChannelRegistry.byId(root.channelId) ?: return@launch
        _seriesInfo.value = runCatching { channel.detail(root) }.getOrNull()
      }
    }
    if (root.kind == ItemKind.SEASON) {
      viewModelScope.launch(Dispatchers.IO) {
        val channel = ChannelRegistry.byId(root.channelId) ?: return@launch
        _seasonPlotIt.value = runCatching { channel.seasonOverview(root) }.getOrNull()
      }
    }
  }

  fun setFilter(filter: BrowseFilter) {
    _filter.value = filter
    loadFirstPage()
  }

  fun loadFirstPage() {
    _uiState.value = BrowseUiState.Loading
    nextPage = 1
    val generation = ++loadGeneration
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.byId(root.channelId)
      if (channel == null) {
        _uiState.value = BrowseUiState.Error("Canale non trovato")
        return@launch
      }
      when (_filter.value.award) {
        "oscar" -> {
          loadCuratedOscars(channel, OSCAR_WINNER_ENTRIES, markWinners = false, cacheKey = "winners", generation = generation)
          return@launch
        }
        "oscar-nominees" -> {
          loadCuratedOscars(channel, OSCAR_NOMINEE_ENTRIES, markWinners = true, cacheKey = "nominees", generation = generation)
          return@launch
        }
      }
      val filtered = root.copy(extra = _filter.value.toExtra())
      runCatching { channel.list(filtered, nextPage) }
        .onSuccess { result ->
          if (generation != loadGeneration) return@onSuccess
          loadedItems = result.items
          nextPage++
          _uiState.value = BrowseUiState.Success(loadedItems, result.hasMore, loadingMore = false)
        }
        .onFailure { if (generation == loadGeneration) _uiState.value = BrowseUiState.Error(it.message ?: "Errore di caricamento") }
    }
  }

  /** Resolves a curated Oscar list through the site's search, emitting PROGRESSIVELY as each
   *  batch lands (the nominees list is ~100 searches — one blocking wait would park the user on a
   *  spinner for the better part of a minute; this way the newest years' cards appear within a
   *  couple of seconds and the grid keeps growing). Results are cached process-wide per list, so
   *  reopening is instant. The year filter still applies; type/genre/sort don't. */
  private suspend fun loadCuratedOscars(
    channel: com.s4me.tv.engine.Channel,
    entries: List<OscarEntry>,
    markWinners: Boolean,
    cacheKey: String,
    generation: Int,
  ) {
    curatedOscarCache[cacheKey]?.let {
      emitOscars(it, generation, stillLoading = false)
      return
    }
    val resolved = mutableListOf<StreamItem>()
    val chunks = entries.chunked(8)
    coroutineScope {
      chunks.forEachIndexed { index, chunk ->
        if (generation != loadGeneration) return@coroutineScope
        val batch = chunk.map { entry -> async { resolveOscarEntry(channel, entry, markWinners) } }.awaitAll().filterNotNull()
        resolved += batch
        emitOscars(resolved, generation, stillLoading = index < chunks.lastIndex)
      }
    }
    if (resolved.isNotEmpty() && generation == loadGeneration) curatedOscarCache[cacheKey] = resolved.toList()
  }

  private suspend fun resolveOscarEntry(channel: com.s4me.tv.engine.Channel, entry: OscarEntry, markWinners: Boolean): StreamItem? =
    runCatching {
      val movies = channel.search(entry.title).filter { it.kind == ItemKind.MOVIE }
      // Exact title + year beats exact title alone (the site has an exact "Frankenstein" that is
      // not 2025's), which beats year alone (searching "Anora" returned "Ancora una possibilità",
      // also a 2024 film). Italian-retitled films miss the exact tiers but the site's search
      // matches original names server-side, so the year tier lands on the right result.
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

  private fun emitOscars(items: List<StreamItem>, generation: Int, stillLoading: Boolean) {
    if (generation != loadGeneration) return
    val yearFilter = _filter.value.year
    val visible = items.distinctBy { it.url }.filter { yearFilter == null || it.year == yearFilter.toString() }
    _uiState.value = BrowseUiState.Success(visible, hasMore = false, loadingMore = stillLoading)
  }

  private companion object {
    /** Process-wide caches (one per curated list): the identities never change within a session. */
    private val curatedOscarCache = java.util.concurrent.ConcurrentHashMap<String, List<StreamItem>>()
  }

  fun loadMore() {
    val current = _uiState.value as? BrowseUiState.Success ?: return
    if (!current.hasMore || current.loadingMore) return
    _uiState.value = current.copy(loadingMore = true)
    val generation = loadGeneration
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.byId(root.channelId) ?: return@launch
      val filtered = root.copy(extra = _filter.value.toExtra())
      runCatching { channel.list(filtered, nextPage) }
        .onSuccess { result ->
          if (generation != loadGeneration) return@onSuccess
          loadedItems = loadedItems + result.items
          nextPage++
          _uiState.value = BrowseUiState.Success(loadedItems, result.hasMore, loadingMore = false)
        }
        .onFailure { if (generation == loadGeneration) _uiState.value = current.copy(loadingMore = false) }
    }
  }
}
