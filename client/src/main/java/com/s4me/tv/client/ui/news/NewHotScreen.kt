package com.s4me.tv.client.ui.news

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.s4me.tv.client.R
import com.s4me.tv.client.ui.components.Symbol
import com.s4me.tv.client.ui.home.HomeSnapshot
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchlistStore

/** At most this many titles per row of the feed — it is a "what's new" list, not the catalogue. */
private const val PER_SECTION = 6

/**
 * "Novità e di tendenza": the Home's catalogue and chart rows laid out as one vertical feed of wide
 * cards — the shape a streaming app's "New & Hot" tab has — instead of horizontal strips. It re-shows
 * what Home already fetched ([HomeSnapshot]), so opening it costs no request to the site.
 */
@Composable
fun NewHotScreen(onOpen: (StreamItem) -> Unit, modifier: Modifier = Modifier) {
  val rows by HomeSnapshot.sections.collectAsStateWithLifecycle()
  val sections =
    remember(rows) {
      rows
        .map { it to it.items.filter { i -> i.kind == ItemKind.MOVIE || i.kind == ItemKind.SERIES }.take(PER_SECTION) }
        .filter { it.second.isNotEmpty() }
    }
  if (sections.isEmpty()) {
    WaitingForHome(modifier)
    return
  }
  val context = LocalContext.current
  val watchlist = remember { WatchlistStore(context) }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    item {
      Text(
        "Novità e di tendenza",
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }
    sections.forEach { (section, titles) ->
      item(key = "h-${section.channelId}") {
        Text(
          section.title,
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
      }
      items(titles, key = { section.channelId + it.url }) { item ->
        NewHotCard(item = item, onOpen = onOpen, watchlist = watchlist)
      }
    }
  }
}

@Composable
private fun NewHotCard(item: StreamItem, onOpen: (StreamItem) -> Unit, watchlist: WatchlistStore) {
  var inList by remember(item.url) { mutableStateOf(watchlist.contains(item.url)) }
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onOpen(item) }) {
    AsyncImage(
      model = item.cover ?: item.backdrop ?: item.thumbnail,
      contentDescription = item.title,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
      Column(modifier = Modifier.weight(1f)) {
        Text(item.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(item.year, item.quality, if (item.kind == ItemKind.SERIES) "Serie TV" else "Film").joinToString("  ·  ")
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      IconButton(onClick = { inList = watchlist.toggle(item) }) {
        Symbol(
          if (inList) R.drawable.ic_ms_check else R.drawable.ic_ms_add,
          contentDescription = if (inList) "Nella lista" else "Aggiungi alla lista",
        )
      }
    }
  }
}

/** Home hasn't delivered its rows yet (or the site is unreachable): the feed fills in by itself the
 *  moment it does, so this is a wait, not an error. */
@Composable
private fun WaitingForHome(modifier: Modifier) {
  Column(
    modifier = modifier.fillMaxSize().padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    Text(
      "Le novità compaiono appena la Home ha finito di caricare.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 16.dp),
    )
  }
}
