package com.s4me.tv.engine.download

import java.io.ByteArrayOutputStream

/** One access unit out of the demuxer. Times are µs on the stream's own (33-bit-unwrapped) PTS
 *  clock — NOT rebased to zero, so the separate audio renditions stay aligned with the video (each
 *  is its own TS file, but all share one encoder clock: verified, both start at PTS 0 with video
 *  83 ms later from B-frame reordering). */
internal class EsSample(val ptsUs: Long, val dtsUs: Long, val data: ByteArray, val keyframe: Boolean)

internal sealed class ElementaryStream {
  val samples = ArrayDeque<EsSample>()

  /** Decode time of the newest queued sample — what the interleaver waits on before writing
   *  anything later than it from another track. */
  var lastQueuedDtsUs: Long = Long.MIN_VALUE
    private set

  protected fun queue(sample: EsSample) {
    samples.addLast(sample)
    lastQueuedDtsUs = sample.dtsUs
  }

  /** True once the codec configuration a container needs up front (SPS/PPS, AudioSpecificConfig)
   *  has been seen. */
  abstract val isReady: Boolean

  internal abstract fun consumePes(ptsUs: Long?, dtsUs: Long?, payload: ByteArray)
}

/**
 * Just enough MPEG-TS demuxing for HLS segments: PAT → first program's PMT → H.264 (stream_type
 * 0x1B) and ADTS AAC (0x0F) elementary streams, PES reassembly with PTS/DTS. Segments are fed in
 * order, one after another, exactly like the concatenated stream HLS defines them to be.
 */
internal class TsDemuxer {
  val streams = mutableListOf<ElementaryStream>()
  private val assemblers = HashMap<Int, PesAssembler>()
  private var pmtPid = -1
  private var carry = ByteArray(0)

  fun feed(data: ByteArray) {
    val buf = if (carry.isEmpty()) data else carry + data
    var i = 0
    while (i + PACKET <= buf.size) {
      if (buf[i] != SYNC) {
        i++ // lost sync — slide until the next 0x47
        continue
      }
      packet(buf, i)
      i += PACKET
    }
    carry = buf.copyOfRange(i, buf.size)
  }

  /** End of stream: an unbounded video PES is only complete once the next one starts, so the very
   *  last access unit is still pending until this is called. */
  fun finish() {
    assemblers.values.forEach { it.flush() }
  }

  private fun packet(b: ByteArray, o: Int) {
    val b1 = b[o + 1].toInt() and 0xFF
    if (b1 and 0x80 != 0) return // transport_error_indicator
    val payloadStart = b1 and 0x40 != 0
    val pid = ((b1 and 0x1F) shl 8) or (b[o + 2].toInt() and 0xFF)
    val adaptation = (b[o + 3].toInt() shr 4) and 0x3
    val end = o + PACKET
    var p = o + 4
    if (adaptation and 0x2 != 0) p += 1 + (b[p].toInt() and 0xFF)
    if (adaptation and 0x1 == 0 || p >= end) return
    when (pid) {
      0 -> if (payloadStart) parsePat(b, p + 1 + (b[p].toInt() and 0xFF), end)
      pmtPid -> if (payloadStart) parsePmt(b, p + 1 + (b[p].toInt() and 0xFF), end)
      else ->
        assemblers[pid]?.let {
          if (payloadStart) it.start()
          it.append(b, p, end - p)
        }
    }
  }

  private fun parsePat(b: ByteArray, s: Int, end: Int) {
    if (s + 8 > end || b[s].toInt() != 0x00) return
    val limit = minOf(s + 3 + sectionLength(b, s) - 4, end)
    var q = s + 8
    while (q + 4 <= limit) {
      val program = ((b[q].toInt() and 0xFF) shl 8) or (b[q + 1].toInt() and 0xFF)
      if (program != 0) {
        pmtPid = ((b[q + 2].toInt() and 0x1F) shl 8) or (b[q + 3].toInt() and 0xFF)
        return
      }
      q += 4
    }
  }

