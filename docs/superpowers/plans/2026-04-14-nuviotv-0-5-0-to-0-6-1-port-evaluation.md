# NuvioTV 0.5.0-0.6.1 Port Evaluation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Evaluate NuvioTV release patches from `0.5.0-beta` through `0.6.1-beta`, plus PR #1335 and PR #1279, and decide which patches should be adapted into Nexio.

**Architecture:** Treat upstream as source material, not as a cherry-pick target. Nexio has diverged into `com.nexio.tv`, has custom trailer/auth/cache work, vendor-agnostic subtitle translation, Trakt/Simkl/MDBList tracking surfaces, and playback-cache/autoplay work, so ports should be behavior-first and validated against Nexio's current files.

**Tech Stack:** Android Kotlin, Jetpack Compose for TV, Media3/ExoPlayer, Nexio trailer helper, Trakt API integration, Kotlin coroutines, DataStore, Hilt.

---

## Findings

The refreshed local NuvioTV checkout exposes ten tags in the requested range, not twelve:

`0.5.0-beta`, `0.5.1-beta`, `0.5.2-beta`, `0.5.3-beta`, `0.5.4-beta`, `0.5.5-beta`, `0.5.6-beta`, `0.5.7-beta`, `0.6.0-beta`, `0.6.1-beta`.

Nexio is currently versioned independently as `0.46` in `app/build.gradle.kts`. The shared merge-base between Nexio `main` and NuvioTV `dev` is old, so exact patch-id matching is not useful. Use feature-level comparisons.

## Priority Port Candidates

### P0: Evaluate, likely adapt

- Search history from `ea22ba84` in `0.5.7-beta`.
  - Upstream files: `app/src/main/java/com/nuvio/tv/data/local/SearchHistoryDataStore.kt`, `app/src/main/java/com/nuvio/tv/ui/screens/search/SearchScreen.kt`, `SearchViewModel.kt`, `SearchUiState.kt`, `SearchEvent.kt`.
  - Nexio target files: `app/src/main/java/com/nexio/tv/ui/screens/search/SearchScreen.kt`, `SearchViewModel.kt`, `SearchUiState.kt`, `SearchEvent.kt`.
  - Current Nexio search does not appear to have persisted recent search history.

- PR #1335 player surface churn work.
  - Upstream PR: https://github.com/NuvioMedia/NuvioTV/pull/1335
  - Upstream commits: `dab780b1`, `e96ef17e`, `bf910bdc`, `ec69d9ee`.
  - Nexio already has `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerVideoSurface.kt` from `3369b116f`, with mutation planning and a separate `PlayerPlaybackProgressUiState`.
  - Still evaluate the remaining delta: keyed `LaunchedEffect` / `DisposableEffect` side effects, stable view IDs, surface-sync workaround, and any split timeline-flow idea not already covered.

- Trakt / Continue Watching correctness fixes.
  - `78a3520e` (`0.6.0-beta`): avoid overwriting `lastWatched` and query Trakt progress in parallel.
  - `0bb34290` (`0.6.1-beta`): avoid TMDB enrichment infinite loop, prevent newer watched items overwriting in-progress rows, reduce CW flicker.
  - `e0d48abb` (`0.6.1-beta`): keep cached next-up items until fresh pipeline processing completes.
  - `557f00be`, `0082521b`, `45e0b565`, `f8244157` (`0.5.5-beta`): CW handling for Trakt/Nuvio sync, TMDB+IMDB watched matching, avoid old series for Trakt users, remote deletion plus >1000 watched-item pagination.
  - Nexio target files: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`, `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`, `HomeViewModelContinueWatchingRuntimePipeline.kt`, and Trakt mutation/outbox files under `app/src/main/java/com/nexio/tv/data/repository/trakt/`.

- Trailer resolution/cache hardening.
  - `cc10886d` (`0.5.4-beta`): 3-hour TTL cache for YouTube trailers.
  - `9712f664` (`0.5.7-beta`): cache YouTube `visitor_data` + API key to reduce 429s.
  - `76a28c8f` (`0.6.0-beta`): fix stale trailer playing in hero when focusing collection folders.
  - Nexio target files: `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`, `app/src/main/java/com/nexio/tv/data/trailer/helper/TrailerHelperCache.kt`, `YouTubeTrailerCookieStore.kt`, `HomePosterTrailerOptions.kt`, and relevant home screen trailer callbacks.
  - Nexio already has a richer authenticated YouTube trailer helper, so port only the cache/race behavior if missing.

- Playback reliability edge fixes.
  - `753d8e91`, `cb5ec2a0`, `834729f6`, `f6091995` (`0.6.0-beta`): ExoPlayer recovery, networking, silent retry, readable audio labels, user-friendly playback errors.
  - `acc158db` (`0.5.4-beta`): fix infinite buffering after audio track switch.
  - `6e32d959`, `44965795` (`0.5.7-beta`): playback speed compatibility, DTS/passthrough speed handling, exit stability.
  - `8e055575` (`0.6.1-beta`): calculate `endsAt` using playback speed.
  - Nexio target files: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`, `PlayerRuntimeControllerPlaybackEvents.kt`, `PlayerRuntimeControllerTrackSelection.kt`, `PlayerRuntimeControllerTracks.kt`, `PlayerRuntimeControllerInitialization.kt`, and `PlayerScreen.kt`.

