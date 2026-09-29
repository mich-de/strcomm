package com.s4me.tv.engine.download

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A stream shape the MKV downloader deliberately doesn't handle (fMP4, byte-range or SAMPLE-AES
 *  segments, a non-H.264 video variant) — thrown up front, before anything is written, so the
 *  caller can say "formato non supportato" instead of producing a broken file. */
class UnsupportedStreamException(message: String) : Exception(message)

internal class HlsVariant(
  val url: String,
  val bandwidth: Long,
  val width: Int?,
  val height: Int?,
  val codecs: String?,
  val audioGroup: String?,
  val subtitleGroup: String?,
)

internal class HlsRendition(
  val type: String,
  val groupId: String,
  val name: String,
  val language: String?,
  val isDefault: Boolean,
  val url: String?,
)

internal class HlsMultivariantPlaylist(val variants: List<HlsVariant>, val renditions: List<HlsRendition>)

internal class HlsKey(val uri: String, val iv: ByteArray?)

internal class HlsSegment(val url: String, val durationSec: Double, val sequence: Long, val key: HlsKey?)

internal class HlsMediaPlaylist(val segments: List<HlsSegment>) {
  val durationSec: Double = segments.sumOf { it.durationSec }
}

/**
 * The subset of RFC 8216 the source's CDN actually serves (verified against live movie and episode
 * streams): a multivariant playlist of H.264 variants plus EXT-X-MEDIA audio/subtitle renditions,
 * and VOD media playlists of AES-128 MPEG-TS segments (one key, explicit IV) or single-file WebVTT.
 * Anything outside that is rejected rather than half-supported — see [UnsupportedStreamException].
 */
internal object HlsPlaylistParser {
  private val ATTRIBUTE = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

  fun isMultivariant(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

  fun parseMultivariant(text: String, baseUrl: String): HlsMultivariantPlaylist {
    val variants = mutableListOf<HlsVariant>()
    val renditions = mutableListOf<HlsRendition>()
    var streamInf: Map<String, String>? = null
    for (raw in text.lines()) {
      val line = raw.trim()
      when {
        line.startsWith("#EXT-X-STREAM-INF:") -> streamInf = attributes(line)
        line.startsWith("#EXT-X-MEDIA:") -> {
          val a = attributes(line)
          renditions +=
            HlsRendition(
              type = a["TYPE"].orEmpty(),
              groupId = a["GROUP-ID"].orEmpty(),
              name = a["NAME"].orEmpty(),
              language = a["LANGUAGE"],
              isDefault = a["DEFAULT"] == "YES",
              url = a["URI"]?.let { resolve(baseUrl, it) },
            )
        }
        line.isNotEmpty() && !line.startsWith("#") && streamInf != null -> {
          val a = streamInf
          val size = a["RESOLUTION"]?.split('x')
          variants +=
            HlsVariant(
              url = resolve(baseUrl, line),
              bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: 0,
              width = size?.getOrNull(0)?.toIntOrNull(),
              height = size?.getOrNull(1)?.toIntOrNull(),
              codecs = a["CODECS"],
              audioGroup = a["AUDIO"],
              subtitleGroup = a["SUBTITLES"],
            )
          streamInf = null
        }
      }
    }
    return HlsMultivariantPlaylist(variants, renditions)
  }

  fun parseMedia(text: String, baseUrl: String): HlsMediaPlaylist {
    val segments = mutableListOf<HlsSegment>()
    var sequence = 0L
    var key: HlsKey? = null
    var duration = 0.0
    for (raw in text.lines()) {
      val line = raw.trim()
      when {
        line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
        line.startsWith("#EXT-X-KEY:") -> {
          val a = attributes(line)
          key =
            when (val method = a["METHOD"]) {
              "NONE" -> null
              "AES-128" ->
                HlsKey(
                  uri = a["URI"]?.let { resolve(baseUrl, it) } ?: throw UnsupportedStreamException("chiave AES-128 senza URI"),
                  iv = a["IV"]?.let(::parseIv),
                )
              else -> throw UnsupportedStreamException("cifratura $method non supportata")
            }
        }
        line.startsWith("#EXT-X-MAP") -> throw UnsupportedStreamException("segmenti fMP4 non supportati")
        line.startsWith("#EXT-X-BYTERANGE") -> throw UnsupportedStreamException("segmenti byte-range non supportati")
        line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
        line.isNotEmpty() && !line.startsWith("#") -> {
          segments += HlsSegment(resolve(baseUrl, line), duration, sequence, key)
          sequence++
          duration = 0.0
        }
      }
    }
    return HlsMediaPlaylist(segments)
  }

  private fun attributes(line: String): Map<String, String> =
    ATTRIBUTE.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }

  // The CDN mixes absolute segment URLs with host-relative ones ("/storage/enc.key") — OkHttp's
  // resolver handles both, and is more lenient than java.net.URI with odd query characters.
  private fun resolve(base: String, reference: String): String = base.toHttpUrlOrNull()?.resolve(reference)?.toString() ?: reference

  private fun parseIv(hex: String): ByteArray {
    val digits = hex.removePrefix("0x").removePrefix("0X").takeLast(32).padStart(32, '0')
    return ByteArray(16) { i -> digits.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
  }
}
