package com.s4me.tv.client.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.s4me.tv.engine.SearchHistoryStore
import com.s4me.tv.engine.WatchProgressStore
import com.s4me.tv.engine.WatchlistStore

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val progress = remember { WatchProgressStore(context) }
  val watchlist = remember { WatchlistStore(context) }
  val history = remember { SearchHistoryStore(context.applicationContext as android.app.Application) }

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Impostazioni", style = MaterialTheme.typography.headlineSmall)
    Text("Dati personali, salvati solo su questo dispositivo.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FilledTonalButton(onClick = { progress.clearAll() }) { Text("🗑  Svuota «Continua a guardare»") }
    FilledTonalButton(onClick = { watchlist.clearAll() }) { Text("🗑  Svuota «La mia lista»") }
    FilledTonalButton(onClick = { history.clear() }) { Text("🗑  Cancella cronologia ricerche") }
    Text("StrComm — client touch. Streaming da un unico sito di terze parti, uso personale.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
  }
}
