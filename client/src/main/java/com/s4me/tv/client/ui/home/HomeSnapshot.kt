package com.s4me.tv.client.ui.home

import com.s4me.tv.engine.HomeSection
import com.s4me.tv.engine.StreamItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Home has already fetched that other screens can show for free. Search's pre-query state lists
 * the catalogue's trending titles before anything is typed, and taking them from here costs no
 * request to the site — which matters, see engine/SiteTraffic. Empty until Home has loaded once
 * (the pre-query state then simply omits the list).
 */
object HomeSnapshot {
  @Volatile var trending: List<StreamItem> = emptyList()

  private val _sections = MutableStateFlow<List<HomeSection>>(emptyList())

  /** The Home's catalogue and chart rows (not the personal ones, not the per-genre ones), in Home's
   *  order — what the "Novità" tab lists again as a feed. A flow, not a plain field: the chart rows
   *  land a few seconds after the site's own, and that tab can already be open by then. */
  val sections: StateFlow<List<HomeSection>> = _sections.asStateFlow()

  fun publishSections(rows: List<HomeSection>) {
    _sections.value = rows
  }
}
