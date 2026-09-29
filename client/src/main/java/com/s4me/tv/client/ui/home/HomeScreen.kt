package com.s4me.tv.client.ui.home

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.s4me.tv.client.nav.isWideScreen
import com.s4me.tv.client.ui.components.ErrorScreen
import com.s4me.tv.client.ui.components.LoadingScreen
import com.s4me.tv.client.ui.components.PosterCard
import com.s4me.tv.client.ui.components.RankedPosterCard
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.HomeSection
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchlistStore
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
fun HomeScreen(
  onOpen: (StreamItem) -> Unit,
  onOpenBrowse: (String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: HomeViewModel = viewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val genres by viewModel.genres.collectAsStateWithLifecycle()
  val docGenreId = remember(genres) { genres.firstOrNull { it.name.contains("document", ignoreCase = true) }?.id }

  when (val s = state) {
    is HomeUiState.Loading -> LoadingScreen(modifier)
    is HomeUiState.Error -> ErrorScreen(s.message, onRetry = viewModel::load, modifier = modifier)
    is HomeUiState.Success ->
      LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        item { TypeFilterRow(docGenreId = docGenreId, onOpenBrowse = onOpenBrowse) }
        if (s.heroes.isNotEmpty()) {
          item { HeroPager(heroes = s.heroes, onOpen = onOpen) }
        }
        if (genres.isNotEmpty()) {
          item { GenreChips(genres = genres, onOpenGenre = { id -> onOpenBrowse("genre=$id") }) }
        }
        items(s.sections, key = { it.channelId }) { section ->
          SectionRow(
            section = section,
            ranked = section.channelId == TRENDING_ID || section.channelId == JUSTWATCH_ID,
            onOpen = onOpen,
          )
        }
      }
  }
}

@Composable
private fun HeroPager(heroes: List<StreamItem>, onOpen: (StreamItem) -> Unit) {
  val pagerState = rememberPagerState(pageCount = { heroes.size })
  val heroHeight = if (isWideScreen()) 420.dp else 260.dp
  val context = LocalContext.current
  val watchlistStore = remember { WatchlistStore(context) }
  HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().height(heroHeight)) { page ->
    val hero = heroes[page]
    var inList by remember(hero.url) { mutableStateOf(watchlistStore.contains(hero.url)) }

    // A muted trailer preview once this page has been sitting still for a beat — long enough that
    // a quick flick through the carousel never starts one just to tear it down a frame later.
    var showTrailer by remember(hero.url) { mutableStateOf(false) }
    // True only once YouTube itself reports the trailer playing — see TrailerPreview.
    var trailerPlaying by remember(hero.url) { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage, hero.trailerYoutubeId) {
      showTrailer = false
      trailerPlaying = false
      if (pagerState.currentPage == page && hero.trailerYoutubeId != null) {
        delay(1500)
        showTrailer = true
      }
    }

    Box(modifier = Modifier.fillMaxWidth().height(heroHeight).clickable { onOpen(hero) }) {
      val trailerId = hero.trailerYoutubeId
      if (showTrailer && trailerId != null) {
        TrailerPreview(youtubeId = trailerId, onPlaying = { trailerPlaying = true }, modifier = Modifier.fillMaxWidth().height(heroHeight))
      }
      // The backdrop sits OVER the trailer and fades out only once YouTube says it is playing. Not
      // every trailer can play inside an app — embedding turned off by its owner, age-restricted,
      // region-locked ("alcuni non parte YouTube es american horror story") — and those now keep the
      // picture instead of uncovering a black box. The WebView still renders underneath, so the
      // page stays "visible" to YouTube and autoplay goes ahead.
      val backdropAlpha by animateFloatAsState(if (trailerPlaying) 0f else 1f, animationSpec = tween(700), label = "heroBackdrop")
      AsyncImage(
        model = hero.backdrop ?: hero.thumbnail,
        contentDescription = hero.title,
        contentScale = ContentScale.Crop,
        modifier =
          Modifier.fillMaxWidth()
            .height(heroHeight)
            .graphicsLayer { alpha = backdropAlpha }
            .background(MaterialTheme.colorScheme.surfaceVariant),
      )
      Box(
        modifier =
          Modifier.fillMaxWidth().height(heroHeight).background(
            Brush.verticalGradient(0.35f to Color.Transparent, 1f to MaterialTheme.colorScheme.background)
          )
      )
      Column(modifier = Modifier.align(Alignment.BottomStart).padding(20.dp)) {
        Text(
          text = hero.title,
          style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
          color = Color.White,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(hero.year, hero.quality, hero.runtime?.let { "$it min" }).joinToString("  ·  ")
        if (meta.isNotBlank()) {
          Text(meta, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
          Button(onClick = { onOpen(hero) }) { Text("▶  Apri") }
          OutlinedButton(onClick = { inList = watchlistStore.toggle(hero) }) {
            Text(if (inList) "✓  Nella lista" else "＋  La mia lista")
          }
        }
      }
      if (heroes.size > 1) {
        Row(
          modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          heroes.indices.forEach { i ->
            Box(
              modifier =
                Modifier.height(6.dp)
                  .width(if (i == pagerState.currentPage) 18.dp else 6.dp)
                  .clip(RoundedCornerShape(3.dp))
                  .background(if (i == pagerState.currentPage) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f))
            )
          }
        }
      }
    }
  }
}

