package com.s4me.tv.engine.download

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal class VideoTrackSpec(val width: Int, val height: Int, val displayWidth: Int, val displayHeight: Int)

internal class AudioTrackSpec(val sampleRate: Int, val channels: Int)

internal class MkvTrackSpec(
  val type: Int,
  val codecId: String,
  val codecPrivate: ByteArray?,
  val language: String,
  val name: String?,
  val isDefault: Boolean,
  val isForced: Boolean = false,
  val video: VideoTrackSpec? = null,
  val audio: AudioTrackSpec? = null,
) {
  companion object {
    const val VIDEO = 1
    const val AUDIO = 2
    const val SUBTITLE = 0x11
  }
}

/**
 * Streaming Matroska muxer: header up front, one cluster at a time straight to [channel] (so a
 * two-hour film never has to fit in memory), then the few values only known at the end — segment
 * size, duration, seek index — patched in place. Timestamps are milliseconds (TimestampScale 1 ms,
 * mkvmerge's default); a cluster starts at each video keyframe at least [MIN_CLUSTER_MS] after the
 * previous one and every cluster start on a keyframe gets a Cues entry, which is what lets players
 * seek instantly instead of scanning the file.
 */
internal class MkvWriter(private val channel: FileChannel) {
  private val out = ByteSink(1 shl 20)
  private var flushedBytes = 0L
  private val cluster = ByteSink(4 shl 20)
  private var clusterTimeMs = NO_CLUSTER
  private val cues = ArrayList<LongArray>() // [time ms, cluster position relative to segment data]
  private var videoTrack = 0
  private var segmentSizePos = 0L
  private var segmentDataStart = 0L
  private var seekHeadPos = 0L
  private var infoPos = 0L
  private var durationPos = 0L
  private var tracksPos = 0L
  private var endTimeMs = 0L

  private fun position() = flushedBytes + out.size

  fun start(title: String, tracks: List<MkvTrackSpec>) {
    require(tracks.size < 127) { "too many tracks" } // track numbers are written as 1-byte vints
    out.master(EBML) {
      uint(EBML_VERSION, 1)
      uint(EBML_READ_VERSION, 1)
      uint(EBML_MAX_ID_LENGTH, 4)
      uint(EBML_MAX_SIZE_LENGTH, 8)
      string(DOC_TYPE, "matroska")
      uint(DOC_TYPE_VERSION, 4)
      uint(DOC_TYPE_READ_VERSION, 2)
    }
    out.writeId(SEGMENT)
    segmentSizePos = position()
    out.writeVint(UNKNOWN_SIZE, 8) // patched in finish()
    segmentDataStart = position()
    seekHeadPos = position()
    out.void(SEEK_HEAD_RESERVED) // replaced by the real SeekHead in finish()
    infoPos = position()
    val info =
      ByteSink().apply {
        uint(TIMESTAMP_SCALE, 1_000_000)
        string(MUXING_APP, APP_NAME)
        string(WRITING_APP, APP_NAME)
        if (title.isNotBlank()) string(TITLE, title)
        float(DURATION, 0.0) // last child on purpose: its 8 data bytes end the Info element
      }
    out.element(INFO, info.toByteArray())
    durationPos = position() - 8
    tracksPos = position()
    out.master(TRACKS) { tracks.forEachIndexed { i, t -> trackEntry(i + 1, t) } }
    videoTrack = tracks.indexOfFirst { it.type == MkvTrackSpec.VIDEO } + 1
  }

  fun writeFrame(track: Int, timeUs: Long, data: ByteArray, keyframe: Boolean) {
    val t = toMs(timeUs)
    val videoKey = track == videoTrack && keyframe
    if (clusterTimeMs == NO_CLUSTER || (videoKey && t - clusterTimeMs >= MIN_CLUSTER_MS) || outOfRange(t) || cluster.size >= MAX_CLUSTER_BYTES) {
      openCluster(t, videoKey)
    }
    cluster.writeId(SIMPLE_BLOCK)
    cluster.writeVint(data.size + 4L)
    blockHeader(cluster, track, t, if (keyframe) 0x80 else 0x00)
    cluster.write(data)
    if (t > endTimeMs) endTimeMs = t
  }

