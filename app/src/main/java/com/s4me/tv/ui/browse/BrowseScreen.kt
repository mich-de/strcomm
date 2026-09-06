package com.s4me.tv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as itemsIndexedList
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.s4me.tv.Search
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.navKeyFor
import com.s4me.tv.ui.components.EpisodeCard
import com.s4me.tv.ui.components.WatchlistToggleButton
import com.s4me.tv.ui.components.LoadingIndicator
import com.s4me.tv.ui.components.NameChipsRow
import com.s4me.tv.ui.components.POSTER_WIDTH
import com.s4me.tv.ui.components.PosterCard
import kotlinx.coroutines.delay
import java.util.Calendar

@Composable
fun BrowseScreen(root: StreamItem, onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  val viewModel: BrowseViewModel = viewModel(key = root.url) { BrowseViewModel(root) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val genreOptions by viewModel.genreOptions.collectAsStateWithLifecycle()
  val filter by viewModel.filter.collectAsStateWithLifecycle()
  val seriesInfo by viewModel.seriesInfo.collectAsStateWithLifecycle()
  val seasonPlotIt by viewModel.seasonPlotIt.collectAsStateWithLifecycle()

  // The genre/year/type/sort filters only make sense for the actual "Sfoglia" catalog root —
  // they showed up (nonsensically) while browsing a series' seasons too before this check existed,
  // which is exactly the kind of thing that reads as "the app is confusing/broken" to a user.
  val isFilterableRoot = root.kind == ItemKind.LIST
  var pickerFor by remember { mutableStateOf<PickerKind?>(null) }
  val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
  val years = remember(currentYear) { (currentYear downTo currentYear - 24).toList() }

  Column(modifier = modifier.fillMaxSize()) {
    if (isFilterableRoot) {
      // Single-line header: title + one dropdown pill per filter. The old five stacked chip rows
      // ate half of the 540dp-tall screen and changing the year meant traversing every row, then
      // scrolling a 25-chip strip sideways ("poco compatto e macchinoso"); a pill opens a centered
      // vertical picker instead, and the grid gets the screen back.
      CompactFilterHeader(
        title = root.title,
        filter = filter,
        genreOptions = genreOptions,
        onOpenPicker = { pickerFor = it },
      )
    } else {
      Text(
        text = root.title,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(start = 48.dp, top = 28.dp),
      )
      // Series land here (their "detail" is the seasons list), so this is where their
      // "La mia lista" toggle lives; movies get it on the Detail screen.
      if (root.kind == ItemKind.SERIES) {
        WatchlistToggleButton(item = root, modifier = Modifier.padding(start = 48.dp, top = 12.dp))
        // seriesInfo loads separately (root itself only carries the bare seasons-list fields) —
        // stays absent instead of blocking the seasons grid below while it resolves.
        SeriesInfo(info = seriesInfo, onNavigate = onNavigate)
      }
      // The season carries its own plot from the seasons-list fetch (English on titles the source
      // never localised); seasonPlotIt is the Italian one, resolved async, preferred when it lands.
      if (root.kind == ItemKind.SEASON) {
        (seasonPlotIt ?: root.plot)?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 8.dp).widthIn(max = 900.dp),
          )
        }
      }
    }
    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
      when (val s = state) {
        is BrowseUiState.Loading -> LoadingIndicator(modifier = Modifier.align(Alignment.Center))
        is BrowseUiState.Error -> Text(text = s.message, modifier = Modifier.align(Alignment.Center))
        is BrowseUiState.Success ->
          // Episodes get Netflix-style horizontal cards (still + title + synopsis); everything else
          // (catalog, seasons, categories) stays a poster grid.
          if (root.kind == ItemKind.SEASON) {
            if (s.items.isEmpty()) {
              // Announced season with no episodes yet ("The Gentlemen Stagione 2 non si apre"):
              // an explicit message instead of a silently blank page.
              Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "🎬", style = MaterialTheme.typography.headlineMedium)
                Text(
                  text = "Nessun episodio ancora disponibile",
                  style = MaterialTheme.typography.titleMedium,
                  modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                  text = "Questa stagione è stata annunciata ma gli episodi non sono ancora usciti.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(top = 4.dp),
                )
              }
            } else {
              EpisodeList(items = s.items, onNavigate = onNavigate)
            }
          } else {
            BrowseGrid(
              items = s.items,
              hasMore = s.hasMore,
              loadingMore = s.loadingMore,
              onNavigate = onNavigate,
              onLoadMore = viewModel::loadMore,
            )
          }
      }
    }
  }

  pickerFor?.let { kind ->
    val spec = pickerSpecFor(kind, filter, genreOptions, years)
    FilterPickerDialog(
      spec = spec,
      onSelect = { value ->
        pickerFor = null
        viewModel.setFilter(spec.apply(filter, value))
      },
      onDismiss = { pickerFor = null },
    )
  }
}

