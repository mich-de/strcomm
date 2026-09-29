package com.s4me.tv.client.sync

import com.s4me.tv.engine.WatchProgress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Talks to :app's RemoteControlServer's "/progress" endpoints (its own source, package
 *  com.s4me.tv.remote) — same short-timeout, same-Wi-Fi-hop assumption as :mobile's TvClient,
 *  which this mirrors. */
class ProgressSyncClient {
  private val json = Json { ignoreUnknownKeys = true }
  private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

  suspend fun fetch(host: String, port: Int): List<WatchProgress> =
    withContext(Dispatchers.IO) {
      runCatching {
        http.newCall(Request.Builder().url("http://$host:$port/progress").build()).execute().use { response ->
          if (!response.isSuccessful) return@runCatching emptyList()
          json.decodeFromString(ListSerializer(WatchProgress.serializer()), response.body.string())
        }
      }.getOrDefault(emptyList())
    }

  suspend fun push(host: String, port: Int, entries: List<WatchProgress>): Boolean =
    withContext(Dispatchers.IO) {
      if (entries.isEmpty()) return@withContext true
      runCatching {
        val body = json.encodeToString(ListSerializer(WatchProgress.serializer()), entries).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("http://$host:$port/progress").post(body).build()
        http.newCall(request).execute().use { it.isSuccessful }
      }.getOrDefault(false)
    }
}
