# Trace 12 — Skip Segment Lookup

**Review SHA:** `774a540f8`
**Date:** 2026-04-29
**Dossier series:** review-dossier-2
**Lane cross-references:** F-12-01, F-12-02, A-skip-canonical

---

## 1. Path overview

When a user starts playback, skip segment data (intro/outro timestamps) is loaded via `SkipIntroRepository` directly from the player controller layer, bypassing the metadata router pipeline. `PlayerViewModel` receives a `SkipIntroRepository` injection and passes it to `PlayerRuntimeController`. On cold start, `PlayerRuntimeController.init` calls `observeSubtitleSettings()` (via `playerSettingsDataStore`) which, on first emission, invokes `fetchSkipIntervals(contentId, currentSeason, currentEpisode)` when `skipIntroEnabled == true` and `skipIntroFetchedKey == null`. For subsequent stream switches the explicit call is in `PlayerRuntimeControllerStreams.kt:777`. The lookup is never routed through `MetadataRouterFacade`; that gate was removed in commit `95e99e5b4` (F-12-01) and is architecturally pinned by `SkipIntroRepositoryCanonicalSurfaceTest`.

---

## 2. Trace steps

### Step 1 — ViewModel construction and controller wiring

`PlayerViewModel` (`PlayerViewModel.kt:46`) receives `SkipIntroRepository` as a constructor-injected `@HiltViewModel` dependency. During `init`, it constructs `PlayerRuntimeController` and forwards the repository as `skipIntroRepository` (`PlayerViewModel.kt:87`). The controller stores it at `internal val skipIntroRepository: SkipIntroRepository` (`PlayerRuntimeController.kt:62`).

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt:21,46,87`
**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt:29,62`

### Step 2 — Controller `init` block and observer wiring

`PlayerRuntimeController.init` (`PlayerRuntimeController.kt:385-398`) calls in sequence:

```
observeDebugSettings()
observeSubtitleSettings()   // <-- skip fetch is triggered from here on first emission
observeSubtitleTranslationSettings()
observeTheIntroDbSettings()
fetchMetaDetails(contentId, contentType)
observeBlurUnwatchedEpisodes()
observeEpisodeWatchProgress()
```

No direct `fetchSkipIntervals` call exists in `init`; skip fetch piggybacks the first emission of `playerSettingsDataStore.playerSettings`.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt:385-398`

### Step 3 — First skip trigger: `observeSubtitleSettings`

`observeSubtitleSettings` (`PlayerRuntimeControllerObservers.kt:235`) collects `playerSettingsDataStore.playerSettings`. On each emission it evaluates:

```kotlin
val wasEnabled = skipIntroEnabled
skipIntroEnabled = settings.skipIntroEnabled
if (!skipIntroEnabled) {
    // clear intervals
} else {
    if (!wasEnabled || skipIntroFetchedKey == null) {
        _uiState.update { it.copy(skipIntervalDismissed = false) }
        fetchSkipIntervals(contentId, currentSeason, currentEpisode)
    }
}
```

On first emission `skipIntroFetchedKey` is `null`, so `fetchSkipIntervals` is called immediately. This is the cold-start skip fetch path.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt:305-318`

### Step 4 — Second trigger path: stream switch

When the user switches streams, `PlayerRuntimeControllerStreams.kt:773-777` resets skip state and calls `fetchSkipIntervals(contentId, currentSeason, currentEpisode)` directly:

```kotlin
skipIntervals = emptyList()
skipIntroFetchedKey = null
lastActiveSkipType = null
fetchSkipIntervals(contentId, currentSeason, currentEpisode)
```

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStreams.kt:773-777`

### Step 5 — Third trigger path: TheIntroDB settings change

`observeTheIntroDbSettings` (`PlayerRuntimeControllerObservers.kt:351`) watches `theIntroDbSettingsDataStore.settings`. When the settings signature changes (toggle on/off, button visibility), it clears the cache and calls `fetchSkipIntervals` — but only when `!isAnimePrimarySkipPath()`. Anime paths skip this observer entirely to avoid interfering with MAL/Kitsu-keyed data.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt:351-394`

### Step 6 — `fetchSkipIntervals` dispatch logic

`fetchSkipIntervals` (`PlayerRuntimeControllerObservers.kt:443-498`) performs provider selection and deduplication:

