# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**StrComm** — a personal-use client for a single third-party catalogue site. Three apps, one
content engine. Five Gradle modules:

| Module | Type | Package | What |
|---|---|---|---|
| `:shared` | kotlin-jvm | `com.s4me.tv.*` | wire types only — `StreamItem` / `ItemKind` / `HomeSection`, and the remote-control protocol (`Handshake`, `PlaybackStatus`, `PlaybackCommand`) |
| `:engine` | android-library | `com.s4me.tv.engine` | **the content engine** — `Channel` sources, HTTP/scrape helpers, IMDb/TMDB enrichment, on-device stores. No Compose, no media3. |
| `:app` | android-application | `com.s4me.tv` | the Android **TV** app (leanback, D-pad) + the local control server for the phone remote — also the **Fire TV Stick** build, same APK |
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
./gradlew :app:lintDebug :client:lintDebug :mobile:lintDebug

# release APKs — signed iff keystore.properties exists at the repo root, else unsigned
./gradlew :app:assembleRelease :client:assembleRelease :mobile:assembleRelease
```

- **JDK 17** toolchain (`jvmToolchain(17)`), Gradle 9.1, AGP 9.0.x, Kotlin 2.3.x, compileSdk 36.
  minSdk is **23** for `:app`/`:engine` (floored by `androidx.tv:tv-material`'s own minSdk 23 —
  this is what makes `:app` installable on Fire TV Stick; Fire OS 6, the oldest Fire OS still
  installable, is API 25) and **26** for `:client`/`:mobile` (phones/tablets have no such
  constraint). AGP 9 auto-applies the Kotlin Android plugin — modules only declare
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
- **Versions never move**: all three apps are versionName `1.0.0` / versionCode `1`, and GitHub has
  one release, `v1.0.0`, whose APKs are replaced in place (asset names
  `StrComm-{TVBox-FireStick,Mobile-Tablet,Remote}-v1.0.0.apk`). On 2026-09-29 the whole history
  was squashed into a single "StrComm 1.0.0" commit and the release recreated on it. Settings (and
  the Remote's first screen) show the installed versionName via `appVersionName()` (`engine/AppInfo.kt`)
  next to "app a uso esclusivamente personale".

## On-device testing (no emulator needed)

Recent sessions had a real Android TV box **and** a USB-tethered tablet on ADB — ask the user for
the box IP:

```bash
adb connect <box-ip>:5555            # e.g. 192.168.188.128:5555 — a Strong 4K STB:
                                     #   SmartTube installed, NO official YouTube app
