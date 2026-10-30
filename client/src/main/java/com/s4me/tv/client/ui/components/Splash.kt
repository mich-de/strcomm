package com.s4me.tv.client.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The tricolore as the Italian government specifies it (screen values), not the accent red: next
// to the app's #E53935 the flag's own red is deliberately a deeper, cooler one.
private val ItalyGreen = Color(0xFF009246)
private val ItalyWhite = Color(0xFFF4F5F0)
private val ItalyRed = Color(0xFFCE2B37)
private val Accent = Color(0xFFE53935)

/**
 * Full-screen launch splash, shown over the app while Home's first load runs ("metti una
 * splashscreen mentre carica i contenuti, mettici StrComm con la flag italiana"): the split-colour
 * wordmark glowing red on black, a small Italian flag beside it, and a tricolore band underneath as
 * the progress indicator. Our own mark and colours — the reference was a streaming brand's glowing
 * letter on a dark screen, used for mood only.
 */
@Composable
fun StrCommSplash(modifier: Modifier = Modifier) {
  val entrance = remember { Animatable(0f) }
  LaunchedEffect(Unit) { entrance.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing)) }
  val loop = rememberInfiniteTransition(label = "splash")
  val glow by
    loop.animateFloat(
      initialValue = 0.55f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "glow",
    )
  val sweep by
    loop.animateFloat(
      initialValue = -0.35f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
      label = "sweep",
    )

  Box(
    contentAlignment = Alignment.Center,
    modifier =
      modifier.fillMaxSize().background(Color.Black).drawBehind {
        // A red bloom behind the mark that breathes with the glow.
        val radius = size.minDimension * 0.8f
        drawCircle(
          brush = Brush.radialGradient(listOf(Accent.copy(alpha = 0.30f * glow), Color.Transparent), center = center, radius = radius),
          radius = radius,
          center = center,
        )
      },
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier =
        Modifier.graphicsLayer {
          alpha = entrance.value
          val scale = 0.9f + 0.1f * entrance.value
          scaleX = scale
          scaleY = scale
        },
    ) {
      Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Center) {
        Text(
          text =
            buildAnnotatedString {
              withStyle(SpanStyle(color = Color.White)) { append("Str") }
              withStyle(SpanStyle(color = Accent)) { append("Comm") }
            },
          fontSize = 54.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = (-1).sp,
          style =
            MaterialTheme.typography.displayMedium.copy(
              shadow = Shadow(color = Accent.copy(alpha = 0.65f * glow), offset = Offset.Zero, blurRadius = 42f),
            ),
        )
        Spacer(Modifier.width(10.dp))
        ItalianFlag(Modifier.padding(top = 14.dp).size(width = 30.dp, height = 20.dp))
      }
      Spacer(Modifier.height(30.dp))
      TricoloreLoader(sweep = { sweep }, modifier = Modifier.width(168.dp).height(4.dp))
      Spacer(Modifier.height(18.dp))
      Text(
        text = "Caricamento dei contenuti…",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.6f),
      )
    }
  }
}

/** The flag itself, drawn rather than the track picker's PNG, so it stays crisp at any size. */
@Composable
private fun ItalianFlag(modifier: Modifier = Modifier) {
  Canvas(modifier.clip(RoundedCornerShape(3.dp))) { drawTricolore(alpha = 1f) }
}

/**
 * Indeterminate progress in the flag's colours: the whole band dimmed, with a bright window
 * sweeping across it left to right.
 */
@Composable
private fun TricoloreLoader(sweep: () -> Float, modifier: Modifier = Modifier) {
  Canvas(modifier.clip(RoundedCornerShape(percent = 50))) {
    drawTricolore(alpha = 0.22f)
    val left = sweep() * size.width
    clipRect(left = left, right = left + size.width * 0.35f) { drawTricolore(alpha = 1f) }
  }
}

// Three equal stripes, left to right; each one 1 px wider so no hairline seam shows between them.
private fun DrawScope.drawTricolore(alpha: Float) {
  val third = size.width / 3f
  val colors = listOf(ItalyGreen, ItalyWhite, ItalyRed)
  colors.forEachIndexed { i, color ->
    val extra = if (i < 2) 1f else 0f
    drawRect(color = color.copy(alpha = alpha), topLeft = Offset(third * i, 0f), size = Size(third + extra, size.height))
  }
}
