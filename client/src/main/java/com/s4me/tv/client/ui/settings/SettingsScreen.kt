package com.s4me.tv.client.ui.settings

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s4me.tv.client.notifications.NewReleaseWorker
import com.s4me.tv.client.sync.ProgressSyncPreference
import com.s4me.tv.client.sync.ProgressSyncWorker
import com.s4me.tv.client.theme.ThemeMode
import com.s4me.tv.client.theme.ThemePreference
import com.s4me.tv.engine.BackupManager
import com.s4me.tv.engine.CacheUtil
import com.s4me.tv.engine.NewReleaseStore
import com.s4me.tv.engine.PERSONAL_USE_NOTICE
import com.s4me.tv.engine.SearchHistoryStore
import com.s4me.tv.engine.Tmdb
import com.s4me.tv.engine.TmdbApiKeyStore
import com.s4me.tv.engine.WatchProgressStore
import com.s4me.tv.engine.WatchStatsStore
import com.s4me.tv.engine.WatchlistStore
import com.s4me.tv.engine.appVersionName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
  onOpenTvGuide: () -> Unit = {},
  onOpenCustomLists: () -> Unit = {},
  onOpenDownloads: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val progress = remember { WatchProgressStore(context) }
  val watchlist = remember { WatchlistStore(context) }
  val history = remember { SearchHistoryStore(context.applicationContext as android.app.Application) }
  val themeMode by ThemePreference.mode.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()

  var cacheBytes by remember { mutableLongStateOf(0L) }
  var clearingCache by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { CacheUtil.sizeBytes(context) } }

  val tmdbKeyStore = remember { TmdbApiKeyStore(context) }
  var tmdbKeyInput by remember { mutableStateOf(tmdbKeyStore.load().orEmpty()) }
  var tmdbUsingApi by remember { mutableStateOf(Tmdb.usingApi) }

  val statsStore = remember { WatchStatsStore(context) }
  val stats = remember { statsStore.summary() }

  val newReleaseStore = remember { NewReleaseStore(context) }
  var notificationsEnabled by remember { mutableStateOf(newReleaseStore.enabled) }
  val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

  var syncEnabled by remember { mutableStateOf(ProgressSyncPreference.isEnabled(context)) }

  val application = context.applicationContext as android.app.Application
  val exportLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      scope.launch(Dispatchers.IO) {
        runCatching {
          context.contentResolver.openOutputStream(uri)?.use { it.write(BackupManager.export(application).toByteArray()) }
        }
        withContext(Dispatchers.Main) { Toast.makeText(context, "Backup salvato", Toast.LENGTH_SHORT).show() }
      }
    }
  val importLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      scope.launch(Dispatchers.IO) {
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        val ok = text != null && BackupManager.import(application, text)
        withContext(Dispatchers.Main) {
          Toast.makeText(context, if (ok) "Dati ripristinati — riapri l'app per vederli" else "File non valido", Toast.LENGTH_LONG).show()
        }
      }
    }

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Impostazioni", style = MaterialTheme.typography.headlineSmall)
    val version = remember { appVersionName(context) }
    Text("StrComm $version · $PERSONAL_USE_NOTICE", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FilledTonalButton(onClick = onOpenTvGuide) { Text("📺  Guida TV completa") }
    FilledTonalButton(onClick = onOpenCustomLists) { Text("📋  Le mie liste personalizzate") }
    FilledTonalButton(onClick = onOpenDownloads) { Text("⬇  I miei download") }

    Text("Dati personali, salvati solo su questo dispositivo.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    FilledTonalButton(onClick = { progress.clearAll() }) { Text("🗑  Svuota «Continua a guardare»") }
    FilledTonalButton(onClick = { watchlist.clearAll() }) { Text("🗑  Svuota «La mia lista»") }
    FilledTonalButton(onClick = { history.clear() }) { Text("🗑  Cancella cronologia ricerche") }
    FilledTonalButton(
      onClick = {
        clearingCache = true
        scope.launch(Dispatchers.IO) {
          CacheUtil.clear(context)
          val size = CacheUtil.sizeBytes(context)
          withContext(Dispatchers.Main) {
            cacheBytes = size
            clearingCache = false
          }
        }
      },
      enabled = !clearingCache,
    ) {
      Text(if (clearingCache) "Pulizia in corso…" else "🧹  Pulisci cache (${CacheUtil.formatSize(cacheBytes)})")
    }

    Text("Tema", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ThemeChip("Sistema", ThemeMode.SYSTEM, themeMode, context)
      ThemeChip("Chiaro", ThemeMode.LIGHT, themeMode, context)
      ThemeChip("Scuro", ThemeMode.DARK, themeMode, context)
    }

    Text("Chiave API TMDB (opzionale)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
      if (tmdbUsingApi) "Attiva: voti, conteggio voti e trailer ufficiali da TMDB."
      else "Non impostata: voti e trama vengono comunque letti dalla pagina pubblica di TMDB, senza chiave.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
      value = tmdbKeyInput,
      onValueChange = { tmdbKeyInput = it },
      label = { Text("Chiave API TMDB") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilledTonalButton(
        onClick = {
          tmdbKeyStore.save(tmdbKeyInput)
          Tmdb.setApiKeyOverride(tmdbKeyInput)
          tmdbUsingApi = Tmdb.usingApi
        }
      ) {
        Text("Salva")
      }
      FilledTonalButton(
        onClick = {
          tmdbKeyInput = ""
          tmdbKeyStore.save(null)
          Tmdb.setApiKeyOverride(null)
          tmdbUsingApi = Tmdb.usingApi
        }
      ) {
        Text("Rimuovi")
      }
    }

    Text("Statistiche di visione", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    if (stats.titlesCompleted == 0) {
      Text(
        "Nessun titolo completato finora.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Text(
        "%d titoli completati (%d film, %d episodi) · circa %.1f ore di contenuti".format(
          stats.titlesCompleted, stats.moviesCompleted, stats.episodesCompleted, stats.totalMs / 3_600_000.0,
        ),
        style = MaterialTheme.typography.bodyMedium,
      )
    }

    Text("Notifiche nuove uscite", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Switch(
        checked = notificationsEnabled,
        onCheckedChange = { checked ->
          notificationsEnabled = checked
          newReleaseStore.enabled = checked
          if (checked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            NewReleaseWorker.schedule(context)
          } else {
            NewReleaseWorker.cancel(context)
          }
        },
      )
      Text(
        "Avvisa quando una serie nella tua lista ha una nuova stagione (controllo ogni 12 ore circa).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Text("Sincronizza con la TV", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Switch(
        checked = syncEnabled,
        onCheckedChange = { checked ->
          syncEnabled = checked
          ProgressSyncPreference.setEnabled(context, checked)
          if (checked) {
            ProgressSyncWorker.schedule(context)
            ProgressSyncWorker.syncNow(context)
          } else {
            ProgressSyncWorker.cancel(context)
          }
        },
      )
      Text(
        "Tieni allineato «Continua a guardare» con la StrComm sulla TV, sulla stessa rete Wi-Fi.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Text("Backup dati", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
      "Esporta «Continua a guardare», «La mia lista», cronologia ricerche e statistiche in un file, o ripristinali da un backup precedente.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilledTonalButton(onClick = { exportLauncher.launch("strcomm-backup.json") }) { Text("⬆  Esporta") }
      FilledTonalButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text("⬇  Ripristina") }
    }

    Text("StrComm — client touch. Streaming da un unico sito di terze parti.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
  }
}

@Composable
private fun ThemeChip(label: String, mode: ThemeMode, current: ThemeMode, context: android.content.Context) {
  FilterChip(selected = mode == current, onClick = { ThemePreference.set(context, mode) }, label = { Text(label) })
}
