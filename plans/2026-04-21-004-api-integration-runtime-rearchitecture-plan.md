# API Integration Runtime Re-architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Nexio's fractured provider-specific cache and rate-limit behavior with one typed integration runtime that becomes the mandatory gateway for every non-playback external API call, adds a provider-adapter boundary around all existing Retrofit/auth services, persists 429 backoff, and defaults every provider to serial network starts until higher concurrency is explicitly validated and approved.

**Architecture:** Keep the central-runtime direction from the April 21 analysis, but do not begin with a raw-HTTP grand rewrite. Build a provider-neutral `IntegrationRuntime` on top of the existing Retrofit/auth services, then make that runtime the required choke point for all non-playback integrations before enabling deep cache behavior provider by provider. Add a Room-backed cache index plus blob store, a persisted per-provider backoff store, cache-policy modes (`Disabled`, `ObserveOnly`, `CacheFirst`, `Mutation`), and a strict provider-adapter boundary so raw Retrofit/auth services become implementation details rather than app-wide dependencies. Playback transport and addon stream transport remain explicitly out of the first-wave runtime scope.

**Deferred Remote Cache:** Cloudflare Worker + R2 remote cache is explicitly deferred until after the Android runtime migration is stable. The runtime should gain a cache-store abstraction seam now, but the first implementation remains local-only.

**Tech Stack:** Kotlin, Coroutines, Hilt, Retrofit, OkHttp, Coil, Room, WorkManager, JUnit, MockK

---

## Secondary Review Verdict

This review agrees with the core conclusion in `plans/2026-04-21-003-api-network-cache-analysis-postanalysis.md`: the system is not "missing cache", it is missing a single authority for cache, concurrency, and backoff policy.

This review disagrees with one part of the proposed direction: the first implementation should not require a raw `NetworkRequest` DSL or a full "everything routes through one custom HTTP stack" rewrite. That would make the migration larger than necessary and would force auth, retry, and DTO mapping logic for Trakt, Simkl, TVDB, Real-Debrid, Premiumize, TorBox, EasyDebrid, OMDb, TheIntroDb, AniSkip, AnimeSkip, RPDB, and TopPosters to move all at once.

The agreement proposal is:

1. Keep the central runtime.
2. Make it typed and provider-neutral.
3. Put it above existing Retrofit/auth services first.
4. Make it the mandatory gateway for all non-playback integrations before deep cache migration.
5. Enable cache behavior behind that gateway in phases.
6. Keep playback transport and addon stream transport out of wave one.

That gives Nexio one place to enforce:

- fresh-cache-before-network
- one-provider-one-policy
- persisted `Retry-After` handling
- default serial network starts
- explicit playback and startup blocking for non-critical work
- a single debugging and kill-switch surface
- a testable "no bypass" integration boundary
- extension points for every future provider

## Scope Check

This is too large for one blind implementation sprint. It spans six lifecycle phases that must land in order:

1. Runtime foundation and storage
2. Control-plane migration: mandatory gateway and provider-adapter boundary
3. Cache/data migration: enable cache-first behavior provider by provider
4. Legacy decommission: delete bypasses, direct injections, and shadow caches
5. Steady-state verification and guardrails
6. Rail ownership, hydration, and long-run cache lifecycle

This document stays as one umbrella plan because the immediate goal is architectural agreement across the full lifecycle. Execution should still happen as a sequence of small commits and testable slices, but the plan is not complete until the codebase reaches the final no-bypass architecture and the legacy path is deleted.

## Scope Boundaries

- Do not route `@Named("playback")` media transport through the new runtime in the first wave.
- Do not route `@Named("addonStreams")` stream fetch transport through the new runtime in the first wave.
- Do not delete legacy caches on day one. Keep them until the last caller for that provider has moved.
- Do not collapse every provider into a single canonical media graph before the runtime exists. Add provider-neutral identity support now; add rail-aware ownership after the runtime proves stable.
- Do not keep remote RPDB/TopPosters URLs as the final UI contract once poster migration begins.

## Agreement Decisions

### Decision 1: Build a typed runtime above existing providers

Use a spec like this:

```kotlin
data class IntegrationSpec<T>(
    val provider: IntegrationProvider,
    val cacheKey: String,
    val codec: IntegrationCodec<T>,
    val cachePolicy: IntegrationCachePolicy = IntegrationCachePolicy.Disabled,
    val workClass: IntegrationWorkClass,
    val scope: IntegrationScope = IntegrationScope.Global,
    val load: suspend () -> IntegrationLoadResult<T>
)
```

Not this:

```kotlin
data class NetworkRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray?
)
```

Reason: the typed runtime is enough to centralize cache, concurrency, 429 backoff, and pause policy across all current providers while reusing working Retrofit/auth code.

### Decision 1a: Runtime-first migration, cache-second migration

The runtime must become the mandatory control plane before every provider has final cache semantics.

Early providers may use:

```kotlin
sealed interface IntegrationCachePolicy {
    data object Disabled : IntegrationCachePolicy
    data class ObserveOnly(val reason: String) : IntegrationCachePolicy
    data class CacheFirst(
        val ttlMs: Long,
        val staleAfterExpiryMs: Long = 0L
    ) : IntegrationCachePolicy
    data object Mutation : IntegrationCachePolicy
}
```

That means the acceptable transitional state is:

```text
all non-playback integrations enter IntegrationRuntime
some providers are pass-through or observe-only
cache-first is enabled provider by provider later
```

The unacceptable transitional state is:

```text
some providers enter IntegrationRuntime
other providers still bypass it from feature code, workers, or random repositories
```

TTL and stale windows must live in `IntegrationCachePolicy`, not be duplicated as separate authoritative fields on `IntegrationSpec`. If both exist during the transition, `IntegrationCachePolicy` is the source of truth and the duplicate fields must be removed before Task 11 closes.

### Decision 2: Default every provider to serial starts

Every provider starts here:

```kotlin
IntegrationProviderPolicy(
    maxConcurrentNetworkStarts = 1,
    allowDuringPlayback = false,
    allowStaleWhilePaused = true,
    defaultBackoffOn429Ms = 2_000L
)
```

The only way to raise concurrency above `1` is:

1. add a provider-specific benchmark or production-safe validation test
2. document the justification in code
3. update the policy registry in a focused commit

### Decision 3: Persist backoff and policy state

If a provider returns `429` or a retryable `5xx`, store the next allowed start time in durable storage keyed by provider and scope:

```kotlin
@Entity(tableName = "integration_provider_backoff")
data class IntegrationProviderBackoffEntity(
    @PrimaryKey val key: String,
    val provider: String,
    val scopeKey: String,
    val blockedUntilEpochMs: Long,
    val statusCode: Int?,
    val reason: String?,
    val updatedAtEpochMs: Long
)
```

That prevents a cold start from forgetting a recent rate limit and immediately slamming the same service again.

### Decision 3a: Add the remote-cache seam now, but keep implementation local-only

The runtime should depend on:

```kotlin
interface IntegrationCacheStore {
    suspend fun <T> readFresh(spec: IntegrationSpec<T>): T?
    suspend fun <T> readStale(spec: IntegrationSpec<T>): T?
    suspend fun <T> write(spec: IntegrationSpec<T>, value: T)
}
```

The first implementation is:

```text
LocalIntegrationCacheStore
    ├── Room cache index
    └── local blob files
```

Future shape, explicitly deferred:

```text
TieredIntegrationCacheStore
    ├── LocalIntegrationCacheStore
    └── RemoteIntegrationCacheStore
          └── Cloudflare Worker + R2
```

The Android migration must add the seam now, but it must not expand scope to Worker/R2 implementation.

### Decision 4: Migrate images into the runtime, not around it

RPDB and TopPosters must stop being "remote URL strings that Coil decides to fetch whenever it wants". The runtime should own poster freshness and network starts. Coil should decode bytes or files that the runtime already resolved.

### Decision 5: Rename the mutation coordinator once multiple providers use it

`TraktMutationOutboxCoordinator` is no longer a truthful name once it carries Simkl and future provider traffic. Rename it after the read-side runtime is in place.

### Decision 6: Enforce a provider-adapter boundary

The final codebase must have four layers:

```text
UI / ViewModels / Workers
        ↓
domain-facing repositories / facades
        ↓
provider integration adapters
        ↓
IntegrationRuntime
        ↓
existing Retrofit/auth services
        ↓
network
```

The runtime alone is not enough. Random app code must not be allowed to build `IntegrationSpec`s or call provider APIs directly. Raw Retrofit APIs and provider auth services survive initially, but only provider adapters may call them.

### Decision 6a: Keep feature code on repository/facade contracts, not runtime contracts

The final app-facing layer should be explicit:

```text
MetadataRepository
CatalogRailRepository
RatingsRepository
ReviewsRepository
PosterRepository
TrackingRepository
SkipIntroRepository
DebridLibraryRepository
ProviderSettingsRepository
```

Feature code should depend on these contracts, not on provider adapters and not on `IntegrationRuntime`.

Allowed runtime callers:

```text
provider integration adapters
core integration internals
explicitly approved infrastructure coordinators
```

Disallowed runtime callers:

```text
ViewModels
screens
routers
workers
feature-facing repositories
```

### Decision 7: Make typed codecs real, not nominal

The runtime is only truly typed if cache codecs are real. The codec contract should be:

```kotlin
interface IntegrationCodec<T> {
    val mimeType: String
    fun encode(value: T): ByteArray
    fun decode(bytes: ByteArray): T
}
```

At minimum the plan must include:

```text
String codec
JSON/object codec
list/map JSON codec
file/binary codec
```

The runtime must use the codec for disk read/write rather than casting `Any` from a generic JSON parser.

### Decision 8: Separate playback-resolution work from ordinary metadata work

Debrid account/library/config APIs and debrid playback-adjacent resolution should not be squeezed into the same work class as ordinary metadata reads.

Use:

```kotlin
enum class IntegrationWorkClass {
    USER_VISIBLE,
    PLAYBACK_CRITICAL,
    PLAYBACK_RESOLUTION,
    SCROBBLE,
    MUTATION_OUTBOX,
    BACKGROUND_HYDRATION,
    PREFETCH,
    MAINTENANCE
}
```

Meaning:

```text
PLAYBACK_CRITICAL: metadata needed to keep playback-adjacent UI coherent
PLAYBACK_RESOLUTION: debrid / link-resolution / playback-adjacent provider work
```

Scope clarification:

```text
debrid account / library / configuration APIs: in scope
debrid availability and playback-adjacent resolution APIs: in scope as PLAYBACK_RESOLUTION when they are non-transport integration work
playback media transport bytes: out of scope in wave one
addon stream transport bytes: out of scope in wave one
```

## Approval-Blocking Invariants

The plan is not approved unless it satisfies all of these:

1. Every non-playback external API call enters `IntegrationRuntime` before final rollout.
2. No ViewModel, worker, router, or feature-facing repository may inject raw provider Retrofit APIs, provider-specific OkHttp clients, or call provider auth execute methods directly.
3. `IntegrationSpec`s are created only in provider adapter packages or approved spec factories.
4. No ViewModel, worker, router, screen, or feature-facing repository may inject `IntegrationRuntime` directly.
5. The runtime supports `Disabled`, `ObserveOnly`, `CacheFirst`, and `Mutation` cache-policy modes so control-plane migration can complete before every provider has final cache semantics.
6. CI architecture tests fail if new bypasses are introduced.
7. The final lifecycle includes deletion of transitional kill-switches and direct-call fallbacks after migration validation.
8. The later rail-ownership lifecycle is planned explicitly rather than left as an implied future rewrite.

## Lifecycle Phases

### Phase A: Foundation

Build runtime primitives, provider policy registry, backoff store, cache store, and base telemetry.

**Exit criteria:**

- `IntegrationRuntime` exists and is injected via Hilt.
- provider policy registry covers every in-scope integration
- persisted backoff store exists
- runtime test harness exists

### Phase B: Control-Plane Migration

Create provider adapters and rebind all non-playback integrations so every call enters the runtime, even if some providers are still `Disabled` or `ObserveOnly`.

**Exit criteria:**

- all non-playback integrations enter the runtime
- feature code no longer injects provider APIs directly
- provider adapters are the only allowed raw Retrofit/auth callers
- CI architecture tests enforce the boundary

### Phase C: Cache/Data Migration

Enable `CacheFirst` provider by provider, starting with the lowest-risk read paths and ending with the highest-risk invalidation-heavy paths.

**Exit criteria:**

- Kitsu, OMDb, skip-intro, TMDB, MDBList, Trakt reviews, RPDB/TopPosters, and TVDB have explicit cache-policy decisions
- providers that stay non-cacheable are explicitly `Disabled` or `Mutation`, not bypasses
- runtime metrics show fresh-hit, stale-hit, blocked, and network-start counts by provider

### Phase D: Legacy Decommission

Delete bypass paths, old direct injections, obsolete shadow caches, and temporary feature flags.

**Exit criteria:**

- no direct provider API injection outside adapter packages
- removed legacy caches are either deleted or clearly retained for a surviving non-runtime purpose
- kill-switches are reduced to emergency rollback only or removed entirely

