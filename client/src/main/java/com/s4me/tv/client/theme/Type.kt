package com.s4me.tv.client.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.s4me.tv.client.R

/**
 * Barlow — OFL-licensed (bundled under res/font; licence text kept at
 * client/third_party_licenses/barlow-OFL.txt), fetched from the google/fonts repository, not from
 * or resembling Netflix's own "Netflix Sans" in any way — in place of Material's default Roboto.
 * Roboto is the single biggest "generic Android app" tell at a glance; a distinct grotesque face is
 * one of the cheapest, highest-leverage ways to read as a built, branded streaming app instead
 * ("ancora lontana da essere come netflix").
 */
private val Barlow =
  FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
    Font(R.font.barlow_extrabold, FontWeight.ExtraBold),
  )

/** The stock Material type ramp — same sizes, same weights per style — with every style's face
 *  swapped to [Barlow]. Call sites that already `.copy(fontWeight = …)` on a style (most titles do)
 *  keep working unchanged: `copy` only touches the field it's given, so the family carries through
 *  and Compose picks the closest bundled weight to whatever is requested. */
val StrCommTypography: Typography =
  Typography().let { m ->
    Typography(
      displayLarge = m.displayLarge.copy(fontFamily = Barlow),
      displayMedium = m.displayMedium.copy(fontFamily = Barlow),
      displaySmall = m.displaySmall.copy(fontFamily = Barlow),
      headlineLarge = m.headlineLarge.copy(fontFamily = Barlow),
      headlineMedium = m.headlineMedium.copy(fontFamily = Barlow),
      headlineSmall = m.headlineSmall.copy(fontFamily = Barlow),
      titleLarge = m.titleLarge.copy(fontFamily = Barlow),
      titleMedium = m.titleMedium.copy(fontFamily = Barlow),
      titleSmall = m.titleSmall.copy(fontFamily = Barlow),
      bodyLarge = m.bodyLarge.copy(fontFamily = Barlow),
      bodyMedium = m.bodyMedium.copy(fontFamily = Barlow),
      bodySmall = m.bodySmall.copy(fontFamily = Barlow),
      labelLarge = m.labelLarge.copy(fontFamily = Barlow),
      labelMedium = m.labelMedium.copy(fontFamily = Barlow),
      labelSmall = m.labelSmall.copy(fontFamily = Barlow),
    )
  }
