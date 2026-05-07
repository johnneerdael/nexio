# Screensaver Shared Media Clips Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make both screensaver variants consume the same resolved display surface and durable media clip candidates as Modern Home.

**Architecture:** Screensaver content is sourced from TMDB trending movies/shows as internal rails, projected through the existing home metadata/display pipeline, and published to `ResolvedDisplaySurfaceRepository`. Trailer playback is resolved through `TrailerResolver` backed by `MediaClipStore`, with provider adapters writing durable clip candidates instead of screensaver-specific trailer fields.

**Tech Stack:** Android/Kotlin, Hilt, SharedPreferences, Robolectric unit tests, Gradle `testDebugUnitTest`.

---

### Task 1: Screensaver Source Surface

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTmdbCatalogPlanTest.kt`

- [x] **Step 1: Add failing test for screensaver TMDB trending rails**

Run: `./gradlew testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeViewModelTmdbCatalogPlanTest`

Expected initial failure: no screensaver surface publication for TMDB trending movies/shows.

- [x] **Step 2: Publish screensaver rows through home display mapper**

Implementation routes `TmdbCatalogIds.TRENDING_MOVIES` and `TmdbCatalogIds.TRENDING_SERIES` through `HomeResolvedDisplayMapper.toResolvedDisplayItems(...)` and publishes them under `ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY`.

- [x] **Step 3: Verify tests**

Run: `./gradlew testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeViewModelTmdbCatalogPlanTest`

Expected: PASS.

### Task 2: Screensaver Display Parity

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverPreparation.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ScreensaverCandidateRepositoryTest.kt`

- [x] **Step 1: Add failing tests for logo, genres, runtime, and no provider-direct calls**

Run: `./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest`

Expected initial failure: screensaver candidates drop resolved display fields.

- [x] **Step 2: Project candidates from `ResolvedDisplaySurfaceRepository`**

Implementation observes `observeScreensaverSurface(profileId)` and derives compatibility URLs from resolved artwork refs while preserving logo, genres, runtime, and stable IDs.

- [x] **Step 3: Verify tests**

Run: `./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ScreensaverCandidateRepositoryTest`

Expected: PASS.

### Task 3: Durable Media Clip Store

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/media/MediaClipStore.kt`
- Test: `app/src/test/java/com/nexio/tv/core/media/MediaClipStoreTest.kt`

- [x] **Step 1: Add failing durable cache tests**

Run: `./gradlew testDebugUnitTest --tests com.nexio.tv.core.media.MediaClipStoreTest`

Expected initial failure: media clip model/store classes do not exist.

- [x] **Step 2: Add `MediaClipCandidate`, `MediaClipScope`, `MediaClipPlaybackRef`, and `MediaClipStore`**

Implementation persists durable candidate IDs and source metadata, keeps YouTube IDs, and intentionally does not persist resolved playback URIs as durable playback refs.

- [x] **Step 3: Verify tests**

Run: `./gradlew testDebugUnitTest --tests com.nexio.tv.core.media.MediaClipStoreTest`

Expected: PASS.

### Task 4: TrailerResolver Cache-First Clip Resolution

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/resolver/TrailerResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/resolver/TrailerResolverMediaClipStoreTest.kt`

- [x] **Step 1: Add failing test for screensaver item with stable IDs and no `trailerYtIds`**

Run: `./gradlew testDebugUnitTest --tests com.nexio.tv.core.metadata.router.resolver.TrailerResolverMediaClipStoreTest`

Expected initial failure: resolver ignores durable media clip cache.

- [x] **Step 2: Resolve cached media clips before provider/fallback lookup**

Implementation maps durable `MediaClipPlaybackRef.YouTubeId` records into `TrailerPlaybackRef.YouTubeId` and marks the decision as `media_clip_cache_hit`.

- [x] **Step 3: Verify tests**

Run: `./gradlew testDebugUnitTest --tests com.nexio.tv.core.metadata.router.resolver.TrailerResolverMediaClipStoreTest`

Expected: PASS.

### Task 5: Provider Adapter Clip Writes

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/TmdbTrailerMetadataAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/KitsuMetadataProviderAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/TvdbTrailerMetadataAdapter.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/TmdbTrailerMetadataAdapterTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/KitsuMetadataProviderAdapterTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/TvdbTrailerMetadataAdapterTest.kt`

- [x] **Step 1: Add failing TMDB and Kitsu media clip tests**

Run: `./gradlew testDebugUnitTest --tests com.nexio.tv.data.integration.metadata.TmdbTrailerMetadataAdapterTest --tests com.nexio.tv.data.integration.metadata.KitsuMetadataProviderAdapterTest`

Expected initial failure: adapters do not accept or write `MediaClipStore`.

- [x] **Step 2: Store TMDB and Kitsu title trailer candidates**

Implementation writes TMDB movie/TV/season videos and Kitsu `attributes.youtubeVideoId` as durable media clip candidates.

- [x] **Step 3: Add failing TVDB title trailer store test**

Run: `./gradlew testDebugUnitTest --tests com.nexio.tv.data.integration.metadata.TvdbTrailerMetadataAdapterTest`

Expected initial failure: TVDB title trailer adapter emits trailer field but does not persist a title media clip candidate.

- [x] **Step 4: Store TVDB title trailer candidates only**

Implementation writes `TvdbTrailerLookupResult.ResolvedYouTube` as title-scoped TVDB `MediaClipCandidate`; it does not create season/recap candidates because current TVDB DTOs expose only id/language/name/url/runtime.

- [x] **Step 5: Verify provider tests**

Run: `./gradlew testDebugUnitTest --tests com.nexio.tv.data.integration.metadata.TmdbTrailerMetadataAdapterTest --tests com.nexio.tv.data.integration.metadata.KitsuMetadataProviderAdapterTest --tests com.nexio.tv.data.integration.metadata.TvdbTrailerMetadataAdapterTest`

Expected: PASS.

### Task 6: On-Device Verification

**Files:**
- Modify as needed: `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`
- Modify as needed: `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`

- [ ] **Step 1: Build release**

Run: `./gradlew :app:assembleUniversalRelease`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Install to active TV**

Run: `adb -s 192.168.50.71:5555 install -r app/build/outputs/apk/universal/release/app-universal-release.apk`

Expected: success.

- [ ] **Step 3: Capture screensaver/media clip trace**

Run: `adb -s 192.168.50.71:5555 logcat -d -v time | grep -Ei 'screensaver\\.|media_clip\\.|metadata\\.trailer|runtime\\.trailer_playback_source|DreamManager|AndroidRuntime|FATAL' | tail -n 260`

Expected: screensaver surface publication, candidate projection, and trailer resolution traces identify whether playback starts or which component rejects it.
