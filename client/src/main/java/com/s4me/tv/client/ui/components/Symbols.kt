package com.s4me.tv.client.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's icons are Material Symbols Rounded — the set Material 3 and current Android use — kept
 * as vector drawables in res/drawable/ic_ms_*.xml, taken unchanged from google/material-design-icons
 * (`symbols/android/<name>/materialsymbolsrounded/<name>[_fill1]_24px.xml`) except for the theme
 * tint, removed so [Icon] colours them. `_fill` is the filled variant, for a selected or primary
 * state. They replaced emoji/Unicode glyphs in labels ("⌂ 🔍 ☰ ⚙", "▶ ＋ 🗑 …") and the older
 * Material Icons ("le icone non sono moderne, sembrano standard").
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

/** Button content: a leading symbol and the label, at Material 3's own icon size and spacing. */
@Composable
fun RowScope.SymbolLabel(@DrawableRes id: Int, text: String) {
  Symbol(id, size = ButtonDefaults.IconSize)
  Spacer(Modifier.width(ButtonDefaults.IconSpacing))
  Text(text)
}
