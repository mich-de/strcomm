package com.s4me.tv.client.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One row of an [ItemActionsSheet]. */
data class SheetAction(@DrawableRes val icon: Int, val label: String, val onClick: () -> Unit)

/**
 * The menu a long press on a poster opens: the title, then what can be done to it. Picking an action
 * runs it and closes the sheet. (Netflix's mobile app has the same idea on its "continue watching"
 * row — `ContinueWatchingMenuController` in its home module.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemActionsSheet(title: String, actions: List<SheetAction>, onDismiss: () -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
      Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 8.dp),
      )
      actions.forEach { action ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier =
            Modifier.fillMaxWidth()
              .clickable {
                action.onClick()
                onDismiss()
              }
              .padding(vertical = 14.dp),
        ) {
          Symbol(action.icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(Modifier.width(16.dp))
          Text(action.label, style = MaterialTheme.typography.bodyLarge)
        }
      }
    }
  }
}
