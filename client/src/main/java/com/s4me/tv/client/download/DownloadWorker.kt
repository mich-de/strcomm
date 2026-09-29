package com.s4me.tv.client.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.s4me.tv.client.MainActivity
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.Net
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.download.HlsMkvDownloader
import com.s4me.tv.engine.download.UnsupportedStreamException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json

private const val CHANNEL_ID = "downloads"
private const val KEY_ITEM = "item"
const val KEY_PROGRESS = "progress"
const val KEY_ERROR = "error"
private val json = Json { ignoreUnknownKeys = true }

/** Entry points for the "⬇ Scarica" button and the downloads screen. One unique work per title,
 *  so tapping twice never starts a second copy. */
object Downloads {
  const val TAG = "strcomm-download"
  const val TITLE_TAG_PREFIX = "title:"

  fun workName(contentId: String) = "download:$contentId"

  fun enqueue(context: Context, item: StreamItem) {
    val request =
      OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(workDataOf(KEY_ITEM to json.encodeToString(StreamItem.serializer(), trimmed(item))))
        .addTag(TAG)
        .addTag(TITLE_TAG_PREFIX + displayTitle(item)) // WorkInfo exposes tags but not input data
        .build()
    // KEEP only skips when a copy is still pending/running — after a failure this starts afresh.
    WorkManager.getInstance(context).enqueueUniqueWork(workName(item.url), ExistingWorkPolicy.KEEP, request)
  }

  fun cancel(context: Context, contentId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(workName(contentId))
  }

  /** Removes the file and its record. False when the file couldn't be deleted — e.g. it outlived a
   *  reinstall, which leaves MediaStore treating it as another app's file. */
  fun delete(context: Context, record: DownloadRecord): Boolean {
    val uri = Uri.parse(record.uri)
    val deleted =
      runCatching { if (uri.scheme == "file") File(uri.path!!).delete() else context.contentResolver.delete(uri, null, null) > 0 }
        .getOrDefault(false)
    DownloadsStore(context).remove(record.id)
    return deleted
  }
}

/** "Breaking Bad - S01E01 - Pilot" / "Inception (2010)" — notification title, file name, MKV title. */
fun displayTitle(item: StreamItem): String =
  if (item.kind == ItemKind.EPISODE && !item.seriesTitle.isNullOrBlank()) {
    buildString {
      append(item.seriesTitle)
      if (item.season != null && item.episode != null) append(" - S%02dE%02d".format(item.season, item.episode))
      if (item.title.isNotBlank() && item.title != item.seriesTitle) append(" - ").append(item.title)
    }
  } else {
    item.year?.takeIf { it.isNotBlank() }?.let { "${item.title} ($it)" } ?: item.title
  }

/** Drops the fields that only matter on the detail page — keeps WorkManager's 10 KB input limit
 *  and the downloads store small. */
private fun trimmed(item: StreamItem) =
  item.copy(plot = null, cast = null, director = null, genres = null, extra = null, trailerYoutubeId = null, progress = null)

