# Nuvio ReSeq → Lucid Nuvio: Feature Reverse-Engineering & Integration Report

**Date:** 2026-11-22
**Reference app:** [abbaskip/Nuvio-ReSeq](https://github.com/abbaskip/Nuvio-ReSeq) at commit `ba3a282` ("Release Nuvio ReSeq 1.0.0 RC3.1", versionCode 10065, versionName `1.0.0-rc.3.1`)
**Target apps:**
- `Lucid Nuvio IPTV+ Mobile` — Compose Multiplatform, AGP 9.2.0 / Kotlin 2.4.10, version from CI, package `com.lucid.nuvioiptvplus.mobile`
- `Lucid-Nuvio-TV-IPTV` — Android TV NuvioTV fork, AGP 8.13.2 / Kotlin 2.3.0, version `1.1.0-beta.40` (versionCode 1103×100+build), package `com.lucid.nuvio.tv.iptv`

**Method:** static source analysis of all three repositories, including the CloudStream 4.7.0 AAR bytecode in the local Gradle cache. No builds, device tests or live extensions were run. Every claim below is grounded in a file path; runtime behaviour still needs the test plan in §6.7 / §7.11.

---

## 0. How to read this

| Reader | Sections |
|---|---|
| Decision maker | §1, §8, §10, §11 |
| TV app engineer | §3, §4.1, §6, Appendix B |
| Mobile app engineer | §3, §4.2, §7 |
| Reviewer / QA | §5, §6.7, §7.11, §10 |

**One-line answer:** CloudStream extension support is a solved problem in ReSeq, already 70–80% present in your TV app, and completely absent from your mobile app. The TV app needs a **delta upgrade**, not a port. The mobile app needs a **new, Android-only DEX runtime** plugged in behind the existing `PluginRepository.executeScraper` seam so the stream UI and playback pipeline stay untouched.

---

## 1. Executive summary

1. **ReSeq's "CloudStream support" is real CloudStream 4.7.0.** It depends on `com.github.recloudstream.cloudstream:library:v4.7.0` from JitPack and supplies the missing **host classes** (`CloudStreamApp`, `AcraApplication`, `CommonActivity`, `MainActivity`, `DataStore`, `CloudflareKiller`, `PluginManager`, tracker APIs, etc.) that extensions link against. Extensions are downloaded as `.cs3` (ZIP or raw DEX), loaded with `DexClassLoader`, and registered into CloudStream's global `APIHolder.allProviders` / `extractorApis`.
2. **Your TV app already contains this subsystem.** It has the same `com.lagradost.cloudstream3` bridge, `ExternalExtensionLoader`, `ExternalExtensionRunner`, `ExternalRepoParser`, `PluginManager` DEX branch, CloudStream dependency and ProGuard keeps. The subsystem is inherited from upstream NuvioTV; ReSeq's revision adds hardening and UX (SHA-256/atomic installs, per-extension settings host, source-language filter, DNS mode, newer repo formats, `TmdbProvider` handling, partial-result timeouts). Treat the TV work as syncing to ReSeq RC3, not porting a ReSeq-only feature.
3. **Your mobile app has no CloudStream compatibility at all.** It has a good **JavaScript** plugin runtime (QuickJS + cheerio/crypto shims) with a CloudStream-inspired manifest, but it downloads every provider as text and runs it in QuickJS. A `.cs3` DEX fails immediately. Adding real support means an **Android-only** runtime; iOS/Play Store must stay JS-only or no-op.
4. **The lowest-risk integration seam on mobile is `PluginRepository.executeScraper`.** It already returns `List<PluginRuntimeResult>`, which is converted by `PluginRuntimeResult.toStreamItem` and merged by `StreamsRepository`. If a DEX scraper returns the same result type, the entire stream list, autoplay, player handoff and playback pipeline work unchanged.
5. **Do not merge ReSeq wholesale.** Its `PluginManager`/`StreamRepositoryImpl` carry ReSeq-specific concepts (`StreamContentContext`, TVDB, provider play statistics, Supabase plugin sync). Copy the **CloudStream layer** and adapt five call sites; do not replace your repositories.
6. **Recommended sequencing:** TV hardening + settings host first (about a week), mobile DEX runtime second (3–4 weeks including tests), then optional smaller ReSeq features (ratings prompt, tiered autoplay, Drive backup). TVDB ordering is the only large ReSeq feature worth a separate project; Sports Hub, Stalker, multi-view and Drive-for-Live-TV already exist in both apps in some form.
7. **Biggest technical risks:** mobile source-set layout (`fullCommonMain` is merged into `androidMain`, not a real KMP source set), R8/reflection, Android 14+ DEX file rules, `minSdk 24` extension API assumptions, and the fact that plugin streams currently bypass debrid/badge processing on mobile.
8. **Licensing is compatible** (all three projects are GPL-3.0), but attribution and the Play Store policy on dynamic code loading must be handled deliberately (§10).

---

## 2. Nuvio ReSeq at a glance

ReSeq is an independent Android TV fork of `NuvioMedia/NuvioTV`, package `com.nuvio.tv`, with Live TV/Sports code adapted from `homelessbrian/NuvioPlusIPTV` and CloudStream host code adapted from `Laking22/Nuvio-Echo` and `luqmanfadlli/NuvioMobile-Enhanced`.

| Area | ReSeq implementation | Size (approx.) |
|---|---|---|
| CloudStream host + DEX runtime | `app/src/full/java/com/nuvio/tv/core/plugin/**`, `app/src/full/java/com/lagradost/cloudstream3/**` | ~5,200 LOC core+bridge (~7,300 incl. related plugin code) + AAR |
| JS plugin runtime (retained) | `PluginRuntime.kt`, QuickJS, fetch/cheerio/crypto shims | ~1,500 LOC |
| TVDB episode ordering / identity tracking | `app/src/main/java/com/nuvio/tv/data/tvdb/**` + UI/player/home integration | ~2,600 dedicated LOC; 137 files mention TVDB |
| Sports Hub | `app/src/main/java/com/nuvio/tv/livetv/sports/**` | ~15,900 LOC across 53 files; 51 test files |
| Live TV / IPTV (Xtream, M3U, Stalker, EPG, catch-up, DVR, multi-view) | `app/src/main/java/com/nuvio/tv/livetv/**` | ~43,000 LOC non-sports; 122 test files |
| Google Drive backup/sync | `app/src/main/java/com/nuvio/tv/livetv/sync/**` + settings UI | ~1,455 LOC |
| TV Mix | 8 files under `data/local`, `ui/screens/home`, `player` | ~1,100 LOC + integration |
| Tiered autoplay | `core/player/TieredStreamAutoPlay.kt` + selector/policy/settings | ~594 LOC core + settings |
| Personal ratings | `data/repository/PersonalRatingsService.kt`, `core/tracking/PersonalRating.kt`, `PersonalRatingsControl.kt` | ~571 LOC + integration |
| IPTV playlists as addon sources | `livetv/ondemand/IptvStreamSources.kt` + stream/addon UI integration | ~106 LOC + ~1.6k LOC integration |
| Multi-view | `livetv/multiview/**` | ~3,440 LOC |
| In-app updater with ART profile metadata | `updater/**`, custom Gradle task | ~2,128 LOC |

**Important provenance:** the CloudStream host/DEX subsystem is **upstream NuvioTV code**, not a ReSeq invention. A local NuvioTV checkout (`/Users/robbdeeze/Documents/projects/upstream-nuvio`, 0.9.4-beta) already contains `ExternalExtensionLoader`, `ExternalExtensionRunner`, `ExternalRepoParser`, `ExternalExtractorRegistry` and `TvTypeExtensions`. ReSeq's contribution is the RC3 **hardening and UX**: hash/atomic installs, per-extension settings host, source languages, DNS selection, newer repo formats, `TmdbProvider` handling, partial-result timeouts, and tests. That is why your TV app already has the subsystem; the work is syncing it forward, not transplanting a ReSeq-only feature.

**Architectural fact that matters:** ReSeq's `PluginManager` is the single façade for both JavaScript and DEX scrapers. It dispatches on `ScraperInfo.type` (`NUVIO_JS` vs `EXTERNAL_DEX`) inside `executeScraper`. Everything above it (stream repository, UI, autoplay, player) is source-type agnostic. Your apps can copy that shape.

---

## 3. CloudStream extension support: how it actually works

### 3.1 What a `.cs3` extension is

A CloudStream extension package is one of:

- a **ZIP** containing `classes.dex`, a `manifest.json`, and optionally resources; or
- a **raw DEX** file (legacy).

The DEX contains a class annotated `@CloudstreamPlugin` that extends `BasePlugin`. Its `load()` (or `load(Context)`) constructs one or more `MainAPI` providers and calls `registerMainAPI(...)`, and may register `ExtractorApi`s. CloudStream's `BasePlugin.registerMainAPI` attaches `sourcePlugin = filename` to each provider so the host can map results back to an installed package.

Extensions are compiled against the **CloudStream library ABI**, not against your app. They import `com.lagradost.cloudstream3.*` (`MainAPI`, `TvType`, `MovieLoadResponse`, `TvSeriesLoadResponse`, `Episode`, `SubtitleFile`), `com.lagradost.cloudstream3.utils.*` (`ExtractorLink`, `Qualities`, `loadExtractor`), `com.lagradost.nicehttp.*`, `com.lagradost.api.*`, plus Android/OkHttp/Jackson/JSoup. Those classes must resolve at runtime or the extension throws `NoClassDefFoundError` / `NoSuchMethodError`.

### 3.2 Why a host bridge is mandatory

The AAR supplies the extension-facing API but **not** the CloudStream application:

| Missing host class | Why extensions need it |
|---|---|
| `CloudStreamApp` | `context`, `cfUserAgent`, `getKey/setKey` helpers; referenced inside OkHttp interceptors on background threads |
| `AcraApplication` | `context`, current `Activity`, `getKey/setKey`; many extensions call it from `load()` |
| `CommonActivity` | `activity` + `showToast(...)` overloads |
| `MainActivity` | Not an Activity in the bridge: `reloadHomeEvent`, `afterPluginsLoadedEvent` |
| `utils.DataStore` | SharedPreferences-backed persistence (`cs3_plugin_preferences`) with Jackson JSON |
| `utils.DataStoreHelper` | `ResumeWatchingResult`, resume positions |
| `utils.UiText` / `txt()` | UI text helpers |
| `utils.Event` | Observer set |
| `network.CloudflareKiller` | WebView-based Cloudflare interceptor |
| `plugins.Plugin` | Subclass of `BasePlugin` adding `resources`, `openSettings`, registration helpers |
| `plugins.PluginManager` (stub) | `PluginData`, `getPluginsOnline`, `setOnline`, `unloadPlugin` |
| `syncproviders.SyncAPI`, `TrackerApis` | Tracker login stubs so extensions that call them don't crash |
| `ui.home.HomeViewModel` | `getResumeWatching()` returns empty |

ReSeq's bridge is 15 files, ~819 LOC, in `app/src/full/java/com/lagradost/cloudstream3/**`. They were adapted from `Laking22/Nuvio-Echo` (GPL-3.0). The AAR does not contain these classes, so there are **no duplicate-class conflicts**.

### 3.3 ReSeq's ABI stack

`app/build.gradle.kts` (full flavor) and `gradle/libs.versions.toml`:

| Dependency | Version | Purpose |
|---|---|---|
| `com.github.recloudstream.cloudstream:library` | `v4.7.0` | Extension-facing CloudStream ABI |
| `org.jsoup:jsoup` | 1.17.2 | Extensions use Jsoup selectors; **Ksoup is not binary-compatible** |
| `com.fleeksoft.ksoup:ksoup` | **0.2.4** | Some CS3 packages call the 0.2.4 `nextElementSibling()` ABI directly |
| Jackson databind + module-kotlin | 2.17.0 | `DataStore` stub JSON, extension code |
| `com.github.Blatzar:NiceHttp` | 0.4.16 | `app.baseClient`, `ignoreAllSSLErrors()` |
| `com.squareup.okhttp3:okhttp-dnsoverhttps` | same as OkHttp | DoH resolver modes |
| `org.conscrypt:conscrypt-android` | 2.5.2 | TLS compatibility |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | **1.11.0** | `runBlockingK` used by StreamPlay and other recent packages |
| `com.google.android.material:material` | 1.12.0 | Downloaded settings fragments use Material Views |
| QuickJS AAR, NanoHTTPD, ZXing | existing | JS plugins, phone pairing, QR |

Exclusions on the CloudStream artifact: Rhino, both `newpipeextractor` spellings, `java-string-similarity`. These avoid duplicate/irrelevant native/library code.

### 3.4 End-to-end flow

```
User pastes repo URL
  └─ PluginManager.addRepository
       ├─ short code?  -> cutt.ly redirect resolution
       ├─ `cloudstreamrepo://` -> https://
       ├─ GitHub blob/raw/tree -> raw.githubusercontent.com/.../repo.json
       └─ ExternalRepoParser.tryParse
            ├─ repo.json  { pluginLists: [ ... ] } -> fetch each list
            ├─ plugins.json [ { internalName, url, fileHash, tvTypes, ... } ]
            └─ directory URL fallback -> <base>/repo.json

  └─ downloadDexExtensions (10 parallel)
       └─ ExternalExtensionLoader.downloadExtension(id, url, fileHash)
            ├─ 10 MB cap (Content-Length + streamed count)
            ├─ optional sha256-<hex> verification
            ├─ raw DEX header OR ZIP with valid classes.dex
            ├─ write to temp in same dir
            ├─ setReadOnly() on API 28+
            └─ Os.rename(temp, target)  // atomic replace, old install survives failure

  └─ PluginDataStore commit (repositories + scrapers, type = EXTERNAL_DEX)

Playback
  └─ StreamRepositoryImpl
       └─ PluginManager.executeScraper(scraper, tmdbId, type, season, episode, content)
            └─ ExternalExtensionRunner.execute
                 ├─ loadExtension(id)
                 │    ├─ ensureCloudstreamInitialized() (Conscrypt, NiceHttp baseClient, DNS, cookies)
                 │    ├─ DexClassLoader(file, codeCacheDir, null, appClassLoader)
                 │    ├─ read manifest.json pluginClassName, else scan @CloudstreamPlugin
                 │    ├─ plugin.load(...) -> APIHolder.allProviders / extractorApis
                 │    └─ reflective fallback scan for unregistered MainAPI/ExtractorApi
                 ├─ TmdbProvider? -> load({"id":123,"type":"movie"}) -> loadLinks(data)
                 └─ else title search -> similarity/type/year match -> load(url)
                      -> episode data (season/episode matcher)
                      -> loadLinks(60 s cap, partial links preserved)
                      -> ExtractorLink -> LocalScraperResult -> Stream
```

### 3.5 Install and validation (ReSeq RC3)

`ExtensionPackage.kt` is small and security-relevant:

- `MAX_DEX_SIZE = 10 MB`, enforced on `Content-Length` and on the streamed byte count.
- Optional `fileHash` must equal `sha256-<hex>`.
- Accepts raw DEX (`dex\n0xx\0`) or ZIP with a valid `classes.dex`.
- Writes to a temp file **in the destination directory**, then `Os.rename` (atomic on the same filesystem).
- Marks the final file read-only on API 28+ (Android blocks writable DEX).
- On any failure, deletes the temp and leaves the previous install untouched.

This replaced the older TV approach, which deleted the existing file first and wrote bytes directly — a failed refresh could leave the user with no extension.

### 3.6 Loading and registration

`ExternalExtensionLoader.loadExtension`:

1. `PluginRuntimeHooks.ensureCloudstreamInitialized()` — lazily installs Conscrypt, builds CloudStream's `app.baseClient` with the extension DNS, cookie jar, 50 MB cache, `ignoreAllSSLErrors()`, and 60 s timeouts.
2. `DexClassLoader(file.path, codeCacheDir.path, null, context.classLoader)` — parent is the app loader, so the AAR + bridge classes resolve.
3. Set `AcraApplication.context`.
4. Snapshot `APIHolder.allProviders` and `extractorApis`.
5. Find the plugin class from the ZIP's `manifest.json` `pluginClassName`, else scan for `@CloudstreamPlugin`.
6. Load resources via `AssetManager.addAssetPath()` when `requiresResources` is true.
7. Call `plugin.load(...)`; BasePlugin registers providers/extractors.
8. Diff the snapshots; fall back to reflective DEX scanning if the plugin registered nothing (legacy packages).
9. Cache the loaded providers/extractors; `beforeUnload()` + remove them on refresh/uninstall.

`ReflectivePluginWrapper` handles non-standard packages that expose `load()`/`getRegisteredMainAPIs()` but do not extend `BasePlugin`.

### 3.7 Execution and matching

`ExternalExtensionRunner` bridges Nuvio's TMDB-ID model to CloudStream's text/URL model:

- **`TmdbProvider` extensions** get `{"id":<numeric tmdbId>,"type":"movie|tv"}` in `load()`, then `loadLinks(data, ...)`. A TMDB URL fallback is attempted.
- **Search-based extensions** get the selected title first (from `StreamContentContext`, if provided), then TMDB localized title, original title and up to 8 Latin-script alternative titles. Searches run in parallel; host-unreachable and `NotImplementedError` short-circuit.
- **Matching**: normalized Levenshtein + containment with length ratio; type check (movie vs TV), hard reject on year difference > 1, catch-all anime types require ≥ 0.9 similarity. Minimum score 0.5.
- **Episodes**: `matchPluginEpisodeIndex` handles absolute numbering and missing/duplicate season-episode pairs (`PluginEpisodeRequestRules`).
- **`loadLinks`**: 60 s cap using `withTimeoutOrNull`, with synchronized callback lists so partial links survive a timeout. Results are filtered to `http(s)` URLs.
- **Conversion**: `ExtractorLink` → `LocalScraperResult` (quality string via `Qualities`, `M3U8` → `hls`, `DASH` → `dash`, headers + `Referer`, provider name, subtitles with headers).
- **Concurrency**: up to 3 `MainAPI`s per package, 10 scraper packages at a time, 120 s outer scraper timeout.

### 3.8 Extractors, headers, subtitles

`ExternalExtractorRegistry` adds extension-provided `ExtractorApi`s to the library's global `extractorApis` list. `loadExtractor(url, referer, subtitleCallback, callback)` then resolves hoster links (StreamTape, Doodstream, etc.) using both built-in library extractors and extension extractors. Extensions are preloaded (`ensureExtractorsLoaded`) before searching so extractor-only packages also work.

Headers from `ExtractorLink` are preserved into `LocalScraperResult.headers`; the `Referer` is added as a header. Subtitles become app `Subtitle` objects with `isStreamProvided = true` and per-subtitle headers when present.

### 3.9 Per-extension settings

CloudStream's settings ABI is `Plugin.openSettings: ((Context) -> Unit)?`. The extension calls `context.startActivity` or attaches AppCompat `Fragment`s/DialogFragments to the host Activity.

ReSeq hosts this in `ExtensionSettingsActivity` (`AppCompatActivity`, translucent Material3 theme):

- Loads the callback off the main thread.
- Sets `CommonActivity.activity` and `AcraApplication.setActivity(this)`.
- Invokes the callback with the Activity as `Context`.
- Registers a `FragmentLifecycleCallbacks` and finishes when the last plugin fragment is destroyed; a plain dialog finishes when it loses/returns focus.
- Restores previous activity refs in `onDestroy`.
- Manifest entry in `app/src/full/AndroidManifest.xml`; theme + failure string in `app/src/full/res/values/extension_settings.xml`.

### 3.10 Storage layout

| Data | Location |
|---|---|
| DEX packages | `filesDir/cs_extensions/<repoId_internalName with `:` `/` → `_`>.cs3` |
| DEX opt output | `codeCacheDir/cs_dex_cache` |
| Extension settings | SharedPreferences `cs3_plugin_preferences` (JSON values via Jackson) |
| Repos + scrapers | DataStore per profile: `plugin_settings[_p<id>].preferences_pb`, keys `repositories`, `scrapers`, `plugins_enabled`, `group_streams_by_repository`, `plugin_provider_plays`, `scraper_settings`, `cloudstream_languages`, `cloudstream_dns` |
| JS code (retained) | `filesDir/plugin_code[_p<id>]/<scraperId>.js` |

### 3.11 UI

`PluginScreen.kt` is a Compose-for-TV `LazyColumn`:

- Add repository inline field (accepts URL, short code, `cloudstreamrepo://`, GitHub links).
- "Manage from phone" QR code + local HTTP server.
- Global plugin toggle, group-streams-by-repository toggle.
- `CloudStreamPreferences` card (only shown when a DEX scraper exists): source-language chips with installed counts, DNS cycle button.
- Repository cards: refresh, delete (with confirmation dialog and focus restoration), enable/disable all.
- Provider cards: type badge, version, description, Test, per-extension Settings (only when `hasSettings`), enable switch, diagnostics output.
- In the stream list, provider/group headers use the existing `StreamProviderGrouping` and `streamProviderHeader` paths.

### 3.12 Security model and honest gaps

**What ReSeq does well:**

- HTTPS transport with optional SHA-256 pin from the repo.
- 10 MB cap, DEX/ZIP validation, atomic replace, read-only file.
- Explicit user-added repositories; no bundled repos.
- No auto-install of new providers without a repository refresh/confirmation flow.
- Per-extension settings run in an isolated Activity, not inline in the main UI.

**What it does not do:**

- **No sandbox.** DEX runs in-process under the app UID with full filesystem, network and Android API access. A malicious extension can read app data, cookies, tokens and files.
- TLS verification is globally weakened for extension HTTP (`ignoreAllSSLErrors()`), so certificate pinning by extensions is moot.
- No signature/author allowlist; trust is "the repo URL the user typed".
- `PluginSafety` only blocks a known scraper name (`videasy`) — a name-based hack, not a security boundary.
- Extensions can persist data in `cs3_plugin_preferences` and read other keys.

**Recommendation for your apps:** keep user-supplied repos, show a one-time consent screen that says extensions are executable third-party code with full app access, display the repo host and hash status, and never bundle repos. On the mobile app this is also a Play Store policy requirement (see §10).

### 3.13 Test coverage in ReSeq

Unit (`app/src/testFull/.../cloudstream/`):

- `ExtensionPackageTest` — hash/legacy acceptance, corrupt download leaves installed package intact.
- `ExternalRepoCompatibilityTest` — redirected plugin lists, relative paths, unavailable lists, CloudStream share links/GitHub links, `Others` type eligibility.
- `CloudStreamActivityAbiTest` — old/new `CommonActivity`/`Toast` entry points.
- `HomeViewModelCompatStubTest` — stub resolvability and empty resume list.
- `MultiProviderRunnerTest` — one failing provider doesn't hide another; `TmdbProvider` fallback; per-language filtering.

Instrumentation (`app/src/androidTestFull/.../cloudstream/`): catalogue stream, DNS, preferences, external repo storage, multi-provider, public extension, settings host/button.

These are directly portable to the TV app and can be adapted to Robolectric for mobile.

### 3.14 Provider browse/search (mobile addition)

Normally a CloudStream provider is only consulted for the movie or series the user already selected. That hides catalogue-only providers (for example clip sites) whose content never shares a title with TMDB entries. The mobile app adds an explicit browse path:

1. Open an enabled CloudStream provider from Plugins, Home, Discover or Live TV.
2. The screen opens with a full-bleed, auto-advancing top-10 carousel built from the extension's current feed. Each slide uses that title's artwork behind a dark scrim, a rank chip, provider label, title and type/year; tapping a slide opens its detail page. The carousel scrolls away with the grid, and a floating back button stays above it. When the feed is still empty it falls back to a provider-artwork hero with the extension name, description, types, version and category count.
3. Below the hero, the screen automatically loads the provider's own home feed (the tab named "Latest" when present, otherwise its first home tab) as a poster grid matching the native Nuvio catalog layout. It shows titles and type/year labels and loads the next page automatically near the end of the grid.
4. Provider home tabs are exposed as horizontally scrolling category chips beneath the search field. Selecting one reloads that tab at page 1; the default **Latest** chip keeps the provider's preferred feed. The stream of titles is not labelled with a count.
5. The search field remains available; searching switches to provider-owned search results and hides the category chips.
6. Tapping a poster opens a native-style detail page: hero artwork, title, type/year badges, provider branding, Play action, sources, expandable synopsis and (for series) season/episode dropdowns. Backdrops come from the provider's `backgroundPosterUrl`; when the provider has no separate backdrop, a generated gradient artwork with the title watermark is used instead of reusing the poster thumbnail.
7. The detail page never shows an inline stream list. Play resolves sources on demand: the current extension first, then other enabled CloudStream extensions, then matching Nuvio add-ons. The first playable source starts automatically. Long-pressing Play opens a full-screen native source page: provider filter tabs with the current CloudStream extension first, then other extensions, then add-ons, followed by Nuvio's own stream cards. Tapping a card plays that source. For series, the first episode is pre-selected and seasons are collapsible dropdowns.
8. The detail page also carries the metadata the extension provides plus a TMDB enrichment pass: genres, runtime, content rating, status/coming-soon, director/writer/country/language/collection, cast (with photos), production companies, merged YouTube/extension trailers, provider or TMDB recommendations, and episode thumbnails, descriptions and runtimes. A Save action opens a target picker listing Nuvio Library plus every connected Simkl/Trakt/MDBList provider. Trailers are shown as 3-across YouTube thumbnail cards and use the TMDB-verified trailer set when available, falling back to provider trailers only when TMDB has none. Cast and recommendations are horizontally scrolling rails. Cast taps resolve the actor name through TMDB search and open the native person page with their other credits. Trailers use the in-app YouTube engine when trailer playback is set to In-App; otherwise they open through the system handler. Non-YouTube trailer links keep the system-handler fallback.
9. Playing a source creates a normal `PlayerLaunch` with request headers, subtitles and `StreamLinkCacheRepository` entry, then opens the existing player.

Not portable from the native page: library/tracking membership, watched progress, Trakt comments and reviews, and next-episode autoplay all depend on TMDB/Trakt IDs and the native metadata graph. CloudStream items only expose provider URLs, so those actions are intentionally absent rather than writing incorrect IDs into Nuvio's databases.

This path does not change title-driven discovery and does not require TMDB metadata. Current limits: category chips come from the provider's home tabs, not from arbitrary sub-sections inside a tab; each tab is paginated one page at a time, and search still uses the first result page only.

The action is available from both plugin surfaces: the full-screen Plugins page and the tablet settings hub (the hub previously bypassed the route callback). A live emulator run against the real `RdNutz` repo verified provider search results, a resolved link (`1 links, 0 subtitles`), ExoPlayer reaching `READY`, and browse state surviving the player round-trip.

### 3.15 Discovery and Home integration (mobile addition)

Extensions are no longer reachable only through Settings:

- **Plugins → repository → Manage extensions** opens a repository-scoped extension page listing every extension in that repository with its enable switch, a collapsible **Catalog visibility** dropdown (Show on Home, Show in Live TV, Show in Sports, Show in Lucid TV) and its settings action. Browse/Test are intentionally omitted there; they remain on the enabled rows of the main Plugins page.
- The main Plugins page uses compact, stacked repository groups: one-line headers with counts, expand/collapse (collapsed by default) and Manage. Expanded groups list their enabled extensions inline.
- **Home** shows a CloudStream row directly below Continue Watching, using equal-size 190×112 tiles. Each tile uses the extension artwork full-bleed with a dark bottom scrim, provider name and a “Latest & search” hint; extensions without artwork get a full-bleed typographic cover using the extension name in a high-contrast accent color. It shows up to 15 providers selected with Show on Home, plus a View all tile.
- **View all** opens a themed grid of equal-size extension tiles with full-bleed artwork, a bottom scrim, title and supported types, plus a spring pop-in as tiles enter the viewport; tapping a tile opens that provider's Latest feed.
- **Live TV → Add-ons** shows a CloudStream Live group for extensions with Show in Live TV enabled. Cards use the same artwork, title, subtitle and animated expand arrow as add-on cards. Each extension expands inline, exposes its home tabs as collapsible layers, and expanding a layer lists its channels. Layer rows mirror the add-on catalog rows: 24dp indent, 40dp icon tile, title/subtitle hierarchy, animated chevron, and 48dp-indented channel rows. Tapping a channel shows a “Finding best source…” notice, resolves the provider links with a bounded timeout (partial links are kept if the provider is slow), ranks them by quality then seeders, chooses the best playable direct source and opens the player directly; no source-selection sheet is shown in Live TV. If the provider URL itself is a direct HLS/DASH/MP4 link, that URL is used as a fallback. No EPG programme data is synthesized (CloudStream has no standard EPG API).
- **Sports** event pages query every enabled extension with Show in Sports, alongside sports add-ons. Each provider is searched by event title and team names; if search yields nothing, its latest feed and sports-looking layers are scanned. The lookup refreshes when the enabled-extension set changes. Matching extension events open the plugin stream page; guide data remains extension-generated.
- **Lucid TV** source picker offers CloudStream catalog rotation, series marathon and live relay sources for every extension with Show in Lucid TV enabled. Playback resolves streams through the plugin host (see §3.16).
- The Add Repository section includes **Browse repository index**, which loads the MegaRepo (3737) CloudStream repository database and lets the user add any child repository individually.
- **Search → Discover** lists each enabled CloudStream provider as a catalog under its supported Movie/TV type. Selecting one loads the provider's latest/home feed into the normal poster grid, with Load more using the provider's next page.
- Plugin cards on Discover are registered in an in-memory item index. Tapping a card opens `PluginBrowseRoute` with the selected item already loaded, so its streams are one step away.
- New CloudStream repositories install all providers **disabled**. The user chooses which extensions to enable; repository refreshes preserve that choice.
- Provider cards only show the settings gear when the extension actually registers a settings UI. Opening a repository page probes its unchecked extensions, persists the result, and releases probe-loaded packages; repository install/refresh does the same for that repo. If a plugin's load() fails before registering its settings callback, a DEX scan for `setOpenSettings`/`SettingsFragment` still exposes the gear, and opening settings reloads the package with the current foreground Activity because some extensions (CNC's SKTechProvider) cast the load context to `AppCompatActivity` and cache their settings fragment during `load()`. Verified on-device for PlayZTV and SKTech.
- Disabled providers, the global plugin switch, and Play Store/App Store builds are respected.
- The **Web-Apps browser** keeps a toolbar page switcher for recently opened pages, with per-page close actions and one-tap return; the active page is highlighted.