1. **Dedup guard:** `skipIntroFetchedKey` is compared against the candidate key; if equal, the function returns early.
2. **MAL path:** `effectiveId.startsWith("mal:")` → `skipIntroRepository.getSkipIntervalsForMal(malId, malEpisode)`
3. **Kitsu path:** `effectiveId.startsWith("kitsu:")` → `skipIntroRepository.getSkipIntervalsForKitsu(kitsuId, kitsuEpisode)`
4. **General path:** `effectiveId` is split at `:` to extract a bare canonical ID (IMDB `tt*` or TMDB numeric). Then `isAnimePrimarySkipPath()` decides between `getAnimePrimarySkipIntervals(canonicalId, season, episode)` and `getSkipIntervals(canonicalId, season, episode)`.

`effectiveId` prefers `currentVideoId` over `contentId`; `currentVideoId` carries the season/episode-specific identifier when available.

`isAnimePrimarySkipPath()` (`PlayerSkipProviderPolicy.kt:9-14`) delegates to `SkipProviderArbiter.resolve(contentType, currentSkipProviderId(), contentId)` and returns true when the result is `SkipProviderRoute.ANIME_PRIMARY`.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt:443-498`
**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerSkipProviderPolicy.kt:9-18`

### Step 7 — `SkipProviderArbiter` routing

`SkipProviderArbiter.resolve` (`SkipIntroRepository.kt:27-42`) inspects `contentType` and `effectiveId`/`fallbackId` strings:

- `effectiveId` starts with `"mal:"` or `"kitsu:"` → `ANIME_PRIMARY`
- `contentType == "anime"` → `ANIME_PRIMARY`
- `":anime:"` substring in either ID → `ANIME_PRIMARY`
- Default → `THEINTRODB`

**File:** `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt:26-42`

### Step 8 — `SkipIntroRepository` public surface

`SkipIntroRepository` (`SkipIntroRepository.kt:98-323`) exposes four public entry points:

| Method | Key used | Route |
|---|---|---|
| `getSkipIntervals(contentId, season, episode)` | `"$contentId:$season:$episode"` | TheIntroDB |
| `getAnimePrimarySkipIntervals(imdbId, season, episode)` | `"anime:$imdbId:$season:$episode"` | AniSkip → AnimeSkip fallback |
| `getSkipIntervalsForMal(malId, episode)` | `"mal:$malId:$episode"` | AniSkip → AnimeSkip fallback |
| `getSkipIntervalsForKitsu(kitsuId, episode)` | `"kitsu:$kitsuId:$episode"` | AniSkip → AnimeSkip fallback |

Each method checks an in-memory `ConcurrentHashMap<String, List<SkipInterval>>` first. Cache eviction is manual only (`clearCachedIntervals()`), triggered on settings change or stream switch.

**File:** `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt:106-176`

### Step 9 — Integration providers and API shapes

Each provider constructs an `IntegrationSpec<T>` naming its `apiShapeId` from `SkipApiShapes`:

| Provider | `apiShapeId` constant | `operationKey` |
|---|---|---|
| `IntroDbIntegrationProvider` | `SkipApiShapes.THEINTRODB_MEDIA` | `theintrodb.media.getIntervals` |
| `AniSkipIntegrationProvider` | `SkipApiShapes.ANISKIP_SKIP_TIMES` | `aniskip.skipTimes.getSkipIntervals` |
| `AnimeSkipIntegrationProvider.resolveShowIds` | `SkipApiShapes.ANIMESKIP_SHOWS` | `animeskip.graphql.resolveShowIds` |
| `AnimeSkipIntegrationProvider.queryEpisodes` | `SkipApiShapes.ANIMESKIP_GRAPHQL` | `animeskip.graphql.queryEpisodes` |
| `AnimeSkipIntegrationProvider.validateClientId` | `SkipApiShapes.ANIMESKIP_VALIDATE` | `animeskip.graphql.validateClientId` |
| `ArmIntegrationProvider` (IMDB bridge ops) | `SkipApiShapes.ARM_IMDB_BRIDGE` | `arm.imdb.*` |
| `ArmIntegrationProvider` (MAL/Kitsu bridge ops) | `SkipApiShapes.ARM_IDS_BRIDGE` | `arm.ids.*` |

All seven constants are defined in `SkipApiShapes` object (`IntegrationApiShapes.kt:107-115`) and every provider passes a non-blank `apiShapeId` to `IntegrationSpec`, satisfying the `require(apiShapeId.isNotBlank())` guard (`IntegrationSpec.kt:18`).

