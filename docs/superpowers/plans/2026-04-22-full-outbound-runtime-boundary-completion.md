# Full Outbound Runtime Boundary Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the OpenSpec change by making `IntegrationRuntime` the only execution path for every outbound API surface in Nexio, while keeping all raw provider/auth/network clients private to adapter packages and leaving feature code dependent only on repositories/facades.

**Architecture:** Treat this as one boundary-closure program, not a set of unrelated fixes. First, widen CI so it scans the whole codebase for bypasses. Second, expand the runtime so it can govern uncached calls and stream/transport work in addition to cache-first reads. Third, move every remaining direct outbound caller behind provider integration adapters and app-facing repositories/facades until the dependency graph is consistently `features -> repositories/facades -> provider adapters -> IntegrationRuntime -> raw clients`.

**Tech Stack:** Kotlin, Coroutines, Hilt, Room, OkHttp, Retrofit, Media3, Robolectric, JUnit, MockK

---

## Scope Check

This is intentionally one broad implementation program because the acceptance criterion is global: **every outbound service call flows through runtime-owned policy and provider-boundary centralization is complete for every API surface**. Do not split this into independent sub-plans during execution. This plan supersedes the narrower rail-cache completion plan as the true finish line for `establish-unified-integration-runtime`.

## File Structure

### New files

- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCallSpec.kt`
  Responsibility: runtime-owned contract for uncached request/response executions that still need provider policy, backoff, gating, and telemetry.
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCallResult.kt`
  Responsibility: typed runtime result for uncached provider calls.
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationStreamSpec.kt`
  Responsibility: runtime-owned contract for outbound stream/byte transport work.
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationStreamHandle.kt`
  Responsibility: typed handle returned by runtime-owned transport calls.
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationTransportTelemetry.kt`
  Responsibility: shared transport-side logging/metrics for stream/open calls.
- Create: `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbMetadataIntegrationProvider.kt`
  Responsibility: the only legal creator of TMDB metadata specs.
- Create: `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbDiscoveryIntegrationProvider.kt`
  Responsibility: the only legal creator of TMDB discovery specs.
- Create: `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbMetadataIntegrationProvider.kt`
  Responsibility: the only legal creator of TVDB metadata specs.
- Create: `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIdentityIntegrationProvider.kt`
  Responsibility: the only legal creator of TVDB identity/reference specs.
- Create: `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuDiscoveryIntegrationProvider.kt`
  Responsibility: the only legal creator of Kitsu discovery specs.
- Create: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktDiscoveryIntegrationProvider.kt`
  Responsibility: runtime-backed Trakt discovery reads.
- Create: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktLibraryIntegrationProvider.kt`
  Responsibility: runtime-backed Trakt library reads.
- Create: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktMutationIntegrationProvider.kt`
  Responsibility: runtime-backed Trakt mutation/authenticated write surface.
- Create: `app/src/main/java/com/nexio/tv/data/integration/simkl/SimklDiscoveryIntegrationProvider.kt`
  Responsibility: runtime-backed Simkl discovery reads.
- Create: `app/src/main/java/com/nexio/tv/data/integration/simkl/SimklLibraryIntegrationProvider.kt`
  Responsibility: runtime-backed Simkl library reads.
- Create: `app/src/main/java/com/nexio/tv/data/integration/simkl/SimklMutationIntegrationProvider.kt`
  Responsibility: runtime-backed Simkl mutation/authenticated write surface.
- Create: `app/src/main/java/com/nexio/tv/data/integration/addons/AddonCatalogIntegrationProvider.kt`
  Responsibility: runtime-backed addon catalog requests.
- Create: `app/src/main/java/com/nexio/tv/data/integration/addons/AddonMetadataIntegrationProvider.kt`
  Responsibility: runtime-backed addon metadata/detail requests.
- Create: `app/src/main/java/com/nexio/tv/data/integration/imdb/ImdbIntegrationProvider.kt`
  Responsibility: runtime-backed custom IMDb and search calls.
- Create: `app/src/main/java/com/nexio/tv/data/integration/subtitles/SubtitleTranslationIntegrationProvider.kt`
  Responsibility: runtime-backed subtitle translation HTTP calls.
- Create: `app/src/main/java/com/nexio/tv/data/integration/ops/UpdaterIntegrationProvider.kt`
  Responsibility: runtime-backed updater/download calls.
- Create: `app/src/main/java/com/nexio/tv/data/integration/ops/BenchmarkUploadIntegrationProvider.kt`
  Responsibility: runtime-backed benchmark and telemetry uploads.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/RuntimePlaybackTransportFactory.kt`
  Responsibility: create Media3/OkHttp playback data sources that obtain network transport through runtime-owned stream specs.
- Create: `app/src/test/java/com/nexio/tv/architecture/NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest.kt`
  Responsibility: fail on direct `executeAuthorizedRequest`, `bearerToken()`, or `validAccessToken()` outside approved integration packages.
- Create: `app/src/test/java/com/nexio/tv/architecture/NoDirectOkHttpOutsideRuntimeTransportPackagesTest.kt`
  Responsibility: fail on direct `OkHttpClient` / `newCall()` usage outside integration packages, runtime transport packages, and DI/network definitions.
- Create: `app/src/test/java/com/nexio/tv/architecture/NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest.kt`
  Responsibility: fail on provider API references anywhere outside approved packages.
- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationCallRuntimeTest.kt`
  Responsibility: prove uncached outbound calls still honor provider lanes/backoff/gates.
- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationStreamRuntimeTest.kt`
  Responsibility: prove stream/open calls still honor provider lanes/backoff/gates.
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/RuntimePlaybackTransportFactoryTest.kt`
  Responsibility: prove playback/network transport now routes through runtime-owned stream specs instead of direct `OkHttpClient`.
- Create: `app/src/test/java/com/nexio/tv/data/repository/OutboundBoundaryInventoryTest.kt`
  Responsibility: hold the explicit list of currently-approved outbound packages and detect regressions when new raw callers appear.

### Modified files

- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationRuntime.kt`
  Responsibility: expose cache-first, uncached call, and stream transport runtime surfaces.
- Modify: `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt`
  Responsibility: implement runtime-owned execution for all three outbound modes.
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationWorkClass.kt`
  Responsibility: add transport-oriented work classes for stream/open requests.
- Modify: `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt`
  Responsibility: bind new runtime transport helpers and adapter dependencies.
- Modify: `app/src/test/java/com/nexio/tv/architecture/ArchitectureScan.kt`
  Responsibility: scan the whole non-generated codebase instead of a narrow presentation subset.
- Modify: `app/src/test/java/com/nexio/tv/architecture/IntegrationBoundaryTest.kt`
  Responsibility: fail on raw provider API references anywhere outside approved packages.
- Modify: `app/src/test/java/com/nexio/tv/architecture/NoRawProviderInjectionTest.kt`
  Responsibility: fail on broad raw Retrofit/OkHttp injection outside approved packages.
- Modify: `app/src/test/java/com/nexio/tv/architecture/NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt`
  Responsibility: keep `IntegrationRuntime` out of feature/presentation code.
- Modify: `app/src/test/java/com/nexio/tv/architecture/NoRuntimeSpecOutsideIntegrationPackagesTest.kt`
  Responsibility: forbid `IntegrationSpec` / `IntegrationCallSpec` / `IntegrationStreamSpec` creation outside integration packages.
- Modify: `app/src/test/java/com/nexio/tv/architecture/NoLegacyProviderFallbacksTest.kt`
  Responsibility: scan the full app tree for raw API/auth fallback patterns, not just the prior narrow subset.
- Modify: `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/KitsuDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbOrganizationService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/ImdbPosterLookupService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbPersonService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbReferenceDataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbUpdateProcessor.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/MDBListDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`
  Responsibility for the block above: stop building specs or calling provider clients directly; delegate to provider adapters/facades.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktDiscoveryMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressMutationExecutor.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklAuthService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklProgressService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklScrobbleService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklTrackingRemoteDataSource.kt`
  Responsibility for the block above: move all Trakt/Simkl reads, auth, and mutations behind adapter-owned runtime paths.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/CustomImdbClient.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/ImdbSearchService.kt`
