package com.s4me.tv.client.ui.search

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.s4me.tv.client.R
import com.s4me.tv.client.notifications.AvailabilityWorker
import com.s4me.tv.client.ui.components.PosterCard
import com.s4me.tv.client.ui.components.Symbol
import com.s4me.tv.client.ui.components.SymbolLabel
import com.s4me.tv.client.ui.home.HomeSnapshot
import com.s4me.tv.engine.AvailabilityReminderStore
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.StreamItem
import java.text.Normalizer

private val DIACRITICS = Regex("\\p{M}+")

/** Accent- and case-insensitive form, so "comm" finds "Commedia" and "azione" finds "Azione". */
private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase().trim()

/**
 * The search tab: one box for "titoli, generi o persone" — the scope Netflix's own search field
 * advertises ("Search for titles, genres or people") — with the three states its flow has: before
 * typing (recent searches + genres to browse), while typing (genre matches on top of the title
 * results) and no match (a hint about what to try, plus genres). Voice input goes through the
 * system recogniser. Written from the behaviour visible in its manifest and UI strings; no
 * Netflix code or assets are used.
 */
@Composable
fun SearchScreen(
  onOpen: (StreamItem) -> Unit,
  onOpenGenre: (Int) -> Unit,
  modifier: Modifier = Modifier,
  initialQuery: String? = null,
  isPersonQuery: Boolean = false,
  viewModel: SearchViewModel = viewModel(),
) {
  var query by remember { mutableStateOf(initialQuery.orEmpty()) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val genres by viewModel.genres.collectAsStateWithLifecycle()
  val context = LocalContext.current

  LaunchedEffect(initialQuery) {
    initialQuery?.takeIf { it.isNotBlank() }?.let { viewModel.search(it, verifyPerson = isPersonQuery) }
  }

  // Voice search: the system recogniser's own screen, so no RECORD_AUDIO permission. The button only
  // shows when a recogniser exists (the manifest's <queries> makes that answer truthful on 11+).
  val voiceIntent =
    remember {
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Cerca titoli, generi o persone")
      }
    }
  val voiceAvailable = remember { voiceIntent.resolveActivity(context.packageManager) != null }
  // "Avvisami quando è disponibile": remembers the search and lets AvailabilityWorker re-run it. On
  // Android 13+ the notification permission is asked for first; the reminder is stored either way.
  val reminders = remember { AvailabilityReminderStore(context) }
  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  fun remind(what: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val added = reminders.add(what)
    AvailabilityWorker.schedule(context)
    Toast.makeText(context, if (added) "Ti avviso quando «$what» sarà disponibile" else "Hai già un promemoria per «$what»", Toast.LENGTH_LONG).show()
  }
  val voiceLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
      val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
      if (!spoken.isNullOrBlank()) {
        query = spoken
        viewModel.search(spoken)
      }
    }

  // Genres whose name contains what's typed — local and instant, shown above the title results.
  val genreMatches =
    remember(query, genres) {
      val q = fold(query)
      if (q.length < 2) emptyList() else genres.filter { fold(it.name).contains(q) }.take(4)
    }

  Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
    TextField(
      value = query,
      onValueChange = {
        query = it
        viewModel.onQueryChange(it)
      },
      placeholder = { Text("Cerca titoli, generi o persone") },
      singleLine = true,
      shape = RoundedCornerShape(28.dp),
      colors =
        TextFieldDefaults.colors(
          focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
          unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent,
          disabledIndicatorColor = Color.Transparent,
        ),
      leadingIcon = { Symbol(R.drawable.ic_ms_search, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
      trailingIcon = {
        when {
          query.isNotEmpty() ->
            IconButton(
              onClick = {
                query = ""
                viewModel.onQueryChange("")
              }
            ) {
              Symbol(R.drawable.ic_ms_close, contentDescription = "Cancella la ricerca")
            }
          voiceAvailable ->
            IconButton(onClick = { runCatching { voiceLauncher.launch(voiceIntent) } }) {
              Symbol(R.drawable.ic_ms_mic, contentDescription = "Ricerca vocale")
            }
        }
      },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }),
      modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )

    if (genreMatches.isNotEmpty()) {
      SectionTitle("Generi")
      GenreChips(genreMatches, onOpenGenre, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    }

    when (val s = state) {
      is SearchUiState.Idle ->
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          val trending = remember { HomeSnapshot.trending }
          if (trending.isNotEmpty()) {
            SectionTitle("Di tendenza", modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            trending.forEach { item -> TrendingRow(item, onClick = { onOpen(item) }) }
          }
          if (s.history.isNotEmpty()) {
            SectionTitle("Ricerche recenti", modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            s.history.forEach { h ->
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                  query = h
                  viewModel.search(h)
                }.padding(vertical = 12.dp),
              ) {
                Symbol(R.drawable.ic_ms_history, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp)
                Spacer(Modifier.width(16.dp))
                Text(h)
              }
            }
            Text(
              "Cancella cronologia",
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.clickable(onClick = viewModel::clearHistory).padding(vertical = 12.dp),
            )
          }
          if (genres.isNotEmpty()) {
            SectionTitle("Sfoglia per genere", modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            GenreChips(genres, onOpenGenre)
          } else if (s.history.isEmpty() && trending.isEmpty()) {
            Text(
              "Digita un titolo, un genere o il nome di una persona.",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 24.dp),
            )
          }
        }
      is SearchUiState.Loading ->
        Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      is SearchUiState.Error ->
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(top = 16.dp)) {
          Text(s.message, color = MaterialTheme.colorScheme.onSurface)
          Text(
            "Prova con un altro titolo, attore, regista o genere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )
          FilledTonalButton(onClick = { remind(query.trim()) }, modifier = Modifier.padding(top = 16.dp)) {
            SymbolLabel(R.drawable.ic_ms_notifications, "Avvisami quando è disponibile")
          }
          if (genres.isNotEmpty()) {
            SectionTitle("Oppure sfoglia per genere", modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            GenreChips(genres, onOpenGenre)
          }
        }
      is SearchUiState.Success -> {
        // A live region, so a screen reader announces that results arrived (Netflix's own app has an
        // equivalent "Search Results Available" announcement).
        SectionTitle(
          text = if (s.results.size == 1) "Film e serie TV · 1 risultato" else "Film e serie TV · ${s.results.size} risultati",
          modifier = Modifier.padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 108.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
          contentPadding = PaddingValues(vertical = 8.dp),
        ) {
          items(s.results, key = { it.channelId + it.url }) { item ->
            PosterCard(item = item, onClick = { onOpen(item) }, width = 108.dp, showLabel = true)
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreChips(genres: List<GenreOption>, onClick: (Int) -> Unit, modifier: Modifier = Modifier) {
  FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    genres.forEach { g ->
      AssistChip(
        onClick = { onClick(g.id) },
        label = { Text(g.name) },
        shape = RoundedCornerShape(50),
        leadingIcon = { Symbol(R.drawable.ic_ms_explore, size = AssistChipDefaults.IconSize) },
      )
    }
  }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
  Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/** A trending title: wide 16:9 still, title, and a play affordance — the row shape streaming apps use
 *  for "what to watch" lists, as opposed to the poster grid used for results. */
@Composable
private fun TrendingRow(item: StreamItem, onClick: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
  ) {
    AsyncImage(
      model = item.cover ?: item.backdrop ?: item.thumbnail,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier =
        Modifier.width(132.dp)
          .aspectRatio(16f / 9f)
          .clip(RoundedCornerShape(4.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
    )
    Text(
      item.title,
      style = MaterialTheme.typography.bodyLarge,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
    )
    Symbol(R.drawable.ic_ms_play_circle_fill, contentDescription = "Apri", size = 32.dp)
  }
}