/**
 * Downloads one title to `Movies/StrComm/<title>.mkv` via [HlsMkvDownloader]: best video, every
 * audio language, every subtitle track. Runs as a foreground (dataSync) worker with a cancellable
 * progress notification, so it survives leaving the app. The stream is resolved here, not by the
 * detail screen, so a retry or a queued start always gets a fresh token.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private val notificationId = id.hashCode()

  override suspend fun doWork(): Result {
    val item =
      inputData.getString(KEY_ITEM)?.let { runCatching { json.decodeFromString(StreamItem.serializer(), it) }.getOrNull() }
        ?: return Result.failure()
    val label = displayTitle(item)
    ensureChannel()
    // Throws only if the app was already in the background when the work started (Android 12+);
    // the download still runs, just without the foreground guarantee.
    runCatching { setForeground(foregroundInfo(label, null)) }

    val channel = ChannelRegistry.byId(item.channelId) ?: return failure(label, "Sorgente non disponibile")
    val playable =
      runCatching { channel.findVideos(item) }.getOrNull()?.firstOrNull { it.serverId == "hls" }
        ?: return failure(label, "Questo titolo non è scaricabile al momento")
    val downloader = HlsMkvDownloader(Net.client, mapOf("Referer" to (playable.referer ?: "https://vixcloud.co/")))
    val plan =
      try {
        downloader.plan(playable.url)
      } catch (e: CancellationException) {
        throw e
      } catch (e: UnsupportedStreamException) {
        return failure(label, "Formato non supportato (${e.message})")
      } catch (e: Exception) {
        return failure(label, "Errore di rete, riprova")
      }

    val needed = (plan.estimatedBytes * 1.1).toLong()
    val free =
      runCatching { StatFs((applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir).path).availableBytes }
        .getOrDefault(Long.MAX_VALUE)
    if (free < needed) return failure(label, "Spazio insufficiente: servono circa ${gigabytes(needed)}, liberi ${gigabytes(free)}")

    val target = OutputTarget.create(applicationContext, fileNameFor(label)) ?: return failure(label, "Impossibile creare il file")
    var lastPercent = -1
    var lastUpdate = 0L
    try {
      val result =
        target.openChannel().use { out ->
          downloader.download(plan, label, out) { fraction ->
            val percent = (fraction * 100).toInt()
            val now = System.currentTimeMillis()
            if (percent != lastPercent && now - lastUpdate >= 1_000) {
              lastPercent = percent
              lastUpdate = now
              setProgress(workDataOf(KEY_PROGRESS to fraction))
              runCatching { setForeground(foregroundInfo(label, percent)) }
            }
          }
        }
      target.publish()
      DownloadsStore(applicationContext)
        .put(
          DownloadRecord(
            id = item.url,
            item = trimmed(item),
            uri = target.uri.toString(),
            fileName = target.name,
            sizeBytes = result.bytesWritten,
            audioTracks = result.audioTracks,
            subtitleTracks = result.subtitleTracks,
            createdAt = System.currentTimeMillis(),
          )
        )
      notifyFinished(label, "Download completato")
      return Result.success()
    } catch (e: CancellationException) {
      target.delete()
      throw e
    } catch (e: Exception) {
      target.delete()
      return failure(label, if (e is IOException) "Download interrotto: problema di rete o di spazio" else "Download non riuscito")
    }
  }

  private fun failure(label: String, message: String): Result {
    notifyFinished(label, message)
    return Result.failure(workDataOf(KEY_ERROR to message))
  }

  private fun foregroundInfo(label: String, percent: Int?): ForegroundInfo {
    val notification =
      NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(label)
        .setContentText(if (percent == null) "Preparazione…" else "Download $percent%")
        .setProgress(100, percent ?: 0, percent == null)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Annulla", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
        .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(notificationId, notification)
    }
  }

  private fun notifyFinished(label: String, message: String) {
    val allowed =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!allowed) return
    val open =
      PendingIntent.getActivity(
        applicationContext,
        0,
        Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val notification =
      NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(label)
        .setContentText(message)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    // A different id from the foreground one: that notification is removed when the worker ends.
    NotificationManagerCompat.from(applicationContext).notify(notificationId + 1, notification)
  }

  private fun ensureChannel() {
    val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Download", NotificationManager.IMPORTANCE_LOW))
  }

  private fun gigabytes(bytes: Long) = "%.1f GB".format(bytes / 1_000_000_000.0)

  private fun fileNameFor(label: String) = label.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(150) + ".mkv"
}

/**
 * Where the .mkv goes. Android 10+: MediaStore `Movies/StrComm/` — visible to the gallery, VLC,
 * MX Player and file managers, no storage permission needed, hidden (IS_PENDING) until complete.
 * Android 8–9 (no RELATIVE_PATH/IS_PENDING yet): the app's own Movies folder, still readable by
 * file managers there.
 */
private class OutputTarget(val uri: Uri, val name: String, private val context: Context, private val file: File?) {
  fun openChannel(): FileChannel =
    if (file != null) {
      RandomAccessFile(file, "rw").channel
    } else {
      val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("file non apribile")
      ParcelFileDescriptor.AutoCloseOutputStream(pfd).channel
    }

  fun publish() {
    if (file == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
    }
  }

  fun delete() {
    runCatching { if (file != null) file.delete() else context.contentResolver.delete(uri, null, null) }
  }

  companion object {
    fun create(context: Context, name: String): OutputTarget? =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values =
          ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/x-matroska")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/StrComm")
            put(MediaStore.Video.Media.IS_PENDING, 1)
          }
        runCatching { context.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }
          .getOrNull()
          ?.let { OutputTarget(it, name, context, null) }
      } else {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let { File(it, "StrComm") }?.apply { mkdirs() }
        dir?.let {
          var file = File(it, name)
          var n = 1
          while (file.exists()) file = File(it, name.removeSuffix(".mkv") + " (${n++}).mkv")
          OutputTarget(Uri.fromFile(file), file.name, context, file)
        }
      }
  }
}
