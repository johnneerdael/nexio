# Project Context

## Purpose
Nexio is an Android TV / Fire TV streaming client (`com.nexio.tv`) built around four differentiators:

- **Deterministic Autoplay** backed by benchmark-aware scoring and device-aware playback decisions.
- **Debrid-first workflows** across Real-Debrid, Premiumize, TorBox, and EasyDebrid — including Service Wrap, cached-torrent validation, and direct-link playback.
- **Enthusiast playback stack** on a forked Media3 / ExoPlayer (HDR, passthrough, Kodi-inspired IEC packer / native audiosink, DV7→DV8.1, DV autoplay fallback, ASS/SSA protected translation).
- **Account-backed ecosystem**: a Supabase-hosted account config plus a companion web portal (QR TV sign-in, synced addons, formatter selection, secret handling) that keeps multiple devices aligned.

The repo is a monorepo: the Android app (`app/`), the forked Media3 source (`media/`), the web portal (`nexio-web/`), Supabase SQL (`supabase/`), docs (`docs/`, `docs-site/`), and supporting tooling (`shadow-collector/`, `stremio-nuvio-importer/`, `tools/`, `scripts/`).

## Tech Stack

### Android app
- **Language:** Kotlin (JVM target 11); small C/C++ layer under `app/src/main/cpp/` built with CMake 3.22.1.
- **UI:** Jetpack Compose (Compose BOM `2026.01.01`), Material3, `androidx.tv:tv-material`, Navigation-Compose.
- **DI:** Hilt (+ `hilt-navigation-compose`, `androidx.hilt:hilt-work`) with KSP.
- **Async:** kotlinx.coroutines + Flow; WorkManager for background jobs.
- **Networking:** Retrofit + Moshi (+ `kotlinx.serialization` for Supabase/JSON payloads), OkHttp, Ktor OkHttp engine (Supabase client).
- **Persistence:** DataStore Preferences, disk-backed caches for Trakt / synthetic home / VOD.
- **Playback:** forked Media3 / ExoPlayer (source mode via `USE_MEDIA3_SOURCE`), HLS/DASH/SmoothStreaming/RTSP, `media3-decoder-ffmpeg`, Kodi-inspired `media3-exoplayer-kodi-cpp-audiosink`, local AAR decoder extensions (AV1, IAMF, MPEG-H), `mpv-android-lib`, optional libdovi native path.
- **Images:** Coil (+ SVG).
- **Other:** Chaquopy (Python 3.11) for select helpers, NanoHTTPD + ZXing for addon management / QR flows, JCTools for lock-free playback instrumentation, JavaScriptEngine for addon sandboxes, JankStats + Compose runtime-tracing for profiling.
- **Backend:** Supabase (Auth, Postgrest, Storage) — SQL under `supabase/` is authoritative.
- **Build:** Gradle (Kotlin DSL) with version catalog (`gradle/libs.versions.toml`); `compileSdk`/`targetSdk` 36, `minSdk` 26. Baseline Profiles plugin enabled.

### Build variants
- Flavors: `arm64`, `armv7`, `universal` (Play publish tasks pin to `universal` + arm64-only).
- Build types: `debug` (debug-only appId `com.nexiodebug.tv`, `SUPABASE_*` from `local.dev.properties`), `release` (minify on, signed, `SUPABASE_*` from `local.properties`), `releaseProfileable`.
- Common commands (prefer `arm64` locally):
  - `./gradlew assembleArm64Debug` — fastest dev build
  - `./gradlew assembleUniversalRelease` — release APK
  - `./gradlew bundlePlayRelease` — Play bundle
  - `./gradlew testArm64DebugUnitTest` (optionally `--tests "FQN.Class[.method]"`)
  - `./gradlew lintArm64Debug`

### Web portal (`nexio-web/`)
Next.js / TypeScript companion surface that shares the account-config contract with the app (addons, catalog order, formatters, secrets, TV login).

### Docs site (`docs-site/`)
Published at `johnneerdael.github.io/nexio`.

## Project Conventions

### Code style
- Kotlin, idiomatic + Compose-first. Follow the existing package layout:
  - `com.nexio.tv.core.*` — cross-cutting infrastructure (auth, di, network, player, stream, sync, metadata, tmdb, tvdb, poster, scheduler, search, server, qr, logging, image, anime, locale, util, ui).
  - `com.nexio.tv.data.*` — `local/`, `remote/`, `mapper/`, `repository/`, plus feature-specific `trakt/`, `trailer/`.
  - `com.nexio.tv.domain.*` — pure-Kotlin `model/` + `repository/` interfaces. **Keep domain free of Android framework dependencies.**
  - `com.nexio.tv.ui.*` — `screens/`, `components/`, `navigation/`, `screensaver/`, `theme/`, `util/`.
  - `com.nexio.tv.workers`, `com.nexio.tv.updater` for WorkManager jobs and the GitHub-Releases in-app updater.
- Preserve existing naming patterns; do not introduce new libraries, DI patterns, or abstractions unless the task clearly justifies it.
- Compose stability is tuned via `compose_stability_config.conf`; Compose compiler metrics land in `app/build/compose_metrics` + `compose_reports`.
- Prefer fixing the root cause over workarounds. Keep changes scoped to the task.

