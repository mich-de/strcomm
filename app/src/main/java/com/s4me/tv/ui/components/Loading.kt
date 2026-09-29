package com.s4me.tv.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Spinner + label, shared by every screen's inline Loading state so "something is happening" is
 *  never just a bare spinner with no word attached to it. For the full-screen first load, prefer
 *  [BrandedLoading] instead — this one is for a section loading in place. */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier, label: String = "Caricamento…") {
  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** Full-screen branded loading — the big StrComm wordmark breathing gently above three
 *  sequentially-pulsing dots. This is what's on screen for most of the actual network wait
 *  (the system splash is only visible for a moment), so it's the "loading" the user really sees.
 *
 *  [log] is an optional running trace (newest last) — when supplied, its tail is shown as a small
 *  terminal-style panel below the dots so a slow load reads as progress, not a hang. */
@Composable
fun BrandedLoading(modifier: Modifier = Modifier, log: List<String> = emptyList()) {
  val transition = rememberInfiniteTransition(label = "brandedLoading")
  // A slow scale + alpha "breathe" on the wordmark — subtle, not a distracting bounce.
  val breathe by
    transition.animateFloat(
      initialValue = 0.94f,
      targetValue = 1.04f,
      animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
      label = "breathe",
    )
  val glow by
    transition.animateFloat(
      initialValue = 0.55f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
      label = "glow",
    )

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
      Row(modifier = Modifier.scale(breathe).alpha(glow), verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "Str",
          style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
          color = Color.White,
        )
        Text(
          text = "Comm",
          style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
          color = MaterialTheme.colorScheme.primary,
        )
      }
      LoadingDots()
      if (log.isNotEmpty()) LoadLog(lines = log)
    }
  }
}

/** Terminal-style tail of the load trace: the last dozen lines, newest brightest, pinned to the
 *  bottom of a fixed box so earlier lines scroll up out of view. Monospace, dim — deliberately
 *  "diagnostic readout", not chrome. */
@Composable
private fun LoadLog(lines: List<String>, modifier: Modifier = Modifier) {
  val visible = lines.takeLast(12)
  Column(
    modifier = modifier.width(760.dp).height(190.dp),
    verticalArrangement = Arrangement.Bottom,
  ) {
    visible.forEachIndexed { index, line ->
      Text(
        text = line,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = Color.White.copy(alpha = if (index == visible.lastIndex) 0.92f else 0.42f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
      )
    }
  }
}

/** Three dots that pulse in sequence — a lighter, more "streaming-app" loading motif than a
 *  spinner, and cheap to animate (just alpha on three small circles). */
@Composable
private fun LoadingDots(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "dots")
  Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    repeat(3) { index ->
      val alpha by
        transition.animateFloat(
          initialValue = 0.25f,
          targetValue = 1f,
          animationSpec =
            infiniteRepeatable(
              animation = tween(600, delayMillis = index * 180),
              repeatMode = RepeatMode.Reverse,
            ),
          label = "dot$index",
        )
      Box(
        modifier =
          Modifier.size(10.dp).alpha(alpha).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
      )
    }
  }
}
