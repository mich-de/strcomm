package com.s4me.tv.client.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private const val PREFS_NAME = "theme"
private const val KEY_MODE = "mode"

/**
 * Manual light/dark override. SYSTEM (default) follows the device setting, same as before this
 * existed; LIGHT/DARK pin it regardless. A process-wide singleton, same shape as the TV app's
 * PlaybackControlBridge/RemoteControlBridge — since the setter lives in SettingsScreen but the
 * reader is the theme root wrapping the whole nav graph in MainActivity, with no direct
 * parent-child composition link between the two.
 */
object ThemePreference {
  private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
  val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

  private var prefs: SharedPreferences? = null

  /** Call once, early (MainActivity.onCreate before setContent), to restore the saved choice. */
  fun load(context: Context) {
    val p = prefs(context)
    _mode.value = runCatching { ThemeMode.valueOf(p.getString(KEY_MODE, null) ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
  }

  fun set(context: Context, mode: ThemeMode) {
    _mode.value = mode
    prefs(context).edit().putString(KEY_MODE, mode.name).apply()
  }

  private fun prefs(context: Context): SharedPreferences =
    prefs ?: context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs = it }
}