**File:** `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt:107-115`
**File:** `app/src/main/java/com/nexio/tv/data/integration/skip/IntroDbIntegrationProvider.kt:32-60`
**File:** `app/src/main/java/com/nexio/tv/data/integration/skip/AniSkipIntegrationProvider.kt:23-57`
**File:** `app/src/main/java/com/nexio/tv/data/integration/skip/AnimeSkipIntegrationProvider.kt:26-116`
**File:** `app/src/main/java/com/nexio/tv/data/integration/skip/ArmIntegrationProvider.kt:26-193`

---

## 3. Findings

### F-12-01 (P1 original) — `ResolverType.SKIP_SEGMENTS` no longer exists in the enum — RESOLVED

**Status:** Resolved. The `ResolverType` enum (`MetadataModels.kt:23-32`) has eight entries: `ADDON_DISPLAY`, `RATING`, `ARTWORK`, `REVIEWS`, `TRACKING`, `TRAILERS`, `RECOMMENDATIONS`, `ORGANIZATION_PERSON`. `SKIP_SEGMENTS` is absent.

`ResolverOrchestrator.schedule` (`ResolverOrchestrator.kt:47-53`) has an explicit comment at the `PLAYER` depth branch:

```
// SKIP_SEGMENTS is intentionally omitted (F-12-01). Player-skip latency requirements
// (sub-50ms from playback start) are incompatible with the resolver pipeline's
// identity-resolution + provider-plan overhead. SkipIntroRepository is the canonical
// surface — see SkipIntroRepositoryCanonicalSurfaceTest (added in Task 21).
```

`MetadataRouterFacade.kt:108` echoes the same intent: `"SKIP_SEGMENTS was removed in Task 20 (F-12-01)"`.

The removal is recorded in commit `95e99e5b4` (`refactor(router): remove SKIP_SEGMENTS from ResolverType and ResolvedField`).

**Residual:** `FieldOwner.SKIP_SEGMENTS` survives in the `FieldOwner` enum (`MetadataModels.kt:64`) and in `FieldOwner.defaultSourceRole()` (`MetadataModels.kt:87` and its copy in `FieldResolver.kt:286`). This is a dead enum constant; it is never assigned to a `FieldValue` in production code, and `ResolvedField` has no `SKIP_SEGMENTS` member. The residual is inert but constitutes namespace litter.

**File:** `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt:23-32,56-88`
**File:** `app/src/main/java/com/nexio/tv/core/metadata/router/ResolverOrchestrator.kt:47-53`
**File:** `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:108`
**File:** `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt:286`

### F-12-02 (P1 original) — Skip bypasses canonical metadata facade — INTENTIONAL and ENFORCED

**Status:** Intentional by design. `SkipIntroRepository` is the declared canonical owner of skip segments. `MetadataRouterFacade` carries an inline comment: `"skip is owned by SkipIntroRepository, not the resolver pipeline"` (`MetadataRouterFacade.kt:109`).

The architecture is pinned by an automated test:

`SkipIntroRepositoryCanonicalSurfaceTest` (`app/src/test/java/com/nexio/tv/architecture/SkipIntroRepositoryCanonicalSurfaceTest.kt`) scans production sources and asserts that `introDbApi.`, `aniSkipApi.`, `animeSkipApi.`, and `armApi.` are called **only** from the five allowlisted paths:

```
/com/nexio/tv/data/repository/SkipIntroRepository.kt
/com/nexio/tv/data/integration/skip/AniSkipIntegrationProvider.kt
/com/nexio/tv/data/integration/skip/AnimeSkipIntegrationProvider.kt
/com/nexio/tv/data/integration/skip/IntroDbIntegrationProvider.kt
/com/nexio/tv/data/integration/skip/ArmIntegrationProvider.kt
```

The test was introduced in commit `e38f61b39` (`test(arch): pin SkipIntroRepository as canonical skip surface (F-12-02)`).

The rationale is documented in the test's KDoc: sub-50 ms latency from playback start is incompatible with the metadata router's identity-resolution + provider-plan overhead.

**File:** `app/src/test/java/com/nexio/tv/architecture/SkipIntroRepositoryCanonicalSurfaceTest.kt`
**File:** `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt:109`

