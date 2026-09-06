package com.s4me.tv.client.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.s4me.tv.client.ui.components.ErrorScreen
import com.s4me.tv.client.ui.components.LoadingScreen
import com.s4me.tv.client.ui.components.PosterCard
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem

/** Routes by kind: a series → season-picker + episode list, a season → episode list, everything
 *  else (the filterable catalog, a category) → the poster grid with the filter bar. */
@Composable
fun BrowseScreen(root: StreamItem, onOpen: (StreamItem) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
  when (root.kind) {
    ItemKind.SERIES -> SeriesScreen(series = root, onOpen = onOpen, onBack = onBack, modifier = modifier)
    ItemKind.SEASON -> SeasonEpisodesScreen(season = root, onOpen = onOpen, onBack = onBack, modifier = modifier)
    else -> BrowseGrid(root = root, onOpen = onOpen, onBack = onBack, modifier = modifier)
  }
}

@Composable
private fun BrowseGrid(root: StreamItem, onOpen: (StreamItem) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val viewModel: BrowseViewModel = viewModel(key = root.url + "|" + root.extra.orEmpty()) { BrowseViewModel(root) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val filter by viewModel.filter.collectAsStateWithLifecycle()
  val genres by viewModel.genreOptions.collectAsStateWithLifecycle()
  val gridState = rememberLazyGridState()

  LaunchedEffect(gridState, state) {
    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
      .collect { last ->
        val total = (state as? BrowseUiState.Success)?.items?.size ?: 0
        if (total > 0 && last >= total - 6) viewModel.loadMore()
      }
  }

  Column(modifier = modifier.fillMaxSize()) {
    TitleRow(root.title, onBack)
    if (viewModel.filterable) {
      FilterBar(
        filter = filter,
        genres = genres,
        years = viewModel.years,
        onChange = viewModel::setFilter,
      )
    }
    when (val s = state) {
      is BrowseUiState.Loading -> LoadingScreen()
      is BrowseUiState.Error -> ErrorScreen(s.message, onRetry = viewModel::loadFirst)
      is BrowseUiState.Success ->
        if (s.items.isEmpty()) {
          Text("Nessun titolo con questi filtri.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        } else {
          LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 112.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
          ) {
            items(s.items, key = { it.channelId + it.url }) { item ->
              PosterCard(item = item, onClick = { onOpen(item) }, width = 112.dp, showLabel = false)
            }
            if (s.loadingMore) {
              item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
              }
            }
          }
        }
    }
  }
}

@Composable
private fun FilterBar(filter: BrowseFilter, genres: List<GenreOption>, years: List<Int>, onChange: (BrowseFilter) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FilterDropdown(
      label = "Tipo",
      current = when (filter.type) {
        "movie" -> "Film"
        "tv" -> "Serie TV"
        else -> null
      },
      options = listOf("Tutti" to null, "Film" to "movie", "Serie TV" to "tv"),
      onPick = { onChange(filter.copy(type = it)) },
    )
    FilterDropdown(
      label = "Genere",
      current = genres.firstOrNull { it.id == filter.genreId }?.name,
      options = listOf("Tutti" to null) + genres.map { it.name to it.id.toString() },
      onPick = { onChange(filter.copy(genreId = it?.toIntOrNull())) },
    )
    FilterDropdown(
      label = "Anno",
      current = filter.year?.toString(),
      options = listOf("Tutti" to null) + years.map { it.toString() to it.toString() },
      onPick = { onChange(filter.copy(year = it?.toIntOrNull())) },
    )
    FilterDropdown(
      label = "Ordina",
      current = when (filter.sort) {
        "release" -> "Uscita"
        "score" -> "Voto"
        else -> null
      },
      options = listOf("Popolari" to null, "Uscita" to "release", "Voto" to "score"),
      onPick = { onChange(filter.copy(sort = it)) },
    )
    FilterDropdown(
      label = "Premi",
      current = when (filter.award) {
        "oscar" -> "Oscar · vincitori"
        "oscar-nominees" -> "Oscar · candidati"
        else -> null
      },
      options = listOf("Nessuno" to null, "Oscar · vincitori" to "oscar", "Oscar · candidati" to "oscar-nominees"),
      onPick = { onChange(filter.copy(award = it)) },
    )
  }
}

@Composable
private fun FilterDropdown(label: String, current: String?, options: List<Pair<String, String?>>, onPick: (String?) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    FilterChip(
      selected = current != null,
      onClick = { open = true },
      label = { Text(if (current != null) "$label: $current" else label) },
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      options.forEach { (text, value) ->
        DropdownMenuItem(
          text = { Text(text) },
          onClick = {
            open = false
            onPick(value)
          },
        )
      }
    }
  }
}

@Composable
private fun TitleRow(title: String, onBack: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("‹", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 4.dp))
    Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 4.dp))
  }
}