### Phase E: Steady-State Guardrails

Lock the architecture so new providers can only enter through adapters plus runtime.

**Exit criteria:**

- architecture doc committed
- CI boundary tests active
- onboarding guidance for new providers committed
- final verification matrix passed

### Phase F: Rail Ownership and Active Hydration

Finish the original long-run cache architecture after the runtime boundary is stable.

**Exit criteria:**

- rail membership is stored explicitly
- media identity graph exists for shared ownership
- orphan cleanup respects multi-rail ownership
- active-rail hydration planner exists
- old catalog/discovery ownership paths are retired

## Transitional Rollback Policy

During Phases B-C, each provider adapter may temporarily support a provider-scoped rollback switch so a broken cache rollout can fall back to adapter-owned `Disabled` or `ObserveOnly` behavior without reintroducing feature-layer bypasses.

Allowed temporary rollback:

```text
feature code → repository/facade → provider adapter → runtime (Disabled/ObserveOnly)
```

Not allowed:

```text
feature code → raw provider api
feature code → raw auth service execute method
worker → raw provider api
```

Rollback switches must:

1. live in adapter or integration-policy code only
2. preserve runtime entry, telemetry, and provider-lane behavior
3. be listed explicitly during rollout
4. be deleted in Task 11

## Approval Condition

This plan is intentionally multi-phase. It is not considered complete, approved, or "done enough" at the point where some providers use the runtime and others still bypass it.

Approval requires all of the following lifecycle checkpoints:

1. Phase B completes before any long-lived branch or release ships cache-deep provider work.
2. Phase C activates cache behavior provider by provider without reintroducing bypasses.
3. Phase D deletes transitional bypasses, temporary rollback switches, and obsolete shadow caches.
4. Phase E leaves CI enforcing the final boundary so the architecture cannot drift back into fragmentation.
5. Phase F completes if rail ownership and active hydration remain part of the accepted end-state cache vision.

## File Structure

Create or modify these files as the stable decomposition for the new runtime:

- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationWorkClass.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationScope.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationProviderPolicy.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationPolicyRegistry.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCodec.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCachePolicy.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationLoadResult.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationSpec.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationFetchOptions.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationFetchResult.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationPlaybackGate.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationBackoffManager.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/ProviderRequestGate.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationSingleFlight.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationRuntime.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheStore.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationKeyFactory.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheEntity.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationOwnerEntity.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationProviderBackoffEntity.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDao.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationProviderBackoffDao.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDatabase.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationBlobStore.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStore.kt`
- Create: `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt`
- Create: `app/src/main/java/com/nexio/tv/core/di/IntegrationProviderModule.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/simkl/SimklIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/mdblist/MDBListIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/omdb/OmdbIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/skip/IntroDbIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/skip/AniSkipIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/skip/AnimeSkipIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/skip/ArmIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/posters/RpdbIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/debrid/RealDebridIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/debrid/PremiumizeIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/debrid/TorBoxIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/debrid/EasyDebridIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/TraktReviewsRepository.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/MetadataRepository.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/CatalogRailRepository.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/ReviewsRepository.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/RatingsRepository.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/PosterRepository.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/TrackingRepository.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryRepository.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/ProviderSettingsRepository.kt`
- Create: `app/src/main/java/com/nexio/tv/core/image/IntegrationPosterFetcher.kt`
- Create: `app/src/main/java/com/nexio/tv/core/image/PosterIntegrationRequest.kt`

Modify these existing files incrementally rather than replacing them wholesale:

- `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt`
- `app/src/main/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepository.kt`
- `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`
- `app/src/main/java/com/nexio/tv/NexioApplication.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomePlaybackWorkGate.kt`
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbUpdateCoordinator.kt`
- `app/src/main/java/com/nexio/tv/data/repository/RealDebridAuthService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/PremiumizeService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/TorBoxService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/EasyDebridService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt`
- `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxCoordinator.kt`
- `app/src/main/java/com/nexio/tv/data/repository/SimklScrobbleService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
- `app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt`

These tests should be added or extended:

- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationPolicyRegistryTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationBackoffManagerTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/integration/DefaultIntegrationRuntimeTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationRuntimeTestFixtures.kt`
- Create: `app/src/test/java/com/nexio/tv/data/local/integration/IntegrationCacheDatabaseTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/IntegrationBoundaryTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/NoRawProviderInjectionTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepositoryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/SkipIntroRepositoryTidbTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/DebridLibraryServiceTest.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/detail/TraktReviewsRepositoryTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/image/IntegrationPosterFetcherTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomePlaybackWorkGateTest.kt`

