package com.s4me.tv.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** One line both apps show under their Settings title: the release this is and what it's for. */
const val PERSONAL_USE_NOTICE = "app a uso esclusivamente personale"

/** This app's own versionName, read from the installed package rather than BuildConfig — the apps
 *  build without it (only `:engine` enables BuildConfig), and the package is what's really
 *  installed. "?" if the lookup fails. */
fun appVersionName(context: Context): String =
  runCatching {
    val pm = context.packageManager
    val info =
      if (Build.VERSION.SDK_INT >= 33) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
      }
    info.versionName
  }.getOrNull() ?: "?"
