package com.s4me.tv.engine.channels

import com.s4me.tv.engine.Channel
import com.s4me.tv.engine.ChannelCategory
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.HomeSection
import com.s4me.tv.engine.HttpStatusException
import com.s4me.tv.engine.Imdb
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.ListResult
import com.s4me.tv.engine.Net
import com.s4me.tv.engine.Scrape
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.Tmdb
import com.s4me.tv.engine.youtubeVideoId
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * streamingcommunityz.taxi — a Laravel + Inertia.js (Vue) site: every page ships a JSON blob
 * (`data-page`) with the exact data it renders, and `/it/archive` and `/it/search` are plain
 * paginated JSON endpoints. No HTML-scraping needed for browsing at all. Playback goes through a
 * shared "vixcloud.co" backend: `/it/iframe/{id}-{slug}[?episode_id=N]` returns a small page whose
 * embedded `window.masterPlaylist` gives a direct, already-token-signed HLS URL, fetchable with a
 * plain HTTP client (verified: manifest, sub-playlist and .ts segments all 200 with a bare
 * User-Agent + Referer, no browser/WebView needed) — so [findVideos] resolves straight to that
 * .m3u8 for native ExoPlayer playback, falling back to the vixcloud embed page (loaded in a
 * WebView, see PlayerScreen) only if that resolution or the live manifest fetch fails.
 *
 * This TLD is not stable: the addon originally shipped against `.style`, which 301-redirected to
 * `.tax`, which in turn now redirects to `.taxi` ("usa anche ...z.taxi come fonti"). These
 * aggregator sites rotate domains to dodge blocking — rather than hardcode a value that goes stale
 * again, [syncConfigFrom] reads the site's own self-reported `app_url`/`cdn_url` (present in every
 * Inertia page's `props`, right next to `version`) and keeps [host]/[cdn] pointed at whatever
 * domain actually served the response. [hostSeeds] below are only the cold-start guesses for the
 * very first request of a session, tried newest-first: [withColdStartFallback] walks them until one
 * answers (so a dead primary domain no longer bricks the app even without a redirect), then the
 * response's own props self-update [host]/[cdn] and every later request skips straight through.
 */
class StreamingCommunityChannel : Channel {
  override val id = "streamingcommunity"
  override val displayName = "StreamingCommunity"
  override val category = ChannelCategory.MIXED

  @Volatile private var host = "https://streamingcommunityz.taxi"
  @Volatile private var cdn = "https://cdn.streamingcommunityz.taxi/images/"

  /**
   * Cold-start seeds for [host], newest domain first. The site rotates its TLD, so these are only
   * guesses: [withColdStartFallback] tries them in order on the first request of the session and
   * adopts whichever one answers, after which [syncConfigFrom] pins the site's own reported domain
   * from every response. `.taxi` is the live domain, `.tax` the prior one (still 301-ing here for
   * now, but listed explicitly so it keeps working the day that redirect stops).
   */
  private val hostSeeds = listOf("https://streamingcommunityz.taxi", "https://streamingcommunityz.tax")

  /** Flips true the first time any fetch succeeds — from then on [withColdStartFallback] is a
   *  straight passthrough and the seed-walk never runs again this session. */
  @Volatile private var hostConfirmed = false

  @Volatile private var inertiaVersion: String? = null
  @Volatile private var cachedGenres: List<GenreOption>? = null

  override fun catalogRoot(): StreamItem =
    StreamItem(title = "Sfoglia", url = "1", kind = ItemKind.LIST, channelId = id, referer = host)

  override suspend fun seasonOverview(item: StreamItem): String? {
    if (item.kind != ItemKind.SEASON) return null
    val n = item.season ?: item.url.split("|").getOrNull(2)?.toIntOrNull() ?: return null
    // tmdbId is normally carried onto the SEASON item by listSeasons; re-fetch the series page for
    // it only if it isn't (e.g. a season item built elsewhere, like auto-next-episode's synthetic one).
    val tmdbId =
      item.tmdbId
        ?: decodeRef(item.url)?.let { (titleId, slug) ->
          fetchInertia("$host/it/titles/$titleId-$slug")
            ?.optJSONObject("props")
            ?.optJSONObject("title")
            ?.let { optStringOrNull(it, "tmdb_id") }
        }
    return tmdbId?.takeIf { it != "0" }?.let { Tmdb.seasonOverviewIt(it, n) }
  }

