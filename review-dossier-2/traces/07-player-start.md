# Trace 07 — Player Start

**Review SHA:** `774a540f8`
**Date:** 2026-04-29
**Dossier series:** review-dossier-2
**Lane cross-references:** F-F-04, F-J-03, F-12-01, A-01, H-01

---

## 1. Path overview

When the user starts playback, `PlayerViewModel` is instantiated by Hilt. During construction, it synchronously builds a `PlaybackOwnerContext`, registers it with `PlaybackSessionRegistry`, and passes the context to `PlayerRuntimeController`. The controller's `init` block kicks off several concurrent pipelines: metadata fetching (which fires `MetadataDepth.PLAYER`), subtitle-settings observation, debug-settings observation, episode-progress loading, and episode-watch-progress observation. Actual media engine initialization does not happen until `PlayerViewModel.startInitialPlaybackIfNeeded()` is called from the `PlayerScreen` composable. At that point `PlayerRuntimeController.initializePlayer(url, headers)` launches, constructs ExoPlayer, and — as part of `launchStartupPreparationTasks` — begins addon-subtitle preparation (which lazily triggers the OpenSubtitles hash fetch). Skip-segment lookup is NOT triggered in `initializePlayer`; it is driven reactively by `observeSubtitleSettings` (which also owns the `skipIntroEnabled` flag) and again when the player reaches `STATE_READY`. Trailer fetching is absent from the player path; `fetchTrailer` lives exclusively in `MetaDetailsViewModel`.

---

## 2. Trace steps

### Step 1 — ViewModel construction and PlaybackOwnerContext assembly

`PlayerViewModel` is annotated `@HiltViewModel` and injected with `PlaybackSessionRegistry` and `ProfileManager`. During the `run { ... }` block that initialises `controller`:

```kotlin
val session = profileManager.activeProfileSession.value
com.nexio.tv.core.playback.PlaybackOwnerContext(
    ownerProfileId = session.profileId,
    ownerSessionId = session.sessionId,
    traktAccount = null,
    simklAccount = null,
    startedAtEpochMs = System.currentTimeMillis().coerceAtLeast(1L)
)
```

All five fields of the data class are present. The `init` block of `PlaybackOwnerContext` enforces: `ownerProfileId > 0`, `ownerSessionId.isNotBlank()`, `startedAtEpochMs > 0L`.

**Finding P-01 — `traktAccount` and `simklAccount` are hard-coded `null`:** Both fields are typed `ProviderAccountRef?` and are deliberately `null` at construction time. The scrobble identity for the active session therefore carries no provider-account binding at the point of registration. `ProfileExecutionContext` does expose these via `accounts[IntegrationProvider.TRAKT/SIMKL]` but that context is not consulted here. This is a known gap: the fields were scaffolded for future scrobble attribution work but are not yet wired.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt:68–77`

---

### Step 2 — Registration with PlaybackSessionRegistry

```kotlin
playbackRegistrationToken = playbackSessionRegistry.register(ownerContext)
```

`PlaybackSessionRegistry.register(context)` is `@Singleton`. It:
1. Generates a UUID token.
2. Calls `current.set(Entry(token, context))` via `AtomicReference`.
3. Sets `_ownerState.value = context`.
4. Returns the token string.

The returned token is stored in `PlayerViewModel.playbackRegistrationToken` and consumed by `unregisterPlaybackSession()` on `stopAndRelease()` or `onCleared()`.

**Finding P-02 — `_ownerState.value = context` confirmed:** The F-F-04 cluster E requirement that `_ownerState` be updated synchronously within `register()` so that `ProfileManager.deferralPolicy` can see the active owner before any coroutine yields is satisfied. The `MutableStateFlow` assignment is synchronous and happens before the method returns.

**File:** `app/src/main/java/com/nexio/tv/core/playback/PlaybackSessionRegistry.kt:20–25`

---

### Step 3 — ProfileManager deferral policy wiring (F-F-04)

`ProfileManager` observes `playbackSessionRegistry.ownerState` in its `init` block:

```kotlin
scope.launch {
    playbackSessionRegistry.ownerState.collect { owner ->
        if (owner == null) {
            val drainedTo = deferralPolicy.onPlaybackIdle()
            if (drainedTo != null) { ... applyProfileChange(...) }
        }
    }
}
```

When a reactive profile-switch arrives during playback (`dataStore.activeProfileId` emits), `deferralPolicy.onIncomingSwitch(targetProfileId, hasActivePlayback = true)` defers the switch by recording `pendingActiveProfileId`. When `playbackSessionRegistry.unregister(token)` is called (setting `_ownerState.value = null`), the observer drains the pending switch.

The `ProfileSwitchDeferralPolicy` comment (`ProfileSwitchDeferralPolicy.kt:4`) directly attributes this design to F-F-04.

**File:** `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt:100–110`
**File:** `app/src/main/java/com/nexio/tv/core/profile/ProfileSwitchDeferralPolicy.kt:1–53`

---

### Step 4 — PlayerRuntimeController init pipelines

`PlayerRuntimeController.init` (`:385–398`) launches:
- `playbackIdleGateState.onPlayerSessionStarted()`
- `refreshScrobbleItem()`
- `mediaSourceFactory.warmupVodCacheAsync()`
- `loadSavedProgressFor(...)` (if not `startFromBeginning`)
- `observeDebugSettings()`
- `observeSubtitleSettings()` — this also owns skip-intro activation (see Step 6)
- `observeSubtitleTranslationSettings()`
- `observeTheIntroDbSettings()`
- `fetchMetaDetails(contentId, contentType)` — triggers `MetadataDepth.PLAYER` (see Step 5)
- `observeBlurUnwatchedEpisodes()`
- `observeEpisodeWatchProgress()`

None of these directly call `fetchSkipIntervals` at init time. Skip-interval fetching is reactive.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt:385–398`

