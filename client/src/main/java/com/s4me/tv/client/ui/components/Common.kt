package com.s4me.tv.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.s4me.tv.engine.ItemKind
import com.s4me.tv.engine.StreamItem

val POSTER_WIDTH = 116.dp

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
  }
}

@Composable
fun ErrorScreen(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.fillMaxSize().padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    if (onRetry != null) {
      Text(
        "↻  Riprova",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp).clip(RoundedCornerShape(50)).clickable(onClick = onRetry).padding(horizontal = 20.dp, vertical = 10.dp),
      )
    }
  }
}

/** A poster tile: artwork, optional rank numeral, optional resume bar, title beneath (for the
 *  personal rows where the show name matters). Fixed [width]; height follows the 2:3 poster ratio. */
@Composable
fun PosterCard(
  item: StreamItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  width: androidx.compose.ui.unit.Dp = POSTER_WIDTH,
  showLabel: Boolean = false,
  rank: Int? = null,
) {
  Column(modifier = modifier.width(width).clickable(onClick = onClick)) {
    Box {
      AsyncImage(
        model = item.thumbnail,
        contentDescription = item.title,
        contentScale = ContentScale.Crop,
        modifier =
          Modifier.fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
      )
      // Year (top-left) and score (top-right) badges, mirroring the TV app's poster cards.
      item.year?.let { CornerBadge(it, Modifier.align(Alignment.TopStart)) }
      item.quality?.let { CornerBadge(it, Modifier.align(Alignment.TopEnd)) }
      if (rank != null) {
        Text(
          text = "$rank",
          style = MaterialTheme.typography.headlineMedium,
          color = Color.White,
          modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
        )
      }
      item.progress?.let { p ->
        LinearProgressIndicator(
          progress = { p.coerceIn(0f, 1f) },
          color = MaterialTheme.colorScheme.primary,
          trackColor = Color.White.copy(alpha = 0.25f),
          modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        )
      }
    }
    if (showLabel) {
      Text(
        text = item.seriesTitle ?: item.contentTitle ?: item.title,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
      )
    }
  }
}

@Composable
private fun CornerBadge(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = Color.White,
    maxLines = 1,
    modifier =
      modifier
        .padding(4.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(Color.Black.copy(alpha = 0.65f))
        .padding(horizontal = 5.dp, vertical = 2.dp),
  )
}

fun kindLabel(item: StreamItem): String? =
  when (item.kind) {
    ItemKind.SERIES -> "Serie TV"
    ItemKind.MOVIE -> "Film"
    else -> null
  }

/** A wide episode row — 16:9 still on the left, number + title + synopsis on the right. */
@Composable
fun EpisodeRow(item: StreamItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
  androidx.compose.foundation.layout.Row(
    modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
  ) {
    Box(
      modifier =
        Modifier.width(150.dp)
          .aspectRatio(16f / 9f)
          .clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      AsyncImage(
        model = item.thumbnail,
        contentDescription = item.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
      item.progress?.let { p ->
        LinearProgressIndicator(
          progress = { p.coerceIn(0f, 1f) },
          color = MaterialTheme.colorScheme.primary,
          trackColor = Color.White.copy(alpha = 0.25f),
          modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
      }
    }
    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
      Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
      item.plot?.takeIf { it.isNotBlank() }?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }
}
