package com.s4me.tv.engine

import android.content.Context
import java.text.Normalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val PREFS_NAME = "availability_reminders"
private const val KEY_ENTRIES = "entries"
private const val MAX_ENTRIES = 20

@Serializable data class AvailabilityReminder(val title: String, val createdAt: Long)

/**
 * "Avvisami quando è disponibile": what a person searched for and didn't find. A periodic check
 * (`:client`'s AvailabilityWorker) searches the catalogue again for each and notifies when something
 * matching shows up. The Netflix mobile app has the same idea on its billboards and title pages
 * (`AddToRemindersCta`, `RemindMeButtonState`); here it hangs off the "nothing found" state, which is
 * where a catalogue that grows needs it. Kept per profile.
 */
class AvailabilityReminderStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(ProfileScope.prefsName(context, PREFS_NAME), Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  fun all(): List<AvailabilityReminder> =
    prefs.getString(KEY_ENTRIES, null)?.let { runCatching { json.decodeFromString(ListSerializer(AvailabilityReminder.serializer()), it) }.getOrNull() }.orEmpty()

  /** False when it was already there or the list is full. */
  fun add(title: String): Boolean {
    val clean = title.trim()
    val current = all()
    if (clean.isBlank() || current.size >= MAX_ENTRIES || current.any { fold(it.title) == fold(clean) }) return false
    persist(current + AvailabilityReminder(clean, System.currentTimeMillis()))
    return true
  }

  fun remove(title: String) = persist(all().filterNot { fold(it.title) == fold(title) })

  fun clear() = prefs.edit().remove(KEY_ENTRIES).apply()

  private fun persist(list: List<AvailabilityReminder>) {
    prefs.edit().putString(KEY_ENTRIES, json.encodeToString(ListSerializer(AvailabilityReminder.serializer()), list)).apply()
  }

  companion object {
    private val DIACRITICS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase().replace(NON_ALNUM, " ").trim()

    /** Whether [candidate] is what [wanted] was looking for: every word of the search appears in its title.
     *  Conservative on purpose — a notification for the wrong title is worse than a late one. */
    fun matches(wanted: String, candidate: String): Boolean {
      val words = fold(wanted).split(' ').filter { it.isNotEmpty() }
      val title = fold(candidate)
      return words.isNotEmpty() && words.all { title.contains(it) }
    }
  }
}
