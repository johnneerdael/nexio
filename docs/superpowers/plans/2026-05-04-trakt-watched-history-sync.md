# Trakt Watched History Sync — Integration Runtime Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Series watched on Trakt are reflected as watched in the app, regardless of which id (TVDB / TMDB / IMDB / Trakt / Kitsu-via-fribb) the local library uses, with persistence handled by the integration runtime cache instead of in-memory state.

**Architecture:** Route `/sync/watched/{movies,shows}` through `runtime.get(IntegrationSpec(... CacheFirst))` so the snapshot survives process death. Extend `TraktIdUtils.ParsedContentIds` to carry `tvdb` and add a `MediaKind`-aware canonicalisation overload. Fetch `/sync/watched/shows` with full seasons/episodes, project that payload into a per-(`contentId`, season, episode) lookup index with alias keys for every id flavour, route anime through the existing `AnimeIdMappingService`. Drop the bespoke in-memory caches in `TraktProgressService`.

**Tech Stack:** Kotlin, Hilt DI, Room (`IntegrationCacheDatabase`), Retrofit (`TraktApi`), Moshi DTOs, JUnit + Mockito for tests. The integration runtime contracts live in `app/src/main/java/com/nexio/tv/core/integration/`.

**Spec:** `docs/superpowers/specs/2026-05-04-trakt-watched-history-sync-design.md`

---

## File Map

**Create:**
- `app/src/test/java/com/nexio/tv/data/repository/TraktIdUtilsTest.kt` — unit tests for the id layer.
- `app/src/test/java/com/nexio/tv/data/integration/trakt/TraktWatchedRuntimeRoutingTest.kt` — verifies `runtime.get` cache hit / invalidation / account scoping for the watched endpoints.
- `app/src/test/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStoreInvalidateTest.kt` — verifies `IntegrationCacheStore.delete(spec)` evicts a row.
- `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceWatchedShowsTest.kt` — covers the regression (tvdb match), reset_at, anime fribb routing, activity-driven invalidation, mutation eviction.
- `app/src/test/resources/fixtures/trakt/sync_watched_shows_full.json` — Trakt-docs example (Breaking Bad + Parks & Recreation) with seasons/episodes and a `reset_at`.

**Modify:**
- `app/src/main/java/com/nexio/tv/data/remote/dto/trakt/TraktSyncDtos.kt:62-68` — extend `TraktWatchedShowItemDto` and add `TraktWatchedSeasonDto` / `TraktWatchedEpisodeDto`.
- `app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt` — `ParsedContentIds.tvdb`, `parseContentIds`/`toTraktIds` carry tvdb, add `MediaKind` enum, `normalizeContentId(ids, kind)` overload, `traktIdLookupKeys(ids, kind)`, `preferredTraktPathId(ids, kind)`.
- `app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheStore.kt` — add `suspend fun delete(spec: IntegrationSpec<*>): Boolean`.
- `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDao.kt` — add `deleteByCacheKey(cacheKey)` query.
- `app/src/main/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStore.kt:79` — implement `delete(spec)`.
- `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:176, 193` — rewrite `getWatched(type)` and `getWatchedShows()` to `runtime.get(spec)` with `CacheFirst`; add `invalidateWatchedSnapshot(kind)`.
- `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt` — extend `WatchedShowIndexEntry`, rewrite `mapWatchedShowItem`, replace `getWatchedMoviesSnapshot` and `getWatchedShowsSnapshot` with thin projections, update `observeEpisodeProgress`, `hasActivityChanged`, `markAsWatched`, `reconcileQueuedHistoryAddSuccess`. Remove bespoke watched-cache plumbing.

**Delete:**
- `app/src/main/java/com/nexio/tv/data/local/WatchedItemsPreferences.kt` — orphan; no DI binding, no callers.
- `app/src/main/java/com/nexio/tv/domain/model/WatchedItem.kt` (if it exists and has no other consumers — verify before deleting).

---

## Task 1: Baseline — verify the build is green before changes

**Files:** none (pre-flight only)

- [ ] **Step 1: Confirm working tree is clean and on the right branch**

```bash
git status
git rev-parse --abbrev-ref HEAD
```
Expected: clean tree, branch `codex/integration-runtime-phase-a`.

- [ ] **Step 2: Run the test suites that this plan touches and confirm baseline pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.integration.trakt.*" \
  --tests "com.nexio.tv.data.local.integration.*" \
  --tests "com.nexio.tv.core.integration.*"
```
Expected: BUILD SUCCESSFUL. Record the test count for comparison after the plan.

- [ ] **Step 3: No commit (baseline only)**

---

## Task 2: Extend `TraktWatchedShowItemDto` to carry seasons + episodes + reset_at

The current DTO drops Trakt's seasons/episodes block on the floor. We need every field present in `/sync/watched/shows` (without `extended=noseasons`).

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/dto/trakt/TraktSyncDtos.kt:62-68`
- Test: covered transitively by Task 11 (mapping). DTO is data only; mapping tests exercise it.

- [ ] **Step 1: Add the season + episode DTOs and extend the show item**

Append after the existing `TraktWatchedShowItemDto` declaration:

```kotlin
@JsonClass(generateAdapter = true)
data class TraktWatchedShowItemDto(
    @Json(name = "plays") val plays: Int? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "last_updated_at") val lastUpdatedAt: String? = null,
    @Json(name = "reset_at") val resetAt: String? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "seasons") val seasons: List<TraktWatchedSeasonDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktWatchedSeasonDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "episodes") val episodes: List<TraktWatchedEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktWatchedEpisodeDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "plays") val plays: Int? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null
)
```

Replace the existing `TraktWatchedShowItemDto` declaration with the extended one above; delete the duplicate.

