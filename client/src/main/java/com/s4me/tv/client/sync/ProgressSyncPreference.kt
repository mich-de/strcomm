package com.s4me.tv.client.sync

import android.content.Context

private const val PREFS_NAME = "progress_sync"
private const val KEY_ENABLED = "enabled"

/** Off by default — "Sincronizza con la TV" in Settings turns it on (see
 *  [ProgressSyncWorker.schedule]/[ProgressSyncWorker.cancel]). No pairing/host to remember: every
 *  sync re-discovers the TV over NSD, same as :mobile's remote does for its own connections. */
object ProgressSyncPreference {
  fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

  fun setEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
  }

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
