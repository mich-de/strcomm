package com.s4me.tv.client.ui.components

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s4me.tv.client.R
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.flow.first

// The tricolore as the Italian government specifies it for screens; next to the app's #E53935
// the flag's own red is a deeper, cooler one.
private val ItalyGreen = Color(0xFF009246)
private val ItalyWhite = Color(0xFFF4F5F0)
private val ItalyRed = Color(0xFFCE2B37)
private val Accent = Color(0xFFE53935)
private val DeepRed = Color(0xFF8E0E15)

// The timeline, in ms, shared with tools/intro_sound.py (which makes res/raw/strcomm_intro.ogg):
// a tricolore streak and a rising whoosh until IMPACT_MS, where "StrComm" slams in letter by letter
// on the sound's deep hit; then the tricolore draws under it stripe by stripe on the three bells,
// and "ITALIA" opens out. INTRO_MS is where the intro rests if Home isn't ready yet.
private const val IMPACT_MS = 380f
private const val LETTER_STAGGER_MS = 45f
private const val LETTER_MS = 260f
private const val BELL_MS = 900f
private const val BELL_STEP_MS = 100f
private const val INTRO_MS = 1800f
private const val EXIT_MS = 800

private val WORD = "StrComm".mapIndexed { i, c -> c to if (i < 3) Color.White else Accent }

/**
 * Cinematic launch intro over the app while Home's first load runs ("metti una splashscreen mentre
 * carica i contenuti", reworked after "l'animazione è orribile" from a Figma streaming-splash
 * tutorial): a light streak, the wordmark slamming in on a deep hit, the tricolore drawn under
 * "ITALIA" on three bells, then — once [ready] — a dive through tricolore ribbons into the app,
 * after which [onFinished] runs. Our own mark, colours and sound (synthesised, see
 * tools/intro_sound.py); the tutorial's method only, none of its brand.
 */
@Composable
fun StrCommSplash(ready: Boolean, onFinished: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val isReady by rememberUpdatedState(ready)
  val finish by rememberUpdatedState(onFinished)
  val clock = remember { Animatable(0f) }
  val exit = remember { Animatable(0f) }
  var holding by remember { mutableStateOf(false) }
  val pulse by
    rememberInfiniteTransition(label = "hold")
      .animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
  val ribbons = remember { buildRibbons() }

  LaunchedEffect(Unit) {
    playIntroSound(context)
    clock.animateTo(INTRO_MS, tween(INTRO_MS.toInt(), easing = LinearEasing))
    holding = true
    snapshotFlow { isReady }.first { it }
    holding = false
    exit.animateTo(1f, tween(EXIT_MS, easing = FastOutLinearInEasing))
    finish()
  }

  Box(
    modifier =
      modifier.fillMaxSize().graphicsLayer {
        // The last stretch of the dive lets the app show through.
        alpha = 1f - ((exit.value - 0.7f) / 0.3f).coerceIn(0f, 1f)
      }
  ) {
    Canvas(Modifier.fillMaxSize().background(Color.Black)) {
      val ms = clock.value
      val ex = exit.value
      drawStreak(ms)
      // A red bloom: a flash on the hit, then a low glow that breathes while waiting.
      val flash = if (ms >= IMPACT_MS) exp(-(ms - IMPACT_MS) / 200f) else 0f
      val settle = ((ms - IMPACT_MS) / 500f).coerceIn(0f, 1f)
      val glow = (0.55f * flash + 0.22f * settle + if (holding) 0.08f * pulse else 0f) * (1f - ex)
      if (glow > 0f) {
        val radius = size.minDimension * (0.55f + 0.25f * settle)
        drawCircle(
          brush = Brush.radialGradient(listOf(Accent.copy(alpha = glow.coerceAtMost(1f)), Color.Transparent), center = center, radius = radius),
          radius = radius,
          center = center,
        )
      }
      if (ex > 0f) drawRibbons(ribbons, ex)
    }

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier =
        Modifier.align(Alignment.Center).graphicsLayer {
          val ex = exit.value
          // A slow push-in during the intro, then the dive: the mark rushes at the camera.
          val scale = (0.94f + 0.06f * (clock.value / INTRO_MS)) * (1f + 7f * ex * ex)
          scaleX = scale
          scaleY = scale
          alpha = 1f - ((ex - 0.3f) / 0.35f).coerceIn(0f, 1f)
        },
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy((-1).dp)) {
        WORD.forEachIndexed { i, (c, color) -> SlamLetter(c, color, IMPACT_MS + LETTER_STAGGER_MS * i, clock) }
      }
      Spacer(Modifier.height(10.dp))
      Canvas(Modifier.width(176.dp).height(3.dp).clip(RoundedCornerShape(percent = 50))) { drawUnderline(clock.value) }
      Spacer(Modifier.height(14.dp))
      Italia(clock.value)
    }

    AnimatedVisibility(
      visible = holding,
      enter = fadeIn(tween(600, delayMillis = 900)),
      exit = fadeOut(tween(150)),
      modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 56.dp),
    ) {
      TricoloreLoader(Modifier.width(120.dp).height(3.dp))
    }
  }
}

