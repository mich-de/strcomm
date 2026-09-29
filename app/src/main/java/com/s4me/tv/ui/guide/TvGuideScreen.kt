package com.s4me.tv.ui.guide

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.s4me.tv.engine.NowOnItalianTV
import com.s4me.tv.navKeyFor

/** The full "Guida TV" behind Home's "Film stasera in TV" row — every film airing tonight on
 *  Italian digital-terrestrial channels, sorted by channel number, not just the first 20 that
 *  already happened to resolve. Selecting a row resolves it on demand (one at a time). */
@Composable
fun TvGuideScreen(onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier, viewModel: TvGuideViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val resolving by viewModel.resolving.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val firstFocusRequester = remember { FocusRequester() }

  Column(modifier = modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp)) {
    Text(text = "Guida TV · stasera", style = MaterialTheme.typography.headlineMedium)
    Text(
      text = "Film sui canali del digitale terrestre italiano, in ordine di canale",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
    )

    when (val s = state) {
      is TvGuideUiState.Loading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
      }
      is TvGuideUiState.Error -> {
        Text(text = s.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      is TvGuideUiState.Success -> {
        LaunchedEffect(Unit) { runCatching { firstFocusRequester.requestFocus() } }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          s.entries.forEachIndexed { index, entry ->
            GuideRow(
              entry = entry,
              isResolving = resolving == TvGuideViewModel.rowKey(entry),
              modifier = if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier,
              onClick = {
                viewModel.resolve(
                  entry = entry,
                  onFound = { item -> onNavigate(navKeyFor(item)) },
                  onNotFound = { Toast.makeText(context, "«${entry.title}» non è nel catalogo al momento", Toast.LENGTH_SHORT).show() },
                )
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun GuideRow(entry: NowOnItalianTV.Entry, isResolving: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Surface(
    onClick = onClick,
    enabled = !isResolving,
    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(entry.time, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(64.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          entry.channel.uppercase(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier =
            Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(
          entry.title,
          style = MaterialTheme.typography.bodyLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
      if (isResolving) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
    }
  }
}
