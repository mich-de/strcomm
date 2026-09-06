# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Three Gradle modules:

| Module | What | Package |
|---|---|---|
| `:app` | **StrComm** — the Android **TV** app | `com.s4me.tv` |
| `:mobile` | **StrComm Remote** — the **phone** companion (second-screen remote) | `com.s4me.tv.remote` |
| `:shared` | pure-Kotlin JVM: data model + remote-control wire contract | `com.s4me.tv.*` |

`:app` and `:mobile` both depend on `:shared` so the wire types (`StreamItem`, `PlaybackStatus`,
`Handshake`, …) can never drift between the TV server and the phone client.

StrComm is a personal-use client for a single third-party catalogue site — it hosts nothing and
does no HTML scraping for browsing (that site ships JSON). Only rating/metadata enrichment scrapes
(IMDb, TMDB). See `README.md` for the user-facing overview and the content-source note.

## Build & check

```bash
# fast compile check after edits (do this before assembling)
./gradlew :shared:compileKotlin :app:compileDebugKotlin :mobile:compileDebugKotlin

# debug APKs
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :mobile:assembleDebug     # -> mobile/build/outputs/apk/debug/mobile-debug.apk

# Android lint (the only real static check configured)
./gradlew :app:lintDebug            # report: app/build/reports/lint-results-debug.html

# release APKs — signed iff keystore.properties exists at the repo root, else unsigned
./gradlew :app:assembleRelease :mobile:assembleRelease
```

- **JDK 17** toolchain (`jvmToolchain(17)`), Gradle 9.1, AGP 9.0.x, Kotlin 2.3.x, compileSdk 36,
  minSdk 26. All versions in `gradle/libs.versions.toml`.
- **No unit tests.** `app/src/test` and `app/src/androidTest` exist but are empty; `./gradlew test`
  is a no-op. `./gradlew check` effectively just runs lint.
- No ktfmt/spotless/detekt task — the code follows ktfmt *style* by convention only (see below).
- Config cache is on ("Configuration cache entry reused" is normal). Known harmless warning:
  deprecated `java.util.Locale(String)` in `PlayerScreen.kt` (WebView path).
- `buildConfig = true` is set on `:app` only, to expose `BuildConfig.TMDB_API_KEY` (read from
  `tmdb.apiKey` in `local.properties` or the `TMDB_API_KEY` env var; blank = keyless scrape path).
- Release signing: `keystore.properties` at the repo root (gitignored) — `:mobile` reuses the same
  keystore as `:app`. An `android` CLI (`~/.local/bin/android`, the *android-cli* skill) is
  available for SDK / emulator management if plain Gradle isn't enough.

## On-device testing (no emulator needed for the TV app)

Recent sessions had a real Android TV box on the LAN over ADB — ask the user for its IP:

```bash
adb connect <box-ip>:5555            # e.g. 192.168.188.128:5555 — a Strong 4K STB:
                                     #   SmartTube installed, NO official YouTube app
adb -s <box-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell monkey -p com.s4me.tv -c android.intent.category.LAUNCHER 1
adb -s <serial> exec-out screencap -p > shot.png
```

The box is **slow** to load Home (network fan-out) and scripted D-pad nav into it is flaky — wait
15 s+ before screenshotting, or ask the user to drive. Wireless ADB drops often; `adb connect`
again. Installing a *release* build over a *debug* one fails on signature mismatch (uninstall
first, losing on-device data).

**Probe the running app's data without the UI** through the TV's own control server (same LAN):

```bash
curl "http://<box-ip>:57813/search?q=..."      # -> StreamItem[] JSON
```

## Architecture

### Content: the `Channel` engine (`app/src/main/java/com/s4me/tv/engine/`)