  override suspend fun home(): List<HomeSection> {
    val root = catalogRoot()
    val page = fetchInertia("$host/") ?: return listOf(HomeSection("StreamingCommunity", "$id-root", listOf(root)))
    val props = page.optJSONObject("props")
    cacheGenresFrom(props)
    val sliders = props?.optJSONArray("sliders") ?: JSONArray()
    fun sliderTitles(name: String): List<StreamItem> =
      (0 until sliders.length())
        .map { sliders.getJSONObject(it) }
        .firstOrNull { it.optString("name") == name }
        ?.optJSONArray("titles")
        ?.let { parseTitles(it) } ?: emptyList()

    val sections = mutableListOf(HomeSection("StreamingCommunity", "$id-root", listOf(root) + sliderTitles("trending")))
    sliderTitles("latest").takeIf { it.isNotEmpty() }?.let { sections += HomeSection("Novità", "$id-latest", it) }
    sliderTitles("top10").takeIf { it.isNotEmpty() }?.let { sections += HomeSection("Top 10", "$id-top10", it) }
    // Title-level "release order" rows — the archive's finest granularity is a title (a movie, or
    // a show's latest season/episode bump), not per-episode, so "ultimi episodi" is approximated
    // as "shows most recently updated," which is what that row practically means to a viewer.
    // Run both in parallel rather than one after the other — sequential awaits here were adding a
    // second round-trip's worth of latency to every Home load for two rows below the fold.
    coroutineScope {
      val moviesByRelease = async { fetchJson("$host/it/archive?type=movie&sort=release_date&page=1")?.optJSONArray("data")?.let { parseTitles(it) } }
      val tvByRelease = async { fetchJson("$host/it/archive?type=tv&sort=release_date&page=1")?.optJSONArray("data")?.let { parseTitles(it) } }
      moviesByRelease.await()?.takeIf { it.isNotEmpty() }?.let { sections += HomeSection("Film · Ordine di uscita", "$id-movies-release", it) }
      tvByRelease.await()?.takeIf { it.isNotEmpty() }?.let { sections += HomeSection("Serie TV · Ultimi episodi", "$id-tv-release", it) }
    }
    return sections
  }

  override suspend fun genres(): List<GenreOption> {
    cachedGenres?.let { return it }
    fetchInertia("$host/")
    return cachedGenres ?: emptyList()
  }

  private fun cacheGenresFrom(props: JSONObject?) {
    if (cachedGenres != null) return
    val arr = props?.optJSONArray("genres") ?: return
    val options =
      (0 until arr.length())
        .mapNotNull { i ->
          val g = arr.getJSONObject(i)
          val gid = g.optInt("id", -1)
          val name = g.optString("name")
          if (gid < 0 || name.isBlank()) null else GenreOption(gid, name)
        }
        .distinctBy { it.id }
        .sortedBy { it.name }
    if (options.isNotEmpty()) cachedGenres = options
  }

  override suspend fun list(item: StreamItem, page: Int): ListResult =
    when (item.kind) {
      ItemKind.SERIES -> listSeasons(item)
      ItemKind.SEASON -> listEpisodes(item)
      else -> listArchive(page, item.extra)
    }

  /** [filter] is the query-string-shaped hint from [StreamItem.extra] — e.g.
   *  "type=movie&genre=4&year=2024&sort=release" — as set by BrowseViewModel's filter UI. */
  private suspend fun listArchive(page: Int, filter: String?): ListResult {
    val params = StringBuilder("page=$page")
    filter?.split("&")?.forEach { pair ->
      val (key, value) = pair.split("=", limit = 2).let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
      if (value.isBlank()) return@forEach
      when (key) {
        "type" -> params.append("&type=").append(URLEncoder.encode(value, "UTF-8"))
        "genre" -> params.append("&genre[]=").append(URLEncoder.encode(value, "UTF-8"))
        "year" -> params.append("&year=").append(URLEncoder.encode(value, "UTF-8"))
        "sort" ->
          when (value) {
            "release" -> params.append("&sort=release_date")
            "score" -> params.append("&sort=score")
          }
      }
    }
    val json = fetchJson("$host/it/archive?$params") ?: return ListResult(emptyList(), false)
    val items = parseTitles(json.optJSONArray("data") ?: JSONArray())
    val hasMore = json.optInt("current_page", page) < json.optInt("last_page", page)
    return ListResult(items, hasMore)
  }

