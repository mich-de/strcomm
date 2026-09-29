package com.s4me.tv.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.s4me.tv.remote.theme.StrCommRemoteTheme
import com.s4me.tv.remote.ui.RemoteApp

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    SingletonImageLoader.setSafe { context -> ImageLoader.Builder(context).crossfade(true).crossfade(200).build() }

    setContent { StrCommRemoteTheme { RemoteApp() } }
  }
}