  /** Text subtitles need an explicit duration, which SimpleBlock can't carry — BlockGroup can. */
  fun writeSubtitle(track: Int, startUs: Long, endUs: Long, text: String) {
    val t = toMs(startUs)
    if (clusterTimeMs == NO_CLUSTER || outOfRange(t)) openCluster(t, keyframe = false)
    val payload = text.toByteArray(Charsets.UTF_8)
    val block = ByteSink(payload.size + 4).apply { blockHeader(this, track, t, 0x00) }
    block.write(payload)
    val duration = maxOf(1L, toMs(endUs) - t)
    cluster.master(BLOCK_GROUP) {
      element(BLOCK, block.toByteArray())
      uint(BLOCK_DURATION, duration)
    }
    if (t + duration > endTimeMs) endTimeMs = t + duration
  }

  /** Closes the file's structure; returns its total size in bytes. */
  fun finish(): Long {
    closeCluster()
    val cuesPos = if (cues.isNotEmpty()) position() else -1L
    if (cues.isNotEmpty()) {
      out.master(CUES) {
        for (c in cues) {
          master(CUE_POINT) {
            uint(CUE_TIME, c[0])
            master(CUE_TRACK_POSITIONS) {
              uint(CUE_TRACK, videoTrack.toLong())
              uint(CUE_CLUSTER_POSITION, c[1])
            }
          }
        }
      }
    }
    flush()
    val end = position()
    patch(segmentSizePos, ByteSink().apply { writeVint(end - segmentDataStart, 8) })
    patch(durationPos, ByteSink().apply { writeDouble(endTimeMs.toDouble()) })
    val seekHead =
      ByteSink().apply {
        master(SEEK_HEAD) {
          seek(INFO, infoPos)
          seek(TRACKS, tracksPos)
          if (cuesPos >= 0) seek(CUES, cuesPos)
        }
      }
    seekHead.void(SEEK_HEAD_RESERVED - seekHead.size)
    patch(seekHeadPos, seekHead)
    return end
  }

  private fun ByteSink.trackEntry(number: Int, t: MkvTrackSpec) =
    master(TRACK_ENTRY) {
      uint(TRACK_NUMBER, number.toLong())
      uint(TRACK_UID, number.toLong())
      uint(TRACK_TYPE, t.type.toLong())
      uint(FLAG_DEFAULT, if (t.isDefault) 1 else 0) // the spec's default is 1 — must be explicit
      if (t.isForced) uint(FLAG_FORCED, 1)
      uint(FLAG_LACING, 0)
      t.name?.takeIf { it.isNotBlank() }?.let { string(NAME, it) }
      string(LANGUAGE, t.language)
      string(CODEC_ID, t.codecId)
      t.codecPrivate?.let { element(CODEC_PRIVATE, it) }
      t.video?.let { v ->
        master(VIDEO) {
          uint(PIXEL_WIDTH, v.width.toLong())
          uint(PIXEL_HEIGHT, v.height.toLong())
          if (v.displayWidth != v.width || v.displayHeight != v.height) {
            uint(DISPLAY_WIDTH, v.displayWidth.toLong())
            uint(DISPLAY_HEIGHT, v.displayHeight.toLong())
          }
        }
      }
      t.audio?.let { a ->
        master(AUDIO) {
          float(SAMPLING_FREQUENCY, a.sampleRate.toDouble())
          uint(CHANNELS, a.channels.toLong())
        }
      }
    }

  private fun ByteSink.seek(id: Int, pos: Long) =
    master(SEEK) {
      element(SEEK_ID, ByteSink().apply { writeId(id) }.toByteArray())
      uint(SEEK_POSITION, pos - segmentDataStart)
    }

