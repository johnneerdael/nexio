# Rail Ownership Spec Compliance Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the four remaining OpenSpec violations in the rail-ownership lifecycle by separating rail namespaces, scoping ownership to profiles, canonicalizing shared media identities, wiring the hydration planner into real refresh behavior, and removing the last legacy snapshot-ownership path.

**Architecture:** Introduce two small core primitives, `RailKeyFactory` and `RailMediaIdentityResolver`, and make every rail writer use them. Move ownership sync out of blocking snapshot-store calls into suspend IO paths, then add a thin hydration coordinator so `IntegrationHydrationPlanner` becomes a live part of runtime behavior rather than a dead utility. Keep the implementation incremental: fix namespace/profile correctness first, then identity correctness, then planner usage, then delete the legacy `home_ref::` path.

**Tech Stack:** Kotlin, Coroutines, Hilt, Room, Robolectric, JUnit, MockK

---

## Scope Check

This fix stays within one subsystem: `integration-rail-cache-lifecycle`. Do not split it into separate plans. All four findings interact with the same ownership graph and should land together so the codebase does not spend time in another half-old/half-new state.

## File Structure

### New files

- Create: `app/src/main/java/com/nexio/tv/core/integration/RailKeyFactory.kt`
  Responsibility: build profile-scoped, collision-free rail keys and namespaces for home catalog rails, continue-watching, Trakt library rails, and Simkl library rails.
- Create: `app/src/main/java/com/nexio/tv/core/integration/RailMediaIdentityResolver.kt`
  Responsibility: produce canonical `mediaKey` values plus `ExternalIdEntity` rows from `MetaPreview`, `LibraryEntry`, and `WatchProgress`-derived data.
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationHydrationCoordinator.kt`
  Responsibility: consume `IntegrationHydrationPlanner` output and trigger real refresh entry points for planned stale rails.
- Create: `app/src/test/java/com/nexio/tv/core/integration/RailKeyFactoryTest.kt`
  Responsibility: lock in namespace separation and profile scoping.
- Create: `app/src/test/java/com/nexio/tv/core/integration/RailMediaIdentityResolverTest.kt`
  Responsibility: lock in canonical identity selection and external-id persistence shape.
- Create: `app/src/test/java/com/nexio/tv/core/integration/IntegrationHydrationCoordinatorTest.kt`
  Responsibility: prove the planner output is consumed by real refresh decisions.
- Create: `app/src/test/java/com/nexio/tv/architecture/NoBlockingRailOwnershipSyncTest.kt`
  Responsibility: fail if snapshot stores reintroduce `runBlocking`-based ownership writes.

### Modified files

- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationOwnershipService.kt`
  Responsibility: sync exact namespaces, keep multi-rail ownership correct, and accept canonical identities plus external IDs.
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationHydrationPlanner.kt`
  Responsibility: keep planner behavior, but pair cleanly with the new coordinator.
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
  Responsibility: emit profile-scoped home catalog rails and canonical media identities through suspend ownership sync.
- Modify: `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt`
  Responsibility: emit profile-scoped Trakt library rails and canonical media identities through suspend ownership sync.
- Modify: `app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt`
  Responsibility: emit profile-scoped Simkl library rails and canonical media identities through suspend ownership sync.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
  Responsibility: emit a profile-scoped continue-watching rail, not a `home:` sibling, and persist canonical media identities.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
  Responsibility: update active rails and invoke the real hydration consumer.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
  Responsibility: stop invoking the deleted legacy ownership helpers and use the new hydration reason path.
- Modify: `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
  Responsibility: delete the legacy `home_ref::` ownership helpers entirely.
- Modify: `app/src/test/java/com/nexio/tv/core/integration/IntegrationOwnershipServiceTest.kt`
  Responsibility: add namespace/profile-isolation scenarios.
- Modify: `app/src/test/java/com/nexio/tv/core/integration/IntegrationHydrationPlannerTest.kt`
  Responsibility: keep existing planner coverage and add any rail-key factory integration if needed.
- Modify: `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt`
  Responsibility: remove obsolete ownership-helper tests and replace them with “metadata still readable without legacy ownership refs”.
- Modify: `app/src/test/java/com/nexio/tv/architecture/RailOwnershipLifecycleTest.kt`
  Responsibility: enforce deletion of the legacy ownership path.
