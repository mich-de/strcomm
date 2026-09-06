import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

// The content engine — Channel sources, HTTP/scrape helpers, IMDb/TMDB enrichment, on-device
// stores — shared by the TV app (:app) and the touch client (:client). Pure logic + Android
// Context for SharedPreferences; no Compose, no media3.
val localProperties =
  Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(FileInputStream(f))
  }
val tmdbApiKey = (localProperties.getProperty("tmdb.apiKey") ?: System.getenv("TMDB_API_KEY") ?: "").trim()

android {
  namespace = "com.s4me.tv.engine"
  compileSdk = 36
  defaultConfig {
    minSdk = 26
    // Ratings + official trailers use the TMDB API when this is set, else a keyless web scrape.
    buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    buildConfig = true
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  api(project(":shared")) // StreamItem/ItemKind/HomeSection are part of this module's public API

  implementation(libs.androidx.core.ktx)
  implementation(libs.okhttp)
  implementation(libs.jsoup)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
}
