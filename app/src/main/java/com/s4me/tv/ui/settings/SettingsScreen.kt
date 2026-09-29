package com.s4me.tv.ui.settings

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s4me.tv.TvGuide
import com.s4me.tv.engine.BackupManager
import com.s4me.tv.engine.CacheUtil
import com.s4me.tv.engine.PERSONAL_USE_NOTICE
import com.s4me.tv.engine.Tmdb
import com.s4me.tv.engine.TmdbApiKeyStore
import com.s4me.tv.engine.WatchProgressStore
import com.s4me.tv.engine.WatchStatsStore
import com.s4me.tv.engine.WatchlistStore
import com.s4me.tv.engine.appVersionName
import com.s4me.tv.remote.RemoteControlProtocol
import com.s4me.tv.engine.SearchHistoryStore
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App settings: wipe the personal data stores, plus basic app info. */
@Composable
fun SettingsScreen(onNavigate: (NavKey) -> Unit = {}, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val progressStore = remember { WatchProgressStore(context) }
  val watchlistStore = remember { WatchlistStore(context) }
  val historyStore = remember { SearchHistoryStore(context.applicationContext as Application) }
  val firstFocusRequester = remember { FocusRequester() }
  val scope = rememberCoroutineScope()
  LaunchedEffect(Unit) { runCatching { firstFocusRequester.requestFocus() } }

  var cacheBytes by remember { mutableLongStateOf(0L) }
  var clearingCache by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { CacheUtil.sizeBytes(context) } }

  val tmdbKeyStore = remember { TmdbApiKeyStore(context) }
  var tmdbKeyInput by remember { mutableStateOf(tmdbKeyStore.load().orEmpty()) }
  var tmdbUsingApi by remember { mutableStateOf(Tmdb.usingApi) }

  val stats = remember { WatchStatsStore(context).summary() }

  fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

  val application = context.applicationContext as Application
  val exportLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      scope.launch(Dispatchers.IO) {
        runCatching {
          context.contentResolver.openOutputStream(uri)?.use { it.write(BackupManager.export(application).toByteArray()) }
        }
        withContext(Dispatchers.Main) { toast("Backup salvato") }
      }
    }
  val importLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      scope.launch(Dispatchers.IO) {
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        val ok = text != null && BackupManager.import(application, text)
        withContext(Dispatchers.Main) { toast(if (ok) "Dati ripristinati — riavvia l'app per vederli" else "File non valido") }
      }
    }

  Column(modifier = modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(text = "Impostazioni", style = MaterialTheme.typography.headlineMedium)
    // Up here, not only under "Informazioni": this Column doesn't scroll, so its tail can sit below
    // the bottom edge of the screen.
    val version = remember { appVersionName(context) }
    Text(
      text = "StrComm $version · $PERSONAL_USE_NOTICE",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(onClick = { onNavigate(TvGuide) }, modifier = Modifier.focusRequester(firstFocusRequester)) {
      Text("📺  Guida TV completa")
    }

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
    Button(
      onClick = {
        clearingCache = true
        scope.launch(Dispatchers.IO) {
          CacheUtil.clear(context)
          val size = CacheUtil.sizeBytes(context)
          withContext(Dispatchers.Main) {
            cacheBytes = size
            clearingCache = false
            toast("Cache pulita")
          }
        }
      },
      enabled = !clearingCache,
    ) {
      Text(if (clearingCache) "Pulizia in corso…" else "🧹  Pulisci cache (${CacheUtil.formatSize(cacheBytes)})")
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
    Text(text = "Chiave API TMDB (opzionale)", style = MaterialTheme.typography.titleMedium)
    Text(
      text =
        if (tmdbUsingApi) "Attiva: voti, conteggio voti e trailer ufficiali da TMDB."
        else "Non impostata: voti e trama vengono comunque letti dalla pagina pubblica di TMDB, senza chiave.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextField(
      value = tmdbKeyInput,
      onValueChange = { tmdbKeyInput = it },
      placeholder = { Text("Chiave API TMDB", color = Color.White.copy(alpha = 0.4f)) },
      singleLine = true,
      colors =
        TextFieldDefaults.colors(
          focusedContainerColor = Color.White.copy(alpha = 0.10f),
          unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
          focusedIndicatorColor = MaterialTheme.colorScheme.primary,
          unfocusedIndicatorColor = Color.Transparent,
          disabledIndicatorColor = Color.Transparent,
          focusedTextColor = Color.White,
          unfocusedTextColor = Color.White,
          cursorColor = MaterialTheme.colorScheme.primary,
        ),
      modifier = Modifier.fillMaxWidth(0.5f).padding(top = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
      Button(
        onClick = {
          tmdbKeyStore.save(tmdbKeyInput)
          Tmdb.setApiKeyOverride(tmdbKeyInput)
          tmdbUsingApi = Tmdb.usingApi
          toast("Chiave TMDB salvata")
        }
      ) {
        Text("Salva")
      }
      Button(
        onClick = {
          tmdbKeyInput = ""
          tmdbKeyStore.save(null)
          Tmdb.setApiKeyOverride(null)
          tmdbUsingApi = Tmdb.usingApi
          toast("Chiave TMDB rimossa")
        }
      ) {
        Text("Rimuovi")
      }
    }

    Spacer(Modifier.height(12.dp))
    Text(text = "Statistiche di visione", style = MaterialTheme.typography.titleMedium)
    Text(
      text =
        if (stats.titlesCompleted == 0) {
          "Nessun titolo completato finora."
        } else {
          "%d titoli completati (%d film, %d episodi) · circa %.1f ore di contenuti".format(
            stats.titlesCompleted, stats.moviesCompleted, stats.episodesCompleted, stats.totalMs / 3_600_000.0,
          )
        },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))
    Text(text = "Backup dati", style = MaterialTheme.typography.titleMedium)
    Text(
      text = "Esporta «Continua a guardare», «La mia lista», cronologia ricerche e statistiche in un file, o ripristinali da un backup precedente.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
      Button(onClick = { exportLauncher.launch("strcomm-backup.json") }) { Text("⬆  Esporta") }
      Button(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text("⬇  Ripristina") }
    }

    Spacer(Modifier.height(12.dp))
    Text(text = "Informazioni", style = MaterialTheme.typography.titleMedium)
    Text(
      text = "StrComm · versione $version · uso esclusivamente personale",
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
