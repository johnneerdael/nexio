# Product Definition

> Nexio — a premium, benchmark-driven Android TV / Fire TV streaming client with an account-backed companion portal.

## Vision

Deliver a lean-back TV streaming experience that feels premium — less menu friction, smarter autoplay, deeper debrid and Trakt workflows, and an account ecosystem that keeps every device aligned.

## Problem Statement

Existing Android TV media hubs make users pick streams blindly, store debrid credentials without tuning them, ignore device decode/HDR/audio nuances, and scatter settings across addons and devices. Enthusiast users end up hand-curating streams, re-configuring each device, and working around Dolby Vision and passthrough issues that the app should handle automatically.

Nexio exists for users who care about more than "open a title and hope." It targets:

- **Lean-back playback** with less manual stream picking.
- **High-quality debrid setups** that need real tuning, not guesswork.
- **Home-theater users** who care about HDR, passthrough, and device-specific behavior.
- **Trakt-heavy workflows** with real Continue Watching, Up Next, and list depth.
- **Users who want one account-backed ecosystem** instead of scattered per-device state.

## Target Users

1. **Power-user cord-cutters on Android TV / Fire TV / shield boxes** running debrid (Real-Debrid / Premiumize / TorBox / EasyDebrid).
2. **Home-theater enthusiasts** with AVRs / soundbars relying on AC3/E-AC3/DTS-family passthrough and HDR/DV-aware playback.
3. **Trakt-heavy viewers** who want scrobble, Continue Watching, Up Next, lists, calendar, and disk-backed startup.
4. **Multi-device households** that want QR-based TV sign-in, synced addons, catalog ordering, formatter selection, and secret handling from a companion web portal.
5. **Self-host / addon-savvy users** importing from Stremio-style addons (`stremio-nuvio-importer`) and curating their own catalogs.

## Success Criteria

- **Autoplay hit-rate:** Deterministic Autoplay produces a stream that plays through without manual re-selection a high percentage of attempts, including on WEB-DL Dolby Vision edge cases (DV-aware fallback kicks in instead of a bad DV→SDR handoff).
- **Playback stability:** minimal audio underrun / buffer-starvation events on the Kodi-inspired IEC packer / native audiosink path; TrueHD explicitly tracked as not-yet-production-stable.
- **Debrid reliability:** Service Wrap resolves playable links for cached candidates with measured benchmark-aware ranking; parallel-connection and chunk-size tuning improves remux playback on large files.
- **Account sync correctness:** `AccountConfigSyncContract` v7 reconciles app ↔ web portal changes with delta-based push and conflict detection; no silent data loss across devices.
- **Trakt depth:** real Continue Watching / Up Next / scrobble / check-in / watchlist / lists / trending / popular / recommended / calendar rails feel first-class, with disk-backed startup staying snappy.
- **Metadata quality:** TMDB + TheTVDB as primary, enriched by MDBList, OMDb/IMDb (episode ratings), RPDB, and TOP Posters; trailer-first browsing with authenticated YouTube trailer login.
- **Subtitle fidelity:** ASS/SSA passes through the protected translation pipeline with positioning / movement / drawing / karaoke intact; generic Media3 cue translation stays disabled for ASS/SSA.
- **Release cadence:** in-app updater (GitHub Releases, `johnneerdael/nexio`) delivers updates without Play-Store gate on universal / arm64 builds, while `bundlePlayRelease` supports Play distribution.

## Core Features

