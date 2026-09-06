package com.s4me.tv.client.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.s4me.tv.client.ui.components.EpisodeRow
import com.s4me.tv.client.ui.components.LoadingScreen
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchlistStore

@Composable
fun SeriesScreen(series: StreamItem, onOpen: (StreamItem) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val vm: SeriesViewModel = viewModel(key = series.url) { SeriesViewModel(series) }
  val info by vm.info.collectAsStateWithLifecycle()
  val seasons by vm.seasons.collectAsStateWithLifecycle()
  val selected by vm.selectedSeason.collectAsStateWithLifecycle()
  val episodes by vm.episodes.collectAsStateWithLifecycle()

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
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
  val watchlist = remember { WatchlistStore(context) }
  var inList by remember(info.url) { mutableStateOf(watchlist.contains(info.url)) }

  Column {
    Box(modifier = Modifier.fillMaxWidth().height(240.dp)) {
      AsyncImage(
        model = info.backdrop ?: info.thumbnail,
        contentDescription = info.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
      )
      Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to MaterialTheme.colorScheme.background)))
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
      Text(info.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
      val badge = (info.imdbRating ?: info.tmdbRating)?.let { "★ %.1f".format(it) } ?: info.quality
      val meta = listOfNotNull(info.year, badge, info.genres).joinToString("  ·  ")
      if (meta.isNotBlank()) {
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
      }
      FilledTonalButton(onClick = { inList = watchlist.toggle(info) }, modifier = Modifier.padding(top = 10.dp)) {
        Text(if (inList) "✓  Nella lista" else "＋  La mia lista")
      }
      info.plot?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, modifier = Modifier.padding(top = 12.dp)) }
    }
  }
}

@Composable
private fun BoxScope.BackButton(onBack: () -> Unit) {
  Text(
    "‹",
    style = MaterialTheme.typography.headlineMedium,
    color = Color.White,
    modifier =
      Modifier.align(Alignment.TopStart)
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(8.dp)
        .background(Color.Black.copy(alpha = 0.4f), androidx.compose.foundation.shape.RoundedCornerShape(50))
        .clickable(onClick = onBack)
        .padding(horizontal = 16.dp, vertical = 4.dp),
  )
}
