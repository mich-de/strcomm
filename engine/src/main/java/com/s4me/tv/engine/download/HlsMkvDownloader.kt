package com.s4me.tv.engine.download

import com.s4me.tv.engine.HttpStatusException
import java.io.IOException
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What a download will contain, known from the playlists alone before any media is fetched. */
class HlsDownloadPlan internal constructor(
  internal val variant: HlsVariant,
  internal val video: HlsMediaPlaylist,
  internal val audio: List<Pair<HlsRendition, HlsMediaPlaylist>>,
  internal val subtitles: List<HlsRendition>,
) {
  val durationSec: Double
    get() = video.durationSec

  /** Rough final size from the advertised bitrates — good enough for a free-space check. */
  val estimatedBytes: Long
    get() = (((variant.bandwidth.takeIf { it > 0 } ?: FALLBACK_VIDEO_BPS) + audio.size * AUDIO_BPS) / 8.0 * durationSec).toLong()

  val videoHeight: Int?
    get() = variant.height

  val audioNames: List<String>
    get() = audio.map { it.first.name }

  val subtitleNames: List<String>
    get() = subtitles.map { it.name }

  private companion object {
    const val FALLBACK_VIDEO_BPS = 2_000_000L
    const val AUDIO_BPS = 192_000L
  }
}

class HlsDownloadResult(val bytesWritten: Long, val audioTracks: Int, val subtitleTracks: Int)

/**
 * Downloads an HLS title into ONE Matroska file with the best video variant plus EVERY audio
 * rendition and EVERY subtitle track the stream offers ("con tutte le lingue e sottotitoli come nel
 * flusso originale") — each keeping its language, name and default/forced flag, so a player picks
 * the same tracks the online stream would.
 *
 * Streaming, not stage-then-remux: segment N of the video and of every audio rendition are fetched
 * together (a few steps ahead, in parallel), decrypted, demuxed and written as soon as every track
 * has moved past them, so the only disk space needed is the finished file itself.
 */
class HlsMkvDownloader(private val http: OkHttpClient, private val headers: Map<String, String> = emptyMap()) {
  suspend fun plan(masterUrl: String): HlsDownloadPlan =
    withContext(Dispatchers.IO) {
      val masterText = fetchText(masterUrl)
      if (!HlsPlaylistParser.isMultivariant(masterText)) {
        val media = HlsPlaylistParser.parseMedia(masterText, masterUrl)
        return@withContext HlsDownloadPlan(HlsVariant(masterUrl, 0, null, null, null, null, null), media, emptyList(), emptyList())
      }
      val master = HlsPlaylistParser.parseMultivariant(masterText, masterUrl)
      val variant =
        master.variants.filter { it.codecs == null || it.codecs.contains("avc1") }.maxByOrNull { it.bandwidth }
          ?: throw UnsupportedStreamException("nessuna variante video H.264")
      val audioRenditions =
        master.renditions.filter { it.type == "AUDIO" && it.url != null && (variant.audioGroup == null || it.groupId == variant.audioGroup) }
      val subtitleRenditions =
        master.renditions.filter { it.type == "SUBTITLES" && it.url != null && (variant.subtitleGroup == null || it.groupId == variant.subtitleGroup) }
      coroutineScope {
        val video = async { mediaPlaylist(variant.url) }
        val audio = audioRenditions.map { r -> async { r to mediaPlaylist(r.url!!) } }
        HlsDownloadPlan(variant, video.await(), audio.awaitAll(), subtitleRenditions)
      }
    }