### Architecture patterns
- Layered: **UI (Compose + ViewModel) → domain (models + repository contracts) → data (repositories, remote clients, local stores) → core infrastructure**.
- Hilt provides app-wide DI; Compose screens use `hilt-navigation-compose` for ViewModel scoping.
- Repositories expose Flows; ViewModels map to Compose state. Background/long-running work uses WorkManager (Hilt-integrated).
- Account config sync is a versioned contract (`AccountConfigSyncContract`) shared with the web portal; the Supabase schema is the source of truth for payload shape and secret allowlists.
- Playback is benchmark-driven: Deterministic Autoplay, Config/Transport benchmarks, Service Wrap, DV-aware fallback, disk-backed VOD cache, stream-reuse cache. Audio path includes a Kodi-inspired IEC packer with AC3/E-AC3/DTS family passthrough; **TrueHD is not considered production-stable**.
- ASS/SSA subtitles go through a protected translation pipeline (tokenize → translate visible text only → validate placeholders → reconstruct → render via libass/assrender). Generic Media3 cue translation is disabled for ASS/SSA to preserve positioning/movement/drawing/karaoke.

### Testing strategy
- Primary: `testArm64DebugUnitTest` — JUnit 4 + MockK + kotlinx-coroutines-test + Robolectric 4.13 + OkHttp `mockwebserver` + `media3-test-utils` + `work-testing`.
- Unit tests run with `returnDefaultValues = true`, `maxHeapSize = 2g`, and `forkEvery = 50` to avoid JVM state buildup; `workingDir` is the repo root.
- Instrumented: `androidTestImplementation` uses Compose UI test (JUnit4) and AndroidX Benchmark (WP10 playback tracer microbenchmark).
- Keep focused tests around sync payload construction, routing, serialization, playback/autoplay scoring, and subtitle pipeline semantics.

### Git / change workflow
- Trunk-based on `main`. Commit style: conventional-ish, scope-led (`fix(player): …`, `feat(detail): …`, `refactor(sync): …`). Small, targeted commits.
- Non-trivial work goes through **OpenSpec** (`openspec/`):
  - Scaffold `proposal.md`, `tasks.md`, and spec deltas under `openspec/changes/<verb-led-id>/`.
  - Use `#### Scenario:` blocks for every requirement.
  - Validate with `openspec validate <change-id> --strict` before implementation handoff.
  - Archived changes live under `openspec/changes/archive/`; persisted specs under `openspec/specs/`.
- Preserve CLAUDE.md and the `<!-- OPENSPEC:START/END -->` block in `AGENTS.md` so `openspec update` can refresh them.

## Domain Context
- **Debrid ecosystem:** Real-Debrid, Premiumize, TorBox, EasyDebrid — each with distinct link-resolution, caching, and probing semantics. Service Wrap unifies behavior.
- **Metadata providers:** TMDB + TheTVDB are core (bundled keys for non-commercial use; users may supply their own). Enrichment also from MDBList, OMDb/IMDb (episode ratings via `IMDB_API_URL` / `IMDB_WS_URL`), RPDB, TOP Posters.
- **Trakt** is treated as a real system layer: device auth, Continue Watching, Up Next, progress sync, scrobble, check-in, watchlist/lists, trending/popular/recommended/calendar, disk-backed startup.
- **Trailers:** TMDB + authenticated YouTube Trailer login; trailer screensaver / ambient flows.
- **Anime ID map:** generated at build time via `generateAnimeIdMap` (Fribb anime-list, Trakt extended anitrakt movies, Kitsu IMDb mapping) and bundled as `src/main/assets/anime/anime-id-map.json`.
- **Shadow data collection:** opt-in telemetry sink (`SHADOW_DATA_COLLECTION_BASE_URL`, default `https://datacollection.nexioapp.org`) with a dashboard (`shadow-collector/`).
- **In-app updater:** GitHub Releases, owner `johnneerdael`, repo `nexio`.
- **Legal:** Nexio is a client only; it does not host or distribute media. All sources come from user-installed addons/services.

## Important Constraints
- Keep **account-config sync backward compatible** across contract versions whenever possible.
- Treat **Supabase SQL as the source of truth** for sync payload and secret allowlists.
- Keep **contract-scaffolding changes isolated from runtime feature work** unless a runtime hook is required for compilation.
- Keep **domain code Android-free**.
- Preserve focused tests around sync payload construction, routing, and serialization; preserve autoplay-scoring and ASS/SSA pipeline tests when touching playback.
- **Do not treat TrueHD as production-stable.**
- Prefer `arm64` builds for local work; Play-store bundles go through `bundlePlayRelease` (universal, arm64-only).
- Do not commit secrets. Build-time secrets resolve from `local.properties` (release) and `local.dev.properties` (debug); examples in `local.example.properties`.

## External Dependencies
- **Supabase** — Auth / Postgrest / Storage; schema under `supabase/`.
- **Real-Debrid / Premiumize / TorBox / EasyDebrid** — debrid providers.
- **Trakt, Simkl** — library / progress / scrobble (Trakt primary).
- **TMDB, TheTVDB, MDBList, OMDb / IMDb, RPDB, TOP Posters** — metadata & artwork.
- **YouTube Data API** — authenticated trailer playback.
- **GitHub Releases** — in-app updater channel (`johnneerdael/nexio`).
- **Shadow data collection service** — `datacollection.nexioapp.org`.
- **Forked Media3 / ExoPlayer** (`media/`) and local decoder AARs (AV1, IAMF, MPEG-H) — consumed via `useMedia3Source` toggle.
- **libdovi** — optional native Dolby Vision path, gated by `DOVI_NATIVE_ENABLED` / `DOVI_ENABLE_REAL_LINK` and local prebuilt paths.
- **Chaquopy** Python 3.11 runtime for select helpers.
- **API blueprints** committed at repo root (`trakt.apib`, `simkl.apib`, `mdblist.apib`, `kitsu.apib`, `rpdb.apib`) — use these before web-searching external docs.