### P1: Evaluate after P0

- `655a62f5` (`0.6.1-beta`): prioritize addon results over plugins. Nexio has removed or heavily reshaped plugin support, but the ordering policy may still map to addon-vs-service-wrap search results.
- `e48ab58a` (`0.6.1-beta`): stream source indicator not hiding after nth episode.
- `fdf66a64`, `b5ce191c` (`0.6.1-beta`): preserve `bingeGroup` for cached streams and improve torrent episode switching. Nexio already stores `bingeGroup` in stream/cache/navigation models, so check only for missing cached-stream propagation.
- `735b7ecf`, `0fa0c510`, `4b0c5bda`, `1ef67745` (`0.5.1-beta`, `0.5.3-beta`, `0.6.0-beta`): reuse-last-link back navigation and p2p exclusions. Nexio has deterministic autoplay and stream link cache work; behavior test before porting.
- `c25a709d`, `88295c29` (`0.5.4-beta`): D-pad repeat throttling in stream selection and player stream source panel.
- `b265cb70`, `57003a6f` (`0.5.7-beta`): search See All and one small search fix.
- `547154c1` (`0.6.0-beta`): stream source page sorting, display redundancy, UI debounce, collapsible diagnostics.
- `b067bfde`, `e149309b` (`0.5.4-beta`): addon subtitle priority by language and primary language handling.
- `7014162c`, `1dfd3c02`, `9de19126` (`0.5.3-beta`, `0.5.6-beta`): duplicate subtitle option keys and deferred secondary subtitle selection.

### P2: Product decision needed

- Collections from `0.6.0-beta`: large feature set for catalog folders, web UI management, import/export, cloud sync, custom art/GIFs, profile-specific web server behavior.
- CloudStream extension compatibility from `0.6.0-beta`: large integration surface and plugin/runtime implications.
- P2P/TorrServer work from `0.6.0-beta` and `0.6.1-beta`: legal/product risk and direct overlap with Nexio's debrid/cache strategy.
- Full libmpv internal engine series from `0.5.7-beta`: Nexio already has custom playback work and libmpv-related settings/history. Treat as architectural research, not a direct port.
- Profile PIN/settings sync from `0.5.0-beta`: useful only if Nexio wants upstream's profile UX.

### Skip or deprioritize

- Pure localization-only commits unless Nexio's strings are missing a language users need.
- Build/release workflow commits and version bumps.
- Release note handling unless Nexio's updater flow lacks the same behavior.
- Upstream plugin-specific code where Nexio has removed plugin support.

## Release-by-Release Review

### `0.5.0-beta`

- Review `baf05ecb` batch watch progress save and `547751ae` startup content gate for home startup stability.
- Review `663f44f9` CW render resolution and `74126120` focus behavior changes.
- Review `d1662f61` / `a6630e27` Trakt comments if Nexio wants comments overlay parity.
- Treat profile PIN/settings sync as P2 unless product wants that UX.

### `0.5.1-beta`

- Review `735b7ecf` reuse-last-link back navigation.
- Review `89435453` IMDB rating focus enrichment if Nexio's detail/home focus enrichment misses IMDB rating refresh.
- Review `75219b04` CW blur/TMDB enrichment/thumbnails setting against Nexio CW pipeline.
- Review `bf7eb22c` fullscreen backdrop/trailer mode only as UX reference; Nexio already has trailer home/detail/screen-saver features.

