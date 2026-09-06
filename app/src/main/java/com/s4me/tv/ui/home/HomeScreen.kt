package com.s4me.tv.ui.home

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.s4me.tv.Search
import com.s4me.tv.Settings
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.WatchProgressStore
import com.s4me.tv.engine.WatchlistStore
import com.s4me.tv.engine.HomeSection
import com.s4me.tv.engine.StreamItem
import com.s4me.tv.navKeyFor
import com.s4me.tv.ui.components.BrandedLoading
import com.s4me.tv.ui.components.PosterCard
import com.s4me.tv.ui.components.RankedPosterCard
import com.s4me.tv.ui.components.WatchlistToggleButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onNavigate: (NavKey) -> Unit, modifier: Modifier = Modifier, viewModel: HomeViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val genres by viewModel.genres.collectAsStateWithLifecycle()
  val loadLog by viewModel.progress.collectAsStateWithLifecycle()
  val searchFocusRequester = remember { FocusRequester() }
  // Shared with HomeHeader so DOWN from either header button has one deterministic target instead
  // of relying on Compose's default spatial focus search — see the comment on HomeHeader's
  // onPreviewKeyEvent for why that default isn't reliable enough on its own.
  val heroButtonFocusRequester = remember { FocusRequester() }
  // Also shared with HomeHeader: the hero lives INSIDE the LazyColumn as its first item, so
  // LazyColumn disposes it (and this requester's target along with it) once scrolled far enough
  // away — `requestFocus()` on a disposed target quietly does nothing. Scrolling back to index 0
  // first guarantees the hero is composed again before focusing it.
  val homeListState = rememberLazyListState()
  val context = LocalContext.current

  // Double-BACK to exit: Home is the nav root, so a single stray BACK would otherwise quit the app.
  var lastBackAt by remember { mutableLongStateOf(0L) }
  BackHandler {
    val now = SystemClock.elapsedRealtime()
    if (now - lastBackAt < 2000) {
      (context as? Activity)?.finish()
    } else {
      lastBackAt = now
      Toast.makeText(context, "Premi di nuovo INDIETRO per uscire", Toast.LENGTH_SHORT).show()
    }
  }

  // Long-press OK on a personal-row card removes it (Netflix's "rimuovi da Continua a guardare").
  val onPersonalItemRemove: (String, StreamItem) -> Unit = remember(viewModel) {
    { sectionId, removed ->
      when (sectionId) {
        CONTINUE_WATCHING_ID -> {
          WatchProgressStore(context).remove(removed.url)
          Toast.makeText(context, "Rimosso da «Continua a guardare»", Toast.LENGTH_SHORT).show()
        }
        WATCHLIST_ID -> {
          WatchlistStore(context).toggle(removed)
          Toast.makeText(context, "Rimosso da «La mia lista»", Toast.LENGTH_SHORT).show()
        }
      }
      viewModel.refreshPersonalRows()
    }
  }

  // Whenever Home comes back to the foreground (returning from the player/detail, or the app being
  // resumed), refresh the "Continua a guardare" row so a just-watched title appears immediately —
  // without this, the row only updated on a full app restart.
  LifecycleResumeEffect(Unit) {
    viewModel.refreshPersonalRows()
    onPauseOrDispose {}
  }

  // First load gets the full-screen branded loading (its own wordmark) with no header, so the
  // StrComm logo isn't shown twice. Once there's content, the persistent header comes in.
  if (state is HomeUiState.Loading) {
    BrandedLoading(modifier = modifier.fillMaxSize(), log = loadLog)
    return
  }

  // The header FLOATS over the content (a Box overlay, not a Column strip): the hero billboard gets
  // the full top of the screen, Netflix-style, instead of being squeezed under a solid bar. Its own
  // top-scrim gradient keeps "StrComm"/"Cerca" readable over any artwork; the billboard's text
  // sits at its bottom-left, so the two never collide.
  Box(modifier = modifier.fillMaxSize()) {
    when (val s = state) {
      is HomeUiState.Loading -> Unit // handled above
      is HomeUiState.Error ->
        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(text = s.message, style = MaterialTheme.typography.titleMedium)
          Button(onClick = { viewModel.load() }, modifier = Modifier.padding(top = 16.dp)) { Text("↻  Riprova") }
        }
      is HomeUiState.Success ->
        HomeSections(
          heroes = s.heroes,
          sections = s.sections,
          genres = genres,
          onNavigate = onNavigate,
          onGenreClick = { genreId -> viewModel.catalogRoot?.let { onNavigate(navKeyFor(it.copy(extra = "genre=$genreId"))) } },
          searchFocusRequester = searchFocusRequester,
          heroButtonFocusRequester = heroButtonFocusRequester,
          listState = homeListState,
          onPersonalItemRemove = onPersonalItemRemove,
        )
    }
    HomeHeader(
      onBrowseClick = { viewModel.catalogRoot?.let { onNavigate(navKeyFor(it)) } },
      onSearchClick = { onNavigate(Search()) },
      onSettingsClick = { onNavigate(Settings) },
      searchFocusRequester = searchFocusRequester,
      firstContentFocusRequester = heroButtonFocusRequester,
      listState = homeListState,
      modifier = Modifier.align(Alignment.TopCenter),
    )
  }
}

