package com.s4me.tv.engine

import android.content.Context

private const val PREFS_NAME = "ratings"

/**
 * Thumbs up / down on a title (+1 / -1; absent = no opinion), kept on the device per profile. There is
 * no recommendation engine behind it — it is a personal marker, shown on the title page and usable to
 * sort or filter lists — so it is deliberately never sent anywhere.
 */
class RatingStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(ProfileScope.prefsName(context, PREFS_NAME), Context.MODE_PRIVATE)

  /** +1, -1, or 0 for no opinion. */
  fun get(contentId: String): Int = prefs.getInt(contentId, 0)

  /** Tapping the thumb you already gave clears it. Returns the resulting rating. */
  fun toggle(contentId: String, value: Int): Int {
    val next = if (get(contentId) == value) 0 else value.coerceIn(-1, 1)
    prefs.edit().apply { if (next == 0) remove(contentId) else putInt(contentId, next) }.apply()
    return next
  }
}