---

### Step 5 — MetadataDepth.PLAYER via fetchMetaDetails

`fetchMetaDetails(id, type)` (`PlayerRuntimeControllerMetadata.kt:19`) is called from `init`. It launches a coroutine that awaits `metaRepository.getMetaFromAllAddons(...)` and on success calls `applyProviderLocalizedPlaybackMetadata(meta)`.

`applyProviderLocalizedPlaybackMetadata` (`PlayerRuntimeControllerMetadata.kt:55`) calls `metadataRouterFacade.fetchTvEnrichment(...)` with:

```kotlin
MetadataRequest(
    contentId = lookupContentId,
    contentType = lookupContentType,
    sourceContext = MetadataSourceContext(itemType = lookupContentType.toApiString()),
    language = language,
    depth = MetadataDepth.PLAYER
)
```

`MetadataDepth.PLAYER` exists in the enum (`MetadataModels.kt:22`) and does fire.

**What PLAYER depth does in the resolver pipeline:**
- `ResolverOrchestrator.schedule(PLAYER)` schedules only `ResolverType.TRACKING` as a network resolver (`:51–53`). SKIP_SEGMENTS is intentionally omitted per F-12-01.
- `ProviderPlanExecutor.buildPlan(route, PLAYER)` returns an `ProviderExecutionPlan` with `steps = emptyList()` (`:23–25`), meaning no provider plan steps are executed.
- In `MetadataRouterFacade.resolveRequest`, when `resolverType == TRACKING`, the dispatch is a no-op (`ResolverType.TRACKING -> Unit`, `:141`). `TRACKING` participates via the FieldResolver / orchestrator local pass, not a network dispatch.

**Finding P-03 — MetadataDepth.PLAYER fires but executes no provider plan and no network resolver:** The depth exists and produces trace events (`metadata.route_decision`, `metadata.field_selected` via `resolveRequest`), but its effective work is limited to TRACKING field resolution via the local FieldResolver pass. No TMDB/TVDB/Kitsu provider plan steps run at this depth.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt:55–116`
**File:** `app/src/main/java/com/nexio/tv/core/metadata/router/ResolverOrchestrator.kt:47–53`
**File:** `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt:23–25`

---

### Step 6 — Skip-segment lookup (SkipIntroRepository path)

`fetchSkipIntervals(id, season, episode)` is defined in `PlayerRuntimeControllerObservers.kt:443`. It is **not** called in `initializePlayer` or directly from `init`. The first trigger paths are:

1. **`observeSubtitleSettings()`** (`PlayerRuntimeControllerObservers.kt:305–318`): collects `playerSettingsDataStore.playerSettings`. On the first emission (which always fires for a new collector), if `skipIntroEnabled == true` and `skipIntroFetchedKey == null`, it calls `fetchSkipIntervals(contentId, currentSeason, currentEpisode)`.

2. **`PlayerRuntimeControllerStreams.kt:777`**: called when a new stream is selected.

3. **`observeTheIntroDbSettings()`** (`PlayerRuntimeControllerObservers.kt:391`): called when TheIntroDb settings change (clear-and-refetch).

`fetchSkipIntervals` dispatches to:
- `SkipIntroRepository.getSkipIntervalsForMal(malId, episode)` for MAL IDs
- `SkipIntroRepository.getSkipIntervalsForKitsu(kitsuId, episode)` for Kitsu IDs
- `SkipIntroRepository.getAnimePrimarySkipIntervals(canonicalId, season, episode)` for anime-primary path
- `SkipIntroRepository.getSkipIntervals(canonicalId, season, episode)` for standard IMDB/TMDB IDs

**Finding P-04 — Skip-segment fetch is not part of `initializePlayer`; it is settings-observer driven:** The first `fetchSkipIntervals` call happens when `observeSubtitleSettings` receives its first PlayerSettings emission from DataStore. This is asynchronous and may arrive before or after `STATE_READY` depending on DataStore load latency. The comment at `ResolverOrchestrator.kt:47–50` explicitly documents the rationale (F-12-01): the resolver pipeline's identity-resolution overhead is incompatible with the sub-50ms skip latency requirement.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt:305–318, 443–498`

