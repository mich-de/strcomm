package com.s4me.tv.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much of the status bar a screen has to cover itself. ClientApp pads every screen below the bar
 * except the ones that open with artwork — Home's billboard and a title page — which draw it up under
 * the status bar, like a streaming app does, and add this much to their own top.
 */
val LocalTopInset = compositionLocalOf<Dp> { 0.dp }

/**
 * Under the status bar of a screen that scrolls up beneath it: nothing while the artwork is still on screen,
 * then the page colour comes in, so scrolled text never runs into the clock ("Oppenheimer" ended up printed
 * over the time). [progress] is 0 (artwork under the bar) to 1 (solid bar). A page with a fixed back button
 * passes its row as [extra], so the bar covers the button's row too instead of text sliding under the arrow.
 */
@Composable
fun StatusBarScrim(progress: Float, modifier: Modifier = Modifier, extra: Dp = 0.dp) {
  val height = LocalTopInset.current + extra
  if (height > 0.dp && progress > 0f) {
    Box(modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.background.copy(alpha = progress.coerceIn(0f, 1f))))
  }
}