  private fun parsePmt(b: ByteArray, s: Int, end: Int) {
    if (s + 12 > end || b[s].toInt() != 0x02) return
    val limit = minOf(s + 3 + sectionLength(b, s) - 4, end)
    val programInfoLength = ((b[s + 10].toInt() and 0x0F) shl 8) or (b[s + 11].toInt() and 0xFF)
    var q = s + 12 + programInfoLength
    while (q + 5 <= limit) {
      val type = b[q].toInt() and 0xFF
      val pid = ((b[q + 1].toInt() and 0x1F) shl 8) or (b[q + 2].toInt() and 0xFF)
      val infoLength = ((b[q + 3].toInt() and 0x0F) shl 8) or (b[q + 4].toInt() and 0xFF)
      // The PMT repeats at the start of every segment — register each PID once.
      if (pid !in assemblers) {
        val stream =
          when (type) {
            0x1B -> H264Stream()
            0x0F -> AacStream()
            else -> null
          }
        if (stream != null) {
          streams += stream
          assemblers[pid] = PesAssembler(stream)
        }
      }
      q += 5 + infoLength
    }
  }

  private fun sectionLength(b: ByteArray, s: Int) = ((b[s + 1].toInt() and 0x0F) shl 8) or (b[s + 2].toInt() and 0xFF)

  private class PesAssembler(val stream: ElementaryStream) {
    private var buffer = ByteArray(256 * 1024)
    private var size = 0
    private var active = false
    private var expected = 0 // total PES size incl. the 6-byte prefix; 0 = not read yet, -1 = unbounded
    private val clock = PtsClock()

    fun start() {
      flush()
      active = true
      size = 0
      expected = 0
    }

    fun append(b: ByteArray, off: Int, len: Int) {
      if (!active) return // payload before the first unit start has no header to anchor it
      if (size + len > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + len))
      System.arraycopy(b, off, buffer, size, len)
      size += len
      if (expected == 0 && size >= 6) {
        val declared = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
        expected = if (declared == 0) -1 else declared + 6
      }
      // A bounded PES (audio, typically) is complete the moment its declared length arrives —
      // emitting it now instead of at the next unit start keeps the interleaver's queues short.
      if (expected > 0 && size >= expected) flush()
    }

    fun flush() {
      if (!active) return
      active = false
      val n = if (expected > 0) minOf(size, expected) else size
      if (n < 9 || buffer[0].toInt() != 0 || buffer[1].toInt() != 0 || buffer[2].toInt() != 1) return
      val flags = buffer[7].toInt() and 0xC0
      val headerEnd = 9 + (buffer[8].toInt() and 0xFF)
      if (headerEnd > n) return
      val pts = if (flags and 0x80 != 0 && n >= 14) clock.toUs(readTimestamp(buffer, 9)) else null
      val dts = if (flags == 0xC0 && n >= 19) clock.toUs(readTimestamp(buffer, 14)) else pts
      stream.consumePes(pts, dts, buffer.copyOfRange(headerEnd, n))
    }

    private fun readTimestamp(b: ByteArray, o: Int): Long =
      ((b[o].toLong() and 0x0E) shl 29) or
        ((b[o + 1].toLong() and 0xFF) shl 22) or
        ((b[o + 2].toLong() and 0xFE) shl 14) or
        ((b[o + 3].toLong() and 0xFF) shl 7) or
        ((b[o + 4].toLong() and 0xFE) shr 1)
  }

  /** 90 kHz → µs, unwrapping the 33-bit rollover (every ~26.5 h of stream clock). */
  private class PtsClock {
    private var epoch = 0L
    private var highest = -1L

    fun toUs(raw: Long): Long {
      var v = raw + epoch
      if (highest >= 0 && v < highest - (1L shl 32)) {
        epoch += 1L shl 33
        v += 1L shl 33
      }
      if (v > highest) highest = v
      return v * 100 / 9
    }
  }

  private companion object {
    const val PACKET = 188
    const val SYNC: Byte = 0x47
  }
}

