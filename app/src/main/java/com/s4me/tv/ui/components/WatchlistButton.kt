package com.s4me.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchlistStore

/** "La mia lista" toggle for a movie or series. State is read/written straight through the store —
 *  Home's personal rows pick the change up on its next resume refresh. */
@Composable
fun WatchlistToggleButton(item: StreamItem, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val store = remember { WatchlistStore(context) }
  var inList by remember(item.url) { mutableStateOf(store.contains(item.url)) }
  Button(onClick = { inList = store.toggle(item) }, modifier = modifier) {
    Text(if (inList) "✓  Nella lista" else "＋  La mia lista")
  }
}
