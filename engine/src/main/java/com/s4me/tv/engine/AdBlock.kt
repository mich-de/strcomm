package com.s4me.tv.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Domain-based blocking for the WebView embed fallback (`:app`'s `WebViewPlayer`) — the one place
 * third-party HTML/JS actually runs, so the one place ad-network malvertising, tracking pixels and
 * scam redirects can reach the user. Native ExoPlayer HLS playback never loads third-party markup
 * and has no exposure here.
 *
 * Source: StevenBlack/hosts (the ads+malware unified list) — free, keyless, well-maintained, the
 * same technique Pi-hole/uBlock Origin use. Cached to a plain file (not SharedPreferences: the
 * list is a few MB of text, too big for XML-backed prefs) and refreshed at most once a week.
 * [SEED] covers the handful of biggest ad/tracker networks so blocking is live from first launch,
 * before [ensureLoaded] has fetched anything, or if the device is offline.
 */
object AdBlock {
  private const val LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
  private const val CACHE_FILE = "adblock_hosts.txt"
  private const val MIN_PLAUSIBLE_ENTRIES = 1000 // guards against caching a truncated/garbage fetch
  private val REFRESH_INTERVAL = TimeUnit.DAYS.toMillis(7)

  private val SEED =
    setOf(
      "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
      "googletagmanager.com", "googletagservices.com", "adnxs.com", "adsrvr.org", "adform.net",
      "taboola.com", "outbrain.com", "criteo.com", "scorecardresearch.com", "popads.net",
      "propellerads.com", "adcash.com", "exoclick.com", "juicyads.com", "adskeeper.co.uk",
      "mgid.com", "revcontent.com", "media.net", "hilltopads.net", "adsterra.com", "onclickmax.com",
      "popcash.net", "clickadu.com", "smartadserver.com", "pubmatic.com", "rubiconproject.com",
    )

  @Volatile private var blocked: Set<String> = SEED

  /** Loads the cached list (if any) synchronously-fast from disk, then refreshes over the network
   *  if the cache is missing or older than [REFRESH_INTERVAL]. Safe to call every time the WebView
   *  fallback opens — cheap no-op once a fresh cache is in memory. Never throws: a failed fetch
   *  just leaves [blocked] at [SEED] or the last good cache. */
  suspend fun ensureLoaded(context: Context) =
    withContext(Dispatchers.IO) {
      val file = File(context.applicationContext.filesDir, CACHE_FILE)
      val cacheIsFresh =
        if (file.exists()) {
          runCatching { parse(file.readText()) }.getOrNull()?.let { blocked = it }
          System.currentTimeMillis() - file.lastModified() < REFRESH_INTERVAL
        } else {
          false
        }
      if (cacheIsFresh) return@withContext
      runCatching {
        val text = Net.get(LIST_URL)
        val domains = parse(text)
        if (domains.size >= MIN_PLAUSIBLE_ENTRIES) {
          file.writeText(text)
          blocked = domains
        }
      }
    }

  private fun parse(hostsText: String): Set<String> =
    hostsText
      .lineSequence()
      .map { it.substringBefore('#').trim() }
      .filter { it.isNotEmpty() }
      .mapNotNull { line ->
        val parts = line.split(Regex("\\s+"))
        if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1].lowercase() else null
      }
      .toSet()

  /** True if [url]'s host is a known ad/tracker/malware domain, or a subdomain of one. */
  fun isBlocked(url: String): Boolean {
    var host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
    while (true) {
      if (host in blocked) return true
      val dot = host.indexOf('.')
      if (dot < 0) return false
      host = host.substring(dot + 1)
    }
  }
}