adb -s <box-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk       # TV app
adb -s <tablet-serial> install -r client/build/outputs/apk/debug/client-debug.apk  # touch app
adb -s <serial> shell am start -n com.s4me.tv/.MainActivity   # NOT `monkey -p … 1`, see below
adb -s <serial> exec-out screencap -p > shot.png
```

The box is **slow** to load Home (network fan-out) and scripted D-pad nav into it is flaky — wait
15 s+ before screenshotting, or ask the user to drive. Wireless ADB drops often (`adb connect`
again); devices sleep and screenshots come back black on the lock screen. Installing a *release*
build over a *debug* one fails on signature mismatch — but a device on Android 13+ can be moved to
the release key **without uninstalling** (data kept) by signing the release APK with a key-rotation
lineage: `apksigner rotate --out L --old-signer --ks ~/.android/debug.keystore … --new-signer --ks
keystore/strcomm-release.jks …`, then `apksigner sign --ks <debug> … --next-signer --ks <release> …
--lineage L app-release.apk` (v3.1 carries the release cert for API 33+; older APIs still see the
debug one, so never upload that APK). Done on the Strong box 2026-09-28 and the Galaxy Tab A9+
tablet (`SM-X216B`, 192.168.188.23) 2026-09-29: both now run the release signature (debug listed as
past signer) and take plain release APKs. Wireless-debug pairing needs the 6-digit code shown next
to the *pairing* port (`adb pair ip:port code`); the *connect* port is a different one — read it
from `avahi-browse`. Launch with `am start`, never
`monkey -p … 1`: monkey injects one random key right after launching, and a debug cold start on the
box took ~8 s to its first frame, so that key waited >5 s for a focused window → ANR ("Input
dispatching timed out (Application does not have a focused window)") and the process was killed. A
Galaxy S24 (`SM-S921B`, already paired) shows up on wireless debugging — its port changes and the
link drops often: `avahi-browse -rtp _adb-tls-connect._tcp` for the current ip:port (the distro
`adb` has no `adb mdns`), then `adb connect` again.

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

### HLS → MKV download (`engine/download/`)

Pure Kotlin + OkHttp (no media3, so both apps could use it; only `:client` does today).
`HlsMkvDownloader.plan(masterUrl)` picks the highest-bandwidth H.264 variant plus **every** audio
rendition and subtitle track; `download(plan, title, FileChannel)` writes ONE Matroska file with all
of them, keeping each track's language/name/DEFAULT flag, forced subs flagged forced. Streaming, not
stage-then-remux: segment N of the video and of each audio rendition are fetched together (6 steps
in flight — the CDN throttles ~1.1 MB/s per connection), AES-128-decrypted, demuxed (`TsDemuxer`:
PAT/PMT, PES, H.264 Annex B → length-prefixed + SPS geometry, ADTS → raw AAC + ASC) and written by
`MkvWriter` once every track has moved past them — the only disk needed is the final file.
Verified live (movie + full 58-min episode, checked with ffprobe/ffmpeg decode + an EBML walker):
- Segments are MPEG-TS, AES-128, one key (`/storage/enc.key`) with an explicit IV; video variant
  and each audio language are **separate** TS renditions sharing one PTS clock — timestamps are
  kept raw (not rebased per track) so they stay aligned; video starts 83 ms after audio (B-frames).
- Playlist `RESOLUTION` is the ladder rung, not the frame (a "1280x720" variant is 1280x534 with an
  801:800 SAR) — dimensions come from the SPS.
- Subtitles: one WebVTT file per language, `mm:ss.mmm` times, no X-TIMESTAMP-MAP. Forced tracks are
  never `FORCED=YES` — only recognisable by name/LANGUAGE (`"ita-forced"` on one title,
  `"forced-ita"` on another).
- fMP4, byte-range and SAMPLE-AES segments throw `UnsupportedStreamException` up front.

### Site traffic (`engine/SiteTraffic.kt`, `engine/TitleResolver.kt`)

The site bans (Cloudflare 1006) addresses that send it scraper-like bursts — see the "Manca la
lista" gotcha. Two rules keep the app under that line; don't add a fan-out that bypasses them:
- **Every request to the site goes through `SiteTraffic.gate`** (the channel's `siteGet`; only the
  site — vixcloud, TMDB/IMDb and the image CDN don't): ≤2 in flight, starts ≥500 ms apart. Work
  nobody is waiting on runs under `withContext(SiteTraffic.Background)` — single file, 1.5 s pause
  after each, never ahead of a user action. Home's chart/genre rows and hero enrichment, the Oscar
  lists and `NewReleaseWorker` use it. `async` fan-outs are fine (the gate serialises them) but
  still cost requests.
- **Titles named elsewhere resolve through `TitleResolver`** (Netflix/JustWatch charts, TV guide,
  cinema calendar, Oscar lists): one search each, remembered on disk — found 14 days, "not on the
  site" 2 days, a failed search never. Home spends at most 15 uncached searches per load
  (`TitleResolver.Budget`, shared by the rows in order) and stops at the first failed one.
  `Channel.searchOrNull()` exists so a failed search isn't stored as "not in the catalogue".
- `:client`'s Home shows the site's rows as soon as `home()` returns and slots the chart rows in
  (under the personal rows) as they resolve; `:app` keeps its splash + wait-for-all (D-pad focus
  doesn't tolerate rows appearing above it). Person-search verification is capped at 30 detail
  pages.

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
  `/ping /search /list /play /now-playing /control /progress`. `RemoteControlBridge`
  (pick → navigation) and `PlaybackControlBridge` (`PlayerScreen` ⇄ server) carry data between the
  background HTTP thread and Compose. Runs as `RemoteControlService`, a foreground service (this
  box otherwise kills the process on backgrounding, taking the server down with it), started from
  `MainActivity`. `/progress` (GET/POST a `WatchProgress` list, engine-side) also backs `:client`'s
  "Sincronizza con la TV" — same server, same trust model, a different device on the other end.
- **`BrowseScreen` triple-role**: a series has no dedicated detail screen, so `BrowseScreen`'s root
  can be a `LIST` (filterable catalog + `FilterBar`, incl. curated Academy Award lists resolved
  through search), a `SERIES` (seasons grid) or a `SEASON` (episode list). `BrowseViewModel` seeds
  its filter from `root.extra`.
- **Ad/tracker/malware blocking in the WebView fallback** (`engine/AdBlock.kt`): the embed page
  rendered by `WebViewPlayer` is the only place third-party HTML/JS runs — native ExoPlayer HLS
  playback never touches it. `AdBlock.isBlocked(url)` checks a domain blocklist (StevenBlack/hosts,
  fetched keyless and cached to a plain file, refreshed weekly; a small hardcoded `SEED` covers the
  biggest ad networks before the first fetch lands) inside `shouldInterceptRequest`, on top of the
  pre-existing `shouldOverrideUrlLoading` guard that blocks whole-page hijack redirects (added after
  an observed incident — ad scripts navigating the entire WebView to a Google Ads policy page).
  `:client` has no WebView fallback at all, so no exposure there. `:app`'s `WebView` also has no
  `WebChromeClient` gaps left implicit: popups, permission prompts and third-party cookies are
  explicitly denied rather than relying on default behavior.

### `:mobile` — the phone remote

`TvDiscovery` (NSD → `Flow<List<DiscoveredTv>>`) · `TvClient` (OkHttp against the server above) ·
`TvPreferences` (last TV) · `RemoteViewModel` + `RemoteApp`. Connect flow pings `/ping` and only
switches once it answers as a real StrComm TV; remembers the last TV and auto-reconnects on launch;
a run of failed pings drops back to discovery.

### `:client` — the touch app

- **Responsive shell** (`ClientApp.kt`): one `rememberNavBackStack` + a `selectedTab` int.
  `isWideScreen()` = `screenWidthDp >= 600` → `WideTopBar` (tablet / phone-landscape) — a floating
  bar with the split-color "StrComm" wordmark + the four tabs as pills, replacing a `NavigationRail`
  that ate a column of width a touch surface doesn't need; narrower → bottom `NavigationBar`.
  `entryProvider {}` wires the tabs (Home / Cerca / Sfoglia / Impostazioni) and the pushed screens
  (Browse / Detail / Player).
- **`fullBleed`** = `backStack.lastOrNull() is Player` — hides the nav bar/rail and drops the
  Scaffold insets so the player is edge-to-edge. `PlayerScreen` *also* forces landscape
  (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`) and hides the system bars itself.
