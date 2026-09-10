# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**StrComm** — a personal-use client for a single third-party catalogue site. Three apps, one
content engine. Five Gradle modules:

| Module | Type | Package | What |
|---|---|---|---|
| `:shared` | kotlin-jvm | `com.s4me.tv.*` | wire types only — `StreamItem` / `ItemKind` / `HomeSection`, and the remote-control protocol (`Handshake`, `PlaybackStatus`, `PlaybackCommand`) |
| `:engine` | android-library | `com.s4me.tv.engine` | **the content engine** — `Channel` sources, HTTP/scrape helpers, IMDb/TMDB enrichment, on-device stores. No Compose, no media3. |
| `:app` | android-application | `com.s4me.tv` | the Android **TV** app (leanback, D-pad) + the local control server for the phone remote |
| `:mobile` | android-application | `com.s4me.tv.remote` | **StrComm Remote** — the phone **second-screen remote** (discover TV, search, cast, transport) |
| `:client` | android-application | `com.s4me.tv.client` | **the touch streaming app** for phones + tablets, portrait + landscape — a full client with its own Netflix-style player |

Dependency graph:

```
:shared ── :engine ──┬── :app
   └─────────────────┼── :client
                     └── :mobile   (:mobile depends on :shared only — no :engine)
```

`:app` and `:client` are **independent apps that share `:engine`** — they do not depend on each
other, and each has its own `navKeyFor()`, its own `NavKey` types, its own player. Changing shared
behaviour means changing `:engine`; changing one app's UI never touches the other.

> `:app`'s server code lives at `app/src/main/java/com/s4me/tv/remote/` — same package name
> (`com.s4me.tv.remote`) as the whole `:mobile` module. They are unrelated; don't conflate them.

The source site ships its browse data as **JSON**, so no HTML scraping is needed for browsing —
only rating/metadata enrichment scrapes (IMDb, TMDB). See `README.md` for the user-facing overview.

## Build & check

```bash
# fast compile check after edits (do this before assembling)
./gradlew :shared:compileKotlin :engine:compileDebugKotlin \
          :app:compileDebugKotlin :client:compileDebugKotlin :mobile:compileDebugKotlin

# debug APKs
./gradlew :app:assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :client:assembleDebug   # -> client/build/outputs/apk/debug/client-debug.apk
./gradlew :mobile:assembleDebug   # -> mobile/build/outputs/apk/debug/mobile-debug.apk

# Android lint (the only real static check configured)
./gradlew :app:lintDebug :client:lintDebug

# release APKs — signed iff keystore.properties exists at the repo root, else unsigned
./gradlew :app:assembleRelease :client:assembleRelease :mobile:assembleRelease
```

- **JDK 17** toolchain (`jvmToolchain(17)`), Gradle 9.1, AGP 9.0.x, Kotlin 2.3.x, compileSdk 36,
  minSdk 26. AGP 9 auto-applies the Kotlin Android plugin — modules only declare
  `android.application` / `android.library` + `compose.compiler` + `kotlin.serialization`. All
  versions in `gradle/libs.versions.toml`.
- **No tests.** `app/src/test` + `app/src/androidTest` are empty; there is no `:client` / `:engine`
  test source set. `./gradlew test` is a no-op; `./gradlew check` effectively just runs lint.
- No ktfmt/spotless/detekt task — the code follows ktfmt *style* by convention only (see below).
- `buildConfig = true` is set on **`:engine` only**, to expose `BuildConfig.TMDB_API_KEY` (read
  from `tmdb.apiKey` in `local.properties` or the `TMDB_API_KEY` env var; blank = keyless scrape
  path, which is the normal state — `local.properties` here has only `sdk.dir`). `:app` and
  `:client` have `buildConfig = false`; if one needs the key, route it through an `:engine` API.
- Release signing: gitignored `keystore.properties` at the repo root points at
  `keystore/strcomm-release.jks` (also gitignored, present locally). All three apps reuse it. A
  clone without it still builds — `assembleRelease` just comes out unsigned (AGP names those
  `*-release-unsigned.apk`).
- `*.apk`, `*.jks`, `*.keystore`, `keystore.properties`, `local.properties` are gitignored and
  must never be committed. APKs staged at the repo root are for manual GitHub Release upload only.

## On-device testing (no emulator needed)

Recent sessions had a real Android TV box **and** a USB-tethered tablet on ADB — ask the user for
the box IP:

