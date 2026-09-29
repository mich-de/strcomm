package com.s4me.tv.engine

import org.json.JSONObject

/**
 * Netflix's own publicly-published weekly Top 10 charts (the "Tudum" press/PR site, not the
 * personalized app — that needs a login and isn't public data). Used ONLY as a list of trending
 * TITLE NAMES to seed the "I titoli del momento" row; every actual watchable item still resolves
 * and plays through [com.s4me.tv.engine.channels.StreamingCommunityChannel], exactly like the
 * curated Oscar list in BrowseViewModel — this is a ranking signal, not a content source.
 *
 * `https://www.netflix.com/it/` (the marketing landing page for logged-out visitors) was checked
 * first and carries no chart data at all — just a subscribe-now page. The actual chart lives at
 * `/tudum/top10/{country}` and ships fully server-rendered: the whole page's data is a normalized
 * GraphQL cache assigned to `netflix.reactContext.models.graphql` as a `JSON.parse('...')` call, so
 * this is one static-HTML GET per category, no login/API key/headless browser/JS execution needed.
 */
object NetflixTop10 {
  data class Entry(val rank: Int, val title: String, val isSeries: Boolean)

  private const val MOVIES_URL = "https://www.netflix.com/tudum/top10/italy"
  private const val SERIES_URL = "https://www.netflix.com/tudum/top10/italy/tv"
  private const val GRAPHQL_MARKER = "netflix.reactContext.models.graphql = JSON.parse('"
  private const val GRAPHQL_END = "');</script>"

  suspend fun fetchItaly(): List<Entry> {
    val movies = runCatching { fetchOne(MOVIES_URL, isSeries = false) }.getOrDefault(emptyList())
    val series = runCatching { fetchOne(SERIES_URL, isSeries = true) }.getOrDefault(emptyList())
    return movies + series
  }

  private suspend fun fetchOne(url: String, isSeries: Boolean): List<Entry> {
    val html = Net.get(url, referer = "https://www.netflix.com/")
    val markerAt = html.indexOf(GRAPHQL_MARKER)
    if (markerAt < 0) return emptyList()
    val start = markerAt + GRAPHQL_MARKER.length
    val end = html.indexOf(GRAPHQL_END, start)
    if (end < 0) return emptyList()
    val cache = JSONObject(unescapeJsString(html.substring(start, end))).optJSONObject("data") ?: return emptyList()

    // The normalized cache stores every entity under an opaque key and, in practice, duplicates
    // each Top10 item under more than one such key — dedup by rank within this single category.
    val seenRanks = mutableSetOf<Int>()
    val entries = mutableListOf<Entry>()
    val keys = cache.keys()
    while (keys.hasNext()) {
      val entity = cache.optJSONObject(keys.next()) ?: continue
      if (entity.optString("__typename") != "PulseTop10ItemEntity") continue
      val rank = entity.optJSONObject("top10")?.optInt("weeklyRank", -1) ?: -1
      if (rank < 1 || !seenRanks.add(rank)) continue
      val video = entity.optJSONObject("top10Video") ?: continue
      // A TV entry's own title is "Show Name: Season N" — parentShow carries the show's plain
      // name, which is what the site's own catalog actually lists (seasons aren't separate titles).
      val title =
        video.optJSONObject("parentShow")?.optString("title")?.takeIf { it.isNotBlank() }
          ?: video.optString("title").takeIf { it.isNotBlank() }
          ?: continue
      entries += Entry(rank, title, isSeries)
    }
    return entries.sortedBy { it.rank }
  }

  /** `netflix.reactContext.models.graphql` is assigned via `JSON.parse('...')` — a JS
   *  single-quoted string literal, which escapes differently from the HTML-attribute-embedded JSON
   *  this app parses elsewhere (see StreamingCommunityChannel's `extractDataPage`/`Scrape.unescape`,
   *  which un-escapes HTML entities like `&quot;`, not JS escapes like `\'`). A naive global
   *  `\'` → `'` replace corrupts sequences like `\\'` (an escaped backslash immediately followed by
   *  a real quote), so this walks the string once, character by character, like a small hand-rolled
   *  JS-string lexer. */
  private fun unescapeJsString(s: String): String {
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length) {
        val next = s[i + 1]
        if (next == 'u' && i + 5 < s.length) {
          out.append(s.substring(i + 2, i + 6).toInt(16).toChar())
          i += 6
          continue
        }
        out.append(
          when (next) {
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            else -> next // '\'', '"', '\\' all decode to themselves
          }
        )
        i += 2
        continue
      }
      out.append(c)
      i++
    }
    return out.toString()
  }
}
