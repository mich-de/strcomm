package com.s4me.tv.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.core.content.FileProvider
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s4me.tv.R
import com.s4me.tv.TvGuide
import com.s4me.tv.engine.AppUpdate
import com.s4me.tv.engine.BackupManager
import com.s4me.tv.engine.CacheUtil
import com.s4me.tv.engine.PERSONAL_USE_NOTICE
import com.s4me.tv.engine.SearchHistoryStore
import com.s4me.tv.engine.Tmdb
import com.s4me.tv.engine.TmdbApiKeyStore
import com.s4me.tv.engine.UpdateChecker
import com.s4me.tv.engine.WatchProgressStore
import com.s4me.tv.engine.WatchStatsStore
import com.s4me.tv.engine.WatchlistStore
import com.s4me.tv.engine.appVersionName
import com.s4me.tv.remote.RemoteControlProtocol
import com.s4me.tv.ui.components.SymbolLabel
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

  // "Hai installato?" / "possiamo prevedere che se l'app trova un update su github lo fa notare e
  // lo installa?" — UpdateChecker reads the latest GitHub release: a higher version, or the same
  // version's APK replaced in place. Inline, not a dialog: this file has no popup/overlay pattern elsewhere and a
  // D-pad-only app is simpler without one to focus-trap into.
  var checkingUpdate by remember { mutableStateOf(false) }
  var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
  var downloadingUpdate by remember { mutableStateOf(false) }
  var updateProgress by remember { mutableFloatStateOf(-1f) }


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
      SymbolLabel(R.drawable.ic_ms_live_tv, "Guida TV completa")
    }

    val update = availableUpdate
    if (update != null) {
      Text(
        text =
          if (downloadingUpdate) {
            if (updateProgress >= 0f) "Download in corso… ${(updateProgress * 100).toInt()}%" else "Download in corso…"
          } else {
            "Aggiornamento disponibile (${CacheUtil.formatSize(update.sizeBytes)})"
          },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (downloadingUpdate && updateProgress >= 0f) {
        LinearProgressIndicator(progress = { updateProgress }, modifier = Modifier.fillMaxWidth(0.5f))
      }
      if (!downloadingUpdate) {
        Button(
          onClick = {
            downloadingUpdate = true
            updateProgress = -1f
            scope.launch {
              val file =
                runCatching {
                  withContext(Dispatchers.IO) { UpdateChecker.download(context, update) { p -> withContext(Dispatchers.Main) { updateProgress = p } } }
                }.getOrNull()
              downloadingUpdate = false
              UpdateChecker.markSeen(context, update)
              availableUpdate = null
              if (file != null) installApk(context, file) else toast("Download non riuscito")
            }
          }
        ) {
          SymbolLabel(R.drawable.ic_ms_download, "Scarica e installa")
        }
      }
    } else {
      Button(
        enabled = !checkingUpdate,
        onClick = {
          checkingUpdate = true
          scope.launch {
            val found = withContext(Dispatchers.IO) { UpdateChecker.check(context, UPDATE_ASSET_PREFIX) }
            checkingUpdate = false
            availableUpdate = found
            if (found == null) toast("Hai già la build più recente")
          }
        },
      ) {
        SymbolLabel(R.drawable.ic_ms_download, if (checkingUpdate) "Verifica in corso…" else "Verifica aggiornamenti")
      }
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
      SymbolLabel(R.drawable.ic_ms_delete, "Svuota «Continua a guardare»")
    }
    Button(
      onClick = {
        watchlistStore.clearAll()
        toast("«La mia lista» svuotata")
      }
    ) {
      SymbolLabel(R.drawable.ic_ms_delete, "Svuota «La mia lista»")
    }
    Button(
      onClick = {
        historyStore.clear()
        toast("Cronologia ricerche cancellata")
      }
    ) {
      SymbolLabel(R.drawable.ic_ms_delete, "Cancella cronologia ricerche")
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
      if (clearingCache) Text("Pulizia in corso…") else SymbolLabel(R.drawable.ic_ms_cleaning_services, "Pulisci cache (${CacheUtil.formatSize(cacheBytes)})")
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
      Button(onClick = { exportLauncher.launch("strcomm-backup.json") }) { SymbolLabel(R.drawable.ic_ms_upload, "Esporta") }
      Button(onClick = { importLauncher.launch(arrayOf("application/json")) }) { SymbolLabel(R.drawable.ic_ms_download, "Ripristina") }
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

/** This app's asset name on a GitHub release, up to the version ("…-v1.0.1.apk") — must match what
 *  `gh release upload` names it, or UpdateChecker never finds it. */
private const val UPDATE_ASSET_PREFIX = "StrComm-TVBox-FireStick-"

/** Fires the system's own APK-install flow via a content:// URI (FileProvider — a bare file:// path
 *  is blocked on API 24+). Fire OS, like stock Android, still shows its own "install unknown apps"
 *  confirmation if this app hasn't been granted it yet — that's the OS's own gate, not something to
 *  route around. */
private fun installApk(context: android.content.Context, file: java.io.File) {
  val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
  val intent =
    Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  runCatching { context.startActivity(intent) }
    .onFailure { Toast.makeText(context, "Nessuna app per installare il pacchetto", Toast.LENGTH_SHORT).show() }
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
