package com.s4me.tv.client

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.s4me.tv.client.nav.Browse
import com.s4me.tv.client.nav.BrowseRoot
import com.s4me.tv.client.nav.Detail
import com.s4me.tv.client.nav.Home
import com.s4me.tv.client.nav.Player
import com.s4me.tv.client.nav.CustomListDetail
import com.s4me.tv.client.nav.CustomLists
import com.s4me.tv.client.nav.MyDownloads
import com.s4me.tv.client.nav.Search
import com.s4me.tv.client.nav.Settings
import com.s4me.tv.client.nav.TvGuide
import com.s4me.tv.client.nav.isWideScreen
import com.s4me.tv.client.nav.navKeyFor
import com.s4me.tv.client.ui.browse.BrowseScreen
import com.s4me.tv.client.ui.detail.DetailScreen
import com.s4me.tv.client.ui.downloads.DownloadsScreen
import com.s4me.tv.client.ui.guide.TvGuideScreen
import com.s4me.tv.client.ui.home.HomeScreen
import com.s4me.tv.client.ui.lists.CustomListDetailScreen
import com.s4me.tv.client.ui.lists.CustomListsScreen
import com.s4me.tv.client.ui.player.PlayerScreen
import com.s4me.tv.client.ui.search.SearchScreen
import com.s4me.tv.client.ui.settings.SettingsScreen
import com.s4me.tv.engine.ChannelRegistry

private data class TabDest(val label: String, val glyph: String, val root: NavKey)

private val TABS =
  listOf(
    TabDest("Home", "⌂", Home),
    TabDest("Cerca", "🔍", Search()),
    TabDest("Sfoglia", "☰", BrowseRoot),
    TabDest("Impostazioni", "⚙", Settings),
  )

// Tablet/wide-screen top bar — replaces the NavigationRail. A permanent icon+label rail eats a
// column of width a touch surface doesn't need; every real streaming tablet app (Netflix incl.)
// puts top-level nav in a bar over the content instead.
@Composable
private fun WideTopBar(selectedTab: Int, onSelect: (Int) -> Unit) {
  Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
      Text(
        text =
          buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append("Str") }
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Comm") }
          },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Black,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TABS.forEachIndexed { i, t ->
          val selected = selectedTab == i
          Surface(
            shape = RoundedCornerShape(50),
            color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { onSelect(i) },
          ) {
            Text(
              text = t.label,
              style = MaterialTheme.typography.labelLarge,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
fun ClientApp() {
  val backStack = rememberNavBackStack(Home)
  var selectedTab by remember { mutableIntStateOf(0) }
  val wide = isWideScreen()
  val activity = LocalContext.current as? Activity

  fun openTab(index: Int) {
    selectedTab = index
    backStack.clear()
    backStack.add(TABS[index].root)
  }

  val open: (com.s4me.tv.engine.StreamItem) -> Unit = { backStack.add(navKeyFor(it)) }
  // Pop one screen; on a tab root, non-Home tabs fall back to Home, Home exits the app.
  val back: () -> Unit = {
    when {
      backStack.size > 1 -> backStack.removeAt(backStack.lastIndex)
      selectedTab != 0 -> openTab(0)
      else -> activity?.finish()
    }
  }
  // System back when nothing is on top of a tab root (NavDisplay only handles size > 1).
  BackHandler(enabled = backStack.size <= 1 && selectedTab != 0) { openTab(0) }

  val catalogRoot = remember { ChannelRegistry.all.firstOrNull()?.catalogRoot() }

  // The player takes the whole screen — no nav bar / rail, no scaffold insets (it also hides the
  // system bars itself, see PlayerScreen).
  val fullBleed = backStack.lastOrNull() is Player

  Scaffold(
    topBar = { if (wide && !fullBleed) WideTopBar(selectedTab = selectedTab, onSelect = { openTab(it) }) },
    bottomBar = {
      if (!wide && !fullBleed) {
        NavigationBar {
          TABS.forEachIndexed { i, t ->
            NavigationBarItem(
              selected = selectedTab == i,
              onClick = { openTab(i) },
              icon = { Text(t.glyph, style = MaterialTheme.typography.titleMedium) },
              label = { Text(t.label) },
            )
          }
        }
      }
    }
  ) { padding ->
    Row(modifier = Modifier.fillMaxSize().then(if (fullBleed) Modifier else Modifier.padding(padding))) {
      NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = { back() },
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        popTransitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) },
        predictivePopTransitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) },
        entryProvider =
          entryProvider {
            entry<Home> {
              HomeScreen(
                onOpen = open,
                onOpenBrowse = { extra -> catalogRoot?.let { backStack.add(Browse(it.copy(extra = extra))) } },
                modifier = Modifier.fillMaxSize(),
              )
            }
            entry<Search> { key ->
              SearchScreen(onOpen = open, initialQuery = key.initialQuery, isPersonQuery = key.isPersonQuery, modifier = Modifier.fillMaxSize())
            }
            entry<BrowseRoot> {
              if (catalogRoot != null) {
                BrowseScreen(root = catalogRoot, onOpen = open, onBack = back, modifier = Modifier.fillMaxSize())
              } else {
                Box(Modifier.fillMaxSize())
              }
            }
            entry<Settings> {
              SettingsScreen(
                onOpenTvGuide = { backStack.add(TvGuide) },
                onOpenCustomLists = { backStack.add(CustomLists) },
                onOpenDownloads = { backStack.add(MyDownloads) },
                modifier = Modifier.fillMaxSize(),
              )
            }
            entry<MyDownloads> { DownloadsScreen(onPlay = { backStack.add(Player(it)) }, onBack = back, modifier = Modifier.fillMaxSize()) }
            entry<TvGuide> { TvGuideScreen(onOpen = open, onBack = back, modifier = Modifier.fillMaxSize()) }
            entry<CustomLists> {
              CustomListsScreen(onOpenList = { id -> backStack.add(CustomListDetail(id)) }, onBack = back, modifier = Modifier.fillMaxSize())
            }
            entry<CustomListDetail> { key ->
              CustomListDetailScreen(listId = key.listId, onOpen = open, onBack = back, modifier = Modifier.fillMaxSize())
            }
            entry<Browse> { key -> BrowseScreen(root = key.item, onOpen = open, onBack = back, modifier = Modifier.fillMaxSize()) }
            entry<Detail> { key ->
              DetailScreen(
                item = key.item,
                onOpen = open,
                onPlay = { backStack.add(Player(it)) },
                onPersonSearch = { name -> backStack.add(Search(initialQuery = name, isPersonQuery = true)) },
                onBack = back,
                modifier = Modifier.fillMaxSize(),
              )
            }
            entry<Player> { key -> PlayerScreen(item = key.item, onBack = back, modifier = Modifier.fillMaxSize()) }
          },
      )
    }
  }
}
