# AGENTS.md — StrComm Project Context & Guidelines

**StrComm** is a personal-use client for a single third-party catalogue site. It consists of three Android apps powered by one shared content engine across five Gradle modules.

---

## 🏗️ Module Architecture & Dependency Graph

| Module | Type | Package | Description |
|---|---|---|---|
| `:shared` | `kotlin-jvm` | `com.s4me.tv.*` | Shared data types (`StreamItem`, `ItemKind`, `HomeSection`) and remote control protocol (`Handshake`, `PlaybackStatus`, `PlaybackCommand`). |
| `:engine` | `android-library` | `com.s4me.tv.engine` | **Content engine**: `Channel` sources, HTTP scraping/JSON parsing helpers, IMDb/TMDB enrichment, on-device stores. *No Compose, no Media3.* |
| `:app` | `android-application` | `com.s4me.tv` | **Android TV & Fire TV Stick app** (Leanback, D-pad navigation) + local remote-control HTTP server. |
| `:mobile` | `android-application` | `com.s4me.tv.remote` | **StrComm Remote**: Phone second-screen remote (TV discovery, search, cast, transport control). |
| `:client` | `android-application` | `com.s4me.tv.client` | **Touch streaming app** for phones & tablets (portrait + landscape) with custom ExoPlayer. |

### Dependency Graph
```
:shared ── :engine ──┬── :app
   └─────────────────┼── :client
                     └── :mobile   (:mobile depends on :shared only — no :engine)
```

- `:app` and `:client` are **independent apps sharing `:engine`**. They do not depend on each other.
- Changing shared behavior happens in `:engine`. UI changes in `:app` or `:client` never touch each other.

---

## ⚡ Build & Compilation Commands

```bash
# Fast compile check (run before assembling)
./gradlew :shared:compileKotlin :engine:compileDebugKotlin \
          :app:compileDebugKotlin :client:compileDebugKotlin :mobile:compileDebugKotlin

# Build Debug APKs
./gradlew :app:assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :client:assembleDebug   # -> client/build/outputs/apk/debug/client-debug.apk
./gradlew :mobile:assembleDebug   # -> mobile/build/outputs/apk/debug/mobile-debug.apk

# Android Lint
./gradlew :app:lintDebug :client:lintDebug :mobile:lintDebug

# Build Release APKs
./gradlew :app:assembleRelease :client:assembleRelease :mobile:assembleRelease
```

### Key Technical Specs
- **Toolchain**: JDK 17, Gradle 9.x, AGP 9.0.x, Kotlin 2.3.x, compileSdk 36.
- **minSdk**: **23** for `:app`/`:engine` (required by `androidx.tv:tv-material` and Fire OS 6 compatibility), **26** for `:client`/`:mobile`.
- **No Unit Tests**: Test suites are omitted by design (`./gradlew check` effectively runs lint).
- **Environment & Keys**: `BuildConfig.TMDB_API_KEY` exists only in `:engine`. If TMDB key is missing, keyless scraping path is used automatically.

---

## 📱 On-Device Testing & Debugging

```bash
# Connect to Android TV box over ADB
adb connect <box-ip>:5555

# Install debug builds
adb -s <box-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <tablet-serial> install -r client/build/outputs/apk/debug/client-debug.apk

# Control server endpoint probing (port 57813)
curl "http://<box-ip>:57813/search?q=..."
```

---

## 💡 Key Architectural Guidelines & Gotchas

1. **Never Hardcode Domains in `:engine`**: The site rotates domains frequently. `absorbSelfUrls()` dynamically updates `host` and `cdn` on every Inertia request response.
2. **Ratings & Metadata**: Site scores are stale; `Channel.detail()` dynamically fetches live ratings via `Imdb` / `Tmdb` helpers.
3. **D-pad vs Touch UX**:
   - `:app` is **D-pad only** (TV interface). Focus management uses `FocusRequester` + `onPreviewKeyEvent`.
   - `:client` is **Touch only** (phone/tablet interface) with custom ExoPlayer controls and dynamic aspect-ratio support.
4. **WebView Security**: Ad/tracker blocking in `:app`'s WebView fallback is managed by `AdBlock.isBlocked(url)` in `shouldInterceptRequest`. Third-party popups and redirects are strictly denied.
5. **Code Style**: Kotlin 2-space indent, ktfmt formatting convention, dense explanatory comments, UI safety via `runCatching { ... }.getOrNull()`. User-facing UI strings are inline Italian.
