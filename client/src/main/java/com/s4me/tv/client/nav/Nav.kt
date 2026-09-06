package com.s4me.tv.client.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation3.runtime.NavKey
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem
import kotlinx.serialization.Serializable

@Serializable data object Home : NavKey

@Serializable data class Search(val initialQuery: String? = null, val isPersonQuery: Boolean = false) : NavKey

@Serializable data object BrowseRoot : NavKey

@Serializable data object Settings : NavKey

/** Grid of a CATEGORY/LIST/SERIES/SEASON item's children (also the "detail" screen for a series). */
@Serializable data class Browse(val item: StreamItem) : NavKey

/** Movie / episode detail. */
@Serializable data class Detail(val item: StreamItem) : NavKey

/** Plays a resolved PLAYABLE item. */
@Serializable data class Player(val item: StreamItem) : NavKey

/** Where a tapped [StreamItem] goes, mirroring the TV app's single routing decision. */
fun navKeyFor(item: StreamItem): NavKey =
  when (item.kind) {
    ItemKind.CATEGORY, ItemKind.LIST, ItemKind.SERIES, ItemKind.SEASON -> Browse(item)
    ItemKind.MOVIE, ItemKind.EPISODE -> Detail(item)
    ItemKind.PLAYABLE -> Player(item)
  }

/** Layout breakpoint. >= 600dp wide → tablet / phone-landscape: nav rail, denser grids. */
@Composable
fun isWideScreen(): Boolean = LocalConfiguration.current.screenWidthDp >= 600
