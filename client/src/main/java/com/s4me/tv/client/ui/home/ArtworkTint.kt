package com.s4me.tv.client.ui.home

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The page colour when the artwork gives nothing to go on (greyscale art, load failure). */
val NeutralTint = Color(0xFF1C1C1C)

/**
 * A deep colour taken from [url]'s artwork, animated as it changes — the Home page is washed in the
 * billboard's own colour (teal behind one title, olive behind the next) instead of flat black, the
 * way the reference streaming home does it. Loads a 48 px copy through the app's own image loader,
 * so it's usually already in the cache from the billboard itself.
 */
@Composable
fun rememberArtworkTint(url: String?): Color {
  val context = LocalContext.current
  var tint by remember { mutableStateOf(NeutralTint) }
  LaunchedEffect(url) {
    if (url == null) return@LaunchedEffect
    val bitmap =
      withContext(Dispatchers.IO) {
        runCatching {
          val request = ImageRequest.Builder(context).data(url).size(48).allowHardware(false).build()
          (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        }.getOrNull()
      } ?: return@LaunchedEffect
    tint = withContext(Dispatchers.Default) { dominantTint(bitmap) }
  }
  val animated by animateColorAsState(tint, animationSpec = tween(900), label = "artworkTint")
  return animated
}

/**
 * The artwork's dominant hue, as a dark colour text stays readable on. Hue is averaged as a vector
 * (so red at 350° and 10° don't average to cyan), weighted by each pixel's saturation × brightness
 * so the vivid parts of the picture decide it, not the black bars or a grey sky. Mostly-grey
 * artwork falls back to [NeutralTint] rather than inventing a colour.
 */
private fun dominantTint(bitmap: Bitmap): Color {
  val hsv = FloatArray(3)
  var x = 0.0
  var y = 0.0
  var weight = 0.0
  var pixels = 0
  for (py in 0 until bitmap.height) {
    for (px in 0 until bitmap.width) {
      android.graphics.Color.colorToHSV(bitmap.getPixel(px, py), hsv)
      val w = (hsv[1] * hsv[2]).toDouble()
      val rad = Math.toRadians(hsv[0].toDouble())
      x += cos(rad) * w
      y += sin(rad) * w
      weight += w
      pixels++
    }
  }
  if (pixels == 0 || weight / pixels < 0.04) return NeutralTint
  val hue = ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
  return Color.hsv(hue, 0.65f, 0.40f)
}
