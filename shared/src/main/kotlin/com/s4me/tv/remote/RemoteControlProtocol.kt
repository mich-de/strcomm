package com.s4me.tv.remote

import kotlinx.serialization.Serializable

/** Shared contract between the TV app's local control server and the phone companion app's
 *  client. Both sides must agree on these without any negotiation step, so they live here instead
 *  of being hardcoded independently in each app. */
object RemoteControlProtocol {
  /** mDNS/NSD service type both sides register/discover under. */
  const val SERVICE_TYPE = "_strcomm._tcp."

  /** Tried first so a phone can pair by typing just an IP when NSD discovery fails (some
   *  routers/APs filter multicast) — the TV falls back to an OS-assigned port only if this one is
   *  somehow already taken, in which case manual pairing needs NSD anyway to learn the real port. */
  const val DEFAULT_PORT = 57813

  /** [Handshake.name] a genuine StrComm TV returns from GET /ping. The phone rejects a host that
   *  answers with anything else — a wrong app on that port, a captive portal, or just some other
   *  device that happens to live at a hand-typed IP. */
  const val APP_NAME = "StrComm"
}

/** GET /ping response. Lets the phone confirm the host it's about to talk to really is a StrComm
 *  TV — especially on the manual-IP path, where there's no NSD record vouching for it — instead of
 *  finding out only later when /search comes back as garbage. */
@Serializable
data class Handshake(val name: String)