- [ ] **Step 2: Compile to verify Moshi adapter generation works**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. (Moshi `@JsonClass(generateAdapter = true)` generates adapters at compile time; failure here means the annotations are misapplied.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/dto/trakt/TraktSyncDtos.kt
git commit -m "feat(trakt): carry seasons/episodes on watched-show DTO"
```

---

## Task 3: Add `tvdb` to `ParsedContentIds` and recognise the `tvdb:` prefix

This is the root of the bug. Without `tvdb` parsing, every TVDB-keyed call site round-trips its id as opaque text.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TraktIdUtilsTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/repository/TraktIdUtilsTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktIdUtilsTest {

    @Test
    fun parseContentIds_recognises_tvdb_prefix() {
        val parsed = parseContentIds("tvdb:81189")
        assertEquals(81189, parsed.tvdb)
        assertNull(parsed.imdb)
        assertNull(parsed.tmdb)
        assertNull(parsed.trakt)
    }

    @Test
    fun toTraktIds_carries_tvdb() {
        val parsed = parseContentIds("tvdb:81189")
        val ids = toTraktIds(parsed)
        assertEquals(81189, ids.tvdb)
    }

    @Test
    fun parseContentIds_still_recognises_existing_prefixes() {
        assertEquals(272, parseContentIds("tmdb:272").tmdb)
        assertEquals("tt0903747", parseContentIds("tt0903747").imdb)
        assertEquals(1, parseContentIds("trakt:1").trakt)
        assertEquals(1, parseContentIds("1").trakt)
    }
}
```

- [ ] **Step 2: Run the test to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TraktIdUtilsTest"
```
Expected: 2 failures (`parseContentIds_recognises_tvdb_prefix`, `toTraktIds_carries_tvdb`); the existing-prefixes test passes.

- [ ] **Step 3: Add the field and parsing logic**

Edit `app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt`:

```kotlin
internal data class ParsedContentIds(
    val trakt: Int? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
    val tvdb: Int? = null
)

internal fun parseContentIds(contentId: String?): ParsedContentIds {
    if (contentId.isNullOrBlank()) return ParsedContentIds()
    val raw = contentId.trim()

    if (raw.startsWith("tt")) {
        return ParsedContentIds(imdb = raw.substringBefore(':'))
    }

    if (raw.startsWith("tmdb:", ignoreCase = true)) {
        return ParsedContentIds(tmdb = raw.substringAfter(':').toIntOrNull())
    }

    if (raw.startsWith("tvdb:", ignoreCase = true)) {
        return ParsedContentIds(tvdb = raw.substringAfter(':').toIntOrNull())
    }

    if (raw.startsWith("trakt:", ignoreCase = true)) {
        return ParsedContentIds(trakt = raw.substringAfter(':').toIntOrNull())
    }

    val numeric = raw.substringBefore(':').toIntOrNull()
    return if (numeric != null) {
        ParsedContentIds(trakt = numeric)
    } else {
        ParsedContentIds()
    }
}

internal fun toTraktIds(ids: ParsedContentIds): TraktIdsDto {
    return TraktIdsDto(
        trakt = ids.trakt,
        imdb = ids.imdb,
        tmdb = ids.tmdb,
        tvdb = ids.tvdb
    )
}
```

- [ ] **Step 4: Run the test to confirm pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TraktIdUtilsTest"
```
Expected: all 3 pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt \
        app/src/test/java/com/nexio/tv/data/repository/TraktIdUtilsTest.kt
git commit -m "feat(trakt): parse tvdb prefix in content ids"
```

---

## Task 4: Add `MediaKind` enum and intentional `normalizeContentId` overload

Today canonicalisation depends on whichever id Trakt happened to send. We make it intentional.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TraktIdUtilsTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `TraktIdUtilsTest.kt`:

```kotlin
@Test
fun normalizeContentId_show_kind_prefers_tvdb() {
    val ids = TraktIdsDto(
        trakt = 1, slug = "breaking-bad",
        imdb = "tt0903747", tmdb = 1396, tvdb = 81189
    )
    assertEquals("tvdb:81189", normalizeContentId(ids, MediaKind.SHOW))
}

@Test
fun normalizeContentId_show_falls_back_to_tmdb_when_tvdb_missing() {
    val ids = TraktIdsDto(
        trakt = 1, slug = "x", imdb = "tt1", tmdb = 99, tvdb = null
    )
    assertEquals("tmdb:99", normalizeContentId(ids, MediaKind.SHOW))
}

@Test
fun normalizeContentId_movie_kind_prefers_tmdb() {
    val ids = TraktIdsDto(
        trakt = 6, slug = "batman-begins-2005",
        imdb = "tt0372784", tmdb = 272, tvdb = null
    )
    assertEquals("tmdb:272", normalizeContentId(ids, MediaKind.MOVIE))
}

@Test
fun normalizeContentId_anime_kind_uses_caller_supplied_canonical() {
    val ids = TraktIdsDto(trakt = 1, tmdb = 1396, tvdb = 81189)
    assertEquals(
        "kitsu:42",
        normalizeContentId(ids, MediaKind.ANIME, animeCanonical = "kitsu:42")
    )
}

@Test
fun normalizeContentId_no_kind_overload_keeps_legacy_behaviour() {
    val ids = TraktIdsDto(imdb = "tt0903747", tmdb = 1396, tvdb = 81189)
    assertEquals("tt0903747", normalizeContentId(ids))
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TraktIdUtilsTest"
```
Expected: 4 failures (the 5th legacy-behaviour test passes).

- [ ] **Step 3: Add `MediaKind` and the new overload**

Edit `app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt`. Add:

```kotlin
internal enum class MediaKind { MOVIE, SHOW, ANIME }

internal fun normalizeContentId(
    ids: TraktIdsDto?,
    kind: MediaKind,
    animeCanonical: String? = null,
    fallback: String? = null
): String {
    if (kind == MediaKind.ANIME) {
        animeCanonical?.takeIf { it.isNotBlank() }?.let { return it }
    }
    val tvdb = ids?.tvdb
    val tmdb = ids?.tmdb
    val imdb = ids?.imdb?.takeIf { it.isNotBlank() }
    val trakt = ids?.trakt

    return when (kind) {
        MediaKind.SHOW -> when {
            tvdb != null -> "tvdb:$tvdb"
            tmdb != null -> "tmdb:$tmdb"
            !imdb.isNullOrBlank() -> imdb
            trakt != null -> "trakt:$trakt"
            else -> fallback?.takeIf { it.isNotBlank() } ?: ""
        }
        MediaKind.MOVIE -> when {
            tmdb != null -> "tmdb:$tmdb"
            !imdb.isNullOrBlank() -> imdb
            trakt != null -> "trakt:$trakt"
            else -> fallback?.takeIf { it.isNotBlank() } ?: ""
        }
        MediaKind.ANIME -> when {
            !imdb.isNullOrBlank() -> imdb
            tmdb != null -> "tmdb:$tmdb"
            tvdb != null -> "tvdb:$tvdb"
            trakt != null -> "trakt:$trakt"
            else -> fallback?.takeIf { it.isNotBlank() } ?: ""
        }
    }
}
```

Leave the existing zero-kind `normalizeContentId(ids: TraktIdsDto?, fallback: String? = null)` overload untouched — call sites that haven't been migrated keep working.

- [ ] **Step 4: Run to confirm pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TraktIdUtilsTest"
```
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt \
        app/src/test/java/com/nexio/tv/data/repository/TraktIdUtilsTest.kt
git commit -m "feat(trakt): kind-aware content id canonicalisation"
```

---

## Task 5: Add `traktIdLookupKeys` and `preferredTraktPathId` helpers

`watchedMovieLookupKeys` (`TraktProgressService.kt:1373`) lives in the wrong place and shows can't share it. Move and generalise.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TraktIdUtilsTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `TraktIdUtilsTest.kt`:

```kotlin
@Test
fun traktIdLookupKeys_show_emits_full_alias_set() {
    val ids = TraktIdsDto(
        trakt = 1, slug = "breaking-bad",
        imdb = "tt0903747", tmdb = 1396, tvdb = 81189
    )
    val keys = traktIdLookupKeys(ids, MediaKind.SHOW)
    assertEquals(
        setOf("tvdb:81189", "tmdb:1396", "tt0903747", "trakt:1", "breaking-bad"),
        keys.toSet()
    )
}

@Test
fun traktIdLookupKeys_movie_omits_tvdb_when_absent() {
    val ids = TraktIdsDto(
        trakt = 6, slug = "batman-begins-2005",
        imdb = "tt0372784", tmdb = 272, tvdb = null
    )
    val keys = traktIdLookupKeys(ids, MediaKind.MOVIE)
    assertEquals(
        setOf("tmdb:272", "tt0372784", "trakt:6", "batman-begins-2005"),
        keys.toSet()
    )
}

@Test
fun preferredTraktPathId_show_prefers_trakt_over_tvdb_for_path_use() {
    // Trakt path endpoints accept trakt slug/id and imdb; tvdb is unreliable in {id}.
    val ids = TraktIdsDto(trakt = 1, slug = "breaking-bad", tvdb = 81189)
    assertEquals("breaking-bad", preferredTraktPathId(ids, MediaKind.SHOW))
}

@Test
fun preferredTraktPathId_show_uses_imdb_when_no_trakt() {
    val ids = TraktIdsDto(imdb = "tt0903747", tvdb = 81189)
    assertEquals("tt0903747", preferredTraktPathId(ids, MediaKind.SHOW))
}

@Test
fun preferredTraktPathId_movie_uses_imdb_then_trakt_then_tmdb() {
    assertEquals(
        "tt0372784",
        preferredTraktPathId(TraktIdsDto(imdb = "tt0372784", tmdb = 272), MediaKind.MOVIE)
    )
    assertEquals(
        "trakt:6",
        preferredTraktPathId(TraktIdsDto(trakt = 6, tmdb = 272), MediaKind.MOVIE)
    )
    assertEquals(
        "tmdb:272",
        preferredTraktPathId(TraktIdsDto(tmdb = 272), MediaKind.MOVIE)
    )
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TraktIdUtilsTest"
```
Expected: 5 new failures.

- [ ] **Step 3: Add the helpers**

Append to `app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt`:

```kotlin
internal fun traktIdLookupKeys(ids: TraktIdsDto?, kind: MediaKind): List<String> {
    if (ids == null) return emptyList()
    return buildList {
        ids.tvdb?.let { if (kind != MediaKind.MOVIE) add("tvdb:$it") }
        ids.tmdb?.let { add("tmdb:$it") }
        ids.imdb?.takeIf { it.isNotBlank() }?.let { add(it) }
        ids.trakt?.let { add("trakt:$it") }
        ids.slug?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
}

/**
 * Returns the id form to use as a Trakt API path segment (e.g. /shows/{id}/...).
 * Trakt accepts trakt slug, trakt id, and imdb id reliably; tvdb is not accepted
 * for /shows/{id}/... endpoints, so we prefer trakt and imdb. tmdb is a last resort.
 */
internal fun preferredTraktPathId(ids: TraktIdsDto?, kind: MediaKind): String? {
    if (ids == null) return null
    val slug = ids.slug?.takeIf { it.isNotBlank() }
    val imdb = ids.imdb?.takeIf { it.isNotBlank() }
    return when (kind) {
        MediaKind.SHOW, MediaKind.ANIME -> slug ?: imdb ?: ids.trakt?.toString() ?: ids.tmdb?.let { "tmdb:$it" }
        MediaKind.MOVIE -> imdb ?: ids.trakt?.let { "trakt:$it" } ?: ids.tmdb?.let { "tmdb:$it" } ?: slug
    }
}
```

- [ ] **Step 4: Run to confirm pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.TraktIdUtilsTest"
```
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt \
        app/src/test/java/com/nexio/tv/data/repository/TraktIdUtilsTest.kt
git commit -m "feat(trakt): introduce alias-key and path-id helpers"
```

---

## Task 6: Add `IntegrationCacheStore.delete(spec)` for explicit cache eviction

The store currently only exposes deletion by media-key ownership. Activity-driven invalidation needs deletion by cache key.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDao.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStoreInvalidateTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStoreInvalidateTest.kt`. Mirror the setup pattern from `LocalIntegrationCacheStoreAtomicityTest.kt` — read it first to copy the in-memory Room boilerplate verbatim:

```bash
sed -n '1,120p' app/src/test/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStoreAtomicityTest.kt
```

Use the same `inMemoryDatabase`, `IntegrationBlobStore`, and `LocalIntegrationCacheStore` construction. Then add this test:

```kotlin
@Test
fun delete_evicts_a_previously_written_entry() = runBlocking {
    val spec = IntegrationSpec(
        provider = IntegrationProvider.TRAKT,
        apiShapeId = "test.shape",
        operationKey = "test.op",
        cacheKey = "test:cache:key",
        codec = IntegrationCodec.gsonCodec<String>(),
        cachePolicy = IntegrationCachePolicy.CacheFirst(
            ttlMs = 60_000L,
            staleAfterExpiryMs = 0L
        ),
        workClass = IntegrationWorkClass.USER_VISIBLE,
        scope = IntegrationScope.GlobalContent,
        load = { IntegrationLoadResult.Success("ignored") }
    )

    store.write(spec, "hello")
    assertEquals("hello", store.readFresh(spec))

    val deleted = store.delete(spec)
    assertEquals(true, deleted)
    assertNull(store.readFresh(spec))
}

@Test
fun delete_returns_false_when_key_missing() = runBlocking {
    val spec = makeSpec(cacheKey = "test:does:not:exist")
    assertEquals(false, store.delete(spec))
}
```

(The `makeSpec` helper can mirror what the existing atomicity test uses; if it doesn't exist, inline the spec construction in both tests.)

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.integration.LocalIntegrationCacheStoreInvalidateTest"
```
Expected: compilation error (`delete` method does not exist on `IntegrationCacheStore`).

- [ ] **Step 3: Add the Dao query**

Edit `app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDao.kt` and add:

```kotlin
@Query("DELETE FROM integration_cache WHERE cacheKey = :cacheKey")
abstract suspend fun deleteByCacheKey(cacheKey: String): Int
```

- [ ] **Step 4: Add the interface method**

Edit `app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheStore.kt`:

```kotlin
interface IntegrationCacheStore {
    suspend fun <T> readFresh(spec: IntegrationSpec<T>): T?
    suspend fun <T> readStale(spec: IntegrationSpec<T>): T?
    suspend fun <T> write(spec: IntegrationSpec<T>, value: T)
    suspend fun deleteOwnedMedia(mediaKey: String): Int
    suspend fun delete(spec: IntegrationSpec<*>): Boolean
}
```

- [ ] **Step 5: Implement it on `LocalIntegrationCacheStore`**

Edit `app/src/main/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStore.kt`. Append after `deleteOwnedMedia`:

```kotlin
override suspend fun delete(spec: IntegrationSpec<*>): Boolean {
    val cacheKey = spec.requiredCacheKey
    val entry = cacheDao.getCacheEntry(cacheKey) ?: return false
    val deleted = cacheDao.deleteByCacheKey(cacheKey) > 0
    if (deleted) {
        // Mirror deleteOwnedMedia: best-effort blob cleanup; orphans are reapable.
        runCatching { blobStore.delete(entry.blobPath) }
    }
    return deleted
}
```

- [ ] **Step 6: Run to confirm pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.integration.LocalIntegrationCacheStoreInvalidateTest"
```
Expected: both pass.

- [ ] **Step 7: Re-run the existing local-integration tests for regression**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.local.integration.*"
```
Expected: all pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationCacheStore.kt \
        app/src/main/java/com/nexio/tv/data/local/integration/IntegrationCacheDao.kt \
        app/src/main/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStore.kt \
        app/src/test/java/com/nexio/tv/data/local/integration/LocalIntegrationCacheStoreInvalidateTest.kt
git commit -m "feat(integration): cache store delete by spec"
```

---

## Task 7: Route `getWatched(type)` through `runtime.get` with `CacheFirst`

Replace the un-cached `executeAuthorizedBackgroundCall` shape with the `fetchTrendingMovies` pattern.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:176-191`
- Test: `app/src/test/java/com/nexio/tv/data/integration/trakt/TraktWatchedRuntimeRoutingTest.kt` (create)
- Fixture: `app/src/test/resources/fixtures/trakt/sync_watched_movies.json` (create — mirrors Trakt-docs example)

- [ ] **Step 1: Add the fixture**

Create `app/src/test/resources/fixtures/trakt/sync_watched_movies.json`:

```json
[
  {
    "plays": 4,
    "last_watched_at": "2014-10-11T17:00:54.000Z",
    "last_updated_at": "2014-10-11T17:00:54.000Z",
    "movie": {
      "title": "Batman Begins",
      "year": 2005,
      "ids": {
        "trakt": 6,
        "slug": "batman-begins-2005",
        "imdb": "tt0372784",
        "tmdb": 272
      }
    }
  }
]
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/integration/trakt/TraktWatchedRuntimeRoutingTest.kt`. Use the same wiring style as `TraktAuthenticatedGlobalContentBoundaryTest.kt` (read it first to confirm provider construction and how it stubs `traktApi`):

```bash
sed -n '1,160p' app/src/test/java/com/nexio/tv/data/integration/trakt/TraktAuthenticatedGlobalContentBoundaryTest.kt
```

Then add this test:

```kotlin
@Test
fun getWatched_movies_second_call_within_ttl_does_not_hit_traktApi() = runBlocking {
    val fixture = readFixture("trakt/sync_watched_movies.json")
    coEvery {
        traktApi.getWatched(any(), eq("movies"), any())
    } returns Response.success(moshi.parseList<TraktWatchedMovieItemDto>(fixture))

    val first = provider.getWatched(type = "movies").valueOrNull()
    val second = provider.getWatched(type = "movies").valueOrNull()

    assertEquals(1, first?.size)
    assertEquals(1, second?.size)
    coVerify(exactly = 1) { traktApi.getWatched(any(), eq("movies"), any()) }
}
```

- [ ] **Step 3: Run to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.trakt.TraktWatchedRuntimeRoutingTest"
```
Expected: failure — second call also hits `traktApi.getWatched` because the current implementation bypasses the cache.

- [ ] **Step 4: Rewrite `getWatched`**

Edit `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt`. Replace the existing `getWatched(type, extended)` with:

```kotlin
suspend fun getWatched(
    type: String
): IntegrationCallResult<List<TraktWatchedMovieItemDto>> {
    val session = traktAuthService.accountScopedSession()
    val spec = IntegrationSpec(
        provider = IntegrationProvider.TRAKT,
        apiShapeId = TraktApiShapes.WATCHED,
        operationKey = accountOperationKey(session, "trakt.watched.$type"),
        cacheKey = accountCacheKey(session, "trakt:sync:watched:$type"),
        codec = gsonCodec<List<TraktWatchedMovieItemDto>>(),
        cachePolicy = IntegrationCachePolicy.CacheFirst(
            ttlMs = WATCHED_SNAPSHOT_TTL_MS,
            staleAfterExpiryMs = WATCHED_SNAPSHOT_STALE_GRACE_MS
        ),
        workClass = IntegrationWorkClass.USER_VISIBLE,
        scope = accountScope(session),
        profileContext = profileContext(session),
        load = {
            val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                traktApi.getWatched(
                    authorization = authorization,
                    type = type,
                    extended = null
                )
            } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")
            if (!response.isSuccessful) {
                return@IntegrationSpec IntegrationLoadResult.HttpError(
                    statusCode = response.code(),
                    retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                    reason = "trakt_watched_${type}_failed"
                )
            }
            IntegrationLoadResult.Success(response.body().orEmpty())
        }
    )
    val value = runtime.get(spec).valueOrNull() ?: return IntegrationCallResult.Missing
    return IntegrationCallResult.Success(value)
}
```

Add the TTL constants near the bottom of the class (next to `credentialHash`):

```kotlin
private companion object {
    const val WATCHED_SNAPSHOT_TTL_MS: Long = 24L * 60L * 60L * 1000L          // 24h, per Trakt's once/day guidance
    const val WATCHED_SNAPSHOT_STALE_GRACE_MS: Long = 7L * 24L * 60L * 60L * 1000L  // 7d grace
}
```

(If a `companion object` already exists in the file, add the constants to it rather than declaring a second one.)

- [ ] **Step 5: Run to confirm pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.trakt.TraktWatchedRuntimeRoutingTest"
```
Expected: pass. Caller-side compilation in `TraktProgressService` may break because `getWatched` no longer accepts `extended`. That gets resolved in Task 12 — leave the broken caller in place for now if needed; the test only needs the provider to compile.

If the broader `:app:compileDebugKotlin` is required to run the test, temporarily inline a no-op `extended` parameter at the one caller site (`TraktProgressService.kt:1324`) by removing it from the call (the parameter was always `null` for the snapshot read).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt \
        app/src/test/java/com/nexio/tv/data/integration/trakt/TraktWatchedRuntimeRoutingTest.kt \
        app/src/test/resources/fixtures/trakt/sync_watched_movies.json \
        app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt
git commit -m "feat(trakt): route watched-movies snapshot through runtime cache"
```

---

## Task 8: Route `getWatchedShows()` through `runtime.get` with full seasons + episodes

Same pattern as Task 7, plus drop the `extended` parameter so the cached payload always includes seasons/episodes.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:193-206`
- Test: `app/src/test/java/com/nexio/tv/data/integration/trakt/TraktWatchedRuntimeRoutingTest.kt`
- Fixture: `app/src/test/resources/fixtures/trakt/sync_watched_shows_full.json` (create)

- [ ] **Step 1: Add the fixture (full seasons + episodes, including a `reset_at` case)**

Create `app/src/test/resources/fixtures/trakt/sync_watched_shows_full.json`:

```json
[
  {
    "plays": 56,
    "last_watched_at": "2014-10-11T17:00:54.000Z",
    "last_updated_at": "2014-10-11T17:00:54.000Z",
    "reset_at": null,
    "show": {
      "title": "Breaking Bad",
      "year": 2008,
      "ids": {
        "trakt": 1,
        "slug": "breaking-bad",
        "tvdb": 81189,
        "imdb": "tt0903747",
        "tmdb": 1396
      }
    },
    "seasons": [
      { "number": 1, "episodes": [
        { "number": 1, "plays": 1, "last_watched_at": "2014-10-11T17:00:54.000Z" },
        { "number": 2, "plays": 1, "last_watched_at": "2014-10-11T17:00:54.000Z" }
      ]},
      { "number": 2, "episodes": [
        { "number": 1, "plays": 1, "last_watched_at": "2014-10-11T17:00:54.000Z" },
        { "number": 2, "plays": 1, "last_watched_at": "2014-10-11T17:00:54.000Z" }
      ]}
    ]
  },
  {
    "plays": 23,
    "last_watched_at": "2019-08-13T17:00:54.000Z",
    "last_updated_at": "2019-08-13T17:00:54.000Z",
    "reset_at": "2019-08-12T17:00:54.000Z",
    "show": {
      "title": "Parks and Recreation",
      "year": 2009,
      "ids": {
        "trakt": 4,
        "slug": "parks-and-recreation",
        "tvdb": 84912,
        "imdb": "tt1266020",
        "tmdb": 8592
      }
    },
    "seasons": [
      { "number": 1, "episodes": [
        { "number": 1, "plays": 1, "last_watched_at": "2014-10-11T17:00:54.000Z" }
      ]},
      { "number": 2, "episodes": [
        { "number": 1, "plays": 1, "last_watched_at": "2019-08-13T17:00:54.000Z" }
      ]}
    ]
  }
]
```

- [ ] **Step 2: Write the failing tests**

Append to `TraktWatchedRuntimeRoutingTest.kt`:

```kotlin
@Test
fun getWatchedShows_second_call_within_ttl_does_not_hit_traktApi() = runBlocking {
    val fixture = readFixture("trakt/sync_watched_shows_full.json")
    coEvery {
        traktApi.getWatchedShows(any(), any())
    } returns Response.success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

    val first = provider.getWatchedShows().valueOrNull()
    val second = provider.getWatchedShows().valueOrNull()

    assertEquals(2, first?.size)
    assertEquals(2, second?.size)
    coVerify(exactly = 1) { traktApi.getWatchedShows(any(), any()) }
}

@Test
fun getWatchedShows_payload_carries_seasons_and_episodes() = runBlocking {
    val fixture = readFixture("trakt/sync_watched_shows_full.json")
    coEvery {
        traktApi.getWatchedShows(any(), any())
    } returns Response.success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

    val items = provider.getWatchedShows().valueOrNull().orEmpty()
    val breakingBad = items.first { it.show?.ids?.tvdb == 81189 }
    assertEquals(2, breakingBad.seasons?.size)
    assertEquals(2, breakingBad.seasons?.first()?.episodes?.size)
}

@Test
fun getWatchedShows_passes_null_extended_so_seasons_are_returned() = runBlocking {
    coEvery {
        traktApi.getWatchedShows(any(), any())
    } returns Response.success(emptyList())

    provider.getWatchedShows()

    coVerify { traktApi.getWatchedShows(any(), extended = null) }
}
```

- [ ] **Step 3: Run to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.trakt.TraktWatchedRuntimeRoutingTest"
```
Expected: failures — second call hits the wire; current call uses `extended=noseasons`.

- [ ] **Step 4: Rewrite `getWatchedShows`**

Replace the existing function in `TraktIntegrationProvider.kt`:

```kotlin
suspend fun getWatchedShows(): IntegrationCallResult<List<TraktWatchedShowItemDto>> {
    val session = traktAuthService.accountScopedSession()
    val spec = IntegrationSpec(
        provider = IntegrationProvider.TRAKT,
        apiShapeId = TraktApiShapes.WATCHED_SHOWS,
        operationKey = accountOperationKey(session, "trakt.watched.shows"),
        cacheKey = accountCacheKey(session, "trakt:sync:watched:shows"),
        codec = gsonCodec<List<TraktWatchedShowItemDto>>(),
        cachePolicy = IntegrationCachePolicy.CacheFirst(
            ttlMs = WATCHED_SNAPSHOT_TTL_MS,
            staleAfterExpiryMs = WATCHED_SNAPSHOT_STALE_GRACE_MS
        ),
        workClass = IntegrationWorkClass.USER_VISIBLE,
        scope = accountScope(session),
        profileContext = profileContext(session),
        load = {
            val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                traktApi.getWatchedShows(
                    authorization = authorization,
                    extended = null
                )
            } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")
            if (!response.isSuccessful) {
                return@IntegrationSpec IntegrationLoadResult.HttpError(
                    statusCode = response.code(),
                    retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                    reason = "trakt_watched_shows_failed"
                )
            }
            IntegrationLoadResult.Success(response.body().orEmpty())
        }
    )
    val value = runtime.get(spec).valueOrNull() ?: return IntegrationCallResult.Missing
    return IntegrationCallResult.Success(value)
}
```

Caller-side: `TraktProgressService.kt:1400` passes `extended = "noseasons"` — drop the argument from the call (no-op for the build now). It will be revisited in Task 12.

- [ ] **Step 5: Run to confirm pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.trakt.TraktWatchedRuntimeRoutingTest"
```
Expected: all three tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt \
        app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt \
        app/src/test/java/com/nexio/tv/data/integration/trakt/TraktWatchedRuntimeRoutingTest.kt \
        app/src/test/resources/fixtures/trakt/sync_watched_shows_full.json
git commit -m "feat(trakt): route watched-shows snapshot with full seasons through runtime cache"
```

---

## Task 9: Add `TraktIntegrationProvider.invalidateWatchedSnapshot(kind)`

Activity-driven invalidation and mutation eviction need a hook.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/trakt/TraktWatchedRuntimeRoutingTest.kt`

- [ ] **Step 1: Write the failing test**

Append:

```kotlin
@Test
fun invalidateWatchedSnapshot_shows_then_read_hits_wire_again() = runBlocking {
    val fixture = readFixture("trakt/sync_watched_shows_full.json")
    coEvery {
        traktApi.getWatchedShows(any(), any())
    } returns Response.success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

    provider.getWatchedShows()  // populates cache
    provider.invalidateWatchedSnapshot(TraktWatchedKind.SHOWS)
    provider.getWatchedShows()  // should re-fetch

    coVerify(exactly = 2) { traktApi.getWatchedShows(any(), any()) }
}

@Test
fun invalidateWatchedSnapshot_movies_then_read_hits_wire_again() = runBlocking {
    val fixture = readFixture("trakt/sync_watched_movies.json")
    coEvery {
        traktApi.getWatched(any(), eq("movies"), any())
    } returns Response.success(moshi.parseList<TraktWatchedMovieItemDto>(fixture))

    provider.getWatched(type = "movies")
    provider.invalidateWatchedSnapshot(TraktWatchedKind.MOVIES)
    provider.getWatched(type = "movies")

    coVerify(exactly = 2) { traktApi.getWatched(any(), eq("movies"), any()) }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.trakt.TraktWatchedRuntimeRoutingTest"
```
Expected: compilation error — `TraktWatchedKind` and `invalidateWatchedSnapshot` don't exist.

- [ ] **Step 3: Add the kind enum + invalidate function**

Append to `TraktIntegrationProvider.kt` (top-level enum next to existing top-level types in the file or at the bottom):

```kotlin
enum class TraktWatchedKind { MOVIES, SHOWS }
```

Add the function on the provider:

```kotlin
suspend fun invalidateWatchedSnapshot(kind: TraktWatchedKind) {
    val session = traktAuthService.accountScopedSession()
    val cacheKey = when (kind) {
        TraktWatchedKind.MOVIES -> accountCacheKey(session, "trakt:sync:watched:movies")
        TraktWatchedKind.SHOWS -> accountCacheKey(session, "trakt:sync:watched:shows")
    }
    // We construct a minimal spec to satisfy IntegrationCacheStore.delete(spec) — the only
    // field consulted is requiredCacheKey; no network call is performed.
    val spec = IntegrationSpec(
        provider = IntegrationProvider.TRAKT,
        apiShapeId = when (kind) {
            TraktWatchedKind.MOVIES -> TraktApiShapes.WATCHED
            TraktWatchedKind.SHOWS -> TraktApiShapes.WATCHED_SHOWS
        },
        operationKey = accountOperationKey(session, "trakt.watched.invalidate.${kind.name.lowercase()}"),
        cacheKey = cacheKey,
        codec = gsonCodec<Unit>(),
        cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 1L, staleAfterExpiryMs = 0L),
        workClass = IntegrationWorkClass.USER_VISIBLE,
        scope = accountScope(session),
        profileContext = profileContext(session),
        load = { IntegrationLoadResult.Success(Unit) }
    )
    cacheStore.delete(spec)
}
```

`cacheStore` (`IntegrationCacheStore`) needs to be available on the provider. If it isn't already injected (`grep "cacheStore\|IntegrationCacheStore" TraktIntegrationProvider.kt` to check), add it to the constructor:

```kotlin
@Inject constructor(
    // ... existing params
    private val cacheStore: IntegrationCacheStore
)
```

Hilt will satisfy the binding because `LocalIntegrationCacheStore` is `@Singleton` and bound to the interface in the integration module — verify by reading `app/src/main/java/com/nexio/tv/di/IntegrationModule.kt` (or whichever module binds `IntegrationCacheStore`); if no binding exists, add one as part of this step.

- [ ] **Step 4: Run to confirm pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.trakt.TraktWatchedRuntimeRoutingTest"
```
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt \
        app/src/test/java/com/nexio/tv/data/integration/trakt/TraktWatchedRuntimeRoutingTest.kt \
        app/src/main/java/com/nexio/tv/di/  # only if a binding was added
git commit -m "feat(trakt): expose watched-snapshot invalidate hook"
```

---

## Task 10: Extend `WatchedShowIndexEntry` with episode set + reset_at + alias keys

The data shape that the rest of the service projects to.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:158-163`
- Test: `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceWatchedShowsTest.kt` (create)

- [ ] **Step 1: Write the failing test (the user's bug, codified)**

Create `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceWatchedShowsTest.kt`. Use the existing `TraktProgressService*Test.kt` files as a wiring template (read `TraktProgressServiceNextUpValidationTest.kt` for the construction pattern of the service under test plus its mocks). Then:

```kotlin
@Test
fun watchedShowIndexEntry_carries_episode_set_and_alias_keys() = runBlocking {
    val fixture = readFixture("trakt/sync_watched_shows_full.json")
    coEvery { traktIntegrationProvider.getWatchedShows() } returns
        IntegrationCallResult.Success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

    val entries = service.testOnlyProjectWatchedShows()  // exposed for tests; see Step 3

    val breakingBad = entries.values.first { it.aliasContentIds.contains("tvdb:81189") }
    assertEquals("tvdb:81189", breakingBad.canonicalContentId)
    assertEquals(
        setOf(1 to 1, 1 to 2, 2 to 1, 2 to 2),
        breakingBad.watchedEpisodes
    )
    assertEquals(
        setOf("tvdb:81189", "tmdb:1396", "tt0903747", "trakt:1", "breaking-bad"),
        breakingBad.aliasContentIds
    )
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceWatchedShowsTest"
```
Expected: compile failure — `WatchedShowIndexEntry` lacks `aliasContentIds`/`watchedEpisodes`/`resetAtMs`/`canonicalContentId`.

- [ ] **Step 3: Extend the entry**

Edit `TraktProgressService.kt:158-163`:

```kotlin
internal data class WatchedShowIndexEntry(
    val canonicalContentId: String,
    val aliasContentIds: Set<String>,
    val name: String,
    val lastWatchedAtMs: Long,
    val resetAtMs: Long?,
    val traktShowId: Int?,
    val watchedEpisodes: Set<Pair<Int, Int>>
)
```

Existing call sites that read `entry.contentId` need updating to `entry.canonicalContentId`. Find them:

```bash
grep -n "WatchedShowIndexEntry\|\.contentId\b" app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt | head -30
```

Update each to the new field name. Don't change behaviour yet — this step is mechanical rename + field addition.

Add a test seam (used by Step 1's test and discarded once mapping is exercised end-to-end in Task 12):

```kotlin
@VisibleForTesting
internal suspend fun testOnlyProjectWatchedShows(): Map<String, WatchedShowIndexEntry> =
    getWatchedShowsSnapshot(forceRefresh = false)
```

(`getWatchedShowsSnapshot` returns the projected map already; it's the index built in Task 12.)

- [ ] **Step 4: Run to confirm test compiles (will still fail on data assertions until Task 11)**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceWatchedShowsTest"
```
Expected: test compiles; assertion failure is acceptable here — it gets fixed in Task 11.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt \
        app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceWatchedShowsTest.kt
git commit -m "refactor(trakt): widen WatchedShowIndexEntry shape"
```

---

## Task 11: Rewrite `mapWatchedShowItem` to project episodes, aliases, reset_at, anime canonical

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:1421-1431`
- Modify: `TraktProgressService` constructor — inject `AnimeIdMappingService`
- Test: `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceWatchedShowsTest.kt`

- [ ] **Step 1: Write the failing tests (regression + reset_at + anime)**

Append:

```kotlin
@Test
fun observeEpisodeProgress_matches_show_by_tvdb_id() = runBlocking {
    val fixture = readFixture("trakt/sync_watched_shows_full.json")
    coEvery { traktIntegrationProvider.getWatchedShows() } returns
        IntegrationCallResult.Success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

    val watched = service.observeEpisodeProgress("tvdb:81189").first()
    assertEquals(
        setOf(1 to 1, 1 to 2, 2 to 1, 2 to 2),
        watched.keys
    )
}

@Test
fun observeEpisodeProgress_matches_show_by_imdb_id() = runBlocking {
    val fixture = readFixture("trakt/sync_watched_shows_full.json")
    coEvery { traktIntegrationProvider.getWatchedShows() } returns
        IntegrationCallResult.Success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

    val watched = service.observeEpisodeProgress("tt0903747").first()
    assertEquals(
        setOf(1 to 1, 1 to 2, 2 to 1, 2 to 2),
        watched.keys
    )
}

@Test
fun observeEpisodeProgress_matches_show_by_tmdb_id() = runBlocking {
    val fixture = readFixture("trakt/sync_watched_shows_full.json")
    coEvery { traktIntegrationProvider.getWatchedShows() } returns
        IntegrationCallResult.Success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

    val watched = service.observeEpisodeProgress("tmdb:1396").first()
    assertEquals(
        setOf(1 to 1, 1 to 2, 2 to 1, 2 to 2),
        watched.keys
    )
}

@Test
fun reset_at_excludes_older_episodes() = runBlocking {
    val fixture = readFixture("trakt/sync_watched_shows_full.json")
    coEvery { traktIntegrationProvider.getWatchedShows() } returns
        IntegrationCallResult.Success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

    // Parks and Recreation: reset_at = 2019-08-12; (1,1) watched at 2014, (2,1) watched at 2019-08-13.
    val watched = service.observeEpisodeProgress("tvdb:84912").first()
    assertEquals(setOf(2 to 1), watched.keys)
}

@Test
fun anime_show_indexed_under_kitsu_canonical_when_resolver_matches() = runBlocking {
    every {
        animeIdMappingService.resolveKitsuId(
            id = AnimeStremioId(AnimeIdSource.TVDB, "81189"),
            mediaKind = ContentMediaKind.SERIES
        )
    } returns "42"  // pretend Breaking Bad is anime for this test

    val fixture = readFixture("trakt/sync_watched_shows_full.json")
    coEvery { traktIntegrationProvider.getWatchedShows() } returns
        IntegrationCallResult.Success(moshi.parseList<TraktWatchedShowItemDto>(fixture))

    val entries = service.testOnlyProjectWatchedShows()
    val anime = entries.values.first { it.canonicalContentId == "kitsu:42" }
    assertEquals(
        setOf("kitsu:42", "tvdb:81189", "tmdb:1396", "tt0903747", "trakt:1", "breaking-bad"),
        anime.aliasContentIds
    )
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceWatchedShowsTest"
```
Expected: failures across the new tests.

- [ ] **Step 3: Inject `AnimeIdMappingService` into `TraktProgressService`**

Add to the constructor (`TraktProgressService.kt:91`):

```kotlin
@Inject constructor(
    // ... existing params
    private val animeIdMappingService: AnimeIdMappingService
)
```

Hilt-managed; no module wiring needed since `AnimeIdMappingService` is already `@Singleton`.

- [ ] **Step 4: Rewrite `mapWatchedShowItem`**

Replace (`TraktProgressService.kt:1421-1431`):

```kotlin
private fun mapWatchedShowItem(item: TraktWatchedShowItemDto): WatchedShowIndexEntry? {
    val show = item.show ?: return null
    val ids = show.ids ?: return null
    val resetAtMs = parseIsoOptionalToMillis(item.resetAt)

    val animeCanonical = resolveAnimeCanonicalIfApplicable(ids)
    val kind = if (animeCanonical != null) MediaKind.ANIME else MediaKind.SHOW
    val canonicalContentId = normalizeContentId(
        ids = ids,
        kind = kind,
        animeCanonical = animeCanonical
    ).takeIf { it.isNotBlank() } ?: return null

    val aliasContentIds = buildSet {
        if (animeCanonical != null) add(animeCanonical)
        addAll(traktIdLookupKeys(ids, kind = MediaKind.SHOW))
    }

    val watchedEpisodes = item.seasons.orEmpty().flatMap { season ->
        val seasonNumber = season.number ?: return@flatMap emptyList()
        season.episodes.orEmpty().mapNotNull { episode ->
            val episodeNumber = episode.number ?: return@mapNotNull null
            val watchedAtMs = parseIsoToMillis(episode.lastWatchedAt)
            if (resetAtMs != null && watchedAtMs < resetAtMs) return@mapNotNull null
            seasonNumber to episodeNumber
        }
    }.toSet()

    return WatchedShowIndexEntry(
        canonicalContentId = canonicalContentId,
        aliasContentIds = aliasContentIds,
        name = show.title ?: canonicalContentId,
        lastWatchedAtMs = parseIsoToMillis(item.lastWatchedAt),
        resetAtMs = resetAtMs,
        traktShowId = ids.trakt,
        watchedEpisodes = watchedEpisodes
    )
}

private fun resolveAnimeCanonicalIfApplicable(ids: TraktIdsDto): String? {
    val candidates = listOfNotNull(
        ids.tvdb?.let { AnimeStremioId(AnimeIdSource.TVDB, it.toString()) },
        ids.tmdb?.let { AnimeStremioId(AnimeIdSource.TMDB, it.toString()) },
        ids.imdb?.takeIf { it.isNotBlank() }?.let { AnimeStremioId(AnimeIdSource.IMDB, it) }
    )
    for (candidate in candidates) {
        val kitsuId = animeIdMappingService.resolveKitsuId(candidate, ContentMediaKind.SERIES)
        if (!kitsuId.isNullOrBlank()) return "kitsu:$kitsuId"
    }
    return null
}

private fun parseIsoOptionalToMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
}
```

If `parseIsoOptionalToMillis` already exists in `TraktIdUtils.kt`, reuse it instead of redeclaring.

- [ ] **Step 5: Run to confirm pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceWatchedShowsTest"
```
Expected: all pass. The id-by-imdb / id-by-tmdb tests will still fail until Task 12 wires the alias lookup into `observeEpisodeProgress`. If they fail, accept the failure for now and proceed; Task 12 closes the loop.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt \
        app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceWatchedShowsTest.kt
git commit -m "feat(trakt): project watched-shows snapshot with episodes, reset_at, anime canonical"
```

---

## Task 12: Replace `getWatchedShowsSnapshot` bespoke caching with thin projection + alias map

Drop the bespoke fingerprint/throttle/cache plumbing. Build the alias-keyed lookup map.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`

- [ ] **Step 1: Write the failing test for alias-keyed lookup**

(Already written in Task 11 — `observeEpisodeProgress_matches_show_by_imdb_id` and `..._by_tmdb_id`. Re-run to confirm they're still red.)

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceWatchedShowsTest"
```
Expected: id-by-imdb and id-by-tmdb still fail.

- [ ] **Step 2: Replace `getWatchedShowsSnapshot`**

Edit `TraktProgressService.kt:1383-1419`. Replace with a thin projection backed by the integration-cached call:

```kotlin
private suspend fun getWatchedShowsSnapshot(forceRefresh: Boolean): Map<String, WatchedShowIndexEntry> {
    if (forceRefresh) {
        traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.SHOWS)
    }
    val items = when (val result = traktIntegrationProvider.getWatchedShows()) {
        is IntegrationCallResult.Success -> result.value
        else -> {
            trace("watched-shows fetch: request returned null (network/auth failure)")
            return emptyMap()
        }
    }
    val entries = items.mapNotNull(::mapWatchedShowItem)
    val aliasMap = buildMap<String, WatchedShowIndexEntry> {
        entries.forEach { entry ->
            entry.aliasContentIds.forEach { alias -> put(alias, entry) }
            put(entry.canonicalContentId, entry)
        }
    }
    return aliasMap
}
```

- [ ] **Step 3: Update `observeEpisodeProgress` to consult the projected episode set first**

Find the current implementation (`TraktProgressService.kt:702-712`). Replace with:

```kotlin
fun observeEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
    val cacheKey = canonicalLookupKey(contentId)
    return flow {
        val watchedShows = getWatchedShowsSnapshot(forceRefresh = false)
        val watchedShowEntry = watchedShows[contentId]
            ?: watchedShows[cacheKey]
            ?: parseContentIds(contentId).let { parsed ->
                watchedShows.values.firstOrNull { entry ->
                    entry.aliasContentIds.any { alias ->
                        alias == contentId || alias == cacheKey
                    }
                }
            }
        val baseFromSnapshot = watchedShowEntry?.watchedEpisodes
            ?.associateWith { (season, episode) ->
                synthesizeWatchedProgress(
                    contentId = watchedShowEntry.canonicalContentId,
                    season = season,
                    episode = episode,
                    lastWatchedAtMs = watchedShowEntry.lastWatchedAtMs
                )
            }
            ?: emptyMap()
        emit(baseFromSnapshot)
        emitAll(
            episodeProgressState
                .map { state -> state[cacheKey]?.progress ?: emptyMap() }
                .map { lazyEntries -> baseFromSnapshot + lazyEntries }
        )
    }
        .onStart {
            scope.launch {
                ensureEpisodeProgressSnapshot(contentId = cacheKey, forceRefresh = false)
            }
        }
        .distinctUntilChanged()
}

private fun synthesizeWatchedProgress(
    contentId: String,
    season: Int,
    episode: Int,
    lastWatchedAtMs: Long
): WatchProgress = WatchProgress(
    contentId = contentId,
    contentType = "series",
    name = contentId,
    poster = null,
    backdrop = null,
    logo = null,
    videoId = contentId,
    season = season,
    episode = episode,
    episodeTitle = null,
    position = 1L,
    duration = 1L,
    lastWatched = lastWatchedAtMs,
    progressPercent = 100f,
    source = WatchProgress.SOURCE_TRAKT_HISTORY,
    traktShowId = null,
    traktEpisodeId = null
)
```

(The synth fields mirror `mapPlaybackEpisode` — copy any field defaults from there if the existing `WatchProgress` constructor demands them.)

- [ ] **Step 4: Strip the bespoke watched-shows cache plumbing**

Delete from `TraktProgressService.kt`:
- `watchedShowsState` field on `TraktProgressRuntimeState` (line 216) and the property accessor (line 329).
- `watchedShowsMutex` (line 339), `watchedShowsUpdatedAtMs` (line 366), `watchedShowsLastAttemptAtMs` (line 369), `hasLoadedWatchedShows` (line 381–383), `watchedShowsStale` (line 287, 1141).
- The corresponding clear-on-reset entries (lines 261, 280, 287, 293).
- `watchedShowsCacheTtlMs` and `watchedShowsFetchThrottleMs` constants if they exist.
- The `lastKnownWatchedShowsFingerprint` field if no longer needed (kept if Task 14 still uses it for triggering `invalidateWatchedSnapshot`).

Search to confirm nothing else references them:

```bash
grep -n "watchedShowsState\|watchedShowsMutex\|watchedShowsUpdatedAtMs\|watchedShowsLastAttemptAtMs\|hasLoadedWatchedShows\|watchedShowsStale\|watchedShowsCacheTtlMs\|watchedShowsFetchThrottleMs" app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt
```
Expected: empty after deletion.

- [ ] **Step 5: Run the watched-shows tests to confirm pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceWatchedShowsTest"
```
Expected: all pass, including imdb / tmdb regression tests.

- [ ] **Step 6: Run the full Trakt repository test cluster for regression**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*"
```
Expected: all pass. If `TraktProgressServiceNextUpValidationTest` fails because it asserts on the bespoke cache, update the test to seed the integration-cached call instead (mock `traktIntegrationProvider.getWatchedShows()`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt \
        app/src/test/java/com/nexio/tv/data/repository/  # any updated existing tests
git commit -m "refactor(trakt): drop bespoke watched-shows cache; project from runtime cache"
```

---

## Task 13: Replace `getWatchedMoviesSnapshot` bespoke caching with thin projection

Symmetric to Task 12. Movies are simpler — no episodes.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`

- [ ] **Step 1: Write a regression test for movies (alias-key match by imdb / tmdb / trakt / slug)**

Append to `TraktProgressServiceWatchedShowsTest.kt` (or create `TraktProgressServiceWatchedMoviesTest.kt` if you prefer file separation):

```kotlin
@Test
fun observeMovieWatched_matches_by_any_id_form() = runBlocking {
    val fixture = readFixture("trakt/sync_watched_movies.json")
    coEvery { traktIntegrationProvider.getWatched(type = "movies") } returns
        IntegrationCallResult.Success(moshi.parseList<TraktWatchedMovieItemDto>(fixture))

    assertEquals(true, service.observeMovieWatched("tt0372784").first())
    assertEquals(true, service.observeMovieWatched("tmdb:272").first())
    assertEquals(true, service.observeMovieWatched("trakt:6").first())
    assertEquals(true, service.observeMovieWatched("batman-begins-2005").first())
    assertEquals(false, service.observeMovieWatched("tt9999999").first())
}
```

- [ ] **Step 2: Run to confirm failure (or pass — current movie code already supports alias matching, so this may pass)**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceWatchedShowsTest.observeMovieWatched_matches_by_any_id_form"
```

If it passes, that confirms movies aren't broken. The refactor below remains worthwhile to remove duplicated cache plumbing.

- [ ] **Step 3: Replace `getWatchedMoviesSnapshot`**

Edit `TraktProgressService.kt:1307-1345`:

```kotlin
private suspend fun getWatchedMoviesSnapshot(forceRefresh: Boolean): Set<String> {
    if (forceRefresh) {
        traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.MOVIES)
    }
    val items = when (val result = traktIntegrationProvider.getWatched(type = "movies")) {
        is IntegrationCallResult.Success -> result.value
        else -> {
            trace("watched-movies fetch: request returned null (network/auth failure)")
            return emptySet()
        }
    }
    return items.flatMap { item -> traktIdLookupKeys(item.movie?.ids, MediaKind.MOVIE) }.toSet()
}
```

- [ ] **Step 4: Replace `watchedMoviesState` exposure with a derived flow**

`observeMovieWatched` (line 714) currently consumes `watchedMoviesState`. Replace its body:

```kotlin
fun observeMovieWatched(contentId: String): Flow<Boolean> {
    val rawKey = contentId.trim()
    val canonicalKey = canonicalLookupKey(rawKey)
    return flow {
        val watchedMovies = getWatchedMoviesSnapshot(forceRefresh = false)
        emit(watchedMovies.contains(rawKey) || watchedMovies.contains(canonicalKey))
    }
        .combine(optimisticProgress) { snapshotWatched, optimistic ->
            val optimisticEntry = optimistic[rawKey]?.progress
                ?: optimistic[canonicalKey]?.progress
            when {
                optimisticEntry?.isCompleted() == true -> true
                optimisticEntry?.isInProgress() == true -> false
                else -> snapshotWatched
            }
        }
        .distinctUntilChanged()
}
```

`isMovieWatched(contentId)` (line 945) becomes:

```kotlin
suspend fun isMovieWatched(contentId: String): Boolean {
    val rawKey = contentId.trim()
    val canonicalKey = canonicalLookupKey(rawKey)
    val watchedMovies = getWatchedMoviesSnapshot(forceRefresh = false)
    return watchedMovies.contains(rawKey) || watchedMovies.contains(canonicalKey)
}
```

- [ ] **Step 5: Strip the bespoke watched-movies cache plumbing**

Delete from `TraktProgressService.kt`:
- `watchedMoviesState` field and accessor.
- `watchedMoviesMutex`, `watchedMoviesUpdatedAtMs`, `watchedMoviesLastAttemptAtMs`, `hasLoadedWatchedMovies`, `watchedMoviesStale`.
- `setMovieWatchedInCache` (line 1347) — replace its callers with `traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.MOVIES)` followed by a no-op (the next read repopulates).
- `watchedMovieLookupKeys` (line 1373) — superseded by `traktIdLookupKeys(ids, MediaKind.MOVIE)`.

Confirm no leftover references:

```bash
grep -n "watchedMoviesState\|watchedMoviesMutex\|setMovieWatchedInCache\|watchedMovieLookupKeys" app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt
```
Expected: empty.

- [ ] **Step 6: Run the full repository tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*"
```
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt \
        app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceWatchedShowsTest.kt
git commit -m "refactor(trakt): drop bespoke watched-movies cache; project from runtime cache"
```

---

## Task 14: `hasActivityChanged` invalidates the cache instead of mutating stale flags

The activity check is the trigger; the cache is the truth.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:1130-1185`
- Test: `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceWatchedShowsTest.kt`

- [ ] **Step 1: Write the failing test**

Append:

```kotlin
@Test
fun activity_change_invalidates_watched_shows_cache() = runBlocking {
    coEvery { traktIntegrationProvider.getLastActivities() } returnsMany listOf(
        IntegrationCallResult.Success(activitiesWith(episodesWatchedAt = "2020-01-01T00:00:00Z")),
        IntegrationCallResult.Success(activitiesWith(episodesWatchedAt = "2026-05-04T00:00:00Z"))
    )

    service.refreshNow()  // first poll: fingerprint set
    service.refreshNow()  // second poll: fingerprint changed

    coVerify(exactly = 1) {
        traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.SHOWS)
    }
}
```

(`activitiesWith` is a small fixture builder for `TraktLastActivitiesResponseDto` — copy from any existing activity-test helper.)

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceWatchedShowsTest.activity_change_invalidates_watched_shows_cache"
```
Expected: failure — `invalidateWatchedSnapshot` is not yet called.

- [ ] **Step 3: Patch `hasActivityChanged`**

Inside the existing function, replace the section that flips `watchedShowsStale = true`:

```kotlin
val watchedShowsFingerprint = activities.episodes?.watchedAt.orEmpty()
if (watchedShowsFingerprint != lastKnownWatchedShowsFingerprint) {
    val firstObservation = lastKnownWatchedShowsFingerprint == null
    lastKnownWatchedShowsFingerprint = watchedShowsFingerprint
    if (!firstObservation) {
        scope.launch { traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.SHOWS) }
    }
    trace("last_activities: watched-show candidate fingerprint changed")
    val version = showNextUpActivityVersion.incrementAndGet()
    trace("last_activities: watched-show candidate fingerprint changed -> next-up cache version=$version")
}
```

(Skip the invalidate on the very first observation — there's nothing to evict and we don't want a redundant fetch.)

Apply the same change for movies — wherever `watchedMoviesStale = true` was set, call `traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.MOVIES)` instead.

- [ ] **Step 4: Run to confirm pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceWatchedShowsTest.activity_change_invalidates_watched_shows_cache"
```
Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt \
        app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceWatchedShowsTest.kt
git commit -m "feat(trakt): activity-driven invalidation of watched snapshot"
```

---

## Task 15: Mutations evict the watched cache

Local `markAsWatched` / outbox reconcile must invalidate the snapshot so the next read reflects the change.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:730-758, 775-786`
- Test: `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceWatchedShowsTest.kt`

- [ ] **Step 1: Write the failing test**

Append:

```kotlin
@Test
fun markAsWatched_episode_invalidates_watched_shows_cache() = runBlocking {
    coEvery { traktProgressMutationExecutor.addHistory(any()) } returns
        Response.success(TraktHistoryAddResponseDto(added = TraktHistoryAddedDto(episodes = 1)))

    val episode = WatchProgress(
        contentId = "tvdb:81189",
        contentType = "series",
        name = "Breaking Bad",
        poster = null, backdrop = null, logo = null, videoId = "tvdb:81189",
        season = 1, episode = 1, episodeTitle = null,
        position = 100L, duration = 100L,
        lastWatched = 1_700_000_000_000L,
        progressPercent = 100f,
        source = WatchProgress.SOURCE_TRAKT_HISTORY,
        traktShowId = 1, traktEpisodeId = null
    )

    service.markAsWatched(episode, title = "Breaking Bad", year = 2008)

    coVerify(atLeast = 1) {
        traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.SHOWS)
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceWatchedShowsTest.markAsWatched_episode_invalidates_watched_shows_cache"
```
Expected: failure.

- [ ] **Step 3: Wire eviction**

In `markAsWatched` (`TraktProgressService.kt:730`) replace the existing branch:

```kotlin
if (progress.contentType.equals("movie", ignoreCase = true)) {
    traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.MOVIES)
} else if (
    progress.contentType.equals("series", ignoreCase = true) ||
    progress.contentType.equals("tv", ignoreCase = true)
) {
    invalidateEpisodeProgressCache(progress.contentId)
    invalidateShowNextUpCache(progress.contentId)
    traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.SHOWS)
}
```

Apply the same addition to `reconcileQueuedHistoryAddSuccess` (line 775).

- [ ] **Step 4: Run to confirm pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceWatchedShowsTest.markAsWatched_episode_invalidates_watched_shows_cache"
```
Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt \
        app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceWatchedShowsTest.kt
git commit -m "feat(trakt): mutations evict watched snapshot cache"
```

---

## Task 16: Delete `WatchedItemsPreferences` (orphan)

**Files:**
- Delete: `app/src/main/java/com/nexio/tv/data/local/WatchedItemsPreferences.kt`

- [ ] **Step 1: Verify no callers remain**

```bash
grep -rn "WatchedItemsPreferences\|watchedItemsPreferences" app/src --include="*.kt"
```
Expected: only the file itself appears (one match).

- [ ] **Step 2: Check `WatchedItem` model usage**

```bash
grep -rn "import com.nexio.tv.domain.model.WatchedItem\|: WatchedItem\b\|WatchedItem(" app/src --include="*.kt"
```

If only `WatchedItemsPreferences.kt` references it, delete `app/src/main/java/com/nexio/tv/domain/model/WatchedItem.kt` too. If anything else imports it, leave the model file alone.

- [ ] **Step 3: Delete the orphan and recompile**

```bash
git rm app/src/main/java/com/nexio/tv/data/local/WatchedItemsPreferences.kt
# If WatchedItem.kt is also unused per Step 2:
# git rm app/src/main/java/com/nexio/tv/domain/model/WatchedItem.kt
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(trakt): delete orphan WatchedItemsPreferences"
```

---

## Task 17: Final verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full Trakt + integration test surface**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  --tests "com.nexio.tv.data.integration.trakt.*" \
  --tests "com.nexio.tv.data.local.integration.*" \
  --tests "com.nexio.tv.core.integration.*" \
  --tests "com.nexio.tv.data.repository.TraktIdUtilsTest"
```
Expected: BUILD SUCCESSFUL. Compare test count with the baseline from Task 1 — should be higher (we added ~25 tests across the new files).

- [ ] **Step 2: Run the broader debug unit test suite for cross-cutting regressions**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL. If unrelated tests fail, investigate (could be flakiness; rerun once before assuming a real regression).

- [ ] **Step 3: Smoke-test on a device or emulator**

Install the debug build on a real device or emulator signed into a Trakt account that has watched series:

```bash
./gradlew :app:installDebug
```

Open a TV-show detail view for a series with episodes marked watched on Trakt (e.g. Breaking Bad if the Trakt account has that history). Verify episodes show as watched.

If you cannot test the UI, say so explicitly when reporting completion — type-checking and tests verify code correctness, not feature correctness.

- [ ] **Step 4: No commit (verification only)**

---

## Self-Review

**Spec coverage:** Each spec section maps to tasks:
- Decision 1 (id layer + tvdb): Tasks 3, 4, 5
- Decision 2 (runtime.get + CacheFirst): Tasks 6, 7, 8, 9
- Decision 3 (TraktProgressService projection): Tasks 10, 11, 12, 13, 14, 15
- Decision 4 (anime fribb routing): Task 11
- Decision 5 (delete WatchedItemsPreferences): Task 16
- Data flow / Error handling / Testing sections: covered in Tasks 6–17

**Placeholder scan:** No "TBD" / "TODO" / vague-error-handling phrasing. Every code step contains the actual code.

**Type consistency:** `WatchedShowIndexEntry.canonicalContentId` / `aliasContentIds` / `watchedEpisodes` / `resetAtMs` introduced in Task 10, used identically in Tasks 11–13. `MediaKind` enum (`MOVIE`/`SHOW`/`ANIME`) consistent across Tasks 4, 5, 11, 13. `TraktWatchedKind` (`MOVIES`/`SHOWS`) consistent across Tasks 9, 12, 13, 14, 15.

**Known minor risk:** Task 11's anime test fakes Breaking Bad as anime. That's deliberate — it isolates the routing logic from the asset's real contents. If the team prefers a real anime fixture, swap in a known-anime show id from `anime/anime-id-map.json`.
