package com.s4me.tv.ui.home

import com.s4me.tv.engine.StreamItem

/**
 * What Home has already fetched that other screens can show for free. Search's pre-query state lists
 * the catalogue's trending titles before anything is typed, and taking them from here costs no
 * request to the site (see engine/SiteTraffic). Empty until Home has loaded once — the pre-query
 * state then simply omits the row.
 */
object HomeSnapshot {
  @Volatile var trending: List<StreamItem> = emptyList()
}
