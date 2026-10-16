package com.s4me.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text

/**
 * The TV app's icons: the same Material Symbols Rounded vectors as `:client` (res/drawable/ic_ms_*.xml,
 * from google/material-design-icons with only the theme tint removed), replacing the emoji and
 * Unicode glyphs that used to stand in for icons in labels ("🔍  Cerca", "▶  Guarda", "🗑 …").
 * Focus colouring comes for free: [tint] defaults to the surrounding content colour, which the TV
 * Button/Surface already switches on focus.
 */
@Composable
fun Symbol(
  @DrawableRes id: Int,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
  tint: Color = LocalContentColor.current,
  size: Dp = 24.dp,
) {
  Icon(painter = painterResource(id), contentDescription = contentDescription, modifier = modifier.size(size), tint = tint)
}

/** Button content: a leading symbol and the label, at the TV Button's own icon size and spacing. */
@Composable
fun RowScope.SymbolLabel(@DrawableRes id: Int, text: String) {
  Symbol(id, size = ButtonDefaults.IconSize)
  Spacer(Modifier.width(ButtonDefaults.IconSpacing))
  Text(text)
}
