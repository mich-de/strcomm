package com.s4me.tv.engine

import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

private val YT_ID = Regex("[A-Za-z0-9_-]{11}")
private val YT_ID_IN_URL = Regex("(?:v=|/embed/|/shorts/|youtu\\.be/|vnd\\.youtube:/?/?)([A-Za-z0-9_-]{11})")

/** Normalises whatever a trailer field holds — a bare id, a watch/embed/short URL, a `vnd.youtube:`
 *  uri — down to the canonical 11-char YouTube id, or null if there isn't one. Everything that
 *  stores [StreamItem.trailerYoutubeId] runs through this so the player only ever gets a clean id. */
fun youtubeVideoId(raw: String?): String? {
  val s = raw?.trim().orEmpty()
  if (s.isEmpty()) return null
  if (s.matches(YT_ID)) return s
  return YT_ID_IN_URL.find(s)?.groupValues?.get(1)
}

/**
 * TMDB lookup by tmdb id — the community score and the Italian synopsis/genres that the source
 * site ships in English for titles it never localised ("House of the Dragon" etc.). One request
 * per title, cached in memory for the session (same shape as [NetflixTop10]).
 *
 * Two paths:
 *  - **API** when a key is configured (`tmdb.apiKey` in local.properties → BuildConfig, or a key
 *    entered in Settings at runtime via [setApiKeyOverride] — that one wins when both are set):
 *    clean JSON, also gives vote count + the official trailer.
 *  - **web scrape** otherwise (no key, no signup): the same score + Italian overview read off
 *    TMDB's public title page. No vote count, no trailer list (the detail screen falls back to the
 *    site's own trailers), and it's fragile to a TMDB redesign — a parse miss just yields null and
 *    the site's own text/score stands.
 */
object Tmdb {
  data class Info(
    val voteAverage: Double?,
    val voteCount: Int?,
    val trailerYoutubeId: String?,
    val overviewIt: String?,
    val genresIt: String?,
  )

  /** One movie in a TMDB "belongs_to_collection" set — enough for the channel to resolve it to a
   *  catalogue entry by tmdb id (exact) or title+year (fallback). */
  data class CollectionPart(val tmdbId: String, val title: String, val year: String?)

  /** A title a person is credited on — enough for the channel to spot it among a catalogue
   *  search's results, which carry no tmdb id: Italian title, year, movie or series. */
  data class PersonCredit(val title: String, val year: Int?, val isSeries: Boolean)

  /** Cache entry for [personCredits]: null credits = "no person by that exact name". */
  private class PersonLookup(val credits: List<PersonCredit>?)

  private const val API_BASE = "https://api.themoviedb.org/3"
  private const val SITE_BASE = "https://www.themoviedb.org"
  @Volatile private var apiKey = BuildConfig.TMDB_API_KEY
  private val cache = ConcurrentHashMap<String, Info>()
  private val seasonCache = ConcurrentHashMap<String, String>()
  private val seasonYearsCache = ConcurrentHashMap<String, Map<Int, String>>()
  private val collectionCache = ConcurrentHashMap<String, List<CollectionPart>>()
  private val personCache = ConcurrentHashMap<String, PersonLookup>()

  /** True when an API key is set — the scrape path always works, so this is just "use the nicer one". */
  val usingApi: Boolean
    get() = apiKey.isNotBlank()

  /** Applies (or clears, with a blank/null [key]) a runtime key from Settings — takes effect on the
   *  next lookup, no restart needed. Both apps call this with [TmdbApiKeyStore]'s saved value once
   *  at startup and again immediately after every Settings save/clear. Falls back to the build-time
   *  key rather than going keyless, so a rebuilt local.properties key still works until overridden. */
  fun setApiKeyOverride(key: String?) {
    apiKey = key?.trim().takeUnless { it.isNullOrBlank() } ?: BuildConfig.TMDB_API_KEY
  }

  /** [isSeries] picks the tv vs movie path — the source site's `tmdb_id` is scoped to the title's
   *  type, so a series id is only valid against /tv. */
  suspend fun lookup(tmdbId: String, isSeries: Boolean): Info? {
    if (tmdbId.isBlank()) return null
    val type = if (isSeries) "tv" else "movie"
    val cacheKey = "$type:$tmdbId"
    cache[cacheKey]?.let { return it }
    val info = (if (usingApi) apiLookup(type, tmdbId) else scrapeLookup(type, tmdbId)) ?: return null
    cache[cacheKey] = info
    return info
  }