  suspend fun download(
    plan: HlsDownloadPlan,
    title: String,
    output: FileChannel,
    onProgress: suspend (Float) -> Unit = {},
  ): HlsDownloadResult =
    withContext(Dispatchers.IO) {
      onProgress(0f)
      // Subtitles are whole small files — fetch them all first. One broken track is dropped, not fatal.
      val subtitles =
        coroutineScope { plan.subtitles.map { r -> async { runCatching { r to fetchSubtitles(r) }.getOrNull() } }.awaitAll() }
          .filterNotNull()
          .filter { it.second.cues.isNotEmpty() }
      val sources = listOf(Source(plan.video, null)) + plan.audio.map { (r, p) -> Source(p, r) }
      val interleaver = Interleaver(title, output, plan.variant, sources, subtitles)
      val steps = sources.maxOf { it.playlist.segments.size }
      val total = sources.sumOf { it.playlist.segments.size }.coerceAtLeast(1)
      val keys = ConcurrentHashMap<String, ByteArray>()
      var done = 0
      coroutineScope {
        fun fetchStep(i: Int): Deferred<List<ByteArray?>> = async {
          sources.map { s -> async { s.playlist.segments.getOrNull(i)?.let { fetchSegment(it, keys) } } }.awaitAll()
        }
        val window = ArrayDeque<Deferred<List<ByteArray?>>>()
        var next = 0
        while (next < steps && window.size < PREFETCH_STEPS) window.addLast(fetchStep(next++))
        for (i in 0 until steps) {
          val data = window.removeFirst().await()
          if (next < steps) window.addLast(fetchStep(next++))
          ensureActive()
          data.forEachIndexed { k, bytes ->
            val source = sources[k]
            if (bytes != null) {
              source.demuxer.feed(bytes)
              done++
            }
            if (!source.finished && i >= source.playlist.segments.lastIndex) {
              source.demuxer.finish()
              source.finished = true
            }
          }
          interleaver.pump()
          onProgress(done.toFloat() / total)
        }
      }
      interleaver.finish()
    }

  private suspend fun mediaPlaylist(url: String): HlsMediaPlaylist = HlsPlaylistParser.parseMedia(fetchText(url), url)

  private suspend fun fetchSegment(segment: HlsSegment, keys: ConcurrentHashMap<String, ByteArray>): ByteArray {
    val raw = fetchBytes(segment.url)
    val key = segment.key ?: return raw
    val keyBytes = keys[key.uri] ?: fetchBytes(key.uri).also {
      if (it.size != 16) throw IOException("chiave AES di ${it.size} byte")
      keys[key.uri] = it
    }
    // No IV attribute → the segment's media sequence number, big-endian (RFC 8216 §5.2).
    val iv = key.iv ?: ByteArray(16).also { iv -> for (i in 0 until 8) iv[15 - i] = (segment.sequence ushr (8 * i)).toByte() }
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(raw)
  }

  /** A rendition URI may point at a subtitle media playlist (the CDN's case: one .vtt segment for
   *  the whole title) or straight at a .vtt file. */
  private suspend fun fetchSubtitles(rendition: HlsRendition): ParsedVtt {
    val url = rendition.url ?: return ParsedVtt(emptyList(), null)
    val body = fetchText(url)
    val files =
      if (body.trimStart().startsWith("WEBVTT")) listOf(body)
      else HlsPlaylistParser.parseMedia(body, url).segments.map { fetchText(it.url) }
    val parsed = files.map(WebVttParser::parse)
    // Segmented subtitles each carry their own timestamp map — rebase every file onto the first's.
    val offset = parsed.firstNotNullOfOrNull { it.ptsOffsetUs }
    val cues =
      parsed
        .flatMap { p ->
          val shift = (p.ptsOffsetUs ?: offset ?: 0L) - (offset ?: 0L)
          p.cues.map { SubtitleCue(it.startUs + shift, it.endUs + shift, it.text) }
        }
        .distinctBy { Triple(it.startUs, it.endUs, it.text) } // a cue spanning two segments appears in both
        .sortedBy { it.startUs }
    return ParsedVtt(cues, offset)
  }

  private suspend fun fetchText(url: String): String = String(fetchBytes(url), Charsets.UTF_8).removePrefix("﻿")

  private suspend fun fetchBytes(url: String): ByteArray {
    var attempt = 0
    while (true) {
      kotlin.coroutines.coroutineContext.ensureActive()
      try {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(request).execute().use { response ->
          if (response.isSuccessful) return response.body.bytes()
          if (response.code !in RETRYABLE_CODES) throw HttpStatusException(response.code, url)
          throw IOException("HTTP ${response.code} per $url")
        }
      } catch (e: IOException) {
        // A dropped connection or a CDN hiccup is worth a few backed-off retries; a 403/404 isn't.
        if (e is HttpStatusException || ++attempt >= MAX_ATTEMPTS) throw e
        delay(1_000L shl (attempt - 1))
      }
    }
  }

  internal class Source(val playlist: HlsMediaPlaylist, val rendition: HlsRendition?) {
    val demuxer = TsDemuxer()
    var finished = playlist.segments.isEmpty()
  }

