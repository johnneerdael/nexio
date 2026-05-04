# Trakt `/sync/collection` Read+Write Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Trakt collection (`/sync/collection/{movies,shows}` read + write) as a first-class library surface, mirroring the watchlist + custom-list architecture already in place. Eliminate the largest functional gap vs the three reference implementations (NuvioMobile and Seren both support collection; only NuvioTV omits it).

**Architecture:** Follow the watched-snapshot template (Tasks 7-15 of the parent plan): runtime-cached `getCollection(type)` reads under `IntegrationScope.Account`; an `invalidateCollectionSnapshot(kind)` hook for activity-driven refresh; `addToCollection`/`removeFromCollection` mutations routed through the existing `TraktLibraryMutationAdapter` outbox pattern; `TraktLibraryService` projection exposes alias-keyed membership lookups for badges. Surface 420 list-limit errors with a user-facing message.

**Tech Stack:** Kotlin, Hilt DI, Room (`IntegrationCacheDatabase`), Retrofit, Moshi, JUnit + Mockito.

**Source review:** `docs/superpowers/specs/2026-05-04-trakt-watched-history-sync-design.md` Part 2 (collection table). Trakt API blueprint at `trakt.apib` for `/sync/collection` semantics.

---

## File Map

**Create:**
- `app/src/main/java/com/nexio/tv/data/remote/dto/trakt/TraktCollectionDtos.kt` — `TraktCollectionMovieItemDto`, `TraktCollectionShowItemDto`, `TraktCollectionSeasonDto`, `TraktCollectionEpisodeDto`, `TraktCollectionAddRequestDto`, `TraktCollectionRemoveRequestDto`, response DTOs.
- `app/src/test/java/com/nexio/tv/data/integration/trakt/TraktCollectionRuntimeRoutingTest.kt` — verifies `runtime.get` cache hit / invalidation / account scoping.
- `app/src/test/resources/fixtures/trakt/sync_collection_movies.json` and `app/src/test/resources/fixtures/trakt/sync_collection_shows.json` — Trakt-docs example payloads.
- `app/src/test/java/com/nexio/tv/data/repository/TraktLibraryServiceCollectionTest.kt` — projection + alias lookup + 420 error surfacing.
- `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationAdapterCollectionTest.kt` — mutation envelope + outbox semantics.

**Modify:**
- `app/src/main/java/com/nexio/tv/data/remote/api/TraktApi.kt` — add 4 endpoints (`getCollectionMovies`, `getCollectionShows`, `addToCollection`, `removeFromCollection`).
- `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt` — add `COLLECTION_MOVIES`, `COLLECTION_SHOWS`, `COLLECTION_ADD`, `COLLECTION_REMOVE` ids.
- `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt` — add `getCollection(type)`, `invalidateCollectionSnapshot(kind)`, `addToCollection(...)`, `removeFromCollection(...)`. Extend `TraktWatchedKind` to a broader `TraktSyncKind` (or add a separate `TraktCollectionKind`).
- `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt` — add collection state, `loadCollection()`, `isInCollection(contentId)`, `addToCollection(...)`, `removeFromCollection(...)` with optimistic local mutation + rollback.
- `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationAdapter.kt` — `buildCollectionAddEnvelope`, `buildCollectionRemoveEnvelope`.
- `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt` — handle the new envelope kinds.
- (UI) `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` — expose collection toggle action mirroring the watchlist toggle.
- (UI) `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt` — add `isInCollection: Boolean = false`.

---

## Task 1: Baseline test build

**Files:** none.

- [ ] **Step 1: Confirm clean tree**

```bash
git status
git rev-parse --abbrev-ref HEAD
```

- [ ] **Step 2: Run the test surface**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  --tests "com.nexio.tv.data.integration.trakt.*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: No commit**

---

## Task 2: Add collection DTOs