- Modify: `docs/architecture/api-integration-runtime.md`
  Responsibility: document profile scoping, canonical identity rules, and live planner consumption.

### Existing files to inspect but not change unless blocked

- Inspect: `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
  Reason: `homeDisplayItemKey` is the current fallback identity shape.
- Inspect: `app/src/main/java/com/nexio/tv/domain/model/LibraryModels.kt`
  Reason: `LibraryEntry` carries the raw external IDs (`imdbId`, `tmdbId`, `traktId`) needed by the new resolver.
- Inspect: `app/src/main/java/com/nexio/tv/domain/model/WatchProgress.kt`
  Reason: continue-watching identity derivation depends on these fields.
- Inspect: `app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt`
  Reason: `TrackingNextUpEntry` shapes the continue-watching metadata bridge.

---

### Task 1: Add Rail Key And Media Identity Primitives

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/integration/RailKeyFactory.kt`
- Create: `app/src/main/java/com/nexio/tv/core/integration/RailMediaIdentityResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/integration/RailKeyFactoryTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/integration/RailMediaIdentityResolverTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.core.integration

import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RailKeyFactoryTest {
    @Test
    fun `rail keys are profile scoped and continue watching is not in the catalog namespace`() {
        assertEquals(
            "profile:7:home:catalog:tmdb:popular:movies",
            RailKeyFactory.homeCatalog(profileId = 7, catalogId = "tmdb:popular:movies")
        )
        assertEquals(
            "profile:7:home:continue_watching",
            RailKeyFactory.continueWatching(profileId = 7)
        )
        assertEquals(
            "profile:7:home:catalog:",
            RailKeyFactory.homeCatalogNamespace(profileId = 7)
        )
        assertTrue(
            RailKeyFactory.continueWatching(profileId = 7)
                .startsWith("profile:7:home:continue_watching")
        )
    }
}

class RailMediaIdentityResolverTest {
    @Test
    fun `library entries prefer imdb as canonical key and persist secondary ids`() {
        val resolver = RailMediaIdentityResolver()
        val entry = LibraryEntry(
            id = "trakt:42",
            type = "movie",
            name = "Fight Club",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "1999",
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            imdbId = "tt0137523",
            tmdbId = 550,
            traktId = 42
        )

        val resolved = resolver.fromLibraryEntry(entry)

        assertEquals("movie:imdb:tt0137523", resolved.mediaIdentity.mediaKey)
        assertEquals(3, resolved.externalIds.size)
        assertTrue(resolved.externalIds.any { it.provider == "IMDB" && it.externalId == "tt0137523" })
        assertTrue(resolved.externalIds.any { it.provider == "TMDB" && it.externalId == "550" })
        assertTrue(resolved.externalIds.any { it.provider == "TRAKT" && it.externalId == "42" })
    }

    @Test
    fun `continue watching falls back to parsed imdb id before raw content id`() {
        val resolver = RailMediaIdentityResolver()
        val progress = WatchProgress(
            contentId = "series:tt0944947",
            contentType = "series",
            name = "Game of Thrones",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "series:tt0944947:1:1",
            season = 1,
            episode = 1,
            episodeTitle = "Winter Is Coming",
            position = 10L,
            duration = 100L,
            lastWatched = 1_000L
        )

        val resolved = resolver.fromWatchProgress(progress, title = "Game of Thrones", year = 2011)

        assertEquals("series:imdb:tt0944947", resolved.mediaIdentity.mediaKey)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.RailKeyFactoryTest" --tests "com.nexio.tv.core.integration.RailMediaIdentityResolverTest"
```

Expected: FAIL with unresolved `RailKeyFactory` / `RailMediaIdentityResolver`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.ExternalIdEntity
import com.nexio.tv.data.local.integration.MediaIdentityEntity
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.WatchProgress

object RailKeyFactory {
    fun homeCatalog(profileId: Int, catalogId: String): String =
        "profile:$profileId:home:catalog:${catalogId.trim()}"

    fun homeCatalogNamespace(profileId: Int): String =
        "profile:$profileId:home:catalog:"

    fun continueWatching(profileId: Int): String =
        "profile:$profileId:home:continue_watching"

    fun traktLibrary(profileId: Int, listKey: String): String =
        "profile:$profileId:library:trakt:${listKey.trim()}"

