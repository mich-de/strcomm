package com.s4me.tv.engine

/**
 * MYmovies.it's own "digitale terrestre, film di stasera" guide — every free Italian channel's
 * movie slots for tonight, already filtered to just films server-side (`?tipo=film`; the same
 * page without it mixes in news/game shows/series, which this app has no reliable way to tell
 * apart from a film in the raw markup). Same ranking-signal role as [NetflixTop10]/[JustWatchTop10]:
 * a list of (time, channel, title), not a content source — [Channel.search] still resolves each
 * title through the actual catalog, so a card here plays exactly like any other.
 *
 * Server-rendered (verified: real times appear straight in the fetched HTML, no JS/AJAX involved),
 * so a plain GET is enough — no headless browser needed, same as everything else in this engine.
 *
 * One entry looks like (whitespace trimmed for readability):
 * ```
 * <div class="mm-padding-8 orari-dettaglio" ...>
 *   ...calendario2...> OGGI <...        (date badge — skipped)
 *   ...calendario2...> 21:15 <...       (start time)
 *   <a href="https://www.mymovies.it/tv/la7cinema/">...calendario2...> LA7CINEMA <i .../> <...
 *   ...
 *   <a href="https://www.mymovies.it/film/2000/vertical-limit/" title="Recensione del film Vertical Limit">
 * ```
 * The channel badge and the film link both live inside one containing `.orari-dettaglio` block —
 * split the page on that marker to get one chunk per showing, then pull each field out of its chunk
 * independently (rather than one long regex across all three) so a badge in an unexpected order
 * doesn't misalign the others.
 */
object NowOnItalianTV {
  data class Entry(val time: String, val channel: String, val title: String, val slug: String)

  private const val URL = "https://www.mymovies.it/tv/digitaleterrestre/stasera/?tipo=film"
  private const val ENTRY_MARKER = """<div class="mm-padding-8 orari-dettaglio""""

  private val TIME_RE = Regex(""">\s*(\d{2}:\d{2})\s*<div class="clear5"""")
  private val BADGE_RE =
    Regex("""calendario2"[^>]*>\s*<div class="clear5"[^>]*></div>\s*([^<]*(?:<i[^>]*>.*?</i>)?[^<]*)<div class="clear5"""", RegexOption.DOT_MATCHES_ALL)
  private val SLUG_RE = Regex("""mymovies\.it/tv/([a-z0-9]+)/""")
  private val TITLE_RE = Regex(""""Recensione del film ([^"]+)"""")
  private val TAG_RE = Regex("<[^>]+>")

  /**
   * Standard Italian DTT LCN (logical channel number), keyed by MYmovies' own URL slug for that
   * channel — verified live (this engine's own fetches) for every key up to `la5`; the rest are
   * the well-known main-EPG numbers (dtti.it) with their most likely slug, unverified since none
   * of them showed a film on the nights this was built against. A slug not in this table sorts to
   * the end (stable, so still grouped/ordered among themselves) rather than breaking the row —
   * new or renumbered channels are far more likely here than in the top-9 broadcasters.
   */
  private val LCN =
    mapOf(
      "raiuno" to 1, "raidue" to 2, "raitre" to 3, "rete4" to 4, "canale5" to 5, "italia1" to 6, "la7" to 7,
      "tv8" to 8, "nove" to 9, "canale20" to 20, "rai4" to 21, "iris" to 22, "rai5" to 23, "raimovie" to 24,
      "raipremium" to 25, "cielo" to 26, "twentyseven" to 27, "tv2000" to 28, "la7cinema" to 29, "la5" to 30,
      "realtime" to 31, "cine34" to 34, "focus" to 35, "giallo" to 38, "topcrime" to 39, "boing" to 40,
      "raigulp" to 42, "raiyoyo" to 43, "cartoonito" to 46, "skytg24" to 50, "raistoria" to 54, "raiscuola" to 57,
    )

  suspend fun fetchTonight(): List<Entry> =
    runCatching { parse(Net.get(URL, referer = "https://www.mymovies.it/")) }
      .getOrDefault(emptyList())
      .sortedBy { LCN[it.slug] ?: Int.MAX_VALUE }

  private fun parse(html: String): List<Entry> =
    html.split(ENTRY_MARKER).drop(1).mapNotNull { chunk ->
      val time = TIME_RE.find(chunk)?.groupValues?.get(1) ?: return@mapNotNull null
      val slug = SLUG_RE.find(chunk)?.groupValues?.get(1) ?: return@mapNotNull null
      val badges =
        BADGE_RE.findAll(chunk).map { Scrape.unescape(TAG_RE.replace(it.groupValues[1], "")).trim() }.toList()
      val channel = badges.firstOrNull { it.isNotBlank() && it != "OGGI" && !Regex("""^\d{2}:\d{2}$""").matches(it) } ?: return@mapNotNull null
      val title = TITLE_RE.find(chunk)?.groupValues?.get(1)?.let { Scrape.unescape(it).trim() } ?: return@mapNotNull null
      Entry(time, channel, title, slug)
    }
}
