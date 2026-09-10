import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// StrComm — the touch client for phones and tablets (portrait + landscape). Full streaming client
// (browse / search / detail / player), same content engine as the TV app (:engine). The TV remote
// lives in :mobile and is unrelated.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
  Properties().apply { if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile)) }

android {
  namespace = "com.s4me.tv.client"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.s4me.tv.client"
    minSdk = 26
    targetSdk = 36
    versionCode = 2
    versionName = "1.1"
  }

  signingConfigs {
    if (keystorePropertiesFile.exists()) {
      create("release") {
        storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
        storePassword = keystoreProperties.getProperty("storePassword")
        keyAlias = keystoreProperties.getProperty("keyAlias")
        keyPassword = keystoreProperties.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (keystorePropertiesFile.exists()) signingConfig = signingConfigs.getByName("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = false
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(project(":shared"))
  implementation(project(":engine"))

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)

  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.exoplayer.hls)
  implementation(libs.androidx.media3.datasource.okhttp)
  implementation(libs.androidx.media3.ui)

  implementation(libs.okhttp)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)

  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)
}
