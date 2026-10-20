package com.s4me.tv.client.ui.profiles

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s4me.tv.client.R
import com.s4me.tv.client.ui.components.Symbol
import com.s4me.tv.engine.Profile
import com.s4me.tv.engine.ProfileScope

/** Avatar colours, picked by a profile's `colorIndex`. */
private val AVATAR_COLORS = listOf(0xFFE8A317, 0xFF3F7FE0, 0xFF22A67A, 0xFFD9534F, 0xFF8E5BD6, 0xFFD6559B).map { Color(it) }

/**
 * "Chi sta guardando?": each profile keeps its own progress, list, searches and ratings (see
 * [ProfileScope]). Picking one makes it current and reports its id; "Gestisci" turns the tiles into
 * rename/delete targets and adds a tile for a new profile. [onBack] is set when it is opened from
 * Settings rather than shown at launch.
 */
@Composable
fun ProfilesScreen(onPicked: (String) -> Unit, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
  val context = LocalContext.current
  var profiles by remember { mutableStateOf(ProfileScope.all(context)) }
  var manage by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf<Profile?>(null) }
  var creating by remember { mutableStateOf(false) }
  fun reload() {
    profiles = ProfileScope.all(context)
  }

  // Shown both at launch (straight from MainActivity, no Scaffold under it — "screen selezione si
  // vede male": Compose's default content colour outside a Surface is plain black, invisible on
  // whatever the bare window paints behind it) and from Settings (inside ClientApp's Scaffold,
  // which already supplies one). Owning a Surface here makes it correct either way.
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (onBack != null) {
          IconButton(onClick = onBack) { Icon(ImageVector.vectorResource(R.drawable.ic_ms_arrow_back), contentDescription = "Indietro") }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { manage = !manage }) { Text(if (manage) "Fine" else "Gestisci") }
      }
      Text(
        if (manage) "Gestisci profili" else "Chi sta guardando?",
        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 32.dp),
      )
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        items(profiles, key = { it.id }) { profile ->
          ProfileTile(
            profile = profile,
            manage = manage,
            current = profile.id == ProfileScope.currentId(context),
            onClick = {
              if (manage) {
                editing = profile
              } else {
                ProfileScope.switchTo(context, profile.id)
                onPicked(profile.id)
              }
            },
          )
        }
        if (manage && ProfileScope.canCreate(context)) {
          item(key = "add") { AddTile(onClick = { creating = true }) }
        }
      }
    }
  }

  if (creating) {
    NameDialog(
      title = "Nuovo profilo",
      initial = "",
      confirm = "Crea",
      onConfirm = {
        ProfileScope.create(context, it)
        reload()
        creating = false
      },
      onDismiss = { creating = false },
    )
  }
  editing?.let { profile ->
    var confirmDelete by remember(profile.id) { mutableStateOf(false) }
    if (confirmDelete) {
      AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Eliminare «${profile.name}»?") },
        text = { Text("Verranno cancellati la sua lista, i progressi, la cronologia e i voti. I titoli restano nel catalogo.") },
        confirmButton = {
          TextButton(
            onClick = {
              val wasCurrent = ProfileScope.currentId(context) == profile.id
              ProfileScope.delete(context, profile.id)
              reload()
              editing = null
              // The deleted profile was in use: continue as the default one.
              if (wasCurrent) onPicked(ProfileScope.DEFAULT_ID)
            }
          ) {
            Text("Elimina")
          }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
      )
    } else {
      NameDialog(
        title = "Modifica profilo",
        initial = profile.name,
        confirm = "Salva",
        onConfirm = {
          ProfileScope.rename(context, profile.id, it)
          reload()
          editing = null
        },
        onDismiss = { editing = null },
        onDelete = if (profile.id != ProfileScope.DEFAULT_ID) ({ confirmDelete = true }) else null,
      )
    }
  }
}

/** A profile's round avatar: its colour with its initial. */
@Composable
fun ProfileAvatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier.size(size).clip(CircleShape).background(AVATAR_COLORS[profile.colorIndex % AVATAR_COLORS.size]),
  ) {
    Text(
      profile.name.firstOrNull()?.uppercase() ?: "?",
      style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp),
      color = Color.White,
    )
  }
}

@Composable
private fun ProfileTile(profile: Profile, manage: Boolean, current: Boolean, onClick: () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
    Box(contentAlignment = Alignment.Center) {
      ProfileAvatar(profile, size = 96.dp)
      if (manage) {
        Box(Modifier.size(96.dp).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
          Symbol(R.drawable.ic_ms_edit, tint = Color.White, size = 32.dp)
        }
      }
    }
    Text(
      profile.name,
      style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (current) FontWeight.Bold else FontWeight.Normal),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      Symbol(R.drawable.ic_ms_person_add, size = 36.dp)
    }
    Text("Aggiungi", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
  }
}

@Composable
private fun NameDialog(
  title: String,
  initial: String,
  confirm: String,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
  onDelete: (() -> Unit)? = null,
) {
  var name by remember { mutableStateOf(initial) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column {
        OutlinedTextField(value = name, onValueChange = { name = it.take(16) }, placeholder = { Text("Nome") }, singleLine = true)
        if (onDelete != null) {
          Spacer(Modifier.height(12.dp))
          TextButton(onClick = onDelete) { Text("Elimina profilo", color = MaterialTheme.colorScheme.error) }
        }
      }
    },
    confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) { Text(confirm) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
  )
}

/** Marks a launch that already knows who is watching, so the launch gate doesn't ask a second time. */
const val EXTRA_PROFILE_CHOSEN = "com.s4me.tv.client.PROFILE_CHOSEN"

/** Starts the app afresh in a new task — used after switching profile from inside the app, so every
 *  screen and ViewModel is rebuilt against the new profile's data instead of keeping the old one's.
 *  The person has just picked, so the new launch skips the "Chi sta guardando?" gate. */
fun restartApp(activity: Activity) {
  val intent =
    Intent(activity, activity.javaClass)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
      .putExtra(EXTRA_PROFILE_CHOSEN, true)
  activity.startActivity(intent)
  activity.finish()
}
