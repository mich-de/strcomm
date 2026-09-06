package com.s4me.tv

import androidx.navigation3.runtime.NavKey
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem
import kotlinx.serialization.Serializable

@Serializable data object Home : NavKey

/** [initialQuery] lets a caller (e.g. tapping an actor/director name on Detail) land directly on
 *  results instead of an empty search box — see DetailScreen's Cast/Regia rows. [isPersonQuery]
 *  additionally asks SearchViewModel to verify each raw result's actual cast/director credits
 *  before showing it — the site's own search is a fuzzy text match, not a credit lookup, and a
 *  full name still pulls in unrelated titles further down its results (verified live: searching
 *  "Vittorio Gassman" surfaced "Monella", whose cast has no Gassman at all — just an unrelated
 *  "Vittorio Attene"). A typed keyboard search stays plain full-text search; only a name tapped
 *  from a known credit is precise enough to be worth re-verifying. */
@Serializable data class Search(val initialQuery: String? = null, val isPersonQuery: Boolean = false) : NavKey

@Serializable data object Settings : NavKey

/** Grid listing of a CATEGORY/LIST/SEASON item's children. */
@Serializable data class Browse(val item: StreamItem) : NavKey

/** Movie/series detail — plot, and either a play action or a season/episode picker. */
@Serializable data class Detail(val item: StreamItem) : NavKey

/** Plays a PLAYABLE item — findVideos() already resolved it to a final, direct stream URL. */
@Serializable data class Player(val item: StreamItem) : NavKey

/** Where clicking a StreamItem should navigate, based on what kind of node it is in the channel tree. */
fun navKeyFor(item: StreamItem): NavKey =
  when (item.kind) {
    ItemKind.CATEGORY, ItemKind.LIST, ItemKind.SERIES, ItemKind.SEASON -> Browse(item)
    ItemKind.MOVIE, ItemKind.EPISODE -> Detail(item)
    ItemKind.PLAYABLE -> Player(item)
  }
