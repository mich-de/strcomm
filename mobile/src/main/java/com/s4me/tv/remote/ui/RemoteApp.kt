package com.s4me.tv.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.remote.PlaybackCommand
import com.s4me.tv.remote.PlaybackStatus
import com.s4me.tv.remote.discovery.DiscoveredTv

/** Local, in-memory back stack for the three screens past "connected" — deliberately not
 *  Navigation3 (as the TV app uses): three screens deep, never deep-linked into, so a manual
 *  stack is simpler than pulling in a nav library for it. */
private sealed interface PhoneScreen {
  data object Search : PhoneScreen

  data class Seasons(val series: StreamItem) : PhoneScreen

  data class Episodes(val season: StreamItem) : PhoneScreen
}

@Composable
fun RemoteApp(viewModel: RemoteViewModel = viewModel()) {
  val discovered by viewModel.discovered.collectAsStateWithLifecycle()
  val connectedTv by viewModel.connectedTv.collectAsStateWithLifecycle()
  val connecting by viewModel.connecting.collectAsStateWithLifecycle()
  val searchState by viewModel.searchState.collectAsStateWithLifecycle()
  val childrenState by viewModel.childrenState.collectAsStateWithLifecycle()
  val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
  val message by viewModel.message.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val screenStack = remember { mutableStateListOf<PhoneScreen>(PhoneScreen.Search) }

  LaunchedEffect(message) {
    message?.let {
      snackbarHostState.showSnackbar(it)
      viewModel.consumeMessage()
    }
  }

  // Disconnecting (or the TV changing) always drops back to the search screen — a stale
  // seasons/episodes list for a TV we're no longer talking to would be confusing to land back on.
  LaunchedEffect(connectedTv) {
    if (screenStack.size > 1) {
      screenStack.clear()
      screenStack.add(PhoneScreen.Search)
    }
  }

  BackHandler(enabled = screenStack.size > 1) { screenStack.removeAt(screenStack.lastIndex) }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    bottomBar = { nowPlaying?.let { status -> NowPlayingBar(status = status, onCommand = viewModel::sendCommand) } },
  ) { padding ->
    Box(modifier = Modifier.padding(padding).fillMaxSize()) {
      val tv = connectedTv
      val connectingTo = connecting
      if (tv == null) {
        if (connectingTo != null) {
          ConnectingScreen(name = connectingTo.name)
        } else {
          DiscoveryScreen(discovered = discovered, onConnect = viewModel::connect, onConnectManually = viewModel::connectManually)
        }
      } else {
        when (val screen = screenStack.last()) {
          is PhoneScreen.Search ->
            SearchScreen(
              tv = tv,
              searchState = searchState,
              onQueryChange = viewModel::onQueryChange,
              onSend = viewModel::send,
              onOpenSeries = { series ->
                viewModel.loadChildren(series)
                screenStack.add(PhoneScreen.Seasons(series))
              },
              onDisconnect = viewModel::disconnect,
            )
          is PhoneScreen.Seasons ->
            ChildrenScreen(
              title = screen.series.title,
              subtitle = "Stagioni",
              childrenState = childrenState,
              actionLabel = { "Episodi" },
              onItemClick = { season ->
                viewModel.loadChildren(season)
                screenStack.add(PhoneScreen.Episodes(season))
              },
              onBack = { screenStack.removeAt(screenStack.lastIndex) },
            )
          is PhoneScreen.Episodes ->
            ChildrenScreen(
              title = screen.season.title,
              subtitle = screen.season.seriesTitle,
              childrenState = childrenState,
              actionLabel = { "Invia" },
              showSubtitleOnItems = true,
              onItemClick = viewModel::send,
              onBack = { screenStack.removeAt(screenStack.lastIndex) },
            )
        }
      }
    }
  }
}