/** Genres/plot/cast/director for the series itself, sitting above the seasons grid. `info` is
 *  null until BrowseViewModel's async detail() call resolves, so everything degrades to simply
 *  not rendering yet rather than showing a placeholder. */
@Composable
private fun SeriesInfo(info: StreamItem?, onNavigate: (NavKey) -> Unit) {
  Column(modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 8.dp).widthIn(max = 900.dp)) {
    info?.genres?.let {
      Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    info?.plot?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
      )
    }
    info?.cast?.let {
      NameChipsRow(
        label = "Cast",
        names = it.split(", "),
        onNameClick = { name -> onNavigate(Search(initialQuery = name, isPersonQuery = true)) },
        modifier = Modifier.padding(top = 10.dp),
      )
    }
    info?.director?.let {
      NameChipsRow(
        label = "Regia",
        names = it.split(", "),
        onNameClick = { name -> onNavigate(Search(initialQuery = name, isPersonQuery = true)) },
        modifier = Modifier.padding(top = 6.dp),
      )
    }
  }
}

private enum class PickerKind { TYPE, GENRE, YEAR, SORT, AWARD }

/** Everything one picker dialog needs: its title, the option list, what's selected now, and how a
 *  chosen value folds back into the filter. Values stay `Any?` because filters mix String?/Int?. */
private data class PickerSpec(
  val title: String,
  val options: List<Pair<Any?, String>>,
  val selected: Any?,
  val apply: (BrowseFilter, Any?) -> BrowseFilter,
)

private fun pickerSpecFor(kind: PickerKind, filter: BrowseFilter, genreOptions: List<GenreOption>, years: List<Int>): PickerSpec =
  when (kind) {
    PickerKind.TYPE ->
      PickerSpec(
        title = "Tipo",
        options = listOf(null to "Tutti", "movie" to "Film", "tv" to "Serie"),
        selected = filter.type,
        apply = { f, v -> f.copy(type = v as String?) },
      )
    PickerKind.GENRE ->
      PickerSpec(
        title = "Genere",
        options = listOf<Pair<Any?, String>>(null to "Tutti") + genreOptions.map { it.id to it.name },
        selected = filter.genreId,
        apply = { f, v -> f.copy(genreId = v as Int?) },
      )
    PickerKind.YEAR ->
      PickerSpec(
        title = "Anno",
        options = listOf<Pair<Any?, String>>(null to "Tutti") + years.map { it to it.toString() },
        selected = filter.year,
        apply = { f, v -> f.copy(year = v as Int?) },
      )
    PickerKind.SORT ->
      PickerSpec(
        title = "Ordina per",
        options = listOf(null to "Più popolari", "release" to "Data di uscita", "score" to "Punteggio più alto"),
        selected = filter.sort,
        apply = { f, v -> f.copy(sort = v as String?) },
      )
    PickerKind.AWARD ->
      PickerSpec(
        title = "Premi",
        options = listOf(null to "Tutti", "oscar" to "🏆 Oscar · Miglior film", "oscar-nominees" to "🎬 Oscar · Candidati"),
        selected = filter.award,
        apply = { f, v -> f.copy(award = v as String?) },
      )
  }

@Composable
private fun CompactFilterHeader(
  title: String,
  filter: BrowseFilter,
  genreOptions: List<GenreOption>,
  onOpenPicker: (PickerKind) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = title, style = MaterialTheme.typography.headlineSmall)
    Row(
      // horizontalScroll is a just-in-case: with the short value labels below all five pills fit
      // 960dp side by side, but a long translated genre name must degrade to scrolling, not clip.
      modifier = Modifier.weight(1f).padding(start = 28.dp).horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilterPill(
        label = "Tipo",
        value = when (filter.type) { null -> "Tutti"; "movie" -> "Film"; else -> "Serie" },
        active = filter.type != null,
        onClick = { onOpenPicker(PickerKind.TYPE) },
      )
      if (genreOptions.isNotEmpty()) {
        FilterPill(
          label = "Genere",
          value = genreOptions.firstOrNull { it.id == filter.genreId }?.name ?: "Tutti",
          active = filter.genreId != null,
          onClick = { onOpenPicker(PickerKind.GENRE) },
        )
      }
      FilterPill(
        label = "Anno",
        value = filter.year?.toString() ?: "Tutti",
        active = filter.year != null,
        onClick = { onOpenPicker(PickerKind.YEAR) },
      )
      FilterPill(
        label = "Ordina",
        value = when (filter.sort) { null -> "Popolari"; "release" -> "Uscita"; else -> "Punteggio" },
        active = filter.sort != null,
        onClick = { onOpenPicker(PickerKind.SORT) },
      )
      FilterPill(
        label = "Premi",
        value = when (filter.award) { null -> "Tutti"; "oscar" -> "🏆 Vincitori"; else -> "🎬 Candidati" },
        active = filter.award != null,
        onClick = { onOpenPicker(PickerKind.AWARD) },
      )
    }
  }
}

