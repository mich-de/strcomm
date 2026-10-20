package com.s4me.tv.engine.download

internal class SubtitleCue(val startUs: Long, val endUs: Long, val text: String)

/** Cues with their times as written in the file, plus the X-TIMESTAMP-MAP offset (µs to add to
 *  land on the MPEG-TS PTS clock) when the file declares one. The CDN's own files don't (verified:
 *  bare "WEBVTT" header, `mm:ss.mmm` times relative to the start of the title), but HLS allows it
 *  and it's two lines to honour. */
internal class ParsedVtt(val cues: List<SubtitleCue>, val ptsOffsetUs: Long?)

internal object WebVttParser {
  private const val TIME = """(?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3}"""
  private val TIMING = Regex("""^\s*($TIME)\s+-->\s+($TIME)""")
  private val MPEGTS = Regex("""MPEGTS:(\d+)""")
  private val LOCAL = Regex("""LOCAL:($TIME)""")

  // Matroska S_TEXT/UTF8 is SubRip-flavoured: players honour <i>/<b>/<u> and nothing else, so
  // every other WebVTT tag (<c.class>, <v Speaker>, <lang>, ruby, inline timestamps) is dropped
  // rather than shown as literal text.
  private val TAG = Regex("""<(?!/?[ibu]>)[^>]*>""")

  fun parse(text: String): ParsedVtt {
    var offset: Long? = null
    val cues = mutableListOf<SubtitleCue>()
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      if (line.startsWith("X-TIMESTAMP-MAP")) {
        val mpegts = MPEGTS.find(line)?.groupValues?.get(1)?.toLongOrNull()
        val local = LOCAL.find(line)?.groupValues?.get(1)?.let(::parseTime)
        if (mpegts != null && local != null) offset = mpegts * 100 / 9 - local
        i++
        continue
      }
      val timing = TIMING.find(line)
      i++
      if (timing == null) continue // header, cue identifier, NOTE/STYLE/REGION block line
      val start = parseTime(timing.groupValues[1])
      val end = parseTime(timing.groupValues[2])
      val body = StringBuilder()
      while (i < lines.size && lines[i].isNotBlank()) {
        if (body.isNotEmpty()) body.append('\n')
        body.append(lines[i])
        i++
      }
      val cleaned = clean(body.toString())
      if (cleaned.isNotEmpty() && end > start) cues += SubtitleCue(start, end, cleaned)
    }
    return ParsedVtt(cues, offset)
  }

  private fun parseTime(s: String): Long {
    val parts = s.replace(',', '.').split(':')
    val (seconds, fraction) = parts.last().split('.').let { it[0].toLong() to it.getOrElse(1) { "0" } }
    val millis = fraction.padEnd(3, '0').take(3).toLong()
    val minutes = parts[parts.size - 2].toLong()
    val hours = if (parts.size >= 3) parts[0].toLong() else 0L
    return ((hours * 3600 + minutes * 60 + seconds) * 1000 + millis) * 1000
  }

  private fun clean(s: String): String =
    TAG.replace(s, "")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&nbsp;", " ")
      .replace("&lrm;", "‎")
      .replace("&rlm;", "‏")
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
      .replace("&amp;", "&") // last, so "&amp;lt;" stays the literal text "&lt;"
      .trim()
}