```bash
adb connect <box-ip>:5555            # e.g. 192.168.188.128:5555 — a Strong 4K STB:
                                     #   SmartTube installed, NO official YouTube app
adb -s <box-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk       # TV app
adb -s <tablet-serial> install -r client/build/outputs/apk/debug/client-debug.apk  # touch app
adb -s <serial> shell monkey -p com.s4me.tv -c android.intent.category.LAUNCHER 1
adb -s <serial> exec-out screencap -p > shot.png
```

The box is **slow** to load Home (network fan-out) and scripted D-pad nav into it is flaky — wait
15 s+ before screenshotting, or ask the user to drive. Wireless ADB drops often (`adb connect`
again); devices sleep and screenshots come back black on the lock screen. Installing a *release*
build over a *debug* one fails on signature mismatch (uninstall first, losing on-device data).

**Probe the TV app's data without its UI** through its own control server (same LAN):

```bash
curl "http://<box-ip>:57813/search?q=..."      # -> StreamItem[] JSON
```

## Architecture

### The content engine — `:engine`

`Channel` (`engine/Channel.kt`) is a port of the Kodi-addon `channels/NAME.py` contract:
`home()`, `catalogRoot()`, `list(item, page)`, `genres()`, `detail(item, withRatings)`,
`seasonOverview(item)`, `findVideos()`, `resolveEmbedUrl()`, `search()`.

**`ItemKind` drives navigation and drilling.** `CATEGORY / LIST / SERIES / SEASON` are
non-terminal — you drill into their children with `list()`. `MOVIE / EPISODE` are terminal —
`findVideos()` resolves them to one or more `PLAYABLE` items (a final direct stream URL each).

Single implementation: **`StreamingCommunityChannel`** (`engine/channels/`) — a Laravel + Inertia.js
site whose pages ship their data as JSON, so browsing needs no HTML scraping. Playback resolves to
a token-signed HLS `.m3u8` for native ExoPlayer, falling back to the embed page in a WebView.

- **The site rotates its domain.** Every Inertia response's `props` carries `app_url` / `cdn_url`
  next to `version`; `absorbSelfUrls()` reads them back and re-points `host` / `cdn` on every call.
  **Never hardcode a domain** — the `@Volatile private var host` seed is just a cold-start guess.
- The filterable catalog root is `catalogRoot()` (`kind = LIST`). Its filter travels as a
  **query-string-shaped hint in `StreamItem.extra`** — `"type=movie&genre=4&year=2024&sort=release"`
  — which `listArchive()` parses into real `/it/archive?…` params. Both apps' Browse filter UIs and
  Home genre deep-links build this string; `BrowseFilter` (in each app) is its typed form.
- `listSeasons()` stamps `tmdbId` and a real per-season `year` onto each SEASON item so the season
  screen needs no extra fetch (see the ratings section).

`ChannelRegistry.all` / `ChannelRegistry.byId(id)` is the only access point.
`NetflixTop10` / `JustWatchTop10` are **ranking signals only** — they yield trending title *names*
that still resolve and play through `StreamingCommunityChannel.search()`.

On-device stores (`engine/`, all `SharedPreferences` + kotlinx.serialization JSON, no DB):
`WatchProgressStore`, `WatchlistStore`, `SearchHistoryStore`. Both apps read/write the same files.

### Ratings & metadata (`engine/Imdb.kt`, `engine/Tmdb.kt`)

The source site's own `score` is a stale second-hand TMDB import. `Channel.detail()` replaces it:

- `Imdb.rating(imdbId)` — scrapes `imdb.com/title/{id}` JSON-LD `AggregateRating`. Keyless.
- `Tmdb.lookup(tmdbId, isSeries)` — **keyless by default**: scrapes
  `themoviedb.org/{tv|movie}/{id}?language=it-IT` (`og:description` = Italian synopsis,
  `data-percent` = score). With `BuildConfig.TMDB_API_KEY` set it uses the TMDB **API** instead and
  additionally gets vote counts + the official trailer.
- `Tmdb.seasonOverviewIt(tmdbId, n)` — Italian per-season synopsis (`Channel.seasonOverview`).
- `Tmdb.seasonYears(tmdbId)` — real per-season release years (API `/tv/{id}`, or one scrape of the
  server-rendered `/tv/{id}/seasons` page via `SEASON_ROW`). Without this every season inherited the
  series' last-air year ("tutte 2026").