### `0.5.2-beta`

- Review `f20f2832` separate CW blur and movie TMDB enrichment setting if Nexio lacks the same user control.
- Review `60751c7f` and `3708810c` fullscreen trailer/backdrop transparency and gradient fixes as trailer UX polish.

### `0.5.3-beta`

- Review `c392dfa0` episode scroll race.
- Review `9de19126` deferred secondary subtitle language selection.
- Review `0fa0c510` reuse-last-link autoplay guard.
- Review `b1b77bde` Trakt as More Like This source.
- Review `d3a6d740` TMDB/IMDB watch progress sync.
- Review `d309e9e1` transient playback error auto-retry.
- Review `e6082312` trailer transparency.

### `0.5.4-beta`

- High-value performance cluster: `c64b3c43`, `58d7a6f6`, `0c2bb407`, `c9fcfa48`, `e966bcdf`, `bc225ce5`, `a27f886e`, `0de34823`, `e9c52807`, `4367583a`, `e7e77d32`, `1429baf8`.
- Review subtitle fixes: `e6da6dd8`, `645756fc`, `6b3a662a`, `4b4345d6`, `b067bfde`, `e149309b`, `9f59e18c`, `14eaa89e`.
- Review Trakt/CW: `16925ba6`, `d8d74d2a`, `a2239ca5`, `7cf215ce`, `ba468c01`, `1090c640`, `9c7b1d4c`, `a1304b0d`, `86ccfcb2`.
- Review trailer: `cc10886d`, `cf16fb4d`.
- Review playback: `acc158db`, `c25a709d`, `88295c29`.

### `0.5.5-beta`

- Review CW/Trakt: `557f00be`, `0082521b`, `45e0b565`, `f8244157`.
- Review self-signed stream support: `713f7004`.
- Skip CI/build workflow churn unless Nexio CI needs it.

### `0.5.6-beta`

- Review subtitle focus and dedupe: `18a38fd7`, `b75cdad0`, `7014162c`, `1dfd3c02`.
- Review `000b4d68` self-signed addon support.
- Review watched badge improvements: `0fa4bead`, `9bfc70eb`, `e6823300`.
- Review `669d5c3c` retry/silent retry error expansion.

### `0.5.7-beta`

- Port candidate: `ea22ba84` recent search history.
- Review MPV/libmpv series only as architecture research: `ce7b9ca3`, `f5539487`, `ef7e1b74`, `211c4c2a`, `5d0a9eaf`, `c412fb2d`, `e3038e82`, `1054045f`, `1df7ae9a`, `2eb9ab35`, `f66e3b2b`, `38050a1b`, `a91ae52b`, `24fc67a3`, `ca3f0127`, `bc48b754`, `41b4d442`, `7743da84`, `87aedb85`, `6575bac2`, `f7a5575d`, `a28f3405`.
- Review trailer/YouTube: `9712f664`.
- Review CW/Trakt: `38b9df32`, `4a054e19`, `e12a608d`, `80e23cd5`, `2a97568d`, `b434ebdc`, `2c6ed7f4`, `a46c71d2`, `ad375c55`, `93e4e90`, `cea92880`.
- Review search: `b265cb70`, `57003a6f`.
- Review playback speed: `44965795`, `6e32d959`.
- Review performance: `9ec3213d`.

### `0.6.0-beta`

- Product-decision clusters: Collections, CloudStream extension support, P2P/TorrServer.
- Review playback/networking: `753d8e91`, `cb5ec2a0`, `834729f6`, `f6091995`, `b97c3ac8`, `c8800f62`, `f3582e51`.
- Review stream source UX: `547154c1`, `7110e579`, `bd79d8c7`.
- Review Trakt/CW: `1129dade`, `0a900ce1`, `ba6fb931`, `a92aeafa`, `4103a450`, `78a3520e`, `406365d5`, `48bce1af`.
- Review trailer/home: `76a28c8f`, `8d543b3d`, `b8e15617`, `2e7bc818`.
- Review original audio track support: `ee6325f4`.
- Review loading/logo and stats UX: `25390d33`, `d77c10d8`, `aee6c553`.

### `0.6.1-beta`

