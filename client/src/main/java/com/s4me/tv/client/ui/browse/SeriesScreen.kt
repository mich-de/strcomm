package com.s4me.tv.client.ui.browse

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.s4me.tv.client.R
import com.s4me.tv.client.ui.components.BackCircleButton
import com.s4me.tv.client.ui.components.EpisodeRow
import com.s4me.tv.client.ui.components.GreyButton
import com.s4me.tv.client.ui.components.LoadingScreen
import com.s4me.tv.client.ui.components.LocalTopInset
import com.s4me.tv.client.ui.components.StatusBarScrim
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchlistStore
import com.s4me.tv.engine.youtubeVideoId

@Composable
fun SeriesScreen(series: StreamItem, onOpen: (StreamItem) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val vm: SeriesViewModel = viewModel(key = series.url) { SeriesViewModel(series) }
  val info by vm.info.collectAsStateWithLifecycle()
  val seasons by vm.seasons.collectAsStateWithLifecycle()
  val selected by vm.selectedSeason.collectAsStateWithLifecycle()
  val episodes by vm.episodes.collectAsStateWithLifecycle()

  val listState = rememberLazyListState()
  // The status bar turns solid as the header picture scrolls away.
  val scrimRamp = with(LocalDensity.current) { 180.dp.toPx() }
  val scrimProgress by remember { derivedStateOf { if (listState.firstVisibleItemIndex > 0) 1f else listState.firstVisibleItemScrollOffset / scrimRamp } }

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
      item { SeriesHeader(info = info) }
      if (seasons.size > 1) {
        item {
          LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 12.dp),
          ) {
            items(seasons, key = { it.url }) { s ->
              FilterChip(
                selected = s.url == selected?.url,
                onClick = { vm.selectSeason(s) },
                label = { Text("Stagione ${s.season ?: "?"}") },
              )
            }
          }
        }
      }
      when (val e = episodes) {
        is EpisodesState.Loading -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        is EpisodesState.Empty -> item { Text("Nessun episodio.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
        is EpisodesState.Ready ->
          items(e.items, key = { it.channelId + it.url }) { ep ->
            EpisodeRow(item = ep, onClick = { onOpen(ep) }, modifier = Modifier.padding(horizontal = 16.dp))
          }
      }
    }
    // The back button's row (40 dp + its 8 dp margins) is part of the bar once the page has scrolled.
    StatusBarScrim(scrimProgress, extra = 56.dp)
    BackButton(onBack)
  }
}

@Composable
fun SeasonEpisodesScreen(season: StreamItem, onOpen: (StreamItem) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val vm: SeasonViewModel = viewModel(key = season.url) { SeasonViewModel(season) }
  val episodes by vm.episodes.collectAsStateWithLifecycle()

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    when (val e = episodes) {
      is EpisodesState.Loading -> LoadingScreen()
      is EpisodesState.Empty -> Text("Nessun episodio.", modifier = Modifier.padding(16.dp).padding(top = 48.dp))
      is EpisodesState.Ready ->
        LazyColumn(contentPadding = PaddingValues(top = 56.dp, bottom = 24.dp)) {
          item {
            Text(
              season.seriesTitle ?: season.title,
              style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
              modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
            )
            Text("Stagione ${season.season ?: "?"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
          }
          items(e.items, key = { it.channelId + it.url }) { ep ->
            EpisodeRow(item = ep, onClick = { onOpen(ep) }, modifier = Modifier.padding(horizontal = 16.dp))
          }
        }
    }
    BackButton(onBack)
  }
}

@Composable
private fun SeriesHeader(info: StreamItem) {
  val context = LocalContext.current
  // Status bar height this page covers itself: the header picture runs up under it (see LocalTopInset).
  val topInset = LocalTopInset.current
  val watchlist = remember { WatchlistStore(context) }
  var inList by remember(info.url) { mutableStateOf(watchlist.contains(info.url)) }

  Column {
    Box(modifier = Modifier.fillMaxWidth().height(240.dp + topInset)) {
      AsyncImage(
        model = info.backdrop ?: info.thumbnail,
        contentDescription = info.title,
        contentScale = ContentScale.Crop,
        // Crop from the top so faces aren't sliced off (same as the movie detail header).
        alignment = Alignment.TopCenter,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
      )
      Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to MaterialTheme.colorScheme.background)))
      // Under the status bar and the back button, so they read on a bright picture.
      Box(Modifier.fillMaxWidth().height(topInset + 64.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
      Text(info.title, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
      val badge = (info.imdbRating ?: info.tmdbRating)?.let { "★ %.1f".format(it) } ?: info.quality
      val meta = listOfNotNull(info.year, badge, info.genres).joinToString("  ·  ")
      if (meta.isNotBlank()) {
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
      }
      Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GreyButton(
          if (inList) "Nella lista" else "La mia lista",
          if (inList) R.drawable.ic_ms_check else R.drawable.ic_ms_add,
          onClick = { inList = watchlist.toggle(info) },
          modifier = Modifier.weight(1f),
        )
        info.trailerYoutubeId?.let { id -> GreyButton("Trailer", R.drawable.ic_ms_movie, onClick = { openSeriesTrailer(context, id) }, modifier = Modifier.weight(1f)) }
      }
      info.plot?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, modifier = Modifier.padding(top = 12.dp)) }
    }
  }
}

/** Opens the trailer in whatever YouTube handler the device has (mirrors DetailScreen). */
private fun openSeriesTrailer(context: Context, videoId: String) {
  val id = youtubeVideoId(videoId) ?: return
  val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  runCatching { context.startActivity(intent) }
}

@Composable
private fun BoxScope.BackButton(onBack: () -> Unit) {
  BackCircleButton(onBack = onBack, modifier = Modifier.align(Alignment.TopStart).padding(top = LocalTopInset.current))
}