### 3.16 Lucid TV sources (mobile addition)

Lucid TV virtual channels can now draw from a CloudStream extension:

- Enable **Show in Lucid TV** on an extension from its repository page.
- In the Lucid TV channel editor, choose one of three CloudStream sources and pick an enabled extension: **CloudStream extension** (catalog rotation), **CloudStream series** (episode marathon) or **CloudStream live relay** (24/7 playlist-style loop).
- Catalog rotation builds its pool from the extension's latest feed (up to four pages, capped by the channel's max-item setting). Each item keeps its provider URL and API index.
- Series marathons walk the extension's series feed, load each series' episode list through `PluginRepository.browseEpisodes`, and expand the episodes into scheduled items with season/episode numbers, episode posters and runtimes.
- Live relay reuses the same provider pool but maps it to the `PLAYLIST_LOOP` kind, so it behaves like a 24/7 relay channel built from the extension's live layers.
- Tuning resolves the selected item through `PluginRepository.browseStreams`, maps the returned links into normal `StreamItem`s, and uses the existing player path. Source headers, quality metadata and subtitles are preserved.
- Codec support is backward compatible: channels without the new plugin fields decode as ordinary add-on sources.

Current limits: the Lucid TV source uses the extension's latest feed rather than a hand-picked catalogue, and there is no per-item channel editing UI yet. Global text search still queries addons only (extension search is available inside a provider's Browse page), and Home shows provider shortcuts rather than separate latest-video rows per extension.

