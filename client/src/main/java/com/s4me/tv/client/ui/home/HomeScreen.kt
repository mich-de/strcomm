package com.s4me.tv.client.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import com.s4me.tv.engine.ProfileScope
import com.s4me.tv.client.ui.profiles.ProfileAvatar
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.material3.IconButton
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.s4me.tv.client.R
import com.s4me.tv.client.nav.isWideScreen
import com.s4me.tv.client.ui.components.ErrorScreen
import com.s4me.tv.client.ui.components.HomeSkeleton
import com.s4me.tv.client.ui.components.GreyButton
import com.s4me.tv.client.ui.components.ItemActionsSheet
import com.s4me.tv.client.ui.components.LocalTopInset
import com.s4me.tv.client.ui.components.PlayButton
import com.s4me.tv.client.ui.components.PosterCard
import com.s4me.tv.client.ui.components.RankedPosterCard
import com.s4me.tv.client.ui.components.SheetAction
import com.s4me.tv.client.ui.components.StatusBarScrim
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
  onOpenSearch: () -> Unit = {},
  onOpenProfile: () -> Unit = {},
  viewModel: HomeViewModel = viewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val genres by viewModel.genres.collectAsStateWithLifecycle()
  val docGenreId = remember(genres) { genres.firstOrNull { it.name.contains("document", ignoreCase = true) }?.id }
  val context = LocalContext.current
  // Status bar height this page covers itself: the billboard runs up under it (see LocalTopInset).
  val topInset = LocalTopInset.current
  // The long-press menu of the personal rows: the section it came from decides what it can remove.
  var menu by remember { mutableStateOf<Pair<HomeSection, StreamItem>?>(null) }

  when (val s = state) {
    is HomeUiState.Loading -> HomeSkeleton(modifier)
    is HomeUiState.Error -> ErrorScreen(s.message, onRetry = viewModel::load, modifier = modifier)
    is HomeUiState.Success -> {
      val listState = rememberLazyListState()
      // The status bar turns solid as the billboard scrolls away.
      val scrimRamp = with(LocalDensity.current) { 200.dp.toPx() }
      val scrimProgress by remember { derivedStateOf { if (listState.firstVisibleItemIndex > 0) 1f else listState.firstVisibleItemScrollOffset / scrimRamp } }
      val wide = isWideScreen()
      var currentHero by remember(s.heroes) { mutableStateOf(s.heroes.firstOrNull()) }
      // The page is washed in the billboard's own colour, fading to black further down, and follows
      // it as the billboard rotates — the reference streaming home ("ricrea le UI dai video"). Only
      // on the dark theme: a dark wash under the light theme's dark text would be unreadable.
      val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
      val tint = rememberArtworkTint(currentHero?.let { heroArt(it, wide) })
      val pageBackground =
        if (dark) Brush.verticalGradient(0f to tint, 0.5f to lerp(tint, Color.Black, 0.7f), 1f to Color.Black)
        else Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background))
      Box(modifier = modifier.background(pageBackground)) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(top = topInset, bottom = 24.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          // "Per <profilo>" with search and the profile on the right, then the type pills, then the
          // billboard as a card — the phone layout of the reference. A wide screen's top bar already
          // carries the app's mark and the tabs.
          if (!wide) item { HomeHeader(onOpenSearch = onOpenSearch, onOpenProfile = onOpenProfile) }
          item { TypeFilterRow(docGenreId = docGenreId, onOpenBrowse = onOpenBrowse, modifier = Modifier.fillMaxWidth().padding(top = if (wide) 8.dp else 0.dp)) }
          if (s.heroes.isNotEmpty()) {
            item { HeroPager(heroes = s.heroes, wide = wide, onOpen = onOpen, onCurrent = { currentHero = it }) }
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
        StatusBarScrim(scrimProgress)
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
private fun HomeHeader(onOpenSearch: () -> Unit, onOpenProfile: () -> Unit) {
  val context = LocalContext.current
  val profile = remember { ProfileScope.current(context) }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp),
  ) {
    Text(
      "Per ${profile.name}",
      style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
      color = Color.White,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    IconButton(onClick = onOpenSearch) { Symbol(R.drawable.ic_ms_search, contentDescription = "Cerca", tint = Color.White) }
    ProfileAvatar(profile, size = 30.dp, modifier = Modifier.clickable(onClick = onOpenProfile))
  }
}

/** The billboard's picture: the poster on a phone's portrait card, the wide still on a tablet's. */
private fun heroArt(hero: StreamItem, wide: Boolean): String? =
  if (wide) hero.backdrop ?: hero.thumbnail else hero.thumbnail ?: hero.backdrop

@Composable
private fun HeroPager(heroes: List<StreamItem>, wide: Boolean, onOpen: (StreamItem) -> Unit, onCurrent: (StreamItem) -> Unit) {
  val pagerState = rememberPagerState(pageCount = { heroes.size })
  val context = LocalContext.current
  val watchlistStore = remember { WatchlistStore(context) }
  LaunchedEffect(pagerState.currentPage) { heroes.getOrNull(pagerState.currentPage)?.let(onCurrent) }
  // Auto-rotates the billboard every 7 s, like a streaming app's Home picks its own hero instead of
  // asking the viewer to swipe (no dot row either — see below). Skips a cycle rather than fighting an
  // in-progress manual swipe; a manual swipe otherwise just moves the next 7 s window with it.
  LaunchedEffect(heroes.size) {
    if (heroes.size <= 1) return@LaunchedEffect
    while (true) {
      delay(7000)
      if (!pagerState.isScrollInProgress) {
        pagerState.animateScrollToPage((pagerState.currentPage + 1) % heroes.size, animationSpec = tween(900))
      }
    }
  }
  // A card inset from the edges, not a full-bleed picture: portrait poster on a phone (its title is
  // part of the artwork), a wide still with the title laid over it on a tablet.
  HorizontalPager(
    state = pagerState,
    contentPadding = PaddingValues(horizontal = 16.dp),
    pageSpacing = 12.dp,
    modifier = Modifier.fillMaxWidth(),
  ) { page ->
    val hero = heroes[page]
    var inList by remember(hero.url) { mutableStateOf(watchlistStore.contains(hero.url)) }

    // A muted trailer preview on the wide card once it has been sitting still for a beat — a 16:9
    // trailer cropped into the phone's portrait card would show a sliver, so the phone keeps the poster.
    var showTrailer by remember(hero.url) { mutableStateOf(false) }
    // True only once YouTube itself reports the trailer playing — see TrailerPreview.
    var trailerPlaying by remember(hero.url) { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage, hero.trailerYoutubeId, wide) {
      showTrailer = false
      trailerPlaying = false
      if (wide && pagerState.currentPage == page && hero.trailerYoutubeId != null) {
        delay(1500)
        showTrailer = true
      }
    }

    val shape = RoundedCornerShape(12.dp)
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .aspectRatio(if (wide) 2.2f else 0.78f)
          .clip(shape)
          .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
          .background(Color.Black)
          .clickable { onOpen(hero) }
    ) {
      val trailerId = hero.trailerYoutubeId
      if (showTrailer && trailerId != null) {
        TrailerView(youtubeId = trailerId, muted = true, onPlaying = { trailerPlaying = true }, modifier = Modifier.matchParentSize())
      }
      // The picture sits OVER the trailer and fades out only once YouTube says it is playing. Not
      // every trailer can play inside an app — embedding turned off by its owner, age-restricted,
      // region-locked ("alcuni non parte YouTube es american horror story") — and those now keep the
      // picture instead of uncovering a black box. The WebView still renders underneath, so the
      // page stays "visible" to YouTube and autoplay goes ahead.
      val pictureAlpha by animateFloatAsState(if (trailerPlaying) 0f else 1f, animationSpec = tween(700), label = "heroPicture")
      AsyncImage(
        model = heroArt(hero, wide),
        contentDescription = hero.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.matchParentSize().graphicsLayer { alpha = pictureAlpha },
      )
      Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))))
      Column(
        horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
        modifier =
          Modifier.align(if (wide) Alignment.BottomStart else Alignment.BottomCenter)
            .padding(16.dp)
            .then(if (wide) Modifier.fillMaxWidth(0.5f) else Modifier.fillMaxWidth()),
      ) {
        if (wide) {
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
        }
        // "Crime • Thriller" — the genre tags under a billboard (once the hero is enriched).
        hero.genres?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(4)?.joinToString("  •  ")?.let {
          Text(it, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
          PlayButton("Apri", R.drawable.ic_ms_play_arrow_fill, onClick = { onOpen(hero) }, modifier = Modifier.weight(1f))
          GreyButton(
            if (inList) "Nella lista" else "La mia lista",
            if (inList) R.drawable.ic_ms_check else R.drawable.ic_ms_add,
            onClick = { inList = watchlistStore.toggle(hero) },
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

@Composable
private fun TypeFilterRow(docGenreId: Int?, onOpenBrowse: (String) -> Unit, modifier: Modifier = Modifier) {
  LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
    item { TypePill("Tutto il catalogo", R.drawable.ic_ms_explore) { onOpenBrowse("") } }
    item { TypePill("Film", R.drawable.ic_ms_movie) { onOpenBrowse("type=movie") } }
    item { TypePill("Serie TV", R.drawable.ic_ms_tv) { onOpenBrowse("type=tv") } }
    if (docGenreId != null) {
      item { TypePill("Documentari", R.drawable.ic_ms_public) { onOpenBrowse("genre=$docGenreId") } }
    }
  }
}

/** A pill laid over artwork: translucent dark fill and a pale outline, so it reads on any picture. */
@Composable
private fun TypePill(label: String, @DrawableRes icon: Int, onClick: () -> Unit) {
  AssistChip(
    onClick = onClick,
    label = { Text(label) },
    leadingIcon = { Symbol(icon, size = AssistChipDefaults.IconSize) },
    shape = RoundedCornerShape(50),
    colors = AssistChipDefaults.assistChipColors(containerColor = Color.Black.copy(alpha = 0.45f), labelColor = Color.White, leadingIconContentColor = Color.White),
    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
  )
}

@Composable
private fun GenreChips(genres: List<GenreOption>, onOpenGenre: (Int) -> Unit) {
  LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Pill-shaped, like TypePill above — one consistent chip language instead of Material's
    // default 8dp-rounded rectangle, which reads as an unstyled stock component next to it.
    items(genres, key = { it.id }) { g -> AssistChip(onClick = { onOpenGenre(g.id) }, label = { Text(g.name) }, shape = RoundedCornerShape(50)) }
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
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = (if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier).padding(start = 16.dp, bottom = 10.dp),
    ) {
      Text(section.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
      // A row whose title opens its full screen says so with a chevron.
      if (onTitleClick != null) Symbol(R.drawable.ic_ms_chevron_right, size = 22.dp)
    }
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      itemsIndexed(section.items, key = { _, it -> it.channelId + it.url }) { index, item ->
        if (ranked) {
          RankedPosterCard(item = item, rank = item.rank ?: (index + 1), onClick = { onOpen(item) }, showLabel = true)
        } else {
          PosterCard(
            item = item,
            onClick = { onOpen(item) },
            // Every row shows the title under the poster now ("in home sotto i titoli non ci sono i
            // nomi") — clean artwork read as unlabelled, not deliberately minimal.
            showLabel = true,
            onLongClick = onLongPress?.let { cb -> { cb(item) } },
            showBadges = false,
            onMenu = if (section.channelId == CONTINUE_WATCHING_ID) onLongPress?.let { cb -> { cb(item) } } else null,
          )
        }
      }
    }
  }
}
