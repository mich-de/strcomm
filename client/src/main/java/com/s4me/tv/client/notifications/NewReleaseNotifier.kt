package com.s4me.tv.client.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.s4me.tv.client.R
import com.s4me.tv.engine.StreamItem

private const val CHANNEL_ID = "new_releases"

/** Posts "nuova stagione disponibile" notifications — see [com.s4me.tv.engine.NewReleaseStore] for
 *  how a change is detected, and [NewReleaseWorker] for the periodic check that calls this. */
object NewReleaseNotifier {
  fun ensureChannel(context: Context) {
    // No SDK_INT gate here — :client's minSdk is already 26 (= O), the floor this needs.
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(CHANNEL_ID, "Nuove uscite", NotificationManager.IMPORTANCE_DEFAULT)
    channel.description = "Avvisa quando una serie nella tua lista ha una nuova stagione"
    manager.createNotificationChannel(channel)
  }

  fun notifyNewSeason(context: Context, item: StreamItem, notificationId: Int) {
    ensureChannel(context)
    // areNotificationsEnabled() alone covers every case that matters at runtime (denied
    // POST_NOTIFICATIONS, channel/app-level opt-out, pre-33 where the permission doesn't exist),
    // but lint's MissingPermission check specifically wants to see this exact
    // checkSelfPermission(...) == PERMISSION_GRANTED shape on API 33+ before it'll accept a call
    // to notify() — a plain try/catch around it doesn't satisfy the static check.
    val hasPermission =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!hasPermission || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
    val notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Nuova stagione disponibile")
        .setContentText(item.title)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    NotificationManagerCompat.from(context).notify(notificationId, notification)
  }
}