---

## 4. Your two apps compared

### 4.1 TV app — already has CloudStream, but an older revision

**Present and working (verified in source):**

- CloudStream 4.7.0 dependency with exclusions (`app/build.gradle.kts:643`).
- `com.lagradost.cloudstream3` bridge (15 files) including `CloudStreamApp`, `AcraApplication`, `CommonActivity`, `MainActivity`, `DataStore`, `CloudflareKiller`, `Plugin`, `PluginManager` stub, `HomeViewModel`.
- `ExternalExtensionLoader` (763 LOC), `ExternalExtensionRunner` (901 LOC), `ExternalRepoParser` (149 LOC), `ExternalExtractorRegistry`.
- `PluginManager` with `RepositoryType.EXTERNAL_DEX`, auto-detection of external repos, `.cs3` download, DEX execution, extractor preload (`ensureExtractorsLoaded`).
- `PluginDataStore` and `PluginScreen`/`PluginViewModel` with repo add/remove, provider test, QR pairing.
- `NuvioApplication` and `MainActivity` already call `PluginRuntimeHooks`.
- ProGuard keeps for `com.lagradost.**` and `com.lagradost.nicehttp.**` (`app/proguard-rules.pro:106-129`).
- AppCompat theme available (`Theme.MyApplication` parent `Theme.AppCompat.DayNight.NoActionBar`).

**Delta versus ReSeq RC3:**

| Item | ReSeq | TV now | Action |
|---|---|---|---|
| `ExtensionPackage.kt` (hash/size/atomic stage) | 62 LOC | absent | copy |
| `ExtensionSettingsActivity.kt` | 112 LOC | absent | copy + manifest/theme |
| `ExtensionDns.kt` | 36 LOC | absent | copy + hook |
| `SourceLanguages.kt` | 19 LOC | absent | copy + DataStore |
| `ExternalExtensionLoader` hash/staging/settings | 405 LOC | 763 LOC divergent | merge, keep TV diagnostics/`getApi` API |
| `ExternalExtensionRunner` multi-provider/partial/content | 718 LOC | 901 LOC divergent | replace internals, keep public methods |
| `ExternalRepoParser` repo.json/GitHub/redirects | 173 LOC | 149 LOC older | merge |
| `PluginManager` settings/languages/setOnline/atomic commit | 1252 LOC | 1227 LOC older | merge |
| `PluginDataStore` source languages/DNS/atomic downloads | 374 LOC | 312 LOC | merge |
| `PluginScreen` preferences/settings/type badges | 1809 LOC | 1638 LOC | merge |
| `PluginViewModel` + `PluginUiState` | 426/68 | 397/63 | merge |
| `Plugin.kt` `fileHash` + `@JsonClass` | 362 | 359 | small patch |
| `PluginRuntimeHooks` DNS eviction | 82 | 78 | merge |
| Build deps: ksoup 0.2.4, DoH, coroutines 1.11, Material 1.12 | yes | **no** | add |
| `proguard-cloudstream-settings.pro` | yes | **no** | copy |
| Extension settings strings (10 keys) | yes | **no** | add |
| CloudStream tests (5 unit + 8 instrumentation) | yes | 1 compat test | copy/adapt |

**What TV users can do today:** paste a CloudStream repo URL and it will auto-detect the external format, download `.cs3` packages, run them, and show streams. Limitations versus ReSeq RC3: only the first `MainAPI` in a multi-provider package runs (`ExternalExtensionLoader.getApi`), extension settings cannot be opened, source languages and DoH modes are absent, installs are not atomic/hash-verified, newer repo formats are partially supported, and `TmdbProvider` handling is narrower. Extensions that require `PluginManager.setOnline` may also misbehave because the TV app doesn't populate it.

### 4.2 Mobile app — JS plugins only, no DEX

**Current plugin system (good, keep it):**

- Models in `composeApp/src/commonMain/kotlin/com/nuvio/app/features/plugins/PluginModels.kt`.
- Repository/state machine in `composeApp/src/fullCommonMain/kotlin/com/nuvio/app/features/plugins/PluginRepository.kt` (667 LOC): add/remove/refresh repos, per-profile state, Supabase sync (`plugins` table + `sync_push_plugins`), 6-hour refresh.
- JS runtime in `composeApp/src/fullCommonMain/kotlin/com/nuvio/app/features/plugins/runtime/**`: QuickJS, `getStreams(tmdbId, mediaType, season, episode)`, host `fetch`, URL, crypto, cheerio via Ksoup, `onSettings()` layout.
- Storage in `composeApp/src/androidFull/kotlin/com/nuvio/app/features/plugins/PluginPlatform.android.kt` + `PluginScraperCodeFileStore.android.kt`: SharedPreferences `nuvio_plugins`, per-profile `.js` files under `filesDir/nuvio_plugin_scrapers/<profileId>/<sha256>.js`.
- UI in `composeApp/src/fullCommonMain/kotlin/com/nuvio/app/features/plugins/PluginsSettingsScreen.kt` (526 LOC) and the settings route `PluginsSettingsRoute` (`navigation/Routes.kt:104`).
- Feature gate: `AppFeaturePolicy.pluginsEnabled`; `androidPlaystore` and `iosAppStore` disable plugins entirely. The full build is GitHub-distributed.

**Why CloudStream repos fail today:**

1. `addRepository` forces the URL to end in `/manifest.json` (`normalizeManifestUrl`), so `repo.json` / `plugins.json` URLs are mangled.
2. `PluginManifestParser` expects `scrapers[]` with `version: String`, while CloudStream lists have a top-level array with `internalName`, numeric `version`, `tvTypes`, and `url`.
3. Every `filename` is fetched with `httpGetText` (UTF-8, `Accept: application/json`) and stored as a `.js` text file.
4. QuickJS then parses binary DEX as JavaScript → syntax error → `Result.failure` → empty provider group.

**Capability matrix:**

| Capability | Mobile now | Needed for CloudStream |
|---|---|---|
| `.cs3` / raw DEX install | no | new Android-only runtime |
| CloudStream repo JSON (`repo.json`, `pluginLists`, `plugins.json`) | no | new parser |
| `DexClassLoader` + plugin load | no | new |
| CloudStream host classes | no | new bridge (15 files) |
| Extractor resolution (`loadExtractor`) | no | new registry |
| Per-extension settings UI | JS layout only | Android Fragment host |
| Headers/subtitles | yes (JS) | map from `ExtractorLink` |
| Source language filter | no | optional |
| DNS override | no | optional |
| Debrid/badges for plugin streams | **limited** (plugin groups bypass them) | decide/phase |
| iOS | JS only | keep JS only |
| Play Store | plugins off | keep off |

### 4.3 Side-by-side

| Concern | TV app | Mobile app |
|---|---|---|
| CloudStream AAR | already present | add (full Android only) |
| Host bridge | present | copy from ReSeq |
| Repo formats | partial | none |
| DEX safety | older | build from ReSeq RC3 |
| Settings host | missing | new Android Activity |
| Pipeline seam | `PluginManager.executeScraper` | `PluginRepository.executeScraper` |
| Feature gate | `pluginsEnabled` (full/playstore flavors) | `pluginsEnabled` + `androidDistribution` |
| Build isolation | real product flavors (`src/full`, `src/playstore`) | manual source-dir switch (`src/androidFull`, `src/androidPlaystore`) |
| Native libs | mpv, ffmpeg, VPN, etc. | mpv, ffmpeg, libass, QuickJS |
| Tests | Hilt + JVM + TV instrumentation | Robolectric host tests + common tests |

---

## 5. Non-breakage principles

Apply these to every change in both apps.

1. **Additive APIs only.** New parameters get defaults (`content: StreamContentContext? = null`, `fileHash: String? = null`). Never change an existing signature without a default in a codebase this large.
2. **Additive persistence only.** New JSON fields get defaults and `ignoreUnknownKeys`. Keep `NUVIO_JS` as the default repository type. Old stored state must load unchanged.
3. **Keep the façade.** `PluginManager` / `PluginRepository` remain the only entry points used by stream repositories and UI. Do not rename packages or move classes that other modules import.
4. **Platform isolation.** TV: all CloudStream code stays in `src/full`. Mobile: all DEX code stays in `src/androidFull`; `fullCommonMain` may reference only a small platform hook that `iosFull` also provides.
5. **No startup cost for non-users.** Keep Conscrypt, NiceHttp, DNS and `DexClassLoader` initialization lazy, triggered by repo/stream actions.
6. **No changes to playback.** DEX providers produce the same stream model as JS/addons. Do not touch ExoPlayer/mpv, player controllers, or download managers.
7. **Feature flag first.** Ship behind `pluginsEnabled` and an additional `cloudStreamExtensionsEnabled` (or a hidden "Experimental" toggle) until the test matrix passes.
8. **One writer per state.** Repository refresh and provider toggles must commit through the existing DataStore/SharedPreferences writers, not parallel ad-hoc writes.
9. **Fail closed and preserve the old install.** Any download/verification failure leaves the previously installed package usable. Any parser failure leaves the existing repo list unchanged.
10. **Tests before merge.** At minimum, port ReSeq's `ExtensionPackageTest`, `ExternalRepoCompatibilityTest`, `MultiProviderRunnerTest` and add a migration test for old stored state.

**Rollback plan:** because all state changes are additive, reverting the code leaves extra JSON keys and files that older builds ignore. Provide a "Remove all CloudStream extensions" action that deletes `cs_extensions` + DEX cache + repo rows, and document that uninstalling the app removes everything.

---

## 6. TV app — upgrade plan (delta port)

### 6.1 Phase 0 — baseline (half day)

1. Build the current full flavor and run the existing plugin tests:
   ```bash
   cd "/Users/robbdeeze/Documents/projects/Lucid-Nuvio-TV-IPTV"
   ./gradlew :app:assembleFullDebug
   ./gradlew :app:testFullDebugUnitTest --tests '*Plugin*' --tests '*cloudstream*'
   ```
2. Record the current `PluginScreen` behaviour with one JS repo and one external repo (add, refresh, toggle, test, play). This is your regression baseline.
3. Create a branch and keep every change in a small commit per phase.

### 6.2 Phase 1 — install safety and repo compatibility (1–2 days)

**Copy new files (verbatim unless noted):**

- `app/src/full/java/com/nuvio/tv/core/plugin/cloudstream/ExtensionPackage.kt`
- `app/src/full/java/com/nuvio/tv/core/plugin/SourceLanguages.kt`
- `app/src/full/java/com/nuvio/tv/core/runtime/ExtensionDns.kt`

**Merge `ExternalRepoParser.kt`** from ReSeq. It adds:

- `repo.json` fallback for directory URLs.
- `pluginLists[]` with per-list failure isolation.
- Redirect-aware relative resolution (uses the final response URL).
- GitHub blob/raw/tree → `raw.githubusercontent.com` rewrite.
- `cloudstreamrepo://` and `https://cs.repo/?` unwrapping.
- Newest duplicate by `internalName`.

Keep the TV app's existing public `tryParse` signature; ReSeq's is source-compatible.

**Merge `ExternalExtensionLoader.kt`, preserving TV-only methods:**

- Keep `downloadExtension(...)` but add `fileHash: String? = null` as the third parameter.
- Keep `loadExtensionWithDiagnostics(...)`; route it through the new loader.
- Replace the delete-then-write download with `stageExtension(...)` + `Os.rename`.
- Add `settingsCallback(...)`, `ensureExtractorsLoaded(...)`, `extensionFile(...)`, `evictCache(...)`.
- Keep TV's `apiCache`/`classLoaderCache` if other code references them; otherwise align with ReSeq's `loaded`/`preloaded` map. Prefer ReSeq's structure and update the two call sites.

**Patch the domain model** (`domain/model/Plugin.kt`):

