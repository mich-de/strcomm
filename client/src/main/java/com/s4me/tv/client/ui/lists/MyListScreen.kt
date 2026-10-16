package com.s4me.tv.client.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.s4me.tv.client.R
import com.s4me.tv.client.ui.components.ErrorScreen
import com.s4me.tv.client.ui.components.PosterCard
import com.s4me.tv.engine.RatingStore
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchlistStore

/** "La mia lista" in full — the Home row shows the latest few, this is the whole thing, sortable. */
@Composable
fun MyListScreen(onOpen: (StreamItem) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val store = remember { WatchlistStore(context) }
  val ratings = remember { RatingStore(context) }
  var items by remember { mutableStateOf(store.all()) }
  var sort by remember { mutableStateOf(ListSort.RECENT) }
  val shown = remember(items, sort) { sortItems(items, sort, ratings) }

  Column(modifier = modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) { Icon(ImageVector.vectorResource(R.drawable.ic_ms_arrow_back), contentDescription = "Indietro") }
      Text("La mia lista", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }

    if (items.isEmpty()) {
      ErrorScreen("La tua lista è vuota.\nAggiungi film e serie con “La mia lista” sulla loro pagina.", modifier = Modifier.fillMaxSize())
    } else {
      SortChips(selected = sort, onSelect = { sort = it }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
      LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 120.dp), contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
        items(shown, key = { it.url }) { item ->
          Box {
            PosterCard(item = item, onClick = { onOpen(item) }, width = 120.dp)
            IconButton(
              onClick = {
                store.toggle(item)
                items = store.all()
              },
              modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
            ) {
              Icon(
                ImageVector.vectorResource(R.drawable.ic_ms_close),
                contentDescription = "Rimuovi dalla lista",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
              )
            }
          }
        }
      }
    }
  }
}