  private suspend fun listSeasons(item: StreamItem): ListResult {
    val (titleId, slug) = decodeRef(item.url) ?: return ListResult(emptyList(), false)
    val page = fetchInertia("$host/it/titles/$titleId-$slug") ?: return ListResult(emptyList(), false)
    val title = page.optJSONObject("props")?.optJSONObject("title") ?: return ListResult(emptyList(), false)
    val tmdbId = optStringOrNull(title, "tmdb_id")?.takeIf { it != "0" }
    // Real per-season release years (season 1 → "2020", …) from TMDB — one extra request, cached.
    // The site has no per-season date, so without this every card inherited the SERIES' last-air
    // year and read as "tutte 2026".
    val seasonYears = tmdbId?.let { runCatching { Tmdb.seasonYears(it) }.getOrDefault(emptyMap()) } ?: emptyMap()
    val seasons = title.optJSONArray("seasons") ?: JSONArray()
    val items =
      (0 until seasons.length()).mapNotNull { i ->
        val s = seasons.getJSONObject(i)
        val number = s.optInt("number", -1)
        if (number < 0) return@mapNotNull null
        // Announced-but-empty seasons (episodes_count == 0, e.g. a next season the site already
        // lists) stay visible but labelled, so opening one isn't a mystery blank page.
        val episodesCount = s.optInt("episodes_count", -1)
        val announced = episodesCount == 0
        val label =
          when {
            announced -> "Stagione $number · In arrivo"
            episodesCount > 0 -> "Stagione $number · $episodesCount episodi"
            else -> "Stagione $number"
          }
        StreamItem(
          title = label,
          url = "$titleId|$slug|$number",
          kind = ItemKind.SEASON,
          channelId = id,
          // Seasons carry no art of their own, so reuse the series' poster — a real cover on every
          // season card instead of the "ST" text placeholder that read like a broken title.
          thumbnail = item.thumbnail,
          seriesTitle = item.title,
          // Already present in this same lightweight seasons array (verified live against the
          // site), so showing it on the season screen costs no extra request.
          plot = optStringOrNull(s, "plot"),
          // Carried so BrowseViewModel can fetch an Italian season synopsis (seasonOverview) with
          // no extra series-page round-trip — it's right here on props.title.
          tmdbId = tmdbId,
          season = number,
          // Real season release year from TMDB, or null — NOT item.year (the series' last-air date,
          // which stamped the same latest year on every season).
          year = seasonYears[number],
          referer = host,
        )
      }
    return ListResult(items, false)
  }

  private suspend fun listEpisodes(item: StreamItem): ListResult {
    val parts = item.url.split("|")
    if (parts.size < 3) return ListResult(emptyList(), false)
    val (titleId, slug, seasonNumber) = parts
    // Season must be a PATH segment (/season-N): the Vue router only matches that form, and a
    // ?season=N query is silently ignored — the server then returns the default loadedSeason
    // (season 1), which had every season of a show listing season 1's episodes (Gomorra bug).
    val page = fetchInertia("$host/it/titles/$titleId-$slug/season-$seasonNumber") ?: return ListResult(emptyList(), false)
    val loadedSeason = page.optJSONObject("props")?.optJSONObject("loadedSeason") ?: return ListResult(emptyList(), false)
    val episodes = loadedSeason.optJSONArray("episodes") ?: JSONArray()
    val items =
      (0 until episodes.length()).mapNotNull { i ->
        val e = episodes.getJSONObject(i)
        val epId = e.optInt("id", -1)
        val number = e.optInt("number", -1)
        if (epId < 0 || number < 0) return@mapNotNull null
        // Episode still ("cover", a 16:9 landscape frame) for the horizontal episode cards.
        val epImage = findImage(e.optJSONArray("images"), "cover") ?: findImage(e.optJSONArray("images"), "background")
        StreamItem(
          title = "$number. ${e.optString("name", "Episodio $number")}",
          url = "$titleId|$slug|$epId",
          kind = ItemKind.EPISODE,
          channelId = id,
          thumbnail = epImage?.let { cdn + it } ?: item.thumbnail,
          seriesTitle = item.seriesTitle,
          season = seasonNumber.toIntOrNull(),
          episode = number,
          plot = e.optString("plot", "").takeIf { it.isNotBlank() },
          // No per-episode air date exists on this site (checked live: absent from the episode
          // object entirely) — inherited from the season item, which got it from the series.
          year = item.year,
          referer = host,
        )
      }
    return ListResult(items, false)
  }