```kotlin
@JsonClass(generateAdapter = true)
data class ExternalPluginEntry(
    // ...
    val fileHash: String? = null,
)
```

and add `@JsonClass(generateAdapter = true)` to `PluginRepository` and `ScraperInfo` (needed by Moshi codegen/reflection consistency).

**Patch `PluginManager.downloadDexExtensions`:**

```kotlin
val file = externalExtensionLoader.downloadExtension(
    scraperId = scraperId,
    downloadUrl = plugin.url,
    fileHash = plugin.fileHash,
)
```

**Add the missing build dependencies** (`app/build.gradle.kts`, full flavor only):

```kotlin
add("fullImplementation", libs.ksoup)                       // 0.2.4
add("fullImplementation", libs.okhttp.dnsoverhttps)
add("fullImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
add("fullImplementation", "com.google.android.material:material:1.12.0")
proguardFile("proguard-cloudstream-settings.pro")
```

`libs.versions.toml`:

```toml
ksoup = "0.2.4"
ksoup = { group = "com.fleeksoft.ksoup", name = "ksoup", version.ref = "ksoup" }
okhttp-dnsoverhttps = { group = "com.squareup.okhttp3", name = "okhttp-dnsoverhttps", version.ref = "okhttp" }
```

Do **not** add jsoup/Jackson/NiceHttp/Conscrypt/CloudStream a second time — they already exist in `app/build.gradle.kts:637-649`.

**Definition of done:** a corrupt or wrong-hash `.cs3` download leaves the old extension playable; a repo with `pluginLists` and relative URLs installs; a GitHub share link resolves; the app still builds both `full` and `playstore`.

### 6.3 Phase 2 — runner parity (1–2 days)

**Important structural difference:** the TV app's runner loads **one** `MainAPI` via `ExternalExtensionLoader.getApi(scraperId)` and instruments each step; ReSeq's runner loads **all** `MainAPI`s in a package and runs up to three concurrently. This is a replacement of the execution internals, not a line-by-line merge. Keep the TV public methods (`execute`, `executeWithDiagnostics`) and the `TestDiagnostics` steps that existing tests assert on.

Merge `ExternalExtensionRunner.kt` from ReSeq, adapting only:

- Add `content: StreamContentContext? = null` to `execute(...)` and `executeWithDiagnostics(...)`; keep existing call sites compiling.
- Add an optional `pluginDataStore: PluginDataStore? = null` constructor dependency for source-language filtering, or pass languages in explicitly if you do not want the DI change.
- Keep `getApi(...)` as a compatibility wrapper that returns the first loaded provider; don't delete it if other code or tests reference it.
- If your TV app has no `StreamContentContext`, add a minimal model:
  ```kotlin
  data class StreamContentContext(
      val contentId: String,
      val title: String,
      val year: Int? = null,
      val episodeTitle: String? = null,
  )
  ```
  and pass `null` everywhere for now. (ReSeq also carries `TvdbPlaybackContext`; omit it until TVDB is ported.)
- Keep TV's diagnostic step labels where tests depend on them; otherwise prefer ReSeq's richer diagnostics.
- Add `PluginManager.registerInstalledExtensions()` using `CloudStreamPluginManager.setOnline(...)`:
  ```kotlin
  CloudStreamPluginManager.setOnline(extensions.map { extension ->
      PluginData(
          internalName = extension.id.removePrefix("${extension.repositoryId}:"),
          url = extension.filename,
          isOnline = true,
          filePath = externalExtensionLoader.extensionFile(extension.id).absolutePath,
          version = extension.version.toIntOrNull() ?: 0,
      )
  })
  ```
  Call it before preloading extractors. This fixes extensions that consult the online plugin list.
- Replace the 60 s `loadLinks` block with the synchronized-list + `withTimeoutOrNull` pattern so partial links survive.
- Add the `TmdbProvider` JSON fast path (`{"id":...,"type":"movie|tv"}`) and the alt-title matching logic. The TV app already has a `TmdbLink` path for some providers; keep it as a fallback where it works, but prefer the ReSeq JSON path for StreamPlay/Ultima-style packages.

**Definition of done:** a `TmdbProvider` extension (StreamPlay/Ultima-style) returns links; a mega-aggregator that exceeds 60 s still returns the links collected so far; a failing provider does not hide a sibling provider's results.

### 6.4 Phase 3 — per-extension settings (1 day)

1. Copy `ExtensionSettingsActivity.kt` to `app/src/full/java/com/nuvio/tv/core/plugin/cloudstream/`.
2. Add `app/src/full/res/values/extension_settings.xml` exactly as ReSeq:
   ```xml
   <resources>
       <style name="Theme.ReSeq.ExtensionSettings" parent="Theme.Material3.Dark.NoActionBar">
           <item name="android:windowIsTranslucent">true</item>
           <item name="android:windowBackground">@android:color/transparent</item>
       </style>
       <string name="plugin_extension_settings_failed">This extension could not open its settings.</string>
   </resources>
   ```
   Rename the style to `Theme.Lucid.ExtensionSettings` if you prefer; update the manifest.
3. Add to `app/src/full/AndroidManifest.xml`:
   ```xml
   <activity
       android:name=".core.plugin.cloudstream.ExtensionSettingsActivity"
       android:exported="false"
       android:theme="@style/Theme.Lucid.ExtensionSettings" />
   ```
4. Copy `proguard-cloudstream-settings.pro`:
   ```proguard
   -keep class androidx.fragment.app.** { *; }
   -keep class androidx.appcompat.** { *; }
   -keep class androidx.recyclerview.widget.** { *; }
   -keep class com.google.android.material.** { *; }
   ```
5. Add `hasExtensionSettings` / `openExtensionSettings` to `PluginManager` and `PluginViewModel`.
6. Add a Settings button to `ScraperCard` only when `scraper.type == EXTERNAL_DEX`, the repo is enabled, and `hasSettings` is true.

**Definition of done:** at least one extension with a Material `Fragment` settings screen opens, its changes persist across process death, Back returns to the provider list, and no fragment state-restore crash occurs on rotation/TV sleep.

### 6.5 Phase 4 — UX and preferences (1–2 days)

Merge the ReSeq UI additions into `PluginScreen.kt` / `PluginViewModel.kt` / `PluginUiState.kt`:

- `sourceLanguages: Set<String>`, `installedLanguages: Map<String, Int>`, `extensionDns: String`.
- `CloudStreamPreferences` card shown only when a DEX scraper exists.
- `ToggleSourceLanguage` and `CycleExtensionDns` events.
- Type badges on provider cards (`CloudStream` vs `JavaScript`).
- Repository delete confirmation with focus restoration.
- `PluginDataStore.sourceLanguages` / `extensionDns` and setters.
- `PluginManager.setSourceLanguages`, `setExtensionDns`, `installedSourceLanguages`.
- `ExternalExtensionRunner` filters `MainAPI.lang` using `SourceLanguages.allows(...)`.
- `ExtensionDns.select(mode)` before preloading/execution; evict the connection pool on change.

**Strings to add to `app/src/main/res/values/strings.xml`:**

```xml
<string name="plugin_all_source_languages">All languages</string>
<string name="plugin_source_languages_title">Source languages</string>
<string name="plugin_source_languages_description">Choose which languages’ CloudStream sources search for streams. Sources with an unspecified or multilingual language are always included.</string>
<string name="plugin_dns_description">Choose the resolver used for CloudStream extension requests. Press OK to switch providers.</string>
<string name="plugin_dns_choice">CloudStream DNS · %1$s</string>
<string name="plugin_dns_system">TV system DNS</string>
<string name="plugin_repo_delete_confirm_title">Delete repository?</string>
<string name="plugin_repo_delete_confirm_message">Are you sure you want to delete it?</string>
<string name="plugin_repo_delete_yes">Yes</string>
<string name="plugin_repo_delete_no">No</string>
```

Add them to `values/` only; the 40+ locales will fall back to English. Do not reference `R.string.nav_settings` from ReSeq's screen unless your app has it.

### 6.6 Build, manifest and ProGuard checklist

- [ ] `fullImplementation` CloudStream 4.7.0 present once (already).
- [ ] `ksoup 0.2.4`, `okhttp-dnsoverhttps`, `coroutines-android 1.11.0`, `material 1.12.0` added.
- [ ] `proguard-cloudstream-settings.pro` linked from the release build type.
- [ ] Existing keeps for `com.lagradost.**`, `com.lagradost.nicehttp.**`, `com.lagradost.api.**` retained.
- [ ] `Theme.Material3.*` available (Material dependency added).
- [ ] `ExtensionSettingsActivity` in the `full` manifest only.
- [ ] `playstore` flavor still compiles: it uses a no-op `PluginManager` and `pluginsEnabled = false`; no CloudStream imports in shared source.
- [ ] `NuvioApplication` / `MainActivity` hooks unchanged.

**Dependency note:** the TV app is on `kotlinx-coroutines` **1.8.1**; ReSeq forces **1.11.0** because recent CS3 packages call `runBlockingK`. That upgrades the whole app's coroutines, not just extensions. Before merging, run the full app test suite and a player soak test. If 1.11.0 causes regressions, try 1.10.x (mobile is already on 1.10.2) and accept that some newest extensions may fail with `NoSuchMethodError`; log that as an unsupported provider rather than crashing. The same caution applies to `material 1.12.0` (new View dependency, no Compose impact) and `ksoup 0.2.4` (new to the TV app, so low risk).

### 6.7 Test plan (TV)

**Unit / Robolectric:**

- Port `ExtensionPackageTest` (hash, legacy raw DEX, corrupt archive, old install preserved).
- Port `ExternalRepoCompatibilityTest` (redirected lists, relative paths, unavailable list, GitHub/CS share links, `Others` type).
- Port `MultiProviderRunnerTest` (failing first provider, `TmdbProvider` fallback, language filter).
- Add a migration test: old `PluginDataStore` JSON without `type`/`fileHash` loads as `NUVIO_JS` with nulls.
- Add a test that `downloadExtension` with a bad hash does not delete/replace an existing file.

**Instrumentation (TV):**

- Add repo → provider list → enable → stream list shows a provider subheader.
- Settings button appears only for an extension with settings; opens and closes cleanly with remote Back.
- DNS/language preferences persist per profile and survive app restart.
- Remove repo deletes the `.cs3` and the provider rows; re-adding re-downloads.
- Corrupt `.cs3` (truncated ZIP) shows an error and does not crash the stream screen.

**Manual matrix:**

| Case | Expected |
|---|---|
| Nuvio JS repo | unchanged behaviour |
| CloudStream repo.json + pluginLists | installs all providers |
| CloudStream direct plugins.json | installs |
| Raw `.cs3` legacy | installs |
| ZIP `.cs3` | installs |
| Wrong SHA-256 | rejected, old install intact |
| 11 MB file | rejected |
| HTML error page | rejected |
| Extension with no `@CloudstreamPlugin`, but `getRegisteredMainAPIs` | reflective wrapper works |
| Extension settings with Fragment | opens, persists |
| Extension that calls `CloudflareKiller` | WebView challenge path works |
| Provider timeout > 60 s | partial links shown |
| Playstore build | no plugins UI, no CloudStream classes referenced |

### 6.8 TV risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Merging ReSeq's `PluginManager` drops TV-specific behaviour | Medium | High | Diff function by function; keep TV wrappers; run existing plugin tests |
| `StreamContentContext` dependency drags in TVDB types | Medium | Medium | Add a minimal model; pass `null`; do not import ReSeq TVDB |
| Missing Material theme causes settings Activity crash | Medium | Medium | Add dependency + theme; test on API 24 and 34 |
| ksoup 0.2.4 downgrade affects other TV code | Low | Low | TV doesn't use ksoup today; it is a new dependency |
| R8/reflection breaks extension loading in benchmark/minified builds | Low today (R8 off) | High if enabled | Keep rules; never enable R8 for `full` without an extension smoke test |
| Old installs lose providers on refresh | Medium | High | Atomic staging + merge-preserving `storeExternalDownloads` |
| Extension code is untrusted | High | High | Consent screen, no bundled repos, hash display, ability to remove all |

---

## 7. Mobile app — adding CloudStream extensions

### 7.1 Design decision: adapter behind the existing façade

Do **not** fork the plugin system. Add a second backend to `PluginRepository`:

```
PluginsSettingsScreen (fullCommonMain)
  └─ PluginRepository.addRepository(url)
       ├─ try Nuvio manifest.json  -> existing JS path (type = NUVIO_JS)
       └─ try ExternalRepoParser   -> type = EXTERNAL_DEX
            └─ externalExtensionHost.install(...)

StreamsRepository (commonMain)
  └─ PluginRepository.executeScraper(scraper, tmdbId, type, season, episode)
       ├─ NUVIO_JS      -> PluginRuntime.executePlugin(...)        (existing)
       └─ EXTERNAL_DEX  -> externalExtensionHost.execute(...)      (new, androidFull)
  └─ PluginRuntimeResult.toStreamItem(...)                         (unchanged)
  └─ StreamItem -> StreamCard -> PlayerLaunch -> player            (unchanged)
```

This keeps the blast radius to:

- `PluginModels.kt` (add types/fields)
- `PluginRepository.kt` (add parser/host branches)
- `PluginManifest` parsing (unchanged for JS)
- `PluginsSettingsScreen.kt` (type UI + settings button)
- `PluginStorage.android.kt` (binary store)
- one dispatch in `executeScraperInternal`
- new platform files in `androidFull` / `iosFull`

### 7.2 Critical source-set constraint

Your `fullCommonMain` is **not** a real KMP source set. `composeApp/build.gradle.kts` injects `src/fullCommonMain/kotlin` into `androidMain` (when `nuvio.android.distribution=full`) or the iOS default source set (when `nuvio.ios.distribution=full`). It compiles **twice**, against different platform directories.

Consequences:

- Do **not** declare new `expect` declarations in `fullCommonMain`; it is compiled as a platform source set, so an `expect` there has no separate common compilation to pair with. Existing `actual` declarations (like `PluginRepository`) are fine.
- Follow the existing pattern: `fullCommonMain` references a top-level platform symbol (like `PluginStorage` or `pluginDispatcher`); `androidFull` provides the real implementation and `iosFull` provides a no-op.
- Any `android.*` / `dalvik.*` import in `fullCommonMain` breaks the iOS-full build.
- The Play Store build never compiles `fullCommonMain`, so it is automatically isolated.

### 7.3 Data model changes (backward compatible)

Add to `composeApp/src/commonMain/kotlin/com/nuvio/app/features/plugins/PluginModels.kt`:

