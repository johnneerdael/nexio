# Rail Cache Lifecycle Completion Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the `integration-rail-cache-lifecycle` implementation so real runtime cache rows are owned by canonical media keys, orphan cleanup deletes rows and blobs, active stale rails trigger targeted refresh work, and persisted snapshots never outrun the authoritative rail graph.

**Architecture:** Add explicit `IntegrationCacheOwnership` metadata to `IntegrationSpec` so title-backed runtime cache rows persist the canonical `mediaKey` that rail ownership already uses. Route orphan cleanup through blob-aware cache deletion, remove the store-side fire-and-forget ownership queue, and replace the generic hydration notifier path with a targeted home-rail executor that consumes `IntegrationHydrationPlanner` output directly.

**Tech Stack:** Kotlin, Coroutines, Hilt, Room, Robolectric, JUnit, MockK

---

## Scope Check

This stays inside one subsystem: `integration-rail-cache-lifecycle`. Do not split it. The three findings all point at the same broken seam between `IntegrationRuntime` cache rows, the rail/media ownership graph, and the home hydration path. Fixing only one side leaves the system half-old and still out of spec.

## File Structure

### New files

- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheOwnership.kt`
  Responsibility: define explicit ownership metadata for runtime cache rows.
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheOwnershipFactory.kt`
  Responsibility: convert raw title identifiers into `IntegrationCacheOwnership.Media` using the existing canonical identity resolver.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeRailHydrationExecutor.kt`
  Responsibility: map planned stale home rails to targeted provider refresh actions instead of a generic “refresh everything” notifier.
- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationCacheOwnershipTest.kt`
  Responsibility: prove `DefaultIntegrationRuntime` and `LocalIntegrationCacheStore` persist owner tokens for media-owned cache rows.
- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationOrphanCleanupServiceTest.kt`
  Responsibility: prove orphan cleanup deletes both DB rows and blob files for owned cache entries.
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailHydrationExecutorTest.kt`
  Responsibility: prove only the providers represented by planned rails are refreshed, and that TMDB/Kitsu targeted catalog refresh is used.

### Modified files

- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationSpec.kt`
  Responsibility: add explicit cache-ownership metadata.
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheStore.kt`
  Responsibility: expose blob-aware owned-cache deletion to orphan cleanup.
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStore.kt`
  Responsibility: persist `ownerToken` for media-owned rows and delete blob-backed rows by owner token.
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDao.kt`
  Responsibility: support blob-aware lookup/deletion by owner token.
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationBlobStore.kt`
  Responsibility: support deleting blobs and pruning empty parent directories.
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationOrphanCleanupService.kt`
  Responsibility: delete real cache rows and blobs through the cache store, not just Room rows.
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationRuntimeTestFixtures.kt`
  Responsibility: add helpers for owned cache seeding and spec capture.
- Modify: `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt`
  Responsibility: mark title-backed Kitsu cache rows with canonical media ownership.
- Modify: `app/src/main/java/com/nexio/tv/data/integration/mdblist/MDBListIntegrationProvider.kt`
  Responsibility: mark MDBList rating caches with canonical media ownership.
- Modify: `app/src/main/java/com/nexio/tv/data/integration/omdb/OmdbIntegrationProvider.kt`
  Responsibility: mark OMDb season rating caches with canonical media ownership.
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/RpdbIntegrationProvider.kt`
  Responsibility: mark RPDB poster caches with canonical media ownership.
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt`
  Responsibility: mark TopPosters poster caches with canonical media ownership.
- Modify: `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt`
  Responsibility: mark TMDB runtime-backed title caches with canonical media ownership.
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
  Responsibility: mark TMDB enrichment caches with canonical media ownership.
- Modify: `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt`
  Responsibility: mark TVDB runtime-backed title caches with canonical media ownership.
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt`
  Responsibility: mark TVDB enrichment/update caches that are tied to title identity with canonical ownership where appropriate.
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationHydrationCoordinator.kt`
  Responsibility: return planned stale rails and stop collapsing them into a generic notifier side effect.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
  Responsibility: consume planner output directly and hand it to the targeted executor.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPriorityHydrationPipeline.kt`
  Responsibility: remain only the explicit broad hydration path; remove accidental coupling to active-rail planner results.
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
  Responsibility: remove ownership authority from the snapshot store so it becomes a pure serializer again.
- Modify: `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt`
  Responsibility: remove ownership authority from the snapshot store so it becomes a pure serializer again.
