package com.s4me.tv.remote.data

import android.content.Context
import com.s4me.tv.remote.discovery.DiscoveredTv

private const val PREFS_NAME = "strcomm_remote"
private const val KEY_HOST = "tv_host"
private const val KEY_PORT = "tv_port"
private const val KEY_NAME = "tv_name"

/** Remembers the last TV the phone actually reached, so reopening the app goes straight back to it
 *  instead of the discovery screen every time. Explicitly disconnecting ("Cambia") clears it —
 *  auto-reconnect is only meant to paper over the app being killed in the background, not to
 *  override a deliberate choice to switch TVs. SharedPreferences is plenty for three fields. */
class TvPreferences(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  var lastTv: DiscoveredTv?
    get() {
      val host = prefs.getString(KEY_HOST, null) ?: return null
      val port = prefs.getInt(KEY_PORT, -1).takeIf { it > 0 } ?: return null
      return DiscoveredTv(name = prefs.getString(KEY_NAME, null) ?: host, host = host, port = port)
    }
    set(value) {
      if (value == null) {
        prefs.edit().remove(KEY_HOST).remove(KEY_PORT).remove(KEY_NAME).apply()
      } else {
        prefs.edit().putString(KEY_HOST, value.host).putInt(KEY_PORT, value.port).putString(KEY_NAME, value.name).apply()
      }
    }
}