internal class SpsInfo(val width: Int, val height: Int, val sarWidth: Int, val sarHeight: Int)

/** H.264 access units, converted from Annex B (start codes, as carried in TS) to the 4-byte
 *  length-prefixed form Matroska's V_MPEG4/ISO/AVC requires. Access-unit delimiters are dropped;
 *  in-band SPS/PPS are kept (harmless, and decoders that reinitialise on keyframes like them). */
internal class H264Stream : ElementaryStream() {
  var sps: ByteArray? = null
    private set

  var pps: ByteArray? = null
    private set

  var spsInfo: SpsInfo? = null
    private set

  override val isReady: Boolean
    get() = sps != null && pps != null

  override fun consumePes(ptsUs: Long?, dtsUs: Long?, payload: ByteArray) {
    val pts = ptsUs ?: return // HLS stamps every video PES; an unstamped one can't be placed
    val out = ByteArrayOutputStream(payload.size + 32)
    var keyframe = false
    forEachNal(payload) { start, end ->
      when (payload[start].toInt() and 0x1F) {
        9 -> return@forEachNal
        5 -> keyframe = true
        7 ->
          if (sps == null) {
            val nal = payload.copyOfRange(start, end)
            sps = nal
            spsInfo = runCatching { SpsParser.parse(nal) }.getOrNull()
          }
        8 -> if (pps == null) pps = payload.copyOfRange(start, end)
      }
      val len = end - start
      out.write(len ushr 24)
      out.write(len ushr 16)
      out.write(len ushr 8)
      out.write(len)
      out.write(payload, start, len)
    }
    if (out.size() == 0) return
    queue(EsSample(pts, dtsUs ?: pts, out.toByteArray(), keyframe))
  }

  /** The avcC box Matroska expects as CodecPrivate: one SPS, one PPS, 4-byte NAL lengths. */
  fun decoderConfigurationRecord(): ByteArray {
    val s = sps ?: error("no SPS")
    val p = pps ?: error("no PPS")
    val out = ByteArrayOutputStream(s.size + p.size + 11)
    out.write(1)
    out.write(s[1].toInt()) // profile_idc
    out.write(s[2].toInt()) // constraint flags
    out.write(s[3].toInt()) // level_idc
    out.write(0xFF) // reserved + lengthSizeMinusOne = 3
    out.write(0xE1) // reserved + one SPS
    out.write(s.size ushr 8)
    out.write(s.size)
    out.write(s)
    out.write(1) // one PPS
    out.write(p.size ushr 8)
    out.write(p.size)
    out.write(p)
    return out.toByteArray()
  }

  private inline fun forEachNal(data: ByteArray, action: (start: Int, end: Int) -> Unit) {
    val first = startCode(data, 0)
    if (first < 0) return
    var start = first + 3
    while (start < data.size) {
      val next = startCode(data, start)
      var end = if (next < 0) data.size else next
      // Trailing zero bytes belong to the next (4-byte) start code or are trailing_zero_8bits —
      // never part of the NAL itself, whose last byte is always non-zero.
      while (end > start && data[end - 1].toInt() == 0) end--
      if (end > start) action(start, end)
      if (next < 0) return
      start = next + 3
    }
  }

  private fun startCode(data: ByteArray, from: Int): Int {
    var i = from
    while (i + 2 < data.size) {
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i
      i++
    }
    return -1
  }
}

/** ADTS AAC frames → raw AAC frames plus the 2-byte AudioSpecificConfig Matroska's A_AAC needs.
 *  An audio PES usually carries several frames and a frame may straddle two PES packets, so bytes
 *  accumulate across PES boundaries; each PES's PTS stamps the first frame that STARTS inside it
 *  (MPEG-2 systems rule), later frames advance by 1024 samples. */
