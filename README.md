# StrComm

A native **Android TV** client for browsing and streaming movies and TV series, plus a **phone
companion app** that acts as a second-screen remote — search on your phone, play on the TV.

Built with Kotlin, Jetpack Compose (Compose for TV), Media3/ExoPlayer and Navigation 3.

> **Personal-use client.** StrComm hosts nothing. It reads a single third-party catalogue site and
> plays what that site exposes. You are responsible for how you use it under your local laws.

---

## Contents

- [Apps](#apps)
- [Features](#features)
  - [TV app](#tv-app)
  - [Phone companion](#phone-companion)
- [Architecture](#architecture)
  - [Modules](#modules)
  - [The channel engine](#the-channel-engine)
  - [Navigation](#navigation)
  - [Second-screen remote-control protocol](#second-screen-remote-control-protocol)
  - [Ratings & trailers](#ratings--trailers)
- [Tech stack](#tech-stack)
- [Project layout](#project-layout)
- [Requirements](#requirements)
- [Building](#building)
  - [Optional: TMDB API key](#optional-tmdb-api-key)
  - [Release signing](#release-signing)
- [Installing & running](#installing--running)
- [Development notes](#development-notes)
- [Content source & legal](#content-source--legal)

---

## Apps

| App | Module | Package | Runs on |
|-----|--------|---------|---------|
| **StrComm** (TV) | `:app` | `com.s4me.tv` | Android TV / boxes, API 26+ |
| **StrComm Remote** (phone) | `:mobile` | `com.s4me.tv.remote` | Android phones, API 26+ |

Both depend on `:shared`, a pure-Kotlin module holding the data model and the remote-control wire
contract so the two sides can never drift.

---

## Features

### TV app

- **Home**
  - Rotating hero billboard (auto-advance + D-pad LEFT/RIGHT), with **▶ Guarda** and **＋ La mia lista**.
  - **☰ Sfoglia** and **🔍 Cerca** in a floating top bar.
  - **Generi** — a row of genre chips that deep-link straight into a filtered Browse.
  - **Continua a guardare** — resume the last 10 titles, each card with a progress bar; long-press OK to remove.
  - **La mia lista** — your watchlist; long-press OK to remove.
  - **I titoli del momento** — Netflix's public Italy Top 10, used purely as a ranking signal (every title still resolves and plays through the app's own source).
  - **Popolari su tutte le piattaforme** — JustWatch's cross-platform Streaming Charts, same idea.
  - The source site's own rows (Novità, Top 10, "ordine di uscita", …).
- **Browse ("Sfoglia")** — filter by type / genre / year / sort (popularity, release date, score), plus a curated **Academy Award Best Picture** filter (winners, or winners + nominees) resolved through the site's search.
- **Search** — **search-as-you-type** (debounced) *and* the keyboard's search action; recent-search history; client-side re-sort by relevance / score / year.
  - Tapping a **cast or director** name runs a credit-verified search — it re-checks each candidate's real credits, not just the site's fuzzy text match.
- **Detail page** — Italian synopsis, genres, runtime, **IMDb / TMDB rating**, focusable cast & director chips, a **▶ Trailer** button (opens the YouTube app the device has), a **▶ Guarda / ▶ Riprendi · Ricomincia** action, and a saga/collection row.
- **Playback** (Media3/ExoPlayer, HLS)
  - D-pad-first overlay: play/pause, ±10 s seek, info overlay, audio/subtitle track picker.
  - **Auto-next-episode** — plays the next episode of a series with no navigation round-trip.
  - **WebView fallback** — if native HLS fails at runtime, falls back to the source's embed page.
  - Keeps the screen awake for the whole session (no more standby mid-movie).
- **Personal data, on-device only** — watch progress, watchlist, search history (SharedPreferences + kotlinx.serialization), each clearable from **Settings**.
- **Second-screen server** — advertises itself over mDNS and serves a small local HTTP API for the phone companion (see [below](#second-screen-remote-control-protocol)).
- Built for the remote: D-pad navigation throughout, focus-driven UI, no touch assumptions.

### Phone companion

*StrComm Remote* — a lightweight Material 3 phone app.

- **Discovery** — finds StrComm TVs on the same Wi-Fi over mDNS/NSD; or connect by typing the IP shown in the TV's Settings.
- **Validated connect** — pings the TV before switching to it, so a wrong IP fails clearly instead of silently.
- **Remembers the last TV** — reconnects automatically on next launch; **Cambia** forgets it.
- **Connection-loss detection** — drops back to discovery with a message if the TV disappears.
- **Search** — search the TV's catalogue from the phone; send a movie to play with one tap, or drill **Series → Seasons → Episodes** and send an episode.
- **Now-playing bar** — a persistent mini-player: title, scrubber, ⏪10s / ⏯ / 10s⏩ / ⏹, mirroring the TV's own transport controls.

---

## Architecture

### Modules

```
:shared   pure-Kotlin JVM   data model + remote-control wire contract
  └─ engine/Models.kt              StreamItem, ItemKind, HomeSection, ListResult
  └─ remote/RemoteControlProtocol  mDNS service type, default port, Handshake
  └─ remote/PlaybackControl        PlaybackStatus, PlaybackCommand

:app      Android (TV)      the StrComm TV app
  └─ engine/       content sources + networking + on-device stores
  └─ remote/       the local HTTP control server + advertiser + bridges
  └─ ui/           home / browse / detail / player / search / settings screens
  └─ MainActivity, Navigation, NavigationKeys

:mobile   Android (phone)   the StrComm Remote companion
  └─ discovery/    mDNS discovery → Flow<List<DiscoveredTv>>
  └─ net/          TvClient — HTTP client for the TV's control API
  └─ data/         TvPreferences — last-connected TV
  └─ ui/           RemoteApp + RemoteViewModel
```

### The channel engine

Content comes through the **`Channel`** interface (`engine/Channel.kt`) — a port of the Kodi-addon
`channels/NAME.py` contract:

| `Channel` method | Kodi analogue | Purpose |
|---|---|---|
| `home()` | `mainlist()` | the named rows for Home |
| `catalogRoot()` | — | the filterable "Sfoglia" entry point |
| `list(item, page)` | `peliculas()` / `series()` / `episodios()` | children of any non-terminal item (CATEGORY / LIST / SERIES / SEASON) |
| `genres()` | — | filter options |
| `detail(item, withRatings)` | — | enrich a MOVIE/SERIES with plot, cast, director, runtime, ratings, trailer |
| `findVideos(item)` | `findvideos()` | resolve a MOVIE/EPISODE to playable sources |
| `search(query)` | `search()` | catalogue search |

The only implementation today is **`StreamingCommunityChannel`** — a Laravel + Inertia.js site whose
pages ship their data as JSON, so browsing needs no HTML scraping. Playback resolves straight to a
token-signed HLS `.m3u8` for native ExoPlayer, falling back to the embed page in a WebView.
The site rotates its domain periodically; the channel reads the site's own self-reported
`app_url` / `cdn_url` from every response and re-points itself, so a TLD change needs no code
change as long as the old domain 301-redirects.

`ChannelRegistry` is the single access point (`ChannelRegistry.all`, `ChannelRegistry.byId(id)`).

### Navigation

Navigation 3 with a `rememberNavBackStack`. `navKeyFor(item)` is the **one** place that decides
where a `StreamItem` goes based on its `ItemKind`:

- `CATEGORY / LIST / SERIES / SEASON` → `Browse`
- `MOVIE / EPISODE` → `Detail`
- `PLAYABLE` → `Player`

`MainNavigation` also collects two flows from the remote-control bridges: an incoming "play this"
pick from the phone (routed through the same `navKeyFor`), and a remote **STOP** that pops the player.

### Second-screen remote-control protocol

Trust model = Chromecast/DIAL: no auth beyond "same Wi-Fi". The TV runs a
[NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) server (`RemoteControlServer`) and advertises it
over NSD/mDNS (`RemoteControlAdvertiser`).

- **Service type** `_strcomm._tcp.` — **default port** `57813` (falls back to an OS-assigned port if taken).

| Endpoint | Method | Purpose |
|---|---|---|
| `/ping` | GET | `{"name":"StrComm"}` handshake — the phone rejects anything else |
| `/search?q=` | GET | catalogue search → `StreamItem[]` |
| `/list` | POST `StreamItem` | that item's children (seasons / episodes) → `StreamItem[]` |
| `/play` | POST `StreamItem` | resolve to a source and start playback on the TV |
| `/now-playing` | GET | `PlaybackStatus` or `null` |
| `/control` | POST `PLAY\|PAUSE\|TOGGLE\|SEEK_BACK\|SEEK_FORWARD\|STOP` | transport control (native player only) |

`RemoteControlBridge` and `PlaybackControlBridge` carry data between the background HTTP thread and
Compose (`MainNavigation` / `PlayerScreen`).

### Ratings & trailers

The source site's own `score` is a stale, second-hand TMDB import. StrComm replaces it on the detail
page:

- **IMDb rating** — scraped from the public `imdb.com/title/{id}` JSON-LD (`AggregateRating`). No key.
- **TMDB rating + Italian synopsis** — for titles the source site left in English (e.g. *House of the Dragon*):
  - **keyless (default):** scraped from `themoviedb.org/{tv|movie}/{id}?language=it-IT` — `og:description` for the plot, the user-score ring for the number.
  - **with an API key:** the TMDB API instead — adds vote counts and the *official* trailer.
- **Trailer** — TMDB's official trailer when an API key is set; otherwise the source site's own trailer list. `youtubeVideoId()` normalises whatever form the id comes in; the button targets the device's YouTube app directly (SmartTube-aware) instead of the `vnd.youtube:` scheme.

Every external lookup is cached in memory for the session.

---

## Tech stack

- **Kotlin** 2.3.x, **Jetpack Compose** + **Compose for TV** (`androidx.tv.material3`)
- **Navigation 3** (`androidx.navigation3`)
- **Media3 / ExoPlayer** 1.11 — HLS playback
- **Coil 3** — image loading
- **OkHttp 5** + **Jsoup** — networking & HTML parsing
- **kotlinx.serialization** — JSON (wire format + on-device stores)
- **kotlinx.coroutines** — everything async
- **NanoHTTPD** — the TV's local control server
- **Gradle** 9.1 with the version catalog in `gradle/libs.versions.toml`; **JDK 17** toolchain

---

## Project layout

```
.
├── app/                     :app   — StrComm TV
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/s4me/tv/
│       │   ├── MainActivity.kt          launches UI + starts the control server
│       │   ├── Navigation.kt            NavDisplay + bridge collectors
│       │   ├── NavigationKeys.kt        NavKey types + navKeyFor()
│       │   ├── engine/
│       │   │   ├── Channel.kt           the content-source contract
│       │   │   ├── ChannelRegistry.kt
│       │   │   ├── channels/StreamingCommunityChannel.kt
│       │   │   ├── Net.kt  Scrape.kt    HTTP + regex helpers
│       │   │   ├── Imdb.kt  Tmdb.kt     keyless rating / metadata scrapers
│       │   │   ├── NetflixTop10.kt  JustWatchTop10.kt
│       │   │   └── WatchProgressStore.kt  WatchlistStore.kt
│       │   ├── remote/
│       │   │   ├── RemoteControlServer.kt      NanoHTTPD API
│       │   │   ├── RemoteControlAdvertiser.kt  NSD/mDNS
│       │   │   ├── RemoteControlBridge.kt      "play this" → navigation
│       │   │   └── PlaybackControlBridge.kt    player ⇄ /now-playing, /control
│       │   ├── ui/{home,browse,detail,player,search,settings,components}/
│       │   └── theme/
│       └── res/
├── mobile/                  :mobile — StrComm Remote
│   └── src/main/java/com/s4me/tv/remote/
│       ├── MainActivity.kt
│       ├── discovery/TvDiscovery.kt
│       ├── net/TvClient.kt
│       ├── data/TvPreferences.kt
│       ├── ui/{RemoteApp,RemoteViewModel}.kt
│       └── theme/Theme.kt
├── shared/                  :shared — data model + wire contract
│   └── src/main/kotlin/com/s4me/tv/
│       ├── engine/Models.kt
│       └── remote/{RemoteControlProtocol,PlaybackControl}.kt
├── gradle/libs.versions.toml
├── settings.gradle.kts      includes :app, :shared, :mobile
├── CLAUDE.md                notes for working on this codebase
└── README.md
```

---

## Requirements

- **JDK 17** and the **Android SDK** (compile SDK 36, build-tools to match).
- An Android TV device / box / emulator (API 26+) for `:app`; an Android phone (API 26+) for `:mobile`.
- `local.properties` with `sdk.dir=/path/to/Android/Sdk` (Android Studio writes this for you).

## Building

```bash
# TV app
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # signed only if keystore.properties exists (see below)

# Phone companion
./gradlew :mobile:assembleDebug       # → mobile/build/outputs/apk/debug/mobile-debug.apk

# Fast checks
./gradlew :shared:compileKotlin :app:compileDebugKotlin :mobile:compileDebugKotlin
```

### Optional: TMDB API key

Everything works with **no key** (ratings via the IMDb + TMDB *web* scrapers). If you want TMDB vote
counts and official trailers, add a free [TMDB API](https://www.themoviedb.org/settings/api) key to
`local.properties` (gitignored):

```properties
tmdb.apiKey=YOUR_KEY
```

or set the `TMDB_API_KEY` environment variable. It's surfaced to the app as
`BuildConfig.TMDB_API_KEY`; blank = scrape path.

### Release signing

`assembleRelease` looks for a gitignored `keystore.properties` at the repo root:

```properties
storeFile=path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it, `assembleRelease` still succeeds and produces an **unsigned** APK.

## Installing & running

```bash
# TV box over the network (enable ADB / Wireless debugging on the box first)
adb connect <tv-ip>:5555
adb -s <tv-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# phone over USB
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

Or grab a prebuilt APK from the [Releases](../../releases) page.

The phone companion needs both devices on the **same Wi-Fi**. If mDNS discovery fails (some
routers block multicast), open **Settings** on the TV to read its IP and type it into the phone.

## Development notes

See **[CLAUDE.md](CLAUDE.md)** for the architecture map, conventions, on-device testing setup,
known gotchas (TV focus management, the auto-next-episode player rebuild, the self-healing source
domain), and what's still in flight.

## Content source & legal

StrComm streams from a single third-party site and hosts nothing itself. It is a personal-use
client, not a content platform. Respect the content owners and your local laws.