/** Floating branded top bar: wordmark left, search right, on a soft black-to-transparent scrim. */
@Composable
private fun HomeHeader(
  onBrowseClick: () -> Unit,
  onSearchClick: () -> Unit,
  onSettingsClick: () -> Unit,
  searchFocusRequester: FocusRequester,
  firstContentFocusRequester: FocusRequester,
  listState: LazyListState,
  modifier: Modifier = Modifier,
) {
  // DOWN from either button used to rely entirely on Compose's default spatial focus search
  // finding something below on its own — which it usually did for "Cerca" (roughly above the
  // content) but never could for "⚙" (further right, with nothing directly below it at some
  // scroll positions), a dead end with no way back onto any card ("non posso più andare sulle
  // cards"). Explicitly redirecting DOWN to the hero button removes the guesswork for both.
  val scope = rememberCoroutineScope()
  val onDownGoToContent =
    Modifier.onPreviewKeyEvent { event ->
      if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
        // The hero is the LazyColumn's own first item — scrolled far enough away (e.g. back from
        // Search/Detail with the list still deep in "Titoli del momento" or further), LazyColumn
        // disposes it, and `requestFocus()` on a disposed target silently does nothing (no
        // exception, no visible effect) — that silent no-op, not a failed request, was the real
        // "non riesco a tornare sulle card" dead end. Scrolling to index 0 first guarantees the
        // hero is composed again before focusing it.
        scope.launch {
          listState.scrollToItem(0)
          runCatching { firstContentFocusRequester.requestFocus() }
        }
        true
      } else {
        false
      }
    }
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.75f), 1f to Color.Transparent))
        .padding(horizontal = 48.dp, vertical = 20.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(text = "Str", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Color.White)
      Text(
        text = "Comm",
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(onClick = onBrowseClick, modifier = onDownGoToContent) { Text("☰  Sfoglia") }
      Button(onClick = onSearchClick, modifier = Modifier.focusRequester(searchFocusRequester).then(onDownGoToContent)) { Text("🔍  Cerca") }
      Button(onClick = onSettingsClick, modifier = onDownGoToContent) { Text("⚙") }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeSections(
  heroes: List<StreamItem>,
  sections: List<HomeSection>,
  genres: List<GenreOption>,
  onNavigate: (NavKey) -> Unit,
  onGenreClick: (Int) -> Unit,
  searchFocusRequester: FocusRequester,
  heroButtonFocusRequester: FocusRequester,
  listState: LazyListState,
  onPersonalItemRemove: (String, StreamItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Auto-focusing the header's Search button (as if there were no hero above it) made
  // LazyColumn's bring-into-view behavior scroll down to comfortably fit the row below the
  // header, clipping the hero banner's top edge off-screen the instant this screen opened. Focus
  // the hero's own button first when there is one — keeps the initial scroll position pinned at
  // the very top, and is the more natural landing spot anyway (the hero IS the featured pick).
  // LifecycleResumeEffect (not LaunchedEffect) so this reclaims focus every time Home comes back
  // into view too, not just on first load — returning from Search/Settings/Detail otherwise left
  // focus wherever Android's default focus-finder happened to land (observed: the header's ⚙
  // Settings button, which has no defined path further down at all — "quando esco dalla ricerca
  // non posso più andare sulle cards", DOWN from there went nowhere). Same reasoning as the
  // header's own DOWN redirect: the hero is the list's first item, and if Home comes back into
  // view still scrolled deep from before the round-trip, LazyColumn has disposed it — focusing it
  // needs scrolling back to index 0 first, or the requestFocus() call is a silent no-op.
  val resumeScope = rememberCoroutineScope()
  LifecycleResumeEffect(heroes.isNotEmpty()) {
    val target = if (heroes.isNotEmpty()) heroButtonFocusRequester else searchFocusRequester
    resumeScope.launch {
      listState.scrollToItem(0)
      runCatching { target.requestFocus() }
    }
    onPauseOrDispose {}
  }

  // The hero lives INSIDE the LazyColumn (so browsing down scrolls it away, Netflix-style) but the
  // list gets a custom BringIntoViewSpec replacing the TV default. The TV default re-pivots the
  // focused element to ~30% of the viewport EVEN when it is already fully on screen — which is
  // what kept beheading the banner on open (auto-focused Guarda near the banner's bottom dragged
  // the list down). This spec scrolls NOTHING when the focused rect is already fully visible, and
  // otherwise pivots the element to HERO_PIVOT_FRACTION — tuned so that stepping back UP onto the
  // Guarda button lands the banner exactly full again.
  val gentleTvBringIntoView =
    remember {
      object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
          if (offset >= 0f && offset + size <= containerSize) return 0f
          return offset - containerSize * HERO_PIVOT_FRACTION
        }
      }
    }

  CompositionLocalProvider(LocalBringIntoViewSpec provides gentleTvBringIntoView) {
    LazyColumn(
      state = listState,
      modifier = modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(28.dp),
      contentPadding = PaddingValues(bottom = 24.dp),
    ) {
      if (heroes.isNotEmpty()) {
        item {
          HeroCarousel(
            heroes = heroes,
            onPlay = { hero -> onNavigate(navKeyFor(hero)) },
            playButtonFocusRequester = heroButtonFocusRequester,
          )
        }
      }
      if (genres.isNotEmpty()) {
        item { GenreShortcutsRow(genres = genres, onGenreClick = onGenreClick) }
      }
      items(sections, key = { it.channelId }) { section -> HomeSectionRow(section, onNavigate, onPersonalItemRemove) }
    }
  }
}

/** Where a not-fully-visible focused element gets pivoted to, as a fraction of the viewport height.
 *  0.415 ≈ the Guarda button's top inside the 300dp banner — so refocusing it from below scrolls
 *  the banner back to exactly full, with no clipped top. */
private const val HERO_PIVOT_FRACTION = 0.415f

/**
 * Netflix-style rotating billboard. Artwork + texts crossfade every few seconds (or on D-pad
 * LEFT/RIGHT while the Guarda button is focused); the button itself and the page dots live OUTSIDE
 * the crossfade so remote focus survives every rotation. The banner still follows the two
 * hard-earned rules: it must fit the viewport (an auto-focused button inside a taller-than-screen
 * banner opens the list pre-scrolled, beheading the artwork), and artwork is ContentScale.Fit
 * (a crop into a wide banner slices heads off busy stills — Dark Matter's wall-of-clones).
 */
@Composable
private fun HeroCarousel(
  heroes: List<StreamItem>,
  onPlay: (StreamItem) -> Unit,
  playButtonFocusRequester: FocusRequester,
  modifier: Modifier = Modifier,
) {
  var index by remember(heroes.size) { mutableIntStateOf(0) }
  val safeIndex = index.coerceIn(0, heroes.lastIndex)
  val current = heroes[safeIndex]

  // Auto-advance, restarted by any index change so a manual LEFT/RIGHT resets the dwell time.
  LaunchedEffect(safeIndex, heroes.size) {
    if (heroes.size < 2) return@LaunchedEffect
    delay(8000)
    index = (safeIndex + 1) % heroes.size
  }

  // 500dp: tall enough that the bottom-anchored text block starts well BELOW the floating header
  // (with a 1-line title the block tops out ~220dp from the banner top), short enough that the
  // next row still peeks underneath on this 1080p layout.
  Box(modifier = modifier.fillMaxWidth().height(300.dp).background(Color.Black)) {
    Crossfade(targetState = current, animationSpec = tween(600), label = "heroCrossfade") { hero ->
      Box(Modifier.fillMaxSize()) {
        AsyncImage(
          model = hero.backdrop,
          contentDescription = hero.title,
          contentScale = ContentScale.Fit,
          alignment = Alignment.CenterEnd,
          // Dissolve the artwork itself toward the left (alpha mask via DstIn) instead of painting
          // black on top of it: whatever the image's aspect ratio, its left edge can never show as
          // a hard vertical cut — it fades into the banner's black, which is what makes the
          // "text left, art right" split look designed instead of "una foto tagliata a metà".
          modifier =
            Modifier.matchParentSize()
              .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
              .drawWithContent {
                drawContent()
                drawRect(
                  brush =
                    Brush.horizontalGradient(
                      // Ramp starts at 44% of the banner: a 16:9 still fitted to this height begins
                      // exactly there, so its left edge lands at ~zero alpha and dissolves in from
                      // nothing; wider stills start further left, safely inside the fully
                      // transparent zone. Either way no visible vertical seam.
                      0.44f to Color.Transparent,
                      0.75f to Color.Black,
                    ),
                  blendMode = BlendMode.DstIn,
                )
              },
        )
        // A much lighter readability scrim over the fade zone — the mask above already does the
        // heavy lifting, this just keeps the plot text crisp where it overlaps the dissolving art.
        Box(
          modifier =
            Modifier.matchParentSize()
              .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.55f to Color.Transparent))
        )
        Box(
          modifier =
            Modifier.matchParentSize()
              .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.95f)))
        )
        // Texts only — the button is outside the Crossfade. Bottom padding clears the button row.
        // Title capped at ONE line: with the block bottom-anchored, a second title line grows it
        // upward — straight into the floating "StrComm" header ("si accavallano").
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, end = 96.dp, bottom = 96.dp)) {
          Text(
            text = hero.title,
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          val infoLine = listOfNotNull(hero.year, hero.quality, hero.runtime?.let { "$it min" }).joinToString("  ·  ")
          if (infoLine.isNotBlank()) {
            Text(text = infoLine, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 6.dp))
          }
          hero.plot?.let {
            Text(
              text = it,
              style = MaterialTheme.typography.bodyMedium,
              color = Color.White.copy(alpha = 0.9f),
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = 10.dp).widthIn(max = 640.dp),
            )
          }
        }
      }
    }

    Row(
      modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 28.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Button(
        onClick = { onPlay(current) },
        modifier =
          Modifier.focusRequester(playButtonFocusRequester)
            .onPreviewKeyEvent { event ->
              // Leftmost hero button: LEFT pages to the previous hero. RIGHT is deliberately NOT
              // consumed here — it moves focus onto "La mia lista" beside it, which pages forward.
              if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && heroes.size > 1) {
                index = (safeIndex - 1 + heroes.size) % heroes.size
                true
              } else {
                false
              }
            },
      ) {
        Text("▶  Guarda")
      }
      WatchlistToggleButton(
        item = current,
        modifier =
          Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight && heroes.size > 1) {
              index = (safeIndex + 1) % heroes.size
              true
            } else {
              false
            }
          },
      )
    }

    if (heroes.size > 1) {
      Row(
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        heroes.indices.forEach { i ->
          Box(
            modifier =
              Modifier.size(if (i == safeIndex) 9.dp else 6.dp)
                .background(if (i == safeIndex) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f), CircleShape)
          )
        }
      }
    }
  }
}