internal class AacStream : ElementaryStream() {
  var audioSpecificConfig: ByteArray? = null
    private set

  var sampleRate = 0
    private set

  var channels = 0
    private set

  override val isReady: Boolean
    get() = audioSpecificConfig != null

  private var pending = ByteArray(64 * 1024)
  private var pendingSize = 0
  private val markers = ArrayDeque<LongArray>() // [byte offset in pending, PTS µs]
  private var baseUs = Long.MIN_VALUE
  private var framesSinceBase = 0L

  override fun consumePes(ptsUs: Long?, dtsUs: Long?, payload: ByteArray) {
    if (ptsUs != null) markers.addLast(longArrayOf(pendingSize.toLong(), ptsUs))
    if (pendingSize + payload.size > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + payload.size))
    System.arraycopy(payload, 0, pending, pendingSize, payload.size)
    pendingSize += payload.size
    parseFrames()
  }

  private fun parseFrames() {
    val b = pending
    var pos = 0
    while (pos + 7 <= pendingSize) {
      if ((b[pos].toInt() and 0xFF) != 0xFF || (b[pos + 1].toInt() and 0xF0) != 0xF0) {
        pos++
        continue
      }
      val protectionAbsent = b[pos + 1].toInt() and 0x01 == 1
      val profile = (b[pos + 2].toInt() shr 6) and 0x3
      val freqIndex = (b[pos + 2].toInt() shr 2) and 0xF
      val channelConfig = ((b[pos + 2].toInt() and 0x1) shl 2) or ((b[pos + 3].toInt() shr 6) and 0x3)
      val frameLength =
        ((b[pos + 3].toInt() and 0x3) shl 11) or ((b[pos + 4].toInt() and 0xFF) shl 3) or ((b[pos + 5].toInt() shr 5) and 0x7)
      val headerLength = if (protectionAbsent) 7 else 9
      if (frameLength <= headerLength || freqIndex >= SAMPLE_RATES.size) {
        pos++ // false sync inside payload data
        continue
      }
      if (pos + frameLength > pendingSize) break // rest of this frame is in the next PES
      if (audioSpecificConfig == null) {
        val objectType = profile + 1
        audioSpecificConfig =
          byteArrayOf(((objectType shl 3) or (freqIndex shr 1)).toByte(), (((freqIndex and 1) shl 7) or (channelConfig shl 3)).toByte())
        sampleRate = SAMPLE_RATES[freqIndex]
        channels = if (channelConfig == 0) 2 else if (channelConfig == 7) 8 else channelConfig
      }
      while (markers.isNotEmpty() && markers.first()[0] <= pos) {
        baseUs = markers.removeFirst()[1]
        framesSinceBase = 0
      }
      if (baseUs != Long.MIN_VALUE) {
        val t = baseUs + framesSinceBase * 1024L * 1_000_000L / sampleRate
        framesSinceBase++
        queue(EsSample(t, t, b.copyOfRange(pos + headerLength, pos + frameLength), keyframe = true))
      }
      pos += frameLength
    }
    if (pos > 0) {
      System.arraycopy(pending, pos, pending, 0, pendingSize - pos)
      pendingSize -= pos
      for (m in markers) m[0] -= pos.toLong()
    }
  }

  private companion object {
    val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
  }
}

/** Reads just the frame geometry out of an H.264 SPS: coded size minus cropping, and the sample
 *  aspect ratio. Needed because the playlist's RESOLUTION is the nominal ladder rung, not the real
 *  frame (verified: a "1280x720" variant is actually 1280x534 scope). */
internal object SpsParser {
  private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
  private val SAR =
    arrayOf(
      1 to 1, 1 to 1, 12 to 11, 10 to 11, 16 to 11, 40 to 33, 24 to 11, 20 to 11, 32 to 11, 80 to 33, 18 to 11, 15 to 11,
      64 to 33, 160 to 99, 4 to 3, 3 to 2, 2 to 1,
    )