  override suspend fun detail(item: StreamItem, withRatings: Boolean): StreamItem {
    if (item.kind != ItemKind.MOVIE && item.kind != ItemKind.SERIES) return item
    val (titleId, slug) = decodeRef(item.url) ?: return item
    val page = fetchInertia("$host/it/titles/$titleId-$slug") ?: return item
    val title = page.optJSONObject("props")?.optJSONObject("title") ?: return item
    val tmdbId = optStringOrNull(title, "tmdb_id")?.takeIf { it != "0" }
    val imdbId = optStringOrNull(title, "imdb_id")
    // IMDb rating + TMDB score/Italian-text (both keyless by default — scraped; TMDB uses its API
    // instead if a key is set), looked up together. Skipped for person-search verification, which
    // only reads cast/director below and would otherwise hit IMDb/TMDB dozens of times per name tap.
    val (imdb, tmdb) =
      if (!withRatings) null to null
      else
        coroutineScope {
          val imdbDeferred = async { imdbId?.let { runCatching { Imdb.rating(it) }.getOrNull() } }
          val tmdbDeferred = async { tmdbId?.let { runCatching { Tmdb.lookup(it, isSeries = item.kind == ItemKind.SERIES) }.getOrNull() } }
          imdbDeferred.await() to tmdbDeferred.await()
        }
    return item.copy(
      // TMDB's Italian text wins when present — the site ships English plot/genres for titles it
      // never localised ("House of the Dragon" etc.); TMDB it-IT has them, or at worst the same
      // English, never worse.
      plot = tmdb?.overviewIt ?: optStringOrNull(title, "plot") ?: item.plot,
      genres = tmdb?.genresIt ?: joinNames(title.optJSONArray("genres")),
      cast = joinNames(title.optJSONArray("main_actors"), limit = 6),
      director = joinNames(title.optJSONArray("main_directors")),
      runtime = title.optInt("runtime", -1).takeIf { it > 0 },
      tmdbId = tmdbId,
      imdbId = imdbId,
      imdbRating = imdb?.value,
      imdbVotes = imdb?.votes,
      tmdbRating = tmdb?.voteAverage,
      tmdbVotes = tmdb?.voteCount,
      trailerYoutubeId = tmdb?.trailerYoutubeId ?: extractTrailerId(title),
    )
  }

  /** "Altri capitoli della saga" — the movie's TMDB collection, each sibling resolved back to a
   *  catalogue MOVIE. The link is TMDB's own `belongs_to_collection`, not a title-keyword guess
   *  (that was the old, removed row); a sibling not on the source just drops out. */
  override suspend fun collection(item: StreamItem): List<StreamItem> {
    if (item.kind != ItemKind.MOVIE) return emptyList()
    val tmdbId =
      (item.tmdbId
          ?: decodeRef(item.url)?.let { (titleId, slug) ->
            fetchInertia("$host/it/titles/$titleId-$slug")
              ?.optJSONObject("props")
              ?.optJSONObject("title")
              ?.let { optStringOrNull(it, "tmdb_id") }
          })
        ?.takeIf { it != "0" } ?: return emptyList()

    val parts = Tmdb.collection(tmdbId)
    if (parts.isEmpty()) return emptyList()
    val selfId = item.url.substringBefore("|")
    return coroutineScope {
        parts.map { part ->
          async {
            runCatching {
                val hits = search(part.title).filter { it.kind == ItemKind.MOVIE }
                hits.firstOrNull { it.tmdbId != null && it.tmdbId == part.tmdbId }
                  ?: hits.firstOrNull { looseTitleMatch(it.title, part.title) && (part.year == null || it.year == part.year) }
                  ?: hits.firstOrNull { looseTitleMatch(it.title, part.title) }
              }
              .getOrNull()
          }
        }
      }
      .awaitAll()
      .filterNotNull()
      .distinctBy { it.url }
      .filter { it.url.substringBefore("|") != selfId }
  }