`Channel` (`engine/Channel.kt`) is a port of the Kodi-addon `channels/NAME.py` contract:
`home()`, `catalogRoot()`, `list(item, page)`, `genres()`, `detail(item, withRatings)`,
`seasonOverview(item)`, `findVideos()`, `search()`. **`ItemKind` drives navigation and drilling**:
`CATEGORY / LIST / SERIES / SEASON` are non-terminal (drill via `list()`), `MOVIE / EPISODE` are
terminal (resolve via `findVideos()` → a `PLAYABLE` item).

Single implementation: **`StreamingCommunityChannel`** — a Laravel + Inertia.js site whose pages
ship their data as JSON, so browsing needs no HTML scraping. Playback resolves to a token-signed
HLS `.m3u8` for native ExoPlayer, falling back to the embed page in a WebView. **The site rotates
its domain**: the channel reads the site's own self-reported `app_url` / `cdn_url` from every
response and re-points `host` / `cdn` — never hardcode a domain.

`ChannelRegistry.all` / `ChannelRegistry.byId(id)` is the only access point.
`NetflixTop10` / `JustWatchTop10` are *ranking signals only* — they yield trending title names that
still resolve and play through `StreamingCommunityChannel`.

### Ratings & metadata (`engine/Imdb.kt`, `engine/Tmdb.kt`)

The source site's own `score` is a stale second-hand TMDB import. The detail screen replaces it:

- `Imdb.rating(imdbId)` — scrapes `imdb.com/title/{id}` JSON-LD `AggregateRating`. Keyless.
- `Tmdb.lookup(tmdbId, isSeries)` — **keyless by default**: scrapes
  `themoviedb.org/{tv|movie}/{id}?language=it-IT` (`og:description` = Italian synopsis,
  `data-percent` = score). With `BuildConfig.TMDB_API_KEY` set it uses the TMDB **API** and also
  gets vote counts + the official trailer.
- `Tmdb.seasonOverviewIt(tmdbId, n)` — Italian per-season synopsis (`Channel.seasonOverview`;
  `listSeasons` carries `tmdbId` onto SEASON items so no extra series fetch is needed).
- `Tmdb.seasonYears(tmdbId)` — real per-season release years (API `/tv/{id}`, or one scrape of the
  server-rendered `/seasons` page via `SEASON_ROW`). Without this every season inherited the
  series' last-air year ("tutte 2026").
- All merged in `StreamingCommunityChannel.detail()`; TMDB Italian text wins over the site's.
- `detail(item, withRatings = false)` skips the rating/trailer lookups — used by
  `SearchViewModel.verifyCredited`, which fans out `detail()` over ~45 candidates per name tap.
- `youtubeVideoId(raw)` (top-level in `Tmdb.kt`) normalises any id/URL form to the 11-char id.
- Every external lookup is cached in-process for the session.

### Navigation (`app/Navigation.kt`, `app/NavigationKeys.kt`)

Navigation 3 + `rememberNavBackStack`. **`navKeyFor(item)` is the single kind→screen decision** —
use it everywhere a `StreamItem` is opened. `MainNavigation` also collects two flows from the
remote-control bridges: `RemoteControlBridge.incoming` (a phone pick → `navKeyFor`) and
`PlaybackControlBridge.commands` (a remote STOP → pop the player).

### Second-screen remote control (`app/remote/`, `shared/remote/`)

Trust model = Chromecast/DIAL (same-Wi-Fi, no auth). `RemoteControlServer` (NanoHTTPD, default
port `57813`) is advertised over NSD/mDNS (`RemoteControlAdvertiser`, service `_strcomm._tcp.`).
Endpoints: `/ping /search /list /play /now-playing /control`. `RemoteControlBridge` (pick →
navigation) and `PlaybackControlBridge` (`PlayerScreen` ⇄ server) carry data between the
background HTTP thread and Compose. Started from `MainActivity`.

### Phone companion (`:mobile`)