  // `language=it-IT` localises overview + genre names; `include_video_language=it,en` keeps the
  // videos list non-empty regardless (it would otherwise be filtered to Italian-only, usually
  // nothing). vote_average is language-independent either way.
  private suspend fun apiLookup(type: String, tmdbId: String): Info? =
    runCatching {
      val body = Net.get("$API_BASE/$type/$tmdbId?api_key=$apiKey&language=it-IT&append_to_response=videos&include_video_language=it,en")
      val obj = JSONObject(body)
      Info(
        voteAverage = obj.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
        voteCount = obj.optInt("vote_count", 0).takeIf { it > 0 },
        trailerYoutubeId = pickTrailer(obj.optJSONObject("videos")?.optJSONArray("results")),
        overviewIt = obj.optString("overview").takeIf { it.isNotBlank() },
        genresIt =
          obj.optJSONArray("genres")?.let { arr ->
            (0 until arr.length())
              .mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
              .joinToString(", ")
              .takeIf { it.isNotBlank() }
          },
      )
    }.getOrNull()

  // Keyless: read the same numbers off the public page. `og:description` carries the full it-IT
  // overview (not a 155-char teaser); the user-score ring exposes `data-percent` (0..100). Genres
  // and vote count aren't reliably parseable here, so they stay null and the caller keeps the
  // site's own. Jsoup (already a dep) handles the HTML-entity decoding in the meta content.
  private suspend fun scrapeLookup(type: String, tmdbId: String): Info? =
    runCatching {
      val html = Net.get("$SITE_BASE/$type/$tmdbId?language=it-IT")
      val doc = Jsoup.parse(html)
      val overview = doc.select("meta[property=og:description]").attr("content").trim().takeIf { it.isNotBlank() }
      val rating =
        doc.select("[data-percent]").firstOrNull()
          ?.attr("data-percent")
          ?.toDoubleOrNull()
          ?.let { it / 10.0 }
          ?.takeIf { it in 0.1..10.0 }
      if (overview == null && rating == null) null
      else Info(voteAverage = rating, voteCount = null, trailerYoutubeId = null, overviewIt = overview, genresIt = null)
    }.getOrNull()

  /** Italian synopsis for one season — the source site ships `seasons[].plot` in English for
   *  titles it never localised, and that's what the episode list header shows. API path when a key
   *  is set, else the public season page's `og:description`. Cached per (title, season). */
  suspend fun seasonOverviewIt(tmdbId: String, seasonNumber: Int): String? {
    if (tmdbId.isBlank() || seasonNumber < 0) return null
    val key = "$tmdbId/$seasonNumber"
    seasonCache[key]?.let { return it }
    val text =
      runCatching {
        if (usingApi) {
          JSONObject(Net.get("$API_BASE/tv/$tmdbId/season/$seasonNumber?api_key=$apiKey&language=it-IT")).optString("overview")
        } else {
          Jsoup.parse(Net.get("$SITE_BASE/tv/$tmdbId/season/$seasonNumber?language=it-IT"))
            .select("meta[property=og:description]")
            .attr("content")
            .trim()
        }
      }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    seasonCache[key] = text
    return text
  }

  // On the public /seasons page (server-rendered) each row reads "Stagione N … YYYY • K episodi";
  // the year is right before the episode count. Bounded lazy gap so it can't jump into the next row.
  private val SEASON_ROW = Regex("""Stagione\s+(\d+)[\s\S]{0,400}?(\d{4})\s*[•·]\s*\d+\s+episod""")

  /** season number → release year ("2020", …). API path: one `/tv/{id}` call. Keyless: one scrape
   *  of the public `/seasons` page. Either way the season cards get a real per-season year instead
   *  of inheriting the series' last-air year ("tutte 2026"). Cached per title. */
  suspend fun seasonYears(tmdbId: String): Map<Int, String> {
    if (tmdbId.isBlank()) return emptyMap()
    seasonYearsCache[tmdbId]?.let { return it }
    val map =
      runCatching {
        if (usingApi) {
          val arr = JSONObject(Net.get("$API_BASE/tv/$tmdbId?api_key=$apiKey")).optJSONArray("seasons") ?: return@runCatching emptyMap<Int, String>()
          (0 until arr.length())
            .mapNotNull {
              val s = arr.getJSONObject(it)
              val n = s.optInt("season_number", -1)
              val year = s.optString("air_date").take(4).takeIf { y -> y.length == 4 }
              if (n >= 0 && year != null) n to year else null
            }
            .toMap()
        } else {
          val html = Net.get("$SITE_BASE/tv/$tmdbId/seasons?language=it-IT")
          SEASON_ROW.findAll(html).mapNotNull { m -> m.groupValues[1].toIntOrNull()?.let { it to m.groupValues[2] } }.toMap()
        }
      }.getOrDefault(emptyMap())
    seasonYearsCache[tmdbId] = map
    return map
  }

