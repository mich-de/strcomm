package com.s4me.tv.engine

import android.content.Context
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One person's slice of the on-device data: their own progress, list, searches, ratings. */
@Serializable data class Profile(val id: String, val name: String, val colorIndex: Int = 0)

/**
 * Which profile's data this app reads and writes. Every *personal* store (progress, watchlist,
 * search history, watch stats, custom lists, new-release tracker, ratings) asks [prefsName] for its
 * SharedPreferences file name instead of using a fixed one, so switching profile swaps the whole set
 * at once — and the default profile keeps the file names that existed before profiles did, so
 * nothing already saved moves or is lost. Settings that belong to the device, not the person (theme,
 * TMDB key, downloads, caches) are deliberately not scoped.
 *
 * Switching takes effect for stores constructed afterwards, so the UI recreates its activity after
 * [switchTo]. The TV app never switches, so it always reads the default profile's files.
 */
object ProfileScope {
  const val DEFAULT_ID = "default"
  private const val PREFS = "profiles"
  private const val KEY_CURRENT = "current"
  private const val KEY_LIST = "list"
  private const val MAX_PROFILES = 6

  /** Base names of the preference files that hold personal data — what a profile owns. */
  private val PERSONAL =
    listOf("watch_progress", "watchlist", "search_history", "watch_stats", "custom_lists", "new_release_tracker", "ratings", "availability_reminders")

  private val json = Json { ignoreUnknownKeys = true }
  @Volatile private var cachedId: String? = null

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun currentId(context: Context): String =
    cachedId
      ?: (prefs(context).getString(KEY_CURRENT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ID).also { cachedId = it }

  /** The preference file name for [base] under the current profile. */
  fun prefsName(context: Context, base: String): String {
    val id = currentId(context)
    return if (id == DEFAULT_ID) base else "${base}__$id"
  }

  /** The default profile first, then the others in creation order. */
  fun all(context: Context): List<Profile> {
    val stored =
      prefs(context).getString(KEY_LIST, null)?.let { runCatching { json.decodeFromString(ListSerializer(Profile.serializer()), it) }.getOrNull() }.orEmpty()
    val default = stored.firstOrNull { it.id == DEFAULT_ID } ?: Profile(DEFAULT_ID, "Principale", 0)
    return listOf(default) + stored.filter { it.id != DEFAULT_ID }
  }

  fun current(context: Context): Profile = all(context).firstOrNull { it.id == currentId(context) } ?: all(context).first()

  fun canCreate(context: Context): Boolean = all(context).size < MAX_PROFILES

  fun create(context: Context, name: String): Profile? {
    val clean = name.trim().take(16)
    if (clean.isBlank() || !canCreate(context)) return null
    val profile = Profile(UUID.randomUUID().toString().take(8), clean, colorIndex = all(context).size)
    save(context, all(context) + profile)
    return profile
  }

  fun rename(context: Context, id: String, name: String) {
    val clean = name.trim().take(16).ifBlank { return }
    save(context, all(context).map { if (it.id == id) it.copy(name = clean) else it })
  }

  /** Removes a profile and its data. The default profile can't be deleted. */
  fun delete(context: Context, id: String) {
    if (id == DEFAULT_ID) return
    if (currentId(context) == id) switchTo(context, DEFAULT_ID)
    PERSONAL.forEach { context.applicationContext.deleteSharedPreferences("${it}__$id") }
    save(context, all(context).filter { it.id != id })
  }

  fun switchTo(context: Context, id: String) {
    if (all(context).none { it.id == id }) return
    prefs(context).edit().putString(KEY_CURRENT, id).apply()
    cachedId = id
  }

  /** Stores the whole list, the default profile included (so a rename of it survives). */
  private fun save(context: Context, profiles: List<Profile>) {
    prefs(context).edit().putString(KEY_LIST, json.encodeToString(ListSerializer(Profile.serializer()), profiles)).apply()
  }
}