  /** Punctuation/spacing-insensitive title equality-or-containment — TMDB's it-IT title and the
   *  source's own title for the same film often differ only in dashes/colons ("Dune: Parte Due"
   *  vs "Dune - Parte due"). */
  private fun looseTitleMatch(a: String, b: String): Boolean {
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    val na = norm(a)
    val nb = norm(b)
    return na.isNotEmpty() && nb.isNotEmpty() && (na == nb || na.contains(nb) || nb.contains(na))
  }

  /** The site keeps a `trailers` array on the title props (TMDB-sourced, each entry a YouTube
   *  clip). Prefer one whose label marks it Italian; otherwise take the first. Tolerant of which
   *  key holds the id (`youtube_id` / `key` / a full URL) — [youtubeVideoId] normalises it, and a
   *  minor site-side rename just means "no trailer button", never a crash. */
  private fun extractTrailerId(title: JSONObject): String? {
    val arr = title.optJSONArray("trailers")?.takeIf { it.length() > 0 } ?: return null
    val entries = (0 until arr.length()).map { arr.getJSONObject(it) }
    fun isItalian(o: JSONObject) =
      listOf("name", "title", "label", "language", "lang").any { optStringOrNull(o, it)?.contains("ita", ignoreCase = true) == true }
    val pick = entries.firstOrNull(::isItalian) ?: entries.first()
    val raw = optStringOrNull(pick, "youtube_id") ?: optStringOrNull(pick, "key") ?: optStringOrNull(pick, "url") ?: optStringOrNull(pick, "link")
    return youtubeVideoId(raw)
  }

  private fun joinNames(arr: JSONArray?, limit: Int = Int.MAX_VALUE): String? {
    arr ?: return null
    val names = (0 until arr.length()).mapNotNull { i -> optStringOrNull(arr.getJSONObject(i), "name") }
    return names.take(limit).joinToString(", ").takeIf { it.isNotBlank() }
  }

  private fun iframeUrlFor(item: StreamItem): String? {
    val parts = item.url.split("|")
    return when (item.kind) {
      ItemKind.MOVIE -> parts.takeIf { it.size >= 2 }?.let { (titleId, slug) -> "$host/it/iframe/$titleId-$slug" }
      ItemKind.EPISODE ->
        parts.takeIf { it.size >= 3 }?.let { (titleId, slug, epId) -> "$host/it/iframe/$titleId-$slug?episode_id=$epId&next_episode=1" }
      else -> null
    }
  }

  /** Fetches the iframe page and pulls out its (freshly-tokenised) vixcloud embed URL. The token
   *  lives only ~20s, so callers must use the result immediately. */
  private suspend fun fetchEmbedUrl(item: StreamItem): String? {
    val iframeUrl = iframeUrlFor(item) ?: return null
    val iframeHtml = runCatching { Net.get(iframeUrl, referer = host) }.getOrElse { return null }
    return Scrape.find1(iframeHtml, """(https://vixcloud\.co/embed/\d+\?[^"'\s]+)""").let { Scrape.unescape(it) }.takeIf { it.isNotBlank() }
  }

  override suspend fun resolveEmbedUrl(item: StreamItem): String? = fetchEmbedUrl(item)

