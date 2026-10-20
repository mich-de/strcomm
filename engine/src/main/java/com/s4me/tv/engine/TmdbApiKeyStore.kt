package com.s4me.tv.engine

import android.content.Context

private const val PREFS_NAME = "tmdb"
private const val KEY_API_KEY = "api_key"

/** Persists a user-supplied TMDB API key entered in Settings, overriding the build-time
 *  `BuildConfig.TMDB_API_KEY` (see [Tmdb]) without a rebuild — call [Tmdb.setApiKeyOverride] with
 *  [load]'s result once at startup, and again on every Settings save/clear. */
class TmdbApiKeyStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun load(): String? = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

  fun save(key: String?) {
    val trimmed = key?.trim()
    if (trimmed.isNullOrBlank()) prefs.edit().remove(KEY_API_KEY).apply() else prefs.edit().putString(KEY_API_KEY, trimmed).apply()
  }
}