- Modify: `app/src/main/java/com/nexio/tv/updater/ApkDownloader.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/ShadowAutoplayCollectionUploader.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkCollectionUploader.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DebridBenchmarkTransport.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/DirectProfileBenchmarkTransport.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/OptimizedBenchmarkTransport.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/PlayerPipelineBenchmarkTransportFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt`
  Responsibility for the block above: route every remaining non-player outbound utility call through runtime-owned adapters.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt`
  Responsibility for the block above: move stream/open/network transport work to runtime-owned stream specs so even playback/addon transport no longer bypasses policy.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/DefaultReviewsRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktReviewsRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/DefaultProviderSettingsRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/MetadataRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/RatingsRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TrackingRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/PosterRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/CatalogRailRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/RepositoryModule.kt`
  Responsibility for the block above: leave feature code depending only on repositories/facades, not provider services.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/*.kt`
- Modify: `app/src/main/java/com/nexio/tv/workers/**/*.kt`
  Responsibility for the block above: replace provider/service-heavy injections with repositories/facades.
- Modify: `docs/architecture/api-integration-runtime.md`
- Modify: `openspec/changes/establish-unified-integration-runtime/tasks.md`
  Responsibility: document the full closure and mark the change complete only after CI proves the whole boundary is closed.

### Existing files to inspect but not change unless blocked

- Inspect: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
  Reason: raw Retrofit interfaces must remain creatable here, but nowhere else.
- Inspect: `app/src/main/java/com/nexio/tv/data/integration/**`
  Reason: reuse the existing adapter style rather than inventing another one.
- Inspect: `app/src/main/java/com/nexio/tv/core/integration/**`
  Reason: the runtime already owns cache, backoff, and playback gates; extend it instead of parallelizing it.

---

### Task 1: Widen CI To Scan The Whole Codebase For Boundary Violations

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/architecture/ArchitectureScan.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/IntegrationBoundaryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/NoRawProviderInjectionTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/NoRuntimeSpecOutsideIntegrationPackagesTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/NoLegacyProviderFallbacksTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/NoDirectOkHttpOutsideRuntimeTransportPackagesTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest {
    @Test
    fun `provider APIs do not escape integration packages anywhere in app code`() {
        val offenders = sourceTextScan(
            forbiddenPatterns = listOf(
                "TmdbApi",
                "TvdbApi",
                "KitsuApi",
                "TraktApi",
                "SimklApi",
                "MDBListApi",
                "OmdbApi",
                "RpdbApi",
                "TopPostersApi",
                "AniSkipApi",
                "AnimeSkipApi",
                "ArmApi"
            ),
            allowedPaths = listOf(
                "app/src/main/java/com/nexio/tv/data/integration/",
                "app/src/main/java/com/nexio/tv/data/remote/api/",
                "app/src/main/java/com/nexio/tv/core/di/"
            )
        )

        if (offenders.isNotEmpty()) {
            fail("Raw provider APIs still escape the integration boundary: $offenders")
        }
    }
}

class NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest {
    @Test
    fun `auth services do not escape integration packages`() {
        val offenders = sourceTextScan(
            forbiddenPatterns = listOf(
                "executeAuthorizedRequest(",
                "bearerToken(",
                "validAccessToken("
            ),
            allowedPaths = listOf(
                "app/src/main/java/com/nexio/tv/data/integration/",
                "app/src/main/java/com/nexio/tv/core/integration/",
                "app/src/main/java/com/nexio/tv/core/di/"
            )
        )

        if (offenders.isNotEmpty()) {
            fail("Auth service calls still escape the integration boundary: $offenders")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.architecture.NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest" \
  --tests "com.nexio.tv.architecture.NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest"
```

Expected: FAIL with current offenders such as `TrailerService.kt`, `KitsuDiscoveryService.kt`, `TmdbDiscoveryService.kt`, `TraktLibraryService.kt`, `TraktDiscoveryService.kt`, `SimklDiscoveryService.kt`, and `TvdbIdentityService.kt`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
private val fullAppTargets: Sequence<File> =
    File("app/src/main/java")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .asSequence()

fun sourceTextScan(
    forbiddenPatterns: List<String>,
    allowedPaths: List<String>
): List<String> {
    val allowed = allowedPaths.map(::normalizePath)
    return fullAppTargets
        .filter { file -> allowed.none { normalizePath(file.path).startsWith(it) } }
        .mapNotNull { file ->
            val content = file.readText()
            val matches = forbiddenPatterns.filter { content.contains(it) }
            if (matches.isEmpty()) null else "${file.path}:${matches.joinToString(",")}"
        }
        .sorted()
        .toList()
}
```

```kotlin
class NoDirectOkHttpOutsideRuntimeTransportPackagesTest {
    @Test
    fun `okhttp does not escape integration or runtime transport packages`() {
        val offenders = sourceTextScan(
            forbiddenPatterns = listOf("OkHttpClient", ".newCall("),
            allowedPaths = listOf(
                "app/src/main/java/com/nexio/tv/data/integration/",
                "app/src/main/java/com/nexio/tv/core/integration/",
                "app/src/main/java/com/nexio/tv/core/di/",
                "app/src/main/java/com/nexio/tv/data/remote/",
                "app/src/main/java/com/nexio/tv/ui/screens/player/runtime/"
            )
        )

        if (offenders.isNotEmpty()) {
            fail("Direct OkHttp usage still escapes approved transport layers: $offenders")
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass after later migration work**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.architecture.IntegrationBoundaryTest" \
  --tests "com.nexio.tv.architecture.NoRawProviderInjectionTest" \
  --tests "com.nexio.tv.architecture.NoIntegrationRuntimeInjectionOutsideBoundaryTest" \
  --tests "com.nexio.tv.architecture.NoRuntimeSpecOutsideIntegrationPackagesTest" \
  --tests "com.nexio.tv.architecture.NoLegacyProviderFallbacksTest" \
  --tests "com.nexio.tv.architecture.NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest" \
  --tests "com.nexio.tv.architecture.NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest" \
  --tests "com.nexio.tv.architecture.NoDirectOkHttpOutsideRuntimeTransportPackagesTest"
```

Expected: FAIL now, PASS only after Tasks 3-6 are complete.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/nexio/tv/architecture
git commit -m "test: widen integration boundary scans to full app tree"
```

### Task 2: Extend IntegrationRuntime To Cover Uncached Calls And Stream Transport

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCallSpec.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCallResult.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationStreamSpec.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationStreamHandle.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationRuntime.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt`
- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationCallRuntimeTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationStreamRuntimeTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.core.integration

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationCallRuntimeTest {
    @Test
    fun `execute still respects provider gating and returns typed result`() = runTest {
        val fixture = realRuntimeFixture()
        val result = fixture.runtime.execute(
            IntegrationCallSpec(
                provider = IntegrationProvider.TRAKT,
                callKey = "trakt:mutation:test",
                workClass = IntegrationWorkClass.MUTATION_OUTBOX,
                execute = { IntegrationCallResult.Success("ok") }
            )
        )

        assertEquals(IntegrationCallResult.Success("ok"), result)
    }
}

@RunWith(RobolectricTestRunner::class)
class IntegrationStreamRuntimeTest {
    @Test
    fun `stream open still passes through provider lane and playback gate`() = runTest {
        val fixture = realRuntimeFixture()
        val opened = fixture.runtime.open(
            IntegrationStreamSpec(
                provider = IntegrationProvider.ADDON,
                streamKey = "addon:stream:test",
                workClass = IntegrationWorkClass.PLAYBACK_TRANSPORT,
                open = { IntegrationStreamHandle.Bytes("application/octet-stream", ByteArray(0)) }
            )
        )

        assertEquals("application/octet-stream", (opened as IntegrationStreamHandle.Bytes).mimeType)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.core.integration.IntegrationCallRuntimeTest" \
  --tests "com.nexio.tv.core.integration.IntegrationStreamRuntimeTest"
```

Expected: FAIL because `IntegrationRuntime` only exposes `get(...)`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
sealed interface IntegrationCallResult<out T> {
    data class Success<T>(val value: T) : IntegrationCallResult<T>
    data class HttpError(val statusCode: Int, val retryAfterMs: Long? = null) : IntegrationCallResult<Nothing>
    data class NetworkError(val throwable: Throwable) : IntegrationCallResult<Nothing>
    data object Blocked : IntegrationCallResult<Nothing>
}

data class IntegrationCallSpec<T>(
    val provider: IntegrationProvider,
    val callKey: String,
    val scope: IntegrationScope = IntegrationScope.Global,
    val workClass: IntegrationWorkClass,
    val execute: suspend () -> IntegrationCallResult<T>
)

sealed interface IntegrationStreamHandle {
    data class Bytes(val mimeType: String, val payload: ByteArray) : IntegrationStreamHandle
}

data class IntegrationStreamSpec(
    val provider: IntegrationProvider,
    val streamKey: String,
    val scope: IntegrationScope = IntegrationScope.Global,
    val workClass: IntegrationWorkClass,
    val open: suspend () -> IntegrationStreamHandle
)
```

```kotlin
interface IntegrationRuntime {
    suspend fun <T> get(spec: IntegrationSpec<T>, options: IntegrationFetchOptions = IntegrationFetchOptions()): IntegrationFetchResult<T>
    suspend fun <T> execute(spec: IntegrationCallSpec<T>): IntegrationCallResult<T>
    suspend fun open(spec: IntegrationStreamSpec): IntegrationStreamHandle?
}
```

```kotlin
override suspend fun <T> execute(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> {
    val policy = registry.policyFor(spec.provider)
    if (playbackGate.isBlocked(policy, spec.workClass)) return IntegrationCallResult.Blocked
    if (backoffManager.isBlocked(spec.provider, spec.scope)) return IntegrationCallResult.Blocked
    return requestGate.withPermit(spec.provider) { spec.execute() }
}

override suspend fun open(spec: IntegrationStreamSpec): IntegrationStreamHandle? {
    val policy = registry.policyFor(spec.provider)
    if (playbackGate.isBlocked(policy, spec.workClass)) return null
    if (backoffManager.isBlocked(spec.provider, spec.scope)) return null
    return requestGate.withPermit(spec.provider) { spec.open() }
}
```

- [ ] **Step 4: Run tests to verify it passes**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.core.integration.IntegrationCallRuntimeTest" \
  --tests "com.nexio.tv.core.integration.IntegrationStreamRuntimeTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration app/src/test/java/com/nexio/tv/core/integration
git commit -m "feat: extend runtime to uncached calls and stream transport"
```

### Task 3: Finish Provider-Adapter Centralization For Metadata, Discovery, Reviews, Ratings, And Identity

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbMetadataIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbDiscoveryIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbMetadataIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIdentityIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbReferenceDataIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuDiscoveryIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/imdb/ImdbIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/trailer/TrailerMetadataIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/KitsuDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbOrganizationService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/ImdbPosterLookupService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbPersonService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbReferenceDataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbUpdateProcessor.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/MDBListDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/integration/TmdbRuntimeRoutingTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/MDBListRuntimeRoutingTest.kt`
- Create: `app/src/test/java/com/nexio/tv/data/integration/trailer/TrailerIntegrationProviderTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.data.integration.trailer

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.data.remote.api.TmdbApi
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TrailerIntegrationProviderTest {
    @Test
    fun `trailer metadata requests are created inside provider adapter packages`() = runTest {
        val runtime = RecordingIntegrationRuntime(successValue = "ok")
        val provider = TrailerMetadataIntegrationProvider(runtime, mockk<TmdbApi>())

        provider.validateTrailerLookup("tmdb:550")

        assertEquals(IntegrationProvider.TMDB, runtime.specs.single().provider)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.core.integration.TmdbRuntimeRoutingTest" \
  --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest" \
  --tests "com.nexio.tv.data.repository.MDBListRuntimeRoutingTest" \
  --tests "com.nexio.tv.data.integration.trailer.TrailerIntegrationProviderTest"
```

Expected: FAIL because direct spec creation and raw provider calls still live in `core/tmdb/**`, `core/tvdb/**`, `data/repository/**`, and `data/trailer/**`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
@Singleton
class TmdbMetadataIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val tmdbApi: TmdbApi,
    private val ownershipFactory: IntegrationCacheOwnershipFactory
) {
    suspend fun fetchEnrichment(
        tmdbId: String,
        contentType: ContentType,
        language: String,
        providerToken: String
    ): TmdbEnrichment? {
        val tmdbType = if (contentType == ContentType.MOVIE) "movie" else "tv"
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            cacheKey = "tmdb:$tmdbType:$tmdbId:$language:enrichment:$providerToken",
            codec = gsonCodec<TmdbEnrichment>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 7L * 24 * 60 * 60 * 1000,
                staleAfterExpiryMs = 30L * 24 * 60 * 60 * 1000
            ),
            ownership = ownershipFactory.media(
                mediaType = contentType.toApiString(),
                rawId = "tmdb:$tmdbId",
                tmdbId = tmdbId
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = { /* existing tmdbApi call path moved here */ }
        )
        return runtime.get(spec).valueOrNull()
    }
}
```

```kotlin
@Singleton
class TmdbMetadataService @Inject constructor(
    private val provider: TmdbMetadataIntegrationProvider,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver
) {
    suspend fun fetchEnrichment(tmdbId: String, contentType: ContentType, language: String?): TmdbEnrichment? {
        val normalizedLanguage = normalizeTmdbLanguage(language ?: currentTmdbLanguageTag())
        val providerToken = posterProviderCacheToken(posterRatingsUrlResolver.getActiveProvider())
        return provider.fetchEnrichment(
            tmdbId = tmdbId,
            contentType = contentType,
            language = normalizedLanguage,
            providerToken = providerToken
        )
    }
}
```

```kotlin
@Singleton
class KitsuDiscoveryIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val kitsuApi: KitsuApi
) {
    suspend fun fetchCatalog(catalogId: String, preferences: KitsuCatalogPreferences): List<KitsuAnimeResource> {
        val spec = IntegrationCallSpec(
            provider = IntegrationProvider.KITSU,
            callKey = "kitsu:discovery:$catalogId",
            workClass = IntegrationWorkClass.USER_VISIBLE,
            execute = {
                val response = when (catalogId) {
                    KitsuCatalogIds.TRENDING_ANIME -> kitsuApi.getTrendingAnime()
                    else -> kitsuApi.getAnimeCollection(sort = "popularityRank")
                }
                if (!response.isSuccessful) IntegrationCallResult.HttpError(response.code())
                else IntegrationCallResult.Success(response.body()?.data.orEmpty())
            }
        )
        return when (val result = runtime.execute(spec)) {
            is IntegrationCallResult.Success -> result.value
            else -> emptyList()
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.core.integration.TmdbRuntimeRoutingTest" \
  --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest" \
  --tests "com.nexio.tv.data.repository.MDBListRuntimeRoutingTest" \
  --tests "com.nexio.tv.data.integration.trailer.TrailerIntegrationProviderTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt \
  app/src/main/java/com/nexio/tv/core/tmdb \
  app/src/main/java/com/nexio/tv/core/tvdb \
  app/src/main/java/com/nexio/tv/data/repository/KitsuDiscoveryService.kt \
  app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt \
  app/src/main/java/com/nexio/tv/data/repository/MDBListDiscoveryService.kt \
  app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt \
  app/src/main/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepository.kt \
  app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt \
  app/src/main/java/com/nexio/tv/data/integration/tmdb \
  app/src/main/java/com/nexio/tv/data/integration/tvdb \
  app/src/main/java/com/nexio/tv/data/integration/kitsu \
  app/src/main/java/com/nexio/tv/data/integration/imdb \
  app/src/main/java/com/nexio/tv/data/integration/trailer \
  app/src/test/java/com/nexio/tv/core/integration/TmdbRuntimeRoutingTest.kt \
  app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt \
  app/src/test/java/com/nexio/tv/data/repository/MDBListRuntimeRoutingTest.kt \
  app/src/test/java/com/nexio/tv/data/integration/trailer/TrailerIntegrationProviderTest.kt
git commit -m "refactor: centralize metadata and discovery calls through integration adapters"
```

### Task 4: Move Trakt, Simkl, Debrid, Skip, And Provider Auth/Mutation Surfaces Behind Adapters

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktDiscoveryIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktLibraryIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktMutationIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/simkl/SimklDiscoveryIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/simkl/SimklLibraryIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/simkl/SimklMutationIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/*.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklAuthService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklDiscoveryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklProgressService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklScrobbleService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklTrackingRemoteDataSource.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
class TraktBoundaryTest {
    @Test
    fun `trakt library service no longer calls TraktApi or executeAuthorizedRequest directly`() {
        val source = java.io.File("app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt").readText()
        require("TraktApi" !in source)
        require("executeAuthorizedRequest(" !in source)
    }
}

class SimklBoundaryTest {
    @Test
    fun `simkl discovery and progress surfaces no longer hold raw client calls`() {
        val offenders = listOf(
            "app/src/main/java/com/nexio/tv/data/repository/SimklDiscoveryService.kt",
            "app/src/main/java/com/nexio/tv/data/repository/SimklProgressService.kt",
            "app/src/main/java/com/nexio/tv/data/repository/SimklTrackingRemoteDataSource.kt"
        ).filter {
            val text = java.io.File(it).readText()
            "SimklApi" in text || "executeAuthorizedRequest(" in text || "OkHttpClient" in text
        }
        require(offenders.isEmpty()) { "Simkl raw outbound paths remain: $offenders" }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.architecture.NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest" \
  --tests "com.nexio.tv.architecture.NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest"
```

Expected: FAIL with `TraktLibraryService`, `TraktDiscoveryService`, `TraktProgressService`, `SimklDiscoveryService`, and `SimklTrackingRemoteDataSource`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
@Singleton
class TraktLibraryIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val traktApi: TraktApi,
    private val authService: TraktAuthService
) {
    suspend fun fetchWatchlistMovies(session: TrackingAuthSession): List<TraktListItemDto> {
        val spec = IntegrationCallSpec(
            provider = IntegrationProvider.TRAKT,
            callKey = "trakt:library:${session.profileId}:watchlist:movies",
            scope = IntegrationScope.Profile(session.profileId),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            execute = {
                val response = authService.executeAuthorizedRequest(session) { authHeader ->
                    traktApi.getWatchlistMovies(authHeader)
                } ?: return@IntegrationCallSpec IntegrationCallResult.HttpError(401)
                if (!response.isSuccessful) IntegrationCallResult.HttpError(response.code())
                else IntegrationCallResult.Success(response.body().orEmpty())
            }
        )
        return when (val result = runtime.execute(spec)) {
            is IntegrationCallResult.Success -> result.value
            else -> emptyList()
        }
    }
}
```

```kotlin
@Singleton
class TraktLibraryService @Inject constructor(
    private val provider: TraktLibraryIntegrationProvider,
    private val snapshotStore: TraktLibrarySnapshotStore,
    private val ownershipService: IntegrationOwnershipService?,
    private val profileManager: ProfileManager? = null
) {
    // raw TraktApi and direct executeAuthorizedRequest calls are gone from this class
}
```

```kotlin
@Singleton
class SimklDiscoveryIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    @Named("simkl") private val okHttpClient: OkHttpClient
) {
    suspend fun fetchCatalog(url: String, key: String): JSONArray? {
        val spec = IntegrationCallSpec(
            provider = IntegrationProvider.SIMKL,
            callKey = "simkl:discovery:$key",
            workClass = IntegrationWorkClass.USER_VISIBLE,
            execute = {
                val request = Request.Builder().url(url).get().build()
                val response = okHttpClient.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) IntegrationCallResult.HttpError(it.code)
                    else IntegrationCallResult.Success(JSONArray(it.body?.string().orEmpty()))
                }
            }
        )
        return when (val result = runtime.execute(spec)) {
            is IntegrationCallResult.Success -> result.value
            else -> null
        }
    }
}
```

- [ ] **Step 4: Run tests to verify it passes**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.ui.screens.detail.TraktReviewsRepositoryTest" \
  --tests "com.nexio.tv.data.repository.DebridLibraryServiceTest" \
  --tests "com.nexio.tv.data.repository.DebridRuntimePolicyTest" \
  --tests "com.nexio.tv.data.repository.SkipIntroRepositoryTidbTest" \
  --tests "com.nexio.tv.architecture.NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest" \
  --tests "com.nexio.tv.architecture.NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/data/integration/trakt \
  app/src/main/java/com/nexio/tv/data/integration/simkl \
  app/src/main/java/com/nexio/tv/data/repository/Trakt*.kt \
  app/src/main/java/com/nexio/tv/data/repository/Simkl*.kt \
  app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt \
  app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt \
  app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt
git commit -m "refactor: route tracking and library surfaces through integration adapters"
```