  /**
   * Turns the per-track sample queues into one timestamp-ordered stream of blocks. Nothing is
   * written until every A/V track has shown its codec config (the Tracks header comes first);
   * after that, a sample is only written once every still-downloading track has queued something
   * at least as late — so no track can ever deliver an earlier sample after a later one was
   * written. Video is ordered by decode time (B-frames), everything else by presentation time.
   */
  private class Interleaver(
    private val title: String,
    output: FileChannel,
    private val variant: HlsVariant,
    private val sources: List<Source>,
    private val subtitles: List<Pair<HlsRendition, ParsedVtt>>,
  ) {
    private class Lane(val stream: ElementaryStream, val source: Source, val track: Int, val isVideo: Boolean)

    private class SubItem(val track: Int, val startUs: Long, val endUs: Long, val text: String)

    private val writer = MkvWriter(output)
    private var lanes: List<Lane>? = null
    private var ignored: List<ElementaryStream> = emptyList()
    private val pendingSubs = ArrayDeque<SubItem>()
    private var baseUs = 0L
    private var videoStarted = false
    private var pumps = 0
    private var audioTracks = 0

    fun pump() {
      pumps++
      if (lanes == null && !start(force = false)) return
      drain(final = false)
    }

    fun finish(): HlsDownloadResult {
      if (lanes == null && !start(force = true)) throw IOException("nessuna traccia audio/video nel flusso")
      drain(final = true)
      return HlsDownloadResult(writer.finish(), audioTracks, subtitles.size)
    }

    private fun start(force: Boolean): Boolean {
      val all = sources.flatMap { s -> s.demuxer.streams.map { s to it } }
      if (all.isEmpty()) return false
      val everyoneReady = all.all { it.second.isReady }
      // A track that still hasn't shown its codec config after a few segments is broken — drop it
      // rather than hold the whole file hostage.
      if (!everyoneReady && !force && pumps < MAX_WAIT_STEPS && !sources.all { it.finished }) return false

      val ready = all.filter { it.second.isReady }
      val video = ready.firstOrNull { it.second is H264Stream }
      val audios = ready.filter { it.second is AacStream }
      if (video == null && audios.isEmpty()) return false

      val specs = mutableListOf<MkvTrackSpec>()
      val newLanes = mutableListOf<Lane>()
      if (video != null) {
        val stream = video.second as H264Stream
        val info = stream.spsInfo
        val width = info?.width ?: variant.width ?: 0
        val height = info?.height ?: variant.height ?: 0
        // Non-square pixels (verified: the real streams carry an 801:800 SAR to hit exactly 12:5)
        // go into DisplayWidth, rounded to the nearest pixel rather than truncated.
        val displayWidth =
          if (info != null && info.sarWidth != info.sarHeight) ((width.toLong() * info.sarWidth + info.sarHeight / 2) / info.sarHeight).toInt()
          else width
        specs +=
          MkvTrackSpec(
            type = MkvTrackSpec.VIDEO,
            codecId = "V_MPEG4/ISO/AVC",
            codecPrivate = stream.decoderConfigurationRecord(),
            language = "und",
            name = null,
            isDefault = true,
            video = VideoTrackSpec(width, height, displayWidth, height),
          )
        newLanes += Lane(stream, video.first, specs.size, isVideo = true)
      }
      val defaultAudio = audios.indexOfFirst { it.first.rendition?.isDefault == true }.coerceAtLeast(0)
      audios.forEachIndexed { i, (source, s) ->
        val stream = s as AacStream
        specs +=
          MkvTrackSpec(
            type = MkvTrackSpec.AUDIO,
            codecId = "A_AAC",
            codecPrivate = stream.audioSpecificConfig,
            language = mkvLanguage(source.rendition?.language),
            name = source.rendition?.name,
            isDefault = i == defaultAudio,
            audio = AudioTrackSpec(stream.sampleRate, stream.channels),
          )
        newLanes += Lane(stream, source, specs.size, isVideo = false)
      }
      audioTracks = audios.size

      // Rebase everything on the earliest queued sample, so the file starts at 0 but keeps each
      // track's offset from the others (video starts 83 ms in on the real streams — B-frames).
      baseUs = newLanes.mapNotNull { lane -> lane.stream.samples.minOfOrNull { it.ptsUs } }.minOrNull() ?: 0L

      val subItems = mutableListOf<SubItem>()
      for ((rendition, vtt) in subtitles) {
        specs +=
          MkvTrackSpec(
            type = MkvTrackSpec.SUBTITLE,
            codecId = "S_TEXT/UTF8",
            codecPrivate = null,
            language = mkvLanguage(rendition.language),
            name = rendition.name,
            isDefault = rendition.isDefault,
            isForced = rendition.isForcedSubtitle(),
          )
        // No timestamp map (the CDN's case) = times already relative to the title start.
        val shift = vtt.ptsOffsetUs?.let { it - baseUs } ?: 0L
        vtt.cues.mapTo(subItems) { SubItem(specs.size, it.startUs + shift, it.endUs + shift, it.text) }
      }
      subItems.sortedBy { it.startUs }.forEach(pendingSubs::addLast)

      writer.start(title, specs)
      lanes = newLanes
      ignored = all.map { it.second }.filter { stream -> newLanes.none { it.stream === stream } }
      return true
    }

    private fun drain(final: Boolean) {
      val lanes = lanes ?: return
      ignored.forEach { it.samples.clear() } // dropped tracks must not pile up in memory
      while (true) {
        var watermark = Long.MAX_VALUE
        if (!final) for (lane in lanes) if (!lane.source.finished) watermark = minOf(watermark, lane.stream.lastQueuedDtsUs)
        var best: Lane? = null
        var bestDts = Long.MAX_VALUE
        for (lane in lanes) {
          val head = lane.stream.samples.firstOrNull() ?: continue
          if (head.dtsUs < bestDts) {
            best = lane
            bestDts = head.dtsUs
          }
        }
        val sub = pendingSubs.firstOrNull()
        if (sub != null) {
          val subDts = sub.startUs + baseUs
          if ((best == null || subDts <= bestDts) && (final || subDts <= watermark)) {
            pendingSubs.removeFirst()
            writer.writeSubtitle(sub.track, sub.startUs, sub.endUs, sub.text)
            continue
          }
        }
        if (best == null || (!final && bestDts > watermark)) break
        val sample = best.stream.samples.removeFirst()
        if (best.isVideo && !videoStarted) {
          if (!sample.keyframe) continue // a decoder can't start mid-GOP
          videoStarted = true
        }
        writer.writeFrame(best.track, sample.ptsUs - baseUs, sample.data, sample.keyframe)
      }
    }
  }