- Modify: `app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt`
  Responsibility: remove ownership authority from the snapshot store so it becomes a pure serializer again.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
  Responsibility: make rail sync authoritative and awaited before snapshot persistence.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
  Responsibility: sync rail ownership before writing persisted library snapshots.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt`
  Responsibility: sync rail ownership before writing persisted library snapshots.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
  Responsibility: sync rail ownership before writing persisted home snapshots.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModel.kt`
  Responsibility: clear rail ownership before clearing persisted home snapshots.
- Modify: `app/src/test/java/com/nexio/tv/core/integration/DefaultIntegrationRuntimeTest.kt`
  Responsibility: verify owner tokens are persisted for media-owned cache rows.
- Modify: `app/src/test/java/com/nexio/tv/core/integration/TmdbRuntimeRoutingTest.kt`
  Responsibility: assert TMDB runtime specs carry canonical media ownership.
- Modify: `app/src/test/java/com/nexio/tv/data/repository/MDBListRuntimeRoutingTest.kt`
  Responsibility: assert MDBList runtime specs carry canonical media ownership.
- Modify: `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt`
  Responsibility: assert TVDB runtime specs carry canonical media ownership for title-backed cache rows.
- Modify: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`
  Responsibility: stop asserting store-owned ownership sync; assert pure serialization only.
- Modify: `app/src/test/java/com/nexio/tv/data/local/TraktLibrarySnapshotStoreTest.kt`
  Responsibility: stop asserting store-owned ownership sync; assert pure serialization only.
- Modify: `app/src/test/java/com/nexio/tv/data/local/SimklLibrarySnapshotStoreTest.kt`
  Responsibility: stop asserting store-owned ownership sync; assert pure serialization only.
- Modify: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceProfileBoundaryTest.kt`
  Responsibility: prove the rail graph is updated before persisted snapshot publication.
- Modify: `app/src/test/java/com/nexio/tv/architecture/NoBlockingRailOwnershipSyncTest.kt`
  Responsibility: ban `runBlocking` in snapshot stores and ban store-owned rail sync authority.
- Modify: `app/src/test/java/com/nexio/tv/core/integration/IntegrationHydrationCoordinatorTest.kt`
  Responsibility: assert the coordinator returns planned rails without side-effecting the old generic notifier path.
- Modify: `docs/architecture/api-integration-runtime.md`
  Responsibility: document `IntegrationCacheOwnership`, blob-aware orphan cleanup, targeted home-rail hydration, and caller-owned rail sync ordering.

### Existing files to inspect but not change unless blocked

- Inspect: `app/src/main/java/com/nexio/tv/core/integration/RailMediaIdentityResolver.kt`
  Reason: reuse the canonical identity rules already introduced in the previous pass.
- Inspect: `app/src/main/java/com/nexio/tv/data/repository/KitsuDiscoveryService.kt`
  Reason: confirm targeted catalog refresh already exists for Kitsu home rails.
- Inspect: `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt`
  Reason: confirm targeted catalog refresh already exists for TMDB home rails.
- Inspect: `app/src/main/java/com/nexio/tv/openspec/changes/establish-unified-integration-runtime/specs/integration-rail-cache-lifecycle/spec.md`
  Reason: keep every task aligned with the four OpenSpec requirements that this plan closes.

---

### Task 1: Add Explicit Runtime Cache Ownership Metadata

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheOwnership.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationSpec.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/integration/IntegrationRuntimeTestFixtures.kt`
- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationCacheOwnershipTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/integration/DefaultIntegrationRuntimeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.integration

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationCacheOwnershipTest {
    @Test
    fun `cache first runtime writes persist canonical media ownership`() = runTest {
        val fixture = realRuntimeFixture()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            cacheKey = "tmdb:movie:550:details",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 60_000L,
                staleAfterExpiryMs = 60_000L
            ),
            ownership = IntegrationCacheOwnership.Media("movie:imdb:tt0137523"),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = { IntegrationLoadResult.Success("payload") }
        )

        fixture.runtime.get(spec)

        assertEquals(
            "movie:imdb:tt0137523",
            fixture.cacheDao.getCacheEntry("tmdb:movie:550:details")?.ownerToken
        )
    }

    @Test
    fun `unowned cache rows keep null owner token`() = runTest {
        val fixture = realRuntimeFixture()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.MDBLIST,
            cacheKey = "mdblist:validate:123",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 60_000L,
                staleAfterExpiryMs = 60_000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = { IntegrationLoadResult.Success("ok") }
        )

        fixture.runtime.get(spec)

        assertNull(fixture.cacheDao.getCacheEntry("mdblist:validate:123")?.ownerToken)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationCacheOwnershipTest"
```

