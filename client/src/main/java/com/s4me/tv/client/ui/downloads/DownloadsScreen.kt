package com.s4me.tv.client.ui.downloads

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil3.compose.AsyncImage
import com.s4me.tv.client.download.DownloadRecord
import com.s4me.tv.client.download.Downloads
import com.s4me.tv.client.download.DownloadsStore
import com.s4me.tv.client.download.KEY_ERROR
import com.s4me.tv.client.download.KEY_PROGRESS
import com.s4me.tv.engine.CacheUtil
import com.s4me.tv.engine.StreamItem

sealed interface DownloadUiState {
  data object None : DownloadUiState

  /** [progress] is null while still resolving the stream / queued. */
  data class Running(val progress: Float?) : DownloadUiState

  data class Done(val record: DownloadRecord) : DownloadUiState

  data class Failed(val message: String) : DownloadUiState
}

@Composable
fun rememberDownloadState(contentId: String): DownloadUiState {
  val context = LocalContext.current
  val infosFlow = remember(contentId) { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(Downloads.workName(contentId)) }
  val infos by infosFlow.collectAsStateWithLifecycle(initialValue = emptyList())
  val version by DownloadsStore.changes.collectAsStateWithLifecycle()
  val record = remember(contentId, version) { DownloadsStore(context).get(contentId) }
  val info = infos.lastOrNull()
  return when {
    info != null && !info.state.isFinished -> DownloadUiState.Running(info.progress.getFloat(KEY_PROGRESS, -1f).takeIf { it >= 0f })
    record != null -> DownloadUiState.Done(record)
    info?.state == WorkInfo.State.FAILED -> DownloadUiState.Failed(info.outputData.getString(KEY_ERROR) ?: "Download non riuscito")
    else -> DownloadUiState.None
  }
}

/** "⬇ Scarica" on a movie/episode page: start → progress (tap to cancel) → play offline. */
@Composable
fun DownloadButton(item: StreamItem, onPlayOffline: (StreamItem) -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val state = rememberDownloadState(item.url)
  // The progress/finished notifications need POST_NOTIFICATIONS on 13+; the download itself
  // doesn't, so it starts whatever the answer.
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Downloads.enqueue(context, item) }
  val start = {
    val needsPermission =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    if (needsPermission) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else Downloads.enqueue(context, item)
  }
  Column(modifier = modifier) {
    when (state) {
      DownloadUiState.None -> FilledTonalButton(onClick = start) { Text("⬇  Scarica") }
      is DownloadUiState.Running ->
        FilledTonalButton(onClick = { Downloads.cancel(context, item.url) }) {
          Text(state.progress?.let { "✕  Annulla download · ${(it * 100).toInt()}%" } ?: "✕  Annulla download")
        }
      is DownloadUiState.Done -> FilledTonalButton(onClick = { onPlayOffline(state.record.playable()) }) { Text("✓  Guarda offline") }
      is DownloadUiState.Failed -> {
        FilledTonalButton(onClick = start) { Text("⬇  Riprova download") }
        Text(
          state.message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }
}

/** Everything downloaded or downloading: progress + cancel, then play in-app, open in another
 *  player (VLC, MX…), or delete. Files live in Movies/StrComm, visible outside the app too. */
@Composable
fun DownloadsScreen(onPlay: (StreamItem) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val workManager = remember { WorkManager.getInstance(context) }
  val infos by remember { workManager.getWorkInfosByTagFlow(Downloads.TAG) }.collectAsStateWithLifecycle(initialValue = emptyList())
  val version by DownloadsStore.changes.collectAsStateWithLifecycle()
  val records = remember(version, infos) { DownloadsStore(context).all() }
  val active = infos.filter { !it.state.isFinished }
  val failed = infos.filter { it.state == WorkInfo.State.FAILED }
  var pendingDelete by remember { mutableStateOf<DownloadRecord?>(null) }

  Column(modifier = modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
      Text("I miei download", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
      if (failed.isNotEmpty()) TextButton(onClick = { workManager.pruneWork() }) { Text("Rimuovi errori") }
    }

    if (active.isEmpty() && failed.isEmpty() && records.isEmpty()) {
      Text(
        "Nessun download.\nApri un film o un episodio e tocca «⬇ Scarica»: il file .mkv — con tutte le lingue e i sottotitoli — finisce in Film/StrComm.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
      )
      return@Column
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      items(active, key = { it.id }) { info ->
        val progress = info.progress.getFloat(KEY_PROGRESS, -1f)
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text(titleOf(info), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress >= 0f) {
              LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
              Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
              Text("Preparazione…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
          TextButton(onClick = { workManager.cancelWorkById(info.id) }) { Text("Annulla") }
        }
      }
      items(failed, key = { it.id }) { info ->
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
          Text(titleOf(info), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(
            info.outputData.getString(KEY_ERROR) ?: "Download non riuscito",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
      items(records, key = { it.id }) { record ->
        CompletedRow(
          record = record,
          onPlay = { onPlay(record.playable()) },
          onOpenElsewhere = {
            val intent =
              Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(record.uri), "video/x-matroska").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(intent, "Apri con")) }
              .onFailure { Toast.makeText(context, "Nessun'altra app può aprire il file", Toast.LENGTH_SHORT).show() }
          },
          onDelete = { pendingDelete = record },
        )
      }
    }
  }

  pendingDelete?.let { record ->
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text("Eliminare il download?") },
      text = { Text("«${record.item.title}» verrà rimosso dal dispositivo (${CacheUtil.formatSize(record.sizeBytes)}).") },
      confirmButton = {
        TextButton(
          onClick = {
            if (!Downloads.delete(context, record)) {
              Toast.makeText(context, "Il file non si può eliminare da qui: cancellalo da Film/StrComm", Toast.LENGTH_LONG).show()
            }
            pendingDelete = null
          }
        ) {
          Text("Elimina")
        }
      },
      dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Annulla") } },
    )
  }
}

@Composable
private fun CompletedRow(record: DownloadRecord, onPlay: () -> Unit, onOpenElsewhere: () -> Unit, onDelete: () -> Unit) {
  Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    AsyncImage(
      model = record.item.backdrop ?: record.item.cover ?: record.item.thumbnail,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
      Text(
        record.fileName.removeSuffix(".mkv"),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        "${CacheUtil.formatSize(record.sizeBytes)} · ${record.audioTracks} audio · ${record.subtitleTracks} sottotitoli",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = "Guarda") }
    // Handing another app a file:// URI throws on Android 7+ — only MediaStore (content://)
    // downloads, i.e. Android 10+, can be opened elsewhere from here.
    if (record.uri.startsWith("content://")) {
      IconButton(onClick = onOpenElsewhere) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Apri con un'altra app") }
    }
    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Elimina") }
  }
}

private fun titleOf(info: WorkInfo): String =
  info.tags.firstOrNull { it.startsWith(Downloads.TITLE_TAG_PREFIX) }?.removePrefix(Downloads.TITLE_TAG_PREFIX) ?: "Download"