Per the official `/sync/collection/movies` and `/sync/collection/shows` payload shape in `trakt.apib`. Show payload includes seasons/episodes (similar to `/sync/watched/shows`).

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/remote/dto/trakt/TraktCollectionDtos.kt`

- [ ] **Step 1: Read the trakt.apib for reference**

```bash
grep -n "## Get Collection\|## Add to Collection\|## Remove from Collection\|sync/collection" trakt.apib | head
```
Read 30 lines around each match to confirm field shape.

- [ ] **Step 2: Create the DTOs**

Create `app/src/main/java/com/nexio/tv/data/remote/dto/trakt/TraktCollectionDtos.kt`:

```kotlin
package com.nexio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktCollectionMovieItemDto(
    @Json(name = "collected_at") val collectedAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "metadata") val metadata: TraktCollectionMetadataDto? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionShowItemDto(
    @Json(name = "last_collected_at") val lastCollectedAt: String? = null,
    @Json(name = "last_updated_at") val lastUpdatedAt: String? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "seasons") val seasons: List<TraktCollectionSeasonDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionSeasonDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "episodes") val episodes: List<TraktCollectionEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionEpisodeDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "collected_at") val collectedAt: String? = null,
    @Json(name = "metadata") val metadata: TraktCollectionMetadataDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionMetadataDto(
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "resolution") val resolution: String? = null,
    @Json(name = "hdr") val hdr: String? = null,
    @Json(name = "audio") val audio: String? = null,
    @Json(name = "audio_channels") val audioChannels: String? = null,
    @Json(name = "3d") val threeD: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionAddRequestDto(
    @Json(name = "movies") val movies: List<TraktCollectionAddMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktCollectionAddShowDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionAddMovieDto(
    @Json(name = "collected_at") val collectedAt: String? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "metadata") val metadata: TraktCollectionMetadataDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionAddShowDto(
    @Json(name = "collected_at") val collectedAt: String? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "seasons") val seasons: List<TraktCollectionAddSeasonDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionAddSeasonDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "episodes") val episodes: List<TraktCollectionAddEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionAddEpisodeDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "collected_at") val collectedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionRemoveRequestDto(
    @Json(name = "movies") val movies: List<TraktCollectionRemoveMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktCollectionRemoveShowDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionRemoveMovieDto(
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionRemoveShowDto(
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "seasons") val seasons: List<TraktCollectionAddSeasonDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionWriteResponseDto(
    @Json(name = "added") val added: TraktCollectionWriteCountsDto? = null,
    @Json(name = "existing") val existing: TraktCollectionWriteCountsDto? = null,
    @Json(name = "deleted") val deleted: TraktCollectionWriteCountsDto? = null,
    @Json(name = "not_found") val notFound: TraktCollectionWriteNotFoundDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionWriteCountsDto(
    @Json(name = "movies") val movies: Int? = null,
    @Json(name = "episodes") val episodes: Int? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionWriteNotFoundDto(
    @Json(name = "movies") val movies: List<TraktCollectionRemoveMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktCollectionRemoveShowDto>? = null
)
```

- [ ] **Step 3: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL (Moshi adapter generation kicks in).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/dto/trakt/TraktCollectionDtos.kt
git commit -m "$(cat <<'EOF'
feat(trakt): add /sync/collection request/response DTOs

Models the Trakt collection payload shape: per-item collected_at,
metadata (media_type / resolution / hdr / audio / audio_channels /
3d), nested seasons/episodes for shows, and add/remove request
shapes plus write-response counts.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Add `TraktApi` endpoints + `IntegrationApiShapes` ids

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/TraktApi.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`

- [ ] **Step 1: Add the API shape ids**

Edit `IntegrationApiShapes.kt`. In the `TraktApiShapes` object (line 159), add:

```kotlin
const val COLLECTION_MOVIES = "trakt.collection.movies"
const val COLLECTION_SHOWS = "trakt.collection.shows"
const val COLLECTION_ADD = "trakt.collection.add"
const val COLLECTION_REMOVE = "trakt.collection.remove"
```

- [ ] **Step 2: Add the Retrofit endpoints**

Edit `app/src/main/java/com/nexio/tv/data/remote/api/TraktApi.kt`. Add (near the other `sync/*` endpoints):

```kotlin
@GET("sync/collection/movies")
suspend fun getCollectionMovies(
    @Header("Authorization") authorization: String,
    @Query("extended") extended: String? = null
): Response<List<TraktCollectionMovieItemDto>>

@GET("sync/collection/shows")
suspend fun getCollectionShows(
    @Header("Authorization") authorization: String,
    @Query("extended") extended: String? = null
): Response<List<TraktCollectionShowItemDto>>

@POST("sync/collection")
suspend fun addToCollection(
    @Header("Authorization") authorization: String,
    @Body body: TraktCollectionAddRequestDto
): Response<TraktCollectionWriteResponseDto>

@POST("sync/collection/remove")
suspend fun removeFromCollection(
    @Header("Authorization") authorization: String,
    @Body body: TraktCollectionRemoveRequestDto
): Response<TraktCollectionWriteResponseDto>
```

Add the imports at the top of the file as needed.

- [ ] **Step 3: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/api/TraktApi.kt \
        app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt
git commit -m "$(cat <<'EOF'
feat(trakt): add /sync/collection retrofit endpoints

Two GET endpoints for the collection snapshot (movies, shows) and
two POST endpoints for add/remove. Apishape ids registered for the
integration audit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Provider read methods + `invalidateCollectionSnapshot`

Mirror `getWatched(type)` / `getWatchedShows()` / `invalidateWatchedSnapshot(kind)`.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/trakt/TraktCollectionRuntimeRoutingTest.kt` (create)
- Fixtures: `app/src/test/resources/fixtures/trakt/sync_collection_movies.json` and `app/src/test/resources/fixtures/trakt/sync_collection_shows.json`

- [ ] **Step 1: Add the fixtures**

Create `app/src/test/resources/fixtures/trakt/sync_collection_movies.json`:

```json
[
  {
    "collected_at": "2014-09-01T09:10:11.000Z",
    "updated_at": "2014-09-01T09:10:11.000Z",
    "metadata": {
      "media_type": "digital",
      "resolution": "hd_1080p",
      "hdr": "dolby_vision",
      "audio": "dts_ma",
      "audio_channels": "5.1",
      "3d": false
    },
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

Create `app/src/test/resources/fixtures/trakt/sync_collection_shows.json`:

```json
[
  {
    "last_collected_at": "2014-07-14T01:00:00.000Z",
    "last_updated_at": "2014-07-14T01:00:00.000Z",
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
      {
        "number": 1,
        "episodes": [
          { "number": 1, "collected_at": "2014-07-14T01:00:00.000Z" },
          { "number": 2, "collected_at": "2014-07-14T01:00:00.000Z" }
        ]
      }
    ]
  }
]
```

- [ ] **Step 2: Add the kind enum (or extend `TraktWatchedKind`)**

The cleanest path is a separate `TraktCollectionKind { MOVIES, SHOWS }` since `invalidateCollectionSnapshot(kind)` is semantically distinct from `invalidateWatchedSnapshot(kind)`. Decide based on the existing code: if `TraktWatchedKind` is already public and named generically, add to it; otherwise create a new top-level enum:

```kotlin
enum class TraktCollectionKind { MOVIES, SHOWS }
```

Place it near `TraktWatchedKind` in `TraktIntegrationProvider.kt`.

- [ ] **Step 3: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/integration/trakt/TraktCollectionRuntimeRoutingTest.kt`. Mirror the wiring of `TraktWatchedRuntimeRoutingTest.kt`:

```kotlin
@Test
fun getCollection_movies_second_call_within_ttl_does_not_hit_traktApi() = runBlocking {
    val item = TraktCollectionMovieItemDto(
        collectedAt = "2014-09-01T09:10:11.000Z",
        movie = TraktMovieDto(
            title = "Batman Begins", year = 2005,
            ids = TraktIdsDto(trakt = 6, slug = "batman-begins-2005", imdb = "tt0372784", tmdb = 272)
        )
    )
    coEvery { traktApi.getCollectionMovies(any(), any()) } returns Response.success(listOf(item))

    val first = unwrap(provider.getCollection(type = "movies"))
    val second = unwrap(provider.getCollection(type = "movies"))

    assertEquals(1, first?.size)
    assertEquals("tt0372784", second?.first()?.movie?.ids?.imdb)
    coVerify(exactly = 1) { traktApi.getCollectionMovies(any(), any()) }
}

@Test
fun getCollection_shows_payload_carries_seasons_and_episodes() = runBlocking {
    val item = TraktCollectionShowItemDto(
        lastCollectedAt = "2014-07-14T01:00:00.000Z",
        show = TraktShowDto(
            title = "Breaking Bad", year = 2008,
            ids = TraktIdsDto(trakt = 1, slug = "breaking-bad", tvdb = 81189, imdb = "tt0903747", tmdb = 1396)
        ),
        seasons = listOf(TraktCollectionSeasonDto(number = 1, episodes = listOf(
            TraktCollectionEpisodeDto(number = 1, collectedAt = "2014-07-14T01:00:00.000Z"),
            TraktCollectionEpisodeDto(number = 2, collectedAt = "2014-07-14T01:00:00.000Z")
        )))
    )
    coEvery { traktApi.getCollectionShows(any(), any()) } returns Response.success(listOf(item))

    val items = unwrap(provider.getCollection(type = "shows")).orEmpty()
    val bb = items.first { (it as TraktCollectionShowItemDto).show?.ids?.tvdb == 81189 } as TraktCollectionShowItemDto
    assertEquals(1, bb.seasons?.size)
    assertEquals(2, bb.seasons?.first()?.episodes?.size)
}

@Test
fun invalidateCollectionSnapshot_movies_then_read_hits_wire_again() = runBlocking {
    coEvery { traktApi.getCollectionMovies(any(), any()) } returns Response.success(emptyList())

    provider.getCollection(type = "movies")
    provider.invalidateCollectionSnapshot(TraktCollectionKind.MOVIES)
    provider.getCollection(type = "movies")

    coVerify(exactly = 2) { traktApi.getCollectionMovies(any(), any()) }
}
```

The `getCollection(type)` signature is described in the next step — it returns a sum type covering both DTOs. The simplest design is two separate methods (`getCollectionMovies()`, `getCollectionShows()`) with distinct return types, matching the watched-snapshot split. Adapt the test names accordingly:

```kotlin
suspend fun getCollectionMovies(): IntegrationCallResult<List<TraktCollectionMovieItemDto>>
suspend fun getCollectionShows(): IntegrationCallResult<List<TraktCollectionShowItemDto>>
```

- [ ] **Step 4: Run to confirm failure**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.integration.trakt.TraktCollectionRuntimeRoutingTest" \
  -x generateIntegrationRuntimeAudit
```
Expected: compilation error — methods don't exist.

- [ ] **Step 5: Add the provider methods**

Edit `TraktIntegrationProvider.kt`. Add `TraktCollectionKind` enum at file scope. Add provider methods (mirror `getWatched(type)` shape):

```kotlin
suspend fun getCollectionMovies(): IntegrationCallResult<List<TraktCollectionMovieItemDto>> {
    val session = traktAuthService.accountScopedSession()
    val spec = IntegrationSpec(
        provider = IntegrationProvider.TRAKT,
        apiShapeId = TraktApiShapes.COLLECTION_MOVIES,
        operationKey = accountOperationKey(session, "trakt.collection.movies"),
        cacheKey = accountCacheKey(session, "trakt:sync:collection:movies"),
        codec = gsonCodec<List<TraktCollectionMovieItemDto>>(),
        cachePolicy = IntegrationCachePolicy.CacheFirst(
            ttlMs = COLLECTION_SNAPSHOT_TTL_MS,
            staleAfterExpiryMs = COLLECTION_SNAPSHOT_STALE_GRACE_MS
        ),
        workClass = IntegrationWorkClass.USER_VISIBLE,
        scope = accountScope(session),
        profileContext = profileContext(session),
        load = {
            val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                traktApi.getCollectionMovies(authorization = authorization, extended = null)
            } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")
            if (!response.isSuccessful) {
                return@IntegrationSpec IntegrationLoadResult.HttpError(
                    statusCode = response.code(),
                    retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                    reason = "trakt_collection_movies_failed"
                )
            }
            IntegrationLoadResult.Success(response.body().orEmpty())
        }
    )
    val value = runtime.get(spec).valueOrNull() ?: return IntegrationCallResult.Missing
    return IntegrationCallResult.Success(value)
}

suspend fun getCollectionShows(): IntegrationCallResult<List<TraktCollectionShowItemDto>> {
    // Same shape as getCollectionMovies but with apiShapeId = COLLECTION_SHOWS,
    // cacheKey = "trakt:sync:collection:shows", codec = gsonCodec<List<TraktCollectionShowItemDto>>(),
    // load { } calling traktApi.getCollectionShows.
    // Reason on failure: "trakt_collection_shows_failed".
    val session = traktAuthService.accountScopedSession()
    val spec = IntegrationSpec(
        provider = IntegrationProvider.TRAKT,
        apiShapeId = TraktApiShapes.COLLECTION_SHOWS,
        operationKey = accountOperationKey(session, "trakt.collection.shows"),
        cacheKey = accountCacheKey(session, "trakt:sync:collection:shows"),
        codec = gsonCodec<List<TraktCollectionShowItemDto>>(),
        cachePolicy = IntegrationCachePolicy.CacheFirst(
            ttlMs = COLLECTION_SNAPSHOT_TTL_MS,
            staleAfterExpiryMs = COLLECTION_SNAPSHOT_STALE_GRACE_MS
        ),
        workClass = IntegrationWorkClass.USER_VISIBLE,
        scope = accountScope(session),
        profileContext = profileContext(session),
        load = {
            val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                traktApi.getCollectionShows(authorization = authorization, extended = null)
            } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")
            if (!response.isSuccessful) {
                return@IntegrationSpec IntegrationLoadResult.HttpError(
                    statusCode = response.code(),
                    retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                    reason = "trakt_collection_shows_failed"
                )
            }
            IntegrationLoadResult.Success(response.body().orEmpty())
        }
    )
    val value = runtime.get(spec).valueOrNull() ?: return IntegrationCallResult.Missing
    return IntegrationCallResult.Success(value)
}

suspend fun invalidateCollectionSnapshot(kind: TraktCollectionKind) {
    val session = traktAuthService.accountScopedSession()
    val (apiShapeId, cacheKey, opSuffix) = when (kind) {
        TraktCollectionKind.MOVIES -> Triple(
            TraktApiShapes.COLLECTION_MOVIES,
            accountCacheKey(session, "trakt:sync:collection:movies"),
            "movies"
        )
        TraktCollectionKind.SHOWS -> Triple(
            TraktApiShapes.COLLECTION_SHOWS,
            accountCacheKey(session, "trakt:sync:collection:shows"),
            "shows"
        )
    }
    val spec = IntegrationSpec(
        provider = IntegrationProvider.TRAKT,
        apiShapeId = apiShapeId,
        operationKey = accountOperationKey(session, "trakt.collection.invalidate.$opSuffix"),
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

Add the constants to the existing `private companion object`:

```kotlin
const val COLLECTION_SNAPSHOT_TTL_MS: Long = 24L * 60L * 60L * 1000L          // 24h
const val COLLECTION_SNAPSHOT_STALE_GRACE_MS: Long = 7L * 24L * 60L * 60L * 1000L  // 7d
```

- [ ] **Step 6: Run to confirm pass**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.integration.trakt.TraktCollectionRuntimeRoutingTest" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt \
        app/src/test/java/com/nexio/tv/data/integration/trakt/TraktCollectionRuntimeRoutingTest.kt \
        app/src/test/resources/fixtures/trakt/sync_collection_movies.json \
        app/src/test/resources/fixtures/trakt/sync_collection_shows.json
git commit -m "$(cat <<'EOF'
feat(trakt): route /sync/collection through runtime cache

Add getCollectionMovies(), getCollectionShows(), and
invalidateCollectionSnapshot(kind) on TraktIntegrationProvider.
Same CacheFirst(24h/7d-stale) account-scoped pattern as
/sync/watched.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Provider write methods (`addToCollection`, `removeFromCollection`)

These are mutations — they go through `runtime.call(IntegrationCallSpec(...))` (no cache) and should also invalidate the read cache so the next read reflects the change.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/trakt/TraktCollectionRuntimeRoutingTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `TraktCollectionRuntimeRoutingTest.kt`:

```kotlin
@Test
fun addToCollection_movie_invalidates_collection_movies_cache() = runBlocking {
    coEvery { traktApi.getCollectionMovies(any(), any()) } returns Response.success(emptyList())
    coEvery { traktApi.addToCollection(any(), any()) } returns
        Response.success(TraktCollectionWriteResponseDto(
            added = TraktCollectionWriteCountsDto(movies = 1)
        ))

    provider.getCollectionMovies()  // populates cache
    provider.addToCollection(TraktCollectionAddRequestDto(
        movies = listOf(TraktCollectionAddMovieDto(ids = TraktIdsDto(imdb = "tt0372784")))
    ))
    provider.getCollectionMovies()  // should re-fetch

    coVerify(exactly = 2) { traktApi.getCollectionMovies(any(), any()) }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.integration.trakt.TraktCollectionRuntimeRoutingTest.addToCollection_movie_invalidates_collection_movies_cache" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 3: Add the provider methods**

Edit `TraktIntegrationProvider.kt`. Mirror `addToWatchlist` (line 572):

```kotlin
suspend fun addToCollection(
    body: TraktCollectionAddRequestDto
): IntegrationCallResult<TraktCollectionWriteResponseDto> {
    val result = executeAuthorizedBackgroundCall(
        apiShapeId = TraktApiShapes.COLLECTION_ADD,
        operationKey = "trakt.collection.add",
        request = { authorization -> traktApi.addToCollection(authorization, body) },
        mapSuccess = { response -> response.body().toCallResult() }
    )
    if (result is IntegrationCallResult.Success) {
        if (body.movies?.isNotEmpty() == true) invalidateCollectionSnapshot(TraktCollectionKind.MOVIES)
        if (body.shows?.isNotEmpty() == true) invalidateCollectionSnapshot(TraktCollectionKind.SHOWS)
    }
    return result
}

suspend fun removeFromCollection(
    body: TraktCollectionRemoveRequestDto
): IntegrationCallResult<TraktCollectionWriteResponseDto> {
    val result = executeAuthorizedBackgroundCall(
        apiShapeId = TraktApiShapes.COLLECTION_REMOVE,
        operationKey = "trakt.collection.remove",
        request = { authorization -> traktApi.removeFromCollection(authorization, body) },
        mapSuccess = { response -> response.body().toCallResult() }
    )
    if (result is IntegrationCallResult.Success) {
        if (body.movies?.isNotEmpty() == true) invalidateCollectionSnapshot(TraktCollectionKind.MOVIES)
        if (body.shows?.isNotEmpty() == true) invalidateCollectionSnapshot(TraktCollectionKind.SHOWS)
    }
    return result
}
```

- [ ] **Step 4: Run to confirm pass + regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.integration.trakt.TraktCollectionRuntimeRoutingTest" \
  --tests "com.nexio.tv.data.integration.trakt.*" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt \
        app/src/test/java/com/nexio/tv/data/integration/trakt/TraktCollectionRuntimeRoutingTest.kt
git commit -m "$(cat <<'EOF'
feat(trakt): add addToCollection / removeFromCollection mutations

POST /sync/collection and /sync/collection/remove. On success the
corresponding read cache is invalidated so the next getCollection*
call surfaces the change.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `TraktLibraryService` projection (read side)

Expose collection membership as an alias-keyed `Set<String>` for synchronous badge lookups, matching the `watchlistMembership` pattern.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TraktLibraryServiceCollectionTest.kt` (create)

- [ ] **Step 1: Read the existing watchlist projection for shape**

```bash
grep -n "watchlistMembership\|isInWatchlist\|membershipByContent\|allContentKeys" \
  app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt | head
```

The pattern: snapshot loaded into a `MutableStateFlow<Set<String>>` keyed by every alias id form, with `isInWatchlist(contentId)` doing a simple `.contains(canonicalLookupKey(contentId))`.

- [ ] **Step 2: Write the failing test**

Create `TraktLibraryServiceCollectionTest.kt`:

```kotlin
@Test
fun isInCollection_matches_movie_by_any_id_form() = runBlocking {
    val item = TraktCollectionMovieItemDto(
        collectedAt = "2014-09-01T09:10:11.000Z",
        movie = TraktMovieDto(
            title = "Batman Begins", year = 2005,
            ids = TraktIdsDto(trakt = 6, slug = "batman-begins-2005", imdb = "tt0372784", tmdb = 272)
        )
    )
    coEvery { traktIntegrationProvider.getCollectionMovies() } returns
        IntegrationCallResult.Success(listOf(item))
    coEvery { traktIntegrationProvider.getCollectionShows() } returns
        IntegrationCallResult.Success(emptyList())

    libraryService.refreshCollection()

    assertEquals(true, libraryService.isInCollection("tt0372784").first())
    assertEquals(true, libraryService.isInCollection("tmdb:272").first())
    assertEquals(true, libraryService.isInCollection("trakt:6").first())
    assertEquals(true, libraryService.isInCollection("batman-begins-2005").first())
    assertEquals(false, libraryService.isInCollection("tt9999999").first())
}

@Test
fun isInCollection_matches_show_by_tvdb_id() = runBlocking {
    val item = TraktCollectionShowItemDto(
        lastCollectedAt = "2014-07-14T01:00:00.000Z",
        show = TraktShowDto(
            title = "Breaking Bad", year = 2008,
            ids = TraktIdsDto(trakt = 1, slug = "breaking-bad", tvdb = 81189, imdb = "tt0903747", tmdb = 1396)
        )
    )
    coEvery { traktIntegrationProvider.getCollectionMovies() } returns
        IntegrationCallResult.Success(emptyList())
    coEvery { traktIntegrationProvider.getCollectionShows() } returns
        IntegrationCallResult.Success(listOf(item))

    libraryService.refreshCollection()

    assertEquals(true, libraryService.isInCollection("tvdb:81189").first())
    assertEquals(true, libraryService.isInCollection("tt0903747").first())
}
```

- [ ] **Step 3: Run to confirm failure**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.TraktLibraryServiceCollectionTest" \
  -x generateIntegrationRuntimeAudit
```
Expected: compilation error — `refreshCollection`, `isInCollection`, `getCollection*` not defined yet.

- [ ] **Step 4: Implement the projection**

Edit `TraktLibraryService.kt`. Add a `collectionMembership` `MutableStateFlow<Set<String>>` (mirror `watchlistMembership` shape). Add:

```kotlin
private val collectionMembership = MutableStateFlow<Set<String>>(emptySet())

suspend fun refreshCollection(force: Boolean = false) {
    if (force) {
        traktIntegrationProvider.invalidateCollectionSnapshot(TraktCollectionKind.MOVIES)
        traktIntegrationProvider.invalidateCollectionSnapshot(TraktCollectionKind.SHOWS)
    }
    val movies = (traktIntegrationProvider.getCollectionMovies() as? IntegrationCallResult.Success)?.value.orEmpty()
    val shows = (traktIntegrationProvider.getCollectionShows() as? IntegrationCallResult.Success)?.value.orEmpty()
    val keys = buildSet<String> {
        movies.forEach { item -> addAll(traktIdLookupKeys(item.movie?.ids, MediaKind.MOVIE)) }
        shows.forEach { item -> addAll(traktIdLookupKeys(item.show?.ids, MediaKind.SHOW)) }
    }
    collectionMembership.value = keys
}

fun isInCollection(contentId: String): Flow<Boolean> {
    val rawKey = contentId.trim()
    val canonicalKey = canonicalLookupKey(rawKey)
    return collectionMembership.map { keys -> keys.contains(rawKey) || keys.contains(canonicalKey) }
        .distinctUntilChanged()
}
```

(`canonicalLookupKey` and `traktIdLookupKeys` are already imported / used by the watchlist code.)

- [ ] **Step 5: Run to confirm pass**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.TraktLibraryServiceCollectionTest" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt \
        app/src/test/java/com/nexio/tv/data/repository/TraktLibraryServiceCollectionTest.kt
git commit -m "$(cat <<'EOF'
feat(trakt): expose collection membership in TraktLibraryService

refreshCollection() pulls /sync/collection/{movies,shows} via the
runtime cache and projects an alias-keyed Set for O(1) badge lookup.
isInCollection(contentId) returns a Flow<Boolean> matching any id
flavour.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `TraktLibraryService` write-side with optimistic mutation + rollback

Mirror the `addToWatchlist` / `removeFromWatchlist` pattern.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TraktLibraryServiceCollectionTest.kt`

- [ ] **Step 1: Read the existing watchlist mutation for shape**

```bash
grep -n "performOptimisticMutation\|fun addToWatchlist\|fun removeFromWatchlist" \
  app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt | head
```

- [ ] **Step 2: Write the failing tests**

Append to `TraktLibraryServiceCollectionTest.kt`:

```kotlin
@Test
fun addToCollection_optimistically_marks_item_in_membership_then_confirms() = runBlocking {
    coEvery { traktIntegrationProvider.getCollectionMovies() } returns IntegrationCallResult.Success(emptyList())
    coEvery { traktIntegrationProvider.getCollectionShows() } returns IntegrationCallResult.Success(emptyList())
    coEvery { traktIntegrationProvider.addToCollection(any()) } returns
        IntegrationCallResult.Success(TraktCollectionWriteResponseDto(
            added = TraktCollectionWriteCountsDto(movies = 1)
        ))
    libraryService.refreshCollection()

    libraryService.addToCollection(
        contentId = "tt0372784",
        contentType = "movie"
    )

    assertEquals(true, libraryService.isInCollection("tt0372784").first())
}

@Test
fun addToCollection_rolls_back_membership_on_network_failure() = runBlocking {
    coEvery { traktIntegrationProvider.getCollectionMovies() } returns IntegrationCallResult.Success(emptyList())
    coEvery { traktIntegrationProvider.getCollectionShows() } returns IntegrationCallResult.Success(emptyList())
    coEvery { traktIntegrationProvider.addToCollection(any()) } returns IntegrationCallResult.Missing
    libraryService.refreshCollection()

    runCatching {
        libraryService.addToCollection(contentId = "tt0372784", contentType = "movie")
    }

    assertEquals(false, libraryService.isInCollection("tt0372784").first())
}
```

- [ ] **Step 3: Run failure**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.TraktLibraryServiceCollectionTest" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 4: Implement**

Edit `TraktLibraryService.kt`. Add `addToCollection(contentId, contentType)` and `removeFromCollection(...)` mirroring the watchlist counterparts. Use the existing `performOptimisticMutation` helper if it's general; otherwise:

```kotlin
suspend fun addToCollection(contentId: String, contentType: String) {
    val ids = toTraktIds(parseContentIds(contentId))
    val previous = collectionMembership.value
    collectionMembership.value = previous + traktIdLookupKeys(ids, kindOf(contentType))
    val result = if (contentType.equals("movie", ignoreCase = true)) {
        traktIntegrationProvider.addToCollection(TraktCollectionAddRequestDto(
            movies = listOf(TraktCollectionAddMovieDto(ids = ids))
        ))
    } else {
        traktIntegrationProvider.addToCollection(TraktCollectionAddRequestDto(
            shows = listOf(TraktCollectionAddShowDto(ids = ids))
        ))
    }
    when (result) {
        is IntegrationCallResult.Success -> {
            // Confirmed; cache invalidated by provider; nothing to do.
        }
        else -> {
            collectionMembership.value = previous  // rollback
            throw IntegrationMutationFailedException("addToCollection failed: $result")
        }
    }
}
```

(Use whatever exception type the existing watchlist code throws. The `kindOf(contentType)` helper just maps "movie" → `MediaKind.MOVIE`, "series"/"tv" → `MediaKind.SHOW`.)

Same shape for `removeFromCollection`.

- [ ] **Step 5: Run to confirm pass**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.TraktLibraryServiceCollectionTest" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt \
        app/src/test/java/com/nexio/tv/data/repository/TraktLibraryServiceCollectionTest.kt
git commit -m "$(cat <<'EOF'
feat(trakt): optimistic add/remove for collection with rollback

Mirror the watchlist write pattern: mutate membership locally
immediately, post to /sync/collection, roll back on failure.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Outbox adapter for durable mutations

Mirror `TraktLibraryMutationAdapter`'s watchlist envelopes.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationAdapterCollectionTest.kt` (create)

- [ ] **Step 1: Read the existing adapter shape**

```bash
sed -n '1,100p' app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationAdapter.kt
```

- [ ] **Step 2: Write the failing tests**

Create `TraktLibraryMutationAdapterCollectionTest.kt`:

```kotlin
@Test
fun buildCollectionAddEnvelope_serializes_movie_ids() {
    val envelope = TraktLibraryMutationAdapter.buildCollectionAddEnvelope(
        contentId = "tt0372784",
        contentType = "movie",
        profileId = 1
    )

    assertEquals("trakt.collection.add", envelope.operationKey)
    assertEquals(1, envelope.profileId)
    // The serialized body should contain the imdb id.
    assertTrue(envelope.payloadJson.contains("tt0372784"))
}

@Test
fun buildCollectionRemoveEnvelope_serializes_show_ids() {
    val envelope = TraktLibraryMutationAdapter.buildCollectionRemoveEnvelope(
        contentId = "tvdb:81189",
        contentType = "series",
        profileId = 2
    )

    assertEquals("trakt.collection.remove", envelope.operationKey)
    assertEquals(2, envelope.profileId)
    assertTrue(envelope.payloadJson.contains("81189"))
}
```

(The exact `TraktMutationEnvelope` shape — payload field name, etc. — depends on what the existing adapter emits. Read `TraktMutationOutboxModels.kt` to find the envelope structure.)

- [ ] **Step 3: Run failure**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.trakt.TraktLibraryMutationAdapterCollectionTest" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 4: Implement the adapter methods**

Edit `TraktLibraryMutationAdapter.kt`. Add (mirror `buildWatchlistAddEnvelope`):

```kotlin
fun buildCollectionAddEnvelope(
    contentId: String,
    contentType: String,
    profileId: Int
): TraktMutationEnvelope {
    val ids = toTraktIds(parseContentIds(contentId))
    val payload = if (contentType.equals("movie", ignoreCase = true)) {
        TraktCollectionAddRequestDto(movies = listOf(TraktCollectionAddMovieDto(ids = ids)))
    } else {
        TraktCollectionAddRequestDto(shows = listOf(TraktCollectionAddShowDto(ids = ids)))
    }
    return TraktMutationEnvelope(
        operationKey = "trakt.collection.add",
        profileId = profileId,
        payloadJson = moshi.adapter(TraktCollectionAddRequestDto::class.java).toJson(payload),
        // Other envelope fields per the existing model
    )
}

fun buildCollectionRemoveEnvelope(
    contentId: String,
    contentType: String,
    profileId: Int
): TraktMutationEnvelope {
    val ids = toTraktIds(parseContentIds(contentId))
    val payload = if (contentType.equals("movie", ignoreCase = true)) {
        TraktCollectionRemoveRequestDto(movies = listOf(TraktCollectionRemoveMovieDto(ids = ids)))
    } else {
        TraktCollectionRemoveRequestDto(shows = listOf(TraktCollectionRemoveShowDto(ids = ids)))
    }
    return TraktMutationEnvelope(
        operationKey = "trakt.collection.remove",
        profileId = profileId,
        payloadJson = moshi.adapter(TraktCollectionRemoveRequestDto::class.java).toJson(payload),
    )
}
```

Edit `TraktLibraryMutationExecutor.kt`. Add a branch for the new operation keys:

```kotlin
"trakt.collection.add" -> {
    val payload = moshi.adapter(TraktCollectionAddRequestDto::class.java).fromJson(envelope.payloadJson)
        ?: return@executeMutation MutationOutcome.Failed("payload parse failed")
    val result = traktIntegrationProvider.addToCollection(payload)
    classifyResult(result)
}
"trakt.collection.remove" -> {
    val payload = moshi.adapter(TraktCollectionRemoveRequestDto::class.java).fromJson(envelope.payloadJson)
        ?: return@executeMutation MutationOutcome.Failed("payload parse failed")
    val result = traktIntegrationProvider.removeFromCollection(payload)
    classifyResult(result)
}
```

(`classifyResult` and the success/failure semantics mirror the watchlist branches — copy the structure.)

- [ ] **Step 5: Run to confirm pass**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.trakt.TraktLibraryMutationAdapterCollectionTest" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationAdapter.kt \
        app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt \
        app/src/test/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationAdapterCollectionTest.kt
git commit -m "$(cat <<'EOF'
feat(trakt): outbox envelopes for collection add/remove

Add buildCollectionAddEnvelope and buildCollectionRemoveEnvelope to
TraktLibraryMutationAdapter, plus matching executor branches. Failed
collection mutations now survive process kills via the existing
mutation outbox.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Surface HTTP 420 list-limit error

NuvioTV maps 420 to "Trakt list limit reached. Upgrade required." Apply the same to collection writes (Trakt rate-limits collection writes too).

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt` — wrap collection mutations to map 420 → user-facing error.
- Test: `app/src/test/java/com/nexio/tv/data/repository/TraktLibraryServiceCollectionTest.kt`

- [ ] **Step 1: Find the 420 surface in the watchlist write path**

```bash
grep -n "420\|listLimitReached\|TraktListLimitException" app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt
```

If it exists, mirror it for collection. If it doesn't, this is a separate gap — flag in the commit message but still implement for collection.

- [ ] **Step 2: Write the test**

```kotlin
@Test
fun addToCollection_420_response_surfaces_list_limit_error() = runBlocking {
    coEvery { traktIntegrationProvider.getCollectionMovies() } returns IntegrationCallResult.Success(emptyList())
    coEvery { traktIntegrationProvider.getCollectionShows() } returns IntegrationCallResult.Success(emptyList())
    coEvery { traktIntegrationProvider.addToCollection(any()) } returns
        IntegrationCallResult.HttpError(statusCode = 420, reason = "trakt_list_limit")

    val ex = runCatching {
        libraryService.addToCollection(contentId = "tt0372784", contentType = "movie")
    }.exceptionOrNull()

    assertNotNull(ex)
    assertTrue("expected user-facing list limit message; got: ${ex?.message}",
        (ex?.message ?: "").contains("list limit", ignoreCase = true))
}
```

- [ ] **Step 3: Run failure, then implement, then re-run**

In `TraktLibraryService.addToCollection` (and `removeFromCollection`), branch on the `IntegrationCallResult.HttpError(420, ...)` outcome:

```kotlin
when (result) {
    is IntegrationCallResult.HttpError -> if (result.statusCode == 420) {
        collectionMembership.value = previous
        throw TraktListLimitException("Trakt list limit reached. Upgrade required.")
    } else {
        collectionMembership.value = previous
        throw IntegrationMutationFailedException("addToCollection failed: $result")
    }
    is IntegrationCallResult.Success -> { /* confirmed */ }
    else -> {
        collectionMembership.value = previous
        throw IntegrationMutationFailedException("addToCollection failed: $result")
    }
}
```

If `TraktListLimitException` doesn't exist, define it next to the service.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt \
        app/src/test/java/com/nexio/tv/data/repository/TraktLibraryServiceCollectionTest.kt
git commit -m "$(cat <<'EOF'
feat(trakt): surface 420 list-limit error on collection mutations

Trakt returns HTTP 420 when a free-tier user exceeds the list
size limit. Translate it to a user-facing message instead of a
generic mutation failure.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: UI surface — collection toggle in detail screen

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt` — add `isInCollection: Boolean = false`.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` — observe `isInCollection`, expose `toggleCollection()`.
- (Optional) `MetaDetailsScreen.kt` — render the toggle.

- [ ] **Step 1: Add state field**

In `MetaDetailsUiState.kt`:

```kotlin
data class MetaDetailsUiState(
    // ... existing fields
    val isInCollection: Boolean = false
)
```

- [ ] **Step 2: Wire the flow in the VM**

In `MetaDetailsViewModel.kt` (find `observeWatchlist` for the pattern):

```kotlin
private fun observeCollection() {
    viewModelScope.launch {
        effectiveContentId.flatMapLatest { contentId ->
            traktLibraryService.isInCollection(contentId)
        }
            .distinctUntilChanged()
            .collectLatest { inCollection ->
                _uiState.update { state ->
                    if (state.isInCollection == inCollection) state else state.copy(isInCollection = inCollection)
                }
            }
    }
}

fun toggleCollection() {
    viewModelScope.launch {
        runCatching {
            if (_uiState.value.isInCollection) {
                traktLibraryService.removeFromCollection(
                    contentId = effectiveContentId.value,
                    contentType = itemType
                )
            } else {
                traktLibraryService.addToCollection(
                    contentId = effectiveContentId.value,
                    contentType = itemType
                )
            }
        }.onFailure { throwable ->
            // Surface via the existing error channel — match how addToWatchlist failures surface.
            errorMessages.emit(throwable.message ?: "Collection update failed")
        }
    }
}
```

Call `observeCollection()` from `init` next to `observeWatchlist()`.

- [ ] **Step 3: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin -x generateIntegrationRuntimeAudit
```

- [ ] **Step 4: Optionally render the toggle button**

If you have time, add a "Collection" toggle to `MetaDetailsScreen.kt` next to the watchlist button. If not, leave it for a follow-up commit — the VM API is what counts for this plan.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsUiState.kt \
        app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
git commit -m "$(cat <<'EOF'
feat(detail): expose collection toggle in MetaDetailsViewModel

isInCollection observed from TraktLibraryService; toggleCollection
adds or removes the current item via the optimistic mutation flow.
UI rendering of the toggle button can land in a follow-up.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Final verification

**Files:** none.

- [ ] **Step 1: Full test surface**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  --tests "com.nexio.tv.data.integration.trakt.*" \
  --tests "com.nexio.tv.data.local.integration.*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Smoke-test on device**

If a real device is available, install:

```bash
./gradlew :app:installDebug
```

Open a movie detail screen for a known-collected item. Verify the collection badge appears. Toggle off; verify it disappears optimistically and stays off after a manual refresh. If no device is available, document that the UI was not validated.

- [ ] **Step 3: No commit (verification only)**

---

## Self-Review

**Spec coverage (Recommendation 3):**
- DTOs (Task 2). ✅
- API endpoints (Task 3). ✅
- Provider read methods + invalidate hook (Task 4). ✅
- Provider write methods (Task 5). ✅
- LibraryService projection + isInCollection (Task 6). ✅
- LibraryService write with optimistic + rollback (Task 7). ✅
- Outbox adapter (Task 8). ✅
- 420 list-limit error surfacing (Task 9). ✅
- UI surface (Task 10). ✅

**Placeholder scan:** clean. Each step shows the actual code; the few "if X exists, mirror it; if not, define it" branches are explicit about both paths.

**Type consistency:** `TraktCollectionKind`, `getCollectionMovies`, `getCollectionShows`, `invalidateCollectionSnapshot`, `addToCollection`, `removeFromCollection` referenced consistently across Tasks 4-10. DTO names (`TraktCollectionMovieItemDto`, `TraktCollectionShowItemDto`, `TraktCollectionAddRequestDto`, `TraktCollectionRemoveRequestDto`, `TraktCollectionWriteResponseDto`) used consistently from Task 2 forward. `COLLECTION_SNAPSHOT_TTL_MS` matches the `WATCHED_SNAPSHOT_TTL_MS` constant pattern.
