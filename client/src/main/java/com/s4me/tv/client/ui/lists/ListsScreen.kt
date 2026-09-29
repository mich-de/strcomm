package com.s4me.tv.client.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.s4me.tv.client.ui.components.ErrorScreen
import com.s4me.tv.client.ui.components.PosterCard
import com.s4me.tv.engine.CustomListsStore
import com.s4me.tv.engine.StreamItem

/** Index of every custom list — see [CustomListsStore]. Lists are normally created inline from
 *  [AddToListSheet]; the "+" here just covers wanting an empty one ready in advance. */
@Composable
fun CustomListsScreen(onOpenList: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val store = remember { CustomListsStore(context) }
  var lists by remember { mutableStateOf(store.lists()) }
  var showCreateDialog by remember { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
      Text("Le mie liste", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
      TextButton(onClick = { showCreateDialog = true }) { Text("+ Nuova") }
    }

    if (lists.isEmpty()) {
      ErrorScreen(
        "Nessuna lista personalizzata ancora.\nCreane una da qui o dal pulsante “Aggiungi a una lista” su un titolo.",
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Column(modifier = Modifier.fillMaxSize()) {
        lists.forEach { def ->
          val count = remember(def.id) { store.itemsIn(def.id).size }
          Row(
            modifier = Modifier.fillMaxWidth().clickable { onOpenList(def.id) }.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(def.name, style = MaterialTheme.typography.bodyLarge)
              Text(
                if (count == 1) "1 titolo" else "$count titoli",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }

  if (showCreateDialog) {
    CreateListDialog(
      onCreate = { name ->
        store.createList(name)
        lists = store.lists()
        showCreateDialog = false
      },
      onDismiss = { showCreateDialog = false },
    )
  }
}

@Composable
private fun CreateListDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
  var name by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Nuova lista") },
    text = { OutlinedTextField(value = name, onValueChange = { name = it }, placeholder = { Text("Nome della lista") }, singleLine = true) },
    confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(name) }) { Text("Crea") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
  )
}

/** One custom list's contents — a poster grid like any other, plus removing an item and deleting
 *  the whole list. */
@Composable
fun CustomListDetailScreen(listId: String, onOpen: (StreamItem) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val store = remember { CustomListsStore(context) }
  val def = remember { store.lists().firstOrNull { it.id == listId } }
  var items by remember { mutableStateOf(store.itemsIn(listId)) }
  var showDeleteDialog by remember { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
      Text(def?.name ?: "Lista", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
      TextButton(onClick = { showDeleteDialog = true }) { Text("Elimina lista") }
    }

    if (items.isEmpty()) {
      ErrorScreen("Questa lista è vuota.\nAggiungi un titolo da “Aggiungi a una lista” sulla sua pagina.", modifier = Modifier.fillMaxSize())
    } else {
      LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 120.dp), contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.url }) { item ->
          Box {
            PosterCard(item = item, onClick = { onOpen(item) }, width = 120.dp)
            IconButton(
              onClick = {
                store.removeFrom(listId, item.url)
                items = store.itemsIn(listId)
              },
              modifier =
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
            ) {
              Icon(Icons.Filled.Close, contentDescription = "Rimuovi dalla lista", tint = Color.White, modifier = Modifier.size(16.dp))
            }
          }
        }
      }
    }
  }

  if (showDeleteDialog) {
    AlertDialog(
      onDismissRequest = { showDeleteDialog = false },
      title = { Text("Eliminare «${def?.name}»?") },
      text = { Text("I titoli restano nel catalogo — verrà eliminata solo questa lista.") },
      confirmButton = {
        TextButton(
          onClick = {
            store.deleteList(listId)
            showDeleteDialog = false
            onBack()
          }
        ) {
          Text("Elimina")
        }
      },
      dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Annulla") } },
    )
  }
}