- Review updater UX: `93112a0c`, `ba0fd498`, `c70e8031`.
- Review stream/cache/playback: `fdf66a64`, `eacc3a0c`, `b5ce191c`, `48c523fb`, `6773c001`, `e48ab58a`, `655a62f5`, `8e055575`.
- Review profile/cache/CW: `2785f708`, `0bb34290`, `e0d48abb`, `566ee8e0`.
- Review focus UX: `6aed24b2`, `1d288da5`.

### Post-`0.6.1-beta` upstream dev

- Review `f865d741` autoplay from HTTP sources.
- Review `d5e13c6a` removal of automatic home observer push on startup.
- Review PR #1336 / `f9410177` home catalog concurrency if it is not already present in Nexio's post-startup home pipeline.

## PR #1279: AI Subtitle Translation

PR: https://github.com/NuvioMedia/NuvioTV/pull/1279

Status from GitHub page: open as of Apr 13, 2026, titled "Live AI Subtitle Translation via Gemini". It adds real-time Gemini subtitle translation, settings, key entry, per-language AI subtitle behavior after feedback, ASS/SSA support, and in-memory cache.

Nexio already has a broader implementation:

- `9e9ad74f8` on 2026-03-06 added Gemini subtitle translation and media sync fixes.
- Follow-ups on 2026-04-11 and 2026-04-12 made it vendor-agnostic with OpenAI-compatible, Anthropic-compatible, Gemini, and DashScope providers.
- Current files include `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`, `SubtitleTranslationProviderRequests.kt`, `app/src/main/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslator.kt`, `PlayerRuntimeControllerAiSubtitles.kt`, and `app/src/main/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsScreen.kt`.

Assessment: do not port PR #1279 wholesale. Nexio's implementation predates the public PR and is broader, including disk cache, provider abstraction, settings sync, built-in cue translation, addon subtitle translation, retry classification, and provider cooldown behavior. Evaluate only whether PR #1279's latest per-language UI affordance and ASS/SSA handling have any edge-case behavior Nexio lacks.

## Execution Tasks

### Task 1: Search History

**Files:**
- Read upstream: `app/src/main/java/com/nuvio/tv/data/local/SearchHistoryDataStore.kt`
- Modify Nexio: `app/src/main/java/com/nexio/tv/ui/screens/search/SearchScreen.kt`
- Modify Nexio: `app/src/main/java/com/nexio/tv/ui/screens/search/SearchViewModel.kt`
- Modify Nexio: `app/src/main/java/com/nexio/tv/ui/screens/search/SearchUiState.kt`
- Modify Nexio: `app/src/main/java/com/nexio/tv/ui/screens/search/SearchEvent.kt`
- Create Nexio if needed: `app/src/main/java/com/nexio/tv/data/local/SearchHistoryDataStore.kt`

- [ ] Inspect upstream `ea22ba84` with `git -C /Users/jneerdael/Scripts/NuvioTV show ea22ba84 -- app/src/main/java/com/nuvio/tv/data/local/SearchHistoryDataStore.kt app/src/main/java/com/nuvio/tv/ui/screens/search`.
- [ ] Add a focused Nexio unit test for saving a search term, deduping repeated terms, and limiting history size.
- [ ] Implement the DataStore and UI integration using Nexio package names.
- [ ] Run `./gradlew :app:testDebugUnitTest`.
- [ ] Run `./gradlew :app:compileDebugKotlin`.
- [ ] Commit with `feat(search): add recent search history`.

### Task 2: Player Surface Churn Delta

**Files:**
- Read upstream PR #1335 diff: `/Users/jneerdael/Scripts/NuvioTV` ref `origin/pr/1335`
- Modify Nexio if needed: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerVideoSurface.kt`
- Modify Nexio if needed: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`
- Modify Nexio if needed: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerUiState.kt`
- Modify Nexio if needed: `app/src/main/res/values/ids.xml`

- [ ] Compare `git -C /Users/jneerdael/Scripts/NuvioTV diff origin/dev..origin/pr/1335 -- app/src/main/java/com/nuvio/tv/ui/screens/player app/src/main/res/values/ids.xml`.
- [ ] Check whether Nexio already separates timeline ticks from full player `uiState` recomposition.
- [ ] Check whether Nexio needs stable view IDs or the surface-sync workaround.
- [ ] Add or update `PlayerVideoSurfaceStateTest` for any new mutation-plan behavior.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*PlayerVideoSurface*'`.
- [ ] Run `./gradlew :app:compileDebugKotlin`.
- [ ] Commit with `perf(player): reduce player surface churn`.