  private fun blockHeader(sink: ByteSink, track: Int, timeMs: Long, flags: Int) {
    val rel = (timeMs - clusterTimeMs).toInt()
    sink.write(0x80 or track)
    sink.write(rel shr 8)
    sink.write(rel)
    sink.write(flags)
  }

  private fun outOfRange(t: Long) = t - clusterTimeMs > MAX_RELATIVE_MS || t - clusterTimeMs < -MAX_RELATIVE_MS

  private fun openCluster(timeMs: Long, keyframe: Boolean) {
    closeCluster()
    clusterTimeMs = timeMs
    if (keyframe) cues += longArrayOf(timeMs, position() - segmentDataStart)
    cluster.reset()
    cluster.uint(TIMESTAMP, timeMs)
  }

  private fun closeCluster() {
    if (clusterTimeMs == NO_CLUSTER) return
    out.writeId(CLUSTER)
    out.writeVint(cluster.size.toLong())
    flush()
    write(cluster.bytes, cluster.size)
    clusterTimeMs = NO_CLUSTER
  }

  private fun flush() {
    write(out.bytes, out.size)
    out.reset()
  }

  private fun write(bytes: ByteArray, length: Int) {
    val buf = ByteBuffer.wrap(bytes, 0, length)
    var p = flushedBytes
    while (buf.hasRemaining()) p += channel.write(buf, p)
    flushedBytes = p
  }

  private fun patch(pos: Long, sink: ByteSink) {
    val buf = ByteBuffer.wrap(sink.bytes, 0, sink.size)
    var p = pos
    while (buf.hasRemaining()) p += channel.write(buf, p)
  }

  private fun toMs(us: Long) = (us.coerceAtLeast(0) + 500) / 1000

  private companion object {
    const val APP_NAME = "StrComm"
    const val NO_CLUSTER = Long.MIN_VALUE
    const val MIN_CLUSTER_MS = 1_000L
    const val MAX_RELATIVE_MS = 30_000L // block timestamps are int16 ms relative to the cluster
    const val MAX_CLUSTER_BYTES = 16 shl 20
    const val SEEK_HEAD_RESERVED = 128
    const val UNKNOWN_SIZE = (1L shl 56) - 1

    const val EBML = 0x1A45DFA3
    const val EBML_VERSION = 0x4286
    const val EBML_READ_VERSION = 0x42F7
    const val EBML_MAX_ID_LENGTH = 0x42F2
    const val EBML_MAX_SIZE_LENGTH = 0x42F3
    const val DOC_TYPE = 0x4282
    const val DOC_TYPE_VERSION = 0x4287
    const val DOC_TYPE_READ_VERSION = 0x4285
    const val SEGMENT = 0x18538067
    const val SEEK_HEAD = 0x114D9B74
    const val SEEK = 0x4DBB
    const val SEEK_ID = 0x53AB
    const val SEEK_POSITION = 0x53AC
    const val INFO = 0x1549A966
    const val TIMESTAMP_SCALE = 0x2AD7B1
    const val MUXING_APP = 0x4D80
    const val WRITING_APP = 0x5741
    const val TITLE = 0x7BA9
    const val DURATION = 0x4489
    const val TRACKS = 0x1654AE6B
    const val TRACK_ENTRY = 0xAE
    const val TRACK_NUMBER = 0xD7
    const val TRACK_UID = 0x73C5
    const val TRACK_TYPE = 0x83
    const val FLAG_DEFAULT = 0x88
    const val FLAG_FORCED = 0x55AA
    const val FLAG_LACING = 0x9C
    const val NAME = 0x536E
    const val LANGUAGE = 0x22B59C
    const val CODEC_ID = 0x86
    const val CODEC_PRIVATE = 0x63A2
    const val VIDEO = 0xE0
    const val PIXEL_WIDTH = 0xB0
    const val PIXEL_HEIGHT = 0xBA
    const val DISPLAY_WIDTH = 0x54B0
    const val DISPLAY_HEIGHT = 0x54BA
    const val AUDIO = 0xE1
    const val SAMPLING_FREQUENCY = 0xB5
    const val CHANNELS = 0x9F
    const val CLUSTER = 0x1F43B675
    const val TIMESTAMP = 0xE7
    const val SIMPLE_BLOCK = 0xA3
    const val BLOCK_GROUP = 0xA0
    const val BLOCK = 0xA1
    const val BLOCK_DURATION = 0x9B
    const val CUES = 0x1C53BB6B
    const val CUE_POINT = 0xBB
    const val CUE_TIME = 0xB3
    const val CUE_TRACK_POSITIONS = 0xB7
    const val CUE_TRACK = 0xF7
    const val CUE_CLUSTER_POSITION = 0xF1
  }
}

