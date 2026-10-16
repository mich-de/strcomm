package com.s4me.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.s4me.tv.R
import java.util.Locale

/**
 * A small flag next to a track's language name in TracksMenu ("oltre la lingua puoi mettere anche
 * una bandierina?"). Same assets as :client's LanguageFlag — rasters (PNG), not vectors: hand-
 * converting ~18 flag SVGs into Android's constrained vector-drawable format wasn't reliable, and
 * flag *emoji* render inconsistently across OEM keyboards. Rasterised once from flag-icons (MIT;
 * licence at third_party_licenses/flag-icons-MIT.txt, repo root) under res/drawable-nodpi. Keyed on
 * the normalised ISO-639-1 language code, so a source stream's "it" or "ita" both resolve the same
 * way; an unmapped language just shows no flag.
 */
private val LANGUAGE_FLAGS =
  mapOf(
    "it" to R.drawable.flag_it,
    "en" to R.drawable.flag_gb,
    "es" to R.drawable.flag_es,
    "fr" to R.drawable.flag_fr,
    "de" to R.drawable.flag_de,
    "pt" to R.drawable.flag_pt,
    "ja" to R.drawable.flag_jp,
    "ko" to R.drawable.flag_kr,
    "zh" to R.drawable.flag_cn,
    "ru" to R.drawable.flag_ru,
    "ar" to R.drawable.flag_sa,
    "hi" to R.drawable.flag_in,
    "nl" to R.drawable.flag_nl,
    "sv" to R.drawable.flag_se,
    "pl" to R.drawable.flag_pl,
    "tr" to R.drawable.flag_tr,
    "el" to R.drawable.flag_gr,
    "uk" to R.drawable.flag_ua,
  )

private fun languageFlagRes(code: String?): Int? = code?.let { runCatching { LANGUAGE_FLAGS[Locale(it).language] }.getOrNull() }

/** Fallback when a track carries no usable language code at all — common on "[Forced]" renditions
 * from this source, which (per engine/download's own prior finding) signal forced-ness only in
 * their human name, not a structured attribute; the same renditions often have no LANGUAGE tag
 * either. Their name is in English ("Italian [Forced]", "English [CC]" …), so a small word map
 * covers it — matched as a leading word so "[Forced]"/"[CC]" suffixes don't need stripping. */
private val ENGLISH_NAME_TO_CODE =
  mapOf(
    "italian" to "it",
    "english" to "en",
    "spanish" to "es",
    "french" to "fr",
    "german" to "de",
    "portuguese" to "pt",
    "japanese" to "ja",
    "korean" to "ko",
    "chinese" to "zh",
    "russian" to "ru",
    "arabic" to "ar",
    "hindi" to "hi",
    "dutch" to "nl",
    "swedish" to "sv",
    "polish" to "pl",
    "turkish" to "tr",
    "greek" to "el",
    "ukrainian" to "ua",
  )

private fun languageFlagRes(code: String?, name: String?): Int? {
  languageFlagRes(code)?.let { return it }
  val word = name?.trim()?.substringBefore(' ')?.lowercase() ?: return null
  return ENGLISH_NAME_TO_CODE[word]?.let { languageFlagRes(it) }
}

/** Renders nothing for a language with no mapped flag, so callers can place it unconditionally.
 *  [name] is the track's own display name/label — the fallback above when [code] alone resolves
 *  nothing. */
@Composable
fun LanguageFlag(code: String?, name: String? = null, modifier: Modifier = Modifier) {
  val res = languageFlagRes(code, name) ?: return
  Image(
    painter = painterResource(res),
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier = modifier.size(width = 20.dp, height = 15.dp).clip(RoundedCornerShape(2.dp)),
  )
}
