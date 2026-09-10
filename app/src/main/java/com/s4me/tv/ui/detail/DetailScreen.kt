package com.s4me.tv.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Locale
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.s4me.tv.Player
import com.s4me.tv.Search
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchProgressStore
import com.s4me.tv.engine.youtubeVideoId
import com.s4me.tv.navKeyFor
import com.s4me.tv.ui.components.LoadingIndicator
import com.s4me.tv.ui.components.NameChipsRow
import com.s4me.tv.ui.components.PosterCard
import com.s4me.tv.ui.components.WatchlistToggleButton

@Composable
fun DetailScreen(item: StreamItem, onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  val viewModel: DetailViewModel = viewModel(key = item.url) { DetailViewModel(item) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val related by viewModel.related.collectAsStateWithLifecycle()
  // Renders from whatever's known so far: the nav-arg item immediately, then the same fields
  // upgrade in place once detail() enrichment (plot for movies, genres, cast, director) lands.
  val displayItem = (state as? DetailUiState.Success)?.item ?: item

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // Cinematic backdrop behind the whole page instead of flat black — the movie's own wide still,
    // pinned to the top, dimmed on the left (where the text sits) and faded to the solid background
    // toward the bottom so the plot/cast below stay readable. Skipped when there's no backdrop
    // (e.g. some episodes), leaving the plain background.
    displayItem.backdrop?.let { backdrop ->
      Box(modifier = Modifier.fillMaxWidth().height(560.dp)) {
        AsyncImage(
          model = backdrop,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          alignment = Alignment.TopCenter,
          modifier = Modifier.matchParentSize(),
        )
        Box(
          modifier =
            Modifier.matchParentSize()
              .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.35f))))
        )
        Box(
          modifier =
            Modifier.matchParentSize()
              .background(
                Brush.verticalGradient(
                  0f to Color.Black.copy(alpha = 0.35f),
                  0.55f to Color.Transparent,
                  1f to MaterialTheme.colorScheme.background,
                )
              )
        )
      }
    }

    DetailContent(displayItem = displayItem, state = state, related = related, onNavigate = onNavigate)
  }
}