/** A growable byte buffer — ByteArrayOutputStream minus its locking and its copy on every read. */
internal class ByteSink(initialCapacity: Int = 256) {
  var bytes = ByteArray(initialCapacity)
    private set

  var size = 0
    private set

  fun write(b: Int) {
    ensure(1)
    bytes[size++] = b.toByte()
  }

  fun write(src: ByteArray) {
    ensure(src.size)
    System.arraycopy(src, 0, bytes, size, src.size)
    size += src.size
  }

  fun reset() {
    size = 0
  }

  fun toByteArray(): ByteArray = bytes.copyOf(size)

  private fun ensure(n: Int) {
    if (size + n > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + n))
  }
}

internal fun ByteSink.writeId(id: Int) {
  when {
    id ushr 24 != 0 -> intArrayOf(id ushr 24, id ushr 16, id ushr 8, id)
    id ushr 16 != 0 -> intArrayOf(id ushr 16, id ushr 8, id)
    id ushr 8 != 0 -> intArrayOf(id ushr 8, id)
    else -> intArrayOf(id)
  }.forEach(::write)
}

/** EBML variable-length integer; [length] bytes, defaulting to the shortest that fits (the
 *  all-ones value of each length is reserved for "unknown size"). */
internal fun ByteSink.writeVint(value: Long, length: Int = vintLength(value)) {
  val marked = value or (1L shl (7 * length))
  for (i in length - 1 downTo 0) write((marked ushr (8 * i)).toInt())
}

private fun vintLength(value: Long): Int {
  var length = 1
  while (length < 8 && value >= (1L shl (7 * length)) - 1) length++
  return length
}

internal fun ByteSink.writeDouble(value: Double) {
  val bits = java.lang.Double.doubleToLongBits(value)
  for (i in 7 downTo 0) write((bits ushr (8 * i)).toInt())
}

internal fun ByteSink.element(id: Int, data: ByteArray) {
  writeId(id)
  writeVint(data.size.toLong())
  write(data)
}

internal fun ByteSink.master(id: Int, build: ByteSink.() -> Unit) = element(id, ByteSink().apply(build).toByteArray())

internal fun ByteSink.uint(id: Int, value: Long) {
  var n = 1
  while (n < 8 && value ushr (8 * n) != 0L) n++
  writeId(id)
  writeVint(n.toLong())
  for (i in n - 1 downTo 0) write((value ushr (8 * i)).toInt())
}

internal fun ByteSink.float(id: Int, value: Double) {
  writeId(id)
  writeVint(8)
  writeDouble(value)
}

internal fun ByteSink.string(id: Int, value: String) = element(id, value.toByteArray(Charsets.UTF_8))

/** EBML Void padding of exactly [total] bytes (ID + size + zeros). */
internal fun ByteSink.void(total: Int) {
  require(total >= 2) { "void too small: $total" }
  writeId(0xEC)
  val sizeLength = if (total - 2 <= 126) 1 else 8
  val payload = total - 1 - sizeLength
  writeVint(payload.toLong(), sizeLength)
  repeat(payload) { write(0) }
}