### Task 1: Add the provider registry and runtime primitives

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationWorkClass.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationScope.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationProviderPolicy.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationPolicyRegistry.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCodec.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCachePolicy.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationLoadResult.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationSpec.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationFetchOptions.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationFetchResult.kt`
- Test: `app/src/test/java/com/nexio/tv/core/integration/IntegrationPolicyRegistryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationPolicyRegistryTest {
    @Test
    fun `default policies cover every external provider and stay serial by default`() {
        val registry = defaultIntegrationPolicyRegistry()

        val providers = setOf(
            IntegrationProvider.TRAKT,
            IntegrationProvider.SIMKL,
            IntegrationProvider.TMDB,
            IntegrationProvider.TVDB,
            IntegrationProvider.KITSU,
            IntegrationProvider.MDBLIST,
            IntegrationProvider.OMDB,
            IntegrationProvider.THEINTRODB,
            IntegrationProvider.ANISKIP,
            IntegrationProvider.ANIMESKIP,
            IntegrationProvider.ARM,
            IntegrationProvider.RPDB,
            IntegrationProvider.TOP_POSTERS,
            IntegrationProvider.REAL_DEBRID,
            IntegrationProvider.PREMIUMIZE,
            IntegrationProvider.TORBOX,
            IntegrationProvider.EASY_DEBRID,
            IntegrationProvider.GITHUB
        )

        providers.forEach { provider ->
            val policy = registry.policyFor(provider)
            assertEquals(provider.name, 1, policy.maxConcurrentNetworkStarts)
        }

        assertTrue(registry.policyFor(IntegrationProvider.TRAKT).allowDuringPlayback)
        assertTrue(registry.policyFor(IntegrationProvider.SIMKL).allowDuringPlayback)
        assertFalse(registry.policyFor(IntegrationProvider.TMDB).allowDuringPlayback)
        assertFalse(registry.policyFor(IntegrationProvider.RPDB).allowDuringPlayback)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationPolicyRegistryTest"`

Expected: FAIL with `ClassNotFoundException`, unresolved `IntegrationProvider`, or unresolved `defaultIntegrationPolicyRegistry`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.nexio.tv.core.integration

enum class IntegrationProvider {
    TRAKT,
    SIMKL,
    TMDB,
    TVDB,
    KITSU,
    MDBLIST,
    OMDB,
    THEINTRODB,
    ANISKIP,
    ANIMESKIP,
    ARM,
    RPDB,
    TOP_POSTERS,
    REAL_DEBRID,
    PREMIUMIZE,
    TORBOX,
    EASY_DEBRID,
    GITHUB
}

enum class IntegrationWorkClass {
    USER_VISIBLE,
    PLAYBACK_CRITICAL,
    PLAYBACK_RESOLUTION,
    SCROBBLE,
    MUTATION_OUTBOX,
    BACKGROUND_HYDRATION,
    PREFETCH,
    MAINTENANCE
}

sealed class IntegrationScope(val storageKey: String) {
    data object Global : IntegrationScope("global")
    data class Profile(val profileId: Int) : IntegrationScope("profile:$profileId")
    data class Account(val providerAccountId: String) : IntegrationScope("account:$providerAccountId")
}

data class IntegrationProviderPolicy(
    val maxConcurrentNetworkStarts: Int = 1,
    val allowDuringPlayback: Boolean = false,
    val allowStaleWhilePaused: Boolean = true,
    val defaultBackoffOn429Ms: Long = 2_000L,
    val defaultBackoffOnTransientMs: Long = 5_000L
)

class IntegrationPolicyRegistry(
    private val policies: Map<IntegrationProvider, IntegrationProviderPolicy>
) {
    fun policyFor(provider: IntegrationProvider): IntegrationProviderPolicy =
        policies.getValue(provider)
}

fun defaultIntegrationPolicyRegistry(): IntegrationPolicyRegistry =
    IntegrationPolicyRegistry(
        policies = mapOf(
            IntegrationProvider.TRAKT to IntegrationProviderPolicy(allowDuringPlayback = true),
            IntegrationProvider.SIMKL to IntegrationProviderPolicy(allowDuringPlayback = true),
            IntegrationProvider.TMDB to IntegrationProviderPolicy(),
            IntegrationProvider.TVDB to IntegrationProviderPolicy(),
            IntegrationProvider.KITSU to IntegrationProviderPolicy(),
            IntegrationProvider.MDBLIST to IntegrationProviderPolicy(),
            IntegrationProvider.OMDB to IntegrationProviderPolicy(),
            IntegrationProvider.THEINTRODB to IntegrationProviderPolicy(),
            IntegrationProvider.ANISKIP to IntegrationProviderPolicy(),
            IntegrationProvider.ANIMESKIP to IntegrationProviderPolicy(),
            IntegrationProvider.ARM to IntegrationProviderPolicy(),
            IntegrationProvider.RPDB to IntegrationProviderPolicy(),
            IntegrationProvider.TOP_POSTERS to IntegrationProviderPolicy(),
            IntegrationProvider.REAL_DEBRID to IntegrationProviderPolicy(),
            IntegrationProvider.PREMIUMIZE to IntegrationProviderPolicy(),
            IntegrationProvider.TORBOX to IntegrationProviderPolicy(),
            IntegrationProvider.EASY_DEBRID to IntegrationProviderPolicy(),
            IntegrationProvider.GITHUB to IntegrationProviderPolicy()
        )
    )
```

Add the runtime dependency stubs that later tasks will need:

```kotlin
// gradle/libs.versions.toml
[versions]
room = "2.8.0"

[libraries]
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
```

```kotlin
// app/build.gradle.kts
implementation(libs.androidx.room.runtime)
implementation(libs.androidx.room.ktx)
ksp(libs.androidx.room.compiler)
testImplementation(libs.androidx.room.testing)
```

Also add the spec/load result stubs so later tasks build on stable names:

```kotlin
sealed interface IntegrationLoadResult<out T> {
    data class Success<T>(val value: T) : IntegrationLoadResult<T>
    data class HttpError(
        val statusCode: Int,
        val retryAfterMs: Long? = null,
        val reason: String? = null
    ) : IntegrationLoadResult<Nothing>
    data class NetworkError(
        val throwable: Throwable,
        val retryAfterMs: Long? = null
    ) : IntegrationLoadResult<Nothing>
}

sealed interface IntegrationCachePolicy {
    data object Disabled : IntegrationCachePolicy
    data class ObserveOnly(val reason: String) : IntegrationCachePolicy
    data class CacheFirst(
        val ttlMs: Long,
        val staleAfterExpiryMs: Long = 0L
    ) : IntegrationCachePolicy
    data object Mutation : IntegrationCachePolicy
}

interface IntegrationCodec<T> {
    val mimeType: String
    fun encode(value: T): ByteArray
    fun decode(bytes: ByteArray): T
}

object StringIntegrationCodec : IntegrationCodec<String> {
    override val mimeType: String = "text/plain"
    override fun encode(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
    override fun decode(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)
}

class JsonCodec<T>(
    private val encodeFn: (T) -> ByteArray,
    private val decodeFn: (ByteArray) -> T,
    override val mimeType: String = "application/json"
) : IntegrationCodec<T> {
    override fun encode(value: T): ByteArray = encodeFn(value)
    override fun decode(bytes: ByteArray): T = decodeFn(bytes)
}

object FileCodec : IntegrationCodec<java.io.File> {
    override val mimeType: String = "application/octet-stream"
    override fun encode(value: java.io.File): ByteArray = value.absolutePath.toByteArray(Charsets.UTF_8)
    override fun decode(bytes: ByteArray): java.io.File = java.io.File(bytes.toString(Charsets.UTF_8))
}

inline fun <reified T> gsonCodec(gson: com.google.gson.Gson = com.google.gson.Gson()): IntegrationCodec<T> =
    JsonCodec(
        encodeFn = { value -> gson.toJson(value).toByteArray(Charsets.UTF_8) },
        decodeFn = { bytes ->
            gson.fromJson(bytes.toString(Charsets.UTF_8), object : com.google.gson.reflect.TypeToken<T>() {}.type)
        }
    )

data class IntegrationSpec<T>(
    val provider: IntegrationProvider,
    val cacheKey: String,
    val codec: IntegrationCodec<T>,
    val cachePolicy: IntegrationCachePolicy = IntegrationCachePolicy.Disabled,
    val workClass: IntegrationWorkClass,
    val scope: IntegrationScope = IntegrationScope.Global,
    val load: suspend () -> IntegrationLoadResult<T>
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationPolicyRegistryTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/nexio/tv/core/integration app/src/test/java/com/nexio/tv/core/integration/IntegrationPolicyRegistryTest.kt
git commit -m "feat: add integration runtime policy primitives"
```

### Task 2: Add the persistent cache index, blob store, and provider-backoff store

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheStore.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheEntity.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationOwnerEntity.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationProviderBackoffEntity.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDao.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationProviderBackoffDao.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDatabase.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationBlobStore.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/integration/IntegrationCacheDatabaseTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.local.integration

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IntegrationCacheDatabaseTest {
    @Test
    fun `cache rows and provider backoff rows round-trip`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(
            context,
            IntegrationCacheDatabase::class.java
        ).allowMainThreadQueries().build()

        val cacheEntity = IntegrationCacheEntity(
            cacheKey = "tmdb:movie:550:en-US",
            provider = "TMDB",
            scopeKey = "global",
            blobPath = "tmdb/movie-550.json",
            mimeType = "application/json",
            expiresAtEpochMs = 10_000L,
            staleUntilEpochMs = 20_000L,
            updatedAtEpochMs = 5_000L,
            ownerToken = null
        )
        db.cacheDao().upsertCacheEntry(cacheEntity)

        db.backoffDao().upsert(
            IntegrationProviderBackoffEntity(
                key = "TMDB:global",
                provider = "TMDB",
                scopeKey = "global",
                blockedUntilEpochMs = 8_000L,
                statusCode = 429,
                reason = "Retry-After",
                updatedAtEpochMs = 6_000L
            )
        )

        assertEquals(cacheEntity.cacheKey, db.cacheDao().getCacheEntry(cacheEntity.cacheKey)?.cacheKey)
        assertNotNull(db.backoffDao().get("TMDB", "global"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.integration.IntegrationCacheDatabaseTest"`

Expected: FAIL with missing Room database, DAO, or entity classes.

- [ ] **Step 3: Write minimal implementation**

Introduce the cache seam now, but only with a local implementation:

```kotlin
package com.nexio.tv.core.integration

interface IntegrationCacheStore {
    suspend fun <T> readFresh(spec: IntegrationSpec<T>): T?
    suspend fun <T> readStale(spec: IntegrationSpec<T>): T?
    suspend fun <T> write(spec: IntegrationSpec<T>, value: T)
}
```

```kotlin
package com.nexio.tv.data.local.integration

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "integration_cache")
data class IntegrationCacheEntity(
    @PrimaryKey val cacheKey: String,
    val provider: String,
    val scopeKey: String,
    val blobPath: String,
    val mimeType: String,
    val expiresAtEpochMs: Long,
    val staleUntilEpochMs: Long,
    val updatedAtEpochMs: Long,
    val ownerToken: String?
)

@Entity(tableName = "integration_cache_owner")
data class IntegrationOwnerEntity(
    @PrimaryKey val key: String,
    val cacheKey: String,
    val ownerType: String,
    val ownerKey: String
)

@Entity(tableName = "integration_provider_backoff")
data class IntegrationProviderBackoffEntity(
    @PrimaryKey val key: String,
    val provider: String,
    val scopeKey: String,
    val blockedUntilEpochMs: Long,
    val statusCode: Int?,
    val reason: String?,
    val updatedAtEpochMs: Long
)

@Dao
interface IntegrationCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCacheEntry(entity: IntegrationCacheEntity)

    @Query("SELECT * FROM integration_cache WHERE cacheKey = :cacheKey")
    suspend fun getCacheEntry(cacheKey: String): IntegrationCacheEntity?
}

@Dao
interface IntegrationProviderBackoffDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IntegrationProviderBackoffEntity)

    @Query("SELECT * FROM integration_provider_backoff WHERE provider = :provider AND scopeKey = :scopeKey LIMIT 1")
    suspend fun get(provider: String, scopeKey: String): IntegrationProviderBackoffEntity?
}

@Database(
    entities = [
        IntegrationCacheEntity::class,
        IntegrationOwnerEntity::class,
        IntegrationProviderBackoffEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class IntegrationCacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): IntegrationCacheDao
    abstract fun backoffDao(): IntegrationProviderBackoffDao
}
```

```kotlin
package com.nexio.tv.data.local.integration

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntegrationBlobStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val root: File = File(context.filesDir, "integration-cache").apply { mkdirs() }

    fun fileFor(path: String): File = File(root, path).apply {
        parentFile?.mkdirs()
    }
}
```

```kotlin
package com.nexio.tv.data.local.integration

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCacheStore
import com.nexio.tv.core.integration.IntegrationSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalIntegrationCacheStore @Inject constructor(
    private val cacheDao: IntegrationCacheDao,
    private val blobStore: IntegrationBlobStore
) : IntegrationCacheStore {
    override suspend fun <T> readFresh(spec: IntegrationSpec<T>): T? = TODO("implemented in Task 3")
    override suspend fun <T> readStale(spec: IntegrationSpec<T>): T? = TODO("implemented in Task 3")
    override suspend fun <T> write(spec: IntegrationSpec<T>, value: T) = Unit
}
```

Remote cache backends are intentionally deferred. Do not add Worker/R2 code, HTTP routes, or remote upload logic in this task.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.integration.IntegrationCacheDatabaseTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheStore.kt app/src/main/java/com/nexio/tv/data/local/integration app/src/test/java/com/nexio/tv/data/local/integration/IntegrationCacheDatabaseTest.kt
git commit -m "feat: add integration cache and backoff storage"
```

### Task 3: Build cache-first runtime execution, single-flight, playback gating, and 429 persistence

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationPlaybackGate.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationBackoffManager.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/ProviderRequestGate.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationSingleFlight.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationRuntime.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationKeyFactory.kt`
- Create: `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt`
- Test: `app/src/test/java/com/nexio/tv/core/integration/IntegrationBackoffManagerTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/integration/DefaultIntegrationRuntimeTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/integration/IntegrationRuntimeTestFixtures.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.core.integration

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DefaultIntegrationRuntimeTest {
    @Test
    fun `fresh cache hit never enters provider gate or loader`() = runTest {
        val fixture = realRuntimeFixture()
        fixture.seedCache(
            cacheKey = "tmdb:movie:550",
            codec = StringIntegrationCodec,
            value = "cached",
            freshForMs = 60_000L,
            staleAfterMs = 60_000L
        )

        val calls = AtomicInteger(0)
        val result = fixture.runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                cacheKey = "tmdb:movie:550",
                codec = StringIntegrationCodec,
                cachePolicy = IntegrationCachePolicy.CacheFirst(
                    ttlMs = 60_000L,
                    staleAfterExpiryMs = 60_000L
                ),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    calls.incrementAndGet()
                    IntegrationLoadResult.Success("network")
                }
            )
        )

        assertEquals(0, calls.get())
        assertEquals(IntegrationFetchResult.Fresh("cached"), result)
    }

    @Test
    fun `single flight deduplicates concurrent cache misses`() = runTest {
        val fixture = realRuntimeFixture()
        val calls = AtomicInteger(0)
        val spec = IntegrationSpec(
            provider = IntegrationProvider.KITSU,
            cacheKey = "kitsu:anime:1",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 60_000L,
                staleAfterExpiryMs = 60_000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                calls.incrementAndGet()
                IntegrationLoadResult.Success("payload")
            }
        )

        val results = listOf(
            async { fixture.runtime.get(spec) },
            async { fixture.runtime.get(spec) }
        ).awaitAll()

        assertEquals(1, calls.get())
        assertTrue(results.all { it == IntegrationFetchResult.Updated("payload") || it == IntegrationFetchResult.Fresh("payload") })
    }
}
```

```kotlin
package com.nexio.tv.core.integration

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationBackoffManagerTest {
    @Test
    fun `http 429 persists provider block using retry after`() = runTest {
        val fixture = realRuntimeFixture()
        fixture.backoffManager.noteHttpFailure(
            provider = IntegrationProvider.TMDB,
            scope = IntegrationScope.Global,
            statusCode = 429,
            retryAfterMs = 12_000L,
            reason = "Retry-After"
        )

        val entry = fixture.backoffDao.get("TMDB", "global")
        assertTrue(entry != null)
        assertTrue((entry?.blockedUntilEpochMs ?: 0L) >= 12_000L)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.integration.DefaultIntegrationRuntimeTest" --tests "com.nexio.tv.core.integration.IntegrationBackoffManagerTest"`

Expected: FAIL with missing `DefaultIntegrationRuntime`, `realRuntimeFixture`, cache seeding helpers, or backoff manager.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.IntegrationBlobStore
import com.nexio.tv.data.local.integration.IntegrationCacheDao
import com.nexio.tv.data.local.integration.IntegrationCacheEntity
import com.nexio.tv.data.local.integration.LocalIntegrationCacheStore
import com.nexio.tv.data.local.integration.IntegrationProviderBackoffDao
import com.nexio.tv.data.local.integration.IntegrationProviderBackoffEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed interface IntegrationFetchResult<out T> {
    data class Fresh<T>(val value: T) : IntegrationFetchResult<T>
    data class Updated<T>(val value: T) : IntegrationFetchResult<T>
    data class Stale<T>(val value: T) : IntegrationFetchResult<T>
    data object Missing : IntegrationFetchResult<Nothing>
}

fun <T> IntegrationFetchResult<T>.valueOrNull(): T? =
    when (this) {
        is IntegrationFetchResult.Fresh -> value
        is IntegrationFetchResult.Updated -> value
        is IntegrationFetchResult.Stale -> value
        IntegrationFetchResult.Missing -> null
    }

@Singleton
class IntegrationPlaybackGate @Inject constructor() {
    @Volatile private var playbackActive: Boolean = false

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
    }

    fun isBlocked(policy: IntegrationProviderPolicy, workClass: IntegrationWorkClass): Boolean {
        if (!playbackActive) return false
        if (policy.allowDuringPlayback) return false
        return workClass !in setOf(
            IntegrationWorkClass.PLAYBACK_CRITICAL,
            IntegrationWorkClass.PLAYBACK_RESOLUTION,
            IntegrationWorkClass.SCROBBLE,
            IntegrationWorkClass.MUTATION_OUTBOX
        )
    }
}

@Singleton
class ProviderRequestGate @Inject constructor(
    private val registry: IntegrationPolicyRegistry
) {
    private val mutexes = ConcurrentHashMap<IntegrationProvider, Mutex>()

    suspend fun <T> withPermit(provider: IntegrationProvider, block: suspend () -> T): T {
        val policy = registry.policyFor(provider)
        if (policy.maxConcurrentNetworkStarts == 1) {
            return mutexes.getOrPut(provider) { Mutex() }.withLock { block() }
        }
        return block()
    }
}

@Singleton
class IntegrationSingleFlight @Inject constructor() {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<IntegrationFetchResult<*>>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> run(cacheKey: String, block: suspend () -> IntegrationFetchResult<T>): IntegrationFetchResult<T> {
        val existing = mutex.withLock {
            inFlight[cacheKey] as? CompletableDeferred<IntegrationFetchResult<T>>
        }
        if (existing != null) return existing.await()

        val deferred = CompletableDeferred<IntegrationFetchResult<T>>()
        val shouldRun = mutex.withLock {
            val found = inFlight[cacheKey] as? CompletableDeferred<IntegrationFetchResult<T>>
            if (found != null) {
                false
            } else {
                inFlight[cacheKey] = deferred
                true
            }
        }
        if (!shouldRun) {
            return mutex.withLock {
                inFlight[cacheKey] as CompletableDeferred<IntegrationFetchResult<T>>
            }.await()
        }

        return try {
            block().also { deferred.complete(it) }
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            mutex.withLock { inFlight.remove(cacheKey) }
        }
    }
}

@Singleton
class IntegrationBackoffManager @Inject constructor(
    private val dao: IntegrationProviderBackoffDao
) {
    suspend fun noteHttpFailure(
        provider: IntegrationProvider,
        scope: IntegrationScope,
        statusCode: Int,
        retryAfterMs: Long?,
        reason: String?
    ) {
        val blockMs = retryAfterMs ?: if (statusCode == 429) 2_000L else 5_000L
        dao.upsert(
            IntegrationProviderBackoffEntity(
                key = "${provider.name}:${scope.storageKey}",
                provider = provider.name,
                scopeKey = scope.storageKey,
                blockedUntilEpochMs = System.currentTimeMillis() + blockMs,
                statusCode = statusCode,
                reason = reason,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun isBlocked(provider: IntegrationProvider, scope: IntegrationScope): Boolean {
        val entry = dao.get(provider.name, scope.storageKey) ?: return false
        return entry.blockedUntilEpochMs > System.currentTimeMillis()
    }
}

interface IntegrationRuntime {
    suspend fun <T> get(
        spec: IntegrationSpec<T>,
        options: IntegrationFetchOptions = IntegrationFetchOptions()
    ): IntegrationFetchResult<T>
}

data class IntegrationFetchOptions(
    val cacheOnly: Boolean = false,
    val allowStaleOnFailure: Boolean = true
)
```

```kotlin
package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.IntegrationBlobStore
import com.nexio.tv.data.local.integration.IntegrationCacheDao
import java.nio.charset.StandardCharsets.UTF_8
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultIntegrationRuntime @Inject constructor(
    private val cacheStore: IntegrationCacheStore,
    private val requestGate: ProviderRequestGate,
    private val backoffManager: IntegrationBackoffManager,
    private val singleFlight: IntegrationSingleFlight,
    private val playbackGate: IntegrationPlaybackGate,
    private val registry: IntegrationPolicyRegistry
) : IntegrationRuntime {
    override suspend fun <T> get(
        spec: IntegrationSpec<T>,
        options: IntegrationFetchOptions
    ): IntegrationFetchResult<T> {
        return when (val policy = spec.cachePolicy) {
            IntegrationCachePolicy.Disabled -> executeWithoutCache(spec)
            is IntegrationCachePolicy.ObserveOnly -> executeObserveOnly(spec, policy)
            is IntegrationCachePolicy.CacheFirst -> executeCacheFirst(spec, policy, options)
            IntegrationCachePolicy.Mutation -> executeMutation(spec)
        }
    }

    private suspend fun <T> executeWithoutCache(spec: IntegrationSpec<T>): IntegrationFetchResult<T> =
        executeProviderLoad(spec)

    private suspend fun <T> executeObserveOnly(
        spec: IntegrationSpec<T>,
        policy: IntegrationCachePolicy.ObserveOnly
    ): IntegrationFetchResult<T> =
        executeProviderLoad(spec)

    private suspend fun <T> executeMutation(spec: IntegrationSpec<T>): IntegrationFetchResult<T> =
        executeProviderLoad(spec)

    private suspend fun <T> executeCacheFirst(
        spec: IntegrationSpec<T>,
        policy: IntegrationCachePolicy.CacheFirst,
        options: IntegrationFetchOptions
    ): IntegrationFetchResult<T> {
        cacheStore.readFresh(spec)?.let { return IntegrationFetchResult.Fresh(it) }

        val providerPolicy = registry.policyFor(spec.provider)
        if (options.cacheOnly || playbackGate.isBlocked(providerPolicy, spec.workClass)) {
            val stale = cacheStore.readStale(spec)
            return if (stale != null && providerPolicy.allowStaleWhilePaused && options.allowStaleOnFailure) {
                IntegrationFetchResult.Stale(stale)
            } else {
                IntegrationFetchResult.Missing
            }
        }

        return singleFlight.run(spec.cacheKey) {
            cacheStore.readFresh(spec)?.let { return@run IntegrationFetchResult.Fresh(it) }
            if (backoffManager.isBlocked(spec.provider, spec.scope)) {
                cacheStore.readStale(spec)?.let { return@run IntegrationFetchResult.Stale(it) }
                return@run IntegrationFetchResult.Missing
            }

            when (val result = executeProviderLoad(spec)) {
                is IntegrationFetchResult.Updated -> {
                    cacheStore.write(spec, result.value)
                    result
                }
                is IntegrationFetchResult.Fresh -> result
                is IntegrationFetchResult.Stale -> result
                IntegrationFetchResult.Missing -> cacheStore.readStale(spec)?.let { IntegrationFetchResult.Stale(it) }
                    ?: IntegrationFetchResult.Missing
            }
        }
    }

    private suspend fun <T> executeProviderLoad(spec: IntegrationSpec<T>): IntegrationFetchResult<T> =
        requestGate.withPermit(spec.provider) {
            when (val result = spec.load()) {
                is IntegrationLoadResult.Success -> IntegrationFetchResult.Updated(result.value)
                is IntegrationLoadResult.HttpError -> {
                    if (result.statusCode == 429 || result.statusCode >= 500) {
                        backoffManager.noteHttpFailure(
                            provider = spec.provider,
                            scope = spec.scope,
                            statusCode = result.statusCode,
                            retryAfterMs = result.retryAfterMs,
                            reason = result.reason
                        )
                    }
                    IntegrationFetchResult.Missing
                }
                is IntegrationLoadResult.NetworkError -> IntegrationFetchResult.Missing
            }
        }

}
```

Add two runtime test harnesses:

1. `realRuntimeFixture()` for core runtime tests. It must construct the real `DefaultIntegrationRuntime` with in-memory Room, a temp blob store, real provider gates, real single-flight, and real backoff storage.
2. `recordingRuntimeFixture()` / `RecordingIntegrationRuntime` for adapter tests only.

```kotlin
package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.IntegrationBlobStore
import com.nexio.tv.data.local.integration.IntegrationCacheDao
import com.nexio.tv.data.local.integration.IntegrationProviderBackoffDao

data class IntegrationRuntimeFixture(
    val runtime: IntegrationRuntime,
    val backoffManager: IntegrationBackoffManager,
    val backoffDao: IntegrationProviderBackoffDao
)

data class RealRuntimeFixture(
    val runtime: DefaultIntegrationRuntime,
    val backoffManager: IntegrationBackoffManager,
    val backoffDao: IntegrationProviderBackoffDao,
    val cacheDao: IntegrationCacheDao,
    val blobStore: IntegrationBlobStore,
    val cacheStore: LocalIntegrationCacheStore
)

class RecordingIntegrationRuntime<T>(
    private val successValue: T? = null,
    private val nextResult: IntegrationFetchResult<T>? = null
) : IntegrationRuntime {
    val keys = mutableListOf<String>()

    override suspend fun <R> get(
        spec: IntegrationSpec<R>,
        options: IntegrationFetchOptions
    ): IntegrationFetchResult<R> {
        keys += spec.cacheKey
        @Suppress("UNCHECKED_CAST")
        return nextResult as? IntegrationFetchResult<R>
            ?: successValue?.let { IntegrationFetchResult.Updated(it as R) }
            ?: IntegrationFetchResult.Missing
    }
}

fun recordingRuntimeFixture(): IntegrationRuntimeFixture {
    val backoffDao = InMemoryIntegrationProviderBackoffDao()
    val backoffManager = IntegrationBackoffManager(backoffDao)
    val runtime = object : IntegrationRuntime {
        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> {
            return when (val result = spec.load()) {
                is IntegrationLoadResult.Success -> IntegrationFetchResult.Updated(result.value)
                is IntegrationLoadResult.HttpError -> IntegrationFetchResult.Missing
                is IntegrationLoadResult.NetworkError -> IntegrationFetchResult.Missing
            }
        }
    }
    return IntegrationRuntimeFixture(runtime = runtime, backoffManager = backoffManager, backoffDao = backoffDao)
}

fun realRuntimeFixture(): RealRuntimeFixture {
    val database = inMemoryIntegrationCacheDatabase()
    val cacheDao = database.cacheDao()
    val backoffDao = database.backoffDao()
    val blobStore = tempIntegrationBlobStore()
    val cacheStore = LocalIntegrationCacheStore(cacheDao, blobStore)
    val registry = defaultIntegrationPolicyRegistry()
    val backoffManager = IntegrationBackoffManager(backoffDao)
    val runtime = DefaultIntegrationRuntime(
        cacheStore = cacheStore,
        requestGate = ProviderRequestGate(registry),
        backoffManager = backoffManager,
        singleFlight = IntegrationSingleFlight(),
        playbackGate = IntegrationPlaybackGate(),
        registry = registry
    )
    return RealRuntimeFixture(
        runtime = runtime,
        backoffManager = backoffManager,
        backoffDao = backoffDao,
        cacheDao = cacheDao,
        blobStore = blobStore,
        cacheStore = cacheStore
    )
}

suspend fun <T> RealRuntimeFixture.seedCache(
    cacheKey: String,
    codec: IntegrationCodec<T>,
    value: T,
    freshForMs: Long,
    staleAfterMs: Long
) {
    val blobPath = cacheKey.replace(':', '/') + ".seed"
    val now = System.currentTimeMillis()
    blobStore.fileFor(blobPath).writeBytes(codec.encode(value))
    cacheDao.upsertCacheEntry(
        IntegrationCacheEntity(
            cacheKey = cacheKey,
            provider = cacheKey.substringBefore(':').uppercase(),
            scopeKey = "global",
            blobPath = blobPath,
            mimeType = codec.mimeType,
            expiresAtEpochMs = now + freshForMs,
            staleUntilEpochMs = now + freshForMs + staleAfterMs,
            updatedAtEpochMs = now,
            ownerToken = null
        )
    )
}

class InMemoryIntegrationProviderBackoffDao : IntegrationProviderBackoffDao {
    private val values = linkedMapOf<String, IntegrationProviderBackoffEntity>()

    override suspend fun upsert(entity: IntegrationProviderBackoffEntity) {
        values[entity.key] = entity
    }

    override suspend fun get(provider: String, scopeKey: String): IntegrationProviderBackoffEntity? =
        values["$provider:$scopeKey"]
}
```

The critical invariant test `fresh cache hit never enters provider gate or loader` must use `realRuntimeFixture()`, not `RecordingIntegrationRuntime`.

Wire the runtime in Hilt:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object IntegrationRuntimeModule {
    @Provides
    @Singleton
    fun provideIntegrationPolicyRegistry(): IntegrationPolicyRegistry =
        defaultIntegrationPolicyRegistry()

    @Provides
    @Singleton
    fun provideIntegrationRuntime(
        impl: DefaultIntegrationRuntime
    ): IntegrationRuntime = impl
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.integration.DefaultIntegrationRuntimeTest" --tests "com.nexio.tv.core.integration.IntegrationBackoffManagerTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt app/src/test/java/com/nexio/tv/core/integration
git commit -m "feat: add cache-first integration runtime"
```

### Task 4: Create the mandatory provider-adapter boundary, add cache-policy modes, and ban direct provider bypasses

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCachePolicy.kt`
- Create: `app/src/main/java/com/nexio/tv/core/di/IntegrationProviderModule.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/simkl/SimklIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/mdblist/MDBListIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/omdb/OmdbIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/skip/IntroDbIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/skip/AniSkipIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/skip/AnimeSkipIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/skip/ArmIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/posters/RpdbIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/debrid/RealDebridIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/debrid/PremiumizeIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/debrid/TorBoxIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/debrid/EasyDebridIntegrationProvider.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/IntegrationBoundaryTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/NoRawProviderInjectionTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class IntegrationBoundaryTest {
    @Test
    fun `non integration packages do not reference provider retrofit apis directly`() {
        val forbiddenTypeNames = setOf(
            "TraktApi",
            "SimklApi",
            "TmdbApi",
            "TvdbApi",
            "KitsuApi",
            "MDBListApi",
            "OmdbApi",
            "IntroDbApi",
            "AniSkipApi",
            "AnimeSkipApi",
            "ArmApi",
            "RealDebridApi",
            "PremiumizeApi",
            "TorBoxApi",
            "EasyDebridApi",
            "RpdbApi",
            "TopPostersApi"
        )

        val offenders = architectureScan(
            allowedPackages = setOf(
                "com.nexio.tv.data.integration",
                "com.nexio.tv.data.remote",
                "com.nexio.tv.core.di"
            ),
            forbiddenSimpleNames = forbiddenTypeNames
        )

        if (offenders.isNotEmpty()) {
            fail("Direct provider API usage outside integration boundary: $offenders")
        }
    }
}
```

```kotlin
package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoRawProviderInjectionTest {
    @Test
    fun `feature packages do not inject raw retrofit or okhttp types`() {
        val offenders = architectureScan(
            allowedPackages = setOf(
                "com.nexio.tv.data.integration",
                "com.nexio.tv.data.remote",
                "com.nexio.tv.core.di"
            ),
            forbiddenSimpleNames = setOf("Retrofit", "OkHttpClient")
        )

        if (offenders.isNotEmpty()) {
            fail("Raw networking types escaped integration boundary: $offenders")
        }
    }
}
```

```kotlin
package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoIntegrationRuntimeInjectionOutsideBoundaryTest {
    @Test
    fun `feature and presentation packages do not inject integration runtime directly`() {
        val offenders = architectureScan(
            allowedPackages = setOf(
                "com.nexio.tv.data.integration",
                "com.nexio.tv.core.integration",
                "com.nexio.tv.core.di"
            ),
            forbiddenSimpleNames = setOf("IntegrationRuntime")
        )

        if (offenders.isNotEmpty()) {
            fail("IntegrationRuntime escaped approved layers: $offenders")
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.IntegrationBoundaryTest" --tests "com.nexio.tv.architecture.NoRawProviderInjectionTest" --tests "com.nexio.tv.architecture.NoIntegrationRuntimeInjectionOutsideBoundaryTest"`

Expected: FAIL because provider Retrofit APIs, raw networking types, or direct `IntegrationRuntime` injections are still referenced from non-integration packages.

- [ ] **Step 3: Write minimal implementation**

Add cache-policy modes so every provider can enter the runtime immediately, even before final cache design:

```kotlin
package com.nexio.tv.core.integration

sealed interface IntegrationCachePolicy {
    data object Disabled : IntegrationCachePolicy
    data class ObserveOnly(val reason: String) : IntegrationCachePolicy
    data class CacheFirst(
        val ttlMs: Long,
        val staleAfterExpiryMs: Long = 0L
    ) : IntegrationCachePolicy
    data object Mutation : IntegrationCachePolicy
}
```

Extend `IntegrationSpec` so every call has explicit cache semantics:

```kotlin
data class IntegrationSpec<T>(
    val provider: IntegrationProvider,
    val cacheKey: String,
    val codec: IntegrationCodec<T>,
    val cachePolicy: IntegrationCachePolicy = IntegrationCachePolicy.Disabled,
    val workClass: IntegrationWorkClass,
    val scope: IntegrationScope = IntegrationScope.Global,
    val load: suspend () -> IntegrationLoadResult<T>
)
```

Create thin provider adapters that are the only legal raw callers:

```kotlin
package com.nexio.tv.data.integration.kitsu

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.KitsuApi
import com.nexio.tv.data.repository.KitsuAuthService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KitsuIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val kitsuApi: KitsuApi,
    private val kitsuAuthService: KitsuAuthService
) {
    suspend fun <T> execute(
        cacheKey: String,
        cachePolicy: IntegrationCachePolicy,
        workClass: IntegrationWorkClass,
        block: suspend (authorization: String?) -> T
    ): T? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.KITSU,
            cacheKey = cacheKey,
            codec = gsonCodec<T>(),
            cachePolicy = cachePolicy,
            workClass = workClass,
            load = {
                val token = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
                runCatching { block(token) }
                    .fold(
                        onSuccess = { IntegrationLoadResult.Success(it) },
                        onFailure = { IntegrationLoadResult.NetworkError(it) }
                    )
            }
        )
        return runtime.get(spec).valueOrNull()
    }
}
```

Add equivalent adapter shells for the remaining providers. For the first pass, most should be `ObserveOnly` or `Disabled`, not `CacheFirst`.

Rebind Hilt so repositories and facades receive adapters instead of raw APIs:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object IntegrationProviderModule {
    @Provides
    @Singleton
    fun provideKitsuIntegrationProvider(
        runtime: IntegrationRuntime,
        kitsuApi: KitsuApi,
        kitsuAuthService: KitsuAuthService
    ): KitsuIntegrationProvider = KitsuIntegrationProvider(runtime, kitsuApi, kitsuAuthService)
}
```

At the same time, make the app-facing contracts explicit:

```kotlin
interface MetadataRepository
interface CatalogRailRepository
interface RatingsRepository
interface ReviewsRepository
interface PosterRepository
interface TrackingRepository
interface DebridLibraryRepository
interface ProviderSettingsRepository
```

Feature code should receive these contracts, not provider adapters.

Extend `DefaultIntegrationRuntime` to honor the new cache-policy modes:

```kotlin
return when (spec.cachePolicy) {
    IntegrationCachePolicy.Disabled -> executeWithoutCache(spec, options)
    is IntegrationCachePolicy.ObserveOnly -> executeObserveOnly(spec, options)
    is IntegrationCachePolicy.CacheFirst -> executeCacheFirst(spec, options)
    IntegrationCachePolicy.Mutation -> executeMutation(spec, options)
}
```

Behavior contract:

1. `Disabled`: no cache read/write, but still enforce provider lane, playback gate, backoff, and telemetry.
2. `ObserveOnly`: no cache read/write decision authority yet, but emit the exact key/policy/latency telemetry that later cache activation will use.
3. `CacheFirst`: current disk-first / stale-fallback path.
4. `Mutation`: no cache read/write, but full backoff/telemetry/provider-lane control.

Add an architecture-scan helper that walks compiled classes or source stubs and reports forbidden constructor/property types. The exact mechanism may use reflection over class metadata or a deterministic source scan, but the test contract must stay the same: CI fails if a non-integration package references raw provider APIs or raw networking types.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.IntegrationBoundaryTest" --tests "com.nexio.tv.architecture.NoRawProviderInjectionTest" --tests "com.nexio.tv.architecture.NoIntegrationRuntimeInjectionOutsideBoundaryTest"`

Expected: PASS once raw provider references have been moved behind adapter packages.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationCachePolicy.kt app/src/main/java/com/nexio/tv/core/di/IntegrationProviderModule.kt app/src/main/java/com/nexio/tv/data/integration app/src/test/java/com/nexio/tv/architecture
git commit -m "feat: add provider adapter boundary for integration runtime"
```

### Task 5: Enable cache-first for Kitsu, OMDb, TheIntroDb, AniSkip, AnimeSkip, and ARM-backed skip-intro lookups under the runtime

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/omdb/OmdbIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/skip/IntroDbIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/skip/AniSkipIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/skip/AnimeSkipIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/skip/ArmIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/OmdbSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TheIntroDbSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/AnimeSkipSettingsViewModel.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepositoryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/SkipIntroRepositoryTidbTest.kt`

- [ ] **Step 1: Write the failing tests**

Add one regression per provider family, but assert against adapter-owned runtime keys rather than direct runtime injection from repositories:

```kotlin
@Test
fun `kitsu enrichment goes through provider adapter cache key once for duplicate requests`() = runTest {
    val runtime = RecordingIntegrationRuntime(successValue = expectedEnrichment)
    val provider = KitsuIntegrationProvider(runtime, api, auth)
    val service = KitsuMetadataService(provider, mapper)

    service.fetchEnrichment("kitsu:1", ContentMediaKind.SERIES)
    service.fetchEnrichment("kitsu:1", ContentMediaKind.SERIES)

    assertEquals(1, runtime.keys.count { it == "kitsu:series:kitsu:1:enrichment" })
}
```

```kotlin
@Test
fun `omdb season ratings serve stale data through provider adapter when runtime reports rate limit`() = runTest {
    val runtime = RecordingIntegrationRuntime(
        nextResult = IntegrationFetchResult.Stale(mapOf((1 to 1) to 8.7))
    )
    val provider = OmdbIntegrationProvider(runtime, omdbApi)
    val repository = OmdbEpisodeRatingsRepository(provider, settingsStore, tmdbService)

    val result = repository.getEpisodeRatingsForMeta(meta, "tt0944947", "series", mapOf(1 to setOf(1)))

    assertEquals(8.7, result[1 to 1], 0.0)
}
```

```kotlin
@Test
fun `skip intro repository routes anime providers through adapter owned runtime keys`() = runTest {
    val runtime = RecordingIntegrationRuntime(successValue = emptyList<SkipInterval>())
    val repository = SkipIntroRepository(
        introDbProvider = IntroDbIntegrationProvider(runtime, introDbApi),
        aniSkipProvider = AniSkipIntegrationProvider(runtime, aniSkipApi),
        animeSkipProvider = AnimeSkipIntegrationProvider(runtime, animeSkipApi),
        armProvider = ArmIntegrationProvider(runtime, armApi),
        animeSkipSettingsDataStore = animeSkipSettings,
        theIntroDbSettingsDataStore = introSettings
    )

    repository.getAnimePrimarySkipIntervals("tt1234567", 1, 1)

    assertTrue(runtime.keys.any { it.startsWith("aniskip:") || it.startsWith("animeskip:") || it.startsWith("arm:") })
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.KitsuMetadataServiceTest" --tests "com.nexio.tv.data.repository.OmdbEpisodeRatingsRepositoryTest" --tests "com.nexio.tv.data.repository.SkipIntroRepositoryTidbTest"`

Expected: FAIL because the repositories/services do not yet depend on provider adapters and the adapters do not yet enable `CacheFirst` policies.

- [ ] **Step 3: Write minimal implementation**

Enable `CacheFirst` inside provider adapters, then keep the existing repositories/facades thin:

```kotlin
class KitsuIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val api: KitsuApi,
    private val kitsuAuthService: KitsuAuthService
) {
    suspend fun fetchEnrichment(rawId: String, kitsuId: String, mediaKind: ContentMediaKind): TvMetadataEnrichment? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.KITSU,
            cacheKey = "kitsu:${mediaKind.name.lowercase()}:$rawId:enrichment",
            codec = gsonCodec<TvMetadataEnrichment>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
                val response = runCatching { api.getAnime(authorization, kitsuId) }
                    .getOrElse { return@IntegrationSpec IntegrationLoadResult.NetworkError(it) }
                val body = response.body()?.data ?: return@IntegrationSpec IntegrationLoadResult.HttpError(response.code(), reason = "missing_body")
                IntegrationLoadResult.Success(body.toTvMetadataEnrichment())
            }
        )
        return runtime.get(spec).valueOrNull()
    }
}

class KitsuMetadataService @Inject constructor(
    private val kitsuProvider: KitsuIntegrationProvider,
    private val idMappingService: AnimeIdMappingService
) {
    suspend fun fetchEnrichment(rawId: String, mediaKind: ContentMediaKind): TvMetadataEnrichment? {
        val animeId = AnimeStremioId.parse(rawId) ?: return null
        val kitsuId = idMappingService.resolveKitsuId(animeId, mediaKind) ?: return null
        return kitsuProvider.fetchEnrichment(rawId = rawId, kitsuId = kitsuId, mediaKind = mediaKind)
    }
}
```

```kotlin
class OmdbIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val omdbApi: OmdbApi
) {
    suspend fun getSeasonRatings(seriesImdbId: String, apiKey: String, season: Int): Map<Pair<Int, Int>, Double> {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.OMDB,
            cacheKey = "omdb:$seriesImdbId:season:$season:${apiKey.hashCode()}",
            codec = gsonCodec<Map<Pair<Int, Int>, Double>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = OMDB_EPISODE_RATINGS_TTL_MS,
                staleAfterExpiryMs = OMDB_EPISODE_RATINGS_TTL_MS
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val response = runCatching { omdbApi.getSeason(apiKey, seriesImdbId, season) }
                    .getOrElse { return@IntegrationSpec IntegrationLoadResult.NetworkError(it) }
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(response.code(), reason = "season_lookup_failed")
                }
                IntegrationLoadResult.Success(parseSeasonRatings(season, body))
            }
        )
        return runtime.get(spec).valueOrNull().orEmpty()
    }
}

class OmdbEpisodeRatingsRepository @Inject constructor(
    private val omdbProvider: OmdbIntegrationProvider,
    private val omdbSettingsDataStore: OmdbSettingsDataStore,
    private val tmdbService: TmdbService
) {
    private suspend fun getSeasonRatings(seriesImdbId: String, apiKey: String, season: Int): Map<Pair<Int, Int>, Double> =
        omdbProvider.getSeasonRatings(seriesImdbId = seriesImdbId, apiKey = apiKey, season = season)
}
```

```kotlin
class IntroDbIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val introDbApi: IntroDbApi
) {
    suspend fun getIntervals(contentId: String, season: Int?, episode: Int?): List<SkipInterval> {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.THEINTRODB,
            cacheKey = "theintrodb:$contentId:$season:$episode",
            codec = gsonCodec<List<SkipInterval>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 7L * 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 30L * 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.PLAYBACK_CRITICAL,
            load = { loadTheIntroDbIntervals(contentId, season, episode) }
        )
        return runtime.get(spec).valueOrNull().orEmpty()
    }
}

class SkipIntroRepository @Inject constructor(
    private val introDbProvider: IntroDbIntegrationProvider,
    private val aniSkipProvider: AniSkipIntegrationProvider,
    private val animeSkipProvider: AnimeSkipIntegrationProvider,
    private val armProvider: ArmIntegrationProvider,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    private val theIntroDbSettingsDataStore: TheIntroDbSettingsDataStore
) {
    suspend fun getSkipIntervals(contentId: String?, season: Int?, episode: Int?): List<SkipInterval> =
        contentId?.let { introDbProvider.getIntervals(it, season, episode) }.orEmpty()
}
```

Also move settings validation calls to the provider adapters so config screens respect the same runtime policies without taking a raw API dependency.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.anime.KitsuMetadataServiceTest" --tests "com.nexio.tv.data.repository.OmdbEpisodeRatingsRepositoryTest" --tests "com.nexio.tv.data.repository.SkipIntroRepositoryTidbTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt app/src/main/java/com/nexio/tv/data/integration/omdb/OmdbIntegrationProvider.kt app/src/main/java/com/nexio/tv/data/integration/skip app/src/main/java/com/nexio/tv/core/anime/KitsuMetadataService.kt app/src/main/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepository.kt app/src/main/java/com/nexio/tv/data/repository/SkipIntroRepository.kt app/src/main/java/com/nexio/tv/ui/screens/settings/OmdbSettingsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/TheIntroDbSettingsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/AnimeSkipSettingsViewModel.kt app/src/test/java/com/nexio/tv/core/anime/KitsuMetadataServiceTest.kt app/src/test/java/com/nexio/tv/data/repository/OmdbEpisodeRatingsRepositoryTest.kt app/src/test/java/com/nexio/tv/data/repository/SkipIntroRepositoryTidbTest.kt
git commit -m "feat: enable cache-first for anime and utility providers"
```

### Task 6: Enable cache-first for TMDB, TVDB, and MDBList read paths under the runtime and collapse duplicate read caches

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/mdblist/MDBListIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/integration/TmdbRuntimeRoutingTest.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/MDBListRuntimeRoutingTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `tmdb enrichment returns cached adapter result before touching provider maps`() = runTest {
    val runtime = RecordingIntegrationRuntime(successValue = expectedEnrichment)
    val provider = buildTmdbIntegrationProvider(runtime = runtime)
    val service = buildTmdbMetadataService(provider = provider)

    val result = service.fetchEnrichment("550", ContentType.MOVIE, "en-US")

    assertEquals(expectedEnrichment, result)
    assertEquals(listOf("tmdb:movie:550:en-US:enrichment"), runtime.keys)
}
```

```kotlin
@Test
fun `tvdb series enrichment serves stale adapter result when credentials are blocked`() = runTest {
    val runtime = RecordingIntegrationRuntime(nextResult = IntegrationFetchResult.Stale(expectedEnrichment))
    val provider = buildTvdbIntegrationProvider(runtime = runtime, credentialHealth = blockedHealth())
    val service = buildTvdbMetadataService(provider = provider)

    val result = service.fetchSeriesEnrichment(TvdbSeriesIdentity(tvdbId = 1234))

    assertEquals(expectedEnrichment, result)
}
```

```kotlin
@Test
fun `mdblist ratings repository uses mdblist integration provider key per provider batch`() = runTest {
    val runtime = RecordingIntegrationRuntime(successValue = MDBListRatingsResult(ratings = MDBListRatings(imdb = 8.8), hasImdbRating = true))
    val provider = buildMDBListIntegrationProvider(runtime = runtime)
    val repository = buildMDBListRepository(provider = provider)

    repository.getRatingsForMeta(meta, "tt0137523", "movie")

    assertTrue(runtime.keys.any { it.startsWith("mdblist:movie:tt0137523:") })
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest" --tests "com.nexio.tv.core.integration.TmdbRuntimeRoutingTest" --tests "com.nexio.tv.data.repository.MDBListRuntimeRoutingTest"`

Expected: FAIL because these services still own their own runtime/network/cache decisions directly instead of delegating to adapter-owned runtime specs.

- [ ] **Step 3: Write minimal implementation**

Move `CacheFirst` behavior into adapter packages, then thin the existing services:

```kotlin
class TmdbIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val tmdbApi: TmdbApi,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val tmdbCredentialProvider: suspend () -> MetadataProviderCredential
) {
    suspend fun fetchEnrichment(tmdbId: String, contentType: ContentType, language: String): TmdbEnrichment? {
        val providerToken = posterProviderCacheToken(posterRatingsUrlResolver.getActiveProvider())
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            cacheKey = "tmdb:${contentType.name.lowercase()}:$tmdbId:$language:enrichment:$providerToken",
            codec = gsonCodec<TmdbEnrichment>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 7L * 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 30L * 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = { loadTmdbEnrichment(tmdbId, contentType, language, providerToken) }
        )
        return runtime.get(spec).valueOrNull()
    }
}

class TmdbMetadataService @Inject constructor(
    private val tmdbProvider: TmdbIntegrationProvider
) {
    suspend fun fetchEnrichment(tmdbId: String, contentType: ContentType, language: String?): TmdbEnrichment? =
        tmdbProvider.fetchEnrichment(
            tmdbId = tmdbId,
            contentType = contentType,
            language = normalizeTmdbLanguage(language ?: currentTmdbLanguageTag())
        )
}
```

```kotlin
class TvdbIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val tvdbApi: TvdbApi,
    private val authService: TvdbAuthService,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val credentialHealth: TvdbCredentialHealth
) {
    suspend fun fetchSeriesEnrichment(identity: TvdbSeriesIdentity, language: String): TvMetadataEnrichment? {
        val resolvedId = resolveSeriesAlias(identity.tvdbId)
        val providerToken = posterProviderCacheToken(posterRatingsUrlResolver.getActiveProvider())
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TVDB,
            cacheKey = "tvdb:series:$resolvedId:$language:$providerToken",
            codec = gsonCodec<TvMetadataEnrichment>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 7L * 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 30L * 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = { loadTvdbSeries(identity, resolvedId, language, providerToken) }
        )
        return runtime.get(spec).valueOrNull()
    }
}
```

```kotlin
class MDBListIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val api: MDBListApi
) {
    suspend fun fetchRatings(
        imdbId: String,
        mediaType: String,
        apiKey: String,
        providers: List<String>
    ): MDBListRatingsResult? {
        val providerKey = providers.sorted().joinToString(",")
        val spec = IntegrationSpec(
            provider = IntegrationProvider.MDBLIST,
            cacheKey = "mdblist:$mediaType:$imdbId:$providerKey:${apiKey.hashCode()}",
            codec = gsonCodec<MDBListRatingsResult>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 30L * 60L * 1000L,
                staleAfterExpiryMs = 6L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = { loadMdblistRatings(imdbId, mediaType, apiKey, providers) }
        )
        return runtime.get(spec).valueOrNull()
    }
}
```

After the runtime-backed tests pass, delete the redundant read-side maps in this order:

1. `TmdbMetadataService.enrichmentCache`
2. `TmdbMetadataService.moreLikeThisCache`
3. `TmdbMetadataService.reviewsCache`
4. `MDBListRepository.cache`
5. `MDBListRepository.episodeRatingsCache`

Keep `MetadataDiskCacheStore` only as a migration fallback until all direct callers move.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest" --tests "com.nexio.tv.core.integration.TmdbRuntimeRoutingTest" --tests "com.nexio.tv.data.repository.MDBListRuntimeRoutingTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt app/src/main/java/com/nexio/tv/data/integration/mdblist/MDBListIntegrationProvider.kt app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt app/src/main/java/com/nexio/tv/data/repository/MDBListRepository.kt app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt app/src/test/java/com/nexio/tv/core/integration/TmdbRuntimeRoutingTest.kt app/src/test/java/com/nexio/tv/data/repository/MDBListRuntimeRoutingTest.kt
git commit -m "feat: migrate metadata providers to integration runtime"
```

### Task 7: Enable runtime-backed Trakt review reads and tracking-provider adapters, then rename the outbox coordinator

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/simkl/SimklIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/TraktReviewsRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxCoordinator.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trakt/outbox/ProviderMutationOutboxCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklScrobbleService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/detail/TraktReviewsRepositoryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelLibraryTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `trakt reviews repository uses runtime and caches pages by endpoint plus page`() = runTest {
    val runtime = RecordingIntegrationRuntime(successValue = listOf(expectedReview))
    val provider = TraktIntegrationProvider(runtime, traktApi, traktAuthService)
    val repository = TraktReviewsRepository(provider)

    repository.fetchPage(metaId = "movie:123", pathId = "fight-club-1999", isShow = false, page = 1)

    assertEquals(listOf("trakt:reviews:movie:fight-club-1999:page:1"), runtime.keys)
}
```

```kotlin
@Test
fun `meta details no longer calls trakt auth service directly for reviews`() = runTest {
    val traktAuthService = mockk<TraktAuthService>(relaxed = true)
    val repository = mockk<TraktReviewsRepository>()
    buildMetaDetailsViewModel(traktAuthService = traktAuthService, traktReviewsRepository = repository)

    coVerify(exactly = 0) { traktAuthService.executeAuthorizedRequest<List<TraktCommentItemDto>>(any()) }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.detail.TraktReviewsRepositoryTest" --tests "com.nexio.tv.ui.screens.detail.MetaDetailsViewModelLibraryTest"`

Expected: FAIL because `MetaDetailsViewModel` still issues Trakt comment requests directly and the repository does not yet depend on a provider adapter.

- [ ] **Step 3: Write minimal implementation**

Introduce a provider-backed repository, not a runtime-owning one:

```kotlin
class TraktIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    suspend fun fetchReviewsPage(
        metaId: String,
        pathId: String,
        isShow: Boolean,
        page: Int
    ): List<MetaReview> {
        val endpoint = if (isShow) "show" else "movie"
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            cacheKey = "trakt:reviews:$endpoint:$pathId:page:$page",
            codec = gsonCodec<List<MetaReview>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val response = traktAuthService.executeAuthorizedRequest { authorization ->
                    if (isShow) traktApi.getShowComments(pathId, authorization, page, TRAKT_REVIEWS_PAGE_SIZE)
                    else traktApi.getMovieComments(pathId, authorization, page, TRAKT_REVIEWS_PAGE_SIZE)
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")
                val body = response.body().orEmpty().map { it.toMetaReview(metaId) }
                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_reviews_failed"
                    )
                }
                IntegrationLoadResult.Success(body)
            }
        )
        return runtime.get(spec).valueOrNull().orEmpty()
    }
}

class TraktReviewsRepository @Inject constructor(
    private val traktProvider: TraktIntegrationProvider
) {
    suspend fun fetchPage(
        metaId: String,
        pathId: String,
        isShow: Boolean,
        page: Int
    ): List<MetaReview> =
        traktProvider.fetchReviewsPage(
            metaId = metaId,
            pathId = pathId,
            isShow = isShow,
            page = page
        )
}
```

Then remove the direct ViewModel escape hatch:

```kotlin
class MetaDetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metaRepository: MetaRepository,
    private val traktReviewsRepository: TraktReviewsRepository,
    ...
)
```

And inside `fetchTraktReviewsPage`:

```kotlin
val reviews = traktReviewsRepository.fetchPage(
    metaId = metaIdForLogs,
    pathId = query.pathId,
    isShow = query.isShowEndpoint,
    page = page
)
```

Finally, rename the outbox abstraction while keeping a compatibility shim for one commit:

```kotlin
typealias TraktMutationOutboxCoordinator = ProviderMutationOutboxCoordinator

class ProviderMutationOutboxCoordinator @Inject constructor(
    ...
)
```

Follow with a cleanup commit that changes constructor types across Trakt and Simkl services.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.detail.TraktReviewsRepositoryTest" --tests "com.nexio.tv.ui.screens.detail.MetaDetailsViewModelLibraryTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt app/src/main/java/com/nexio/tv/data/integration/simkl/SimklIntegrationProvider.kt app/src/main/java/com/nexio/tv/data/repository/TraktReviewsRepository.kt app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxCoordinator.kt app/src/main/java/com/nexio/tv/data/trakt/outbox/ProviderMutationOutboxCoordinator.kt app/src/main/java/com/nexio/tv/data/repository/SimklScrobbleService.kt app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt app/src/test/java/com/nexio/tv/ui/screens/detail/TraktReviewsRepositoryTest.kt app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelLibraryTest.kt
git commit -m "feat: route tracking reads through integration runtime"
```

### Task 8: Route Real-Debrid, Premiumize, TorBox, and EasyDebrid through runtime-backed provider adapters

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/debrid/RealDebridIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/debrid/PremiumizeIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/debrid/TorBoxIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/debrid/EasyDebridIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/RealDebridAuthService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/PremiumizeService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TorBoxService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/EasyDebridService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/DebridLibraryServiceTest.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/DebridRuntimePolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `real debrid library refresh uses runtime provider key and respects stale fallback`() = runTest {
    val runtime = RecordingIntegrationRuntime(nextResult = IntegrationFetchResult.Stale(expectedItems))
    val service = buildDebridLibraryService(
        realDebridProvider = RealDebridIntegrationProvider(runtime, realDebridApi, realDebridAuthService)
    )

    service.refreshNow(DebridLibraryService.RefreshTarget.REAL_DEBRID)

    assertTrue(runtime.keys.any { it.startsWith("realdebrid:library:") })
}
```

```kotlin
@Test
fun `debrid providers remain serial until explicitly raised`() = runTest {
    val registry = defaultIntegrationPolicyRegistry()
    assertEquals(1, registry.policyFor(IntegrationProvider.REAL_DEBRID).maxConcurrentNetworkStarts)
    assertEquals(1, registry.policyFor(IntegrationProvider.PREMIUMIZE).maxConcurrentNetworkStarts)
    assertEquals(1, registry.policyFor(IntegrationProvider.TORBOX).maxConcurrentNetworkStarts)
    assertEquals(1, registry.policyFor(IntegrationProvider.EASY_DEBRID).maxConcurrentNetworkStarts)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.DebridLibraryServiceTest" --tests "com.nexio.tv.data.repository.DebridRuntimePolicyTest"`

Expected: FAIL because the debrid code still talks directly to provider APIs or auth services without going through adapter-owned runtime policies.

- [ ] **Step 3: Write minimal implementation**

Keep auth refresh logic inside each auth/service class, but move runtime ownership into debrid provider adapters:

```kotlin
class RealDebridIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val realDebridApi: RealDebridApi,
    private val realDebridAuthService: RealDebridAuthService
) {
    suspend fun <T> executeRequest(
        cacheKey: String,
        cachePolicy: IntegrationCachePolicy,
        workClass: IntegrationWorkClass,
        block: suspend (authorization: String) -> Response<T>
    ): IntegrationFetchResult<T> {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.REAL_DEBRID,
            cacheKey = cacheKey,
            codec = gsonCodec<T>(),
            cachePolicy = cachePolicy,
            workClass = workClass,
            scope = IntegrationScope.Account(realDebridAuthService.getCurrentAuthState().username ?: "realdebrid"),
            load = {
                val response = realDebridAuthService.executeAuthorizedRequest(block)
                    ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "realdebrid_auth_missing")
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "realdebrid_request_failed"
                    )
                }
                IntegrationLoadResult.Success(body)
            }
        )
        return runtime.get(spec)
    }
}
```

Apply the same pattern in `PremiumizeIntegrationProvider`, `TorBoxIntegrationProvider`, and `EasyDebridIntegrationProvider`, then change `DebridLibraryService` and `DebridAvailabilityResolver` to call adapter methods instead of raw APIs directly.

For debrid playback-adjacent availability and link-resolution work, classify adapter requests as:

```kotlin
IntegrationWorkClass.PLAYBACK_RESOLUTION
```

Do not classify those paths as ordinary metadata reads.

Important rule for this task: keep all per-provider semaphores deleted unless the runtime policy explicitly raises that provider above `1`. For example, this existing pattern:

```kotlin
val infoSemaphore = Semaphore(6)
```

must not survive unchanged. Replace it with provider-adapter calls plus a comment explaining that any concurrency increase must be done in `IntegrationPolicyRegistry`, not at the call site.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.DebridLibraryServiceTest" --tests "com.nexio.tv.data.repository.DebridRuntimePolicyTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/debrid app/src/main/java/com/nexio/tv/data/repository/RealDebridAuthService.kt app/src/main/java/com/nexio/tv/data/repository/PremiumizeService.kt app/src/main/java/com/nexio/tv/data/repository/TorBoxService.kt app/src/main/java/com/nexio/tv/data/repository/EasyDebridService.kt app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt app/src/test/java/com/nexio/tv/data/repository/DebridLibraryServiceTest.kt app/src/test/java/com/nexio/tv/data/repository/DebridRuntimePolicyTest.kt
git commit -m "feat: route debrid providers through integration runtime"
```

### Task 9: Bring RPDB and TopPosters under runtime-controlled image fetching and resolve the TopPosters host identity

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/RpdbIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt`
- Create: `app/src/main/java/com/nexio/tv/core/image/PosterIntegrationRequest.kt`
- Create: `app/src/main/java/com/nexio/tv/core/image/IntegrationPosterFetcher.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/NexioApplication.kt`
- Create: `app/src/test/java/com/nexio/tv/core/image/IntegrationPosterFetcherTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomePlaybackWorkGateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `poster fetcher converts poster request into runtime cache key instead of remote url pass through`() = runTest {
    val runtime = RecordingIntegrationRuntime(successValue = testFile)
    val fetcher = IntegrationPosterFetcher(
        rpdbProvider = RpdbIntegrationProvider(runtime),
        topPostersProvider = TopPostersIntegrationProvider(runtime)
    )

    fetcher.fetch(
        PosterIntegrationRequest(
            provider = IntegrationProvider.RPDB,
            cacheKey = "rpdb:imdb:tt0137523:poster-default",
            remoteUrl = "https://api.ratingposterdb.com/key/imdb/poster-default/tt0137523.jpg"
        )
    )

    assertEquals(listOf("rpdb:imdb:tt0137523:poster-default"), runtime.keys)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.image.IntegrationPosterFetcherTest"`

Expected: FAIL because poster loading still bypasses the runtime and uses remote URLs directly.

- [ ] **Step 3: Write minimal implementation**

Introduce typed poster-provider adapters plus a typed poster request:

```kotlin
data class PosterIntegrationRequest(
    val provider: IntegrationProvider,
    val cacheKey: String,
    val remoteUrl: String,
    val ttlMs: Long = 12L * 60L * 60L * 1000L
)
```

Use a runtime-backed fetcher:

```kotlin
class IntegrationPosterFetcher @Inject constructor(
    private val rpdbProvider: RpdbIntegrationProvider,
    private val topPostersProvider: TopPostersIntegrationProvider
) {
    suspend fun fetch(request: PosterIntegrationRequest): File? {
        return when (request.provider) {
            IntegrationProvider.RPDB -> rpdbProvider.fetchPoster(request)
            IntegrationProvider.TOP_POSTERS -> topPostersProvider.fetchPoster(request)
            else -> null
        }
    }
}
```

Replace `PosterRatingsUrlResolver` return values with `PosterIntegrationRequest` for RPDB and TopPosters instead of final remote URLs, and keep the actual spec-building logic inside `RpdbIntegrationProvider` / `TopPostersIntegrationProvider`.

Lock the TopPosters identity now:

```kotlin
enum class IntegrationProvider {
    ...
    TOP_POSTERS
}
```

Treat `api.top-posters.com` and `api.top-streaming.stream` as endpoint aliases for the same provider until product decides otherwise. Do not create two cache namespaces for the same logical service.

Register the custom fetcher in `NexioApplication.newImageLoader()` so Coil receives local `File` values or fetcher-owned models, not raw remote poster URLs.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.image.IntegrationPosterFetcherTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/posters app/src/main/java/com/nexio/tv/core/image/PosterIntegrationRequest.kt app/src/main/java/com/nexio/tv/core/image/IntegrationPosterFetcher.kt app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt app/src/main/java/com/nexio/tv/NexioApplication.kt app/src/test/java/com/nexio/tv/core/image/IntegrationPosterFetcherTest.kt
git commit -m "feat: move poster providers under integration runtime"
```

### Task 10: Wire playback/startup policy into the runtime, document the final architecture, and remove only the legacy caches that are now dead

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomePlaybackWorkGate.kt`
- Modify: `app/src/main/java/com/nexio/tv/NexioApplication.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbUpdateCoordinator.kt`
- Create: `docs/architecture/api-integration-runtime.md`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomePlaybackWorkGateTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `playback gate sets runtime playback state and does not cancel fresh cache reads`() {
    val gate = IntegrationPlaybackGate()
    gate.setPlaybackActive(true)

    val metadataPolicy = defaultIntegrationPolicyRegistry().policyFor(IntegrationProvider.TMDB)
    val traktPolicy = defaultIntegrationPolicyRegistry().policyFor(IntegrationProvider.TRAKT)

    assertTrue(gate.isBlocked(metadataPolicy, IntegrationWorkClass.BACKGROUND_HYDRATION))
    assertTrue(!gate.isBlocked(traktPolicy, IntegrationWorkClass.SCROBBLE))
}
```

```kotlin
@Test
fun `tvdb startup work enters runtime as maintenance not direct uncategorized network`() = runTest {
    val runtime = RecordingIntegrationRuntime(successValue = TvdbUpdateCoordinatorResult.Success(0, 0))
    val coordinator = buildTvdbUpdateCoordinator(
        tvdbProvider = buildTvdbIntegrationProvider(runtime = runtime)
    )

    coordinator.catchUpUpdates(TvdbUpdateTrigger.STARTUP)

    assertTrue(runtime.keys.any { it.startsWith("tvdb:updates:startup") })
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomePlaybackWorkGateTest" --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"`

Expected: FAIL because playback/startup state is not yet routed through the runtime.

- [ ] **Step 3: Write minimal implementation**

Propagate playback state into the runtime:

```kotlin
internal fun HomeViewModel.observePlaybackWorkGate() {
    viewModelScope.launch {
        playbackIdleGateState.snapshot.collectLatest { snapshot ->
            integrationPlaybackGate.setPlaybackActive(snapshot.hasActiveSession)
            if (!shouldRunHomeBackgroundWork(snapshot)) {
                cancelNonPlaybackHomeWorkForPlayback()
            }
        }
    }
}
```

Make startup maintenance explicit:

```kotlin
override fun onCreate() {
    super.onCreate()
    ...
    appScope.launch {
        integrationPlaybackGate.setPlaybackActive(false)
        tvdbUpdateCoordinator.catchUpUpdates(TvdbUpdateTrigger.STARTUP)
    }
}
```

And inside `TvdbUpdateCoordinator.catchUpUpdates`, route through the TVDB provider adapter rather than injecting the runtime directly into coordinator logic:

```kotlin
return tvdbIntegrationProvider.runMaintenanceUpdate(trigger)
    ?: TvdbUpdateCoordinatorResult.BlockedInvalidCredentials
```

Write the architecture doc with these invariants:

```markdown
1. Every non-playback external API call enters `IntegrationRuntime`.
2. Fresh cache is checked before provider queueing or backoff.
3. Every provider starts with `maxConcurrentNetworkStarts = 1`.
4. `429` and retryable `5xx` write durable provider backoff.
5. Playback blocks background and prefetch work, not cache reads or scrobbles.
6. Image providers use runtime-owned files, not raw remote poster URLs.
```

Remove only these caches if their last read path has migrated:

1. `KitsuMetadataService.cache`
2. `OmdbEpisodeRatingsRepository.cache`
3. `SkipIntroRepository.cache`
4. `MDBListRepository.cache`
5. `TmdbMetadataService.enrichmentCache`

Do not delete `MetadataDiskCacheStore` entirely in this task if any home/catalog code still relies on it.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomePlaybackWorkGateTest" --tests "com.nexio.tv.core.integration.DefaultIntegrationRuntimeTest" --tests "com.nexio.tv.data.repository.DebridLibraryServiceTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomePlaybackWorkGate.kt app/src/main/java/com/nexio/tv/NexioApplication.kt app/src/main/java/com/nexio/tv/core/tvdb/TvdbUpdateCoordinator.kt docs/architecture/api-integration-runtime.md app/src/test/java/com/nexio/tv/ui/screens/home/HomePlaybackWorkGateTest.kt app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt
git commit -m "feat: wire playback and startup policy into integration runtime"
```

### Task 11: Decommission bypass paths, delete transitional kill-switches, and lock the final no-bypass architecture

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/IntegrationBoundaryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/NoRawProviderInjectionTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/NoRuntimeSpecOutsideIntegrationPackagesTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/NoLegacyProviderFallbacksTest.kt`
- Modify: `docs/architecture/api-integration-runtime.md`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoRuntimeSpecOutsideIntegrationPackagesTest {
    @Test
    fun `only integration packages create integration specs`() {
        val offenders = architectureScan(
            allowedPackages = setOf(
                "com.nexio.tv.data.integration",
                "com.nexio.tv.core.integration"
            ),
            forbiddenSimpleNames = setOf("IntegrationSpec")
        )

        if (offenders.isNotEmpty()) {
            fail("IntegrationSpec creation escaped adapter boundary: $offenders")
        }
    }
}
```

```kotlin
package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoLegacyProviderFallbacksTest {
    @Test
    fun `legacy direct provider fallbacks are removed after migration`() {
        val offenders = sourceTextScan(
            forbiddenPatterns = listOf(
                "executeAuthorizedRequest {",
                "kitsuApi.",
                "tmdbApi.",
                "tvdbApi.",
                "omdbApi.",
                "introDbApi.",
                "aniSkipApi.",
                "animeSkipApi.",
                "realDebridApi.",
                "premiumizeApi.",
                "torBoxApi.",
                "easyDebridApi."
            ),
            allowedPaths = listOf(
                "app/src/main/java/com/nexio/tv/data/integration/",
                "app/src/main/java/com/nexio/tv/data/remote/",
                "app/src/main/java/com/nexio/tv/core/di/"
            )
        )

        if (offenders.isNotEmpty()) {
            fail("Legacy provider fallback paths still exist: $offenders")
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.NoRuntimeSpecOutsideIntegrationPackagesTest" --tests "com.nexio.tv.architecture.NoLegacyProviderFallbacksTest"`

Expected: FAIL while direct-call fallbacks, constructor injections, or feature-owned specs still exist.

- [ ] **Step 3: Write minimal implementation**

Delete the final transitional escape hatches:

1. remove any feature-level `IntegrationRuntime` injection where a repository/facade should be injected instead
2. delete temporary provider kill-switches that allow feature code to fall back to raw APIs
3. delete direct provider API fields from ViewModels, routers, workers, and settings screens
4. delete redundant caches whose last caller is now adapter-owned runtime storage
5. make `MetadataDiskCacheStore` either runtime-owned compatibility storage or delete the dead entry families entirely

Tighten the final documentation contract:

```markdown
Final invariants:
1. UI, ViewModels, routers, and workers never inject provider Retrofit APIs.
2. UI, ViewModels, routers, workers, and feature-facing repositories never inject `IntegrationRuntime`.
3. UI, ViewModels, routers, and workers never build `IntegrationSpec`s.
4. Provider adapters are the only legal raw provider callers.
5. `IntegrationRuntime` is the only legal non-playback external-call gateway.
6. New providers must add adapter + policy + tests before first use.
```

Tighten DI so raw provider APIs are no longer visible to feature modules except through adapter construction:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object IntegrationProviderModule {
    @Provides
    @Singleton
    fun provideMetadataRepository(
        tmdbProvider: TmdbIntegrationProvider,
        tvdbProvider: TvdbIntegrationProvider,
        kitsuProvider: KitsuIntegrationProvider
    ): MetadataRepository = DefaultMetadataRepository(
        tmdbProvider = tmdbProvider,
        tvdbProvider = tvdbProvider,
        kitsuProvider = kitsuProvider
    )
}
```

Remove or narrow obsolete raw-provider bindings where possible. If Hilt still needs to expose Retrofit APIs for adapter construction, the boundary tests remain the enforcement point for the app layer.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.IntegrationBoundaryTest" --tests "com.nexio.tv.architecture.NoRawProviderInjectionTest" --tests "com.nexio.tv.architecture.NoIntegrationRuntimeInjectionOutsideBoundaryTest" --tests "com.nexio.tv.architecture.NoRuntimeSpecOutsideIntegrationPackagesTest" --tests "com.nexio.tv.architecture.NoLegacyProviderFallbacksTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt app/src/main/java/com/nexio/tv/data/repository/servicewrap/DebridAvailabilityResolver.kt app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt app/src/test/java/com/nexio/tv/architecture app/src/test/java/com/nexio/tv/architecture/NoIntegrationRuntimeInjectionOutsideBoundaryTest.kt app/src/test/java/com/nexio/tv/architecture/NoRuntimeSpecOutsideIntegrationPackagesTest.kt app/src/test/java/com/nexio/tv/architecture/NoLegacyProviderFallbacksTest.kt docs/architecture/api-integration-runtime.md
git commit -m "refactor: remove legacy provider bypasses"
```

### Task 12: Add RailStore and MediaIdentityStore primitives for shared cache ownership

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/RailCacheEntity.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/RailItemEntity.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/MediaIdentityEntity.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/ExternalIdEntity.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/RailStoreDao.kt`
- Create: `app/src/main/java/com/nexio/tv/data/local/integration/MediaIdentityDao.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDatabase.kt`
- Create: `app/src/test/java/com/nexio/tv/data/local/integration/RailStoreDaoTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `rail items and media identities persist shared ownership roots`() = runTest {
    val db = inMemoryIntegrationCacheDatabase()

    db.railStoreDao().upsertRail(
        RailCacheEntity(
            railKey = "home:tmdb:popular:movies",
            provider = "TMDB",
            kind = "POPULAR_MOVIES",
            paramsHash = "lang=en-US",
            fetchedAtEpochMs = 1_000L,
            expiresAtEpochMs = 61_000L,
            staleUntilEpochMs = 121_000L
        )
    )
    db.mediaIdentityDao().upsertMediaIdentity(
        MediaIdentityEntity(
            mediaKey = "movie:imdb:tt0137523",
            mediaType = "movie",
            title = "Fight Club",
            year = 1999,
            updatedAtEpochMs = 1_000L
        )
    )
    db.railStoreDao().replaceRailItems(
        railKey = "home:tmdb:popular:movies",
        items = listOf(
            RailItemEntity(
                key = "home:tmdb:popular:movies#movie:imdb:tt0137523",
                railKey = "home:tmdb:popular:movies",
                mediaKey = "movie:imdb:tt0137523",
                position = 0,
                updatedAtEpochMs = 1_000L
            )
        )
    )

    assertEquals(1, db.railStoreDao().itemsForRail("home:tmdb:popular:movies").size)
    assertEquals("Fight Club", db.mediaIdentityDao().getMediaIdentity("movie:imdb:tt0137523")?.title)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.integration.RailStoreDaoTest"`

Expected: FAIL because rail and media identity entities/DAOs do not exist yet.

- [ ] **Step 3: Write minimal implementation**

```kotlin
@Entity(tableName = "integration_rail_cache")
data class RailCacheEntity(
    @PrimaryKey val railKey: String,
    val provider: String,
    val kind: String,
    val paramsHash: String,
    val fetchedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val staleUntilEpochMs: Long
)

@Entity(tableName = "integration_rail_item")
data class RailItemEntity(
    @PrimaryKey val key: String,
    val railKey: String,
    val mediaKey: String,
    val position: Int,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "integration_media_identity")
data class MediaIdentityEntity(
    @PrimaryKey val mediaKey: String,
    val mediaType: String,
    val title: String?,
    val year: Int?,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "integration_external_id")
data class ExternalIdEntity(
    @PrimaryKey val key: String,
    val mediaKey: String,
    val provider: String,
    val externalId: String,
    val idType: String
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.integration.RailStoreDaoTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/integration app/src/test/java/com/nexio/tv/data/local/integration/RailStoreDaoTest.kt
git commit -m "feat: add rail and media identity storage"
```

### Task 13: Add rail-aware ownership and orphan cleanup

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationOwnershipService.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationOrphanCleanupService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDao.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/RailStoreDao.kt`
- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationOwnershipServiceTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `orphan cleanup keeps cache entry while another rail still owns same media`() = runTest {
    val fixture = railOwnershipFixture()
    fixture.seedMediaOwnedByRails(
        mediaKey = "movie:imdb:tt0137523",
        railKeys = listOf("home:tmdb:popular:movies", "home:mdblist:top:horror")
    )

    fixture.ownershipService.removeRail("home:tmdb:popular:movies")
    val retained = fixture.cacheDao.findByMediaKey("movie:imdb:tt0137523")

    assertTrue(retained.isNotEmpty())
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationOwnershipServiceTest"`

Expected: FAIL because shared ownership logic does not exist yet.

- [ ] **Step 3: Write minimal implementation**

```kotlin
class IntegrationOwnershipService @Inject constructor(
    private val railStoreDao: RailStoreDao,
    private val cacheDao: IntegrationCacheDao
) {
    suspend fun removeRail(railKey: String) {
        val items = railStoreDao.itemsForRail(railKey)
        railStoreDao.deleteRail(railKey)
        items.forEach { item ->
            val remainingOwners = railStoreDao.railsForMedia(item.mediaKey)
            if (remainingOwners.isEmpty()) {
                cacheDao.deleteByMediaKey(item.mediaKey)
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationOwnershipServiceTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationOwnershipService.kt app/src/main/java/com/nexio/tv/core/integration/IntegrationOrphanCleanupService.kt app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDao.kt app/src/main/java/com/nexio/tv/data/local/integration/RailStoreDao.kt app/src/test/java/com/nexio/tv/core/integration/IntegrationOwnershipServiceTest.kt
git commit -m "feat: add rail-aware cache ownership cleanup"
```

### Task 14: Add active-rail hydration planner

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationHydrationPlanner.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/ActiveRailTracker.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationHydrationPlannerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `hydration planner refreshes active stale rails before inactive stale rails`() = runTest {
    val planner = plannerFixture()
    planner.seedRail("home:tmdb:popular:movies", active = true, stale = true)
    planner.seedRail("home:mdblist:top:horror", active = false, stale = true)

    val next = planner.planNextBatch(limit = 2)

    assertEquals("home:tmdb:popular:movies", next.first().railKey)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationHydrationPlannerTest"`

Expected: FAIL because active-rail planning does not exist yet.

- [ ] **Step 3: Write minimal implementation**

```kotlin
class IntegrationHydrationPlanner @Inject constructor(
    private val railStoreDao: RailStoreDao,
    private val activeRailTracker: ActiveRailTracker
) {
    suspend fun planNextBatch(limit: Int): List<RailCacheEntity> =
        railStoreDao.staleRails(limit = 200)
            .sortedWith(
                compareByDescending<RailCacheEntity> { activeRailTracker.isActive(it.railKey) }
                    .thenBy { it.expiresAtEpochMs }
            )
            .take(limit)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationHydrationPlannerTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationHydrationPlanner.kt app/src/main/java/com/nexio/tv/core/integration/ActiveRailTracker.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/test/java/com/nexio/tv/core/integration/IntegrationHydrationPlannerTest.kt
git commit -m "feat: add active rail hydration planning"
```

### Task 15: Retire old catalog/discovery ownership paths and verify the final long-run cache lifecycle

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt`
- Modify: `docs/architecture/api-integration-runtime.md`
- Create: `app/src/test/java/com/nexio/tv/architecture/RailOwnershipLifecycleTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `legacy snapshot ownership paths are retired in favor of rail store`() {
    val offenders = sourceTextScan(
        forbiddenPatterns = listOf(
            "replaceHomeFeedReferences(",
            "removeHomeUnreferencedMetaEntries(",
            "home_ref::"
        ),
        allowedPaths = listOf(
            "app/src/test/",
            "docs/architecture/"
        )
    )

    assertTrue("Legacy snapshot ownership paths still exist: $offenders", offenders.isEmpty())
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.RailOwnershipLifecycleTest"`

Expected: FAIL because the old ownership/snapshot paths still exist.

- [ ] **Step 3: Write minimal implementation**

Replace legacy home/reference ownership calls with `RailStoreDao` + `IntegrationOwnershipService` flows, update docs to describe the final lifecycle, and delete dead helper methods from `MetadataDiskCacheStore`.

```kotlin
class HomeCatalogSnapshotStore @Inject constructor(
    private val railStoreDao: RailStoreDao,
    private val ownershipService: IntegrationOwnershipService
) {
    suspend fun replaceRailMembership(railKey: String, itemKeys: List<String>) {
        railStoreDao.upsertRail(
            RailCacheEntity(
                railKey = railKey,
                provider = railKey.substringBefore(':').uppercase(),
                kind = "HOME",
                paramsHash = railKey,
                fetchedAtEpochMs = System.currentTimeMillis(),
                expiresAtEpochMs = System.currentTimeMillis() + 30_000L,
                staleUntilEpochMs = System.currentTimeMillis() + 3_600_000L
            )
        )
        railStoreDao.replaceRailItems(
            railKey = railKey,
            items = itemKeys.mapIndexed { index, mediaKey ->
                RailItemEntity(
                    key = "$railKey#$mediaKey",
                    railKey = railKey,
                    mediaKey = mediaKey,
                    position = index,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            }
        )
        ownershipService.cleanupOrphansAfterRailRefresh(railKey)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.architecture.RailOwnershipLifecycleTest" --tests "com.nexio.tv.architecture.NoLegacyProviderFallbacksTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt docs/architecture/api-integration-runtime.md app/src/test/java/com/nexio/tv/architecture/RailOwnershipLifecycleTest.kt
git commit -m "refactor: retire legacy snapshot ownership paths"
```

## Self-Review

### Spec coverage

- Central runtime primitives, storage, and cache-first invariant: Tasks 1-3
- Mandatory gateway, provider-adapter boundary, and CI enforcement: Task 4
- Every listed extra integration:
  - Real-Debrid, Premiumize, TorBox, EasyDebrid: Task 8
  - TheIntroDb, OMDb, AnimeSkip/AniSkip, ARM: Task 5
  - RPDB and TopPosters: Task 9
  - Trakt and Simkl read/mutation paths: Task 7
- 429 handling and persisted backoff: Tasks 2-3, then exercised again in Tasks 7-10
- Heavy concurrency prevention unless approved: Task 1 policy registry, Task 3 provider request gate, Task 8 semaphore removal
- Playback and startup gating: Task 10
- No direct `IntegrationRuntime` injection into feature code: Task 4 and Task 11
- Real typed codecs and stale-window semantics: Tasks 1 and 3
- Full lifecycle through final decommission and no-bypass steady state: Task 11
- Rail ownership, orphan cleanup, active hydration, and retirement of old snapshot ownership paths: Tasks 12-15
- Easy future extension: Task 1 provider registry, Task 4 adapter boundary, plus Task 3 typed spec/load-result model

### Placeholder scan

No task says `TODO`, `TBD`, `later`, `similar to`, or `add tests` without concrete code or commands.

### Type consistency

The plan uses these stable names throughout and they should not drift during implementation:

- `IntegrationProvider`
- `IntegrationProviderPolicy`
- `IntegrationScope`
- `IntegrationWorkClass`
- `IntegrationSpec`
- `IntegrationLoadResult`
- `IntegrationFetchResult`
- `IntegrationRuntime`
- `IntegrationPlaybackGate`
- `ProviderMutationOutboxCoordinator`

If implementation changes one of those names, update every later task before continuing.

## Execution Notes

Recommended execution order:

1. Task 1
2. Task 2
3. Task 3
4. Task 4
5. Task 5
6. Task 6
7. Task 7
8. Task 8
9. Task 9
10. Task 10
11. Task 11
12. Task 12
13. Task 13
14. Task 14
15. Task 15

Phase mapping:

1. Phase A Foundation: Tasks 1-3
2. Phase B Mandatory Gateway: Task 4
3. Phase C Cache/Data Migration: Tasks 5-10
4. Phase D Legacy Decommission: Task 11
5. Phase E Steady-State Guardrails: Tasks 10-11 plus architecture docs/tests left green in CI
6. Phase F Rail Ownership and Hydration: Tasks 12-15

Do not skip directly to Tasks 5-10. The architecture is not allowed to enter a long-lived partial state where some providers still bypass the runtime. Task 4 is the approval gate that makes the runtime mandatory for all non-playback integrations before cache behavior deepens provider by provider.

Do not treat Task 11 as the end if the accepted end-state still includes rail ownership, orphan cleanup, and active-rail hydration. In that case, Tasks 12-15 are part of completion, not optional follow-up cleanup.

Cloudflare Worker + R2 remain explicitly deferred throughout Tasks 1-15. The only current obligation is to keep `IntegrationRuntime` behind the `IntegrationCacheStore` seam so a later remote tier can be added without another runtime contract rewrite.
