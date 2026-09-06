package com.s4me.tv.client.ui.home

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.s4me.tv.client.nav.isWideScreen
import com.s4me.tv.client.ui.components.ErrorScreen
import com.s4me.tv.client.ui.components.LoadingScreen
import com.s4me.tv.client.ui.components.PosterCard
import com.s4me.tv.engine.GenreOption
import com.s4me.tv.engine.HomeSection
import com.s4me.tv.engine.StreamItem

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
  HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().height(heroHeight)) { page ->
    val hero = heroes[page]
    Box(modifier = Modifier.fillMaxWidth().height(heroHeight).clickable { onOpen(hero) }) {
      AsyncImage(
        model = hero.backdrop ?: hero.thumbnail,
        contentDescription = hero.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().height(heroHeight).background(MaterialTheme.colorScheme.surfaceVariant),
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
        Button(onClick = { onOpen(hero) }, modifier = Modifier.padding(top = 12.dp)) { Text("▶  Apri") }
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
        PosterCard(
          item = item,
          onClick = { onOpen(item) },
          showLabel = section.channelId == CONTINUE_WATCHING_ID,
          rank = if (ranked) (item.rank ?: (index + 1)) else null,
        )
      }
    }
  }
}
