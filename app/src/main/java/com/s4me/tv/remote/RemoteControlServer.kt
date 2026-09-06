package com.s4me.tv.remote

import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.StreamItem
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Local-LAN-only HTTP API for the phone companion app: search this app's own catalog, then hand
 *  a picked result back to [RemoteControlBridge] so MainNavigation opens it — same trust model as
 *  Chromecast/DIAL second-screen protocols (no auth beyond "same Wi-Fi"; nothing here does more
 *  than search+navigate, both of which a person could already do by hand with the TV remote). */
class RemoteControlServer(port: Int) : NanoHTTPD(port) {
  private val json = Json { ignoreUnknownKeys = true }

  override fun serve(session: IHTTPSession): Response =
    runCatching {
      when {
        session.method == Method.GET && session.uri == "/ping" ->
          jsonResponse(json.encodeToString(Handshake.serializer(), Handshake(RemoteControlProtocol.APP_NAME)))
        session.method == Method.GET && session.uri == "/search" -> handleSearch(session)
        session.method == Method.POST && session.uri == "/list" -> handleList(session)
        session.method == Method.POST && session.uri == "/play" -> handlePlay(session)
        session.method == Method.GET && session.uri == "/now-playing" -> handleNowPlaying()
        session.method == Method.POST && session.uri == "/control" -> handleControl(session)
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
      }
    }.getOrElse { e -> newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error") }

  // Same multi-channel fan-out SearchViewModel uses — kept in sync by calling the one shared
  // Channel.search(), not a second copy of the matching logic.
  private fun handleSearch(session: IHTTPSession): Response {
    val query = session.parameters["q"]?.firstOrNull().orEmpty()
    if (query.isBlank()) return jsonResponse("[]")
    val results =
      runBlocking(Dispatchers.IO) {
        coroutineScope {
          ChannelRegistry.all
            .map { channel -> async { runCatching { channel.search(query) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
        }
      }
    return jsonResponse(json.encodeToString(ListSerializer(StreamItem.serializer()), results))
  }

  // A series/season node's children (seasons, or that season's episodes) — same Channel.list()
  // BrowseScreen itself drills through, so a season card here reads exactly like the TV's own
  // ("Stagione 1 · 8 episodi") without the phone reimplementing that label logic.
  private fun handleList(session: IHTTPSession): Response {
    val item = decodeBody(session) ?: return jsonResponse("[]")
    val channel = ChannelRegistry.byId(item.channelId) ?: return jsonResponse("[]")
    val items =
      runBlocking(Dispatchers.IO) { runCatching { channel.list(item) }.getOrNull() }?.items.orEmpty()
    return jsonResponse(json.encodeToString(ListSerializer(StreamItem.serializer()), items))
  }

  // The phone only ever echoes back a MOVIE/EPISODE StreamItem this same server's /search or
  // /list just produced, so decoding it is safe without re-validating fields. Resolving to a
  // PLAYABLE source here (rather than just navigating to Detail/Browse) is what makes "Invia"
  // actually start playback on the TV instead of only opening its page.
  private fun handlePlay(session: IHTTPSession): Response {
    val item = decodeBody(session) ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad request")
    val channel =
      ChannelRegistry.byId(item.channelId)
        ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "unknown channel")
    val playable =
      runBlocking(Dispatchers.IO) { runCatching { channel.findVideos(item) }.getOrDefault(emptyList()) }.firstOrNull()
        ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "no playable source")
    RemoteControlBridge.submit(playable)
    return jsonResponse("""{"ok":true}""")
  }

  // Whatever PlayerScreen last published — null when nothing's on screen right now (including
  // while the WebView fallback player is active, which doesn't publish into this bridge at all).
  private fun handleNowPlaying(): Response {
    val status = PlaybackControlBridge.status.value
    return jsonResponse(status?.let { json.encodeToString(PlaybackStatus.serializer(), it) } ?: "null")
  }

  private fun handleControl(session: IHTTPSession): Response {
    val body = HashMap<String, String>()
    session.parseBody(body)
    val command =
      runCatching { PlaybackCommand.valueOf(body["postData"].orEmpty().trim()) }.getOrNull()
        ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad command")
    PlaybackControlBridge.send(command)
    return jsonResponse("""{"ok":true}""")
  }

  private fun decodeBody(session: IHTTPSession): StreamItem? {
    val body = HashMap<String, String>()
    session.parseBody(body)
    return runCatching { json.decodeFromString(StreamItem.serializer(), body["postData"].orEmpty()) }.getOrNull()
  }

  private fun jsonResponse(body: String): Response = newFixedLengthResponse(Response.Status.OK, "application/json", body)
}