---

### Step 7 — OpenSubtitles hash fetch (F-J-03 closure)

`OpenSubtitlesHashIntegrationProvider.compute(url, headers)` is called from `fetchAddonSubtitlesNow()` (`PlayerRuntimeControllerObservers.kt:51–74`), which is invoked during the startup subtitle preparation pipeline launched in `launchStartupPreparationTasks()` (`PlayerRuntimeControllerInitialization.kt:1234–1256`). The hash is fetched lazily: it only runs when `currentVideoHash == null && currentStreamUrl.isNotBlank()`.

The `IntegrationCallSpec` inside `OpenSubtitlesHashIntegrationProvider` uses:

```kotlin
scope = IntegrationScope.GlobalContent,
```

**Finding P-05 — F-J-03 closure confirmed:** `IntegrationScope.GlobalContent` is used, not the deprecated `IntegrationScope.Global`. `IntegrationScope.Global` is annotated `@Deprecated(replaceWith = ReplaceWith("IntegrationScope.GlobalContent"))` in `IntegrationScope.kt:36–44`. The architecture pin test `IntegrationScopeGlobalDeprecatedNoCallersTest` (labelled "F-J-03") enforces this at the test level. The production call site at `OpenSubtitlesHashIntegrationProvider.kt:44` is clean.

**File:** `app/src/main/java/com/nexio/tv/data/integration/playback/OpenSubtitlesHashIntegrationProvider.kt:37–76`
**File:** `app/src/main/java/com/nexio/tv/core/integration/IntegrationScope.kt:36–44`

---

### Step 8 — Trailer fetch: F-04-03 status

There is **no trailer fetch in the player start path.** `metadataRouterFacade.fetchTrailer(...)` has exactly one call site: `MetaDetailsViewModel.fetchTrailerUrl()` (`MetaDetailsViewModel.kt:2557`), which is detail-screen-scoped. `PlayerRuntimeController` and `PlayerViewModel` have no reference to `TrailerService`, `fetchTrailer`, or any trailer URL resolution.

**Finding P-06 — Trailer enrichment does not bypass the canonical metadata facade in the player path because it is absent entirely:** The F-04-03 finding about "trailer enrichment bypasses canonical metadata facade" applies to the detail screen, not the player. In the player, `applyProviderLocalizedPlaybackMetadata` uses only `metadataRouterFacade.fetchTvEnrichment` and `metadataRouterFacade.fetchTvEpisodeEnrichment`, both of which route through `resolveRequest` and emit canonical trace events. There is no rogue direct call to a trailer or poster service from the player start path.

**File:** `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt:64–115`
**File:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:2536–2557` (detail screen only)

---

## 3. Findings summary

| ID | Severity | Description |
|----|----------|-------------|
| P-01 | Low / by-design | `traktAccount` and `simklAccount` are hard-coded `null` in `PlaybackOwnerContext` construction; scrobble identity carries no provider-account binding at registration time. The fields are scaffolded for future work. |
| P-02 | Confirmed-OK | `_ownerState.value = context` is set synchronously inside `register()`, satisfying the F-F-04 cluster E requirement that `ProfileManager.deferralPolicy` can observe active playback before any coroutine yield. |
| P-03 | Informational | `MetadataDepth.PLAYER` exists and fires trace events, but executes an empty provider plan (no TMDB/TVDB steps) and dispatches TRACKING as a no-op network resolver. Its functional contribution is limited to local FieldResolver TRACKING pass. |
| P-04 | Informational | Skip-segment fetch is not wired into `initializePlayer`; it is triggered reactively by `observeSubtitleSettings` on first PlayerSettings emission. This is correct per F-12-01 (latency incompatibility with resolver pipeline). |
| P-05 | Confirmed-OK | F-J-03 closure verified: `OpenSubtitlesHashIntegrationProvider` uses `IntegrationScope.GlobalContent`, not the deprecated `IntegrationScope.Global`. |
| P-06 | Confirmed-OK | Trailer enrichment is absent from the player start path. It resides exclusively in `MetaDetailsViewModel`. No bypass of the canonical metadata facade occurs in the player. |

---

## 4. Files examined

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerStartup.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- `app/src/main/java/com/nexio/tv/core/playback/PlaybackSessionRegistry.kt`
- `app/src/main/java/com/nexio/tv/core/playback/PlaybackOwnerContext.kt`
- `app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt`
- `app/src/main/java/com/nexio/tv/core/profile/ProfileSwitchDeferralPolicy.kt`
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`
- `app/src/main/java/com/nexio/tv/core/metadata/router/ResolverOrchestrator.kt`
- `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt`
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- `app/src/main/java/com/nexio/tv/data/integration/playback/OpenSubtitlesHashIntegrationProvider.kt`
- `app/src/main/java/com/nexio/tv/core/integration/IntegrationScope.kt`
- `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt` (referenced)
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` (trailer path, detail screen only)
