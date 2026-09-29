package com.s4me.tv.remote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.s4me.tv.remote.RemoteControlProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredTv(val name: String, val host: String, val port: Int)

/** Wraps NsdManager's discover-then-resolve dance behind a single Flow of "every StrComm TV seen
 *  right now" — each emission is the full current set (not a delta), since the UI only ever needs
 *  "what's on the list to tap." */
class TvDiscovery(private val context: Context) {
  private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }

  fun discover(): Flow<List<DiscoveredTv>> = callbackFlow {
    val found = LinkedHashMap<String, DiscoveredTv>()
    fun emitCurrent() {
      trySend(found.values.toList())
    }

    val discoveryListener =
      object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
          // A fresh listener per resolve call — some OEM NSD stacks reject reusing one listener
          // across concurrent resolves (e.g. two StrComm TVs found back-to-back).
          runCatching {
            nsdManager.resolveService(
              serviceInfo,
              object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}

                override fun onServiceResolved(info: NsdServiceInfo) {
                  val host = info.host?.hostAddress ?: return
                  found[info.serviceName] = DiscoveredTv(info.serviceName, host, info.port)
                  emitCurrent()
                }
              },
            )
          }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
          found.remove(serviceInfo.serviceName)
          emitCurrent()
        }

        override fun onDiscoveryStopped(serviceType: String) {}

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
          close()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
      }

    runCatching { nsdManager.discoverServices(RemoteControlProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
      .onFailure { close(it) }

    awaitClose { runCatching { nsdManager.stopServiceDiscovery(discoveryListener) } }
  }
}
