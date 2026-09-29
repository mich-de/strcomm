package com.s4me.tv.client.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.s4me.tv.engine.WatchProgressStore
import java.util.concurrent.TimeUnit

private const val UNIQUE_PERIODIC_NAME = "progress-sync"
private const val UNIQUE_ONE_SHOT_NAME = "progress-sync-now"
// WorkManager's own floor for periodic work is 15 minutes; this doesn't need to be more frequent
// than that — the one-shot kicked off at app start (see MainActivity) covers "just switched
// device and want it fresh right now" instead.
private const val SYNC_INTERVAL_MINUTES = 30L

/** "Sincronizza con la TV" — see [com.s4me.tv.engine.WatchProgressStore.mergeIfNewer] for how a
 *  pull and a push in either order converge safely, and :app's RemoteControlServer for the
 *  "/progress" endpoints this talks to. A silent no-op whenever the TV isn't reachable (different
 *  Wi-Fi, box off) — there's nothing actionable to tell the user, it just tries again next cycle. */
class ProgressSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    if (!ProgressSyncPreference.isEnabled(applicationContext)) return Result.success()
    val tv = discoverFirstTv(applicationContext) ?: return Result.success()

    val progressStore = WatchProgressStore(applicationContext)
    val client = ProgressSyncClient()

    // Pull first and merge in, then push this device's own state back — mergeIfNewer means the
    // order between the two devices doesn't matter, only which entry is actually newer per title.
    client.fetch(tv.host, tv.port).forEach { progressStore.mergeIfNewer(it) }
    client.push(tv.host, tv.port, progressStore.inProgress())

    return Result.success()
  }

  companion object {
    /** Idempotent — safe on every app start; KEEP means an already-scheduled interval isn't reset. */
    fun schedule(context: Context) {
      val request = PeriodicWorkRequestBuilder<ProgressSyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES).build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_NAME)
    }

    /** One immediate run — called at app start so opening the app on a different device than
     *  where you last watched picks up fresh progress right away, rather than waiting for the
     *  next periodic tick (up to [SYNC_INTERVAL_MINUTES] away). */
    fun syncNow(context: Context) {
      val request = OneTimeWorkRequestBuilder<ProgressSyncWorker>().build()
      WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_ONE_SHOT_NAME, ExistingWorkPolicy.REPLACE, request)
    }
  }
}