  private companion object {
    // The CDN throttles each connection (~1.1 MB/s measured on one 2 MB segment), so throughput
    // comes from fetching several steps at once: 6 in flight measured ~15% faster than 3, still no
    // more concurrent video requests than a browser opens per host.
    const val PREFETCH_STEPS = 6
    const val MAX_ATTEMPTS = 5
    const val MAX_WAIT_STEPS = 5
    val RETRYABLE_CODES = setOf(408, 429, 500, 502, 503, 504)
  }
}

/** ISO 639-2 code for Matroska's Language element. The CDN tags forced subtitles with made-up
 *  compounds ("ita-forced" on one title, "forced-ita" on another) — take the real language part. */
internal fun mkvLanguage(raw: String?): String {
  val tokens = raw.orEmpty().lowercase(Locale.ROOT).split('-', '_', ' ').filter { it.isNotEmpty() && it !in NON_LANGUAGE_TAGS }
  tokens.firstOrNull { t -> t.length == 3 && t.all { it in 'a'..'z' } }?.let { return it }
  tokens.firstOrNull { t -> t.length == 2 && t.all { it in 'a'..'z' } }?.let { two ->
    runCatching { Locale.forLanguageTag(two).isO3Language }.getOrNull()?.takeIf { it.length == 3 }?.let { return it }
  }
  return "und"
}

private val NON_LANGUAGE_TAGS = setOf("forced", "sdh", "cc")

/** The CDN never sets FORCED=YES; forced tracks are only recognisable by name/language
 *  ("Italian [Forced]", LANGUAGE="ita-forced" / "forced-ita"). */
internal fun HlsRendition.isForcedSubtitle(): Boolean =
  name.contains("forced", ignoreCase = true) || language.orEmpty().contains("forced", ignoreCase = true)
