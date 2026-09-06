package com.s4me.tv

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.s4me.tv.remote.PlaybackCommand
import com.s4me.tv.remote.PlaybackControlBridge
import com.s4me.tv.remote.RemoteControlBridge
import com.s4me.tv.ui.browse.BrowseScreen
import com.s4me.tv.ui.detail.DetailScreen
import com.s4me.tv.ui.home.HomeScreen
import com.s4me.tv.ui.player.PlayerScreen
import com.s4me.tv.ui.search.SearchScreen
import com.s4me.tv.ui.settings.SettingsScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Home)

  // A pick sent from the phone companion app lands exactly where tapping that same card on the TV
  // itself would — navKeyFor() is the one place that decision is made everywhere else too.
  LaunchedEffect(Unit) { RemoteControlBridge.incoming.collect { item -> backStack.add(navKeyFor(item)) } }

  // A remote "stop" pops the player exactly like the physical BACK button would — PlayerScreen
  // itself has no onNavigate/onBack callback to call directly, so this is the one place that can.
  LaunchedEffect(Unit) {
    PlaybackControlBridge.commands.collect { command -> if (command == PlaybackCommand.STOP) backStack.removeLastOrNull() }
  }

  // A soft cross-fade between screens instead of an instant hard cut — the single most noticeable
  // "this feels like a real streaming app" change, and cheap (an alpha animation, no layout work).
  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
    popTransitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
    predictivePopTransitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
    entryProvider =
      entryProvider {
        entry<Home> { HomeScreen(onNavigate = { key -> backStack.add(key) }, modifier = Modifier.fillMaxSize().safeDrawingPadding()) }
        entry<Search> { key ->
          SearchScreen(
            initialQuery = key.initialQuery,
            isPersonQuery = key.isPersonQuery,
            onNavigate = { nav -> backStack.add(nav) },
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
          )
        }
        entry<Settings> { SettingsScreen(modifier = Modifier.fillMaxSize().safeDrawingPadding()) }
        entry<Browse> { key ->
          BrowseScreen(root = key.item, onNavigate = { nav -> backStack.add(nav) }, modifier = Modifier.fillMaxSize().safeDrawingPadding())
        }
        entry<Detail> { key ->
          DetailScreen(item = key.item, onNavigate = { nav -> backStack.add(nav) }, modifier = Modifier.fillMaxSize().safeDrawingPadding())
        }
        entry<Player> { key -> PlayerScreen(item = key.item, modifier = Modifier.fillMaxSize()) }
      },
  )
}
