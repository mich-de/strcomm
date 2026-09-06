package com.s4me.tv.client.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import com.s4me.tv.client.ui.components.ErrorScreen
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchProgressStore
import com.s4me.tv.engine.WatchlistStore
import com.s4me.tv.engine.youtubeVideoId
import java.util.Locale

@Composable
fun DetailScreen(
  item: StreamItem,
  onPlay: (StreamItem) -> Unit,
  onPersonSearch: (String) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val viewModel: DetailViewModel = viewModel(key = item.url) { DetailViewModel(item) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val display by viewModel.preview.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val scroll = rememberScrollState()

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll)) {
      Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
        AsyncImage(
          model = display.backdrop ?: display.thumbnail,
          contentDescription = display.title,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Box(
          Modifier.fillMaxSize().background(
            Brush.verticalGradient(0.4f to Color.Transparent, 1f to MaterialTheme.colorScheme.background)
          )
        )
      }

      Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 32.dp)) {
        Text(display.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        display.seriesTitle?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        val badge = (display.imdbRating ?: display.tmdbRating)?.let { "★ %.1f".format(it) } ?: display.quality
        val meta = listOfNotNull(display.year, badge, display.runtime?.let { "$it min" }).joinToString("  ·  ")
        if (meta.isNotBlank()) {
          Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        val ratingSrc =
          listOfNotNull(
            display.imdbRating?.let { "IMDb %.1f".format(it) + (display.imdbVotes?.let { v -> " (${formatVotes(v)})" } ?: "") },
            display.tmdbRating?.let { "TMDB %.1f".format(it) + (display.tmdbVotes?.let { v -> " (${formatVotes(v)})" } ?: "") },
          )
        if (ratingSrc.isNotEmpty()) {
          Text(ratingSrc.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        display.genres?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }

        PlayActions(item = display, state = state, onPlay = onPlay, modifier = Modifier.padding(top = 16.dp))

        display.plot?.let {
          Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 18.dp))
        }

        display.trailerYoutubeId?.let { id ->
          FilledTonalButton(onClick = { openTrailer(context, id) }, modifier = Modifier.padding(top = 14.dp)) { Text("▶  Trailer") }
        }

        NameRow("Cast", display.cast, onPersonSearch, Modifier.padding(top = 16.dp))
        NameRow("Regia", display.director, onPersonSearch, Modifier.padding(top = 8.dp))

        if (state is DetailUiState.Error) {
          Text((state as DetailUiState.Error).message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
        }
      }
    }

    Text(
      text = "‹",
      style = MaterialTheme.typography.headlineMedium,
      color = Color.White,
      modifier =
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
          .padding(8.dp)
          .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50))
          .clickable(onClick = onBack)
          .padding(horizontal = 16.dp, vertical = 4.dp),
    )
  }
}

@Composable
private fun PlayActions(item: StreamItem, state: DetailUiState, onPlay: (StreamItem) -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val progressStore = remember { WatchProgressStore(context) }
  val watchlistStore = remember { WatchlistStore(context) }
  val resumeMs = remember(item.url) { progressStore.positionFor(item.url) }
  var inList by androidx.compose.runtime.remember(item.url) { androidx.compose.runtime.mutableStateOf(watchlistStore.contains(item.url)) }

  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    val sources = (state as? DetailUiState.Success)?.sources.orEmpty()
    val ready = sources.firstOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      if (resumeMs != null && resumeMs > 0) {
        Button(onClick = { ready?.let(onPlay) }, enabled = ready != null) { Text("▶  Riprendi · ${formatTime(resumeMs)}") }
        FilledTonalButton(
          onClick = {
            progressStore.remove(item.url)
            ready?.let(onPlay)
          },
          enabled = ready != null,
        ) { Text("↻  Ricomincia") }
      } else {
        Button(onClick = { ready?.let(onPlay) }, enabled = ready != null) {
          Text(if (state is DetailUiState.Loading) "Caricamento…" else "▶  Guarda")
        }
      }
      FilledTonalButton(onClick = { inList = watchlistStore.toggle(item) }) {
        Text(if (inList) "✓  Nella lista" else "＋  La mia lista")
      }
    }
  }
}

@Composable
private fun NameRow(label: String, names: String?, onClick: (String) -> Unit, modifier: Modifier = Modifier) {
  names ?: return
  Column(modifier = modifier) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      names.split(", ").forEach { name -> AssistChip(onClick = { onClick(name) }, label = { Text(name) }) }
    }
  }
}

private fun formatVotes(count: Int): String = String.format(Locale.ITALY, "%,d", count)

private fun formatTime(ms: Long): String {
  val s = ms / 1000
  val h = s / 3600
  val m = (s % 3600) / 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%d:%02d".format(m, s % 60)
}

private fun openTrailer(context: Context, videoId: String) {
  val id = youtubeVideoId(videoId) ?: return
  val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  runCatching { context.startActivity(intent) }
}
