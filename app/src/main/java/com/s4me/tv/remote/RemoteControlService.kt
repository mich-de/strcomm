package com.s4me.tv.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.s4me.tv.R

private const val CHANNEL_ID = "strcomm_remote_control"
private const val NOTIFICATION_ID = 1

/**
 * Hosts [RemoteControlServer] + [RemoteControlAdvertiser] as a foreground service instead of
 * tying them to MainActivity's lifecycle. Without this, backgrounding the app — switching the
 * TV's HDMI input away, pressing Home — got the whole process killed by Android within a short
 * while on this box (`pidof com.s4me.tv` came back empty, `mWakefulness=Awake` at the same
 * time — the box itself wasn't asleep, just this process), taking the local server down with it.
 * That already broke the phone remote whenever StrComm wasn't the foreground app; it made a
 * same-LAN webOS client — which depends on this server for everything, not just casting —
 * unusable the moment you switched away to actually look at it. A foreground service with a
 * persistent notification is the standard Android way to say "keep this running" and survives
 * exactly that kind of background kill.
 */
class RemoteControlService : Service() {
  private var server: RemoteControlServer? = null
  private var advertiser: RemoteControlAdvertiser? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    // Same well-known-port-first, OS-assigned-fallback logic MainActivity used to run directly.
    val started =
      runCatching { RemoteControlServer(RemoteControlProtocol.DEFAULT_PORT, applicationContext).apply { start() } }
        .getOrElse { runCatching { RemoteControlServer(0, applicationContext).apply { start() } }.getOrNull() }
    server = started
    if (started != null) {
      advertiser = RemoteControlAdvertiser(applicationContext).apply { start(started.listeningPort) }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

  override fun onDestroy() {
    advertiser?.stop()
    server?.stop()
    super.onDestroy()
  }

  private fun buildNotification(): Notification {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(CHANNEL_ID, "Telecomando e webOS", NotificationManager.IMPORTANCE_MIN).apply {
          description = "Server locale per il telecomando dal telefono e l'app webOS"
        }
      getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("StrComm")
      .setContentText("Server locale attivo per telecomando e webOS")
      .setSmallIcon(R.mipmap.ic_launcher)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .build()
  }

  companion object {
    fun start(context: Context) {
      ContextCompat.startForegroundService(context, Intent(context, RemoteControlService::class.java))
    }
  }
}