Expected: FAIL because `IntegrationSpec` has no `ownership` property and `LocalIntegrationCacheStore` always persists `ownerToken = null`.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
package com.nexio.tv.core.integration

sealed interface IntegrationCacheOwnership {
    data object None : IntegrationCacheOwnership
    data class Media(val mediaKey: String) : IntegrationCacheOwnership
}
```

```kotlin
data class IntegrationSpec<T>(
    val provider: IntegrationProvider,
    val cacheKey: String,
    val codec: IntegrationCodec<T>,
    val cachePolicy: IntegrationCachePolicy = IntegrationCachePolicy.Disabled,
    val ownership: IntegrationCacheOwnership = IntegrationCacheOwnership.None,
    val workClass: IntegrationWorkClass,
    val scope: IntegrationScope = IntegrationScope.Global,
    val load: suspend () -> IntegrationLoadResult<T>
)
```

```kotlin
override suspend fun <T> write(spec: IntegrationSpec<T>, value: T) {
    val policy = spec.cachePolicy as? IntegrationCachePolicy.CacheFirst ?: return
    val now = nowMsProvider()
    val freshUntil = now + policy.ttlMs
    val staleUntil = freshUntil + policy.staleAfterExpiryMs
    val blobPath = spec.cacheKey.replace(':', '/') + ".bin"
    val ownerToken = when (val ownership = spec.ownership) {
        IntegrationCacheOwnership.None -> null
        is IntegrationCacheOwnership.Media -> ownership.mediaKey
    }

    blobStore.fileFor(blobPath).writeBytes(spec.codec.encode(value))
    cacheDao.upsertCacheEntry(
        IntegrationCacheEntity(
            cacheKey = spec.cacheKey,
            provider = spec.provider.name,
            scopeKey = spec.scope.storageKey,
            blobPath = blobPath,
            mimeType = spec.codec.mimeType,
            expiresAtEpochMs = freshUntil,
            staleUntilEpochMs = staleUntil,
            updatedAtEpochMs = now,
            ownerToken = ownerToken
        )
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationCacheOwnershipTest" --tests "com.nexio.tv.core.integration.DefaultIntegrationRuntimeTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheOwnership.kt \
  app/src/main/java/com/nexio/tv/core/integration/IntegrationSpec.kt \
  app/src/main/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStore.kt \
  app/src/test/java/com/nexio/tv/core/integration/IntegrationCacheOwnershipTest.kt \
  app/src/test/java/com/nexio/tv/core/integration/IntegrationRuntimeTestFixtures.kt \
  app/src/test/java/com/nexio/tv/core/integration/DefaultIntegrationRuntimeTest.kt
git commit -m "feat: add explicit runtime cache ownership"
```

### Task 2: Make Orphan Cleanup Delete Real Cache Rows And Blob Files

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDao.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationBlobStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationOrphanCleanupService.kt`
- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationOrphanCleanupServiceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/integration/IntegrationOwnershipServiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.IntegrationCacheEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationOrphanCleanupServiceTest {
    @Test
    fun `cleanup removes owned cache row and blob when final owner disappears`() = runTest {
        val db = inMemoryIntegrationCacheDatabase()
        val blobStore = tempIntegrationBlobStore()
        val cacheStore = LocalIntegrationCacheStore(db.cacheDao(), blobStore)
        val cleanup = IntegrationOrphanCleanupService(
            railStoreDao = db.railStoreDao(),
            cacheStore = cacheStore
        )
        val blob = blobStore.fileFor("tmdb/movie-550.bin").apply { writeText("payload") }

        db.cacheDao().upsertCacheEntry(
            IntegrationCacheEntity(
                cacheKey = "tmdb:movie:550:details",
                provider = "TMDB",
                scopeKey = "global",
                blobPath = "tmdb/movie-550.bin",
                mimeType = "application/json",
                expiresAtEpochMs = 10_000L,
                staleUntilEpochMs = 20_000L,
                updatedAtEpochMs = 5_000L,
                ownerToken = "movie:imdb:tt0137523"
            )
        )

        val deleted = cleanup.cleanupIfOrphaned("movie:imdb:tt0137523")

        assertTrue(deleted)
        assertTrue(db.cacheDao().findByMediaKey("movie:imdb:tt0137523").isEmpty())
        assertFalse(blob.exists())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationOrphanCleanupServiceTest"
```

Expected: FAIL because `IntegrationOrphanCleanupService` still takes `IntegrationCacheDao`, has no blob deletion path, and `IntegrationCacheStore` exposes no owner-aware delete method.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
interface IntegrationCacheStore {
    suspend fun <T> readFresh(spec: IntegrationSpec<T>): T?
    suspend fun <T> readStale(spec: IntegrationSpec<T>): T?
    suspend fun <T> write(spec: IntegrationSpec<T>, value: T)
    suspend fun deleteOwnedMedia(mediaKey: String): Int
}
```

```kotlin
@Query("SELECT * FROM integration_cache WHERE ownerToken = :mediaKey")
suspend fun findByMediaKey(mediaKey: String): List<IntegrationCacheEntity>

@Query("DELETE FROM integration_cache WHERE ownerToken = :mediaKey")
suspend fun deleteByMediaKey(mediaKey: String): Int
```

```kotlin
fun delete(path: String) {
    val file = File(root, path)
    if (file.exists()) file.delete()
    file.parentFile?.let { parent ->
        if (parent != root && parent.exists() && parent.list().isNullOrEmpty()) {
            parent.delete()
        }
    }
}
```

```kotlin
override suspend fun deleteOwnedMedia(mediaKey: String): Int {
    val ownedEntries = cacheDao.findByMediaKey(mediaKey)
    ownedEntries.forEach { entry -> blobStore.delete(entry.blobPath) }
    return cacheDao.deleteByMediaKey(mediaKey)
}
```

```kotlin
@Singleton
class IntegrationOrphanCleanupService @Inject constructor(
    private val railStoreDao: RailStoreDao,
    private val cacheStore: IntegrationCacheStore
) {
    suspend fun cleanupIfOrphaned(mediaKey: String): Boolean {
        val remainingOwners = railStoreDao.railsForMedia(mediaKey)
        if (remainingOwners.isNotEmpty()) return false
        return cacheStore.deleteOwnedMedia(mediaKey) > 0
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationOrphanCleanupServiceTest" --tests "com.nexio.tv.core.integration.IntegrationOwnershipServiceTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheStore.kt \
  app/src/main/java/com/nexio/tv/core/integration/IntegrationOrphanCleanupService.kt \
  app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDao.kt \
  app/src/main/java/com/nexio/tv/data/local/integration/IntegrationBlobStore.kt \
  app/src/main/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStore.kt \
  app/src/test/java/com/nexio/tv/core/integration/IntegrationOrphanCleanupServiceTest.kt \
  app/src/test/java/com/nexio/tv/core/integration/IntegrationOwnershipServiceTest.kt
git commit -m "fix: delete owned cache blobs during orphan cleanup"
```

### Task 3: Mark Rail-Backed Runtime Specs With Canonical Media Ownership

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheOwnershipFactory.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/mdblist/MDBListIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/omdb/OmdbIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/RpdbIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/integration/IntegrationRuntimeTestFixtures.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/integration/TmdbRuntimeRoutingTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/MDBListRuntimeRoutingTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
class TmdbRuntimeRoutingTest {
    @Test
    fun `tmdb enrichment spec carries canonical media ownership`() = runTest {
        val runtime = RecordingIntegrationRuntime(successValue = expectedEnrichment())
        val service = tmdbService(runtime = runtime)

        service.fetchEnrichment("550", ContentType.MOVIE, "en-US")

        val ownership = runtime.specs.single().ownership
        assertEquals(
            IntegrationCacheOwnership.Media("movie:tmdb:550"),
            ownership
        )
    }
}

class MDBListRuntimeRoutingTest {
    @Test
    fun `mdblist ratings spec carries imdb ownership`() = runTest {
        val runtime = RecordingIntegrationRuntime(
            successValue = MDBListRatingsResult(
                ratings = MDBListRatings(imdb = 8.8),
                hasImdbRating = true
            )
        )
        val provider = MDBListIntegrationProvider(runtime, mockk(), IntegrationCacheOwnershipFactory(RailMediaIdentityResolver()))

        provider.fetchRatings("tt0137523", "movie", "mdb-key", listOf("imdb"))

        assertEquals(
            IntegrationCacheOwnership.Media("movie:imdb:tt0137523"),
            runtime.specs.single().ownership
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.TmdbRuntimeRoutingTest" --tests "com.nexio.tv.data.repository.MDBListRuntimeRoutingTest" --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"
```

Expected: FAIL because `RecordingIntegrationRuntime` does not record specs yet and the providers/services do not set `ownership`.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
@Singleton
class IntegrationCacheOwnershipFactory @Inject constructor(
    private val identityResolver: RailMediaIdentityResolver
) {
    fun media(
        mediaType: String,
        rawId: String,
        title: String? = null,
        year: Int? = null,
        imdbId: String? = null,
        tmdbId: String? = null,
        traktId: String? = null
    ): IntegrationCacheOwnership {
        val resolved = identityResolver.fromRawContent(
            mediaType = mediaType,
            rawId = rawId,
            title = title,
            year = year,
            imdbId = imdbId,
            tmdbId = tmdbId,
            traktId = traktId
        )
        return IntegrationCacheOwnership.Media(resolved.mediaIdentity.mediaKey)
    }
}
```

```kotlin
class RecordingIntegrationRuntime<T>(
    private val successValue: T? = null,
    private val nextResult: IntegrationFetchResult<T>? = null
) : IntegrationRuntime {
    val keys = mutableListOf<String>()
    val specs = mutableListOf<IntegrationSpec<*>>()

    override suspend fun <R> get(
        spec: IntegrationSpec<R>,
        options: IntegrationFetchOptions
    ): IntegrationFetchResult<R> {
        keys += spec.cacheKey
        specs += spec
        @Suppress("UNCHECKED_CAST")
        return nextResult as? IntegrationFetchResult<R>
            ?: successValue?.let { IntegrationFetchResult.Updated(it as R) }
            ?: IntegrationFetchResult.Missing
    }
}
```

```kotlin
val spec = IntegrationSpec(
    provider = IntegrationProvider.MDBLIST,
    cacheKey = "mdblist:$mediaType:$imdbId:$providerHash:${apiKey.hashCode()}",
    codec = gsonCodec<MDBListRatingsResult>(),
    cachePolicy = IntegrationCachePolicy.CacheFirst(
        ttlMs = 30L * 60L * 1000L,
        staleAfterExpiryMs = 30L * 60L * 1000L
    ),
    ownership = ownershipFactory.media(
        mediaType = mediaType,
        rawId = imdbId,
        imdbId = imdbId
    ),
    workClass = IntegrationWorkClass.USER_VISIBLE,
    load = { ... }
)
```

```kotlin
val spec = IntegrationSpec(
    provider = IntegrationProvider.TMDB,
    cacheKey = cacheKey,
    codec = gsonCodec<TmdbEnrichment>(),
    cachePolicy = IntegrationCachePolicy.CacheFirst(
        ttlMs = 24L * 60L * 60L * 1000L,
        staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
    ),
    ownership = ownershipFactory.media(
        mediaType = contentType.toApiString(),
        rawId = when (contentType) {
            ContentType.MOVIE -> "tmdb:$tmdbId"
            else -> "tmdb:$tmdbId"
        },
        tmdbId = tmdbId
    ),
    workClass = IntegrationWorkClass.USER_VISIBLE,
    load = { ... }
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.TmdbRuntimeRoutingTest" --tests "com.nexio.tv.data.repository.MDBListRuntimeRoutingTest" --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheOwnershipFactory.kt \
  app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt \
  app/src/main/java/com/nexio/tv/data/integration/mdblist/MDBListIntegrationProvider.kt \
  app/src/main/java/com/nexio/tv/data/integration/omdb/OmdbIntegrationProvider.kt \
  app/src/main/java/com/nexio/tv/data/integration/posters/RpdbIntegrationProvider.kt \
  app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt \
  app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt \
  app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt \
  app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt \
  app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt \
  app/src/test/java/com/nexio/tv/core/integration/IntegrationRuntimeTestFixtures.kt \
  app/src/test/java/com/nexio/tv/core/integration/TmdbRuntimeRoutingTest.kt \
  app/src/test/java/com/nexio/tv/data/repository/MDBListRuntimeRoutingTest.kt \
  app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt
git commit -m "feat: attach media ownership to rail-backed runtime caches"
```

### Task 4: Replace Generic Priority Hydration With Targeted Home-Rail Execution

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationHydrationCoordinator.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeRailHydrationExecutor.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPriorityHydrationPipeline.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailHydrationExecutorTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/integration/IntegrationHydrationCoordinatorTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomePlaybackWorkGateTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
class HomeRailHydrationExecutorTest {
    @Test
    fun `executor refreshes only providers represented by planned home rails`() = runTest {
        val tmdbDiscovery = mockk<TmdbDiscoveryService>(relaxed = true)
        val kitsuDiscovery = mockk<KitsuDiscoveryService>(relaxed = true)
        val traktDiscovery = mockk<TraktDiscoveryService>(relaxed = true)
        val executor = HomeRailHydrationExecutor(
            tmdbDiscoveryService = tmdbDiscovery,
            kitsuDiscoveryService = kitsuDiscovery,
            traktDiscoveryService = traktDiscovery,
            simklDiscoveryService = mockk(relaxed = true),
            mdbListDiscoveryService = mockk(relaxed = true),
            continueWatchingSnapshotService = mockk(relaxed = true),
            profileManager = profileManager(activeProfileId = 7)
        )

        executor.hydrate(
            listOf(
                RailCacheEntity(
                    railKey = "profile:7:home:catalog:tmdb:popular_movies",
                    provider = "TMDB",
                    kind = "MOVIE",
                    paramsHash = "tmdb",
                    fetchedAtEpochMs = 0L,
                    expiresAtEpochMs = 0L,
                    staleUntilEpochMs = 0L
                ),
                RailCacheEntity(
                    railKey = "profile:7:home:catalog:kitsu:trending_anime",
                    provider = "KITSU",
                    kind = "SERIES",
                    paramsHash = "kitsu",
                    fetchedAtEpochMs = 0L,
                    expiresAtEpochMs = 0L,
                    staleUntilEpochMs = 0L
                )
            )
        )

        coVerify(exactly = 1) {
            tmdbDiscovery.refreshCatalogs(any(), force = true, catalogIds = setOf("tmdb:popular_movies"))
        }
        coVerify(exactly = 1) {
            kitsuDiscovery.refreshCatalogs(any(), force = true, catalogIds = setOf("kitsu:trending_anime"))
        }
        coVerify(exactly = 0) { traktDiscovery.priorityFetch() }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeRailHydrationExecutorTest" --tests "com.nexio.tv.core.integration.IntegrationHydrationCoordinatorTest"
```

Expected: FAIL because `IntegrationHydrationCoordinator` still side-effects `CatalogPriorityHydrationNotifier` and there is no targeted executor.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
interface IntegrationHydrationCoordinator {
    suspend fun planNextBatch(limit: Int): List<RailCacheEntity>
}

@Singleton
class DefaultIntegrationHydrationCoordinator @Inject constructor(
    private val planner: IntegrationHydrationPlanner
) : IntegrationHydrationCoordinator {
    override suspend fun planNextBatch(limit: Int): List<RailCacheEntity> =
        if (limit <= 0) emptyList() else planner.planNextBatch(limit)
}
```

```kotlin
@Singleton
class HomeRailHydrationExecutor @Inject constructor(
    private val tmdbDiscoveryService: TmdbDiscoveryService,
    private val kitsuDiscoveryService: KitsuDiscoveryService,
    private val traktDiscoveryService: TraktDiscoveryService,
    private val simklDiscoveryService: SimklDiscoveryService,
    private val mdbListDiscoveryService: MDBListDiscoveryService,
    private val continueWatchingSnapshotService: ContinueWatchingSnapshotService,
    private val profileManager: ProfileManager
) {
    suspend fun hydrate(rails: List<RailCacheEntity>) {
        if (rails.isEmpty()) return
        val profileId = profileManager.activeProfileId.value
        val homeCatalogIds = rails.mapNotNull(::homeCatalogIdOrNull)
        val tmdbCatalogIds = homeCatalogIds.filter { it.startsWith("tmdb:") }.toSet()
        val kitsuCatalogIds = homeCatalogIds.filter { it.startsWith("kitsu:") }.toSet()
        val wantsTrakt = homeCatalogIds.any { it.startsWith("trakt:") }
        val wantsSimkl = homeCatalogIds.any { it.startsWith("simkl:") }
        val wantsMDBList = homeCatalogIds.any { it.startsWith("mdblist:") }
        val wantsContinueWatching = rails.any { it.railKey == RailKeyFactory.continueWatching(profileId) }

        if (tmdbCatalogIds.isNotEmpty()) {
            tmdbDiscoveryService.refreshCatalogs(
                preferences = tmdbDiscoveryService.observeSnapshot().first().toPreferencesFallback(),
                force = true,
                catalogIds = tmdbCatalogIds
            )
        }
        if (kitsuCatalogIds.isNotEmpty()) {
            kitsuDiscoveryService.refreshCatalogs(
                preferences = kitsuCatalogPreferences(),
                force = true,
                catalogIds = kitsuCatalogIds
            )
        }
        if (wantsTrakt) traktDiscoveryService.ensureFresh(force = true, profileId = profileId)
        if (wantsSimkl) simklDiscoveryService.ensureFresh(force = true, profileId = profileId)
        if (wantsMDBList) mdbListDiscoveryService.ensureFresh(force = true, profileId = profileId)
        if (wantsContinueWatching) continueWatchingSnapshotService.ensureFresh(force = true)
    }
}
```

```kotlin
collectLatest { activeRails ->
    activeRailTracker.replaceActiveRails(activeRails)
    withContext(Dispatchers.IO) {
        val planned = integrationHydrationCoordinator.planNextBatch(
            limit = activeRails.size.coerceAtLeast(1)
        )
        homeRailHydrationExecutor.hydrate(planned)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeRailHydrationExecutorTest" --tests "com.nexio.tv.core.integration.IntegrationHydrationCoordinatorTest" --tests "com.nexio.tv.ui.screens.home.HomePlaybackWorkGateTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/core/integration/IntegrationHydrationCoordinator.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeRailHydrationExecutor.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPriorityHydrationPipeline.kt \
  app/src/test/java/com/nexio/tv/ui/screens/home/HomeRailHydrationExecutorTest.kt \
  app/src/test/java/com/nexio/tv/core/integration/IntegrationHydrationCoordinatorTest.kt \
  app/src/test/java/com/nexio/tv/ui/screens/home/HomePlaybackWorkGateTest.kt
git commit -m "fix: target active rail hydration by planned rail keys"
```

### Task 5: Make Rail Sync Authoritative And Awaited

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModel.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/TraktLibrarySnapshotStoreTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/SimklLibrarySnapshotStoreTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceProfileBoundaryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/NoBlockingRailOwnershipSyncTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
class ContinueWatchingSnapshotServiceProfileBoundaryTest {
    @Test
    fun `continue watching syncs rail ownership before persisting snapshot`() = runTest {
        val callOrder = mutableListOf<String>()
        val snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
            every { write(any(), profileId = any()) } answers { callOrder += "snapshot_write" }
        }
        val ownershipService = mockk<IntegrationOwnershipService>(relaxed = true) {
            coEvery { upsertRailMembership(any()) } answers { callOrder += "rail_sync" }
        }

        val service = continueWatchingService(
            snapshotStore = snapshotStore,
            ownershipService = ownershipService,
            activeProfileId = 7,
            progress = listOf(sampleProgress("series:tt0944947"))
        )

        service.ensureFresh(force = true)

        assertEquals(listOf("rail_sync", "snapshot_write"), callOrder.takeLast(2))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceProfileBoundaryTest" --tests "com.nexio.tv.architecture.NoBlockingRailOwnershipSyncTest"
```

Expected: FAIL because home/Trakt/Simkl snapshot stores still own rail sync and `ContinueWatchingSnapshotService` writes the snapshot before syncing the rail.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
class HomeCatalogSnapshotStore(
    @ApplicationContext private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val activeProfileId: () -> Int
) {
    fun write(snapshot: Snapshot, posterProviderToken: String, profileId: Int = activeProfileId()) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(snapshotKey(profileId), gson.toJson(payload)).commit()
    }
}
```

```kotlin
val memberships = homeCatalogSnapshotStore.buildRailMemberships(
    snapshot = latestSnapshot,
    posterProviderToken = posterToken,
    profileId = profileId
)
ownershipService.syncRails(
    RailKeyFactory.homeCatalogNamespace(profileId),
    memberships
)
homeCatalogSnapshotStore.write(latestSnapshot, posterToken, profileId = profileId)
```

```kotlin
private suspend fun persistRawSnapshot(
    snapshot: ContinueWatchingSnapshot,
    profileId: Int = activeProfileId()
): Boolean {
    val normalized = sanitizeSnapshot(snapshot)
    val hydrated = hydrateSnapshotMetadata(
        snapshot = normalized,
        fallbackMetadata = rawSnapshotState.value.snapshot.displayMetadataByItemKey
    )

    syncContinueWatchingRail(hydrated, profileId)
    snapshotStore.write(hydrated, profileId = profileId)
    activeRailTracker.markActive(RailKeyFactory.continueWatching(profileId))
    ...
}
```

```kotlin
class NoBlockingRailOwnershipSyncTest {
    @Test
    fun `snapshot stores no longer own rail sync authority`() {
        val offenders = sourceTextScan(
            forbiddenPatterns = listOf("IntegrationOwnershipService", "queueOwnershipSync", "runBlocking"),
            allowedPaths = listOf(
                "app/src/test/"
            )
        ).filter {
            it.contains("HomeCatalogSnapshotStore.kt") ||
                it.contains("TraktLibrarySnapshotStore.kt") ||
                it.contains("SimklLibrarySnapshotStore.kt")
        }

        assertTrue("Snapshot stores still own rail sync: $offenders", offenders.isEmpty())
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest" --tests "com.nexio.tv.data.local.TraktLibrarySnapshotStoreTest" --tests "com.nexio.tv.data.local.SimklLibrarySnapshotStoreTest" --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceProfileBoundaryTest" --tests "com.nexio.tv.architecture.NoBlockingRailOwnershipSyncTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt \
  app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt \
  app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt \
  app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt \
  app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt \
  app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
  app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModel.kt \
  app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt \
  app/src/test/java/com/nexio/tv/data/local/TraktLibrarySnapshotStoreTest.kt \
  app/src/test/java/com/nexio/tv/data/local/SimklLibrarySnapshotStoreTest.kt \
  app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceProfileBoundaryTest.kt \
  app/src/test/java/com/nexio/tv/architecture/NoBlockingRailOwnershipSyncTest.kt
git commit -m "fix: make rail sync authoritative over snapshot persistence"
```

### Task 6: Update Docs And Run Final Verification

**Files:**
- Modify: `docs/architecture/api-integration-runtime.md`
- Inspect: `openspec/changes/establish-unified-integration-runtime/specs/integration-rail-cache-lifecycle/spec.md`

- [ ] **Step 1: Update the architecture doc**

```markdown
Phase F rail lifecycle:

1. `IntegrationSpec` may declare `IntegrationCacheOwnership.Media(mediaKey)` for title-backed runtime cache rows.
2. `LocalIntegrationCacheStore` persists `ownerToken = mediaKey` for owned cache rows.
3. Orphan cleanup deletes both owned cache rows and their blob files after the final owning rail disappears.
4. Snapshot stores are pure serializers; repositories and services own rail sync ordering.
5. `IntegrationHydrationPlanner` output is consumed by `HomeRailHydrationExecutor`, which refreshes only the providers and catalog ids represented by the planned stale rails.
```

- [ ] **Step 2: Run the focused verification bundle**

Run:

```bash
./gradlew :app:compileArm64DebugKotlin :app:compileArm64DebugUnitTestKotlin
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.core.integration.IntegrationCacheOwnershipTest" \
  --tests "com.nexio.tv.core.integration.IntegrationOrphanCleanupServiceTest" \
  --tests "com.nexio.tv.core.integration.IntegrationOwnershipServiceTest" \
  --tests "com.nexio.tv.core.integration.IntegrationHydrationPlannerTest" \
  --tests "com.nexio.tv.core.integration.IntegrationHydrationCoordinatorTest" \
  --tests "com.nexio.tv.core.integration.TmdbRuntimeRoutingTest" \
  --tests "com.nexio.tv.data.repository.MDBListRuntimeRoutingTest" \
  --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest" \
  --tests "com.nexio.tv.data.local.HomeCatalogSnapshotStoreTest" \
  --tests "com.nexio.tv.data.local.TraktLibrarySnapshotStoreTest" \
  --tests "com.nexio.tv.data.local.SimklLibrarySnapshotStoreTest" \
  --tests "com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceProfileBoundaryTest" \
  --tests "com.nexio.tv.ui.screens.home.HomeRailHydrationExecutorTest" \
  --tests "com.nexio.tv.architecture.NoBlockingRailOwnershipSyncTest" \
  --tests "com.nexio.tv.architecture.RailOwnershipLifecycleTest"
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add \
  docs/architecture/api-integration-runtime.md \
  app/src/main/java/com/nexio/tv/core/integration \
  app/src/main/java/com/nexio/tv/data/local/integration \
  app/src/main/java/com/nexio/tv/data/integration \
  app/src/main/java/com/nexio/tv/data/repository \
  app/src/main/java/com/nexio/tv/ui/screens/home \
  app/src/test/java/com/nexio/tv/core/integration \
  app/src/test/java/com/nexio/tv/data/local \
  app/src/test/java/com/nexio/tv/data/repository \
  app/src/test/java/com/nexio/tv/ui/screens/home \
  app/src/test/java/com/nexio/tv/architecture
git commit -m "fix: complete rail cache lifecycle ownership and hydration"
```

## Self-Review

1. **Spec coverage:**  
   `Rails and media identities define cache ownership roots` is covered by Tasks 1 and 3.  
   `Orphan cleanup respects multi-rail ownership` is covered by Tasks 2 and 5.  
   `Active rail hydration prioritizes active stale rails` is covered by Task 4.  
   `Legacy snapshot ownership paths are retired after rail ownership lands` remains guarded by Task 5 and the existing `RailOwnershipLifecycleTest`.

2. **Placeholder scan:**  
   No `TODO`, `TBD`, “add appropriate”, or “similar to Task N” placeholders remain.

3. **Type consistency:**  
   `IntegrationCacheOwnership.Media` is introduced in Task 1 and reused consistently in Tasks 2 and 3.  
   `planNextBatch(limit)` remains the coordinator/planner entrypoint in Task 4.  
   Snapshot stores are pure serializers by Task 5, and the call sites become the only ownership writers.
