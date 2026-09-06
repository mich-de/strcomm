package com.s4me.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** A label followed by its names as individually-focusable pills (D-pad focus targets a whole
 *  composable, not a sub-string span inside one Text, so making names separately clickable on TV
 *  means laying them out as separate small elements — not `AnnotatedString` click spans, which
 *  have no way to receive D-pad focus at all). Wraps via [FlowRow] since a cast list can run to 6
 *  names. Shared by the movie Detail screen and the series Browse screen. */
@Composable
fun NameChipsRow(label: String, names: List<String>, onNameClick: (String) -> Unit, modifier: Modifier = Modifier) {
  Column(modifier = modifier.widthIn(max = 900.dp)) {
    Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
      names.forEach { name -> NameChip(name = name, onClick = { onNameClick(name) }) }
    }
  }
}

@Composable
private fun NameChip(name: String, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    colors =
      ClickableSurfaceDefaults.colors(
        containerColor = Color.White.copy(alpha = 0.08f),
        contentColor = Color.White.copy(alpha = 0.85f),
        focusedContainerColor = MaterialTheme.colorScheme.primary,
        focusedContentColor = Color.Black,
      ),
  ) {
    Text(text = name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
  }
}
