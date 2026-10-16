package com.s4me.tv.client.ui.me

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.s4me.tv.client.R
import com.s4me.tv.client.ui.components.GreyButton
import com.s4me.tv.client.ui.components.PosterCard
import com.s4me.tv.client.ui.components.Symbol
import com.s4me.tv.client.ui.profiles.ProfileAvatar
import com.s4me.tv.engine.ProfileScope
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchlistStore

/**
 * "Io": whose app this is and everything personal in one place — the profile, the first titles of the
 * list, and the way into downloads, lists, the TV guide and Settings. Replaces the old Impostazioni
 * tab; Settings itself is unchanged and opens from here.
 */
@Composable
fun MeScreen(
  onOpen: (StreamItem) -> Unit,
  onOpenProfiles: () -> Unit,
  onOpenMyList: () -> Unit,
  onOpenDownloads: () -> Unit,
  onOpenLists: () -> Unit,
  onOpenGuide: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val profile = remember { ProfileScope.current(context) }
  val myList = remember { WatchlistStore(context).all().take(12) }

  Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 24.dp)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp)) {
      ProfileAvatar(profile, size = 84.dp)
      Text(profile.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 10.dp))
      GreyButton("Cambia profilo", R.drawable.ic_ms_account_circle, onClick = onOpenProfiles, modifier = Modifier.padding(top = 12.dp))
    }

    Text(
      "La mia lista  ›",
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
      modifier = Modifier.clickable(onClick = onOpenMyList).padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (myList.isEmpty()) {
      Text(
        "La tua lista è vuota. Aggiungi film e serie con “La mia lista” sulla loro pagina.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      )
    } else {
      LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(myList, key = { it.url }) { item -> PosterCard(item = item, onClick = { onOpen(item) }, showBadges = false) }
      }
    }

    Column(modifier = Modifier.padding(top = 20.dp)) {
      MeRow(R.drawable.ic_ms_download, "I miei download", onOpenDownloads)
      MeRow(R.drawable.ic_ms_list_alt, "Le mie liste personalizzate", onOpenLists)
      MeRow(R.drawable.ic_ms_live_tv, "Guida TV completa", onOpenGuide)
      MeRow(R.drawable.ic_ms_settings, "Impostazioni", onOpenSettings)
    }
  }
}

@Composable
private fun MeRow(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
  Column {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 16.dp)) {
      Symbol(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.width(16.dp))
      Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Symbol(R.drawable.ic_ms_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
