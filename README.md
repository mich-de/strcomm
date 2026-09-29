# StrComm

Three native Android apps sharing one content engine: an **Android TV** client (also installable
on **Fire TV Stick**), a **touch app** for phones and tablets with its own Netflix-style player,
and a **phone companion** that turns your phone into a second-screen remote for the TV app.

Built with Kotlin, Jetpack Compose (Compose for TV + plain Material 3), Media3/ExoPlayer and
Navigation 3.

> **Personal-use client.** StrComm hosts nothing. It reads a single third-party catalogue site and
> plays what that site exposes. You are responsible for how you use it under your local laws.

---

## Contents

- [Apps](#apps)
- [Features](#features)
  - [TV app](#tv-app)
  - [Touch app (phone & tablet)](#touch-app-phone--tablet)
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
  - [Fire TV Stick](#fire-tv-stick)
- [Content source & legal](#content-source--legal)

---

## Apps

| App | Module | Package | Runs on |
|-----|--------|---------|---------|
| **StrComm** (TV) | `:app` | `com.s4me.tv` | Android TV, boxes, and **Fire TV Stick** — API 23+ |
| **StrComm** (touch) | `:client` | `com.s4me.tv.client` | Android phones & tablets, portrait + landscape — API 26+ |
| **StrComm Remote** (phone) | `:mobile` | `com.s4me.tv.remote` | Android phones, as a second-screen remote for the TV app — API 26+ |

`:app` and `:client` are independent apps that share `:engine` (the content engine) and `:shared`
(wire types) — they don't depend on each other, and each has its own navigation and its own
player. `:mobile` depends only on `:shared`; it has no player of its own, it just drives the TV
app's local control server over Wi-Fi.

`:app` doubles as the Fire TV Stick build with no separate variant: Fire OS is AOSP-based, the
manifest already declares `LEANBACK_LAUNCHER` and marks touchscreen as not required, and the app
has zero Google Play Services dependency. The one real constraint is `minSdk` — see
[Fire TV Stick](#fire-tv-stick) below.

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

### Touch app (phone & tablet)

*StrComm* — a responsive Material 3 app for phones and tablets, portrait and landscape, with its
own player (it does not reuse the TV app's).

- **Responsive shell** — a bottom `NavigationBar` on phones, a side `NavigationRail` on tablets and
  landscape phones, switching at 600dp width. Tabs: Home / Cerca / Sfoglia / Impostazioni.
- **Home** — hero `HorizontalPager`, Film/Serie TV/Documentari type filter, genre chips, ~10
  progressively-appended genre rows, plus the same Netflix and JustWatch trending rows as the TV app.
- **Browse ("Sfoglia")** — same filterable catalog as the TV app (Tipo/Genere/Anno/Ordina, plus the
  curated Oscar filter).
- **Series screen** — Netflix-style: backdrop header, a season filter-chip row, an episode list.
- **Detail page** — enriched metadata renders immediately, before the playable sources resolve.
- **Search** — debounced type-ahead.
- **Continue Watching** — resume any in-progress movie or episode; tapping it re-resolves a fresh
  stream rather than reusing a stale playback URL.
- **Custom player** — tap to toggle a Netflix-style overlay (back + info block, centre transport,
  bottom control row + scrubber); double-tap the edges to seek ±10s. Controls include
  subtitle/audio track selection, playback speed (0.5–2×), fit/fill resize, a live stats panel
  (resolution, bitrate, codec, fps, buffer), and a screen lock. Forced subtitles are auto-selected
  when available. The player forces landscape and goes edge-to-edge, hiding the nav bar/rail and
  system bars.
- **Settings** — clears the on-device watch progress / watchlist / search-history stores.

### Phone companion

*StrComm Remote* — a lightweight Material 3 phone app whose only job is to remote-control the TV app.

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
:shared    pure-Kotlin JVM          wire types only — StreamItem / ItemKind / HomeSection, and
                                     the remote-control protocol (Handshake, PlaybackStatus,
                                     PlaybackCommand)

:engine    Android library          the content engine — Channel sources, HTTP/scrape helpers,
                                     IMDb/TMDB enrichment, on-device stores. No Compose, no media3.

:app       Android (TV)             StrComm TV — leanback, D-pad, + the local control server for
                                     the phone remote. Also the Fire TV Stick build (same APK).

:client    Android (phone/tablet)   StrComm — the touch streaming app, portrait + landscape, its
                                     own Netflix-style player.

:mobile    Android (phone)          StrComm Remote — the phone second-screen remote (discover TV,
                                     search, cast, transport). Depends on :shared only — no :engine.
```

Dependency graph:

```
:shared ── :engine ──┬── :app
   └─────────────────┼── :client
                     └── :mobile   (:mobile depends on :shared only — no :engine)
```

`:app` and `:client` are **independent apps that share `:engine`** — they do not depend on each
other, and each has its own `navKeyFor()`, its own `NavKey` types, its own player. Changing shared
behaviour means changing `:engine`; changing one app's UI never touches the other.

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
| `seasonOverview(item)` | — | Italian per-season synopsis |
| `findVideos(item)` | `findvideos()` | resolve a MOVIE/EPISODE to playable sources |
| `resolveEmbedUrl(item)` | — | the WebView fallback URL when native HLS playback fails |
| `search(query)` | `search()` | catalogue search |

`ItemKind` drives navigation and drilling: `CATEGORY` / `LIST` / `SERIES` / `SEASON` are
non-terminal (drill into their children with `list()`); `MOVIE` / `EPISODE` are terminal
(`findVideos()` resolves them to one or more `PLAYABLE` items — a final direct stream URL each).

The only implementation today is **`StreamingCommunityChannel`** — a Laravel + Inertia.js site whose
pages ship their data as JSON, so browsing needs no HTML scraping. Playback resolves straight to a
token-signed HLS `.m3u8` for native ExoPlayer, falling back to the embed page in a WebView.
The site rotates its domain periodically; every Inertia response carries the site's own
self-reported `app_url` / `cdn_url` next to a `version` field, and the channel reads them back to
re-point itself on every call — so a domain change needs no code change as long as the old domain
redirects.

`ChannelRegistry` is the single access point (`ChannelRegistry.all`, `ChannelRegistry.byId(id)`).

### Navigation

Both apps use Navigation 3 with a `rememberNavBackStack`, but each owns its own decision:

- **`:app`** — `navKeyFor(item)` is the one place that decides where a `StreamItem` goes:
  `CATEGORY / LIST / SERIES / SEASON` → `Browse`, `MOVIE / EPISODE` → `Detail`, `PLAYABLE` → `Player`.
  `MainNavigation` also collects two flows from the remote-control bridges: an incoming "play this"
  pick from the phone (routed through the same `navKeyFor`), and a remote **STOP** that pops the player.
- **`:client`** — its own `navKeyFor()` and `NavKey` types drive the same kind of routing inside one
  `rememberNavBackStack` shared with the bottom-nav/rail tab state; the player screen forces
  landscape and full-bleeds itself (hides the nav chrome) whenever it's on top of the back stack.

### Second-screen remote-control protocol

Trust model = Chromecast/DIAL: no auth beyond "same Wi-Fi". The TV app (`:app`) runs a
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
Compose (`MainNavigation` / `PlayerScreen`) inside `:app`.

### Ratings & trailers

The source site's own `score` is a stale, second-hand TMDB import. StrComm replaces it on the detail
page:

- **IMDb rating** — scraped from the public `imdb.com/title/{id}` JSON-LD (`AggregateRating`). No key.
- **TMDB rating + Italian synopsis** — for titles the source site left in English (e.g. *House of the Dragon*):
  - **keyless (default):** scraped from `themoviedb.org/{tv|movie}/{id}?language=it-IT` — `og:description` for the plot, the user-score ring for the number.
  - **with an API key:** the TMDB API instead — adds vote counts and the *official* trailer.
- **Per-season data** — Italian per-season synopses, and real per-season release years (API, or one
  scrape of the server-rendered seasons page) instead of every season inheriting the series' last-air year.
- **Trailer** — TMDB's official trailer when an API key is set; otherwise the source site's own trailer list. `youtubeVideoId()` normalises whatever form the id comes in; the button targets the device's YouTube app directly (SmartTube-aware) instead of the `vnd.youtube:` scheme.

Every external lookup is cached in memory for the session.

---

## Tech stack

- **Kotlin** 2.3.x, **Jetpack Compose** — Compose for TV (`androidx.tv.material3`) in `:app`, plain Material 3 in `:client`/`:mobile`
- **Navigation 3** (`androidx.navigation3`)
- **Media3 / ExoPlayer** 1.11 — HLS playback; `:client` inflates a `PlayerView` with a `TextureView`
  surface so the resize modes can actually crop the video, not just letterbox it
- **Coil 3** — image loading
- **OkHttp 5** + **Jsoup** — networking & HTML parsing
- **kotlinx.serialization** — JSON (wire format + on-device stores)
- **kotlinx.coroutines** — everything async
- **NanoHTTPD** — the TV app's local control server
- **Gradle** 9.1 with the version catalog in `gradle/libs.versions.toml`; **JDK 17** toolchain

---

## Project layout

```
.
├── app/                     :app    — StrComm TV (+ Fire TV Stick)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/s4me/tv/
│       │   ├── MainActivity.kt          launches UI + starts the control server
│       │   ├── Navigation.kt            NavDisplay + bridge collectors
│       │   ├── NavigationKeys.kt        NavKey types + navKeyFor()
│       │   ├── remote/
│       │   │   ├── RemoteControlServer.kt      NanoHTTPD API
│       │   │   ├── RemoteControlAdvertiser.kt  NSD/mDNS
│       │   │   ├── RemoteControlBridge.kt      "play this" → navigation
│       │   │   └── PlaybackControlBridge.kt    player ⇄ /now-playing, /control
│       │   ├── ui/{home,browse,detail,player,search,settings,components}/
│       │   └── theme/
│       └── res/
├── client/                  :client — StrComm (touch, phone + tablet)
│   └── src/main/java/com/s4me/tv/client/
│       ├── MainActivity.kt
│       ├── ClientApp.kt                 responsive shell (NavigationBar/Rail, tabs, fullBleed)
│       ├── nav/Nav.kt                   NavKey types + navKeyFor()
│       ├── ui/{home,browse,detail,player,search,settings,components}/
│       └── theme/
├── mobile/                  :mobile — StrComm Remote
│   └── src/main/java/com/s4me/tv/remote/
│       ├── MainActivity.kt
│       ├── discovery/TvDiscovery.kt
│       ├── net/TvClient.kt
│       ├── data/TvPreferences.kt
│       ├── ui/{RemoteApp,RemoteViewModel}.kt
│       └── theme/
├── engine/                  :engine — the content engine (Android library, no Compose/media3)
│   └── src/main/java/com/s4me/tv/engine/
│       ├── Channel.kt  ChannelRegistry.kt
│       ├── channels/StreamingCommunityChannel.kt
│       ├── Net.kt  Scrape.kt            HTTP + regex helpers
│       ├── Imdb.kt  Tmdb.kt             rating / metadata scrapers (+ optional TMDB API)
│       ├── NetflixTop10.kt  JustWatchTop10.kt
│       └── WatchProgressStore.kt  WatchlistStore.kt  SearchHistoryStore.kt
├── shared/                  :shared — data model + wire contract
│   └── src/main/kotlin/com/s4me/tv/
│       ├── engine/Models.kt
│       └── remote/{RemoteControlProtocol,PlaybackControl}.kt
├── gradle/libs.versions.toml
├── settings.gradle.kts      includes :app, :shared, :engine, :client, :mobile
└── README.md
```

---

## Requirements

- **JDK 17** and the **Android SDK** (compile SDK 36, build-tools to match).
- `:app` — an Android TV device/box, a **Fire TV Stick**, or an emulator, API 23+.
- `:client` — an Android phone or tablet, API 26+.
- `:mobile` — an Android phone, API 26+.
- `local.properties` with `sdk.dir=/path/to/Android/Sdk` (Android Studio writes this for you).

## Building

```bash
# fast compile check after edits (do this before assembling)
./gradlew :shared:compileKotlin :engine:compileDebugKotlin \
          :app:compileDebugKotlin :client:compileDebugKotlin :mobile:compileDebugKotlin

# debug APKs
./gradlew :app:assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :client:assembleDebug   # -> client/build/outputs/apk/debug/client-debug.apk
./gradlew :mobile:assembleDebug   # -> mobile/build/outputs/apk/debug/mobile-debug.apk

# release APKs — signed iff keystore.properties exists at the repo root, else unsigned
./gradlew :app:assembleRelease :client:assembleRelease :mobile:assembleRelease
```

### Optional: TMDB API key

Everything works with **no key** (ratings via the IMDb + TMDB *web* scrapers). If you want TMDB vote
counts and official trailers, add a free [TMDB API](https://www.themoviedb.org/settings/api) key to
`local.properties` (gitignored):

```properties
tmdb.apiKey=YOUR_KEY
```

or set the `TMDB_API_KEY` environment variable. It's surfaced to `:engine` as
`BuildConfig.TMDB_API_KEY`; blank = scrape path.

### Release signing

`assembleRelease` looks for a gitignored `keystore.properties` at the repo root:

```properties
storeFile=path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it, `assembleRelease` still succeeds and produces an **unsigned** APK. All three apps reuse
the same keystore.

## Installing & running

```bash
# TV box over the network (enable ADB / Wireless debugging on the box first)
adb connect <tv-ip>:5555
adb -s <tv-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# phone/tablet touch app, over USB
adb install -r client/build/outputs/apk/debug/client-debug.apk

# phone companion, over USB
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

Or grab a prebuilt APK from the [Releases](../../releases) page.

The phone companion needs both devices on the **same Wi-Fi**. If mDNS discovery fails (some
routers block multicast), open **Settings** on the TV to read its IP and type it into the phone.

### Fire TV Stick

The TV app installs on Fire TV Stick as-is — no separate build, same APK as the Android TV box.

1. On the Fire TV Stick: **Settings → My Fire TV → Developer Options** → turn on **ADB debugging**
   and **Apps from Unknown Sources**.
2. Find its IP under **Settings → My Fire TV → About → Network**.
3. From a machine on the same Wi-Fi:
   ```bash
   adb connect <firestick-ip>:5555
   adb -s <firestick-ip>:5555 install -r app-release.apk
   ```

`minSdk` for `:app` is 23 (Android 6.0) specifically so this works — Fire OS 6, which many Fire TV
Stick units still run, is API 25. Only the original 2014/2016 non-4K Fire TV Stick (Fire OS 5,
API 22) falls outside that floor.

## Content source & legal

StrComm streams from a single third-party site and hosts nothing itself. It is a personal-use
client, not a content platform. Respect the content owners and your local laws.