    fun traktLibraryNamespace(profileId: Int): String =
        "profile:$profileId:library:trakt:"

    fun simklLibrary(profileId: Int, listKey: String): String =
        "profile:$profileId:library:simkl:${listKey.trim()}"

    fun simklLibraryNamespace(profileId: Int): String =
        "profile:$profileId:library:simkl:"
}

data class ResolvedRailMediaIdentity(
    val mediaIdentity: MediaIdentityEntity,
    val externalIds: List<ExternalIdEntity>
)

class RailMediaIdentityResolver {
    fun fromPreview(preview: MetaPreview): ResolvedRailMediaIdentity {
        return resolve(
            mediaType = preview.apiType,
            rawId = preview.id,
            title = preview.name,
            year = preview.releaseInfo?.take(4)?.toIntOrNull(),
            imdbId = extractImdbId(preview.id),
            tmdbId = extractProviderId(preview.id, "tmdb"),
            traktId = extractProviderId(preview.id, "trakt")
        )
    }

    fun fromLibraryEntry(entry: LibraryEntry): ResolvedRailMediaIdentity {
        return resolve(
            mediaType = entry.type,
            rawId = entry.id,
            title = entry.name,
            year = entry.releaseInfo?.take(4)?.toIntOrNull(),
            imdbId = entry.imdbId ?: extractImdbId(entry.id),
            tmdbId = entry.tmdbId?.toString() ?: extractProviderId(entry.id, "tmdb"),
            traktId = entry.traktId?.toString() ?: extractProviderId(entry.id, "trakt")
        )
    }

    fun fromWatchProgress(progress: WatchProgress, title: String?, year: Int?): ResolvedRailMediaIdentity {
        return resolve(
            mediaType = progress.contentType,
            rawId = progress.contentId,
            title = title ?: progress.name,
            year = year,
            imdbId = extractImdbId(progress.contentId),
            tmdbId = extractProviderId(progress.contentId, "tmdb"),
            traktId = null
        )
    }

    private fun resolve(
        mediaType: String,
        rawId: String,
        title: String?,
        year: Int?,
        imdbId: String?,
        tmdbId: String?,
        traktId: String?
    ): ResolvedRailMediaIdentity {
        val normalizedType = if (mediaType.equals("movie", ignoreCase = true)) "movie" else "series"
        val canonicalKey = when {
            !imdbId.isNullOrBlank() -> "$normalizedType:imdb:$imdbId"
            !tmdbId.isNullOrBlank() -> "$normalizedType:tmdb:$tmdbId"
            !traktId.isNullOrBlank() -> "$normalizedType:trakt:$traktId"
            else -> "$normalizedType:raw:${rawId.trim()}"
        }
        val externalIds = buildList {
            imdbId?.let { add(externalId(canonicalKey, "IMDB", it, "canonical")) }
            tmdbId?.let { add(externalId(canonicalKey, "TMDB", it, "canonical")) }
            traktId?.let { add(externalId(canonicalKey, "TRAKT", it, "canonical")) }
        }
        return ResolvedRailMediaIdentity(
            mediaIdentity = MediaIdentityEntity(
                mediaKey = canonicalKey,
                mediaType = normalizedType,
                title = title,
                year = year,
                updatedAtEpochMs = System.currentTimeMillis()
            ),
            externalIds = externalIds
        )
    }

    private fun externalId(mediaKey: String, provider: String, externalId: String, idType: String) =
        ExternalIdEntity(
            key = "$provider:$idType:$externalId",
            mediaKey = mediaKey,
            provider = provider,
            externalId = externalId,
            idType = idType
        )

    private fun extractImdbId(raw: String?): String? =
        Regex("tt\\d+").find(raw.orEmpty())?.value