### Task 3: Trakt and Continue Watching Fixes

**Files:**
- Read upstream: `app/src/main/java/com/nuvio/tv/data/repository/TraktProgressService.kt`
- Read upstream: `app/src/main/java/com/nuvio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Modify Nexio: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`
- Modify Nexio: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Modify Nexio: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt`
- Modify Nexio if needed: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressMutationExecutor.kt`

- [ ] Inspect upstream commits `78a3520e`, `0bb34290`, `e0d48abb`, `557f00be`, `0082521b`, `45e0b565`, and `f8244157`.
- [ ] Add tests for lastWatched preservation, stale in-progress rows, cached next-up retention during fresh pipeline processing, and >1000 watched pagination if Nexio lacks coverage.
- [ ] Adapt only missing behavior into Nexio's existing Trakt outbox/runtime pipeline.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*Trakt*' --tests '*ContinueWatching*'`.
- [ ] Run `./gradlew :app:compileDebugKotlin`.
- [ ] Commit with `fix(trakt): harden continue watching progress reconciliation`.

### Task 4: Trailer Cache and Race Fixes

**Files:**
- Read upstream: `app/src/main/java/com/nuvio/tv/data/trailer/InAppYouTubeExtractor.kt`
- Read upstream: `app/src/main/java/com/nuvio/tv/data/trailer/TrailerService.kt`
- Modify Nexio if needed: `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`
- Modify Nexio if needed: `app/src/main/java/com/nexio/tv/data/trailer/helper/TrailerHelperCache.kt`
- Modify Nexio if needed: `app/src/main/java/com/nexio/tv/data/trailer/helper/YouTubeTrailerCookieStore.kt`
- Modify Nexio if needed: `app/src/main/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptions.kt`

- [ ] Inspect upstream commits `cc10886d`, `9712f664`, and `76a28c8f`.
- [ ] Verify Nexio's trailer helper already caches equivalent YouTube session data and playback-source resolution.
- [ ] Add tests for stale hero trailer cancellation and YouTube 429 cache reuse if missing.
- [ ] Implement only missing TTL/session/race behavior.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*Trailer*'`.
- [ ] Run `./gradlew :app:compileDebugKotlin`.
- [ ] Commit with `fix(trailer): reuse trailer resolver cache safely`.

### Task 5: Playback Edge Fixes

**Files:**
- Read upstream: `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Read upstream: `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerErrorRecovery.kt`
- Modify Nexio: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify Nexio: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
- Modify Nexio: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTrackSelection.kt`
- Modify Nexio: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt`
- Modify Nexio: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

- [ ] Inspect upstream commits `753d8e91`, `cb5ec2a0`, `834729f6`, `f6091995`, `acc158db`, `44965795`, `6e32d959`, and `8e055575`.
- [ ] Add tests for playback speed `endsAt`, audio-track switch recovery, silent first retry, and user-facing error message mapping if missing.
- [ ] Adapt only missing behavior around Nexio's disk cache and deterministic autoplay constraints.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*Player*'`.
- [ ] Run `./gradlew :app:compileDebugKotlin`.
- [ ] Commit with `fix(player): port upstream playback edge fixes`.

### Task 6: Secondary UX Polish Pass

**Files:**
- Modify Nexio based on findings from P1 candidate commits.

- [ ] Evaluate stream source indicator, search See All, addon result ordering, D-pad throttling, subtitle dedupe, and subtitle language priority commits listed under P1.
- [ ] For each accepted behavior, create a focused test before code changes.
- [ ] Run `./gradlew :app:testDebugUnitTest`.
- [ ] Run `./gradlew :app:compileDebugKotlin`.
- [ ] Commit accepted fixes in separate topical commits.

## Validation Gate

- [ ] `./gradlew :app:testDebugUnitTest`
- [ ] `./gradlew :app:compileDebugKotlin`
- [ ] Manual Android TV smoke test: home startup, search flow, detail trailer playback, player start, seek, audio track switch, subtitle selection, AI subtitle toggle, Trakt-backed CW refresh.
- [ ] For PR #1335-related changes, run a focused playback session and collect `gfxinfo` or equivalent frame/churn evidence before claiming improvement.
