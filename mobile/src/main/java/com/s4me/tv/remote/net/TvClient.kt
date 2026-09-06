package com.s4me.tv.remote.net

import com.s4me.tv.engine.StreamItem
import com.s4me.tv.remote.Handshake
import com.s4me.tv.remote.PlaybackCommand
import com.s4me.tv.remote.PlaybackStatus
import com.s4me.tv.remote.RemoteControlProtocol
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Talks to RemoteControlServer on the TV app over plain HTTP on the LAN — see that class (in the
 *  TV app's own source, com.s4me.tv.remote) for the endpoints this mirrors. Short timeouts since
 *  everything here is a same-Wi-Fi hop, not a real internet call. */
class TvClient {
  private val json = Json { ignoreUnknownKeys = true }
  private val http =
    OkHttpClient.Builder()
      .connectTimeout(3, TimeUnit.SECONDS)
      .readTimeout(8, TimeUnit.SECONDS)
      .build()

  /** GET /ping — the TV's advertised name on success, null if the host is unreachable or isn't a
   *  StrComm TV at all. Used to vet a host before switching the UI over to it, and, while
   *  connected, to tell "the TV isn't playing anything" apart from "the TV fell off the Wi-Fi"
   *  (both of which /now-playing reports the same way, as null). */
  suspend fun handshake(host: String, port: Int): String? =
    withContext(Dispatchers.IO) {
      runCatching {
        http.newCall(Request.Builder().url("http://$host:$port/ping").build()).execute().use { response ->
          if (!response.isSuccessful) return@runCatching null
          json.decodeFromString(Handshake.serializer(), response.body.string()).name.takeIf { it == RemoteControlProtocol.APP_NAME }
        }
      }.getOrNull()
    }

  suspend fun search(host: String, port: Int, query: String): List<StreamItem> =
    withContext(Dispatchers.IO) {
      runCatching {
        val url = "http://$host:$port/search?q=${URLEncoder.encode(query, "UTF-8")}"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
          if (!response.isSuccessful) return@runCatching emptyList()
          json.decodeFromString(ListSerializer(StreamItem.serializer()), response.body.string())
        }
      }.getOrDefault(emptyList())
    }

  /** Seasons of a SERIES item, or episodes of a SEASON item — mirrors RemoteControlServer's
   *  /list, itself a thin wrapper over the same Channel.list() the TV's own BrowseScreen uses. */
  suspend fun list(host: String, port: Int, item: StreamItem): List<StreamItem> =
    withContext(Dispatchers.IO) {
      runCatching {
        val request = Request.Builder().url("http://$host:$port/list").post(itemBody(item)).build()
        http.newCall(request).execute().use { response ->
          if (!response.isSuccessful) return@runCatching emptyList()
          json.decodeFromString(ListSerializer(StreamItem.serializer()), response.body.string())
        }
      }.getOrDefault(emptyList())
    }

  /** Sends a MOVIE or EPISODE item to play — the TV resolves it to a playable source and starts
   *  it immediately, it does not just open that title's page. */
  suspend fun play(host: String, port: Int, item: StreamItem): Boolean =
    withContext(Dispatchers.IO) {
      runCatching {
        val request = Request.Builder().url("http://$host:$port/play").post(itemBody(item)).build()
        http.newCall(request).execute().use { it.isSuccessful }
      }.getOrDefault(false)
    }

  /** Null both when nothing is playing and when the request itself fails (TV unreachable) — the
   *  "now playing" bar treats both the same way: don't show it. */
  suspend fun nowPlaying(host: String, port: Int): PlaybackStatus? =
    withContext(Dispatchers.IO) {
      runCatching {
        http.newCall(Request.Builder().url("http://$host:$port/now-playing").build()).execute().use { response ->
          if (!response.isSuccessful) return@runCatching null
          json.decodeFromString(PlaybackStatus.serializer().nullable, response.body.string())
        }
      }.getOrNull()
    }

  suspend fun sendControl(host: String, port: Int, command: PlaybackCommand): Boolean =
    withContext(Dispatchers.IO) {
      runCatching {
        val body = command.name.toRequestBody("text/plain".toMediaType())
        val request = Request.Builder().url("http://$host:$port/control").post(body).build()
        http.newCall(request).execute().use { it.isSuccessful }
      }.getOrDefault(false)
    }

  private fun itemBody(item: StreamItem) =
    json.encodeToString(StreamItem.serializer(), item).toRequestBody("application/json".toMediaType())
}
