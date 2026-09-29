package com.s4me.tv.client.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface EpisodesState {
  data object Loading : EpisodesState

  data class Ready(val items: List<StreamItem>) : EpisodesState

  data object Empty : EpisodesState
}

/** Backs the series screen: enriched series info + its seasons + the episode list for whichever
 *  season is selected (first by default). Netflix-style — one screen, a season picker, an episode
 *  list, no separate "seasons grid". */
class SeriesViewModel(private val series: StreamItem) : ViewModel() {
  private val _info = MutableStateFlow(series)
  val info: StateFlow<StreamItem> = _info.asStateFlow()

  private val _seasons = MutableStateFlow<List<StreamItem>>(emptyList())
  val seasons: StateFlow<List<StreamItem>> = _seasons.asStateFlow()

  private val _selectedSeason = MutableStateFlow<StreamItem?>(null)
  val selectedSeason: StateFlow<StreamItem?> = _selectedSeason.asStateFlow()

  private val _episodes = MutableStateFlow<EpisodesState>(EpisodesState.Loading)
  val episodes: StateFlow<EpisodesState> = _episodes.asStateFlow()

  private var episodesJob: Job? = null

  init {
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.byId(series.channelId) ?: return@launch
      launch { runCatching { channel.detail(series) }.getOrNull()?.let { _info.value = it } }
      val seasons = runCatching { channel.list(series) }.getOrNull()?.items.orEmpty()
      _seasons.value = seasons
      if (seasons.isEmpty()) _episodes.value = EpisodesState.Empty else selectSeason(seasons.first())
    }
  }

  fun selectSeason(season: StreamItem) {
    _selectedSeason.value = season
    episodesJob?.cancel()
    _episodes.value = EpisodesState.Loading
    episodesJob =
      viewModelScope.launch(Dispatchers.IO) {
        val channel = ChannelRegistry.byId(season.channelId) ?: return@launch
        val eps = runCatching { channel.list(season) }.getOrNull()?.items.orEmpty()
        _episodes.value = if (eps.isEmpty()) EpisodesState.Empty else EpisodesState.Ready(eps)
      }
  }
}

/** Just the episode list for one SEASON (deep link / not entered through a series). */
class SeasonViewModel(private val season: StreamItem) : ViewModel() {
  private val _episodes = MutableStateFlow<EpisodesState>(EpisodesState.Loading)
  val episodes: StateFlow<EpisodesState> = _episodes.asStateFlow()

  init {
    viewModelScope.launch(Dispatchers.IO) {
      val channel = ChannelRegistry.byId(season.channelId) ?: return@launch
      val eps = runCatching { channel.list(season) }.getOrNull()?.items.orEmpty()
      _episodes.value = if (eps.isEmpty()) EpisodesState.Empty else EpisodesState.Ready(eps)
    }
  }
}
