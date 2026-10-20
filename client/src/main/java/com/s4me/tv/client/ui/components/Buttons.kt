package com.s4me.tv.client.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.s4me.tv.client.R

/**
 * The two button looks streaming apps share for a title's main actions: a solid white "play" button
 * with dark text, and a muted grey one for everything secondary. Same pairing on the Home billboard
 * and the title page — white says "this is the thing to press".
 */
@Composable
fun PlayButton(text: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  Button(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(6.dp),
    colors =
      ButtonDefaults.buttonColors(
        containerColor = Color.White,
        contentColor = Color.Black,
        disabledContainerColor = Color.White.copy(alpha = 0.4f),
        disabledContentColor = Color.Black.copy(alpha = 0.5f),
      ),
    contentPadding = PaddingValues(horizontal = 14.dp),
    modifier = modifier.height(46.dp),
  ) {
    Symbol(icon, size = ButtonDefaults.IconSize)
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text, fontWeight = FontWeight.Bold, maxLines = 1)
  }
}

@Composable
fun GreyButton(text: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  Button(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(6.dp),
    colors =
      ButtonDefaults.buttonColors(
        containerColor = Color(0xFF3A3A3C),
        contentColor = Color.White,
        disabledContainerColor = Color(0xFF2A2A2C),
        disabledContentColor = Color.White.copy(alpha = 0.4f),
      ),
    contentPadding = PaddingValues(horizontal = 14.dp),
    modifier = modifier.height(46.dp),
  ) {
    Symbol(icon, size = ButtonDefaults.IconSize)
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/** An icon over a short label — the "La mia lista / Valuta / Trailer" strip under a title's buttons. */
@Composable
fun IconAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
  ) {
    Symbol(icon, size = 26.dp)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
  }
}

/** A back arrow on a translucent disc, for screens whose top is artwork (a title page, a series page): a
 *  bare arrow would vanish into the picture. The caller places it and pads it clear of the status bar. */
@Composable
fun BackCircleButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier.padding(8.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onBack),
  ) {
    Symbol(R.drawable.ic_ms_arrow_back, contentDescription = "Indietro", tint = Color.White, size = 22.dp)
  }
}
