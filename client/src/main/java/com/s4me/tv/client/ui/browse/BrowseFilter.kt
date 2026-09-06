package com.s4me.tv.client.ui.browse

/** The catalog filters, mirroring the TV app's. Encoded into [com.s4me.tv.engine.StreamItem.extra]
 *  as a query string the channel's archive listing understands. */
data class BrowseFilter(
  val type: String? = null, // "movie" | "tv"
  val genreId: Int? = null,
  val year: Int? = null,
  val sort: String? = null, // null = popolari, "release" = uscita, "score" = voto
  /** "oscar" = Best Picture winners, "oscar-nominees" = winners + nominees. A curated list
   *  resolved through search (the source has no award metadata); replaces the archive listing. */
  val award: String? = null,
) {
  fun toExtra(): String? {
    val parts = buildList {
      type?.let { add("type=$it") }
      genreId?.let { add("genre=$it") }
      year?.let { add("year=$it") }
      sort?.let { add("sort=$it") }
      award?.let { add("award=$it") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("&")
  }

  val isActive: Boolean
    get() = type != null || genreId != null || year != null || sort != null || award != null

  companion object {
    fun fromExtra(extra: String?): BrowseFilter {
      if (extra.isNullOrBlank()) return BrowseFilter()
      val map =
        extra.split("&").mapNotNull { pair -> pair.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
      return BrowseFilter(
        type = map["type"],
        genreId = map["genre"]?.toIntOrNull(),
        year = map["year"]?.toIntOrNull(),
        sort = map["sort"],
        award = map["award"],
      )
    }
  }
}
