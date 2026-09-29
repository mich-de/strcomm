package com.s4me.tv.client.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.s4me.tv.remote.RemoteControlProtocol
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class DiscoveredTv(val host: String, val port: Int)

/**
 * One-shot "is there a StrComm TV on this network right now", for [ProgressSyncWorker] — unlike
 * :mobile's TvDiscovery (a live Flow of every TV seen, for a picker UI), a periodic background
 * sync just needs the first one found within a short window; null when none answers in time (a
 * different Wi-Fi, the TV app not running, the box asleep — all indistinguishable from here, and
 * all just mean "try again next cycle").
 */
suspend fun discoverFirstTv(context: Context, timeoutMs: Long = 4000): DiscoveredTv? =
  withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine { cont ->
      val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
      lateinit var discoveryListener: NsdManager.DiscoveryListener

      fun finish(result: DiscoveredTv?) {
        if (!cont.isActive) return
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        cont.resume(result)
      }

      discoveryListener =
        object : NsdManager.DiscoveryListener {
          override fun onDiscoveryStarted(serviceType: String) {}

          override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            runCatching {
              nsdManager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                  override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}

                  override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    finish(DiscoveredTv(host, info.port))
                  }
                },
              )
            }
          }

          override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

          override fun onDiscoveryStopped(serviceType: String) {}

          override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            finish(null)
          }

          override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

      cont.invokeOnCancellation { runCatching { nsdManager.stopServiceDiscovery(discoveryListener) } }
      runCatching { nsdManager.discoverServices(RemoteControlProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
        .onFailure { finish(null) }
    }
  }