  fun parse(nal: ByteArray): SpsInfo {
    val r = BitReader(unescape(nal, 1))
    val profile = r.bits(8)
    r.skip(16) // constraint flags + level
    r.ue() // seq_parameter_set_id
    var chroma = 1
    if (profile in HIGH_PROFILES) {
      chroma = r.ue()
      if (chroma == 3) r.skip(1)
      r.ue() // bit_depth_luma_minus8
      r.ue() // bit_depth_chroma_minus8
      r.skip(1)
      if (r.bit() == 1) repeat(if (chroma != 3) 8 else 12) { i -> if (r.bit() == 1) skipScalingList(r, if (i < 6) 16 else 64) }
    }
    r.ue() // log2_max_frame_num_minus4
    when (r.ue()) {
      0 -> r.ue()
      1 -> {
        r.skip(1)
        r.se()
        r.se()
        repeat(r.ue()) { r.se() }
      }
    }
    r.ue() // max_num_ref_frames
    r.skip(1)
    val widthMbs = r.ue() + 1
    val heightUnits = r.ue() + 1
    val frameMbsOnly = r.bit()
    if (frameMbsOnly == 0) r.skip(1)
    r.skip(1) // direct_8x8_inference_flag
    var cropLeft = 0
    var cropRight = 0
    var cropTop = 0
    var cropBottom = 0
    if (r.bit() == 1) {
      cropLeft = r.ue()
      cropRight = r.ue()
      cropTop = r.ue()
      cropBottom = r.ue()
    }
    val cropUnitX = if (chroma == 1 || chroma == 2) 2 else 1
    val cropUnitY = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
    val width = widthMbs * 16 - cropUnitX * (cropLeft + cropRight)
    val height = (2 - frameMbsOnly) * heightUnits * 16 - cropUnitY * (cropTop + cropBottom)
    var sarWidth = 1
    var sarHeight = 1
    if (r.bit() == 1 && r.bit() == 1) { // vui_parameters_present && aspect_ratio_info_present
      val idc = r.bits(8)
      if (idc == 255) {
        sarWidth = r.bits(16)
        sarHeight = r.bits(16)
      } else if (idc in 1 until SAR.size) {
        sarWidth = SAR[idc].first
        sarHeight = SAR[idc].second
      }
    }
    if (sarWidth <= 0 || sarHeight <= 0) {
      sarWidth = 1
      sarHeight = 1
    }
    return SpsInfo(width, height, sarWidth, sarHeight)
  }

  private fun skipScalingList(r: BitReader, size: Int) {
    var last = 8
    var next = 8
    repeat(size) {
      if (next != 0) next = (last + r.se() + 256) % 256
      if (next != 0) last = next
    }
  }

  /** Strips emulation-prevention bytes (the 0x03 in 00 00 03) from a NAL payload. */
  private fun unescape(nal: ByteArray, from: Int): ByteArray {
    val out = ByteArrayOutputStream(nal.size)
    var zeros = 0
    for (i in from until nal.size) {
      val v = nal[i].toInt() and 0xFF
      if (zeros >= 2 && v == 3) {
        zeros = 0
        continue
      }
      out.write(v)
      zeros = if (v == 0) zeros + 1 else 0
    }
    return out.toByteArray()
  }

  private class BitReader(private val data: ByteArray) {
    private var pos = 0

    fun bit(): Int = ((data[pos ushr 3].toInt() shr (7 - (pos and 7))) and 1).also { pos++ }

    fun bits(n: Int): Int {
      var v = 0
      repeat(n) { v = (v shl 1) or bit() }
      return v
    }

    fun skip(n: Int) {
      pos += n
    }

    fun ue(): Int {
      var zeros = 0
      while (bit() == 0) if (++zeros > 31) error("bad exp-Golomb code")
      return (1 shl zeros) - 1 + bits(zeros)
    }

    fun se(): Int {
      val k = ue()
      return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
    }
  }
}
