package com.s4me.tv.engine

import android.app.Application
import android.content.Context

private const val PREFS_NAME = "search_history"
private const val KEY_QUERIES = "queries"
private const val MAX_HISTORY = 15

/** Most-recent-first list of past search terms, deduplicated case-insensitively and capped at
 *  [MAX_HISTORY]. SharedPreferences is plenty for a flat string list — no need to pull in a DB. */
class SearchHistoryStore(app: Application) {
  private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun load(): List<String> = prefs.getString(KEY_QUERIES, null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

  fun add(query: String) {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return
    val updated = (listOf(trimmed) + load().filterNot { it.equals(trimmed, ignoreCase = true) }).take(MAX_HISTORY)
    prefs.edit().putString(KEY_QUERIES, updated.joinToString("\n")).apply()
  }

  fun clear() {
    prefs.edit().remove(KEY_QUERIES).apply()
  }
}