- All merged in `StreamingCommunityChannel.detail()`; TMDB Italian text wins over the site's.
- `detail(item, withRatings = false)` skips the rating/trailer lookups — used by both apps'
  `SearchViewModel.verifyCredited`, which fans `detail()` out over ~45 candidates per name tap to
  check real cast/director credits (the site's search is fuzzy text, not a credit lookup).
- `youtubeVideoId(raw)` (top-level in `Tmdb.kt`) normalises any id/URL form to the 11-char id.
- Every external lookup is cached in-process (`ConcurrentHashMap`) for the session.

### `:app` — the TV app

- **Navigation** (`Navigation.kt`, `NavigationKeys.kt`): Navigation 3 + `rememberNavBackStack`.
  `navKeyFor(item)` is the **single kind→screen decision**. `MainNavigation` also collects
  `RemoteControlBridge.incoming` (a phone pick → `navKeyFor`) and `PlaybackControlBridge.commands`
  (a remote STOP → pop the player).
- **Home load** (`ui/home/HomeViewModel.kt`): fans out `channel.home()`, the two trending charts,
  personal rows and hero enrichment concurrently. `progress: StateFlow<List<String>>` is appended
  via `log()` from those coroutines; `BrandedLoading(log = …)` renders its tail as a terminal-style
  panel on the splash — call `log()` when adding a load step.
- **Second-screen server** (`app/remote/`): trust model = Chromecast/DIAL (same-Wi-Fi, no auth).
  `RemoteControlServer` (NanoHTTPD, default port `57813`) advertised over NSD/mDNS
  (`RemoteControlAdvertiser`, service `_strcomm._tcp.`). Endpoints:
  `/ping /search /list /play /now-playing /control`. `RemoteControlBridge` (pick → navigation) and
  `PlaybackControlBridge` (`PlayerScreen` ⇄ server) carry data between the background HTTP thread
  and Compose. Started from `MainActivity`.
- **`BrowseScreen` triple-role**: a series has no dedicated detail screen, so `BrowseScreen`'s root
  can be a `LIST` (filterable catalog + `FilterBar`, incl. curated Academy Award lists resolved
  through search), a `SERIES` (seasons grid) or a `SEASON` (episode list). `BrowseViewModel` seeds
  its filter from `root.extra`.

### `:mobile` — the phone remote

`TvDiscovery` (NSD → `Flow<List<DiscoveredTv>>`) · `TvClient` (OkHttp against the server above) ·
`TvPreferences` (last TV) · `RemoteViewModel` + `RemoteApp`. Connect flow pings `/ping` and only
switches once it answers as a real StrComm TV; remembers the last TV and auto-reconnects on launch;
a run of failed pings drops back to discovery.

### `:client` — the touch app

- **Responsive shell** (`ClientApp.kt`): one `rememberNavBackStack` + a `selectedTab` int.
  `isWideScreen()` = `screenWidthDp >= 600` → `NavigationRail` (tablet / phone-landscape);
  narrower → bottom `NavigationBar`. `entryProvider {}` wires the tabs (Home / Cerca / Sfoglia /
  Impostazioni) and the pushed screens (Browse / Detail / Player).
- **`fullBleed`** = `backStack.lastOrNull() is Player` — hides the nav bar/rail and drops the
  Scaffold insets so the player is edge-to-edge. `PlayerScreen` *also* forces landscape
  (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`) and hides the system bars itself.
- **Back handling**: `NavDisplay` only handles back when `backStack.size > 1`. A separate
  `BackHandler` covers tab roots (non-Home tab → Home; Home → `activity.finish()`).
- **Screens** mirror the TV app through the same `:engine`: `HomeScreen` (hero `HorizontalPager`,
  Film/Serie TV/Documentari `TypeFilterRow`, genre chips, ~10 progressively-appended genre rows,
  Netflix + JustWatch trending), `BrowseScreen` (same triple-role as `:app`; `FilterBar` with
  Tipo/Genere/Anno/Ordina/**Premi→Oscar**), `SeriesScreen` (Netflix-style — backdrop header, season
  `FilterChip` row, `EpisodeRow` list; backed by `SeriesViewModel`), `DetailScreen` (enriched via
  `DetailViewModel` — `_preview` renders metadata before `findVideos()` sources resolve),
  `SearchScreen` (debounced type-ahead), `SettingsScreen` (clears the three stores).
- **Continue Watching**: `HomeViewModel.personalSections()` normalises any legacy `PLAYABLE` entry
  to a navigable `MOVIE`/`EPISODE` so a tap opens Detail and re-resolves a fresh stream.
  `PlayerScreen` saves progress against `playbackOrigin(item, originId)` — a rebuilt navigable item
  keyed on `StreamItem.originId` (the stable content id), **not** the `PLAYABLE`'s own url (a
  short-lived HLS token, useless later). Same pattern exists in `:app`'s player.
- **Custom player** (`ui/player/PlayerScreen.kt`, ~560 lines): tap toggles a Netflix-style overlay
  (back + content `InfoBlock`, centre transport, bottom control row + scrubber); double-tap edges
  seek ±10 s. Control row: **Sottotitoli / Audio** (`ModalBottomSheet` → `TrackSheet`, audio +
  always-shown subtitle section), **Velocità** (0.5–2×), **Adatta / Riempi** (see gotcha),
  **Info** (`StatsPanel` — resolution · bitrate · codec · fps · buffer, polled every 500 ms),
  **Blocca** (lock, unlock affordance only). Forced subtitles are auto-selected on the first
  `onTracksChanged` (`selectForcedSubtitle`, preferring the audio language). Preferred audio + text
  language seeded to `"it"`.

## Conventions

- **ktfmt style**: 2-space indent, no semicolons, trailing commas, ~120 col.
- Comments are dense and explain **why**, frequently quoting the user bug report that motivated the
  change (e.g. `"dopo 20 minuti… il box va in standby"`). Match the surrounding density.
- Errors: `runCatching { … }.getOrNull() / .getOrDefault(…)` almost everywhere; engine/net helpers
  never throw to the UI.
- Coroutines: `withContext(Dispatchers.IO)` / `launch(Dispatchers.IO)` inside engine/VM methods;
  `viewModelScope` in VMs; `coroutineScope { async … awaitAll() }` for the parallel fan-outs.
- Persistence: `SharedPreferences` + kotlinx.serialization JSON. No database.
- `:app` UI is **D-pad only** — focus is hand-managed with `FocusRequester` + `onPreviewKeyEvent`;
  read the long comments in `HomeScreen.kt` / `PlayerScreen.kt` before touching focus logic.
  `:client` UI is touch-only and never assumes a focus model.
- User-facing strings are Italian (no `strings.xml` catalogue — inline literals).

## Gotchas

- **`LocalContext` → Activity differs by app.** In `:app`, `setContent` under `androidx.tv`
  material wraps the context in a `ContextThemeWrapper`, so `context as? Activity` is silently
  null — use `Context.findActivity()` (in `PlayerScreen.kt`). In `:client` (plain Material3),
  `context as? Activity` works and is used directly.
- **Player resize needs a `TextureView`.** `PlayerView`'s default `SurfaceView` is a separate
  compositor layer the parent can't crop, so `RESIZE_MODE_ZOOM` ("Riempi") leaves the video boxed.
  `:client` inflates `res/layout/player_view.xml` (`app:surface_type="texture_view"`) instead of
  constructing `PlayerView` in code. Don't "simplify" that back to `PlayerView(ctx)`.
- **Auto-next-episode** (`:app`): `PlayerScreen` swaps `activeItem` in place and `remember`s a new
  `ExoPlayer` keyed on `item.url`. The `AndroidView` `factory` runs once, so its `update` block
  must reassign `view.player` and re-`requestFocus()` — otherwise frozen video + dead D-pad.
- **`LazyColumn`-disposed hero** (`:app`): `requestFocus()` on a scrolled-away first item is a
  silent no-op; `scrollToItem(0)` first (see `HomeScreen.kt` / `HomeHeader`).
- **Trending rows** must dedup by a stable key or the `LazyRow` crashes ("Key … was already used").
  Home section keys and card keys are `channelId + url`.
- **YouTube on TV boxes**: often no official app — only SmartTube + a "no browser" stub. Open
  trailers with the `youtube.com/watch?v=` URL (**not** the `vnd.youtube:` scheme, which SmartTube
  errors on) and target the sole / SmartTube handler explicitly. Needs the `<queries>` block in
  `app/src/main/AndroidManifest.xml` for API 30+ package visibility.

## Not built yet

- **Phone companion "Browse" tab** (`:mobile`) — design decided, not implemented: add `GET /home`
  to `RemoteControlServer` (return `channel.home()` + Continue-Watching / My-List — pass a
  `Context` into the server ctor for the stores), make `HomeSection` `@Serializable` in `:shared`,
  add `TvClient.home()` + a `HomeState` in `RemoteViewModel`, render poster rows in the idle search
  screen.
- **Per-episode i18n** — episode titles/plots are whatever the source ships (English for some
  series); only series and per-season synopses are localised. Per-episode = N TMDB calls/season.
- **TMDB genres** localise only on the API path; the keyless scrape keeps the site's genres.
- A second `Channel` implementation — the whole engine is built for it but only
  `StreamingCommunityChannel` exists.
