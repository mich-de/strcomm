package com.s4me.tv.client.ui.guide

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.s4me.tv.client.ui.components.ErrorScreen
import com.s4me.tv.client.ui.components.LoadingScreen
import com.s4me.tv.engine.NowOnItalianTV
import com.s4me.tv.engine.StreamItem

/** The full "Guida TV" — every film airing tonight on Italian digital-terrestrial channels, sorted
 *  by channel number (same order as Home's "Film stasera in TV" row), not just the first 20 that
 *  already happened to resolve. Tapping a row resolves it on demand. */
@Composable
fun TvGuideScreen(onOpen: (StreamItem) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: TvGuideViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val resolving by viewModel.resolving.collectAsStateWithLifecycle()
  val context = LocalContext.current

  Column(modifier = modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
      Text("Guida TV · stasera", style = MaterialTheme.typography.titleLarge)
    }

    when (val s = state) {
      is TvGuideUiState.Loading -> LoadingScreen(modifier = Modifier.fillMaxSize())
      is TvGuideUiState.Error -> ErrorScreen(s.message, onRetry = viewModel::load, modifier = Modifier.fillMaxSize())
      is TvGuideUiState.Success ->
        LazyColumn(modifier = Modifier.fillMaxSize()) {
          items(s.entries, key = { TvGuideViewModel.rowKey(it) }) { entry ->
            GuideRow(
              entry = entry,
              isResolving = resolving == TvGuideViewModel.rowKey(entry),
              onClick = {
                viewModel.resolve(
                  entry = entry,
                  onFound = onOpen,
                  onNotFound = { Toast.makeText(context, "«${entry.title}» non è nel catalogo al momento", Toast.LENGTH_SHORT).show() },
                )
              },
            )
          }
        }
    }
  }
}

@Composable
private fun GuideRow(entry: NowOnItalianTV.Entry, isResolving: Boolean, onClick: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(enabled = !isResolving, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(entry.time, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(52.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        entry.channel.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
          Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
      )
      Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
    if (isResolving) {
      CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
    } else {
      Text("▶", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