`TvDiscovery` (NSD → `Flow<List<DiscoveredTv>>`) · `TvClient` (OkHttp against the server above) ·
`TvPreferences` (last TV) · `RemoteViewModel` + `RemoteApp`. Connect flow pings `/ping` and only
switches once it answers as a real StrComm TV; remembers the last TV and auto-reconnects on
launch; a run of failed pings drops back to discovery.

### Home load (`ui/home/HomeViewModel.kt`)

Fans out `channel.home()`, the two trending charts, personal rows and hero enrichment concurrently.
`HomeViewModel.progress` is a `StateFlow<List<String>>` appended via `log()` from those coroutines;
`BrandedLoading(log = …)` renders its tail as a terminal-style panel on the splash — call `log()`
when adding a load step.

## Conventions

- **ktfmt style**: 2-space indent, no semicolons, trailing commas, ~120 col.
- Comments are dense and explain **why**, frequently quoting the user bug report that motivated the
  change (e.g. `"dopo 20 minuti… il box va in standby"`). Match the surrounding density.
- Errors: `runCatching { … }.getOrNull() / .getOrDefault(…)` almost everywhere; net/engine helpers
  never throw to the UI.
- Coroutines: `withContext(Dispatchers.IO)` inside engine/net methods; `viewModelScope` in VMs.
- Persistence: `SharedPreferences` + kotlinx.serialization JSON (`WatchProgressStore`,
  `WatchlistStore`, `SearchHistoryStore`, `TvPreferences`). No database.
- TV UI is **D-pad only**. Focus is hand-managed with `FocusRequester` + `onPreviewKeyEvent` — read
  the long comments in `HomeScreen.kt` / `PlayerScreen.kt` before touching focus logic.
- User-facing strings are Italian.

## Gotchas

- **`LocalContext` on TV is a `ContextThemeWrapper`, not an `Activity`.** `context as? Activity`
  silently yields null (this defeated the first keep-screen-on attempt). Use `Context.findActivity()`
  in `PlayerScreen.kt`.
- **Auto-next-episode**: `PlayerScreen` swaps `activeItem` in place and `remember`s a new
  `ExoPlayer` keyed on `item.url`. The `AndroidView` `factory` runs once, so its `update` block
  must reassign `view.player` and re-`requestFocus()` — otherwise frozen video + dead D-pad.
- **`LazyColumn`-disposed hero**: `requestFocus()` on a scrolled-away first item is a silent no-op;
  `scrollToItem(0)` first (see `HomeScreen.kt` / `HomeHeader`).
- **Trending rows** must dedup by a stable key or the `LazyRow` crashes ("Key … was already used").
- **YouTube on TV boxes**: often no official app — only SmartTube + a "no browser" framework stub.
  Open trailers with the `youtube.com/watch?v=` URL (not the `vnd.youtube:` scheme, which SmartTube
  errors on) and target the sole / SmartTube handler explicitly. Needs the `<queries>` block in the
  manifest for API 30+ package visibility.
- **`BrowseScreen` doubles as the series "detail"** (a series has no `DetailScreen`) — its Browse
  root can be a `LIST` (filterable catalog), `SERIES` (seasons grid) or `SEASON` (episode list);
  `BrowseViewModel` seeds its filter from `root.extra` so genre deep-links work.

## Not built yet

- **Phone companion "Browse" tab** — design decided, not implemented: add `GET /home` to
  `RemoteControlServer` (return `channel.home()` + Continue-Watching / My-List — pass a `Context`
  into the server ctor for the stores), make `HomeSection` `@Serializable` in `:shared`, add
  `TvClient.home()` + a `HomeState` in `RemoteViewModel`, render poster rows in the companion's
  idle search screen.
- **Per-episode i18n** — episode titles/plots are still whatever the source ships (English for some
  series); only series and per-season synopses are localised. Per-episode = N TMDB calls per season.
- **TMDB genres** localise only on the API path; the keyless scrape keeps the site's genres.
- Later: resume-aware casting from the phone, basic D-pad passthrough from the phone.
