package com.s4me.tv.engine

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

private const val RELEASES_API = "https://api.github.com/repos/mich-de/strcomm/releases/tags/v1.0.0"
private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class GhAsset(
  val name: String,
  @SerialName("browser_download_url") val downloadUrl: String,
  @SerialName("updated_at") val updatedAt: String,
  val size: Long,
)

@Serializable private data class GhRelease(val assets: List<GhAsset>)

/** What [UpdateChecker.check] found: the asset to offer, ready to hand to [UpdateChecker.download]. */
data class AppUpdate(val assetName: String, val downloadUrl: String, val sizeBytes: Long, val updatedAt: String)

/**
 * Checks this app's own asset on the GitHub release for an update. "Versions never move" here —
 * every app stays `1.0.0` forever and the `v1.0.0` release's APKs are replaced in place instead —
 * so there is no version number to compare, only the asset's own `updated_at` timestamp (an
 * ISO-8601 UTC string, which sorts correctly as plain text). The last timestamp a device has
 * already been offered is kept in SharedPreferences (device-level, not per-profile — an update
 * notice isn't personal data) so a dismissed offer doesn't reappear on every check, and a fresh
 * install's very first check never announces an "update" for whatever was already live when it
 * was built.
 */
object UpdateChecker {
  private const val PREFS = "update_checker"
  private const val KEY_SEEN = "seen_updated_at"

  /** Keyless GitHub REST call — null on any failure (offline, rate-limited, asset renamed), never
   *  thrown to the caller; an update check failing silently is correct, not an error to surface. */
  suspend fun check(context: Context, assetName: String): AppUpdate? =
    withContext(Dispatchers.IO) {
      val release =
        runCatching { json.decodeFromString<GhRelease>(Net.get(RELEASES_API, headers = mapOf("Accept" to "application/vnd.github+json"))) }
          .getOrNull() ?: return@withContext null
      val asset = release.assets.firstOrNull { it.name == assetName } ?: return@withContext null
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val seen = prefs.getString(KEY_SEEN, null)
      if (seen == null) {
        // First check this device has ever made: whatever is live right now IS this install —
        // record it as the baseline instead of announcing it as new.
        prefs.edit().putString(KEY_SEEN, asset.updatedAt).apply()
        return@withContext null
      }
      if (asset.updatedAt <= seen) return@withContext null
      AppUpdate(asset.name, asset.downloadUrl, asset.size, asset.updatedAt)
    }

  /** Call once an update has been shown to the user (accepted or dismissed either way), so the
   *  same build isn't offered again next check — re-nagging every launch about something already
   *  seen would be worse than not checking at all. */
  fun markSeen(context: Context, update: AppUpdate) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SEEN, update.updatedAt).apply()
  }

  /** Streams [update]'s APK into `context.cacheDir/updates/<assetName>`, overwriting any previous
   *  download there. [onProgress] gets 0f..1f (or -1f if the response carries no Content-Length).
   *  The caller turns the returned [File] into an install Intent — that part needs a FileProvider,
   *  which is app-specific, so it isn't done here. */
  suspend fun download(context: Context, update: AppUpdate, onProgress: suspend (Float) -> Unit = {}): File =
    withContext(Dispatchers.IO) {
      val dir = File(context.cacheDir, "updates").apply { mkdirs() }
      val out = File(dir, update.assetName)
      val req = Request.Builder().url(update.downloadUrl).build()
      Net.client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw HttpStatusException(resp.code, update.downloadUrl)
        val body = resp.body
        val total = body.contentLength()
        var done = 0L
        body.byteStream().use { input ->
          out.outputStream().use { output ->
            val buf = ByteArray(64 * 1024)
            while (true) {
              val n = input.read(buf)
              if (n < 0) break
              output.write(buf, 0, n)
              done += n
              onProgress(if (total > 0) done.toFloat() / total else -1f)
            }
          }
        }
      }
      out
    }
}
