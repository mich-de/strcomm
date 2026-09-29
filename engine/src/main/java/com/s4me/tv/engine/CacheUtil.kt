package com.s4me.tv.engine

import android.content.Context
import java.io.File

/** Cache-size probing/clearing for the Settings screen's "Pulisci cache" — covers everything this
 *  app writes to Android's own cache areas (Coil's disk image cache lives here with no extra setup
 *  needed; ExoPlayer keeps no on-disk cache of its own, it's all in-memory). Deletes cacheDir /
 *  externalCacheDir *contents*, never the directories themselves — Android expects them to exist. */
object CacheUtil {
  fun sizeBytes(context: Context): Long = dirSize(context.cacheDir) + dirSize(context.externalCacheDir)

  fun clear(context: Context) {
    clearDir(context.cacheDir)
    clearDir(context.externalCacheDir)
  }

  private fun dirSize(dir: File?): Long =
    runCatching { dir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

  private fun clearDir(dir: File?) {
    runCatching { dir?.listFiles()?.forEach { it.deleteRecursively() } }
  }

  /** "1,3 GB" / "540 MB" / "12 KB" — always at least KB, this is a cache-size figure not a byte count. */
  fun formatSize(bytes: Long): String =
    when {
      bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
      bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
      else -> "%.0f KB".format((bytes / 1_000.0).coerceAtLeast(1.0))
    }
}