@Composable
private fun DetailContent(
  displayItem: StreamItem,
  state: DetailUiState,
  related: List<StreamItem>,
  onNavigate: (NavKey) -> Unit,
) {
  // Order matters for TV: title + meta, then the (focusable) Guarda button, THEN the long text.
  // The button is what gets auto-focused, and a LazyColumn scrolls to keep the focused item in
  // view — so with the button near the top, opening a detail leaves the title on screen instead
  // of immediately scrolling down past it (the "premo su e non risalgo" trap). Only the saga row
  // sits below the fold, reached by pressing DOWN and returned from by pressing UP.
  LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(48.dp)) {
    item {
      val context = LocalContext.current
      Column {
        Text(text = displayItem.title, style = MaterialTheme.typography.displayMedium)
        displayItem.seriesTitle?.let { Text(text = it, style = MaterialTheme.typography.titleMedium) }
        // A real IMDb/TMDB number (named source, current) replaces the source site's own "★ n"
        // badge when we have one — IMDb first (the rating most people recognise), then TMDB, then
        // the site's own (displayItem.quality) as the last resort.
        val ratingBadge =
          (displayItem.imdbRating ?: displayItem.tmdbRating)?.let { "★ %.1f".format(it) } ?: displayItem.quality
        Text(
          text = listOfNotNull(displayItem.year, ratingBadge, displayItem.runtime?.let { "$it min" }).joinToString("  ·  "),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 4.dp),
        )
        val ratingSources =
          listOfNotNull(
            displayItem.imdbRating?.let { "IMDb %.1f".format(it) + (displayItem.imdbVotes?.let { v -> " (${formatVotes(v)})" } ?: "") },
            displayItem.tmdbRating?.let { "TMDB %.1f".format(it) + (displayItem.tmdbVotes?.let { v -> " (${formatVotes(v)})" } ?: "") },
          )
        if (ratingSources.isNotEmpty()) {
          Text(
            text = ratingSources.joinToString("  ·  "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
        displayItem.genres?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        // TMDB's official trailer when a key is set, else the source site's own trailer list —
        // opens in the YouTube app, browser fallback. Only shown once detail() resolves an id.
        displayItem.trailerYoutubeId?.let { videoId ->
          Button(onClick = { openYouTubeTrailer(context, videoId) }, modifier = Modifier.padding(top = 12.dp)) { Text("▶  Trailer") }
        }
      }
    }

    when (val s = state) {
      is DetailUiState.Loading -> item { LoadingIndicator(modifier = Modifier.padding(top = 20.dp)) }
      is DetailUiState.Error -> item { Text(text = s.message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 20.dp)) }
      is DetailUiState.Success ->
        item {
          PlayActions(
            item = displayItem,
            sources = s.sources,
            onPlay = { source -> onNavigate(Player(source)) },
            modifier = Modifier.padding(top = 20.dp),
          )
        }
    }

    // Description block, always from displayItem — sits below the play button so the button stays
    // high. Plot is capped so the whole primary block fits on one screen (no need to scroll to
    // read it); very long synopses ellipsize, the way every streaming app truncates them.
    item {
      Column(modifier = Modifier.padding(top = 20.dp)) {
        displayItem.plot?.let {
          Text(text = it, style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 900.dp))
        }
        // Names are individually focusable/clickable, each jumping to a search for that person —
        // the site indexes cast/director names in its own title search (verified live: "Gal Gadot"
        // and "Guy Ritchie" both return their real filmographies), so this needed no new data
        // source, just wiring what already worked as a query. Split back out of the comma-joined
        // string StreamingCommunityChannel.joinNames() produces (single names never contain a
        // comma, so this round-trips cleanly).
        displayItem.cast?.let {
          NameChipsRow(
            label = "Cast",
            names = it.split(", "),
            onNameClick = { name -> onNavigate(Search(initialQuery = name, isPersonQuery = true)) },
            modifier = Modifier.padding(top = 12.dp),
          )
        }
        displayItem.director?.let {
          NameChipsRow(
            label = "Regia",
            names = it.split(", "),
            onNameClick = { name -> onNavigate(Search(initialQuery = name, isPersonQuery = true)) },
            modifier = Modifier.padding(top = 8.dp),
          )
        }
        val externalIds = listOfNotNull(displayItem.tmdbId?.let { "TMDB: $it" }, displayItem.imdbId?.let { "IMDB: $it" })
        if (externalIds.isNotEmpty()) {
          Text(
            text = externalIds.joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      }
    }

    // "Altri capitoli della saga" — the TMDB collection, each sibling that's also on the source
    // resolved to a real catalogue MOVIE. Lands after enrichment (its own coroutine in the VM),
    // so it just isn't emitted until non-empty — this is the one row that sits below the fold,
    // reached with DOWN and returned from with UP.
    if (related.isNotEmpty()) {
      item {
        SagaRow(items = related, onNavigate = onNavigate, modifier = Modifier.padding(top = 28.dp))
      }
    }

    item { Spacer(Modifier.height(40.dp)) }
  }
}

/** The movie's TMDB collection as a focusable poster row (same [PosterCard] as everywhere else). */
@Composable
private fun SagaRow(items: List<StreamItem>, onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier) {
  Column(modifier = modifier) {
    Text(
      text = "Altri capitoli della saga",
      style = MaterialTheme.typography.titleMedium,
    )
    LazyRow(
      modifier = Modifier.padding(top = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      items(items, key = { it.url }) { movie ->
        PosterCard(item = movie, onClick = { onNavigate(navKeyFor(movie)) })
      }
    }
  }
}

/**
 * The action row under the title: play (splitting into "Riprendi"/"Ricomincia" when there's saved
 * watch progress, Netflix-style) plus the "La mia lista" toggle for movies (series get theirs on
 * the seasons screen — an episode in a personal list would be odd).
 */
@Composable
private fun PlayActions(item: StreamItem, sources: List<StreamItem>, onPlay: (StreamItem) -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val progressStore = remember { WatchProgressStore(context) }
  val resumeMs = remember(item.url) { progressStore.positionFor(item.url) }
  val firstFocusRequester = remember(sources.firstOrNull()?.url) { FocusRequester() }
  LaunchedEffect(sources.firstOrNull()?.url) {
    if (sources.isNotEmpty()) runCatching { firstFocusRequester.requestFocus() }
  }

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    sources.forEachIndexed { index, source ->
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (index == 0 && resumeMs != null && resumeMs > 0) {
          // The player auto-resumes from the stored position, so "Riprendi" just plays; "Ricomincia"
          // clears the stored position first so playback starts from zero.
          Button(onClick = { onPlay(source) }, modifier = Modifier.focusRequester(firstFocusRequester)) {
            Text("▶  Riprendi · ${formatResumeTime(resumeMs)}")
          }
          Button(
            onClick = {
              progressStore.remove(item.url)
              onPlay(source)
            }
          ) {
            Text("↻  Ricomincia")
          }
        } else {
          Button(
            onClick = { onPlay(source) },
            modifier = if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier,
          ) {
            Text("▶  Guarda · ${source.title}")
          }
        }
        if (index == 0 && item.kind == ItemKind.MOVIE) {
          WatchlistToggleButton(item = item)
        }
      }
    }
  }
}

/** 12345 -> "12.345" (Italian grouping) so a big vote count reads at a glance. */
private fun formatVotes(count: Int): String = String.format(Locale.ITALY, "%,d", count)

/** Opens the trailer in whatever YouTube app the box has. Many Android TV boxes ship only a
 *  third-party client (SmartTube) plus the framework "no browser" stub — so use the plain
 *  youtube.com/watch URL every client documents (`vnd.youtube:` is the *official* app's scheme and
 *  SmartTube errors on it), and when exactly one real handler exists, target it directly so
 *  there's no disambiguation chooser and no stub. */
private fun openYouTubeTrailer(context: Context, videoId: String) {
  val id = youtubeVideoId(videoId) ?: return
  val intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  val handlers =
    context.packageManager
      .queryIntentActivities(intent, 0)
      .filterNot {
        it.activityInfo.packageName == "android" ||
          it.activityInfo.packageName.startsWith("com.android.tv.frameworkpackagestubs")
      }
  // Prefer SmartTube (the usual Android-TV-box YouTube client, id varies but the activity class
  // doesn't); otherwise pin the sole handler; otherwise leave it for the system chooser.
  val target =
    handlers.firstOrNull { it.activityInfo.name.startsWith("com.liskovsoft.smartyoutubetv2") } ?: handlers.singleOrNull()
  target?.let { intent.setPackage(it.activityInfo.packageName) }
  runCatching { context.startActivity(intent) }
    .onFailure { Toast.makeText(context, "Nessuna app per aprire YouTube", Toast.LENGTH_SHORT).show() }
}

private fun formatResumeTime(ms: Long): String {
  val totalSec = ms / 1000
  val h = totalSec / 3600
  val m = (totalSec % 3600) / 60
  val s = totalSec % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
