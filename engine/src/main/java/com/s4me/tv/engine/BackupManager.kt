package com.s4me.tv.engine

import android.app.Application
import org.json.JSONObject

private const val BACKUP_VERSION = 1

/**
 * Export/import for the stores that hold real user data — deliberately NOT the small preference
 * stores ([ThemePreference]-equivalents, [TmdbApiKeyStore], [BrowseFilterStore]): those are
 * trivial to reconfigure and not worth round-tripping through a backup file, unlike a watchlist or
 * viewing history built up over months.
 *
 * Each store already persists as one opaque string blob ([WatchProgressStore], [WatchlistStore]
 * and [WatchStatsStore] as JSON, [SearchHistoryStore] as newline-joined text) — this wraps those
 * four blobs UNPARSED in one small JSON envelope. Deliberately opaque: this file doesn't need to
 * know any store's internal shape, so a store's own serialization can change later without this
 * needing to follow.
 */
object BackupManager {
  fun export(app: Application): String {
    val obj = JSONObject()
    obj.put("version", BACKUP_VERSION)
    obj.put("exportedAt", System.currentTimeMillis())
    obj.put("watchProgress", WatchProgressStore(app).exportRaw())
    obj.put("watchlist", WatchlistStore(app).exportRaw())
    obj.put("searchHistory", SearchHistoryStore(app).exportRaw())
    obj.put("watchStats", WatchStatsStore(app).exportRaw())
    return obj.toString(2)
  }

  /** Returns true on a recognisable backup file (carries our "version" key) after restoring every
   *  store it mentions; false — leaving every store untouched — for anything else, so an
   *  unrelated or garbled file picked by mistake can't silently wipe real data. */
  fun import(app: Application, json: String): Boolean {
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return false
    if (!obj.has("version")) return false
    WatchProgressStore(app).importRaw(obj.optStringOrNull("watchProgress"))
    WatchlistStore(app).importRaw(obj.optStringOrNull("watchlist"))
    SearchHistoryStore(app).importRaw(obj.optStringOrNull("searchHistory"))
    WatchStatsStore(app).importRaw(obj.optStringOrNull("watchStats"))
    return true
  }

  private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
}