### F-12-03 (new) — `SkipApiShapes.ANIMESKIP_VALIDATE` is undocumented in the original shape inventory

**Severity:** Low / informational.

The original task description lists six `SkipApiShapes` constants to verify: `THEINTRODB_MEDIA`, `ANISKIP_SKIP_TIMES`, `ANIMESKIP_GRAPHQL`, `ANIMESKIP_SHOWS`, `ARM_IMDB_BRIDGE`, `ARM_IDS_BRIDGE`. A seventh constant, `ANIMESKIP_VALIDATE = "animeskip.key_validation"`, exists in `IntegrationApiShapes.kt:112` and is used by `AnimeSkipIntegrationProvider.validateClientId`. It has a runtime spec (`IntegrationSpec` with `cachePolicy = IntegrationCachePolicy.Disabled` and `workClass = IntegrationWorkClass.USER_VISIBLE`), so it is not a dangling constant. However, it is absent from the F-12 task description. All seven shapes have runtime specs attached.

**File:** `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt:112`
**File:** `app/src/main/java/com/nexio/tv/data/integration/skip/AnimeSkipIntegrationProvider.kt:88-116`

### F-12-04 (new) — Skip segment cache keys are language-independent for all paths

**Status:** Verified correct for anime.

Cache keys across all providers contain no language or locale component:

- TheIntroDB: `"theintrodb:$contentId:$season:$episode"`
- AniSkip: `"aniskip:$malId:$episode"`
- AnimeSkip shows: `"animeskip:shows:$anilistId"`
- AnimeSkip episodes: `"animeskip:episodes:$showId"`
- ARM IMDB bridge: `"arm:imdb:$imdbId:{target}"` (target = `anilist`, `mal`)
- ARM IDS bridge: `"arm:mal:$malId:{target}"`, `"arm:kitsu:$kitsuId:{target}"`

Skip segment timestamp data (start/end offsets) is not language-localized at any of these APIs — timestamps are position-based and content-universal. Language-independence in cache keys is therefore correct. The `SkipIntroRepository` in-memory layer also uses language-free keys: e.g., `"anime:$imdbId:$season:$episode"`.

**File:** `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt:117,127,149,180`
**File:** `app/src/main/java/com/nexio/tv/data/integration/skip/IntroDbIntegrationProvider.kt:34`
**File:** `app/src/main/java/com/nexio/tv/data/integration/skip/AniSkipIntegrationProvider.kt:25`
**File:** `app/src/main/java/com/nexio/tv/data/integration/skip/AnimeSkipIntegrationProvider.kt:28,60`
**File:** `app/src/main/java/com/nexio/tv/data/integration/skip/ArmIntegrationProvider.kt:29,48,73,98,122,147,172`

### F-12-05 (new) — Cold-start skip fetch is indirect, not co-located with `initializePlayer`

**Severity:** Low / observability risk.

`initializePlayer` (`PlayerRuntimeControllerInitialization.kt:179`) does not call `fetchSkipIntervals` directly. The cold-start skip fetch is triggered by the first emission of `playerSettingsDataStore.playerSettings` inside `observeSubtitleSettings` (`PlayerRuntimeControllerObservers.kt:314-316`). The coroutine for settings observation is launched in `PlayerRuntimeController.init`, concurrently with `initializePlayer`. The ordering between them is not deterministic; skip data may arrive after the first frame has been rendered. This is the intended design (non-blocking playback start), but the indirect trigger makes the call graph harder to follow during code review. No defect is present; the existing `skipIntroFetchedKey` dedup guard prevents redundant fetches.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt:305-318`
**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt:179`

---

## 4. Summary table

| Finding | Severity | Status |
|---|---|---|
| F-12-01: `ResolverType.SKIP_SEGMENTS` removed from resolver pipeline | — | Resolved; `FieldOwner.SKIP_SEGMENTS` is inert residue (informational) |
| F-12-02: Skip bypasses `MetadataRouterFacade` | — | Intentional; pinned by `SkipIntroRepositoryCanonicalSurfaceTest` |
| F-12-03: `ANIMESKIP_VALIDATE` absent from original shape inventory | Low | All 7 shapes have runtime specs; no missing implementation |
| F-12-04: Skip cache keys are language-independent | — | Correct for all providers; timestamp data is locale-neutral |
| F-12-05: Cold-start skip trigger is indirect via settings observer | Low | No defect; observability risk only |