/** One letter of the wordmark: drops in from larger and above, glowing, on its own beat. */
@Composable
private fun SlamLetter(c: Char, color: Color, startMs: Float, clock: Animatable<Float, AnimationVector1D>) {
  Text(
    text = c.toString(),
    color = color,
    fontSize = 58.sp,
    fontWeight = FontWeight.ExtraBold,
    style = MaterialTheme.typography.displayMedium.copy(shadow = Shadow(color = Accent.copy(alpha = 0.7f), offset = Offset.Zero, blurRadius = 38f)),
    modifier =
      Modifier.graphicsLayer {
        val p = ((clock.value - startMs) / LETTER_MS).coerceIn(0f, 1f)
        val e = FastOutSlowInEasing.transform(p)
        alpha = (p * 2.5f).coerceAtMost(1f)
        val scale = 1.8f - 0.8f * e
        scaleX = scale
        scaleY = scale
        translationY = (1f - e) * -28.dp.toPx()
      },
  )
}

/** "ITALIA" opening out: spaced capitals whose tracking widens as they fade in, after the bells. */
@Composable
private fun Italia(ms: Float) {
  val p = ((ms - BELL_MS) / 700f).coerceIn(0f, 1f)
  val e = FastOutSlowInEasing.transform(p)
  Text(
    text = "ITALIA",
    color = Color.White.copy(alpha = 0.9f * e),
    fontSize = 15.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (2f + 8f * e).sp,
  )
}

// The streak: three thin lines in the flag's colours sweeping left to right across the middle,
// riding the whoosh, then gone as the letters land.
private fun DrawScope.drawStreak(ms: Float) {
  if (ms >= IMPACT_MS + 160f) return
  val p = FastOutSlowInEasing.transform((ms / IMPACT_MS).coerceIn(0f, 1f))
  val fade = 1f - ((ms - IMPACT_MS) / 160f).coerceIn(0f, 1f)
  val head = -size.width * 0.1f + size.width * 1.2f * p
  val tail = head - size.width * 0.55f
  val line = 2.dp.toPx()
  listOf(ItalyGreen, ItalyWhite, ItalyRed).forEachIndexed { i, color ->
    val y = center.y + (i - 1) * line * 1.6f
    drawLine(
      brush = Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = fade)), startX = tail, endX = head),
      start = Offset(tail, y),
      end = Offset(head, y),
      strokeWidth = line,
    )
  }
}

// The underline: green, white, red, each third drawing itself out on its bell.
private fun DrawScope.drawUnderline(ms: Float) {
  val third = size.width / 3f
  listOf(ItalyGreen, ItalyWhite, ItalyRed).forEachIndexed { i, color ->
    val p = FastOutSlowInEasing.transform(((ms - (BELL_MS + BELL_STEP_MS * i)) / 180f).coerceIn(0f, 1f))
    if (p > 0f) drawRect(color = color, topLeft = Offset(third * i, 0f), size = Size(third * p + if (i < 2) 1f else 0f, size.height))
  }
}

private class Ribbon(val x: Float, val width: Float, val color: Color)

// Vertical bands spread around the centre (x and width as fractions of the screen width), in the
// flag's colours with the accent and a deep red for depth. Fixed seed: the same dive every launch.
private fun buildRibbons(): List<Ribbon> {
  val rng = Random(11)
  val colors = listOf(ItalyGreen, ItalyWhite, ItalyRed, Accent, DeepRed, ItalyGreen, ItalyRed)
  return List(28) { i -> Ribbon(x = (rng.nextFloat() - 0.5f) * 0.5f, width = 0.008f + rng.nextFloat() * 0.035f, color = colors[i % colors.size]) }
}

// The dive: the bands start packed behind the mark and rush outward and wider, as if the camera
// flew through them.
private fun DrawScope.drawRibbons(ribbons: List<Ribbon>, ex: Float) {
  val spread = 1f + 10f * ex * ex
  val alpha = sin(PI.toFloat() * ex.coerceIn(0f, 1f)).coerceIn(0f, 1f)
  for (r in ribbons) {
    val w = r.width * size.width * spread
    val x = center.x + r.x * size.width * spread - w / 2f
    drawRect(color = r.color.copy(alpha = 0.85f * alpha), topLeft = Offset(x, 0f), size = Size(w, size.height))
  }
}

/** Indeterminate progress in the flag's colours, only if Home is still loading after the intro. */
@Composable
private fun TricoloreLoader(modifier: Modifier = Modifier) {
  val sweep by
    rememberInfiniteTransition(label = "loader")
      .animateFloat(-0.35f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
  Canvas(modifier.clip(RoundedCornerShape(percent = 50))) {
    drawTricolore(alpha = 0.2f)
    val left = sweep * size.width
    clipRect(left = left, right = left + size.width * 0.35f) { drawTricolore(alpha = 1f) }
  }
}

private fun DrawScope.drawTricolore(alpha: Float) {
  val third = size.width / 3f
  listOf(ItalyGreen, ItalyWhite, ItalyRed).forEachIndexed { i, color ->
    drawRect(color = color.copy(alpha = alpha), topLeft = Offset(third * i, 0f), size = Size(third + if (i < 2) 1f else 0f, size.height))
  }
}

// The sting plays on the media stream, and only with the ringer on: a phone set to silent or
// vibrate opens without a boom (opening the app at night shouldn't wake the house).
private fun playIntroSound(context: Context) {
  val audio = context.getSystemService(AudioManager::class.java)
  if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
  val player = runCatching { MediaPlayer.create(context, R.raw.strcomm_intro) }.getOrNull() ?: return
  player.setOnCompletionListener { it.release() }
  runCatching { player.start() }.onFailure { player.release() }
}