```kotlin
enum class PluginRepositoryType { NUVIO_JS, EXTERNAL_DEX }

data class PluginRepositoryItem(
    val manifestUrl: String,
    val name: String,
    val description: String? = null,
    val version: String? = null,
    val scraperCount: Int = 0,
    val lastUpdated: Long = 0L,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val type: PluginRepositoryType = PluginRepositoryType.NUVIO_JS, // new
)

data class PluginScraper(
    val id: String,
    val repositoryUrl: String,
    val name: String,
    val description: String,
    val version: String,
    val filename: String,
    val supportedTypes: List<String>,
    val enabled: Boolean,
    val manifestEnabled: Boolean,
    val hasSettings: Boolean = false,
    val logo: String? = null,
    val contentLanguage: List<String> = emptyList(),
    val formats: List<String>? = null,
    val code: String,
    val type: PluginRepositoryType = PluginRepositoryType.NUVIO_JS, // new
    val extensionHash: String? = null,                             // new
)
```

`PluginScraper` is a plain data class; also add `type`/`extensionHash` to `StoredPluginScraper` (which is `@Serializable`). With defaults, old JSON loads unchanged.

External repo models (new, in `fullCommonMain/.../plugins/external/`):

```kotlin
@Serializable
internal data class ExternalRepoManifest(
    val name: String = "",
    val description: String? = null,
    val manifestVersion: Int = 1,
    val pluginLists: List<String> = emptyList(),
)

@Serializable
internal data class ExternalPluginEntry(
    val name: String = "",
    val internalName: String,
    val description: String? = null,
    val version: Int = 1,
    val apiVersion: Int = 1,
    val status: Int = 1,
    val authors: List<String>? = null,
    val tvTypes: List<String>? = null,
    val iconUrl: String? = null,
    val url: String,
    val fileSize: Long? = null,
    val repositoryUrl: String? = null,
    val fileHash: String? = null,
)
```

### 7.3.1 Known pitfalls when adapting the existing models

- **`PluginScraper.code` is non-null.** For DEX providers set `code = ""` and never pass it to `PluginRuntime`. Branch on `type` before touching `code`.
- **`PluginManifest` expects `version: String`.** CloudStream lists use numeric `version`; that is why they must go through a separate `ExternalPluginEntry` parser, not `PluginManifestParser`.
- **`normalizeManifestUrl` appends `/manifest.json`.** Do not call it for `repo.json` / `plugins.json` URLs. Resolve a `RepositoryCandidate` first, then choose the parser.
- **`StoredPluginScraper.code` is nullable on purpose.** Keep the DEX path out of the JSON state; derive the file path from `scraperId` + profile id.
- **`PluginRepository` is an `expect object` in `commonMain`.** Its full implementation (JS path today) lives in `fullCommonMain`; the Play Store stub lives in `androidPlaystore`. Adding methods to the expect declaration requires updating both actuals.

### 7.4 Repository parsing

New `ExternalRepoParser` in `fullCommonMain`:

- `tryParse(input): ExternalRepoParseResult?`
- Unwrap `cloudstreamrepo://`, `https://cs.repo/?`, and GitHub blob/raw/tree URLs.
- Fetch `repo.json`; if it has `pluginLists`, fetch each list (relative to the **final** URL), skip failed lists, dedupe by `internalName`, keep the newest `version`.
- If the body is a direct array, parse `List<ExternalPluginEntry>`.
- If the URL has no extension, fall back to `<base>/repo.json`.
- Use your existing `httpGetText` for JSON; use `httpRequestRaw` only for the binary `.cs3`.

In `PluginRepository.addRepository`:

1. Keep the existing `normalizeManifestUrl` path for inputs that end in `/manifest.json` or have no known external shape.
2. Try the Nuvio manifest first **only if** the URL is not an explicit `.json` repo/list.
3. On failure (or explicit `.json`), call `externalExtensionHost.isSupported` and `ExternalRepoParser.tryParse`.
4. Persist `type` on the repository and each scraper.
5. For DEX entries, do not download the provider text; call `externalExtensionHost.install(scraperId, url, hash)` and store only metadata.

Keep `AddPluginRepositoryResult` unchanged; return the same `Success(repo)`/`Error(message)`.

**Migration:** `normalizeManifestUrl` must not append `/manifest.json` to a `repo.json` / `plugins.json` URL. Introduce a small candidate resolver instead of changing the existing function's behaviour for JS repos.

### 7.5 Android runtime files

Create under `composeApp/src/androidFull/kotlin/com/nuvio/app/features/plugins/external/`:

| File | Origin / adaptation |
|---|---|
| `AndroidExternalExtensionHost.kt` | New; implements the common interface; initializes the runtime, delegates install/execute/settings |
| `CloudStreamRuntime.kt` | Adapted from ReSeq `PluginRuntimeHooks`; Conscrypt, NiceHttp `app.baseClient`, cookies, DNS, Activity tracking |
| `ExternalExtensionLoader.kt` | ReSeq `ExternalExtensionLoader` + `ExtensionPackage`; replace `Context` source with the app context; keep `DexClassLoader` |
| `ExternalExtensionRunner.kt` | ReSeq runner; map `ExtractorLink` → `PluginRuntimeResult`; use mobile `TmdbService` for ID→title |
| `ExtensionSettingsActivity.kt` | ReSeq settings host; `AppCompatActivity`; theme in `androidApp/src/full/res` |
| `ExtensionDns.kt` | Optional; same DoH modes |
| `ExternalExtractorRegistry.kt` | ReSeq registry |
| `ExternalBridge.kt` or `com/lagradost/cloudstream3/**` | ReSeq's 15 host stubs, unchanged |

The bridge files must be in `androidFull` (not `fullCommonMain`). They are ordinary Kotlin/Java classes in the app APK.

**Initialization without touching shared code:** `CloudStreamRuntime.ensureInitialized(context)` is called at the start of `install`/`execute`/`hasSettings`. It can register `Application.ActivityLifecycleCallbacks` to track the current Activity for `AcraApplication.setActivity(...)`. This avoids editing `NuvioApplication.kt` or `MainActivity.kt` (which are shared with the Play Store build).

**Cookie jar:** ReSeq uses `NuvioApplication.extensionCookieJar` (a persistent `CookieJar`). Mobile has no equivalent. Implement a small `CookieJar` backed by `SharedPreferences` (per extension or global) or use `android.webkit.CookieManager` for WebView-based extractors. Document that cookies are shared across extensions (as in CloudStream).

**Title/year lookup:** `ExternalExtensionRunner` needs a title and year for search-based providers. Mobile's `TmdbMetadataService.fetchEnrichment(...)` is currently `private` (and returns `TmdbEnrichment` with `localizedTitle`, `originalTitle`, `alternativeTitles`, `releaseInfo`). Add a small `internal suspend fun enrichmentFor(tmdbId: Int, mediaType: String): TmdbEnrichment?` wrapper in `TmdbMetadataService` and call it from the Android runner; do not duplicate the TMDB detail API in the extension layer. If the ID is already IMDb, `resolvePluginTmdbId`/`TmdbService.ensureTmdbId` converts it first.

**Settings host:** `hasSettings` loads the package and returns `(plugin as? Plugin)?.openSettings != null`. `openSettings` launches `ExtensionSettingsActivity` with the scraper ID using the tracked Activity. The common UI only sees a Boolean + a `fun openSettings()` call.

### 7.6 Stream pipeline changes

Only one dispatch changes:

```kotlin
private suspend fun executeScraperInternal(
    scraper: PluginScraper,
    tmdbId: String,
    mediaType: String,
    season: Int?,
    episode: Int?,
    respectSearchPause: Boolean,
): Result<List<PluginRuntimeResult>> {
    val resolvedTmdbId = resolvePluginTmdbId(tmdbId, mediaType)
    return runCatching {
        when (scraper.type) {
            PluginRepositoryType.NUVIO_JS -> PluginRuntime.executePlugin(
                code = scraper.code,
                tmdbId = resolvedTmdbId,
                mediaType = normalizePluginType(mediaType),
                season = season,
                episode = episode,
                scraperId = scraper.id,
                respectSearchPause = respectSearchPause,
            )
            PluginRepositoryType.EXTERNAL_DEX -> externalExtensionHost.execute(
                scraperId = scraper.id,
                tmdbId = resolvedTmdbId,
                mediaType = normalizePluginType(mediaType),
                season = season,
                episode = episode,
                searchPaused = respectSearchPause,
            )
        }
    }
}
```

`StreamsRepository`, `StreamFetchSupport`, `StreamItem`, autoplay, `PlayerStreamsRepository` and the player need **no changes**. `PluginRuntimeResult` already carries `title/name/url/quality/size/language/provider/type/headers/subtitles`, which is exactly what `ExtractorLink` maps to.

**Per-extension settings button:** CloudStream repo entries do not carry `hasSettings`. Do not set it from the manifest. Query `externalExtensionHost.hasSettings(scraperId, profileId)` lazily (when the provider card is shown or when the user taps a "Settings" affordance), cache the result per scraper/version, and show the gear only when true. ReSeq does the same (`PluginViewModel.hasExtensionSettings`).

**Debrid/badges caveat:** `StreamsRepository` currently treats plugin groups as non-addon (`plugin:` IDs) and therefore excludes them from `StreamBadgePresentation` and `DirectDebridStreamPreparer`. CloudStream streams will inherit that limitation. Decide explicitly:

- **Phase 1 (recommended):** accept the limitation; direct links and P2P still work.
- **Phase 2:** add an `origin`/`resolver` field to `StreamItem` and extend `DirectDebridStreamPreparer` + `StreamBadgePresentation` eligibility to `plugin:` groups. This is a separate change with its own regression risk; do not mix it into the DEX port.

### 7.7 Storage, profiles, sync and wipe

**Binary store** — extend `PluginStorage` in `androidFull`:

```kotlin
internal object PluginStorage {
    // existing...
    fun extensionDir(profileId: Int): File =
        File(filesDir, "nuvio_cs3_${profileId}").apply { mkdirs() }

    fun extensionFile(profileId: Int, scraperId: String): File =
        File(extensionDir(profileId), sha256(scraperId) + ".cs3")

    fun saveExtension(profileId: Int, scraperId: String, bytes: ByteArray): Boolean {
        // temp file in same dir -> verify -> setReadOnly -> rename
    }

    fun hasExtension(profileId: Int, scraperId: String): Boolean
    fun loadExtensionPath(profileId: Int, scraperId: String): String?
    fun deleteExtension(profileId: Int, scraperId: String)
    fun deleteAllExtensions(profileId: Int)
}
```

**Keep these methods out of `fullCommonMain`.** They are called only by `AndroidExternalExtensionHost`; the common layer talks to the host interface. That way `iosFull` does not need matching stubs and cannot accidentally import `java.io.File`.

Rules:

- Per-profile directories (`nuvio_cs3_1`, `nuvio_cs3_p2`, …), matching the JS code store.
- Hash the scraper ID for the filename (safe on all filesystems).
- Write temp → fsync → `setReadOnly()` (API 28+) → `renameTo`; never delete the old file first.
- `iosFull` provides no-op implementations.

