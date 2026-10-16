package com.s4me.tv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.s4me.tv.R
import com.s4me.tv.engine.ChannelRegistry
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.navKeyFor
import com.s4me.tv.ui.components.CoverCard
import com.s4me.tv.ui.components.LoadingIndicator
import com.s4me.tv.ui.components.POSTER_WIDTH
import com.s4me.tv.ui.components.PosterCard
import com.s4me.tv.ui.components.Symbol
import com.s4me.tv.ui.components.SymbolLabel
import com.s4me.tv.ui.home.HomeSnapshot
import java.text.Normalizer

@Composable
fun SearchScreen(
  onNavigate: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
  initialQuery: String? = null,
  isPersonQuery: Boolean = false,
  viewModel: SearchViewModel = viewModel(),
) {
  var query by remember { mutableStateOf(initialQuery.orEmpty()) }
  var sortMode by remember { mutableStateOf(SearchSort.RELEVANCE) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val genres by viewModel.genres.collectAsStateWithLifecycle()
  val catalogRoot = remember { ChannelRegistry.all.firstOrNull()?.catalogRoot() }
  // Genres whose name contains what's typed — local and instant, shown between the field and the
  // title results (Netflix's search is scoped to "titles, genres or people"; ours now is too).
  val genreMatches =
    remember(query, genres) {
      val q = fold(query)
      if (q.length < 2) emptyList() else genres.filter { fold(it.name).contains(q) }.take(6)
    }
  fun openGenre(id: Int) {
    catalogRoot?.let { onNavigate(navKeyFor(it.copy(extra = "genre=$id"))) }
  }
  val fieldFocusRequester = remember { FocusRequester() }
  var fieldFocused by remember { mutableStateOf(false) }
  // The text field otherwise swallows D-pad DOWN (it stays put on the field), so results and
  // history were unreachable with the remote — the classic TV "search box is a dead end" trap.
  // This requester is attached to the first content item below; DOWN on the field jumps to it.
  // With genre matches showing, that first item is the first genre button; otherwise the content
  // below carries it (see [firstModifier]).
  val contentFocusRequester = remember { FocusRequester() }
  val firstModifier = if (genreMatches.isEmpty()) Modifier.focusRequester(contentFocusRequester) else Modifier

  LaunchedEffect(Unit) { runCatching { fieldFocusRequester.requestFocus() } }
  // Arriving from an actor/director tap on Detail (see DetailScreen's Cast/Regia rows) — jump
  // straight to their results instead of an empty box the user would have to retype into.
  LaunchedEffect(initialQuery) { initialQuery?.takeIf { it.isNotBlank() }?.let { viewModel.search(it, verifyPerson = isPersonQuery) } }

  // Search as you type (debounced). A TV on-screen keyboard makes reliably hitting the IME
  // "Search" key fiddly — before this, typing a title and then moving to the results just showed
  // the previous (or no) results ("la ricerca non mi trova..."). The explicit IME action below
  // still works for an instant search.
  LaunchedEffect(query) {
    if (query.isBlank() || query == initialQuery.orEmpty()) return@LaunchedEffect
    kotlinx.coroutines.delay(350)
    viewModel.search(query)
  }

  fun runSearch(q: String) {
    query = q
    viewModel.search(q)
  }

  Column(modifier = modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp)) {
    Text(text = "Cerca", style = MaterialTheme.typography.headlineMedium)
    Text(
      text = "Titoli, generi o persone",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp),
    )

    // Dark, rounded search bar with an amber focus ring — reads as part of the app instead of the
    // bright stock Material field that clashed with the dark TV surface.
    TextField(
      value = query,
      onValueChange = { query = it },
      placeholder = { Text("Cerca titoli, generi o persone", color = Color.White.copy(alpha = 0.4f)) },
      leadingIcon = { Symbol(R.drawable.ic_ms_search, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(start = 8.dp)) },
      singleLine = true,
      shape = RoundedCornerShape(16.dp),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { runSearch(query) }),
      colors =
        TextFieldDefaults.colors(
          focusedContainerColor = Color.White.copy(alpha = 0.10f),
          unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent,
          disabledIndicatorColor = Color.Transparent,
          focusedTextColor = Color.White,
          unfocusedTextColor = Color.White,
          cursorColor = MaterialTheme.colorScheme.primary,
        ),
      modifier =
        Modifier.fillMaxWidth(0.55f)
          .padding(top = 20.dp, bottom = 28.dp)
          .border(
            width = 2.dp,
            color = if (fieldFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = RoundedCornerShape(16.dp),
          )
          .onFocusChanged { fieldFocused = it.isFocused }
          .focusRequester(fieldFocusRequester)
          .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
              runCatching { contentFocusRequester.requestFocus() }.isSuccess
            } else {
              false
            }
          },
    )

    if (genreMatches.isNotEmpty()) {
      SectionTitle("Generi", modifier = Modifier.padding(bottom = 6.dp))
      GenreRow(genreMatches, onClick = ::openGenre, firstModifier = Modifier.focusRequester(contentFocusRequester), modifier = Modifier.padding(bottom = 12.dp))
    }

    Box(modifier = Modifier.fillMaxSize()) {
      when (val s = state) {
        is SearchUiState.Idle ->
          IdleContent(
            history = s.history,
            trending = remember { HomeSnapshot.trending },
            genres = genres,
            onOpen = { onNavigate(navKeyFor(it)) },
            onPick = ::runSearch,
            onClear = viewModel::clearHistory,
            onGenre = ::openGenre,
            firstModifier = firstModifier,
          )
        is SearchUiState.Loading -> LoadingIndicator(modifier = Modifier.align(Alignment.TopStart))
        is SearchUiState.Error -> NoResults(s.message, genres, onGenre = ::openGenre, firstModifier = firstModifier)
        is SearchUiState.Success ->
          if (s.results.isEmpty()) {
            NoResults("Nessun risultato per \"$query\".", genres, onGenre = ::openGenre, firstModifier = firstModifier)
          } else {
            // All results are already in memory (search() isn't paginated), so re-ordering is a
            // free client-side sort — no re-fetch, unlike Sfoglia's server-driven "Ordina".
            val sorted =
              remember(s.results, sortMode) {
                when (sortMode) {
                  SearchSort.RELEVANCE -> s.results
                  SearchSort.SCORE -> s.results.sortedByDescending { it.quality?.removePrefix("★ ")?.toDoubleOrNull() ?: -1.0 }
                  SearchSort.YEAR -> s.results.sortedByDescending { it.year?.toIntOrNull() ?: -1 }
                }
              }
            Column(modifier = Modifier.fillMaxSize()) {
              SectionTitle(
                if (s.results.size == 1) "Film e serie TV · 1 risultato" else "Film e serie TV · ${s.results.size} risultati",
                modifier = Modifier.padding(bottom = 8.dp),
              )
              SearchSortRow(selected = sortMode, onSelect = { sortMode = it })
              LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = POSTER_WIDTH),
                modifier = Modifier.weight(1f),
                // `start`/`top` absorb the same focus-scale edge-clip as DetailScreen's saga row —
                // see the comment there.
                contentPadding = PaddingValues(start = 8.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
              ) {
                itemsIndexed(sorted, key = { _, it -> it.channelId + it.url }) { index, item ->
                  PosterCard(
                    item = item,
                    onClick = { onNavigate(navKeyFor(item)) },
                    cardModifier = if (index == 0) firstModifier else Modifier,
                  )
                }
              }
            }
          }
      }
    }
  }
}

