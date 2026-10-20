package com.s4me.tv.client.download

import android.content.Context
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PREFS_NAME = "downloads"
private const val KEY_RECORDS = "records"

/** A finished download: the .mkv's location plus the MOVIE/EPISODE it came from, so it can be
 *  played offline through the normal player with watch progress still keyed on the content id. */
@Serializable
data class DownloadRecord(
  val id: String,
  val item: StreamItem,
  val uri: String,
  val fileName: String,
  val sizeBytes: Long,
  val audioTracks: Int,
  val subtitleTracks: Int,
  val createdAt: Long,
) {
  /** The PLAYABLE the player needs — same shape findVideos() produces, pointing at the local file. */
  fun playable(): StreamItem =
    item.copy(title = "Download", kind = ItemKind.PLAYABLE, url = uri, serverId = LOCAL_SERVER_ID, referer = null, extra = null, contentTitle = item.title, originId = item.url)
}

/** serverId of a PLAYABLE backed by a downloaded file rather than a stream. */
const val LOCAL_SERVER_ID = "local"

class DownloadsStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  companion object {
    private val _changes = MutableStateFlow(0)

    /** Bumped on every write — the worker adds records from a background thread, so screens
     *  collect this to re-read instead of caching a stale list. */
    val changes: StateFlow<Int> = _changes.asStateFlow()
  }

  private fun load(): MutableMap<String, DownloadRecord> {
    val raw = prefs.getString(KEY_RECORDS, null) ?: return mutableMapOf()
    return runCatching { json.decodeFromString<Map<String, DownloadRecord>>(raw).toMutableMap() }.getOrDefault(mutableMapOf())
  }

  private fun persist(map: Map<String, DownloadRecord>) {
    prefs.edit().putString(KEY_RECORDS, json.encodeToString(map)).apply()
    _changes.update { it + 1 }
  }

  /** Newest first. */
  fun all(): List<DownloadRecord> = load().values.sortedByDescending { it.createdAt }

  fun get(id: String): DownloadRecord? = load()[id]

  fun put(record: DownloadRecord) {
    val map = load()
    map[record.id] = record
    persist(map)
  }

  fun remove(id: String) {
    val map = load()
    if (map.remove(id) != null) persist(map)
  }
}
