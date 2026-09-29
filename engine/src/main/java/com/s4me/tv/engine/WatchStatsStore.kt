package com.s4me.tv.engine

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PREFS_NAME = "watch_stats"
private const val KEY_COMPLETED = "completed"
private const val COMPLETED_FRACTION = 0.95f
private const val MAX_ENTRIES = 2000

@Serializable
private data class CompletedEntry(val title: String, val isSeries: Boolean, val durationMs: Long, val completedAt: Long)

data class WatchStatsSummary(val titlesCompleted: Int, val moviesCompleted: Int, val episodesCompleted: Int, val totalMs: Long)

/**
 * A permanent record of finished viewings, for the "Statistiche di visione" section in Settings.
 * [WatchProgressStore] can't answer this on its own — the moment a title is finished it's REMOVED
 * from there (its only job is "where do I resume"), so nothing kept a history until now.
 *
 * Keyed by content id, same as [WatchProgressStore]: a rewatch just refreshes `completedAt` on the
 * same key rather than inflating the count, and — deliberately — calling [recordIfCompleted]
 * repeatedly for a title that's already past the line (a player's periodic *and* on-exit saves can
 * each cross it) is harmless for the same reason: same key, same overwrite, no double-count. Total
 * watch time is approximated as the summed *duration* of completed titles, not actual elapsed
 * wall-clock viewing time (which would need much fussier incremental tracking, fragile against
 * seeking) — an honest, simple number: "content you've finished adds up to about X hours".
 */
class WatchStatsStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  private fun load(): MutableMap<String, CompletedEntry> {
    val raw = prefs.getString(KEY_COMPLETED, null) ?: return mutableMapOf()
    return runCatching { json.decodeFromString<Map<String, CompletedEntry>>(raw).toMutableMap() }.getOrDefault(mutableMapOf())
  }

  private fun persist(map: Map<String, CompletedEntry>) {
    val trimmed = map.entries.sortedByDescending { it.value.completedAt }.take(MAX_ENTRIES).associate { it.key to it.value }
    prefs.edit().putString(KEY_COMPLETED, json.encodeToString(trimmed)).apply()
  }

  /** Call from wherever progress is already being saved — a no-op unless [positionMs]/[durationMs]
   *  actually cross the "finished" line (matching [WatchProgressStore]'s own threshold). */
  fun recordIfCompleted(item: StreamItem, positionMs: Long, durationMs: Long) {
    val id = item.url.takeIf { it.isNotBlank() } ?: return
    if (durationMs <= 0 || positionMs.toFloat() / durationMs.toFloat() < COMPLETED_FRACTION) return
    val map = load()
    map[id] =
      CompletedEntry(
        title = item.contentTitle ?: item.seriesTitle ?: item.title,
        isSeries = item.kind == ItemKind.EPISODE,
        durationMs = durationMs,
        completedAt = System.currentTimeMillis(),
      )
    persist(map)
  }

  fun summary(): WatchStatsSummary {
    val entries = load().values
    return WatchStatsSummary(
      titlesCompleted = entries.size,
      moviesCompleted = entries.count { !it.isSeries },
      episodesCompleted = entries.count { it.isSeries },
      totalMs = entries.sumOf { it.durationMs },
    )
  }

  fun clearAll() {
    prefs.edit().remove(KEY_COMPLETED).apply()
  }

  /** Raw underlying blob, opaque to callers — see [BackupManager]. */
  fun exportRaw(): String? = prefs.getString(KEY_COMPLETED, null)

  fun importRaw(raw: String?) {
    if (raw != null) prefs.edit().putString(KEY_COMPLETED, raw).apply() else clearAll()
  }
}