    private fun extractProviderId(raw: String?, provider: String): String? {
        val prefix = "$provider:"
        val trimmed = raw?.trim().orEmpty()
        if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
        return trimmed.substringAfter(':').substringBefore(':').trim().ifBlank { null }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.RailKeyFactoryTest" --tests "com.nexio.tv.core.integration.RailMediaIdentityResolverTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/RailKeyFactory.kt app/src/main/java/com/nexio/tv/core/integration/RailMediaIdentityResolver.kt app/src/test/java/com/nexio/tv/core/integration/RailKeyFactoryTest.kt app/src/test/java/com/nexio/tv/core/integration/RailMediaIdentityResolverTest.kt
git commit -m "feat: add scoped rail keys and canonical media identity resolution"
```

### Task 2: Fix Namespace And Profile Isolation In Rail Writers

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationOwnershipService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Test: `app/src/test/java/com/nexio/tv/core/integration/IntegrationOwnershipServiceTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `syncing home catalog namespace does not delete continue watching rail in same profile`() = runTest {
    val fixture = railOwnershipFixture()
    fixture.seedMediaOwnedByRails(
        mediaKey = "series:imdb:tt0944947",
        railKeys = listOf(
            RailKeyFactory.continueWatching(profileId = 7),
            RailKeyFactory.homeCatalog(profileId = 7, catalogId = "tmdb:popular:tv")
        )
    )

    fixture.ownershipService.syncRails(
        namespacePrefix = RailKeyFactory.homeCatalogNamespace(profileId = 7),
        memberships = listOf(
            fixture.simpleMembership(
                railKey = RailKeyFactory.homeCatalog(profileId = 7, catalogId = "tmdb:popular:movies"),
                mediaKey = "movie:imdb:tt0137523"
            )
        )
    )

    assertEquals(
        RailKeyFactory.continueWatching(profileId = 7),
        fixture.railStoreDao.rail(RailKeyFactory.continueWatching(profileId = 7))?.railKey
    )
}

@Test
fun `syncing profile one rails does not delete profile two rails`() = runTest {
    val fixture = railOwnershipFixture()
    fixture.seedMediaOwnedByRails(
        mediaKey = "movie:imdb:tt0137523",
        railKeys = listOf(RailKeyFactory.homeCatalog(profileId = 2, catalogId = "tmdb:popular:movies"))
    )

    fixture.ownershipService.syncRails(
        namespacePrefix = RailKeyFactory.homeCatalogNamespace(profileId = 1),
        memberships = emptyList()
    )

    assertEquals(
        RailKeyFactory.homeCatalog(profileId = 2, catalogId = "tmdb:popular:movies"),
        fixture.railStoreDao.rail(RailKeyFactory.homeCatalog(profileId = 2, catalogId = "tmdb:popular:movies"))?.railKey
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationOwnershipServiceTest"
```

Expected: FAIL because the current writers still use broad `home:` / `library:*` namespaces.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// ContinueWatchingSnapshotService.kt
private const val CONTINUE_WATCHING_RAIL_KEY_PREFIX = "profile:"

private suspend fun syncContinueWatchingRail(
    snapshot: ContinueWatchingSnapshot,
    referencedItemKeys: Set<String>,
    profileId: Int
) {
    val ownership = ownershipService ?: return
    val now = System.currentTimeMillis()
    val railKey = RailKeyFactory.continueWatching(profileId)
    ownership.upsertRailMembership(
        RailMembership(
            rail = RailCacheEntity(
                railKey = railKey,
                provider = "LOCAL",
                kind = "CONTINUE_WATCHING",
                paramsHash = "profile:$profileId",
                fetchedAtEpochMs = now,
                expiresAtEpochMs = now + minRefreshIntervalMs,
                staleUntilEpochMs = now + REFRESH_FAILURE_RETRY_MS
            ),
            items = referencedItemKeys.sorted().mapIndexed { index, mediaKey ->
                RailItemEntity(
                    key = "$railKey#$mediaKey",
                    railKey = railKey,
                    mediaKey = mediaKey,
                    position = index,
                    updatedAtEpochMs = now
                )
            }
        )
    )
}

// HomeCatalogSnapshotStore.kt
private fun buildRailMemberships(
    snapshot: Snapshot,
    posterProviderToken: String,
    profileId: Int
): List<RailMembership> = rows.map { row ->
    val railKey = RailKeyFactory.homeCatalog(profileId = profileId, catalogId = row.catalogId)
    ...
}

suspend fun write(snapshot: Snapshot, posterProviderToken: String, profileId: Int = activeProfileId()) {
    withContext(Dispatchers.IO) {
        ...
        ownershipService?.syncRails(
            namespacePrefix = RailKeyFactory.homeCatalogNamespace(profileId),
            memberships = buildRailMemberships(snapshot, posterProviderToken, profileId)
        )
    }
}

// TraktLibrarySnapshotStore.kt
val railKey = RailKeyFactory.traktLibrary(profileId = profileId, listKey = listKey)
...
ownershipService?.syncRails(
    namespacePrefix = RailKeyFactory.traktLibraryNamespace(profileId),
    memberships = buildRailMemberships(snapshot, profileId)
)

// SimklLibrarySnapshotStore.kt
val railKey = RailKeyFactory.simklLibrary(profileId = profileId, listKey = listKey)
...
ownershipService?.syncRails(
    namespacePrefix = RailKeyFactory.simklLibraryNamespace(profileId),
    memberships = buildRailMemberships(snapshot, profileId)
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationOwnershipServiceTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationOwnershipService.kt app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/test/java/com/nexio/tv/core/integration/IntegrationOwnershipServiceTest.kt
git commit -m "fix: scope rail ownership by profile and namespace"
```

### Task 3: Persist Canonical Media Identities And External IDs From Every Writer

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationOwnershipService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/MediaIdentityDao.kt`
- Test: `app/src/test/java/com/nexio/tv/core/integration/RailMediaIdentityResolverTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `home snapshots write canonical identities instead of raw addon ids`() = runTest {
    val resolver = RailMediaIdentityResolver()
    val preview = MetaPreview(
        id = "tmdb:550",
        type = ContentType.MOVIE,
        name = "Fight Club",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "1999",
        imdbRating = null,
        genres = emptyList()
    )

    val resolved = resolver.fromPreview(preview)

    assertEquals("movie:tmdb:550", resolved.mediaIdentity.mediaKey)
    assertTrue(resolved.externalIds.any { it.provider == "TMDB" && it.externalId == "550" })
}

@Test
fun `ownership service replaces external ids per canonical media key`() = runTest {
    val fixture = railOwnershipFixture()
    val membership = fixture.simpleMembership(
        railKey = RailKeyFactory.homeCatalog(1, "tmdb:popular:movies"),
        mediaKey = "movie:imdb:tt0137523",
        externalIds = listOf(
            ExternalIdEntity(
                key = "TMDB:canonical:550",
                mediaKey = "movie:imdb:tt0137523",
                provider = "TMDB",
                externalId = "550",
                idType = "canonical"
            )
        )
    )

    fixture.ownershipService.upsertRailMembership(membership)

    assertEquals(1, fixture.mediaIdentityDao.externalIdsForMedia("movie:imdb:tt0137523").size)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.RailMediaIdentityResolverTest" --tests "com.nexio.tv.core.integration.IntegrationOwnershipServiceTest"
```

Expected: FAIL because the current writers still generate raw `contentType:id` keys and mostly empty `externalIds`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// MediaIdentityDao.kt
@Query("""
    SELECT mediaKey FROM integration_external_id
    WHERE provider = :provider AND externalId = :externalId AND idType = :idType
    LIMIT 1
""")
abstract suspend fun findMediaKeyByExternalId(
    provider: String,
    externalId: String,
    idType: String
): String?

// HomeCatalogSnapshotStore.kt
private val identityResolver = RailMediaIdentityResolver()

mediaIdentities = row.items.map { item ->
    identityResolver.fromPreview(item).mediaIdentity
},
externalIds = row.items.flatMap { item ->
    identityResolver.fromPreview(item).externalIds
}

// TraktLibrarySnapshotStore.kt
externalIds = entries.flatMap { entry ->
    identityResolver.fromLibraryEntry(entry).externalIds
}

// ContinueWatchingSnapshotService.kt
val resolved = snapshot.resumeItems.map { progress ->
    identityResolver.fromWatchProgress(
        progress = progress,
        title = snapshot.displayMetadataByItemKey[homeDisplayItemKey(progress.contentType, progress.contentId)]?.title,
        year = snapshot.displayMetadataByItemKey[homeDisplayItemKey(progress.contentType, progress.contentId)]?.releaseInfo?.take(4)?.toIntOrNull()
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.RailMediaIdentityResolverTest" --tests "com.nexio.tv.core.integration.IntegrationOwnershipServiceTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/RailMediaIdentityResolver.kt app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/main/java/com/nexio/tv/data/local/integration/MediaIdentityDao.kt app/src/test/java/com/nexio/tv/core/integration/RailMediaIdentityResolverTest.kt app/src/test/java/com/nexio/tv/core/integration/IntegrationOwnershipServiceTest.kt
git commit -m "fix: canonicalize rail media identities and external ids"
```

### Task 4: Remove Blocking Ownership Sync From Snapshot Stores

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt`
- Test: `app/src/test/java/com/nexio/tv/architecture/NoBlockingRailOwnershipSyncTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

class NoBlockingRailOwnershipSyncTest {
    @Test
    fun `snapshot stores do not use runBlocking for ownership sync`() {
        val offenders = sourceTextScan(
            forbiddenPatterns = listOf(
                "runBlocking { ownershipService",
                "runBlocking { ownershipService?.syncRails"
            ),
            allowedPaths = listOf("app/src/test/")
        )

        assertTrue("Blocking ownership sync remains: $offenders", offenders.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.architecture.NoBlockingRailOwnershipSyncTest"
```

Expected: FAIL because the snapshot stores currently call `runBlocking`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// HomeCatalogSnapshotStore.kt
suspend fun write(
    snapshot: Snapshot,
    posterProviderToken: String,
    profileId: Int = activeProfileId()
) = withContext(Dispatchers.IO) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(snapshotKey(profileId), gson.toJson(payload)).commit()
    ownershipService?.syncRails(
        namespacePrefix = RailKeyFactory.homeCatalogNamespace(profileId),
        memberships = buildRailMemberships(snapshot, posterProviderToken, profileId)
    )
}

suspend fun clear(profileId: Int = activeProfileId()) = withContext(Dispatchers.IO) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(snapshotKey(profileId))
        .commit()
    ownershipService?.syncRails(RailKeyFactory.homeCatalogNamespace(profileId), emptyList())
}

// HomeViewModelCatalogPipeline.kt
homeCatalogSnapshotStore.clear(profileId = profileManager.activeProfileId.value)
...
homeCatalogSnapshotStore.write(latestSnapshot, posterToken, profileId = profileId)

// PosterRatingsSettingsViewModel.kt
withContext(Dispatchers.IO) {
    homeCatalogSnapshotStore.clear()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.architecture.NoBlockingRailOwnershipSyncTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModel.kt app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt app/src/test/java/com/nexio/tv/architecture/NoBlockingRailOwnershipSyncTest.kt
git commit -m "refactor: make rail ownership sync non-blocking"
```

### Task 5: Wire The Hydration Planner Into Real Refresh Behavior

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/integration/IntegrationHydrationCoordinator.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationHydrationPlanner.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Test: `app/src/test/java/com/nexio/tv/core/integration/IntegrationHydrationCoordinatorTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/integration/IntegrationHydrationPlannerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.RailCacheEntity
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IntegrationHydrationCoordinatorTest {
    @Test
    fun `coordinator consumes planner output and refreshes non-home rails`() = runTest {
        val continueWatching = mockk<com.nexio.tv.data.repository.ContinueWatchingSnapshotService>(relaxed = true)
        val libraryRepository = mockk<com.nexio.tv.domain.repository.LibraryRepository>(relaxed = true)
        val coordinator = IntegrationHydrationCoordinator(
            planner = object : IntegrationHydrationPlanner(mockk(), ActiveRailTracker()) {
                override suspend fun planNextBatch(limit: Int): List<RailCacheEntity> = listOf(
                    RailCacheEntity("profile:7:home:continue_watching", "LOCAL", "CONTINUE_WATCHING", "", 0L, 0L, 0L),
                    RailCacheEntity("profile:7:library:trakt:watchlist", "TRAKT", "LIBRARY", "", 0L, 0L, 0L)
                )
            },
            continueWatchingSnapshotService = continueWatching,
            libraryRepository = libraryRepository
        )

        coordinator.refreshPlannedRails(limit = 2)

        coVerify(exactly = 1) { continueWatching.ensureFresh(force = false) }
        coVerify(exactly = 1) { libraryRepository.refreshProviderNow() }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationHydrationCoordinatorTest"
```

Expected: FAIL because no production consumer exists for the planner.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.RailCacheEntity
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.domain.repository.LibraryRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntegrationHydrationCoordinator @Inject constructor(
    private val planner: IntegrationHydrationPlanner,
    private val continueWatchingSnapshotService: ContinueWatchingSnapshotService,
    private val libraryRepository: LibraryRepository
) {
    suspend fun refreshPlannedRails(limit: Int): List<RailCacheEntity> {
        val planned = planner.planNextBatch(limit)
        if (planned.any { it.railKey.contains(":home:continue_watching") }) {
            continueWatchingSnapshotService.ensureFresh(force = false)
        }
        if (planned.any { it.railKey.contains(":library:trakt:") || it.railKey.contains(":library:simkl:") }) {
            libraryRepository.refreshProviderNow()
        }
        return planned
    }
}

// HomeViewModel.kt
private fun observeActiveHomeRails() {
    viewModelScope.launch {
        _uiState
            .map { state -> state.catalogRows.map { row -> RailKeyFactory.homeCatalog(activeProfileId(), row.catalogId) }.toSet() }
            .collectLatest { activeRails ->
                activeRailTracker.replaceActiveRails(activeRails)
                val planned = integrationHydrationCoordinator.refreshPlannedRails(limit = 2)
                if (planned.any { it.railKey.startsWith(RailKeyFactory.homeCatalogNamespace(activeProfileId())) }) {
                    runSerializedHomeRefreshIfNeeded("priority_hydration")
                }
            }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.integration.IntegrationHydrationCoordinatorTest" --tests "com.nexio.tv.core.integration.IntegrationHydrationPlannerTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationHydrationCoordinator.kt app/src/main/java/com/nexio/tv/core/integration/IntegrationHydrationPlanner.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/test/java/com/nexio/tv/core/integration/IntegrationHydrationCoordinatorTest.kt app/src/test/java/com/nexio/tv/core/integration/IntegrationHydrationPlannerTest.kt
git commit -m "feat: consume rail hydration plans in production"
```

### Task 6: Retire The Legacy Snapshot Ownership Path And Update Docs

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/RailOwnershipLifecycleTest.kt`
- Modify: `docs/architecture/api-integration-runtime.md`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

class RailOwnershipLifecycleTest {
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.architecture.RailOwnershipLifecycleTest"
```

Expected: FAIL while `MetadataDiskCacheStore` and home catalog pipeline still reference the old helpers.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// MetadataDiskCacheStore.kt
// Delete:
// - HOME_REF_PREFIX
// - replaceHomeFeedReferences(...)
// - readHomeReferencedItemKeys()
// - removeHomeUnreferencedMetaEntries(...)

// HomeViewModelCatalogPipeline.kt
if (activeCatalogItemKeys.isEmpty() && activeContinueWatchingItemKeys.isEmpty()) {
    logStartupPerf("metadata_cleanup_skipped", "reason=active_items_empty")
} else {
    logStartupPerf(
        "metadata_cleanup_end",
        "active_items=${activeCatalogItemKeys.size + activeContinueWatchingItemKeys.size} ownership=rail_store"
    )
}

// MetadataDiskCacheStoreTest.kt
@Test
fun `shared metadata remains available without legacy home ownership references`() {
    val store = MetadataDiskCacheStore(context = mockContext(InMemorySharedPreferences()))
    store.writeMeta("movie:tt1", "en", "native", meta("tt1"))
    store.writeMeta("movie:tt2", "en", "native", meta("tt2"))
    assertNotNull(store.readMeta("movie:tt1", "en", "native"))
    assertNotNull(store.readMeta("movie:tt2", "en", "native"))
}

// api-integration-runtime.md
Phase F final lifecycle:
1. Rails are profile-scoped ownership roots.
2. Continue-watching is not part of the home-catalog namespace.
3. Media identities are canonical and backed by external IDs.
4. Legacy `home_ref::` ownership helpers are removed.
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.architecture.RailOwnershipLifecycleTest" --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt app/src/test/java/com/nexio/tv/architecture/RailOwnershipLifecycleTest.kt docs/architecture/api-integration-runtime.md
git commit -m "refactor: retire legacy snapshot ownership path"
```

### Task 7: Run Full Verification And OpenSpec Validation

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/openspec/changes/establish-unified-integration-runtime/tasks.md`

- [ ] **Step 1: Update the OpenSpec task checklist**

```markdown
- [x] Add OpenSpec deltas for runtime gateway, provider boundary, and rail-cache lifecycle.
- [x] Phase A: add integration runtime primitives, provider policy registry, cache-policy modes, real codecs, `IntegrationCacheStore`, local-only `LocalIntegrationCacheStore`, persisted backoff store, and real-runtime tests.
- [x] Phase B: add provider integration adapters and app-facing repository/facade bindings; add architecture tests banning raw provider API injection, direct `IntegrationRuntime` injection, and spec creation outside approved layers.
- [x] Phase C1: enable `CacheFirst` under provider adapters for Kitsu, OMDb, TheIntroDb, AniSkip, AnimeSkip, and ARM-backed skip-intro flows.
- [x] Phase C2: enable `CacheFirst` under provider adapters for TMDB, TVDB, MDBList, Trakt reviews, poster providers, and classify debrid playback-adjacent work as `PLAYBACK_RESOLUTION`.
- [x] Phase C3: route debrid account/library/config/availability APIs through provider adapters and runtime policies without reintroducing playback-transport coupling.
- [x] Phase D: remove feature-layer bypasses, direct provider injections, temporary rollback switches, and obsolete shadow caches; keep only adapter-owned raw clients.
- [x] Phase E: commit architecture docs and keep CI guardrails green for no-bypass enforcement.
- [x] Phase F: add rail/media identity stores, multi-rail ownership cleanup, active-rail hydration planning, and retire legacy snapshot ownership paths.
- [x] Explicitly defer Cloudflare Worker + R2 remote cache implementation until after the Android runtime migration is stable.
- [x] Validate the OpenSpec change with `openspec validate establish-unified-integration-runtime --strict`.
```

- [ ] **Step 2: Run the complete verification bundle**

Run:

```bash
./gradlew :app:compileArm64DebugKotlin :app:compileArm64DebugUnitTestKotlin
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.core.integration.RailKeyFactoryTest" \
  --tests "com.nexio.tv.core.integration.RailMediaIdentityResolverTest" \
  --tests "com.nexio.tv.data.local.integration.RailStoreDaoTest" \
  --tests "com.nexio.tv.core.integration.IntegrationOwnershipServiceTest" \
  --tests "com.nexio.tv.architecture.NoBlockingRailOwnershipSyncTest" \
  --tests "com.nexio.tv.core.integration.IntegrationHydrationPlannerTest" \
  --tests "com.nexio.tv.core.integration.IntegrationHydrationCoordinatorTest" \
  --tests "com.nexio.tv.architecture.RailOwnershipLifecycleTest" \
  --tests "com.nexio.tv.architecture.IntegrationBoundaryTest" \
  --tests "com.nexio.tv.architecture.NoRawProviderInjectionTest" \
  --tests "com.nexio.tv.architecture.NoIntegrationRuntimeInjectionOutsideBoundaryTest" \
  --tests "com.nexio.tv.architecture.NoRuntimeSpecOutsideIntegrationPackagesTest" \
  --tests "com.nexio.tv.architecture.NoLegacyProviderFallbacksTest"
openspec validate establish-unified-integration-runtime --strict
```

Expected:
- both compile tasks PASS
- all listed tests PASS
- OpenSpec validation reports `valid`

- [ ] **Step 3: Commit**

```bash
git add /Users/jneerdael/Scripts/nexio/openspec/changes/establish-unified-integration-runtime/tasks.md
git commit -m "docs: close openspec checklist for rail ownership fixes"
```

## Self-Review

### Spec coverage

- `Rails and media identities define cache ownership roots`
  Covered by Task 1 and Task 3.
- `Orphan cleanup respects multi-rail ownership`
  Covered by Task 2 and Task 3.
- `Active rail hydration prioritizes active stale rails`
  Covered by Task 5.
- `Legacy snapshot ownership paths are retired after rail ownership lands`
  Covered by Task 6.
- Provider-boundary guardrails stay intact while fixing the rail layer
  Re-verified in Task 7.

### Placeholder scan

No `TODO`, `TBD`, “implement later”, or “similar to previous task” placeholders are left in the plan. Every code-changing step contains code, every test step contains the actual test, and every verification step contains the concrete command.

### Type consistency

The plan uses these stable names throughout:
- `RailKeyFactory`
- `RailMediaIdentityResolver`
- `ResolvedRailMediaIdentity`
- `IntegrationHydrationCoordinator`
- `NoBlockingRailOwnershipSyncTest`
- `RailOwnershipLifecycleTest`

No later task renames or aliases them.
