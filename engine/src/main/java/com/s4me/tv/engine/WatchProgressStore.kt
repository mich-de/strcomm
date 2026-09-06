package com.s4me.tv.engine

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PREFS_NAME = "watch_progress"
private const val KEY_ENTRIES = "entries"
private const val MAX_ENTRIES = 20
// Below this fraction there's nothing meaningful to resume (barely started); at/above the near-end
// fraction the title is effectively finished and shouldn't clutter "Continua a guardare".
private const val MIN_RESUME_FRACTION = 0.02f
private const val FINISHED_FRACTION = 0.95f

@Serializable
data class WatchProgress(val item: StreamItem, val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
  val fraction: Float
    get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Persists "where you left off" per movie/episode, backed by SharedPreferences (a flat JSON blob —
 * this is a handful of small entries, no need for a database). Keyed by the content's stable
 * identity ([StreamItem.url] of the MOVIE/EPISODE, e.g. "8424|dark-matter|61161").
 */
class WatchProgressStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  private fun load(): MutableMap<String, WatchProgress> {
    val raw = prefs.getString(KEY_ENTRIES, null) ?: return mutableMapOf()
    return runCatching { json.decodeFromString<Map<String, WatchProgress>>(raw).toMutableMap() }.getOrDefault(mutableMapOf())
  }

  private fun persist(map: Map<String, WatchProgress>) {
    val trimmed = map.entries.sortedByDescending { it.value.updatedAt }.take(MAX_ENTRIES).associate { it.key to it.value }
    prefs.edit().putString(KEY_ENTRIES, json.encodeToString(trimmed)).apply()
  }

  /** Most-recently-watched first, only genuinely-resumable entries (started but not finished). */
  fun inProgress(): List<WatchProgress> =
    load().values
      .filter { it.durationMs > 0 && it.fraction in MIN_RESUME_FRACTION..FINISHED_FRACTION }
      .sortedByDescending { it.updatedAt }

  /** Saved resume position for a content id, or null if none / it was effectively finished. */
  fun positionFor(originId: String): Long? {
    val entry = load()[originId] ?: return null
    if (entry.durationMs > 0 && entry.fraction >= FINISHED_FRACTION) return null
    return entry.positionMs.takeIf { it > 0 }
  }

  /** Upsert progress. A near-finished position removes the entry instead (nothing left to resume). */
  fun save(item: StreamItem, positionMs: Long, durationMs: Long) {
    val id = item.url.takeIf { it.isNotBlank() } ?: return
    val map = load()
    if (durationMs > 0 && positionMs.toFloat() / durationMs.toFloat() >= FINISHED_FRACTION) {
      map.remove(id)
    } else {
      map[id] = WatchProgress(item = item.copy(progress = null), positionMs = positionMs, durationMs = durationMs, updatedAt = System.currentTimeMillis())
    }
    persist(map)
  }

  fun clearAll() {
    prefs.edit().remove(KEY_ENTRIES).apply()
  }

  fun remove(originId: String) {
    val map = load()
    if (map.remove(originId) != null) persist(map)
  }
}
