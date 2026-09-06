package com.s4me.tv.engine

data class GenreOption(val id: Int, val name: String)

/**
 * One content source (a single scraped site). Mirrors the addon's channels/NAME.py contract:
 * home() ~ mainlist(), list() ~ peliculas()/episodios()/series() for any non-terminal item
 * (CATEGORY, LIST, SERIES and SEASON all drill down through list() into their children),
 * findVideos() ~ findvideos() for a terminal MOVIE/EPISODE item.
 */
interface Channel {
  val id: String
  val displayName: String
  val category: ChannelCategory

  /** Top-level menu: one or more named rows shown on Home (e.g. "Sfoglia", "Novità", "Top 10"). */
  suspend fun home(): List<HomeSection>

  /** The filterable catalog root ("Sfoglia") for this source, or null if it has no browsable
   *  archive. Synchronous — a fixed entry point, not a fetch — so Home can link straight to it. */
  fun catalogRoot(): StreamItem? = null

  /** A better (localised) synopsis for a SEASON item than the one it carries, or null. The Browse
   *  screen shows this above a season's episode list when available. */
  suspend fun seasonOverview(item: StreamItem): String? = null

  /** Children of a CATEGORY/LIST/SERIES/SEASON item: more lists/seasons, or terminal MOVIE/EPISODE items.
   *  For a filterable catalog root, [item.extra] carries the active filter as a query-string-shaped
   *  hint (e.g. "type=movie&genre=4&year=2024&sort=release") — see [genres] for filter options. */
  suspend fun list(item: StreamItem, page: Int = 1): ListResult

  /** Genre options this channel's catalog can be filtered by, or empty if it doesn't support filtering. */
  suspend fun genres(): List<GenreOption> = emptyList()

  /** Enriches a MOVIE/SERIES item with full detail-page metadata (plot, genres, cast, director,
   *  runtime) that list/search results don't carry. Returns [item] unchanged when there's nothing
   *  more to fetch or the fetch fails.
   *
   *  [withRatings] additionally pulls external ratings / trailer (extra network per call) — the
   *  detail screen wants them; a bulk caller that only needs cast/director (person-search
   *  verification) passes false to skip that cost. */
  suspend fun detail(item: StreamItem, withRatings: Boolean = true): StreamItem

  /** Resolves a MOVIE/EPISODE item's page into playable candidates (one PLAYABLE StreamItem per server found). */
  suspend fun findVideos(item: StreamItem): List<StreamItem>

  /** Freshly re-derives just the browser-playable embed URL for a MOVIE/EPISODE (no native HLS
   *  step). Used by the WebView fallback, whose short-lived embed token would otherwise be stale
   *  by the time it's needed. Returns null when not applicable/available. */
  suspend fun resolveEmbedUrl(item: StreamItem): String? = null

  suspend fun search(query: String): List<StreamItem>
}
