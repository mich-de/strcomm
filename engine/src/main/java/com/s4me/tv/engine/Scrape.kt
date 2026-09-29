package com.s4me.tv.engine

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Regex helpers mirroring the addon's core/scrapertools.py contract: never throw, empty string/list on no match. */
object Scrape {
  private val NAMED_GROUP_RE = Regex("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>")

  /** Resolves a possibly-relative href found on [base]'s page into an absolute URL. */
  fun absUrl(base: String, href: String): String {
    if (href.isBlank()) return href
    if (href.startsWith("http://") || href.startsWith("https://")) return href
    val baseUrl = base.toHttpUrlOrNull() ?: return href
    return baseUrl.resolve(href)?.toString() ?: href
  }

  private fun groupNames(pattern: String): List<String> = NAMED_GROUP_RE.findAll(pattern).map { it.groupValues[1] }.toList()

  /** First match's named groups as a map (missing/unmatched group -> ""), or null if the pattern doesn't match. */
  fun findNamed(text: String, pattern: String): Map<String, String>? {
    val names = groupNames(pattern)
    val m = Regex(pattern, RegexOption.DOT_MATCHES_ALL).find(text) ?: return null
    return names.associateWith { n -> runCatching { m.groups[n]?.value }.getOrNull() ?: "" }
  }

  /** Every match's named groups as a map, in document order. */
  fun findAllNamed(text: String, pattern: String): List<Map<String, String>> {
    val names = groupNames(pattern)
    return Regex(pattern, RegexOption.DOT_MATCHES_ALL).findAll(text).map { m ->
      names.associateWith { n -> runCatching { m.groups[n]?.value }.getOrNull() ?: "" }
    }.toList()
  }

  fun find1(text: String, pattern: String, group: Int = 1): String {
    val m = Regex(pattern, RegexOption.DOT_MATCHES_ALL).find(text) ?: return ""
    return m.groupValues.getOrElse(group) { "" }
  }

  /** All matches of the first capture group (or full match if the pattern has none). */
  fun findAll(text: String, pattern: String): List<String> {
    val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
    return regex.findAll(text).map { m -> m.groupValues.getOrElse(1) { m.value } }.toList()
  }

  fun findAllMatches(text: String, pattern: String): List<MatchResult> =
    Regex(pattern, RegexOption.DOT_MATCHES_ALL).findAll(text).toList()

  fun htmlClean(text: String): String =
    text
      .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
      .replace(Regex("(?s)<[^>]+>"), " ")
      .replace("&nbsp;", " ")
      .replace(Regex("\\s+"), " ")
      .trim()

  fun unescape(text: String): String =
    text
      .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
      .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
      .replace("&quot;", "\"")
      .replace("&apos;", "'")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&nbsp;", " ")
      .replace("&amp;", "&") // must run last: earlier replacements' literal & must not be re-escaped
}
