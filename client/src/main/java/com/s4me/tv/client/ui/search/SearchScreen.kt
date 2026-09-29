package com.s4me.tv.client.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.s4me.tv.client.ui.components.PosterCard
import com.s4me.tv.engine.StreamItem

@Composable
fun SearchScreen(
  onOpen: (StreamItem) -> Unit,
  modifier: Modifier = Modifier,
  initialQuery: String? = null,
  isPersonQuery: Boolean = false,
  viewModel: SearchViewModel = viewModel(),
) {
  var query by remember { mutableStateOf(initialQuery.orEmpty()) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(initialQuery) {
    initialQuery?.takeIf { it.isNotBlank() }?.let { viewModel.search(it, verifyPerson = isPersonQuery) }
  }

  Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
    OutlinedTextField(
      value = query,
      onValueChange = {
        query = it
        viewModel.onQueryChange(it)
      },
      placeholder = { Text("Cerca film e serie TV") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }),
      modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )

    when (val s = state) {
      is SearchUiState.Idle ->
        if (s.history.isEmpty()) {
          Text("Digita un titolo, o un nome del cast.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
        } else {
          Column {
            Text("Ricerche recenti", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
            s.history.forEach { h ->
              Text(
                "🕑   $h",
                modifier = Modifier.fillMaxWidth().clickable {
                  query = h
                  viewModel.search(h)
                }.padding(vertical = 12.dp),
              )
            }
            Text(
              "Cancella cronologia",
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.clickable(onClick = viewModel::clearHistory).padding(vertical = 12.dp),
            )
          }
        }
      is SearchUiState.Loading ->
        Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      is SearchUiState.Error ->
        Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
      is SearchUiState.Success ->
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 108.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
          contentPadding = PaddingValues(vertical = 8.dp),
        ) {
          items(s.results, key = { it.channelId + it.url }) { item ->
            PosterCard(item = item, onClick = { onOpen(item) }, width = 108.dp, showLabel = true)
          }
        }
    }
  }
}