  override suspend fun findVideos(item: StreamItem): List<StreamItem> {
    val embedUrl = fetchEmbedUrl(item) ?: return emptyList()

    val hlsUrl = runCatching { resolveHlsUrl(embedUrl) }.getOrNull()
    // Every content field [item] carries (including whatever detail() enriched it with — genres,
    // cast, director, runtime, tmdbId, imdbId) is carried onto the PLAYABLE item so PlayerScreen's
    // info overlay has the full picture — title here becomes the source/server label
    // ("StreamingCommunity") instead, since this is a template copy, not [item] itself.
    val base =
      item.copy(
        title = "StreamingCommunity",
        url = "",
        kind = ItemKind.PLAYABLE,
        channelId = id,
        serverId = null,
        extra = null,
        contentTitle = item.title,
        // Keep the source movie/episode identity so PlayerScreen can save/restore watch progress
        // against a stable key (the HLS url it plays is a throwaway token).
        originId = item.url,
      )
    val playable =
      if (hlsUrl != null) {
        // Native path: ExoPlayer plays the resolved .m3u8 directly. `extra` keeps the embed page
        // around so PlayerScreen can fall back to the WebView if the manifest fetch 403s in
        // practice on some client/network this wasn't verified against.
        base.copy(url = hlsUrl, serverId = "hls", referer = "https://vixcloud.co/", extra = embedUrl)
      } else {
        // Resolution failed (page shape changed, request errored, etc.) — fall back straight to
        // the WebView loading the embed page itself, same as before this was resolved further.
        base.copy(url = embedUrl, serverId = "webview", referer = host)
      }
    return listOf(playable)
  }

  /**
   * Pulls the pre-signed `.m3u8` URL out of vixcloud's embed page: `window.masterPlaylist = {
   * params: { 'token': '...', 'expires': '...' }, url: 'https://vixcloud.co/playlist/{id}' }`.
   * Must be called immediately after fetching [embedUrl] — same short-token-lifetime constraint
   * as the iframe step above.
   */
  private suspend fun resolveHlsUrl(embedUrl: String): String? {
    val embedHtml = Net.get(embedUrl, referer = host)
    val playlistUrl = Scrape.find1(embedHtml, """url:\s*'([^']+)'""")
    val token = Scrape.find1(embedHtml, """'token':\s*'([^']+)'""")
    val expires = Scrape.find1(embedHtml, """'expires':\s*'([^']+)'""")
    if (playlistUrl.isBlank() || token.isBlank() || expires.isBlank()) return null
    // Some episodes' masterPlaylist.url already carries a query string (observed: "?b=1" on a
    // TV episode, absent on a movie) — reusing "?" in that case glues onto it instead of
    // starting a new query string, so the server never sees a real "token" param and 403s.
    val sep = if (playlistUrl.contains("?")) "&" else "?"
    // `h=1` must mirror the embed's `window.canPlayFHD` EXACTLY — the signed token embeds the FHD
    // entitlement and the CDN 403s a mismatch in both directions (verified against the CDN:
    // canPlayFHD=true → 403 without h=1, 200 with; canPlayFHD=false → 200 without, 403 with).
    // Getting this wrong was the real cause of native playback 403ing into the WebView fallback.
    val canPlayFhd = Regex("""window\.canPlayFHD\s*=\s*true""").containsMatchIn(embedHtml)
    val fhd = if (canPlayFhd) "&h=1" else ""
    return "$playlistUrl${sep}token=$token&expires=$expires$fhd"
  }

  override suspend fun search(query: String): List<StreamItem> {
    val json = fetchJson("$host/it/search?q=${URLEncoder.encode(query, "UTF-8")}") ?: return emptyList()
    return rankByRelevance(query, parseTitles(json.optJSONArray("data") ?: JSONArray()))
  }

