package com.s4me.tv.client.ui.home

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
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.s4me.tv.client.R
import com.s4me.tv.client.nav.isWideScreen
import com.s4me.tv.client.ui.components.ErrorScreen
import com.s4me.tv.client.ui.components.GreyButton
import com.s4me.tv.client.ui.components.ItemActionsSheet
import com.s4me.tv.client.ui.components.LoadingScreen
import com.s4me.tv.client.ui.components.PlayButton
import com.s4me.tv.client.ui.components.PosterCard
import com.s4me.tv.client.ui.components.RankedPosterCard
import com.s4me.tv.client.ui.components.SheetAction
import com.s4me.tv.client.ui.components.Symbol
import com.s4me.tv.client.ui.components.SymbolLabel
import com.s4me.tv.client.ui.components.TrailerView
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.HomeSection
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.engine.WatchProgressStore
import com.s4me.tv.engine.WatchlistStore
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
  onOpen: (StreamItem) -> Unit,
  onOpenMyList: () -> Unit,
  onOpenBrowse: (String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: HomeViewModel = viewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val genres by viewModel.genres.collectAsStateWithLifecycle()
  val docGenreId = remember(genres) { genres.firstOrNull { it.name.contains("document", ignoreCase = true) }?.id }
  val context = LocalContext.current
  // The long-press menu of the personal rows: the section it came from decides what it can remove.
  var menu by remember { mutableStateOf<Pair<HomeSection, StreamItem>?>(null) }

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
          val personal = section.channelId == CONTINUE_WATCHING_ID || section.channelId == WATCHLIST_ID
          SectionRow(
            section = section,
            ranked = section.channelId == TRENDING_ID || section.channelId == JUSTWATCH_ID,
            onOpen = onOpen,
            onLongPress = if (personal) { item -> menu = section to item } else null,
            onTitleClick = if (section.channelId == WATCHLIST_ID) onOpenMyList else null,
          )
        }
      }
  }

  menu?.let { (section, item) ->
    val actions =
      buildList {
        add(SheetAction(R.drawable.ic_ms_info, "Dettagli") { onOpen(item) })
        if (section.channelId == CONTINUE_WATCHING_ID) {
          add(SheetAction(R.drawable.ic_ms_close, "Rimuovi da «Continua a guardare»") {
            WatchProgressStore(context).remove(item.url)
            viewModel.refreshPersonalRows()
          })
        } else {
          add(SheetAction(R.drawable.ic_ms_close, "Rimuovi da «La mia lista»") {
            WatchlistStore(context).let { if (it.contains(item.url)) it.toggle(item) }
            viewModel.refreshPersonalRows()
          })
        }
      }
    ItemActionsSheet(title = item.seriesTitle ?: item.contentTitle ?: item.title, actions = actions, onDismiss = { menu = null })
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
        TrailerView(youtubeId = trailerId, muted = true, onPlaying = { trailerPlaying = true }, modifier = Modifier.fillMaxWidth().height(heroHeight))
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
        // "Crime • Thriller" — the genre tags under a billboard's title (once the hero is enriched).
        hero.genres?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(3)?.joinToString("  •  ")?.let {
          Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 2.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
          PlayButton("Apri", R.drawable.ic_ms_play_arrow_fill, onClick = { onOpen(hero) }, modifier = Modifier.weight(1f))
          GreyButton(
            if (inList) "Nella lista" else "La mia lista",
            if (inList) R.drawable.ic_ms_check else R.drawable.ic_ms_add,
            onClick = { inList = watchlistStore.toggle(hero) },
            modifier = Modifier.weight(1f),
          )
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

@Composable
private fun TypeFilterRow(docGenreId: Int?, onOpenBrowse: (String) -> Unit) {
  LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.padding(top = 8.dp),
  ) {
    item {
      AssistChip(
        onClick = { onOpenBrowse("") },
        label = { Text("Tutto il catalogo") },
        leadingIcon = { Symbol(R.drawable.ic_ms_explore, size = AssistChipDefaults.IconSize) },
      )
    }
    item { AssistChip(onClick = { onOpenBrowse("type=movie") }, label = { Text("Film") }, leadingIcon = { Symbol(R.drawable.ic_ms_movie, size = AssistChipDefaults.IconSize) }) }
    item { AssistChip(onClick = { onOpenBrowse("type=tv") }, label = { Text("Serie TV") }, leadingIcon = { Symbol(R.drawable.ic_ms_tv, size = AssistChipDefaults.IconSize) }) }
    if (docGenreId != null) {
      item { AssistChip(onClick = { onOpenBrowse("genre=$docGenreId") }, label = { Text("Documentari") }, leadingIcon = { Symbol(R.drawable.ic_ms_public, size = AssistChipDefaults.IconSize) }) }
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
private fun SectionRow(
  section: HomeSection,
  ranked: Boolean,
  onOpen: (StreamItem) -> Unit,
  onLongPress: ((StreamItem) -> Unit)? = null,
  onTitleClick: (() -> Unit)? = null,
) {
  Column {
    Text(
      // A row whose title opens its full screen says so with a chevron.
      text = if (onTitleClick != null) "${section.title}  ›" else section.title,
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
      modifier = (if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier).padding(start = 16.dp, bottom = 10.dp),
    )
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      itemsIndexed(section.items, key = { _, it -> it.channelId + it.url }) { index, item ->
        if (ranked) {
          RankedPosterCard(item = item, rank = item.rank ?: (index + 1), onClick = { onOpen(item) })
        } else {
          PosterCard(
            item = item,
            onClick = { onOpen(item) },
            showLabel = section.channelId == CONTINUE_WATCHING_ID,
            onLongClick = onLongPress?.let { cb -> { cb(item) } },
            showBadges = false,
            onMenu = if (section.channelId == CONTINUE_WATCHING_ID) onLongPress?.let { cb -> { cb(item) } } else null,
          )
        }
      }
    }
  }
}
