package com.s4me.tv.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.s4me.tv.client.theme.StrCommClientTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    SingletonImageLoader.setSafe { context -> ImageLoader.Builder(context).crossfade(true).crossfade(200).build() }

    setContent { StrCommClientTheme { ClientApp() } }
  }
}
