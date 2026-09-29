package com.s4me.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem

@Composable
fun PosterCard(
  item: StreamItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  cardModifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
) {
  // Netflix-style focus zoom: the focused card grows and rises above its row neighbors instead
  // of just showing a static border, matching the "hover" feel a mouse-driven grid gets for free.
  // scale() is a single graphicsLayer transform — GPU-cheap even with a whole row animating. An
  // animated Modifier.shadow used to sit here too, but a real blurred shadow is a separate render
  // pass per card, and dozens of them redrawing on every focus move was a measurable source of the
  // jank on this box's weak GPU — the tv.material3 Card's own focus border already conveys "this
  // one is selected", so the shadow was cost without much payoff.
  var isFocused by remember { mutableStateOf(false) }
  val scale by animateFloatAsState(if (isFocused) 1.1f else 1f, label = "posterFocusScale")
  val cardShape = RoundedCornerShape(6.dp)
  // Card's own `scale` param defaults to CardDefaults.scale(focusedScale = 1.1f) — left at its
  // default this ran a SECOND independent animateFloatAsState+graphicsLayer scale underneath the
  // manual bottom-anchored one above, compounding to ~1.21x and doubling the per-focus-move
  // animation cost on every poster on screen (the real source of the D-pad navigation jank, not
  // just this box's GPU). Pinned to 1f so only the intentional animation above runs.
  val noLibraryScale = remember { CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f) }

  Column(modifier = modifier.width(POSTER_WIDTH).zIndex(if (isFocused) 1f else 0f)) {
    Card(
      onClick = onClick,
      onLongClick = onLongClick,
      shape = CardDefaults.shape(cardShape),
      scale = noLibraryScale,
      modifier =
        cardModifier
          .fillMaxWidth()
          .aspectRatio(2f / 3f)
          // Grow from the bottom edge (TransformOrigin y=1) so the focused card rises instead of
          // expanding from its centre — a centre zoom bled ~5% downward over the title label right
          // below it, making the focused item look unlabelled.
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0.5f, 1f)
          }
          .onFocusChanged { isFocused = it.isFocused },
    ) {
      Box(Modifier.fillMaxSize()) {
        if (item.thumbnail != null) {
          AsyncImage(
            model = item.thumbnail,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(text = item.title.take(2).uppercase(), style = MaterialTheme.typography.titleMedium)
          }
        }
        // Release-year badge (top-left), mirroring the score badge on the right.
        item.year?.let { year ->
          Text(
            text = year,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier =
              Modifier.align(Alignment.TopStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
          )
        }
        // Rating badge (top-right): the site's score, the at-a-glance quality signal the user
        // asked for ("metti pure lo score del film").
        item.quality?.let { score ->
          Text(
            text = score,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier =
              Modifier.align(Alignment.TopEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
          )
        }
        // "Continua a guardare" resume bar, only when a progress fraction was attached — the
        // at-a-glance "you're X% through this" cue every streaming grid has.
        item.progress?.let { fraction ->
          Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.55f))) {
            Box(modifier = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
          }
        }
      }
    }
    // Always exactly two lines — a primary label and a secondary line — regardless of which
    // branch below fills them. Letting some cards render one line and others two (an episode's
    // seriesTitle+"Episodio N" vs. a bare title) gave rows of mixed-height cards, which is what
    // made focus-driven scrolling feel jumpy ("mi fa uno scatto in su fastidioso"): the row's
    // measured extent shifted depending on which card the layout pass happened to be sizing.
    // An episode's own title/number ("2. C'è un topo in cucina") means nothing out of context —
    // "Continua a guardare" showed rows of bare episode labels with no indication which SHOW they
    // belonged to ("mancano i titoli della serie tv il nome") — so the series name is the primary
    // label there, episode number secondary, same as Netflix's own continue-watching tiles.
    // SEASON items ALSO carry seriesTitle (so EpisodeCard headers and similar can use it) but must
    // NOT take this branch — their own title IS "Stagione N", which is the useful label here;
    // gating on item.seriesTitle != null alone showed every season card as just the series name
    // twice over ("dark matter non esce poi card con stagione 1 e stagione 2").
    val isEpisode = item.kind == ItemKind.EPISODE
    val primary = if (isEpisode) item.seriesTitle ?: item.title else item.title
    val secondary = if (isEpisode) item.episode?.let { "Episodio $it" } else item.year
    Column(modifier = Modifier.padding(top = 4.dp)) {
      Text(
        text = primary,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // Marquee-scroll the full title into view once focused instead of leaving it permanently
        // truncated with "…" ("i titoli sono tagliati, fai che scorrano per leggerle tutto") — only
        // while focused, or a whole row of simultaneously-scrolling titles would be distracting.
        modifier = if (isFocused) Modifier.basicMarquee() else Modifier,
      )
      // Year badge alone wasn't enough to tell same-named titles apart at a glance ("2 robocop uno
      // degli anni 90 e uno del 2024") — this second line always reserves its height (empty when
      // there's nothing to show) so it never affects the card's total measured height either way.
      Text(
        text = secondary.orEmpty(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** Netflix's TV grid fits noticeably more columns per row than a 136dp card allowed — this is
 *  the shared size referenced by every LazyRow/LazyVerticalGrid that lays out [PosterCard]s. */
val POSTER_WIDTH = 92.dp

/** Width of a [CoverCard] — wide (16:9) rather than tall, so a Home row fits fewer of these than
 *  of a [PosterCard], the same trade the source site's own landscape rows make. */
val COVER_WIDTH = 176.dp

/**
 * A LANDSCAPE title card, matching streamingcommunity's own site rows ("le voglio orizzontali come
 * su streamingcommunityz.taxi"): a 16:9 `cover` still with the transparent title `logo` overlaid
 * bottom-left over a scrim, instead of a portrait poster with a text label beneath. Carries the
 * same focus-zoom, year/score badges, resume bar and optional Top-10 [rank] numeral as
 * [PosterCard]. Degrades gracefully: art falls back cover → backdrop → thumbnail → flat box, and
 * the logo falls back to the plain title text. [showLabel] adds a one-line title UNDER the card
 * for the personal rows ("Continua a guardare" / "La mia lista"), where knowing which show an
 * episode belongs to matters and the overlaid logo alone was judged too easy to miss.
 */
@Composable
fun CoverCard(
  item: StreamItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  cardModifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  rank: Int? = null,
  showLabel: Boolean = false,
) {
  var isFocused by remember { mutableStateOf(false) }
  // Slightly gentler zoom than the portrait card's 1.1f — a wide card growing 10% sweeps a lot
  // more horizontal space and shoves its row neighbours further, which read as jumpy on the box.
  val scale by animateFloatAsState(if (isFocused) 1.07f else 1f, label = "coverFocusScale")
  val cardShape = RoundedCornerShape(8.dp)
  val noLibraryScale = remember { CardDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f) }

  Column(modifier = modifier.width(COVER_WIDTH).zIndex(if (isFocused) 1f else 0f)) {
    Card(
      onClick = onClick,
      onLongClick = onLongClick,
      shape = CardDefaults.shape(cardShape),
      scale = noLibraryScale,
      modifier =
        cardModifier
          .fillMaxWidth()
          .aspectRatio(16f / 9f)
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0.5f, 1f)
          }
          .onFocusChanged { isFocused = it.isFocused },
    ) {
      Box(Modifier.fillMaxSize()) {
        val art = item.cover ?: item.backdrop ?: item.thumbnail
        if (art != null) {
          AsyncImage(model = art, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
          Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
        // Bottom scrim: the `cover` art is busy toward its lower edge on plenty of titles, and the
        // logo (or fallback text) has to stay legible over any of them.
        Box(
          Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))
        )
        if (item.logo != null) {
          AsyncImage(
            model = item.logo,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).fillMaxWidth(0.66f).heightIn(max = 32.dp),
          )
        } else {
          Text(
            text = item.seriesTitle ?: item.title,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
          )
        }
        item.year?.let { year ->
          Text(
            text = year,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier =
              Modifier.align(Alignment.TopStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
          )
        }
        item.quality?.let { score ->
          Text(
            text = score,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier =
              Modifier.align(Alignment.TopEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
          )
        }
        rank?.let { r ->
          Text(
            text = "$r",
            style =
              MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Black,
                shadow = Shadow(color = Color.Black, offset = Offset.Zero, blurRadius = 14f),
              ),
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 8.dp, vertical = 6.dp),
          )
        }
        item.progress?.let { fraction ->
          Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.55f))) {
            Box(modifier = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
          }
        }
      }
    }
    if (showLabel) {
      val isEpisode = item.kind == ItemKind.EPISODE
      Text(
        text = if (isEpisode) item.seriesTitle ?: item.title else item.title,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp).then(if (isFocused) Modifier.basicMarquee() else Modifier),
      )
    }
  }
}

/**
 * A horizontal episode row, Netflix-style: a 16:9 still on the left, then the episode number/title
 * and a few lines of plot. Episode stills are landscape, so this reads far better than forcing them
 * into a portrait [PosterCard] — and it gives room to actually show the synopsis.
 */
@Composable
fun EpisodeCard(item: StreamItem, onClick: () -> Unit, modifier: Modifier = Modifier, cardModifier: Modifier = Modifier) {
  Card(
    onClick = onClick,
    shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
    modifier = modifier.fillMaxWidth().then(cardModifier),
  ) {
    Row(modifier = Modifier.height(126.dp)) {
      Box(modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f)) {
        if (item.thumbnail != null) {
          AsyncImage(
            model = item.thumbnail,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(text = item.episode?.toString() ?: "?", style = MaterialTheme.typography.headlineSmall)
          }
        }
      }
      Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          // No per-episode air date exists on this site — this is the series' own year (see
          // StreamingCommunityChannel.listEpisodes), same signal EpisodeList's caller already has,
          // just previously never shown here ("manca l'anno... episodio").
          item.year?.let {
            Text(
              text = it,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 8.dp),
            )
          }
        }
        item.plot?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      }
    }
  }
}