  /**
   * The site's search is a fuzzy STEM match: `q=matrix` returns the 4 real Matrix films and then
   * ~55 "Matrimonio…"/"Matriarch"/"Matricola" titles sharing only the "matri" stem ("matrix ha
   * dato un sacco di titoli"). Re-rank by how the query sits in the TITLE — exact, whole-word
   * prefix, whole word, bare substring — and once any of those solid tiers has a hit, drop the
   * long tail the site matched on a stem / plot / cast name instead of the title. Stable sort, so
   * inside a tier the site's own ordering is kept. A query that hits no title at all (a person
   * name like "Gal Gadot") lands entirely in the last tier and is returned untouched, which is
   * what the person-search verification path downstream needs.
   */
  private fun rankByRelevance(query: String, items: List<StreamItem>): List<StreamItem> {
    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()
    val q = norm(query)
    if (q.isEmpty() || items.isEmpty()) return items
    val whole = Regex("(^| )${Regex.escape(q)}( |$)")
    fun tier(title: String): Int {
      val t = norm(title)
      return when {
        t == q -> 0
        t.startsWith("$q ") -> 1
        whole.containsMatchIn(t) -> 2
        t.contains(q) -> 3
        else -> 4
      }
    }
    // A 1–3 char query ("it", "up") turns "substring of the title" into noise ("Spirit", "Legit"),
    // so for those keep only exact / whole-word matches.
    val keep = if (q.length <= 3) 2 else 3
    val scored = items.map { it to tier(it.title) }
    val solid = scored.any { it.second <= keep }
    return scored.filter { !solid || it.second <= keep }.sortedBy { it.second }.map { it.first }
  }

  // --- shared helpers -----------------------------------------------------------------------

  private fun parseTitles(arr: JSONArray): List<StreamItem> =
    (0 until arr.length()).mapNotNull { i ->
      val t = arr.getJSONObject(i)
      val tid = t.optInt("id", -1)
      val slug = t.optString("slug", "")
      if (tid < 0 || slug.isBlank()) return@mapNotNull null
      val isTv = t.optString("type", "movie") == "tv"
      val images = t.optJSONArray("images")
      val poster = findImage(images, "poster")
      val backdrop = findImage(images, "background")
      // "cover" is the 16:9 card art the site's own rows use; "logo" is the transparent title
      // treatment it overlays on that card — both feed :app's horizontal Home cards.
      val cover = findImage(images, "cover")
      val logo = findImage(images, "logo")
      val score = optStringOrNull(t, "score")
      StreamItem(
        title = t.optString("name", "?"),
        url = "$tid|$slug",
        kind = if (isTv) ItemKind.SERIES else ItemKind.MOVIE,
        channelId = id,
        thumbnail = poster?.let { cdn + it },
        backdrop = backdrop?.let { cdn + it },
        cover = cover?.let { cdn + it },
        logo = logo?.let { cdn + it },
        // Prefer the Italy-specific air date (`last_air_date_it`) over the generic one — the site
        // tracks them separately (imported/dubbed content can air later in Italy than originally),
        // and this is an Italian-language app for an Italian audience. Falls back to the generic
        // field on titles where the IT-specific one is null.
        year =
          (optStringOrNull(t, "last_air_date_it") ?: optStringOrNull(t, "last_air_date"))
            ?.take(4)
            ?.takeIf { it.length == 4 },
        quality = score?.takeIf { it != "0" }?.let { "★ $it" },
        // Present on some list endpoints, absent on others — when it's here it lets a caller match
        // a title exactly (e.g. resolving a TMDB collection sibling) instead of by fuzzy name.
        tmdbId = optStringOrNull(t, "tmdb_id")?.takeIf { it != "0" },
        referer = host,
      )
    }

  /** org.json's `optString(key, fallback)` only falls back when the key is ABSENT — a key present
   *  with a JSON `null` value (a title with no score yet, e.g. very fresh releases) still "exists"
   *  as the `JSONObject.NULL` sentinel, whose `toString()` is the literal string "null". Left
   *  unguarded that surfaced as a "★ null" score badge and, since "null".length == 4, would have
   *  passed as a bogus year too. */
  private fun optStringOrNull(obj: JSONObject, key: String): String? =
    if (obj.isNull(key)) null else obj.optString(key, "").takeIf { it.isNotBlank() }

  private fun findImage(images: JSONArray?, type: String): String? {
    images ?: return null
    for (i in 0 until images.length()) {
      val img = images.getJSONObject(i)
      if (img.optString("type") == type) return img.optString("filename").takeIf { it.isNotBlank() }
    }
    return null
  }

  private fun decodeRef(url: String): Pair<String, String>? {
    val parts = url.split("|")
    return if (parts.size >= 2) parts[0] to parts[1] else null
  }

