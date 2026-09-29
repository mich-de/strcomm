package com.s4me.tv.engine

import kotlinx.serialization.Serializable

@Serializable enum class ItemKind { CATEGORY, LIST, MOVIE, SERIES, SEASON, EPISODE, PLAYABLE }

@Serializable enum class ChannelCategory { MOVIES, SERIES, ANIME, MIXED }

@Serializable
data class StreamItem(
  val title: String,
  val url: String,
  val kind: ItemKind,
  val channelId: String,
  val thumbnail: String? = null,
  /** Wide "background" still, for a hero banner — null for items only ever shown as a poster card. */
  val backdrop: String? = null,
  /** Wide 16:9 "cover" art, purpose-made for a landscape title card — distinct from [backdrop]
   *  (the full-bleed hero still) and [thumbnail] (the 2:3 poster). Drives :app's horizontal Home
   *  rows, which mirror streamingcommunity's own site ("le voglio orizzontali come su ...taxi"). */
  val cover: String? = null,
  /** Transparent title-treatment "logo" image. Overlaid on the landscape [cover] card the way the
   *  source site shows it, in place of a plain text label. */
  val logo: String? = null,
  val plot: String? = null,
  val year: String? = null,
  val quality: String? = null,
  val seriesTitle: String? = null,
  val season: Int? = null,
  val episode: Int? = null,
  val serverId: String? = null,
  val referer: String? = null,
  /** Free-form per-item payload a channel stashes while listing (e.g. a raw mirror-links HTML
   *  fragment) so a later findVideos() call doesn't need to re-fetch and re-locate it. */
  val extra: String? = null,
  val page: Int = 1,
  /** The movie/episode's own display name, carried onto the PLAYABLE item findVideos() returns
   *  so PlayerScreen can show it (title is the source/server label there, e.g. "StreamingCommunity"). */
  val contentTitle: String? = null,
  val genres: String? = null,
  val cast: String? = null,
  val director: String? = null,
  /** Runtime in minutes, when known. */
  val runtime: Int? = null,
  val tmdbId: String? = null,
  val imdbId: String? = null,
  /** Authoritative ratings filled by [Channel.detail], shown on the detail screen in place of the
   *  source site's own score. Both keyless by default (scraped from IMDb / TMDB public pages);
   *  a TMDB API key, if set, upgrades the TMDB path with vote counts + the official trailer.
   *  Any may be null. */
  val imdbRating: Double? = null,
  val imdbVotes: Int? = null,
  val tmdbRating: Double? = null,
  val tmdbVotes: Int? = null,
  /** YouTube video id of the title's trailer — TMDB's official trailer when a key is set,
   *  otherwise the source site's own "trailers" list, preferring an Italian one. Set by
   *  [Channel.detail]; drives the "Trailer" button on the detail screen. */
  val trailerYoutubeId: String? = null,
  /** On a PLAYABLE item, the url of the MOVIE/EPISODE it was resolved from (e.g. "8424|dark-matter|61161")
   *  — the stable content identity used to save/restore watch progress, since the PLAYABLE's own url is
   *  a short-lived HLS token that's useless later. */
  val originId: String? = null,
  /** Watch-progress fraction 0..1, set only when an item is shown in the "Continua a guardare" row so
   *  its card can draw a resume bar. Not persisted as content metadata. */
  val progress: Float? = null,
  /** Position in Netflix's own public Top 10 chart, set only when an item is shown in the
   *  "I titoli del momento" row so its card can draw a rank numeral. Not persisted as content
   *  metadata — see [com.s4me.tv.engine.NetflixTop10]. */
  val rank: Int? = null,
)

data class ListResult(val items: List<StreamItem>, val hasMore: Boolean)

data class HomeSection(val title: String, val channelId: String, val items: List<StreamItem>)
