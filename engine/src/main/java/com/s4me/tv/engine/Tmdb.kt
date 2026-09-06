package com.s4me.tv.engine

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

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
 *  - **API** when a key is configured (`tmdb.apiKey` in local.properties → BuildConfig): clean
 *    JSON, also gives vote count + the official trailer.
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

  private const val API_BASE = "https://api.themoviedb.org/3"
  private const val SITE_BASE = "https://www.themoviedb.org"
  private val apiKey = BuildConfig.TMDB_API_KEY
  private val cache = ConcurrentHashMap<String, Info>()
  private val seasonCache = ConcurrentHashMap<String, String>()
  private val seasonYearsCache = ConcurrentHashMap<String, Map<Int, String>>()

  /** True when an API key is set — the scrape path always works, so this is just "use the nicer one". */
  val usingApi: Boolean
    get() = apiKey.isNotBlank()

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