private enum class SearchSort(val label: String) {
  RELEVANCE("Rilevanza"),
  SCORE("Punteggio più alto"),
  YEAR("Anno"),
}

/** Same "Ordina" idea as Sfoglia's filter bar, scaled down to three toggle pills since search
 *  results are a flat in-memory list, not a paginated server query — no picker dialog needed. */
@Composable
private fun SearchSortRow(selected: SearchSort, onSelect: (SearchSort) -> Unit, modifier: Modifier = Modifier) {
  val primary = MaterialTheme.colorScheme.primary
  Row(modifier = modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    for (option in SearchSort.entries) {
      val isSelected = option == selected
      Surface(
        onClick = { onSelect(option) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors =
          ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) primary else Color.White.copy(alpha = 0.08f),
            contentColor = if (isSelected) Color.Black else Color.White.copy(alpha = 0.82f),
            focusedContainerColor = if (isSelected) primary else Color.White.copy(alpha = 0.24f),
            focusedContentColor = if (isSelected) Color.Black else Color.White,
          ),
      ) {
        Text(text = option.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
      }
    }
  }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
  Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/** Genre shortcuts as a row of buttons — D-pad friendly, one focus line. */
@Composable
private fun GenreRow(genres: List<GenreOption>, onClick: (Int) -> Unit, modifier: Modifier = Modifier, firstModifier: Modifier = Modifier) {
  LazyRow(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
  ) {
    itemsIndexed(genres, key = { _, g -> g.id }) { index, g ->
      Button(onClick = { onClick(g.id) }, modifier = if (index == 0) firstModifier else Modifier) {
        SymbolLabel(R.drawable.ic_ms_explore, g.name)
      }
    }
  }
}

/**
 * Before anything is typed — what Netflix's own search calls a pre-query state: trending titles
 * (from Home's already-fetched row, so no site request), recent searches, and genres to browse.
 * [firstModifier] carries the field's DOWN target, so it goes on the first section that exists.
 */
@Composable
private fun IdleContent(
  history: List<String>,
  trending: List<StreamItem>,
  genres: List<GenreOption>,
  onOpen: (StreamItem) -> Unit,
  onPick: (String) -> Unit,
  onClear: () -> Unit,
  onGenre: (Int) -> Unit,
  firstModifier: Modifier,
) {
  if (trending.isEmpty() && history.isEmpty() && genres.isEmpty()) {
    Column(modifier = Modifier.padding(top = 40.dp)) {
      Symbol(R.drawable.ic_ms_search, size = 40.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(text = "Cosa hai voglia di guardare?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
      Text(
        text = "Digita un titolo, un genere o il nome di una persona.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
    return
  }
  val trendingFirst = trending.isNotEmpty()
  val historyFirst = !trendingFirst && history.isNotEmpty()
  LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
    if (trendingFirst) {
      item { SectionTitle("Di tendenza") }
      item {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(start = 8.dp, top = 8.dp, bottom = 8.dp)) {
          itemsIndexed(trending, key = { _, it -> it.channelId + it.url }) { index, item ->
            CoverCard(item = item, onClick = { onOpen(item) }, showLabel = true, cardModifier = if (index == 0) firstModifier else Modifier)
          }
        }
      }
    }
    if (history.isNotEmpty()) {
      item { SectionTitle("Ricerche recenti", modifier = Modifier.padding(top = 8.dp)) }
      itemsIndexed(history, key = { _, it -> "h:$it" }) { index, q ->
        Button(
          onClick = { onPick(q) },
          modifier = (if (historyFirst && index == 0) firstModifier else Modifier).fillMaxWidth(0.55f),
        ) {
          SymbolLabel(R.drawable.ic_ms_history, q)
        }
      }
      item { Button(onClick = onClear) { Text("Cancella cronologia") } }
    }
    if (genres.isNotEmpty()) {
      item { SectionTitle("Sfoglia per genere", modifier = Modifier.padding(top = 8.dp)) }
      item { GenreRow(genres, onClick = onGenre, firstModifier = if (!trendingFirst && !historyFirst) firstModifier else Modifier) }
    }
  }
}

/** Nothing matched: say what to try, and keep the genres within D-pad reach. */
@Composable
private fun NoResults(message: String, genres: List<GenreOption>, onGenre: (Int) -> Unit, firstModifier: Modifier) {
  Column {
    Text(text = message, style = MaterialTheme.typography.bodyLarge)
    Text(
      text = "Prova con un altro titolo, attore, regista o genere.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp),
    )
    if (genres.isNotEmpty()) {
      SectionTitle("Oppure sfoglia per genere", modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
      GenreRow(genres, onClick = onGenre, firstModifier = firstModifier)
    }
  }
}

private val DIACRITICS = Regex("\\p{M}+")

/** Accent- and case-insensitive form, so "comm" finds "Commedia" and "azione" finds "Azione". */
private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase().trim()
