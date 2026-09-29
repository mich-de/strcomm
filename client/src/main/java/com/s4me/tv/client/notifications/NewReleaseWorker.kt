package com.s4me.tv.client.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.NewReleaseStore
import com.s4me.tv.engine.SiteTraffic
import com.s4me.tv.engine.WatchlistStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext

private const val UNIQUE_WORK_NAME = "new-release-check"
private const val CHECK_INTERVAL_HOURS = 12L

/**
 * Periodic "did a watchlisted series get a new season" check — see [NewReleaseStore] for why this
 * compares season COUNT (one [com.s4me.tv.engine.Channel.list] call per series) rather than
 * per-episode. Runs whether or not the app is open, on Wi-Fi or mobile data alike (nothing here is
 * large enough to bother constraining to unmetered — the whole check is a handful of small JSON
 * fetches). Only ever notifies about a season count that GREW since a previously recorded value —
 * a series seen for the very first time just records its current count silently, so turning the
 * feature on doesn't immediately notify about every season every watchlisted show already has.
 */
class NewReleaseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val store = NewReleaseStore(applicationContext)
    if (!store.enabled) return Result.success()
    val watchlist = WatchlistStore(applicationContext)
    val series = watchlist.all().filter { it.kind == ItemKind.SERIES }
    store.pruneTo(series.mapNotNull { it.url.takeIf(String::isNotBlank) }.toSet())

    // Nobody is waiting on this: SiteTraffic's background lane, one series at a time.
    withContext(SiteTraffic.Background) {
      series.forEachIndexed { index, item ->
        val id = item.url.takeIf { it.isNotBlank() } ?: return@forEachIndexed
        val channel = ChannelRegistry.byId(item.channelId) ?: return@forEachIndexed
        // 0 seasons is a failed fetch, not a real count (the site lists no series without one) —
        // recording it would make the next successful check "discover" every season of every
        // watchlisted series at once, e.g. right after the site stops refusing this connection.
        val seasonCount = runCatching { channel.list(item).items.size }.getOrNull()?.takeIf { it > 0 } ?: return@forEachIndexed
        val previous = store.lastSeasonCount(id)
        store.recordSeasonCount(id, seasonCount)
        if (previous != null && seasonCount > previous) {
          NewReleaseNotifier.notifyNewSeason(applicationContext, item, notificationId = id.hashCode() + index)
        }
      }
    }
    return Result.success()
  }

  companion object {
    /** Idempotent — safe to call on every app start; WorkManager keeps the existing schedule
     *  ([ExistingPeriodicWorkPolicy.KEEP]) rather than restarting the interval from zero each time. */
    fun schedule(context: Context) {
      val request = PeriodicWorkRequestBuilder<NewReleaseWorker>(CHECK_INTERVAL_HOURS, TimeUnit.HOURS).build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
  }
}
