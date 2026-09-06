plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

// Pure Kotlin, no Android dependency — StreamItem/ItemKind are the wire format shared between the
// TV app (server) and the phone companion app (client) over the local /search and /play endpoints,
// so both sides must compile against the exact same class rather than two hand-kept-in-sync copies.
kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
}
