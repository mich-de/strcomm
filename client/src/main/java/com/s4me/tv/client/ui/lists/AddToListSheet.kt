package com.s4me.tv.client.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.s4me.tv.engine.CustomListsStore
import com.s4me.tv.engine.StreamItem

/**
 * "Aggiungi a una lista" — picks any number of CUSTOM lists for [item] to belong to, separate
 * from (and doesn't touch) the single default "La mia lista" [com.s4me.tv.engine.WatchlistStore]
 * already handled by its own quick-toggle button. Creating a list happens right here, inline, at
 * the point you'd actually want one — not from an empty management screen with nowhere to add
 * anything from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToListSheet(item: StreamItem, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val store = remember { CustomListsStore(context) }
  var lists by remember { mutableStateOf(store.lists()) }
  var memberOf by remember { mutableStateOf(store.listsContaining(item.url)) }
  var newListName by remember { mutableStateOf("") }
  val sheetState = rememberModalBottomSheetState()

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
      Text("Aggiungi a una lista", style = MaterialTheme.typography.titleLarge)
      if (lists.isEmpty()) {
        Text(
          "Non hai ancora liste personalizzate — creane una qui sotto.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
      lists.forEach { list ->
        val checked = memberOf.contains(list.id)
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .clickable {
                if (checked) store.removeFrom(list.id, item.url) else store.addTo(list.id, item)
                memberOf = store.listsContaining(item.url)
              }
              .padding(vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Checkbox(checked = checked, onCheckedChange = null)
          Text(list.name, modifier = Modifier.padding(start = 8.dp))
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
        OutlinedTextField(
          value = newListName,
          onValueChange = { newListName = it },
          placeholder = { Text("Nuova lista…") },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
        IconButton(
          onClick = {
            val def = store.createList(newListName) ?: return@IconButton
            store.addTo(def.id, item)
            lists = store.lists()
            memberOf = store.listsContaining(item.url)
            newListName = ""
          }
        ) {
          Icon(Icons.Filled.Add, contentDescription = "Crea lista")
        }
      }
    }
  }
}
