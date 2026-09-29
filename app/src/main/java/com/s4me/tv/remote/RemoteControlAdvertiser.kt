package com.s4me.tv.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

private const val TAG = "RemoteControlAdvertiser"

/** Advertises [RemoteControlServer] via NSD (mDNS) so the phone companion app can find this TV
 *  automatically on the same Wi-Fi, without the user typing an IP — the same idea as Chromecast
 *  discovery, just for our own app instead of an arbitrary receiver. */
class RemoteControlAdvertiser(private val context: Context) {
  private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
  private var registrationListener: NsdManager.RegistrationListener? = null

  fun start(port: Int) {
    if (registrationListener != null) return
    val serviceInfo =
      NsdServiceInfo().apply {
        // NSD renames this on conflict (e.g. a second box on the same network), so it's a display
        // hint for the phone's picker, not something either side can assume stays unique.
        serviceName = "StrComm-${Build.MODEL}".take(60)
        serviceType = RemoteControlProtocol.SERVICE_TYPE
        setPort(port)
      }
    val listener =
      object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
          Log.d(TAG, "Registered as ${info.serviceName} on port $port")
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
          Log.w(TAG, "NSD registration failed: $errorCode")
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {}

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
      }
    registrationListener = listener
    runCatching { nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
  }

  fun stop() {
    registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
    registrationListener = null
  }
}
