package com.s4me.tv.client.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** The split-colour "StrComm" mark: "Str" in [strColor], "Comm" in the accent. */
@Composable
fun StrCommWordmark(
  modifier: Modifier = Modifier,
  strColor: Color = MaterialTheme.colorScheme.onSurface,
  style: TextStyle = MaterialTheme.typography.titleLarge,
) {
  Text(
    text =
      buildAnnotatedString {
        withStyle(SpanStyle(color = strColor)) { append("Str") }
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Comm") }
      },
    style = style,
    fontWeight = FontWeight.Black,
    modifier = modifier,
  )
}