/** A compact dropdown pill: dim category label + current value + ▾. Amber when its filter deviates
 *  from the default, so active filters stay visible at a glance even with the pickers closed. */
@Composable
private fun FilterPill(label: String, value: String, active: Boolean, onClick: () -> Unit) {
  val primary = MaterialTheme.colorScheme.primary
  Surface(
    onClick = onClick,
    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    colors =
      ClickableSurfaceDefaults.colors(
        containerColor = if (active) primary else Color.White.copy(alpha = 0.08f),
        contentColor = if (active) Color.Black else Color.White.copy(alpha = 0.85f),
        focusedContainerColor = if (active) primary else Color.White.copy(alpha = 0.24f),
        focusedContentColor = if (active) Color.Black else Color.White,
      ),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LocalContentColor.current.copy(alpha = 0.65f),
      )
      Text(
        text = value,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 130.dp),
      )
      Text(
        text = "▾",
        style = MaterialTheme.typography.labelSmall,
        color = LocalContentColor.current.copy(alpha = 0.65f),
      )
    }
  }
}

/** Centered vertical option list. A vertical list beats a horizontal chip strip for anything long
 *  (25 years, 15 genres): hold-DOWN flies through it, and it opens pre-scrolled with the current
 *  choice focused, so BACK/confirm are both a single press. */
@Composable
private fun FilterPickerDialog(spec: PickerSpec, onSelect: (Any?) -> Unit, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    val selectedIndex = spec.options.indexOfFirst { it.first == spec.selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    val selectedFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
      // Give the dialog window a beat to attach before grabbing focus, or the request is dropped.
      delay(100)
      runCatching { selectedFocusRequester.requestFocus() }
    }
    Column(
      modifier =
        Modifier.width(380.dp)
          .heightIn(max = 440.dp)
          .background(Color(0xFF161921), RoundedCornerShape(14.dp))
          .padding(vertical = 18.dp),
    ) {
      Text(
        text = spec.title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 26.dp, bottom = 10.dp),
      )
      LazyColumn(
        state = listState,
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        itemsIndexedList(spec.options) { index, (value, label) ->
          val isSelected = index == selectedIndex
          Surface(
            onClick = { onSelect(value) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors =
              ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f),
                focusedContainerColor = Color.White.copy(alpha = 0.22f),
                focusedContentColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
              ),
            modifier = if (isSelected) Modifier.focusRequester(selectedFocusRequester) else Modifier,
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              if (isSelected) Text(text = "✓", style = MaterialTheme.typography.labelLarge)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun EpisodeList(items: List<StreamItem>, onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  val firstItemFocusRequester = remember(items.firstOrNull()?.url) { FocusRequester() }
  LaunchedEffect(items.firstOrNull()?.url) {
    if (items.isNotEmpty()) runCatching { firstItemFocusRequester.requestFocus() }
  }
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    itemsIndexedList(items, key = { _, it -> it.url }) { index, item ->
      EpisodeCard(
        item = item,
        onClick = { onNavigate(navKeyFor(item)) },
        cardModifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier,
      )
    }
  }
}

@Composable
private fun BrowseGrid(
  items: List<StreamItem>,
  hasMore: Boolean,
  loadingMore: Boolean,
  onNavigate: (NavKey) -> Unit,
  onLoadMore: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val firstItemFocusRequester = remember(items.firstOrNull()?.url) { FocusRequester() }
  LaunchedEffect(items.firstOrNull()?.url) {
    if (items.isNotEmpty()) runCatching { firstItemFocusRequester.requestFocus() }
  }

  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = POSTER_WIDTH),
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 14.dp, bottom = 24.dp),
    horizontalArrangement = Arrangement.spacedBy(22.dp),
    verticalArrangement = Arrangement.spacedBy(30.dp),
  ) {
    itemsIndexed(items, key = { _, it -> it.url }) { index, item ->
      PosterCard(
        item = item,
        onClick = { onNavigate(navKeyFor(item)) },
        cardModifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier,
      )
    }
    if (hasMore) {
      item {
        if (loadingMore) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
        } else {
          Button(onClick = onLoadMore) { Text("Carica altri") }
        }
      }
    }
  }
}
