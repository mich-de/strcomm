package com.s4me.tv.client.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.s4me.tv.engine.AvailabilityReminderStore
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.SiteTraffic
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext

private const val UNIQUE_WORK_NAME = "availability-check"
private const val CHECK_INTERVAL_HOURS = 12L

/**
 * Re-searches the catalogue for every "Avvisami quando è disponibile" reminder and notifies when
 * something matching has appeared, then drops that reminder. One search per reminder per run, through
 * [SiteTraffic]'s background lane like every other fill-in fetch, so a handful of reminders cost a
 * handful of requests twice a day. With no reminders left it unschedules itself.
 */
class AvailabilityWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val store = AvailabilityReminderStore(applicationContext)
    val reminders = store.all()
    if (reminders.isEmpty()) {
      cancel(applicationContext)
      return Result.success()
    }
    val channel = ChannelRegistry.all.firstOrNull() ?: return Result.success()
    withContext(SiteTraffic.Background) {
      reminders.forEachIndexed { index, reminder ->
        // null = the site couldn't be asked (network, a ban): keep the reminder for the next run.
        val results = runCatching { channel.searchOrNull(reminder.title) }.getOrNull() ?: return@forEachIndexed
        val hit =
          results.firstOrNull {
            (it.kind == ItemKind.MOVIE || it.kind == ItemKind.SERIES) && AvailabilityReminderStore.matches(reminder.title, it.title)
          } ?: return@forEachIndexed
        NewReleaseNotifier.notifyAvailable(applicationContext, hit, reminder.title, notificationId = reminder.title.hashCode() + index)
        store.remove(reminder.title)
      }
    }
    return Result.success()
  }

  companion object {
    /** Idempotent — safe on every app start and every time a reminder is added. */
    fun schedule(context: Context) {
      val request = PeriodicWorkRequestBuilder<AvailabilityWorker>(CHECK_INTERVAL_HOURS, TimeUnit.HOURS).build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
  }
}