1. **Deterministic Autoplay** — benchmark-aware scoring, device-aware playback decisions, DV-aware fallback for problematic WEB-DL Dolby Vision.
2. **Debrid workflows** — Real-Debrid, Premiumize, TorBox, EasyDebrid with Service Wrap, cached-torrent validation, direct-link resolution, debrid-aware library behavior.
3. **Benchmarking & transport tuning** — Config Benchmark (connection / chunk-size profiles), Direct-vs-Optimized Benchmark, parallel connections, disk-backed VOD cache, stream-reuse / last-link cache, transport diagnostics.
4. **Stream cleanup** — grouping, deduplication, uniform formatting, wrong-episode / wrong-year filtering, parser-backed metadata rendering, optional WEB-DL Dolby Vision filtering.
5. **Enthusiast playback & audio path** — forked Media3/ExoPlayer, Kodi-inspired IEC packer / native audiosink, AC3/E-AC3/DTS/DTS-HD/DTS:X passthrough + DTS-HD core fallback, DV7→DV8.1 conversion, tunneled playback, frame-rate / resolution matching, audio-delay supervision for affected firmware.
6. **Deep Trakt integration** — device auth, Continue Watching, Up Next, progress sync, scrobble, check-in, watchlist/lists, trending/popular/recommended/calendar, disk-backed startup.
7. **Metadata & discovery** — TMDB, TheTVDB, MDBList, OMDb/IMDb episode ratings, RPDB, TOP Posters, trailer-first browsing, authenticated YouTube Trailer login, trailer screensaver / ambient flows.
8. **Account portal & cross-device control plane (`nexio-web/`)** — QR TV sign-in, linked-device flows, synced integration settings, addon management, catalog ordering / visibility, formatter selection, secure secret handling, migration / import workflows.
9. **Protected ASS/SSA subtitle pipeline** — tokenize ASS structure, translate only visible language text (Gemini / translation provider), validate placeholders, reconstruct events, render via libass / assrender; generic Media3 cue translation disabled for ASS/SSA.
10. **In-app updater** — GitHub Releases channel (`johnneerdael/nexio`) for universal / arm64 builds outside Play Store distribution.

## Non-Goals

- **Hosting or distributing media content.** Nexio is a client; all sources come from user-installed addons / services the user is authorized to use.
- **Phone / tablet UI.** Leanback / TV-first; no dedicated handheld layout.
- **iOS / Apple-TV support.** Android TV + Fire TV only.
- **Production-stable TrueHD passthrough.** Explicitly tracked as not reliable today; do not promise parity with AC3/E-AC3/DTS family.
- **Generic Media3 cue translation for ASS/SSA.** Must stay disabled to preserve positioning / movement / drawing / karaoke.
- **Inline storage of addon secrets in the sync payload.** Addon credentials go through the `secretRef` allowlist; never round-trip raw secrets in `AccountConfigSyncPayload`.
- **Third-party OTT provider integrations** (Netflix / Disney+ / Prime etc.) — out of scope; Nexio is debrid + addon + Trakt.
- **First-party content catalog.** We do not curate a house catalog; catalogs come from user-installed addons and metadata providers.

## Constraints

### Platform & build
- `minSdk` 26, `compileSdk` / `targetSdk` 36; Kotlin (JVM 11) + small C/C++ (CMake 3.22.1) native layer.
- Flavors `arm64`, `armv7`, `universal`; Play-store bundle (`bundlePlayRelease`) is universal + arm64-only.
- Debug appId is `com.nexiodebug.tv` (release is `com.nexio.tv`).

### Backend & contracts
- **Supabase SQL is the source of truth** for the account-config sync payload and secret allowlists.
- **`AccountConfigSyncContract` is versioned (currently v7)** — keep backward compatible whenever possible; contract-scaffolding changes stay isolated from runtime feature work unless a runtime hook is required for compilation.
- Delta-path push (`observeAccountConfigSyncChangedPaths`) must not regress to full-payload push without an explicit version bump.

### Domain / architecture
- **Domain code must be free of Android framework dependencies.**
- Preserve existing package layering (`ui / domain / data / core`) and naming patterns; do not introduce new libraries, DI patterns, or abstractions without clear justification.
- Prefer fixing root causes over workarounds.

### Playback
- **TrueHD is not production-stable** — gate it accordingly.
- ASS/SSA goes through the protected translation pipeline only; generic Media3 cue translation stays disabled for ASS/SSA.
- Deterministic Autoplay must keep DV-aware fallback wired for WEB-DL Dolby Vision.

### Third-party dependencies
- Trakt, Simkl, Real-Debrid, Premiumize, TorBox, EasyDebrid, TMDB, TheTVDB, OMDb/IMDb, MDBList, RPDB, TOP Posters, YouTube Data API, GitHub Releases (updater), `datacollection.nexioapp.org` (opt-in shadow telemetry).
- TMDB / TheTVDB shipped with bundled keys for non-commercial usage; users may supply their own to use their own quota.

### Secrets & config
- No secrets committed. Release secrets resolve from `local.properties`; debug secrets from `local.dev.properties` (examples in `local.example.properties`).
- Shadow data collection is opt-in and routed through `SHADOW_DATA_COLLECTION_BASE_URL` (default `https://datacollection.nexioapp.org`).

### Legal
- Nexio does not host or distribute media. It is a client only; all content access is the user's responsibility via addons / services they are authorized to use.