/** Muted, looping, chromeless YouTube embed — the same trick every "hover to preview" streaming
 *  UI uses since there's no first-party player for arbitrary YouTube ids. mute=1 is what lets
 *  autoplay work at all (mediaPlaybackRequiresUserGesture = false alone isn't enough on its own
 *  once a WebView actually has audio to play — browsers block that combination outright).
 *
 *  YouTube only plays embeds that say who is embedding them, through the HTTP Referer: without one
 *  the player shows "Errore 153 · errore di configurazione del video player" ("ho un errore su s24
 *  errore configurazione video player youtube"). A URL loaded straight into a WebView sends none, so
 *  it carries the app's identity in the form YouTube documents for native apps: https://<app id>.
 *
 *  MATCH_PARENT layout params are not optional: AndroidView attaches its view WRAP_CONTENT, and a
 *  WRAP_CONTENT-tall WebView switches to sizing itself to its content — it gives the page a
 *  zero-height layout viewport, so everything 100% tall collapses. Checked over DevTools on the
 *  tablet ("YouTube non parte"): innerHeight 420, yet html, body, player and video all 800×0 — the
 *  trailer was playing, in a 0 px box, painted black. */
@Composable
private fun TrailerPreview(youtubeId: String, onPlaying: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val reportPlaying by rememberUpdatedState(onPlaying)
  val webView = remember(youtubeId) { trailerWebView(context, youtubeId) }
  DisposableEffect(webView) { onDispose { webView.destroy() } }
  AndroidView(factory = { webView }, modifier = modifier)
  // Asks the embedded player every half second whether it is actually playing (state 1); the hero
  // uncovers the video only then. YouTube's error card, or no playback within ~20 s, leaves the
  // backdrop up for good.
  LaunchedEffect(webView) {
    repeat(40) {
      delay(500)
      when (webView.evaluate(PLAYER_STATE_JS)) {
        "1" -> {
          reportPlaying()
          return@LaunchedEffect
        }
        "\"error\"" -> return@LaunchedEffect
      }
    }
  }
}

private fun trailerWebView(context: Context, youtubeId: String): WebView =
  WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    setBackgroundColor(android.graphics.Color.BLACK)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    webChromeClient = WebChromeClient()
    loadUrl(
      "https://www.youtube.com/embed/$youtubeId" +
        "?autoplay=1&mute=1&controls=0&playsinline=1&loop=1&playlist=$youtubeId&rel=0&iv_load_policy=3",
      mapOf("Referer" to "https://${context.packageName}"),
    )
  }

private suspend fun WebView.evaluate(script: String): String? = suspendCancellableCoroutine { cont ->
  evaluateJavascript(script) { cont.resume(it) }
}

/** Runs in the embed page itself: `movie_player.getPlayerState()` is 1 while playing, and `.ytp-error`
 *  is the card YouTube shows when a video can't be played here. */
private const val PLAYER_STATE_JS =
  "(function(){if(document.querySelector('.ytp-error'))return 'error';" +
    "var p=document.getElementById('movie_player');return p&&p.getPlayerState?p.getPlayerState():-2;})()"

@Composable
private fun TypeFilterRow(docGenreId: Int?, onOpenBrowse: (String) -> Unit) {
  LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.padding(top = 8.dp),
  ) {
    item { AssistChip(onClick = { onOpenBrowse("type=movie") }, label = { Text("Film") }) }
    item { AssistChip(onClick = { onOpenBrowse("type=tv") }, label = { Text("Serie TV") }) }
    if (docGenreId != null) {
      item { AssistChip(onClick = { onOpenBrowse("genre=$docGenreId") }, label = { Text("Documentari") }) }
    }
  }
}

@Composable
private fun GenreChips(genres: List<GenreOption>, onOpenGenre: (Int) -> Unit) {
  LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(genres, key = { it.id }) { g -> AssistChip(onClick = { onOpenGenre(g.id) }, label = { Text(g.name) }) }
  }
}

@Composable
private fun SectionRow(section: HomeSection, ranked: Boolean, onOpen: (StreamItem) -> Unit) {
  Column {
    Text(
      text = section.title,
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
      modifier = Modifier.padding(start = 16.dp, bottom = 10.dp),
    )
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      itemsIndexed(section.items, key = { _, it -> it.channelId + it.url }) { index, item ->
        if (ranked) {
          RankedPosterCard(item = item, rank = item.rank ?: (index + 1), onClick = { onOpen(item) })
        } else {
          PosterCard(item = item, onClick = { onOpen(item) }, showLabel = section.channelId == CONTINUE_WATCHING_ID)
        }
      }
    }
  }
}