@Composable
private fun DiscoveryScreen(
  discovered: List<DiscoveredTv>,
  onConnect: (DiscoveredTv) -> Unit,
  onConnectManually: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var manualHost by remember { mutableStateOf("") }
  Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
    Text("StrComm Remote", style = MaterialTheme.typography.headlineMedium)
    Text(
      "Cerco la TV sulla stessa rete Wi-Fi…",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
    )

    if (discovered.isEmpty()) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Nessuna TV trovata finora", modifier = Modifier.padding(start = 12.dp))
      }
    } else {
      discovered.forEach { tv ->
        Card(onClick = { onConnect(tv) }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(tv.name, style = MaterialTheme.typography.titleMedium)
            Text("${tv.host}:${tv.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))
    Text("Non trova la TV automaticamente?", style = MaterialTheme.typography.titleSmall)
    Text(
      "Inserisci l'indirizzo IP mostrato in Impostazioni sulla TV.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = manualHost,
        onValueChange = { manualHost = it },
        placeholder = { Text("192.168.1.50") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
      Button(onClick = { onConnectManually(manualHost) }, modifier = Modifier.padding(start = 8.dp)) { Text("Connetti") }
    }
  }
}

/** Shown while [RemoteViewModel.connectTo] is pinging a candidate TV — the auto-reconnect on
 *  launch, a tapped discovery card, or a hand-typed IP all land here first. On success the
 *  connected UI replaces it; on failure it falls back to [DiscoveryScreen] with a snackbar. */
@Composable
private fun ConnectingScreen(name: String, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    CircularProgressIndicator()
    Text("Connessione a $name…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
  }
}

@Composable
private fun SearchScreen(
  tv: DiscoveredTv,
  searchState: SearchState,
  onQueryChange: (String) -> Unit,
  onSend: (StreamItem) -> Unit,
  onOpenSeries: (StreamItem) -> Unit,
  onDisconnect: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var query by remember { mutableStateOf("") }
  Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Connesso a", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(tv.name, style = MaterialTheme.typography.titleMedium)
      }
      TextButton(onClick = onDisconnect) { Text("Cambia") }
    }

    OutlinedTextField(
      value = query,
      onValueChange = {
        query = it
        onQueryChange(it)
      },
      placeholder = { Text("Cerca un film o una serie…") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
    )

    when (val s = searchState) {
      is SearchState.Idle ->
        Text("Digita un titolo per cercarlo sulla TV.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
      is SearchState.Loading ->
        Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      is SearchState.Results ->
        if (s.items.isEmpty()) {
          Text("Nessun risultato.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
        } else {
          LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(s.items, key = { it.channelId + it.url }) { item ->
              val isSeries = item.kind == ItemKind.SERIES
              val kindLabel = if (isSeries) "Serie TV" else "Film"
              ContentRow(
                title = item.title,
                subtitle = listOfNotNull(item.year, kindLabel).joinToString(" · "),
                thumbnail = item.thumbnail,
                actionLabel = if (isSeries) "Stagioni" else "Invia",
                onClick = { if (isSeries) onOpenSeries(item) else onSend(item) },
              )
            }
          }
        }
    }
  }
}

/** Shared by the Seasons screen (tap → drill into that season's episodes) and the Episodes screen
 *  (tap → send that episode to play) — both are just "browse [parent]'s children and act on one". */
@Composable
private fun ChildrenScreen(
  title: String,
  subtitle: String?,
  childrenState: ChildrenState,
  actionLabel: (StreamItem) -> String,
  onItemClick: (StreamItem) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  showSubtitleOnItems: Boolean = false,
) {
  Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
    TextButton(onClick = onBack) { Text("‹ Indietro") }
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

    when (val s = childrenState) {
      is ChildrenState.Loading ->
        Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      is ChildrenState.Empty ->
        Text("Nessun contenuto trovato.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
      is ChildrenState.Results ->
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 16.dp)) {
          items(s.items, key = { it.channelId + it.url }) { item ->
            ContentRow(
              title = item.title,
              subtitle = if (showSubtitleOnItems) item.plot else null,
              thumbnail = item.thumbnail,
              actionLabel = actionLabel(item),
              onClick = { onItemClick(item) },
            )
          }
        }
    }
  }
}

@Composable
private fun ContentRow(title: String, subtitle: String?, thumbnail: String?, actionLabel: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      AsyncImage(model = thumbnail, contentDescription = null, modifier = Modifier.width(56.dp).height(84.dp))
      Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
        subtitle?.takeIf { it.isNotBlank() }?.let {
          Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      Button(onClick = onClick) { Text(actionLabel) }
    }
  }
}

/** Persistent mini-player pinned to the bottom of every connected screen — like Spotify's now
 *  playing bar — so sending "Invia" doesn't strand the user without controls until they go find a
 *  remote; STOP mirrors pressing BACK on the TV's own remote during playback. */
@Composable
private fun NowPlayingBar(status: PlaybackStatus, onCommand: (PlaybackCommand) -> Unit, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 10.dp)) {
    Text(status.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (status.durationMs > 0) {
      LinearProgressIndicator(
        progress = { (status.positionMs.toFloat() / status.durationMs.toFloat()).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      )
      Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatDuration(status.positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatDuration(status.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
      TextButton(onClick = { onCommand(PlaybackCommand.SEEK_BACK) }) { Text("⏪ 10s") }
      TextButton(onClick = { onCommand(PlaybackCommand.TOGGLE) }) { Text(if (status.isPlaying) "⏸ Pausa" else "▶ Play") }
      TextButton(onClick = { onCommand(PlaybackCommand.SEEK_FORWARD) }) { Text("10s ⏩") }
      TextButton(onClick = { onCommand(PlaybackCommand.STOP) }) { Text("⏹ Stop") }
    }
  }
}

private fun formatDuration(ms: Long): String {
  val totalSec = ms / 1000
  val m = totalSec / 60
  val s = totalSec % 60
  return "%d:%02d".format(m, s)
}
