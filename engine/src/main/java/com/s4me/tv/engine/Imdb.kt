package com.s4me.tv.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * IMDb rating, scraped from the public title page's schema.org JSON-LD (`AggregateRating`) — no API
 * key, no quota, no signup. IMDb publishes no free API, so this is the only way to the actual IMDb
 * number; it's also the rating most people mean by "voto del film".
 *
 * One GET per title, only when its detail screen opens, cached in memory for the session — the
 * same shape as [Tmdb] and [NetflixTop10]. The regexes target the JSON-LD block specifically (not
 * any stray `ratingValue` elsewhere on the page); a markup change just means "no IMDb rating",
 * never a crash.
 */
object Imdb {
  data class Rating(val value: Double, val votes: Int?)

  private val cache = ConcurrentHashMap<String, Rating>()

  // In the JSON-LD, "ratingCount" precedes "ratingValue" inside the same object, e.g.
  //   "aggregateRating":{"@type":"AggregateRating","ratingCount":316237,"bestRating":10,"worstRating":1,"ratingValue":8.4}
  private val RATING_VALUE = Regex(""""aggregateRating"\s*:\s*\{[^}]*?"ratingValue"\s*:\s*([0-9.]+)""")
  private val RATING_COUNT = Regex(""""aggregateRating"\s*:\s*\{[^}]*?"ratingCount"\s*:\s*([0-9]+)""")

  suspend fun rating(imdbId: String): Rating? {
    val id = normalize(imdbId) ?: return null
    cache[id]?.let { return it }
    val result =
      runCatching {
        val html = Net.get("https://www.imdb.com/title/$id/")
        val value = RATING_VALUE.find(html)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@runCatching null
        Rating(value, RATING_COUNT.find(html)?.groupValues?.get(1)?.toIntOrNull())
      }.getOrNull() ?: return null
    cache[id] = result
    return result
  }

  /** SC stores the id as "tt1234567"; tolerate a bare numeric id too. */
  private fun normalize(imdbId: String): String? {
    val trimmed = imdbId.trim()
    return when {
      trimmed.isBlank() -> null
      trimmed.startsWith("tt") && trimmed.length > 2 -> trimmed
      trimmed.all { it.isDigit() } -> "tt$trimmed"
      else -> null
    }
  }
}
