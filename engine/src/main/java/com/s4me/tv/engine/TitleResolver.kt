package com.s4me.tv.engine

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Resolves a title named somewhere else — an external chart (Netflix / JustWatch Top 10), tonight's
 * TV guide, the cinema calendar, the curated Oscar lists — to this site's own item, one catalogue
 * search each, and remembers the answer on disk, found or not.
 *
 * Those rows used to re-search every title on every load, all at once (~50 searches per Home launch):
 * the burst [SiteTraffic] exists to stop. Charts move weekly and the guide daily, so nearly every
 * answer is already known the next time. A found title is kept [FOUND_TTL_MS] (its id on the site
 * doesn't change); "not on the site" only [MISSING_TTL_MS], since the catalogue grows. A search that
 * couldn't be made at all (network, a ban) is never remembered.
 */
class TitleResolver private constructor(context: Context) {
  /** How many *uncached* searches one load may still spend — shared by a screen's rows in priority
   *  order, so the first launch after install (nothing known yet) stays a trickle instead of a burst;
   *  titles left over resolve on a later load. */
  class Budget(searches: Int) {
    private val left = AtomicInteger(searches)
    private val skipped = AtomicInteger(0)
    @Volatile private var unreachable = false

    /** Titles this budget has turned away so far — a row built while this grew is incomplete. */
    val deferred: Int
      get() = skipped.get()

    internal fun spend(): Boolean {
      val granted = !unreachable && left.getAndDecrement() > 0
      if (!granted) skipped.incrementAndGet()
      return granted
    }

    /** A search couldn't be made (network, a ban): stop spending for this load — every further one
     *  would fail the same way and only add to what a refused address sends. */
    internal fun siteUnreachable() {
      unreachable = true
    }
  }

  @Serializable private data class Entry(val item: StreamItem? = null, val at: Long)

  private val prefs = context.getSharedPreferences("title_resolver", Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  init {
    prune()
  }

  /** A chart entry → the site's MOVIE or SERIES of that name: exact title first, then a loose
   *  contains either way (chart and site spell a handful of titles slightly differently). Never a
   *  blind "first result" — the site's search is fuzzy full text, and a wrong title under a rank
   *  badge is worse than a gap. */
  suspend fun chartTitle(channel: Channel, title: String, isSeries: Boolean, budget: Budget? = null): StreamItem? {
    val kind = if (isSeries) ItemKind.SERIES else ItemKind.MOVIE
    return resolve(channel, key = "${kind.name}:${norm(title)}", query = title, budget = budget) { results ->
      val candidates = results.filter { it.kind == kind }
      candidates.firstOrNull { it.title.equals(title, ignoreCase = true) }
        ?: candidates.firstOrNull { it.title.contains(title, ignoreCase = true) || title.contains(it.title, ignoreCase = true) }
    }
  }

  /** [query]'s search results through [pick], remembered under [key] — for callers with their own
   *  matching rule (the Oscar lists match on title + year). */
  suspend fun resolve(
    channel: Channel,
    key: String,
    query: String,
    budget: Budget? = null,
    pick: (List<StreamItem>) -> StreamItem?,
  ): StreamItem? {
    val prefKey = "${channel.id}|$key"
    read(prefKey)?.let { return it.item }
    if (budget != null && !budget.spend()) return null
    // Never throws to the rows calling this (a malformed page counts as a failed search, not a crash).
    val results = runCatching { channel.searchOrNull(query) }.getOrNull()
    if (results == null) {
      budget?.siteUnreachable()
      return null
    }
    val hit = runCatching { pick(results) }.getOrElse { return null }
    prefs.edit().putString(prefKey, json.encodeToString(Entry.serializer(), Entry(hit, System.currentTimeMillis()))).apply()
    return hit
  }

  private fun read(prefKey: String): Entry? {
    val raw = prefs.getString(prefKey, null) ?: return null
    return runCatching { json.decodeFromString(Entry.serializer(), raw) }.getOrNull()?.takeUnless { expired(it) }
  }

  private fun expired(entry: Entry): Boolean =
    System.currentTimeMillis() - entry.at > if (entry.item != null) FOUND_TTL_MS else MISSING_TTL_MS

  private fun prune() {
    val stale =
      prefs.all.filter { (_, value) ->
        val entry = (value as? String)?.let { runCatching { json.decodeFromString(Entry.serializer(), it) }.getOrNull() }
        entry == null || expired(entry)
      }
    if (stale.isNotEmpty()) prefs.edit().apply { stale.keys.forEach(::remove) }.apply()
  }

  companion object {
    private const val FOUND_TTL_MS = 14L * 24 * 60 * 60 * 1000
    private const val MISSING_TTL_MS = 2L * 24 * 60 * 60 * 1000
    private val WHITESPACE = Regex("\\s+")

    @Volatile private var instance: TitleResolver? = null

    fun get(context: Context): TitleResolver =
      instance ?: synchronized(this) { instance ?: TitleResolver(context.applicationContext).also { instance = it } }

    private fun norm(title: String) = title.trim().lowercase().replace(WHITESPACE, " ")
  }
}
