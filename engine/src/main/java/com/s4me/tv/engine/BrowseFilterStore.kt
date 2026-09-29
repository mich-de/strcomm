package com.s4me.tv.engine

import android.content.Context

private const val PREFS_NAME = "browse_filter"
private const val KEY_EXTRA = "last_extra"

/**
 * Remembers the last-applied Browse filter — the same query-string-shaped `extra` [Channel.list]
 * already understands (`"type=movie&genre=4&year=2024"`) — across app restarts, so reopening
 * "Sfoglia" from its tab/button doesn't reset to unfiltered every time.
 *
 * Only the *generic* entry point should consult this. A deep link (Home's genre shortcuts, the
 * curated Oscar lists) already carries its own explicit `extra` on the [StreamItem] it navigates
 * with, and that must always win over whatever filter was last remembered — callers gate the read
 * on `root.extra.isNullOrBlank()`, same as they gate the write on `filterable`.
 */
class BrowseFilterStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun load(): String? = prefs.getString(KEY_EXTRA, null)

  fun save(extra: String?) {
    prefs.edit().putString(KEY_EXTRA, extra).apply()
  }
}
