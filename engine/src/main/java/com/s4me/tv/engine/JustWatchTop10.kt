package com.s4me.tv.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * JustWatch's own public "Streaming Charts" (justwatch.com/it/streaming-charts) — a cross-platform
 * popularity ranking built from JustWatch's own users' activity across ALL services, not just one
 * platform's catalog (unlike [NetflixTop10]). Used the exact same way: purely as a list of
 * trending TITLE NAMES to seed a row, resolved and played through this app's own channel — not a
 * content source in itself.
 *
 * Unlike Netflix's Tudum page, JustWatch is a Nuxt.js app: its server-rendered payload
 * (`__NUXT_DATA__`) is one flat JSON array using a compact reference-based format ("devalue" —
 * objects/arrays/strings that could repeat are stored once and referenced elsewhere by their index
 * into that same array; small primitives like plain numbers are inlined directly). This walks just
 * the handful of fields needed (rank + title) rather than implementing a general devalue decoder.
 * Verified live: the whole chain — the flat Apollo cache map (found by searching for its
 * "ROOT_QUERY" key, since its array position isn't stable across loads), the
 * `streamingCharts(...)` connection under it, each edge's rank + entity reference, and that
 * entity's localized `content(...).title` — round-trips correctly for both movies and shows.
 */
object JustWatchTop10 {
  data class Entry(val rank: Int, val title: String, val isSeries: Boolean)

  private const val MOVIES_URL = "https://www.justwatch.com/it/streaming-charts?t=movies"
  private const val SERIES_URL = "https://www.justwatch.com/it/streaming-charts?t=shows"
  private const val NUXT_MARKER = "id=\"__NUXT_DATA__\""

  suspend fun fetchItaly(): List<Entry> {
    val movies = runCatching { fetchOne(MOVIES_URL, isSeries = false) }.getOrDefault(emptyList())
    val series = runCatching { fetchOne(SERIES_URL, isSeries = true) }.getOrDefault(emptyList())
    return movies + series
  }

  private suspend fun fetchOne(url: String, isSeries: Boolean): List<Entry> {
    val html = Net.get(url, referer = "https://www.justwatch.com/")
    val arr = extractNuxtArray(html) ?: return emptyList()

    // The whole page's data is ONE flat Apollo cache map, holding both the special "ROOT_QUERY"
    // entry point and every entity ("Movie:tm123", "Show:ts456", ...) as sibling keys — finding it
    // by searching for that marker key (rather than hardcoding its array position, which isn't
    // stable across page loads) means this same map also resolves every entity reference below.
    val cache =
      (0 until arr.length()).asSequence().mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.has("ROOT_QUERY") }
        ?: return emptyList()
    val root = resolveObject(arr, cache.opt("ROOT_QUERY")) ?: return emptyList()
    val chartsKey = root.keys().asSequence().firstOrNull { it.startsWith("streamingCharts(") } ?: return emptyList()
    val chartsNode = resolveObject(arr, root.opt(chartsKey)) ?: return emptyList()
    val edges = resolveArray(arr, chartsNode.opt("edges")) ?: return emptyList()

    val entries = mutableListOf<Entry>()
    for (i in 0 until edges.length()) {
      val edge = resolveObject(arr, edges.opt(i)) ?: continue
      val chartInfo = resolveObject(arr, edge.opt("streamingChartInfo")) ?: continue
      val rank = chartInfo.optInt("rank", -1)
      if (rank < 1) continue
      val node = resolveObject(arr, edge.opt("node")) ?: continue
      val ref = resolveString(arr, node.opt("__ref")) ?: continue
      val entity = resolveObject(arr, cache.opt(ref)) ?: continue
      val contentKey = entity.keys().asSequence().firstOrNull { it.startsWith("content(") } ?: continue
      val content = resolveObject(arr, entity.opt(contentKey)) ?: continue
      val title = resolveString(arr, content.opt("title"))?.takeIf { it.isNotBlank() } ?: continue
      entries += Entry(rank, title, isSeries)
    }
    return entries.sortedBy { it.rank }
  }

  private fun extractNuxtArray(html: String): JSONArray? {
    val markerAt = html.indexOf(NUXT_MARKER)
    if (markerAt < 0) return null
    val tagEnd = html.indexOf('>', markerAt).takeIf { it >= 0 } ?: return null
    val end = html.indexOf("</script>", tagEnd)
    if (end < 0) return null
    return runCatching { JSONArray(html.substring(tagEnd + 1, end)) }.getOrNull()
  }

  /** Every field read above goes through one of these three "resolve if it's an index into [arr],
   *  else use the value as-is" helpers, since devalue inlines some values and indexes others. */
  private fun resolveObject(arr: JSONArray, ref: Any?): JSONObject? =
    when (ref) {
      is Int -> arr.optJSONObject(ref)
      is JSONObject -> ref
      else -> null
    }

  private fun resolveArray(arr: JSONArray, ref: Any?): JSONArray? =
    when (ref) {
      is Int -> arr.optJSONArray(ref)
      is JSONArray -> ref
      else -> null
    }

  private fun resolveString(arr: JSONArray, ref: Any?): String? =
    when (ref) {
      is Int -> arr.optString(ref, "").takeIf { it.isNotBlank() }
      is String -> ref
      else -> null
    }
}
