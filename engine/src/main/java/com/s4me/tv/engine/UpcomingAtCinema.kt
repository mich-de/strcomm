package com.s4me.tv.engine

/**
 * MYmovies.it's "coming to Italian cinemas" teaser — the same site whose TV guide already backs
 * [NowOnItalianTV], reused for the same reason: server-rendered, no headless browser needed. Same
 * ranking-signal role as [NetflixTop10]/[JustWatchTop10]/[NowOnItalianTV]: a list of (date, title),
 * not a content source — [Channel.search] still resolves each title through the actual catalog.
 *
 * This is a THEATRICAL release calendar, not "coming to this app's catalog" — the source site
 * publishes no such list (verified: StreamingCommunityChannel.home()'s own sliders are only
 * trending/latest/top10). Most of these won't resolve through search yet — a film hitting cinemas
 * next week is nowhere near a streaming/piracy catalog yet — which is expected: unresolved entries
 * are just dropped, same as every other row here, and what's left tends to be re-releases of older
 * titles that ARE already in the catalog (verified live: "Avengers: Endgame" and "Cars" showed up
 * in this exact list, re-released theatrically). The row title in each app's HomeViewModel says
 * "al cinema", not "in arrivo su StrComm", so this stays honest about what it actually is.
 *
 * Lives in a small footer widget present on every mymovies.it page (verified against the real
 * `/prossimamente/` page), grouped as one date header followed by that date's film link(s):
 * ```
 * <li class="... mm-footer-title"><span ...>&nbsp;16 settembre 2026</span></li>
 * <li><span class="mm-small"><a href="https://www.mymovies.it/film/2026/the-invite/" title="The Invite...">...</a></span></li>
 * ```
 * Splitting on the date-header marker gives one chunk per date; every film link inside a chunk
 * (there can be more than one per date) belongs to that date, up until the next marker/chunk.
 */
object UpcomingAtCinema {
  data class Entry(val date: String, val title: String)

  private const val URL = "https://www.mymovies.it/prossimamente/"
  private const val MARKER = """mm-footer-title">"""
  private val DATE_RE = Regex("""^\s*<span[^>]*>&nbsp;([^<]+)</span>""")
  private val TITLE_RE = Regex("""<a href="https://www\.mymovies\.it/film/[^"]+" title="([^"]+)">""")

  suspend fun fetchUpcoming(): List<Entry> =
    runCatching { parse(Net.get(URL, referer = "https://www.mymovies.it/")) }.getOrDefault(emptyList())

  private fun parse(html: String): List<Entry> =
    html.split(MARKER).drop(1).flatMap { chunk ->
      val date = DATE_RE.find(chunk)?.groupValues?.get(1)?.trim() ?: return@flatMap emptyList()
      // This date's titles run up to the next date header, which is already this chunk's end (the
      // split above cut it there) — capping the scan window just keeps a stray huge tail chunk cheap.
      TITLE_RE.findAll(chunk.take(1200)).map { Entry(date, Scrape.unescape(it.groupValues[1]).trim()) }.toList()
    }
}
