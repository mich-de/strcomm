package com.s4me.tv.engine

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * The one door every request to the catalogue site goes through — only the site: vixcloud (video),
 * TMDB/IMDb and the image CDN don't pass here.
 *
 * Why it exists: on 2026-09-28 the site started answering Cloudflare "error code: 1006" — an IP ban
 * set by its owner — to the home connection, then to every fresh address it was given: a new WindTre
 * IP after a modem restart, and NordVPN servers that worked at first ("si apre e poi mi banna
 * nuovamente"). An address banned minutes after first use means automated detection, and the app's
 * traffic read as a scraper's: one Home load fired ~80–100 requests within a few seconds (a catalogue
 * search per Netflix / JustWatch / TV-guide / cinema title, all at once, beside hero details and genre
 * rows), and a tap on an actor's name up to 120 detail pages more. A person in a browser makes a
 * handful.
 *
 * So the rationing lives here, not in each caller:
 * - at most [MAX_IN_FLIGHT] requests at once, starts at least [INTERACTIVE_SPACING_MS] apart;
 * - work nobody is waiting on runs under `withContext(SiteTraffic.Background)` — Home's chart and genre
 *   rows, hero enrichment, bulk list resolution, workers. Those go single file with a
 *   [BACKGROUND_GAP_MS] pause after each, and only one of them ever competes for a start slot, so a
 *   request someone IS waiting for never queues behind a row of prefetches.
 */
object SiteTraffic {
  /** Coroutine-context marker for the background lane — see [SiteTraffic]. */
  object Background : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<Background>
  }

  private const val MAX_IN_FLIGHT = 2
  private const val INTERACTIVE_SPACING_MS = 500L
  private const val BACKGROUND_GAP_MS = 1_500L

  private val inFlight = Semaphore(MAX_IN_FLIGHT)
  private val backgroundLane = Semaphore(1)
  private val clock = Mutex()

  // Far enough in the past that the first request of the process never waits (System.nanoTime()
  // has an arbitrary origin, so 0 would not do).
  private var lastStart = Long.MIN_VALUE / 2
  @Volatile private var lastBackgroundEnd = Long.MIN_VALUE / 2

  suspend fun <T> gate(block: suspend () -> T): T {
    if (currentCoroutineContext()[Background.Key] == null) return paced(block)
    return backgroundLane.withPermit {
      val breather = lastBackgroundEnd + BACKGROUND_GAP_MS - now()
      if (breather > 0) delay(breather)
      try {
        paced(block)
      } finally {
        lastBackgroundEnd = now()
      }
    }
  }

  /** Reserves the next start slot under [clock] but waits for it outside, so queued callers never
   *  hold the lock — then runs [block] within the in-flight limit. */
  private suspend fun <T> paced(block: suspend () -> T): T {
    val start = clock.withLock { maxOf(now(), lastStart + INTERACTIVE_SPACING_MS).also { lastStart = it } }
    val wait = start - now()
    if (wait > 0) delay(wait)
    return inFlight.withPermit { block() }
  }

  private fun now(): Long = System.nanoTime() / 1_000_000
}
