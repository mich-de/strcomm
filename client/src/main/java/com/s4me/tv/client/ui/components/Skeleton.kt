package com.s4me.tv.client.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * A shimmering placeholder, shape-of-the-content-to-come, in place of a bare spinner — a streaming
 * app's cold start shows roughly what's loading, not just "please wait" (Netflix, Prime Video,
 * Disney+ all do this on first launch). Pure Compose animation, no asset.
 */
@Composable
private fun shimmerBrush(): Brush {
  val transition = rememberInfiniteTransition(label = "shimmer")
  val translate by
    transition.animateFloat(
      initialValue = -400f,
      targetValue = 1200f,
      animationSpec = infiniteRepeatable(animation = tween(1100, easing = LinearEasing), repeatMode = RepeatMode.Restart),
      label = "shimmerTranslate",
    )
  val base = MaterialTheme.colorScheme.surfaceVariant
  val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
  return Brush.linearGradient(colors = listOf(base, highlight, base), start = Offset(translate, 0f), end = Offset(translate + 400f, 0f))
}

@Composable
private fun SkeletonBlock(modifier: Modifier) {
  Spacer(modifier.clip(RoundedCornerShape(4.dp)).background(shimmerBrush()))
}

/** Mirrors Home's real shape — a billboard, then a couple of poster rows — so the page doesn't jump
 *  once the real content lands in the same spots. */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxSize()) {
    SkeletonBlock(Modifier.fillMaxWidth().height(260.dp))
    Spacer(Modifier.height(28.dp))
    repeat(2) {
      SkeletonBlock(Modifier.padding(start = 16.dp).width(140.dp).height(18.dp))
      Spacer(Modifier.height(10.dp))
      LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), userScrollEnabled = false) {
        items(6) { SkeletonBlock(Modifier.width(POSTER_WIDTH).aspectRatio(2f / 3f)) }
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}