**Profile changes:** `ProfileRepository.selectProfile` already calls `onProfileChanged` on each repository; `PluginRepository` handles it. DEX files are per profile, so no extra hook is needed beyond `PluginStorage` using the current profile id. Make sure `PluginRepository.currentProfileId` is captured before a long download (ReSeq's `writablePluginProfile` pattern) so a profile switch mid-download doesn't write to the wrong profile.

**Cloud sync:** your `plugins` table/RPC currently stores URL + name + enabled. Add a `kind` column (default `'NUVIO_JS'`) or a second table. Keep the old RPC working for old clients; treat missing `kind` as `NUVIO_JS`. DEX packages are re-downloaded on the new device, not synced. If you cannot migrate the Supabase schema immediately, encode the type in the stored URL with a prefix (e.g. `cloudstream:<url>`) and strip it on install; old rows stay JS. Do not silently reinterpret an existing JS row as a DEX repo.

**Local data wipe:** add `nuvio_cs3_*` directories and any new prefs keys to `PlatformLocalAccountDataCleaner.android.kt` and the common cleaner list.

### 7.8 iOS and Play Store behaviour

- **Play Store (`androidPlaystore`)**: `fullCommonMain` is not compiled; `PluginRepository` uses the existing disabled stub. No CloudStream dependency, no DEX code, no manifest entry. Keep it that way — dynamic code loading violates Play policy.
- **iOS full (`iosFull`)**: `fullCommonMain` is compiled, so `externalExtensionHost` must exist. Provide `UnsupportedExternalExtensionHost` with `isSupported = false`, `install`/`execute` returning failure, `hasSettings = false`. The UI should hide the CloudStream option when `!isSupported`.
- **iOS App Store (`iosAppStore`)**: plugins already disabled; no change.

### 7.9 Build changes

Inside the `androidMain` dependencies block, guard with the same distribution switch used for the source dir:

```kotlin
if (androidDistribution == "full") {
    implementation("com.github.recloudstream.cloudstream:library:v4.7.0") {
        exclude(group = "org.mozilla", module = "rhino")
        exclude(group = "com.github.AmarullisVFX", module = "newpipeextractor")
        exclude(group = "com.github.AmaryllisVFX", module = "newpipeextractor")
        exclude(group = "com.github.AmaryllisVFX.newpipeextractor")
        exclude(group = "info.debatty", module = "java-string-similarity")
    }
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.github.Blatzar:NiceHttp:0.4.16")
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
```

Notes:

- JitPack is already allow-listed for `com.github` in `settings.gradle.kts`.
- Keep `ksoup` at 0.2.6 unless an extension smoke test shows a missing ABI. I verified that `nextElementSibling()`/`previousElementSibling()` exist on `com.fleeksoft.ksoup.nodes.Node` in 0.2.6, so the ReSeq 0.2.4 pin is likely conservative. Test the top extension set before downgrading.
- OkHttp 4.12 already exists; NiceHttp/CloudStream align with it.
- Jackson is new to the mobile app; add it only in the full distribution.
- R8 is off by default (`releaseMinifyEnabled=false`). If you enable it, copy the CloudStream keeps from `composeApp/proguard-rules.pro` and add Fragment/AppCompat/RecyclerView/Material keeps.

**Manifest/theme** (`androidApp/src/full`):

```xml
<activity
    android:name="com.nuvio.app.features.plugins.external.ExtensionSettingsActivity"
    android:exported="false"
    android:theme="@style/Theme.Lucid.ExtensionSettings" />
```

`androidApp/src/full/res/values/extension_settings.xml`:

```xml
<resources>
    <style name="Theme.Lucid.ExtensionSettings" parent="Theme.Material3.Dark.NoActionBar">
        <item name="android:windowIsTranslucent">true</item>
        <item name="android:windowBackground">@android:color/transparent</item>
    </style>
</resources>
```

Add the failure string to `composeApp/src/androidMain/res/values/strings.xml` (shared, harmless in Play Store; it is only a string resource):

```xml
<string name="plugin_extension_settings_failed">This extension could not open its settings.</string>
```

The Activity lives in `composeApp` and references `com.nuvio.app.R.string.plugin_extension_settings_failed`. The **theme** must live in `androidApp/src/full/res` because the Play Store build does not include Material Components and AAPT would fail resolving `Theme.Material3.*`. Alternatively, keep the Activity in `androidApp/src/full/kotlin` and expose a public settings-host function from `composeApp`; that avoids the cross-module `R` question but adds a public API. Either is workable; the report's file map assumes the Activity is in `composeApp`.

### 7.10 Code sketches

**Common interface (in `fullCommonMain`, no `expect`):**

```kotlin
package com.nuvio.app.features.plugins.external

import com.nuvio.app.features.plugins.PluginRuntimeResult

internal interface ExternalExtensionHost {
    val isSupported: Boolean
    fun hasExtension(scraperId: String, profileId: Int): Boolean
    suspend fun install(scraperId: String, profileId: Int, url: String, fileHash: String?): Boolean
    fun uninstall(scraperId: String, profileId: Int)
    suspend fun execute(
        scraperId: String,
        profileId: Int,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        searchPaused: Boolean,
    ): List<PluginRuntimeResult>
    suspend fun hasSettings(scraperId: String, profileId: Int): Boolean
    fun openSettings(scraperId: String, profileId: Int)
}
```

`androidFull`:

```kotlin
internal val externalExtensionHost: ExternalExtensionHost = AndroidExternalExtensionHost()
```

`iosFull`:

```kotlin
internal val externalExtensionHost: ExternalExtensionHost = object : ExternalExtensionHost {
    override val isSupported = false
    override fun hasExtension(...) = false
    override suspend fun install(...) = false
    override fun uninstall(...) = Unit
    override suspend fun execute(...) = emptyList<PluginRuntimeResult>()
    override suspend fun hasSettings(...) = false
    override fun openSettings(...) = Unit
}
```

**Android host initialization:**

```kotlin
internal object CloudStreamRuntime {
    @Volatile private var initialized = false
    private var appContext: Context? = null
    private var currentActivity: WeakReference<Activity>? = null

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
            app.baseClient = OkHttpClient.Builder()
                .dns(ExtensionDns)
                .cookieJar(ExtensionCookieJar(context))
                .followRedirects(true)
                .followSslRedirects(true)
                .ignoreAllSSLErrors()
                .cache(Cache(File(context.cacheDir, "cs_http_cache"), 50L * 1024 * 1024))
                .build()
            (context.applicationContext as Application).registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: Activity) {
                        currentActivity = WeakReference(activity)
                        AcraApplication.setActivity(activity)
                    }
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityDestroyed(activity: Activity) {
                        if (currentActivity?.get() === activity) AcraApplication.setActivity(null)
                    }
                    // other callbacks no-op
                }
            )
            AcraApplication.context = context.applicationContext
            initialized = true
        }
    }

    fun openSettings(scraperId: String) {
        val activity = currentActivity?.get() ?: return
        ExtensionSettingsActivity.open(activity, scraperId)
    }
}
```

**Result mapping (runner):**

```kotlin
private fun ExtractorLink.toRuntimeResult(provider: String): PluginRuntimeResult {
    val streamType = when (type) {
        ExtractorLinkType.M3U8 -> "hls"
        ExtractorLinkType.DASH -> "dash"
        else -> null
    }
    val allHeaders = buildMap {
        putAll(headers)
        if (referer.isNotBlank()) put("Referer", referer)
    }
    return PluginRuntimeResult(
        title = name,
        name = source,
        url = url,
        quality = Qualities.getStringByInt(quality).ifEmpty { null },
        type = streamType,
        headers = allHeaders.ifEmpty { null },
        provider = provider,
    )
}
```

### 7.11 Test plan (mobile)

**Host/JVM (Robolectric, existing `androidFullHostTest`):**

- `ExternalRepoParserTest`: repo.json + pluginLists, direct array, relative URLs, redirect base, GitHub/CS scheme, failed list doesn't produce an empty success.
- `ExtensionPackageTest`: hash, raw DEX, ZIP, corrupt, 10 MB cap, old install preserved (port ReSeq).
- `PluginStateMigrationTest`: old `StoredPluginScraper` JSON without `type` loads as `NUVIO_JS`.
- `PluginRepositoryDispatchTest`: mock host; `NUVIO_JS` calls `PluginRuntime`, `EXTERNAL_DEX` calls host; failures become `Result.failure` and don't crash the stream group.
- `PluginBrowseModelsTest`: provider results map to stream cards with name fallback, quality/size/language subtitles, header filtering, and subtitle handoff.
- `PluginBrowseStateStoreTest`: browse defaults to Latest and keeps latest feed/pagination state isolated per profile+provider key.
- `PluginCatalogRegistryTest`: extension cards get stable IDs and resolve back to the correct provider item.

**Instrumentation / manual:**

- Install a CloudStream repo, enable a provider, see streams in the stream list alongside addons/JS.
- Add a new CloudStream repo: every installed provider starts disabled; enable only the desired ones and confirm a repo refresh keeps those choices.
- Confirm the settings gear is absent for extensions without a settings screen and present for one that has one. Open CNC's SKTechProvider settings from the phone.
- Add a repository from **Browse repository index** (MegaRepo 3737 child list) and confirm consent/install behavior.
- Enable **Show in Live TV** for a provider and confirm the CloudStream Live group appears under Live TV → Add-ons.
- Enable **Show in Sports** for a provider and confirm its catalog results appear on a matching sports event page.
- Toggle **Show on Home** off for a provider and confirm it disappears from the Home CloudStream row but remains available in View all.
- Plugins → provider → Browse: the provider's latest/home feed loads without a query; scroll and use Load more.
- Plugins → provider → Browse: search the provider's own site, open a result, load a movie link or a series episode, and play it.
- Return from the player: the browse search/results/latest feed/selected episode are still present.
- Open per-extension settings, change a value, kill/relaunch, confirm persistence.
- Remove the repo: provider rows and `.cs3` file are gone.
- Switch profiles: separate repos/providers/files.
- Corrupt `.cs3`: error group, other providers still load.
- iOS-full build still compiles and hides CloudStream.
- Playstore build has no CloudStream classes and no settings entry.

### 7.12 Mobile risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `fullCommonMain` accidentally imports Android/dalvik | High if rushed | iOS build breaks | Runtime interface only; all DEX code in `androidFull`; CI builds iOS-full |
| R8/reflection breaks extension load | Medium if minify enabled | High | Keep rules; smoke test with 5 popular packages |
| Extension ABI mismatch (`NoSuchMethodError`) | Medium | Medium | Log missing class/domain (ReSeq's `extractMissingClass`); test top packages; keep jsoup/ksoup/coroutines versions aligned |
| `minSdk 24` vs extensions using API 26+ | Medium | Medium | Catch `LinkageError`/`NoClassDefFoundError`; show "unsupported on this device" |
| Plugin streams bypass debrid/badges | Certain | Medium | Decide phase 2; document user-visible limitation |
| Play Store policy | Certain if shipped | High | Keep out of Play flavor; no dynamic code in `androidPlaystore` |
| Repo JSON changes break parsing | Medium | Low | `ignoreUnknownKeys`; parser unit tests; fallback to direct array |
| Storage growth | Low | Low | 10 MB/package cap; "remove all"; per-profile delete on wipe |
| Profile switch during download | Low | Medium | Capture profile id before download; commit to that profile |
| Cookie leakage between extensions | Medium | Medium | Document; optionally per-scraper cookie jars |

---

## 8. Other ReSeq features: what to port and what to skip

ReSeq contains many features beyond CloudStream. The table below ranks them for **your** apps. "TV fit" assumes the TV app's existing NuvioTV base; "Mobile fit" assumes the Compose Multiplatform app.

| Feature | ReSeq LOC / files | Value | Effort | TV fit | Mobile fit | Verdict |
|---|---|---|---|---|---|---|
| **CloudStream extensions** | ~5,200 core+bridge (~7,300 related) + AAR | Very high | TV 4–8 d; mobile 3–4 w | already 70% | none today | **P0** |
| **TVDB episode ordering + identity tracking** | ~2,600 dedicated, 137 files mention TVDB, 33 test files | High for anime/foreign/split episodes | 4–8 w TV; 8–12 w mobile | good (same upstream) | large rewrite (KMP + trackers) | **P2**, separate project |
| **Personal ratings across Trakt/Simkl/MDBList + prompt to rate** | ~571 + 798 test LOC | Medium | 2–4 d each (TV already has Trakt-only) | partial (Trakt) | none (tracker clients exist) | **P1** |
| **Tiered autoplay** (immediate addon → approved wait → any-source fallback) | ~594 core + settings | Medium-high | 2–4 d | good | good | **P1** |
| **TV Mix** (random episode mixes) | ~1,100 + Home/player integration | Medium | 5–10 d | good | medium | **P2** |
| **Episode Shuffle** | ~250 + integration | Medium | already present | **already present** | **already present** | **Skip** |
| **Google Drive backup/sync for Live TV + plugin/TVDB keys** | ~1,455 + conflict handling | Medium | TV 3–5 d; mobile 5–8 d | good | medium (already has Live TV Drive) | **P2** |
| **IPTV playlists as addon sources** | ~106 + ~1.6k integration | Medium-high | TV 3–5 d; mobile 1–2 w | good | larger (stream model is addon-centric) | **P2** |
| **FlickPick + Advanced Discover** | ~1,300 + tests | Low-medium | 3–6 d | good if TMDB/search exists | good | **P2** |
| **Calendar** (episodes, releases, sports) | ~2,568 + tests | Medium | 5–10 d | good | medium | **P2** |
| **Multi-view** | ~3,440 | High for IPTV | — | already present | already present | **Skip** |
| **Sports Hub improvements** | ~15,900 / 53 files | Medium | 1–3 w to reconcile | already has 16.8k LOC sports | already has sports UI | **Skip** (compare, don't merge) |
| **Stalker/Ministra portals** | large | High | — | already present | already present (`StalkerClient.kt`) | **Skip** |
| **In-app updater + ART profile metadata** | ~2,128 | Medium | — | already present | already present | **Skip** |
| **Usenet/NNTP playback** | ~2,689 + native | Low for most users | high | none | none | **Skip** |
| **DoVi native (libdovi)** | native + CMake | Niche | high | TV has DV7 | no | **Skip** |

### 8.1 TVDB episode ordering (the one big feature worth planning)

**What it does:** lets a profile choose a TheTVDB order (default, standard/aired, DVD, absolute, alternate). The selected order changes the SxxEyy sent to stream addons; tracking is mapped back to the underlying episode identity used by Nuvio, Trakt, Simkl and MDBList. Known split/combined episodes project progress proportionally; extended cuts use the playing video's real duration. The TVDB key is per profile and can be backed up to Drive in a separate credentials file.

**Why it is expensive:**

- 14 core files (`TvdbClient`, `TvdbTrackingResolver`, `TvdbOrderStore`, `TvdbOrderRepository`, `TvdbEpisodeCorrespondence`, `TvdbEpisodeResolver`, `TvdbProgressProjection`, `TvdbProportionalProgress`, `TvdbCloudProgress`, `TvdbCredentialStore`, `TvdbMetaRepository`, `TvdbEpisodeOverlay`, `TvdbTraktCatalogue`, `TvdbTrackingCoverage`).
- **137 main files** mention TVDB, **45 files** carry `tvdbContext`/`tvdbEpisodeId`, and **23** reference `episodeIdentity`. The visible surface touches Home, Detail, Player, tracking, Continue Watching, stream requests, Drive sync and settings.
- 33 test files / ~3,500 LOC define the behaviour (mapping, split/combined, cross-series, progress, scrobble boundaries).
- External dependency: `https://api4.thetvdb.com/v4`, user-supplied API key, per-profile encrypted storage, 24-hour caches and a 16-entry episode projection cache.

**TV app path:** copy core + tests, then adapt the integration points to your fork's Home/Detail/Player. Because both are NuvioTV forks, many call sites match; expect a few days of merge conflicts around Live TV/Sports changes. The `TvdbMetaRepository` delegate replaces the bound `MetaRepository`, so the insertion point is clean but global.

**Mobile path:** the core logic is Kotlin and could be shared, but your data layer is JSON/SharedPreferences rather than DataStore, and your player/tracker abstractions differ. Budget a rewrite of the storage/integration layers, not the API client.

**Recommendation:** do CloudStream first. Treat TVDB as a funded follow-up only if users actually need alternate ordering; it adds a per-user API key requirement and a large test surface.

### 8.2 Small, high-value ports

**Personal ratings + prompt to rate**

- ReSeq: `core/tracking/TrackingRatings.kt`, `PersonalRating.kt`, `PersonalRatingsControl.kt`, `TraktRatings.kt`, MDBList/Simkl calls, "Prompt to rate after watching" setting.
- Behaviour: half-star UI, maps to 1–10 for each service, submits independently, reports per-service failures, removes via rating-removal API (not history deletion), prompts only after a movie reaches completion (not episodes/early stops).
- Fit: the TV app already has a `TrackingRatingProvider` abstraction but only `TraktRatings` implements it (`core/di/TrackingModule.kt:55`). The delta is Simkl + MDBList providers, multi-service submission, the half-star control and the prompt. Mobile has Trakt/Simkl/MDBList clients but no personal-rating UI, so port the whole small surface. Low risk, additive UI + one setting.

**Tiered autoplay**

- ReSeq: `core/player/TieredStreamAutoPlay.kt` + `PlaybackAutoPlaySettings.kt` + player runtime.
- Behaviour: immediate addons play after 0.5 s; approved sources wait N seconds; optional any-source fallback after a longer wait; both waits measured from the same search start; manual selection/cancel always wins; applies to next episode, TV Mix and in-player selection.
- Fit: both apps have autoplay selectors; this is a policy layer, not a playback change. Additive settings and timers.

**TV Mix**

- ReSeq: 8 files (`TvMix`, `TvMixStore`, `TvMixSession`, `TvMixScreen`, `TvMixViewModel`, `TvMixNavigation`, `TvMixHomeCatalog`, `HomeTvMix`) + Home/player hooks.
- Behaviour: long-press a series → add to a named mix; Home row; random show first, then shuffle episodes; excludes the previous episode when possible; natural completion continues the mix; no new numbering or tracking semantics.
- Fit: TV is straightforward (Home rows already exist). Mobile needs a new Home row + a session store; medium.

### 8.3 Other small features

- **Episode Shuffle** — already present in both apps (`features/shuffle/` on mobile; `HomeEpisodeShuffle`/`DetailEpisodeShuffle` on TV). Do not port.
- **FlickPick** — an unwatched-movie picker with genre/keyword/era/rating filters. ~844 LOC + tests. Additive to an existing Detail/Discover surface; low risk if you already have TMDB metadata.
- **Advanced Discover** — title terms, TMDB keywords, match any/every, send to FlickPick; ~975 LOC + tests. Cheap if your Discover page is extensible.
- **Calendar** — upcoming episodes, movie release dates and sports in day/week/month views; ~2,568 LOC + 1,114 test LOC. Depends on metadata release dates, TVDB order, sports fixtures and EPG. Good retention feature, but only after the data sources exist.
- **In-app updater ART metadata** — both apps already update themselves. ReSeq's `.dm` embedding task is a build-time optimization; port only if post-update cold start is a measured problem.

### 8.4 Do not port

- **Sports Hub**: your TV app already has 16.8k LOC of sports code and the mobile app has a sports UI. ReSeq's version is a different lineage (53 files, public `sports-base.json`, ESPN/TheSportsDB/sporting-events.org, optional Gemini). Merging would be a reconciliation project with high regression risk and little user-visible gain. Compare behaviour first; port individual fixes only.
- **Stalker, multi-view, Xtream/M3U, EPG, catch-up**: already present in both apps in some form. Port specific bug fixes, not the subsystem.
- **Updater**: both apps have in-app updaters. ReSeq's ART-profile `.dm` embedding is interesting but not worth touching a working updater.
- **Usenet/NNTP and DoVi native**: niche, heavy, native build risk.

---

## 9. Blending with your current UX

### 9.1 Naming

Use **one** user-facing concept per app, not "plugins" and "extensions" as separate top-level ideas:

- TV: keep the existing **Plugins** destination. Inside it, label providers by type: `CloudStream` and `JavaScript`. The add-repository field accepts both.
- Mobile: keep **Plugins** in Content Discovery. Update the description from "Install JavaScript scraper repositories" to "Install JavaScript or CloudStream extension repositories". Add a type chip on each repo/provider.

Avoid calling CloudStream "addons" — your apps already use "Addons" for Stremio addons. Consistent vocabulary:

| Term | Meaning |
|---|---|
| Addon | Stremio-compatible HTTP addon |
| Plugin | JavaScript scraper (Nuvio format) |
| Extension | CloudStream `.cs3` package |

### 9.2 Placement

- **TV**: `Settings → Content Discovery → Plugins` already exists. Add the CloudStream preferences card only when a DEX provider is installed, so JS-only users see no change.
- **Mobile**: `Settings → Content Discovery → Plugins` already exists; `PluginsSettingsRoute` is registered. Add:
  - a type badge in the repo/provider cards,
  - a CloudStream option in the add-repo card (segmented control or a secondary "Add CloudStream repository" action),
  - a per-provider gear button when `hasSettings`.

### 9.3 Copy and consent

Show a one-time confirmation before the first CloudStream repo install:

> CloudStream extensions are executable third-party code. They run with the same access as this app (files, network, accounts) and are not sandboxed. Only add repositories you trust. This app does not provide or endorse any extension or content source.

Include the repo host and whether the package has a SHA-256 hash. Do not bury this in a tooltip.

### 9.4 Error states

- Repo fetch failure: keep the old repo list, show "Could not reach <host>".
- Package verification failure: show "Extension failed verification" and keep the previous install.
- Provider execution failure: show the provider's error in the existing test/diagnostics panel, not a global error.
- Empty results: distinguish "no streams" from "provider unsupported on this device" (API/linkage failure) and "provider timed out; partial results shown".

### 9.5 Badges and grouping

- Provider badge: `CloudStream` / `JavaScript`, plus quality/size/language as today.
- Keep `groupStreamsByRepository` behaviour; DEX scrapers participate the same way (`plugin-repo:<url>` grouping).
- Do not add a second "sources" screen; reuse `StreamProviderGrouping` / `ProviderFilterRow`.

---

## 10. Legal, licensing and compliance

1. **GPL-3.0 compatibility.** ReSeq, Lucid Mobile and Lucid TV are all GPL-3.0. Copying ReSeq code into your apps is compatible. Retain copyright/attribution notices, mark modified files, and keep the full license texts. ReSeq's `NOTICE-IPTVNATOR.md` and `NOTICE-IPTV.md` only matter if you port the corresponding Stalker/IPTV code; for the CloudStream layer, credit `abbaskip/Nuvio-ReSeq`, `Laking22/Nuvio-Echo` and CloudStream.
2. **CloudStream library.** `com.github.recloudstream.cloudstream:library` is GPL-3.0. Linking it makes the combined work GPL-3.0; your apps already are. Add CloudStream and ReSeq to the in-app attributions/licences screen.
3. **Extension code.** Extensions are third-party works with their own licences (often none declared). Do not bundle them. Download only at the user's request from a user-supplied repo. Keep the repo URL and hash visible.
4. **Content legality.** Extensions can point at unlicensed content. State clearly, as ReSeq does, that the app does not host or supply media, does not bundle repos, and users are responsible for compliance. Do not ship default repos that provide copyrighted streams. Default metadata/subtitle endpoints are a separate matter.
5. **Play Store.** Dynamic code loading (DEX from the network) is not allowed in the Play-distributed flavor. Your `androidPlaystore`/`playstore` gates already disable plugins; keep CloudStream entirely out of those source sets and dependencies. The GitHub-distributed full APK is the correct channel.
6. **Trademarks.** "CloudStream" is a third-party project name. Use it descriptively ("CloudStream-compatible extensions"), not as branding or endorsement. Do not imply affiliation.
7. **Privacy.** Extension HTTP can carry cookies and identifiers. Document that extensions can read app data and make network requests; consider a per-extension cookie jar and a clear-data action.

---

## 11. Effort, sequencing and milestones

Estimates assume one experienced Android/Kotlin engineer familiar with the codebase; add 30–50% for review, QA and device testing.

| Milestone | Scope | Estimate | Exit criteria |
|---|---|---|---|
| **M0 — TV baseline** | Build, record behaviour, add tests | 0.5 d | Green build + baseline notes |
| **M1 — TV install safety** | `ExtensionPackage`, repo parser, hash, atomic install, build deps | 1–2 d | Corrupt/hash failure leaves old install; new repo formats install |
| **M2 — TV runner parity** | `TmdbProvider`, partial links, matching, `setOnline`, subtitles | 1–2 d | Real extension smoke tests pass |
| **M3 — TV settings host** | Activity, theme, manifest, button, ProGuard | 1 d | Extension Fragment settings open/persist |
| **M4 — TV UX** | Source languages, DNS, type badges, delete confirm | 1–2 d | Preferences persist; JS users see no change |
| **M5 — TV release** | Full regression + device matrix | 1–2 d | No regressions in addons/Live TV/player |
| **M6 — Mobile host skeleton** | Models, parser, platform hook, storage, UI types | 3–5 d | CloudStream repo installs; metadata persisted; iOS/Play unaffected |
| **M7 — Mobile DEX runtime** | Bridge, loader, package validation, runner, settings host | 7–10 d | Top 5 extensions return streams; settings open |
| **M8 — Mobile integration** | Dispatch, migration, sync/wipe, error states, tests | 3–5 d | Streams appear next to addons/JS; profile switch works |
| **M9 — Mobile release** | Device matrix (API 24/26/34/36), R8 check, iOS-full check | 2–3 d | No regressions; Play flavor clean |
| **Optional P1** | Ratings prompt + tiered autoplay | 4–8 d total | Settings + tests |
| **Optional P2** | TVDB ordering | 4–8 w TV / 8–12 w mobile | Separate project plan |
| **Optional P2** | Drive backup, TV Mix, IPTV-as-addon | 5–15 d each | Per-feature plan |

**Recommended order:**

1. TV M1–M5 (immediate, low risk, high value).
2. Mobile M6–M9 (the main engineering effort).
3. P1 features only after both CloudStream runtimes are stable for a release cycle.
4. TVDB only with a separate design doc and user research.

**Go/no-go gates:**

- After M1: does a real CloudStream repo install and play? If not, stop and debug the ABI before touching UX.
- After M7: do at least 5 diverse extensions (search-based, `TmdbProvider`, extractor-heavy, settings-heavy, Cloudflare-protected) work on API 24 and API 34? If not, do not ship.
- Before release: does the Play Store build contain zero `com.lagradost` classes and zero DEX-loading code? Verify with `unzip -l`/`dexdump`.

---

## Appendix A — ReSeq CloudStream file inventory

**Host bridge (`app/src/full/java/com/lagradost/cloudstream3/`)**

| File | Purpose |
|---|---|
| `CloudStreamApp.kt` | App context, CF user agent, key/value facade |
| `AcraApplication.kt` | Context/Activity, settings key no-ops |
| `CommonActivity.kt` | Current Activity + Toast ABI |
| `MainActivity.kt` | Reload/home events (not an Activity) |
| `network/CloudflareKiller.kt` | WebView Cloudflare interceptor |
| `plugins/Plugin.kt` | `BasePlugin` subclass, resources, `openSettings`, registration helpers |
| `plugins/PluginManagerStub.kt` | `PluginData`, online plugin list |
| `syncproviders/SyncAPI.kt` | Tracker login stubs |
| `syncproviders/providers/TrackerApis.kt` | Simkl/MAL/AniList DTOs |
| `ui/home/HomeViewModel.kt` | Empty resume state |
| `utils/DataStore.kt` | SharedPreferences + Jackson |
| `utils/DataStoreHelper.kt` | Resume watching stub |
| `utils/Event.kt` | Observer set |
| `utils/UiText.kt` | `txt()` helpers |

**Core (`app/src/full/java/com/nuvio/tv/core/`)**

| File | Purpose |
|---|---|
| `plugin/PluginManager.kt` | Repo/provider lifecycle, JS + DEX dispatch |
| `plugin/PluginRuntime.kt` | QuickJS runtime |
| `plugin/cloudstream/ExternalExtensionLoader.kt` | Download/load/register DEX |
| `plugin/cloudstream/ExternalExtensionRunner.kt` | TMDB ↔ CloudStream execution |
| `plugin/cloudstream/ExternalRepoParser.kt` | Repo/list formats |
| `plugin/cloudstream/ExternalExtractorRegistry.kt` | Extractor registration |
| `plugin/cloudstream/ExtensionPackage.kt` | Hash/size/DEX validation + atomic stage |
| `plugin/cloudstream/ExtensionSettingsActivity.kt` | Fragment settings host |
| `plugin/cloudstream/TvTypeExtensions.kt` | `TvType` → `movie`/`tv` |
| `plugin/PluginEpisodeRequestRules.kt` | Absolute/season-episode matching |
| `plugin/SourceLanguages.kt` | Language normalization/filter |
| `plugin/PluginSafety.kt` | Known-risky name guard (replace with consent) |
| `plugin/PluginProviderPriority.kt`, `PluginMediaType.kt`, `TestDiagnostics.kt` | Provider ordering, type mapping, diagnostics |
| `runtime/PluginRuntimeHooks.kt` | Lazy Conscrypt/NiceHttp/DNS/Activity |
| `runtime/ExtensionDns.kt` | SYSTEM/Cloudflare/Google/Quad9 |

**Domain/data/UI**

| File | Purpose |
|---|---|
| `domain/model/Plugin.kt` | `RepositoryType`, `PluginRepository`, `ScraperInfo`, `ExternalPluginEntry`, binge groups |
| `domain/model/Stream.kt`, `Subtitle.kt`, `StreamContentContext.kt` | Stream mapping |
| `data/local/PluginDataStore.kt` | Per-profile repos/providers/preferences |
| `data/repository/StreamRepositoryImpl.kt` | Plugin stream merge |
| `ui/screens/plugin/{PluginScreen,PluginViewModel,PluginUiState}.kt` | UI |
| `ui/screens/stream/{StreamScreen,StreamProviderGrouping}.kt` | Provider display |
| `ui/screens/player/PlayerRuntimeControllerStreams.kt` | Plugin play stats |
| `ui/navigation/{Screen,NuvioNavHost}.kt`, `settings/*` | Entry points |
| `app/src/full/AndroidManifest.xml`, `app/src/full/res/values/extension_settings.xml` | Activity + theme |
| `proguard-cloudstream-settings.pro` | Fragment/AppCompat/Material keeps |

---

## Appendix B — TV delta matrix (file-by-file)

| ReSeq path | TV path | Status | Notes |
|---|---|---|---|
| `core/plugin/cloudstream/ExtensionPackage.kt` | — | **missing** | copy |
| `core/plugin/cloudstream/ExtensionSettingsActivity.kt` | — | **missing** | copy |
| `core/plugin/cloudstream/ExternalExtensionLoader.kt` | same | divergent | merge; keep `loadExtensionWithDiagnostics`/`getApi` |
| `core/plugin/cloudstream/ExternalExtensionRunner.kt` | same | divergent | replace internals; keep `execute`/`executeWithDiagnostics` |
| `core/plugin/cloudstream/ExternalRepoParser.kt` | same | older | merge |
| `core/plugin/cloudstream/ExternalExtractorRegistry.kt` | same | identical | none |
| `core/plugin/cloudstream/TvTypeExtensions.kt` | same | identical | none |
| `core/plugin/PluginManager.kt` | same | older | merge |
| `core/plugin/SourceLanguages.kt` | — | **missing** | copy |
| `core/runtime/PluginRuntimeHooks.kt` | same | older | add DNS eviction |
| `core/runtime/ExtensionDns.kt` | — | **missing** | copy |
| `data/local/PluginDataStore.kt` | same | older | add languages/DNS/atomic commit |
| `domain/model/Plugin.kt` | same | older | add `fileHash`, `@JsonClass` |
| `ui/screens/plugin/PluginScreen.kt` | same | older | merge UI |
| `ui/screens/plugin/PluginViewModel.kt` | same | older | merge events |
| `ui/screens/plugin/PluginUiState.kt` | same | older | add fields |
| `app/src/full/AndroidManifest.xml` | same | no activity | add |
| `app/src/full/res/values/extension_settings.xml` | — | **missing** | copy |
| `proguard-cloudstream-settings.pro` | — | **missing** | copy |
| `gradle/libs.versions.toml` | same | older | add ksoup/DoH/material |
| `app/src/testFull/.../cloudstream/*` | one compat test | **partial** | port 5 unit tests |

---

## Appendix C — CloudStream ABI checklist

Classes/methods that must resolve for a typical extension (verified against `library-android-v4.7.0.aar` and ReSeq's bridge):

- `com.lagradost.cloudstream3.plugins.BasePlugin` (AAR) — `filename`, `registerMainAPI`, `registerExtractorAPI`
- `com.lagradost.cloudstream3.plugins.CloudstreamPlugin` (annotation, AAR)
- `com.lagradost.cloudstream3.APIHolder` (AAR) — `allProviders`, `removePluginMapping`
- `com.lagradost.cloudstream3.MainAPI` (AAR) — `search`, `load`, `loadLinks`, `name`, `lang`, `mainUrl`
- `com.lagradost.cloudstream3.*LoadResponse`, `Episode`, `SubtitleFile`, `TvType`, `SearchResponse`
- `com.lagradost.cloudstream3.metaproviders.TmdbProvider` (AAR)
- `com.lagradost.cloudstream3.utils.ExtractorApi`, `ExtractorLink`, `ExtractorLinkType`, `Qualities`, `extractorApis`, `loadExtractor`
- `com.lagradost.nicehttp.NiceResponse`, `app`, `ignoreAllSSLErrors`
- `com.lagradost.api.setContext`
- Host bridge: `CloudStreamApp`, `AcraApplication`, `CommonActivity`, `MainActivity`, `DataStore`, `DataStoreHelper`, `UiText`, `Event`, `CloudflareKiller`, `Plugin`, `PluginManager`, `SyncAPI`, `TrackerApis`, `HomeViewModel`

**Known ABI traps**

- Extensions call `runBlockingK` (coroutines 1.11) — older coroutines lack it.
- Some call `nextElementSibling()`/`previousElementSibling()` — present in ksoup 0.2.6 (`Node`), but ReSeq pins 0.2.4; verify with your extension set.
- Some use `org.jsoup` directly; Ksoup is **not** a substitute.
- Some use Jackson annotations/classes; add Jackson in the full build.
- `Toast` ABI differs between old/new Kotlin; ReSeq's `CommonActivity` provides three overloads including `$default`.
- `Plugin.load()` overloads: `BasePlugin` (no-arg), `Plugin(Context)`, `Plugin(Activity?)`, and reflective wrappers.

---

## Appendix D — Repo formats

**CloudStream `repo.json`:**

```json
{
  "name": "Example Repository",
  "description": "Example only",
  "manifestVersion": 1,
  "pluginLists": [
    "https://example.com/plugins.json"
  ]
}
```

**CloudStream `plugins.json`:**

```json
[
  {
    "name": "Example Provider",
    "internalName": "exampleprovider",
    "description": "Example only",
    "version": 1,
    "apiVersion": 1,
    "status": 1,
    "authors": ["example"],
    "tvTypes": ["Movie", "TvSeries"],
    "iconUrl": "https://example.com/icon.png",
    "url": "https://example.com/exampleprovider.cs3",
    "fileSize": 123456,
    "fileHash": "sha256-<64 hex chars>"
  }
]
```

**Nuvio JS `manifest.json` (existing mobile/TV format):**

```json
{
  "name": "Example JS Repository",
  "version": "1.0.0",
  "description": "Example only",
  "scrapers": [
    {
      "id": "example",
      "name": "Example",
      "version": "1.0.0",
      "filename": "example.js",
      "supportedTypes": ["movie", "tv"]
    }
  ]
}
```

The two formats must remain distinguishable. Do not try to coerce one into the other.

---

## Appendix E — Verification commands

**TV app**

```bash
cd "/Users/robbdeeze/Documents/projects/Lucid-Nuvio-TV-IPTV"
./gradlew :app:assembleFullDebug
./gradlew :app:assemblePlaystoreDebug
./gradlew :app:testFullDebugUnitTest --tests '*Plugin*' --tests '*cloudstream*'
# optional device suite
./gradlew :app:connectedFullDebugAndroidTest
```

**Mobile app**

```bash
cd "/Users/robbdeeze/Documents/projects/Lucid Nuvio IPTV+ Mobile"
./gradlew :composeApp:testAndroidHostTest -Pnuvio.android.distribution=full
./gradlew :androidApp:assembleFullRelease -Pnuvio.android.distribution=full
./gradlew :androidApp:assemblePlaystoreRelease -Pnuvio.android.distribution=playstore
# iOS-full compile check (macOS)
./gradlew :composeApp:compileKotlinIosSimulatorArm64 -Pnuvio.ios.distribution=full
```

**Verify the Play Store APK is clean**

```bash
unzip -l app-playstore-release.apk | grep -E 'classes.*dex'   # normal
unzip -p app-playstore-release.apk classes.dex | strings | grep -i lagradost | head
# Expected: no output
```

**Inspect extension packages**

```bash
unzip -l provider.cs3 | head          # classes.dex, manifest.json, res/...
unzip -p provider.cs3 manifest.json
```

---

## Appendix F — Glossary

| Term | Meaning |
|---|---|
| `.cs3` | CloudStream extension package (ZIP or raw DEX) |
| `BasePlugin` | CloudStream class extensions extend; registers providers/extractors |
| `MainAPI` | A provider: search, load metadata, load links |
| `ExtractorApi` | A hoster resolver used by `loadExtractor` |
| `APIHolder` | CloudStream global provider registry |
| `TmdbProvider` | A CloudStream provider that takes TMDB IDs/URLs instead of text search |
| `bingeGroup` | Identity string used to auto-select the same provider/quality for the next episode |
| `DexClassLoader` | Android loader for external DEX |
| `NUVIO_JS` / `EXTERNAL_DEX` | Repository types in ReSeq's plugin model |
| `full` / `playstore` | Distribution flavors; plugins/CloudStream only in full |

---

### Bottom line

- **TV:** you already ship CloudStream extensions. Upgrade the subsystem in place (hash/atomic installs, repo formats, runner parity, settings host, preferences). Do not re-port it.
- **Mobile:** add a second backend behind `PluginRepository`, keep the JS runtime and the entire stream/player pipeline untouched, and keep all DEX code in `androidFull`. This is a contained 3–4 week project with a clear test plan.
- **Everything else in ReSeq** is either already present in your apps or optional. Port the small behavioural features (ratings prompt, tiered autoplay) if users ask; treat TVDB ordering as a separate product decision.

---

## Appendix G — Mobile implementation status (2026-11-22)

This appendix records the first implementation slice on branch `feat/cloudstream-extensions` in `Lucid Nuvio IPTV+ Mobile`. It is intentionally separate from the forward-looking plan above.

**Implemented**

- `PluginRepositoryType` (`NUVIO_JS` / `EXTERNAL_DEX`) and additive `type`/`extensionHash` fields on repository/scraper models. Old stored JSON loads as JavaScript.
- CloudStream repository parsing (`repo.json` + `pluginLists`, direct `plugins.json`, GitHub blob/raw links, `cloudstreamrepo://`, redirect-aware relative URLs).
- Android-full DEX host: `DexClassLoader`, plugin discovery (`manifest.pluginClassName` + `@CloudstreamPlugin` scan + reflective fallback), extractor registration, hash/size/ZIP validation, atomic install, read-only DEX, profile-scoped storage, per-extension settings Activity, Conscrypt/NiceHttp/activity lifecycle init.
- `PluginRepository` dispatch: JS plugins keep QuickJS, DEX providers go through the Android host. External installs are parallelised (4 at a time) and skip unchanged packages.
- Stream pipeline: DEX results return the existing `PluginRuntimeResult`, so `StreamsRepository`, autoplay, player handoff and playback are unchanged. Plugin groups now receive badge presentation in both the stream list and in-player source panel; debrid semantics were deliberately left addon-only.
- UI: CloudStream/JavaScript type badges, repo hint, per-provider gear (lazily checks settings), "no settings" and "no streams returned" feedback.
- A blocking one-time consent dialog appears before the first non-manifest repository install; acceptance is stored in `nuvio_plugins`.
- Short-code input (`cspr`, `0094`, etc.) resolves through `cutt.ly` before repository parsing.
- Test Provider opens a dialog with a search title, an optional TMDB ID, and a "Use first search result" switch. The switch bypasses fuzzy title matching so custom providers can be validated without knowing a catalog title.
- Account wipe removes `cs_extensions*`, `cs_dex_cache` and `cs_http_cache`.
- Search-based providers receive the current title from the stream screen; the test button uses sample titles so it works without a TMDB key.

**Behavior note for real extensions**

CloudStream extensions are being used as **stream providers for the title the user selected in Nuvio**, not as a full CloudStream catalogue browser. A search-based extension receives the selected title (or the TMDB ID for `TmdbProvider` packages); if that title does not exist on the provider's site, the matcher correctly returns no streams. The synced RDNU repo is a niche shock/viral catalogue: its `crazyshit` search returned 30 site results for "The Matrix", but none matched the requested title, so the test ended with no streams. The Test Provider action now lets you enter a title the provider actually carries (and an optional TMDB ID), and logs the no-match reason. Full CloudStream home/search/provider browsing is a separate feature and is not part of this slice.

**Validated**

- Focused host tests pass: `ExternalRepoParserTest` (4), `ExtensionPackageTest` (2), `PluginStateMigrationTest` (2), `PluginBadgePresentationTest` (2).
- On a physical OnePlus 9 Pro (Android 14, arm64) via ADB: repo install, CloudStream badge, provider listing, refresh to a new package version, atomic replacement, DEX load, and no crash for a synthetic package.
- Synthetic extension compiled against CloudStream 4.7.0 and packaged as `.cs3`. End-to-end test succeeded: search returned 1 result, matched the requested title, `loadLinks` returned 1 direct link + 1 subtitle, and the UI displayed "Test results (1)".
- A real synced CloudStream repository (`lucidconviction/RdNutz-Repo`, 15 providers) installed and loaded in the app; one provider reported `1 providers, 0 extractors`. The extension ABI and host bridge work with real packages, not just the synthetic one.
- A real custom extension (`crazyshit`) was tested with **Use first search result**: search returned 30 site results, the first result was loaded, and `loadLinks` returned 1 direct link. The user's custom extensions work through the host; the only earlier blocker was the fixed sample title not existing on the site.
- Per-extension gear opens the lazy settings check; a package without settings shows "This extension does not expose a settings screen."
- `assemblePlaystoreDebug` builds successfully with the CloudStream dependency and DEX code excluded from that flavor.
- Full `:composeApp:testAndroidHostTest` suite passes (308 test suites) with the new code.

**Still to validate / next**

- Run a real public extension that returns streams (search-based and `TmdbProvider`), a real extractor-heavy package, and one with a settings Fragment. The synced repo is available for this; a mainstream-title provider would make the test more representative.
- Validate playback from the stream screen, source grouping, autoplay and subtitle handoff.
- iOS-full compile check is blocked locally by the missing `nuvio-engine` XCFramework; the `iosFull` no-op host is in place and the code has no Android imports in `fullCommonMain`.
- Decide whether plugin torrents need the optional debrid-parity phase (§7.6).

---

## Appendix H — TV implementation status (2026-11-23)

Branch `feat/cloudstream-delta` in `Lucid-Nuvio-TV-IPTV` implements the report's TV delta (M1–M4) as four commits.

**Implemented**

- **Install safety (M1):** `ExtensionPackage.stageExtension` verifies the 10 MB cap, optional `sha256-` hash, raw DEX or ZIP `classes.dex`, writes a temp file beside the target and replaces it with `Os.rename`. A failed refresh leaves the installed package playable.
- **Repository formats (M1):** ReSeq's parser adds `repo.json` directory fallback, `pluginLists` with per-list failure isolation, redirect-aware relative URLs, GitHub blob/raw/tree rewriting, `cloudstreamrepo://` / `cs.repo/?` unwrapping, newest duplicate by `internalName`, and `Other` type eligibility. `ExternalPluginEntry.fileHash` is stored.
- **Runner (M2):** every `MainAPI` in a package now runs (up to three concurrently) with a 90 s budget, results deduplicated, and one failing provider no longer hides a sibling. Partial links continue to survive the 60 s `loadLinks` cap. The legacy TmdbProvider diagnostic path now uses the real `load(json)`/`loadLinks(data)` flow instead of TmdbLink JSON.
- **CloudStream registry (M2):** `PluginManager.setOnline(...)` publishes installed packages (internal name, URL, file path, version) before extractor preload and settings probes.
- **Per-extension settings (M3):** `ExtensionSettingsActivity`, `Theme.Lucid.ExtensionSettings`, manifest entry, `settingsCallback`, lazy gear button on enabled DEX providers, Material 1.12.0 and Fragment/Material ProGuard keeps.
- **Preferences (M4):** per-profile source languages with a CloudStream preferences card, DoH resolver selection (SYSTEM/Cloudflare/Google/Quad9) with connection-pool eviction, CloudStream/JavaScript provider badges, and a repository delete confirmation. Old state loads as JavaScript.
- **Unload correctness:** refreshing or deleting a package calls `beforeUnload` and removes its providers/extractors from `APIHolder`/`extractorApis`; `CommonActivity` now matches ReSeq's Activity/Toast ABI.

**Validation**

- New tests green: `ExtensionPackageTest` (2), `ExternalRepoCompatibilityTest` (5), `MultiProviderRunnerTest` (3), `SourceLanguagesTest` (2), `PluginStateMigrationTest` (2), `CloudStreamActivityAbiTest` (1).
- `:app:assembleFullDebug` and `:app:assemblePlaystoreDebug` succeed on JDK 17.
- Full `:app:testFullDebugUnitTest` completes 2,854 tests with four failures unrelated to this change: `SubtitleCredentialScopeTest` (one), `EmbeddedSubtitleTimelineLoaderTest` (two, concurrency), and `ContinueWatchingAiringRulesTest` (date-sensitive airing rule). The first three match the failures already recorded for beta.37; none touch plugin code.

**Still to validate on a device**

- Add a real public CloudStream repo; install, refresh and remove a package; confirm hash/atomic behavior with a corrupt download.
- Play a search-based and a `TmdbProvider` extension; confirm sibling providers appear and the settings gear opens a real Fragment screen.
- Toggle source languages and DNS; confirm persistence across restart/profile switch.
- No Play Store APK check of `com.lagradost` symbols was run; the playstore flavor compiles without the plugin source set.

**Deliberately not done in this slice**

- `StreamContentContext` plumbing from the stream screen (the report's optional content hint). Matching still uses TMDB title/year and alternative titles.
- Debrid parity for plugin streams, TVDB ordering, Drive sync, and the ReSeq features marked P1/P2.
- No version bump or release; QA matrix from §6.7 and §10 still applies.