  /** The other movies in this movie's TMDB collection (the "saga"/franchise), chronological and
   *  with the current film removed. Empty for a standalone film. API path when a key is set (one
   *  `/movie` + one `/collection` call), else two scrapes of the public TMDB pages — the movie page
   *  links its collection, the collection page server-renders every entry. Cached per movie id. */
  suspend fun collection(movieTmdbId: String): List<CollectionPart> {
    if (movieTmdbId.isBlank()) return emptyList()
    collectionCache[movieTmdbId]?.let { return it }
    val parts =
      runCatching { if (usingApi) apiCollection(movieTmdbId) else scrapeCollection(movieTmdbId) }
        .getOrDefault(emptyList())
        .filterNot { it.tmdbId == movieTmdbId }
        .distinctBy { it.tmdbId }
        .sortedBy { it.year ?: "9999" }
    collectionCache[movieTmdbId] = parts
    return parts
  }

  private suspend fun apiCollection(movieTmdbId: String): List<CollectionPart> {
    val movie = JSONObject(Net.get("$API_BASE/movie/$movieTmdbId?api_key=$apiKey&language=it-IT"))
    val cid = movie.optJSONObject("belongs_to_collection")?.optInt("id", -1)?.takeIf { it > 0 } ?: return emptyList()
    val arr = JSONObject(Net.get("$API_BASE/collection/$cid?api_key=$apiKey&language=it-IT")).optJSONArray("parts") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
      val p = arr.getJSONObject(i)
      val pid = p.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
      val t = p.optString("title").takeIf { it.isNotBlank() } ?: p.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
      CollectionPart(pid.toString(), t, p.optString("release_date").take(4).takeIf { y -> y.length == 4 })
    }
  }

  // Keyless: read the collection id off the movie page, then the collection page server-renders
  // every entry as a "/movie/{id}-slug" card. Titles come out it-IT, matching the source site's.
  private suspend fun scrapeCollection(movieTmdbId: String): List<CollectionPart> {
    val moviePage = Net.get("$SITE_BASE/movie/$movieTmdbId?language=it-IT")
    // The current TMDB build no longer prints a plain "/collection/{id}" link on the movie page —
    // that panel is lazy-loaded and the id survives only as the kendo.format() argument in its
    // loader script (`.../collection/{0}/static_cache/movie_card...'), '2344')`). Try the real-id
    // form first (other TMDB page types still use it), then that templated form.
    val cid =
      Regex("""/collection/(\d+)[/"?]""").find(moviePage)?.groupValues?.get(1)
        ?: Regex("""collection/\{0\}/static_cache/movie_card[^']*'\),\s*'(\d+)'""").find(moviePage)?.groupValues?.get(1)
        ?: return emptyList()
    val doc = Jsoup.parse(Net.get("$SITE_BASE/collection/$cid?language=it-IT"))
    return doc.select("a[href*=/movie/]").mapNotNull { a ->
      val pid = Regex("""/movie/(\d+)""").find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
      val raw = a.text().trim().ifBlank { a.attr("title").trim() }.ifBlank { a.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
      // Card text is "Titolo IT (Original Title)" — the parenthetical only muddies a catalogue
      // re-search, so keep what precedes it.
      val t = raw.substringBefore(" (").trim().ifBlank { raw }
      t.takeIf { it.isNotBlank() }?.let { CollectionPart(pid, it, null) }
    }
  }

  /**
   * What the person called [name] directed or appeared in — null when TMDB's best match for [name]
   * isn't a person of exactly that name (so a two-word title search like "harry potter" is never
   * taken for one) or the lookup failed. Directing counts only as "Regista": TMDB's Direzione
   * department also lists assistant and second-unit work, which the catalogue's own credits don't
   * show ("trova anche altri film dove sergio leone non appare tra registi"). Acting counts in full
   * — documentaries about a director list them as themselves. Cached per name for the session,
   * "not a person" included; a failed lookup is not cached.
   */
  suspend fun personCredits(name: String): List<PersonCredit>? {
    val key = personKey(name)
    if (key.isBlank()) return null
    personCache[key]?.let { return it.credits }
    val credits = runCatching { if (usingApi) apiPersonCredits(name, key) else scrapePersonCredits(name, key) }.getOrElse { return null }
    personCache[key] = PersonLookup(credits)
    return credits
  }

  private suspend fun apiPersonCredits(name: String, key: String): List<PersonCredit>? {
    val q = URLEncoder.encode(name.trim(), "UTF-8")
    val results = JSONObject(Net.get("$API_BASE/search/person?api_key=$apiKey&language=it-IT&query=$q")).optJSONArray("results")
    val person = results?.optJSONObject(0) ?: return null
    if (personKey(person.optString("name")) != key) return null
    val combined = JSONObject(Net.get("$API_BASE/person/${person.optInt("id")}/combined_credits?api_key=$apiKey&language=it-IT"))
    fun credits(arr: JSONArray?, keep: (JSONObject) -> Boolean): List<PersonCredit> =
      (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        val c = arr!!.getJSONObject(i)
        if (!keep(c)) return@mapNotNull null
        val isSeries = c.optString("media_type") == "tv"
        val title = c.optString(if (isSeries) "name" else "title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val year = c.optString(if (isSeries) "first_air_date" else "release_date").take(4).toIntOrNull()
        PersonCredit(title, year, isSeries)
      }
    return credits(combined.optJSONArray("cast")) { true } + credits(combined.optJSONArray("crew")) { it.optString("job") == "Director" }
  }

  // Keyless: the person search page lists matches most popular first ("Sergio Leone" the director
  // ahead of his namesakes), each as <a href="/person/4385-sergio-leone?…" title="Sergio Leone">.
  // The person page then server-renders one credits table per department under an <h3>, each row
  // "year · <a class="tooltip" href="/movie/391-…"><bdi>Title</bdi></a> … job".
  private suspend fun scrapePersonCredits(name: String, key: String): List<PersonCredit>? {
    val q = URLEncoder.encode(name.trim(), "UTF-8")
    val match = PERSON_LINK.find(Net.get("$SITE_BASE/search/person?query=$q&language=it-IT")) ?: return null
    if (personKey(Parser.unescapeEntities(match.groupValues[2], true)) != key) return null
    val page = Net.get("$SITE_BASE/person/${match.groupValues[1]}?language=it-IT")
    val headings = CREDIT_HEADING.findAll(page).toList()
    val credits = mutableListOf<PersonCredit>()
    headings.forEachIndexed { i, heading ->
      val department = heading.groupValues[1].trim()
      if (department != "Direzione" && department != "Recitazione") return@forEachIndexed
      val section = page.substring(heading.range.last + 1, headings.getOrNull(i + 1)?.range?.first ?: page.length)
      for (row in CREDIT_ROW.findAll(section)) {
        val html = row.groupValues[1]
        val link = CREDIT_LINK.find(html) ?: continue
        if (department == "Direzione") {
          val jobs = Parser.unescapeEntities(html.replace(TAG, " "), false).split('…').drop(1).map { it.trim() }
          if ("Regista" !in jobs) continue
        }
        val title = Parser.unescapeEntities(link.groupValues[3].trim(), false)
        credits += PersonCredit(title, CREDIT_YEAR.find(html)?.groupValues?.get(1)?.toIntOrNull(), isSeries = link.groupValues[1] == "tv")
      }
    }
    return credits
  }

  /** Accent-, case- and punctuation-insensitive form of a person's name, for "is this exactly them". */
  private fun personKey(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase().replace(NON_ALNUM, " ").trim()

  private val PERSON_LINK = Regex("""href="/person/(\d+-[^"?]+)[^"]*"\s+title="([^"]+)"""")
  private val CREDIT_HEADING = Regex("""<h3[^>]*>([^<]{2,40})</h3>""")
  private val CREDIT_ROW = Regex("""<tr>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
  private val CREDIT_LINK = Regex("""<a class="tooltip" href="/(movie|tv)/(\d+)[^"]*"[^>]*>\s*<bdi>([^<]+)</bdi>""")
  private val CREDIT_YEAR = Regex("""<td class="year">\s*(\d{4})""")
  private val TAG = Regex("<[^>]+>")
  private val DIACRITICS = Regex("\\p{M}+")
  private val NON_ALNUM = Regex("[^a-z0-9]+")

  /** Best YouTube clip: a real "Trailer" beats a teaser/clip, Italian beats other languages, and
   *  an official upload beats a fan mirror — summed so the ordering degrades gracefully when a
   *  title has only, say, an unofficial English teaser. */
  private fun pickTrailer(results: JSONArray?): String? {
    results ?: return null
    val candidates =
      (0 until results.length())
        .map { results.getJSONObject(it) }
        .filter { it.optString("site").equals("YouTube", ignoreCase = true) && it.optString("key").isNotBlank() }
    fun rank(v: JSONObject): Int {
      var score = 0
      if (v.optString("type").equals("Trailer", ignoreCase = true)) score += 4
      if (v.optString("iso_639_1").equals("it", ignoreCase = true)) score += 2
      if (v.optBoolean("official")) score += 1
      return score
    }
    return youtubeVideoId(candidates.maxByOrNull(::rank)?.optString("key"))
  }
}