  /**
   * Cold start only: [host] is a guess and the site rotates its TLD, so if [fetch] comes back null
   * before any request has confirmed a live host, retry the same path against each remaining
   * [hostSeeds] entry, adopting the first that produces a result as [host]/[cdn]. `url` is passed
   * already built against the current [host]; the path is sliced back off it and re-prefixed per
   * seed, so a repoint mid-walk still hits the right domain. Once [hostConfirmed] this is a plain
   * passthrough. Lock-free like [syncConfigFrom]: a few concurrent probes on the very first load
   * just converge on the same working host.
   */
  private suspend fun <T : Any> withColdStartFallback(url: String, fetch: suspend (String) -> T?): T? {
    fetch(url)?.let {
      hostConfirmed = true
      return it
    }
    if (hostConfirmed) return null
    val path = url.removePrefix(host).ifEmpty { "/" }
    for (seed in hostSeeds) {
      if (seed == host) continue
      host = seed
      cdn = "https://cdn." + seed.removePrefix("https://") + "/images/"
      fetch("$seed$path")?.let {
        hostConfirmed = true
        return it
      }
    }
    return null
  }

  /** Plain paginated JSON (archive/search) — no Inertia envelope, no version header needed. */
  private suspend fun fetchJson(url: String): JSONObject? =
    withColdStartFallback(url) { u ->
      val body = runCatching { Net.get(u, headers = mapOf("Accept" to "application/json"), referer = host) }.getOrNull()
      body?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

  /**
   * A full Inertia page's JSON (needed for title/season data, which only populates fully via the
   * X-Inertia AJAX path). Caches the asset version and refreshes it once on a 409 (version stale
   * after a deploy) rather than hardcoding it.
   */
  private suspend fun fetchInertia(url: String): JSONObject? {
    // Path relative to whatever [host] currently is: [fetchVersionFromFullPage] below can repoint
    // [host] via the cold-start fallback, so every request here is rebuilt from `path`, never from
    // the possibly-stale seed baked into `url`.
    val path = url.removePrefix(host).ifEmpty { "/" }
    val version = inertiaVersion ?: fetchVersionFromFullPage(url) ?: return null
    val headers = mapOf("X-Inertia" to "true", "X-Inertia-Version" to version, "Accept" to "application/json")
    val body =
      try {
        Net.get("$host$path", headers = headers, referer = host)
      } catch (e: HttpStatusException) {
        if (e.code != 409) return null
        val fresh = fetchVersionFromFullPage("$host$path") ?: return null
        inertiaVersion = fresh
        runCatching { Net.get("$host$path", headers = headers + ("X-Inertia-Version" to fresh), referer = host) }.getOrElse { return null }
      } catch (e: Exception) {
        return null
      }
    return runCatching { JSONObject(body) }.getOrNull()
  }

  private suspend fun fetchVersionFromFullPage(url: String): String? =
    withColdStartFallback(url) { u ->
      val html = runCatching { Net.get(u, referer = host) }.getOrNull() ?: return@withColdStartFallback null
      val page = extractDataPage(html) ?: return@withColdStartFallback null
      page.optString("version").takeIf { it.isNotBlank() }?.also { inertiaVersion = it }
    }

  private fun extractDataPage(html: String): JSONObject? {
    val raw = Scrape.find1(html, """data-page="(.*?)"\s*>""")
    if (raw.isBlank()) return null
    return runCatching { JSONObject(Scrape.unescape(raw)) }.getOrNull()?.also { syncConfigFrom(it) }
  }

  /**
   * Every Inertia page's `props` carries the domain that actually served it (`app_url`,
   * `cdn_url`) alongside `version` — reading it back here is what makes [host]/[cdn] self-heal
   * after a TLD rotation, instead of silently going stale like a hardcoded value would.
   */
  private fun syncConfigFrom(page: JSONObject) {
    val props = page.optJSONObject("props") ?: return
    props.optString("app_url").trimEnd('/').takeIf { it.isNotBlank() }?.let { host = it }
    props.optString("cdn_url").trimEnd('/').takeIf { it.isNotBlank() }?.let { cdn = "$it/images/" }
  }
}