- **Back handling**: `NavDisplay` only handles back when `backStack.size > 1`. A separate
  `BackHandler` covers tab roots (non-Home tab → Home; Home → `activity.finish()`).
- **Screens** mirror the TV app through the same `:engine`: `HomeScreen` (hero `HorizontalPager`
  with **▶ Apri** + **＋ La mia lista** — the latter wired straight to `WatchlistStore.toggle()`,
  same as the hero card, not just the detail screen; Film/Serie TV/Documentari `TypeFilterRow`,
  genre chips, ~10 progressively-appended genre rows, Netflix + JustWatch trending — the trending
  rows use `RankedPosterCard`, a big low-contrast bleeding numeral to the poster's left, Top-10-chart
  style, in place of a small overlay digit), `BrowseScreen` (same triple-role as `:app`; `FilterBar`
  with Tipo/Genere/**Paese**/Anno/Ordina/**Premi→Oscar** — Paese (`CountryOption`, `Channel.countries()`)
  filters by country of origin, verified live against the site's own archive-page props, not guessed),
  `SeriesScreen` (Netflix-style — backdrop header, season `FilterChip` row, `EpisodeRow` list; backed
  by `SeriesViewModel`), `DetailScreen` (enriched via `DetailViewModel` — `_preview` renders metadata
  before `findVideos()` sources resolve), `SearchScreen` (debounced type-ahead), `SettingsScreen`
  (clears the three stores).
- **Continue Watching**: `HomeViewModel.personalSections()` normalises any legacy `PLAYABLE` entry
  to a navigable `MOVIE`/`EPISODE` so a tap opens Detail and re-resolves a fresh stream.
  `PlayerScreen` saves progress against `playbackOrigin(item, originId)` — a rebuilt navigable item
  keyed on `StreamItem.originId` (the stable content id), **not** the `PLAYABLE`'s own url (a
  short-lived HLS token, useless later). Same pattern exists in `:app`'s player.
- **Custom player** (`ui/player/PlayerScreen.kt`): tap toggles a Netflix-style overlay (back +
  compact `InfoBlock`, centre transport, bottom control row + a buffered-aware scrubber); double-tap
  edges seek ±10 s and flash a `SeekFlashBubble` (auto-dismisses after 650 ms — there was previously
  no feedback at all for a double-tap seek). Every control is a real `material-icons-extended` glyph,
  not a Unicode/emoji character — `libs.androidx.compose.material.icons.extended`, `:client` only.
  Control row: **Audio e CC** (`ModalBottomSheet` → `TrackSheet`), **Velocità** (0.5–2×),
  **Adatta / Riempi** (see gotcha), **Info**, **Blocca**. The scrubber is a `Slider` with
  `inactiveTrackColor = Transparent` layered over a plain `Box` painting the buffered fraction
  behind it — reuses `Slider`'s already-correct drag/tap-to-seek instead of hand-rolling one.
  **Info** opens `InfoPanel`, a right-side sheet unifying content metadata (title, season/episode,
  plot, cast chips) *and* streaming stats (resolution · bitrate · codec · fps · audio · buffer) in
  one scrollable surface — previously two separate, independently-toggled overlays. A
  `CircularProgressIndicator` now shows during any `STATE_BUFFERING` (initial load included, which
  had no indicator before). Forced subtitles are auto-selected on the first `onTracksChanged`
  (`selectForcedSubtitle`, preferring the audio language). Preferred audio + text language seeded
  to `"it"`.
- **Auto-next-episode** (`:client`): ported from `:app` — `resolveNextEpisode` finds the next
  episode in the same season and resolves it via `findVideos()`. Starts resolving ~20 s before the
  current episode ends (`NEXT_UP_LOOKAHEAD_MS`) so it's ready in time, not fetched cold at the end;
  swaps `activeItem` (rebuilding the `ExoPlayer`, keyed on `activeItem.url`, same pattern as `:app`)
  once the remaining time drops under `NEXT_UP_SWAP_MS`. `NextUpCard` shows a live countdown +
  thumbnail in the bottom-right during that window; **Annulla** sets a per-episode dismiss flag so a
  cancelled prompt doesn't immediately reappear. Movies (`episode == null`) never swap.
- **Downloads** (`client/download/`, `ui/downloads/`): "⬇ Scarica" on Detail enqueues
  `DownloadWorker` — a WorkManager foreground (`dataSync`) worker, one unique work per content id —
  which re-resolves a fresh stream, checks free space, and runs the engine's `HlsMkvDownloader`
  into MediaStore `Movies/StrComm/<title>.mkv` (IS_PENDING until complete; Android 8–9 fall back to
  the app's own Movies dir). Finished files are recorded in `DownloadsStore` and play offline via a
  PLAYABLE with `serverId = "local"`: `PlayerScreen` then skips the HLS source (ExoPlayer's default
  source reads the content:// .mkv) and auto-next-episode. "I miei download" (Settings) lists
  progress/cancel, play, "Apri con…" (VLC, MX…) and delete. Not verified on a device yet — the
  pipeline was verified on the JVM against the live CDN; the Android glue compiled and linted only.

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
- **YouTube embeds need an HTTP Referer** (`:client` Home hero's muted trailer): a
  `youtube.com/embed/…` URL loaded straight into a WebView sends none, and YouTube answers that with
  "Errore 153 · errore di configurazione del video player" ("ho un errore su s24 errore
  configurazione video player youtube"). Pass `mapOf("Referer" to "https://<app id>")` to `loadUrl`
  — YouTube's documented client identity for native apps. Trailers opened via intent (Detail's
  button, the TV app's SmartTube path) aren't affected.
- **A WebView inside `AndroidView` needs `MATCH_PARENT` layout params.** AndroidView attaches its view
  `WRAP_CONTENT`, and a WebView whose height is `WRAP_CONTENT` sizes itself to its content: the
  page gets a zero-height layout viewport, so anything `height: 100%` collapses. The hero trailer
  played with sound-off but showed black ("YouTube non parte") — DevTools showed innerHeight 420 yet
  the player and video 800×0. For DevTools on a release build, temporarily call
  `WebView.setWebContentsDebuggingEnabled(true)`, then `adb forward tcp:9333
  localabstract:webview_devtools_remote_<pid>` and talk CDP to `/json`'s `webSocketDebuggerUrl`
  (Node's global `WebSocket` is enough; `Page.createIsolatedWorld` reaches cross-origin iframes).
  Some trailers can't play embedded at all (embedding disabled by the owner, age-restricted,
  region-locked — "es american horror story"), so the hero keeps its backdrop *over* the WebView
  and fades it out only when `evaluateJavascript` reads `movie_player.getPlayerState() == 1`; an
  error card (`.ytp-error`) or 20 s without playback leaves the picture up.
- **YouTube on TV boxes**: often no official app — only SmartTube + a "no browser" stub. Open
  trailers with the `youtube.com/watch?v=` URL (**not** the `vnd.youtube:` scheme, which SmartTube
  errors on) and target the sole / SmartTube handler explicitly. Needs the `<queries>` block in
  `app/src/main/AndroidManifest.xml` for API 30+ package visibility.
- **Fire TV Stick install rejected as "incompatible"**: Fire OS's installer hard-checks
  `minSdkVersion` against the device's own API level before it will even attempt the install — a
  relabeled copy of an incompatible APK still fails identically. `:app` was minSdk 26; most Fire TV
  Stick units run Fire OS 6 (Android 7.1 → API 25) or older, below that floor. Fixed by lowering
  `:app` **and** `:engine` (a dependency floor propagates to consumers) to minSdk 23 — the true
  limit is `androidx.tv:tv-material`'s own minSdk, found via the manifest-merger error, not a
  guess. 23 still excludes only the original 2014/2016 non-4K sticks (Fire OS 5, API 22). If minSdk
  ever needs to move again, don't just edit the number and assume it builds — rerun
  `:app:assembleRelease` and read the manifest-merger error for the real floor.
- **Fire TV Stick covers blank** ("non si vedono le copertine") while the catalog loaded fine: the
  CDN checked out for every device (plain lossy WebP, same GlobalSign-rooted chain as the site), so
  `:app`'s Coil loader now uses `Net.client` (the stack the working catalog uses) instead of its own
  bare OkHttp, bypasses Coil's separate ConnectivityChecker, and disables hardware bitmaps on Amazon
  devices. Failures log under the `StrCommImages` tag — `adb logcat -s StrCommImages` if it recurs.
  Not reproduced on a Stick (none on ADB), so the actual root cause among those three is unconfirmed.
- **"Manca la lista" — Home's StreamingCommunity row holds only the "Sfoglia" card**: that is
  `home()`'s fallback when `fetchInertia("$host/")` fails, i.e. the site is unreachable, not a UI
  bug (the TV's `/search` returns `[]` too). Check from the same network with
  `curl -s https://<domain>/`: a 403 with body `error code: 1006` is Cloudflare's **IP ban set by the
  site owner**. Seen 2026-09-28, and **caused by the app itself**: the home IP, the new one after a
  modem restart, and each fresh NordVPN exit were all banned soon after the app ran on them ("si
  apre e poi mi banna nuovamente"), while the site answered 200 elsewhere. (It first looked like an
  ISP-range ban — two WindTre /16s refused — but each address had simply been used by the app.)
  One Home load fired ~80–100 site requests within seconds. Fixed by `SiteTraffic` +
  `TitleResolver` (see Architecture): measured on the S24 afterwards, 70 requests over 94 s across
  two launches plus browsing and playback, all 200, and the same exit still accepted minutes later.
  Bans already in place are not lifted by the fix — a banned address needs a VPN exit that isn't.
  `adb logcat -s StrCommSite` lists every site request with its status. To see what the site
  returns to a *phone's* network (e.g. through its VPN) without touching its UI, relay through
  toybox `nc` on the phone: `adb shell "nc -L -s 127.0.0.1 -p 18443 nc <domain> 443" &`,
  `adb forward tcp:18443 tcp:18443`, then `curl --connect-to <domain>:443:127.0.0.1:18443
  https://<domain>/` from the PC (TLS stays end to end); kill the listener and `adb forward --remove`.

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
