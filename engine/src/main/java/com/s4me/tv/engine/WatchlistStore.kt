package com.s4me.tv.engine

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PREFS_NAME = "watchlist"
private const val KEY_ENTRIES = "entries"
private const val MAX_ENTRIES = 100

@Serializable
private data class WatchlistEntry(val item: StreamItem, val addedAt: Long)

/**
 * "La mia lista": user-curated titles (movies and series), most recently added first. Same
 * flat-JSON-in-SharedPreferences approach as [WatchProgressStore] — a handful of small entries,
 * keyed by the content's stable identity ([StreamItem.url], e.g. "167|gomorra-la-serie").
 */
class WatchlistStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  private fun load(): MutableMap<String, WatchlistEntry> {
    val raw = prefs.getString(KEY_ENTRIES, null) ?: return mutableMapOf()
    return runCatching { json.decodeFromString<Map<String, WatchlistEntry>>(raw).toMutableMap() }.getOrDefault(mutableMapOf())
  }

  private fun persist(map: Map<String, WatchlistEntry>) {
    val trimmed = map.entries.sortedByDescending { it.value.addedAt }.take(MAX_ENTRIES).associate { it.key to it.value }
    prefs.edit().putString(KEY_ENTRIES, json.encodeToString(trimmed)).apply()
  }

  /** Most recently added first. */
  fun all(): List<StreamItem> = load().values.sortedByDescending { it.addedAt }.map { it.item }

  fun clearAll() {
    prefs.edit().remove(KEY_ENTRIES).apply()
  }

  fun contains(id: String): Boolean = load().containsKey(id)

  /** Adds when absent, removes when present. Returns true when the item is IN the list afterwards. */
  fun toggle(item: StreamItem): Boolean {
    val id = item.url.takeIf { it.isNotBlank() } ?: return false
    val map = load()
    val nowInList =
      if (map.remove(id) == null) {
        map[id] = WatchlistEntry(item = item.copy(progress = null), addedAt = System.currentTimeMillis())
        true
      } else {
        false
      }
    persist(map)
    return nowInList
  }
}