/** Quick genre jump-off, sitting between the hero and the content rows — each chip deep-links into
 *  Browse pre-filtered to that genre (BrowseViewModel seeds its filter from the root's `extra`). */
@Composable
private fun GenreShortcutsRow(genres: List<GenreOption>, onGenreClick: (Int) -> Unit) {
  Column {
    Text(text = "Generi", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 48.dp, bottom = 12.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      items(genres, key = { it.id }) { genre -> Button(onClick = { onGenreClick(genre.id) }) { Text(genre.name) } }
    }
  }
}

@Composable
private fun HomeSectionRow(section: HomeSection, onNavigate: (NavKey) -> Unit, onPersonalItemRemove: (String, StreamItem) -> Unit) {
  val personalRow = section.channelId == CONTINUE_WATCHING_ID || section.channelId == WATCHLIST_ID
  val trendingRow = section.channelId == TRENDING_ID || section.channelId == JUSTWATCH_ID
  Column {
    Text(text = section.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 48.dp, bottom = 12.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      itemsIndexed(section.items, key = { _, it -> it.url }) { index, item ->
        if (trendingRow) {
          RankedPosterCard(rank = item.rank ?: (index + 1), item = item, onClick = { onNavigate(navKeyFor(item)) })
        } else {
          PosterCard(
            item = item,
            onClick = { onNavigate(navKeyFor(item)) },
            onLongClick = if (personalRow) ({ onPersonalItemRemove(section.channelId, item) }) else null,
          )
        }
      }
    }
  }
}