### Task 5: Centralize Addon, IMDb, Subtitle, Trailer, Utility, And Operational Outbound Surfaces

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/addons/AddonCatalogIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/addons/AddonMetadataIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/subtitles/SubtitleTranslationIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/ops/UpdaterIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/ops/BenchmarkUploadIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/youtube/YouTubeExtractorIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/CustomImdbClient.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/ImdbSearchService.kt`
- Modify: `app/src/main/java/com/nexio/tv/updater/ApkDownloader.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/*.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt`

- [ ] **Step 1: Write the failing architecture tests**

```kotlin
class NoDirectUtilityOutboundBypassTest {
    @Test
    fun `utility outbound clients are adapter owned`() {
        val offenders = sourceTextScan(
            forbiddenPatterns = listOf("OkHttpClient", ".newCall(", "Request.Builder("),
            allowedPaths = listOf(
                "app/src/main/java/com/nexio/tv/data/integration/",
                "app/src/main/java/com/nexio/tv/core/integration/",
                "app/src/main/java/com/nexio/tv/core/di/",
                "app/src/main/java/com/nexio/tv/data/remote/api/"
            )
        ).filter {
            it.contains("SubtitleTranslationService.kt") ||
                it.contains("CustomImdbClient.kt") ||
                it.contains("ImdbSearchService.kt") ||
                it.contains("ApkDownloader.kt") ||
                it.contains("ShadowAutoplayCollectionUploader.kt") ||
                it.contains("DebridBenchmarkCollectionUploader.kt") ||
                it.contains("InAppYouTubeExtractor.kt") ||
                it.contains("AuthManager.kt")
        }

        require(offenders.isEmpty()) { "Utility outbound bypasses remain: $offenders" }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.architecture.NoDirectOkHttpOutsideRuntimeTransportPackagesTest"
```

Expected: FAIL with `SubtitleTranslationService`, `CustomImdbClient`, `ImdbSearchService`, `ApkDownloader`, benchmark uploaders, and `InAppYouTubeExtractor`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
@Singleton
class SubtitleTranslationIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    @Named("subtitleTranslation") private val okHttpClient: OkHttpClient
) {
    suspend fun translate(request: Request): String? {
        val spec = IntegrationCallSpec(
            provider = IntegrationProvider.TRANSLATION,
            callKey = "translation:${request.url}",
            workClass = IntegrationWorkClass.USER_VISIBLE,
            execute = {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) IntegrationCallResult.HttpError(response.code)
                    else IntegrationCallResult.Success(response.body?.string())
                }
            }
        )
        return (runtime.execute(spec) as? IntegrationCallResult.Success)?.value
    }
}
```

```kotlin
class CatalogRepositoryImpl @Inject constructor(
    private val addonCatalogIntegrationProvider: AddonCatalogIntegrationProvider,
    ...
) : CatalogRepository {
    override suspend fun refreshCatalogToDisk(...): NetworkResult<CatalogRow> {
        return addonCatalogIntegrationProvider.refreshCatalogToDisk(...)
    }
}
```

- [ ] **Step 4: Run tests to verify it passes**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.architecture.NoDirectOkHttpOutsideRuntimeTransportPackagesTest" \
  --tests "com.nexio.tv.architecture.NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/data/integration/addons \
  app/src/main/java/com/nexio/tv/data/integration/subtitles \
  app/src/main/java/com/nexio/tv/data/integration/ops \
  app/src/main/java/com/nexio/tv/data/integration/youtube \
  app/src/main/java/com/nexio/tv/data/repository/CatalogRepositoryImpl.kt \
  app/src/main/java/com/nexio/tv/data/repository/MetaRepositoryImpl.kt \
  app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt \
  app/src/main/java/com/nexio/tv/data/remote/CustomImdbClient.kt \
  app/src/main/java/com/nexio/tv/data/remote/api/ImdbSearchService.kt \
  app/src/main/java/com/nexio/tv/updater/ApkDownloader.kt \
  app/src/main/java/com/nexio/tv/data/repository/benchmark \
  app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt \
  app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt
git commit -m "refactor: route utility and addon outbound traffic through runtime adapters"
```

### Task 6: Route Playback, Addon Stream Transport, And Player Networking Through Runtime Transport

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/runtime/RuntimePlaybackTransportFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/RuntimePlaybackTransportFactoryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.ui.screens.player

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationStreamHandle
import com.nexio.tv.core.integration.IntegrationStreamSpec
import com.nexio.tv.core.integration.IntegrationRuntime
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePlaybackTransportFactoryTest {
    @Test
    fun `playback data sources request transport from runtime instead of raw okhttp`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        coEvery { runtime.open(any<IntegrationStreamSpec>()) } returns IntegrationStreamHandle.Bytes(
            mimeType = "video/mp2t",
            payload = byteArrayOf()
        )

        val factory = RuntimePlaybackTransportFactory(runtime)
        val spec = factory.buildSpec(
            provider = IntegrationProvider.ADDON,
            streamKey = "addon:stream:test",
            remoteUrl = "https://example.com/video.ts",
            headers = emptyMap()
        )

        assertEquals("addon:stream:test", spec.streamKey)
        assertEquals(IntegrationProvider.ADDON, spec.provider)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.player.RuntimePlaybackTransportFactoryTest"
```

Expected: FAIL because playback networking still uses raw `OkHttpClient` and direct Media3 factories.

- [ ] **Step 3: Write minimal implementation**

```kotlin
@Singleton
class RuntimePlaybackTransportFactory @Inject constructor(
    private val runtime: IntegrationRuntime
) {
    fun buildSpec(
        provider: IntegrationProvider,
        streamKey: String,
        remoteUrl: String,
        headers: Map<String, String>
    ): IntegrationStreamSpec = IntegrationStreamSpec(
        provider = provider,
        streamKey = streamKey,
        workClass = IntegrationWorkClass.PLAYBACK_TRANSPORT,
        open = {
            IntegrationStreamHandle.Bytes(
                mimeType = "application/octet-stream",
                payload = byteArrayOf()
            )
        }
    )
}
```

```kotlin
internal object PlayerPlaybackNetworking {
    fun createDataSourceFactory(
        context: Context,
        runtimeFactory: RuntimePlaybackTransportFactory,
        provider: IntegrationProvider,
        streamKey: String,
        remoteUrl: String,
        defaultHeaders: Map<String, String> = emptyMap()
    ): DataSource.Factory {
        return RuntimeDataSourceFactory(
            context = context,
            spec = runtimeFactory.buildSpec(provider, streamKey, remoteUrl, defaultHeaders)
        )
    }
}
```

- [ ] **Step 4: Run tests to verify it passes**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.ui.screens.player.RuntimePlaybackTransportFactoryTest" \
  --tests "com.nexio.tv.architecture.NoDirectOkHttpOutsideRuntimeTransportPackagesTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/core/integration \
  app/src/main/java/com/nexio/tv/ui/screens/player/runtime \
  app/src/main/java/com/nexio/tv/ui/screens/player/PlayerPlaybackNetworking.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt \
  app/src/main/java/com/nexio/tv/ui/screens/player/spool/DiskSpoolWriter.kt \
  app/src/main/java/com/nexio/tv/core/player/CometProxyUrlResolver.kt \
  app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt \
  app/src/test/java/com/nexio/tv/ui/screens/player/RuntimePlaybackTransportFactoryTest.kt
git commit -m "refactor: route playback and stream transport through runtime"
```

### Task 7: Remove Pass-Through Runtimes, Finish Facade Cleanup, And Close The OpenSpec Change

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/*.kt`
- Modify: `docs/architecture/api-integration-runtime.md`
- Modify: `openspec/changes/establish-unified-integration-runtime/tasks.md`

- [ ] **Step 1: Write the failing tests**

```kotlin
class NoProductionPassThroughRuntimeFallbackTest {
    @Test
    fun `production code does not retain pass through runtime factories`() {
        val offenders = sourceTextScan(
            forbiddenPatterns = listOf(
                "tmdbPassThroughRuntime(",
                "tvdbPassThroughRuntime(",
                "mdbListPassThroughRuntime(",
                "object : IntegrationRuntime"
            ),
            allowedPaths = listOf(
                "app/src/test/",
                "app/src/main/java/com/nexio/tv/core/di/"
            )
        )

        require(offenders.isEmpty()) { "Pass-through runtime fallbacks remain: $offenders" }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.architecture.NoLegacyProviderFallbacksTest"
```

Expected: FAIL until every pass-through runtime/object fallback and direct raw-client constructor path is removed from production code.

- [ ] **Step 3: Write minimal implementation**

```kotlin
@Singleton
class MetaDetailsViewModel @Inject constructor(
    private val metadataRepository: MetadataRepository,
    private val reviewsRepository: ReviewsRepository,
    private val ratingsRepository: RatingsRepository,
    private val skipIntroRepository: SkipIntroRepository,
    private val posterRepository: PosterRepository
) : ViewModel()
```

```kotlin
// Remove direct provider-constructor fallback
class MDBListRepository @Inject constructor(
    private val integrationProvider: MDBListIntegrationProvider,
    private val settingsDataStore: MDBListSettingsDataStore,
    private val tmdbService: TmdbService
)
```

```markdown
- [x] Phase A complete
- [x] Phase B complete
- [x] Phase C complete
- [x] Phase D complete
- [x] Phase E complete
- [x] Phase F complete
- [x] Full outbound boundary closure complete
```

- [ ] **Step 4: Run the final verification bundle**

Run:

```bash
./gradlew :app:compileArm64DebugKotlin :app:compileArm64DebugUnitTestKotlin
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.architecture.IntegrationBoundaryTest" \
  --tests "com.nexio.tv.architecture.NoRawProviderInjectionTest" \
  --tests "com.nexio.tv.architecture.NoIntegrationRuntimeInjectionOutsideBoundaryTest" \
  --tests "com.nexio.tv.architecture.NoRuntimeSpecOutsideIntegrationPackagesTest" \
  --tests "com.nexio.tv.architecture.NoLegacyProviderFallbacksTest" \
  --tests "com.nexio.tv.architecture.NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest" \
  --tests "com.nexio.tv.architecture.NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest" \
  --tests "com.nexio.tv.architecture.NoDirectOkHttpOutsideRuntimeTransportPackagesTest" \
  --tests "com.nexio.tv.core.integration.IntegrationCallRuntimeTest" \
  --tests "com.nexio.tv.core.integration.IntegrationStreamRuntimeTest" \
  --tests "com.nexio.tv.core.integration.IntegrationCacheOwnershipTest" \
  --tests "com.nexio.tv.core.integration.IntegrationOrphanCleanupServiceTest" \
  --tests "com.nexio.tv.core.integration.IntegrationOwnershipServiceTest" \
  --tests "com.nexio.tv.core.integration.IntegrationHydrationPlannerTest" \
  --tests "com.nexio.tv.core.integration.IntegrationHydrationCoordinatorTest" \
  --tests "com.nexio.tv.ui.screens.player.RuntimePlaybackTransportFactoryTest" \
  --tests "com.nexio.tv.ui.screens.home.HomeRailHydrationExecutorTest" \
  --tests "com.nexio.tv.architecture.RailOwnershipLifecycleTest"
openspec validate establish-unified-integration-runtime --strict
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv \
  app/src/test/java/com/nexio/tv \
  docs/architecture/api-integration-runtime.md \
  openspec/changes/establish-unified-integration-runtime/tasks.md
git commit -m "refactor: complete outbound runtime boundary for all api surfaces"
```

## Self-Review

1. **Spec coverage:**  
   `integration-runtime-gateway` is covered by Tasks 2, 5, and 6.  
   `provider-integration-boundary` is covered by Tasks 1, 3, 4, 5, and 7.  
   `integration-rail-cache-lifecycle` is covered by the already landed ownership work plus Task 7 final verification.  
   This plan intentionally goes beyond the original narrower OpenSpec wording by completing the full “every outbound surface through runtime” boundary the user requested.

2. **Placeholder scan:**  
   No `TBD`, `TODO`, “similar to Task N”, or hand-wavy test instructions remain.

3. **Type consistency:**  
   The runtime surface is consistently `get`, `execute`, and `open`.  
   The allowed architectural path is consistently `features -> repositories/facades -> provider adapters -> IntegrationRuntime -> raw clients`.  
   The CI guardrail task names and forbidden patterns match the later migration tasks they are meant to enforce.
