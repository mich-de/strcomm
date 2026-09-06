package com.s4me.tv.ui.settings

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s4me.tv.engine.WatchProgressStore
import com.s4me.tv.engine.WatchlistStore
import com.s4me.tv.remote.RemoteControlProtocol
import com.s4me.tv.engine.SearchHistoryStore
import java.net.Inet4Address
import java.net.NetworkInterface

/** App settings: wipe the personal data stores, plus basic app info. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val progressStore = remember { WatchProgressStore(context) }
  val watchlistStore = remember { WatchlistStore(context) }
  val historyStore = remember { SearchHistoryStore(context.applicationContext as Application) }
  val firstFocusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocusRequester.requestFocus() } }

  fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

  Column(modifier = modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(text = "Impostazioni", style = MaterialTheme.typography.headlineMedium)

    Text(
      text = "Dati personali",
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(top = 16.dp),
    )
    Button(
      onClick = {
        progressStore.clearAll()
        toast("«Continua a guardare» svuotato")
      },
      modifier = Modifier.focusRequester(firstFocusRequester),
    ) {
      Text("🗑  Svuota «Continua a guardare»")
    }
    Button(
      onClick = {
        watchlistStore.clearAll()
        toast("«La mia lista» svuotata")
      }
    ) {
      Text("🗑  Svuota «La mia lista»")
    }
    Button(
      onClick = {
        historyStore.clear()
        toast("Cronologia ricerche cancellata")
      }
    ) {
      Text("🗑  Cancella cronologia ricerche")
    }

    Spacer(Modifier.height(12.dp))
    Text(text = "App companion", style = MaterialTheme.typography.titleMedium)
    Text(
      text = "Installa «StrComm Remote» sul telefono (stessa rete Wi-Fi) per cercare un titolo e inviarlo qui.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val localIp = remember { localIpAddress() }
    Text(
      text =
        if (localIp != null) {
          "Se il telefono non trova la TV da solo, inserisci manualmente: $localIp:${RemoteControlProtocol.DEFAULT_PORT}"
        } else {
          "Indirizzo di rete non disponibile al momento."
        },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))
    Text(text = "Informazioni", style = MaterialTheme.typography.titleMedium)
    Text(
      text = "StrComm · versione 1.0",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = "Fonte contenuti: StreamingCommunity (dominio auto-aggiornante)",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = "Suggerimento: tieni premuto OK su una copertina di «Continua a guardare» o «La mia lista» per rimuoverla.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}

/** First non-loopback IPv4 address, Wi-Fi or Ethernet — WifiManager.connectionInfo only covers
 *  Wi-Fi and is deprecated besides, and this box can be on either. */
private fun localIpAddress(): String? =
  runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
      .flatMap { it.inetAddresses.asSequence() }
      .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
      ?.hostAddress
  }.getOrNull()
