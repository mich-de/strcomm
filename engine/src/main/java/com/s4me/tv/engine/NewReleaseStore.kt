package com.s4me.tv.engine

import android.content.Context

private const val PREFS_NAME = "new_release_tracker"
private const val KEY_SEASON_COUNTS = "season_counts"
private const val KEY_ENABLED = "enabled"

/**
 * The last known season count per watchlisted SERIES — how "Notifiche nuove uscite" (see the
 * periodic checker in :client) tells "this series just got a new season" from "no change since
 * last check". Deliberately tracks season count, not per-episode: a single network call per series
 * ([Channel.list] on the series item) rather than fetching every season's own episode list, which
 * would multiply the cost of a periodic background check by however many seasons each watchlisted
 * series already has.
 */
class NewReleaseStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  var enabled: Boolean
    get() = prefs.getBoolean(KEY_ENABLED, false)
    set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

  private fun load(): MutableMap<String, Int> =
    prefs.getString(KEY_SEASON_COUNTS, null)?.split("\n")?.mapNotNull { line ->
      val parts = line.split("=", limit = 2)
      val count = parts.getOrNull(1)?.toIntOrNull()
      if (parts.size == 2 && count != null) parts[0] to count else null
    }?.toMap()?.toMutableMap() ?: mutableMapOf()

  private fun persist(map: Map<String, Int>) {
    prefs.edit().putString(KEY_SEASON_COUNTS, map.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
  }

  /** Null the first time a series is seen (nothing to compare against yet — the caller should
   *  record the count without notifying). Otherwise the previously recorded season count. */
  fun lastSeasonCount(contentId: String): Int? = load()[contentId]

  fun recordSeasonCount(contentId: String, count: Int) {
    val map = load()
    map[contentId] = count
    persist(map)
  }

  /** Drops entries for series no longer being tracked (removed from the watchlist), so this
   *  doesn't grow forever. */
  fun pruneTo(contentIds: Set<String>) {
    val map = load()
    if (map.keys.retainAll(contentIds)) persist(map)
  }
}
