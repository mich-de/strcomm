package com.s4me.tv.engine

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

private const val LATEST_RELEASE_API = "https://api.github.com/repos/mich-de/strcomm/releases/latest"
private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class GhAsset(
  val name: String,
  @SerialName("browser_download_url") val downloadUrl: String,
  @SerialName("updated_at") val updatedAt: String,
  val size: Long,
)

@Serializable private data class GhRelease(@SerialName("tag_name") val tagName: String, val assets: List<GhAsset>)

/** What [UpdateChecker.check] found: the asset to offer, ready to hand to [UpdateChecker.download]. */
data class AppUpdate(val assetName: String, val downloadUrl: String, val sizeBytes: Long, val updatedAt: String)

/**
 * Checks the repo's latest GitHub release for a newer build of this app. Releases are versioned
 * ("salva questa versione 1.0.1"): a release tagged above the installed versionName is an update.
 * A release's APKs can also still be replaced in place without a new tag, so at the SAME version
 * the asset's own `updated_at` (ISO-8601 UTC, sorts as plain text) decides, against the last one
 * this device was offered — kept per installed version in SharedPreferences (device-level, not
 * per-profile), so a dismissed offer doesn't reappear and the first check after installing a
 * version, however it was installed, records the baseline instead of offering the build itself.
 * Assets are found by name prefix ("StrComm-Mobile-Tablet-"), since the name carries the version.
 */
object UpdateChecker {
  private const val PREFS = "update_checker"
  private const val KEY_SEEN = "seen_updated_at"

  /** Keyless GitHub REST call — null on any failure (offline, rate-limited, asset missing), never
   *  thrown to the caller; an update check failing silently is correct, not an error to surface. */
  suspend fun check(context: Context, assetPrefix: String): AppUpdate? =
    withContext(Dispatchers.IO) {
      val release =
        runCatching { json.decodeFromString<GhRelease>(Net.get(LATEST_RELEASE_API, headers = mapOf("Accept" to "application/vnd.github+json"))) }
          .getOrNull() ?: return@withContext null
      val asset = release.assets.firstOrNull { it.name.startsWith(assetPrefix) && it.name.endsWith(".apk") } ?: return@withContext null
      val update = AppUpdate(asset.name, asset.downloadUrl, asset.size, asset.updatedAt)
      val installed = appVersionName(context)
      val order = compareVersions(release.tagName.removePrefix("v"), installed)
      if (order > 0) return@withContext update
      if (order < 0) return@withContext null
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val key = "$KEY_SEEN:$installed"
      val seen = prefs.getString(key, null)
      if (seen == null) {
        prefs.edit().putString(key, asset.updatedAt).apply()
        return@withContext null
      }
      if (asset.updatedAt <= seen) null else update
    }

  /** Numeric dotted-version order ("1.0.10" > "1.0.9"); a non-numeric part counts as 0. */
  private fun compareVersions(a: String, b: String): Int {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
      val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
      if (d != 0) return d
    }
    return 0
  }

  fun markSeen(context: Context, update: AppUpdate) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("$KEY_SEEN:${appVersionName(context)}", update.updatedAt).apply()
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
