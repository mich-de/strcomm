package com.s4me.tv.client.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.s4me.tv.engine.StreamItem

/**
 * Overlay cinematico mostrato durante il buffering del video su touch/tablet:
 * animazione glowing neon a doppio anello rotante, onde di streaming,
 * titolo principale, contesto stagione/episodio/anno e miniatura poster.
 */
@Composable
fun VideoBufferingOverlay(
  item: StreamItem,
  visible: Boolean,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(350)) + scaleIn(initialScale = 0.94f, animationSpec = tween(350)),
    exit = fadeOut(tween(400)) + scaleOut(targetScale = 0.98f, animationSpec = tween(400)),
    modifier = modifier,
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.62f)),
      contentAlignment = Alignment.Center,
    ) {
      val mainTitle = item.seriesTitle ?: item.contentTitle ?: item.title
      val itemYear = item.year
      val subTitle = when {
        item.season != null && item.episode != null -> {
          val epTitle = item.contentTitle?.takeIf { it != item.seriesTitle && it.isNotBlank() }
          if (epTitle != null) "Stagione ${item.season} · Ep. ${item.episode} — $epTitle"
          else "Stagione ${item.season} · Episodio ${item.episode}"
        }
        !itemYear.isNullOrBlank() -> "Film · $itemYear" + (item.quality?.let { " · $it" } ?: "")
        else -> item.quality
      }
      val poster = item.thumbnail?.takeIf { it.isNotBlank() }

      Row(
        modifier = Modifier
          .widthIn(max = 640.dp)
          .padding(horizontal = 24.dp)
          .clip(RoundedCornerShape(22.dp))
          .background(Color(0xE612141A))
          .border(
            width = 1.dp,
            brush = Brush.horizontalGradient(
              listOf(
                Color(0x55E50914),
                Color(0x33FFFFFF),
                Color(0x22E50914),
              )
            ),
            shape = RoundedCornerShape(22.dp),
          )
          .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (poster != null) {
          AsyncImage(
            model = poster,
            contentDescription = mainTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .width(58.dp)
              .height(86.dp)
              .clip(RoundedCornerShape(10.dp))
              .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
          )
        }

        Column(
          modifier = Modifier.weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            GlowingDualRingSpinner(size = 30.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "CARICAMENTO VIDEO",
                style = MaterialTheme.typography.labelSmall.copy(
                  fontWeight = FontWeight.Bold,
                  letterSpacing = 1.1.sp,
                ),
                color = Color(0xFFFF5252),
              )
              Spacer(Modifier.width(6.dp))
              PulsingLoadingDots()
            }
          }

          Text(
            text = mainTitle,
            style = MaterialTheme.typography.titleMedium.copy(
              fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )

          if (!subTitle.isNullOrBlank()) {
            Text(
              text = subTitle,
              style = MaterialTheme.typography.bodySmall,
              color = Color.White.copy(alpha = 0.72f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }

          Spacer(Modifier.height(2.dp))
          StreamingEqualizerBar()
        }
      }
    }
  }
}

@Composable
private fun GlowingDualRingSpinner(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "spinnerTransition")
  val rotateOuter by transition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
    label = "rotateOuter",
  )
  val rotateInner by transition.animateFloat(
    initialValue = 360f,
    targetValue = 0f,
    animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
    label = "rotateInner",
  )
  val pulseDot by transition.animateFloat(
    initialValue = 0.75f,
    targetValue = 1.25f,
    animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "pulseDot",
  )

  Box(
    modifier = modifier.size(size),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(
      modifier = Modifier
        .fillMaxSize()
        .rotate(rotateOuter)
    ) {
      val stroke = 3.dp.toPx()
      drawArc(
        brush = Brush.sweepGradient(
          listOf(
            Color.Transparent,
            Color(0x33FF1744),
            Color(0xFFFF1744),
          )
        ),
        startAngle = 0f,
        sweepAngle = 290f,
        useCenter = false,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
      )
    }

    Canvas(
      modifier = Modifier
        .size(size * 0.65f)
        .rotate(rotateInner)
    ) {
      val stroke = 2.dp.toPx()
      drawArc(
        brush = Brush.sweepGradient(
          listOf(
            Color.Transparent,
            Color(0x4400E5FF),
            Color(0xFF00E5FF),
          )
        ),
        startAngle = 0f,
        sweepAngle = 260f,
        useCenter = false,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
      )
    }

    Box(
      modifier = Modifier
        .size(size * 0.22f)
        .scale(pulseDot)
        .clip(CircleShape)
        .background(Color.White)
    )
  }
}

@Composable
private fun PulsingLoadingDots(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "dotsTransition")
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    repeat(3) { index ->
      val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
          animation = tween(500, delayMillis = index * 160),
          repeatMode = RepeatMode.Reverse,
        ),
        label = "dot$index",
      )
      Box(
        modifier = Modifier
          .size(4.5.dp)
          .alpha(alpha)
          .clip(CircleShape)
          .background(Color(0xFFFF5252))
      )
    }
  }
}

@Composable
private fun StreamingEqualizerBar(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "equalizerTransition")
  val bar1 by transition.animateFloat(
    initialValue = 3f,
    targetValue = 12f,
    animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "bar1",
  )
  val bar2 by transition.animateFloat(
    initialValue = 11f,
    targetValue = 4f,
    animationSpec = infiniteRepeatable(tween(580, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "bar2",
  )
  val bar3 by transition.animateFloat(
    initialValue = 5f,
    targetValue = 14f,
    animationSpec = infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "bar3",
  )
  val bar4 by transition.animateFloat(
    initialValue = 13f,
    targetValue = 6f,
    animationSpec = infiniteRepeatable(tween(490, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "bar4",
  )
  val bar5 by transition.animateFloat(
    initialValue = 4f,
    targetValue = 11f,
    animationSpec = infiniteRepeatable(tween(390, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "bar5",
  )

  Row(
    modifier = modifier.height(14.dp),
    horizontalArrangement = Arrangement.spacedBy(3.dp),
    verticalAlignment = Alignment.Bottom,
  ) {
    listOf(bar1, bar2, bar3, bar4, bar5).forEach { h ->
      Box(
        modifier = Modifier
          .width(2.5.dp)
          .height(h.dp)
          .clip(RoundedCornerShape(1.dp))
          .background(
            Brush.verticalGradient(
              listOf(Color(0xFFFF5252), Color(0xFFFF1744))
            )
          )
      )
    }
  }
}
