I would build this as a **central integration runtime** rather than separate provider repositories. Every metadata call, image call, rail refresh, hydration job, and prefetch job passes through one gateway.

The shape would be:

```text
App / ViewModels / Playback / Catalog UI
        |
        v
IntegrationHub
        |
        +-- CacheStore
        |     +-- Room index: freshness, ownership, rail membership, identities
        |     +-- Disk blobs: JSON, posters, thumbnails, backdrops
        |
        +-- ProviderLanes
        |     +-- MDBList lane       concurrency = 1
        |     +-- SIMKL lane         concurrency = 1
        |     +-- TRAKT lane         concurrency = 1
        |     +-- TMDB lane          concurrency = 1
        |     +-- TVDB lane          concurrency = 1
        |     +-- KITSU lane         concurrency = 1
        |     +-- RPDB lane          concurrency = 1
        |     +-- TOP-POSTERS lane   concurrency = 1
        |
        +-- RailStore
        |     +-- cached discovery rails
        |     +-- rail item membership
        |     +-- item reference counts
        |
        +-- HydrationPlanner
        |     +-- hydrate active rails
        |     +-- prefetch missing / near-expired data
        |
        +-- EvictionManager
              +-- TTL eviction
              +-- rail-aware orphan cleanup
              +-- disk budget cleanup
```

The most important invariant remains:

```kotlin
fresh disk cache is checked before provider queue, rate limiter, or network code
```

So a fresh item does **not** wait behind other provider work and does **not** consume the provider’s single connection.

---

## The central object: `IntegrationHub`

Everything should go through one public interface:

```kotlin
interface IntegrationHub {
    suspend fun <T> get(
        spec: IntegrationSpec<T>,
        options: FetchOptions = FetchOptions.Normal
    ): FetchResult<T>

    fun prefetch(
        specs: List<IntegrationSpec<*>>,
        reason: PrefetchReason
    )

    suspend fun refreshRail(
        railSpec: RailSpec,
        options: RailRefreshOptions = RailRefreshOptions.Normal
    ): RailResult

    fun pauseNetwork(reason: PauseReason)

    fun resumeNetwork(reason: PauseReason)

    fun setPlaybackActive(active: Boolean)
}
```

Your app should not call `TmdbClient`, `TraktClient`, `RpdbClient`, or Coil remote URLs directly. Provider modules should only be allowed to create **request specs**.

For example:

```kotlin
val spec = tmdbSpecs.movieDetails(
    tmdbId = 550,
    language = "en-US",
    append = listOf("credits", "images", "videos")
)

val result = integrationHub.get(spec)
```

Not:

```kotlin
tmdbApi.getMovieDetails(...)
```

That separation is what makes the architecture reusable.

---

## Core request model

Each provider request becomes a normalized `IntegrationSpec`.

```kotlin
data class IntegrationSpec<T>(
    val provider: Provider,
    val kind: ResourceKind,
    val identity: String,
    val params: Map<String, String> = emptyMap(),

    /**
     * The canonical item this request belongs to, if known.
     * Example: movie:imdb:tt0137523, show:tvdb:12345, anime:kitsu:1
     */
    val mediaKey: MediaKey? = null,

    /**
     * Who owns this data?
     * Rail-scoped cache can be cleaned when no active rails reference it.
     * User-scoped or pinned data should survive rail cleanup.
     */
    val retention: RetentionClass = RetentionClass.RailScoped,

    val ttl: CacheTtl,
    val decoder: CacheDecoder<T>,
    val requestFactory: suspend ProviderRequestContext.() -> NetworkRequest
)
```

Example:

```kotlin
val fightClubPosterSpec = IntegrationSpec<File>(
    provider = Provider.RPDB,
    kind = ResourceKind.POSTER_IMAGE,
    identity = "imdb:tt0137523",
    params = mapOf(
        "style" to "poster-default",
        "size" to "w500",
        "ratings" to "imdb,rt,metacritic"
    ),
    mediaKey = MediaKey.Movie.Imdb("tt0137523"),
    retention = RetentionClass.RailScoped,
    ttl = CacheTtl(hours = 12),
    decoder = FileDecoder,
    requestFactory = {
        NetworkRequest.Get(url = rpdbPosterUrl(...))
    }
)
```

The cache key is derived from:

```text
provider
resource kind
identity
canonical params
schema version
account scope if relevant
```

Never include API keys or bearer tokens in the cache key.

---

## Fetch flow

The fetch pipeline should look like this:

```text
IntegrationHub.get(spec)
    |
    |-- 1. Compute cache key
    |
    |-- 2. Read fresh cache
    |       |
    |       +-- fresh hit → return immediately from disk
    |
    |-- 3. If network paused / cache-only mode
    |       |
    |       +-- return stale cache if allowed
    |       +-- otherwise return Pending / CacheMiss
    |
    |-- 4. Deduplicate same in-flight cache key
    |
    |-- 5. Enqueue into provider lane
    |
    |-- 6. Provider lane checks cache again
    |
    |-- 7. Provider lane performs one network call at a time
    |
    |-- 8. Write blob + metadata transactionally
    |
    |-- 9. Return decoded result
```

The second cache check inside the provider lane matters. Another coroutine may have already refreshed the same item while this request was waiting.

```kotlin
class DefaultIntegrationHub(
    private val cache: CacheStore,
    private val lanes: Map<Provider, ProviderLane>,
    private val singleFlight: SingleFlight,
    private val networkGate: NetworkGate
) : IntegrationHub {

    override suspend fun <T> get(
        spec: IntegrationSpec<T>,
        options: FetchOptions
    ): FetchResult<T> {
        val key = CacheKey.from(spec)

        cache.readFresh(key, spec.decoder)?.let { fresh ->
            return FetchResult.Fresh(fresh)
        }

        if (options.cacheOnly || networkGate.isPausedFor(options.workClass)) {
            val stale = cache.readStale(key, spec.decoder)
            return if (stale != null && options.allowStale) {
                FetchResult.Stale(stale)
            } else {
                FetchResult.Pending(key)
            }
        }

        return singleFlight.run(key) {
            cache.readFresh(key, spec.decoder)?.let { fresh ->
                return@run FetchResult.Fresh(fresh)
            }

            lanes.getValue(spec.provider).execute(spec, options)
        }
    }
}
```

Room is a good fit for the structured index because you need indexed, queryable relationships between cache entries, rails, identities, TTLs, ownership, and media items. Android’s Room library is an abstraction over SQLite, and DAOs give you generated access methods for database operations. ([Android Developers][1])

For strict TTL guarantees, store the important cache blobs in app-private persistent storage, not only `cacheDir`. Android may delete files in `cacheDir` when storage is low, so those files should always be treated as disposable. ([Android Developers][2])

---

## Provider lanes: one service, one active network call

Each provider gets a serialized lane.

```kotlin
class ProviderLane(
    private val provider: Provider,
    private val cache: CacheStore,
    private val network: NetworkExecutor,
    private val rateLimiter: ProviderRateLimiter,
    private val networkGate: NetworkGate
) {
    suspend fun <T> execute(
        spec: IntegrationSpec<T>,
        options: FetchOptions
    ): FetchResult<T> {
        // This function is internally serialized per provider.
        return mutex.withLock {
            val key = CacheKey.from(spec)

            cache.readFresh(key, spec.decoder)?.let {
                return@withLock FetchResult.Fresh(it)
            }

            networkGate.awaitAllowed(options.workClass)

            rateLimiter.awaitPermit(provider)

            val request = spec.requestFactory(
                ProviderRequestContext(provider = provider)
            )

            val response = network.execute(provider, request)

            val stored = cache.write(
                key = key,
                spec = spec,
                response = response
            )

            FetchResult.Updated(spec.decoder.decode(stored))
        }
    }
}
```

In production, I would probably use an actor/queue per provider rather than a bare mutex, because queues make prioritization, cancellation, pause behavior, and prefetch handling easier. Kotlin’s coroutine `Mutex` does provide mutual exclusion, but a provider lane as an actor gives you a cleaner operational model. ([Kotlin][3])

The lane should support priorities:

```kotlin
enum class WorkClass {
    UserVisible,
    PlaybackCritical,
    BackgroundHydration,
    Prefetch,
    Maintenance
}
```

A good priority order:

```text
1. UserVisible cache miss
2. UserVisible refresh
3. BackgroundHydration for visible rails
4. Prefetch
5. Maintenance cleanup / warmups
```

Fresh cache hits bypass all of this.

---

## Pause behavior during playback

During playback, I would not pause cache reads. I would pause:

```text
network starts
prefetch
background rail hydration
cleanup that does heavy disk I/O
non-urgent refreshes
```

So playback mode becomes:

```kotlin
integrationHub.setPlaybackActive(true)
```

Internally:

```kotlin
class NetworkGate {
    private val pauseReasons = MutableStateFlow<Set<PauseReason>>(emptySet())

    fun pause(reason: PauseReason) {
        pauseReasons.update { it + reason }
    }

    fun resume(reason: PauseReason) {
        pauseReasons.update { it - reason }
    }

    fun isPausedFor(workClass: WorkClass): Boolean {
        val paused = pauseReasons.value.isNotEmpty()

        return paused && when (workClass) {
            WorkClass.PlaybackCritical -> false
            WorkClass.UserVisible -> true // or configurable
            WorkClass.BackgroundHydration -> true
            WorkClass.Prefetch -> true
            WorkClass.Maintenance -> true
        }
    }

    suspend fun awaitAllowed(workClass: WorkClass) {
        pauseReasons
            .filter { reasons -> !isPausedFor(workClass) }
            .first()
    }
}
```

You probably want this policy:

| Situation                                 | Behavior                                                         |
| ----------------------------------------- | ---------------------------------------------------------------- |
| Fresh cache exists                        | Return from disk immediately                                     |
| Stale cache exists and playback is active | Return stale if allowed                                          |
| No cache and playback is active           | Return `Pending` / placeholder                                   |
| Prefetch running and playback starts      | Cancel or defer                                                  |
| Provider call already in-flight           | Let user-visible calls finish; cancel low-priority calls if safe |
| Playback ends                             | Resume queued provider lanes                                     |

This keeps playback smooth without making the rest of the app weird.

---

## Rails should be first-class cached objects

Discovery rails should not be separate from the cache. They are API-backed cache objects with membership.

A rail is something like:

```text
TMDB popular movies, page 1, region BE, language en-US
TRAKT trending shows, limit 20
SIMKL anime trending
MDBList top rated horror movies
TOP-POSTERS recently generated posters
```

Model them as:

```kotlin
data class RailSpec(
    val railKey: RailKey,
    val provider: Provider,
    val kind: RailKind,
    val params: Map<String, String>,
    val ttl: CacheTtl,
    val hydrationProfile: HydrationProfile
)
```

The rail response itself is cached, but you also store normalized membership.

```text
rail
----
rail_key
provider
kind
params_hash
fetched_at
expires_at
stale_until
revision
title
priority

rail_item
---------
rail_key
media_key
position
revision
added_at
visible_until
```

A rail item points to a canonical `media_key`, not merely to a provider ID.

```text
media_item
----------
media_key
media_type
title
year
canonical_status
created_at
last_seen_at

external_id
-----------
media_key
provider
external_id
id_type
```

This is necessary because the same movie may appear as:

```text
TMDB movie 550
IMDb tt0137523
Trakt fight-club-1999
TVDB movie ID
RPDB poster by IMDb ID
MDBList rating by IMDb ID
```

Without a canonical identity layer, your cleanup logic will accidentally delete useful data because it does not realize two providers are talking about the same item.

---

## The rail-aware cache ownership model

Add ownership to cache entries.

```text
cache_entry
-----------
cache_key
provider
kind
media_key nullable
rail_key nullable
retention_class
blob_path
mime_type
fetched_at
expires_at
stale_until
last_accessed_at
byte_count
etag
last_modified

cache_owner
-----------
cache_key
owner_type
owner_key
```

Retention classes:

```kotlin
enum class RetentionClass {
    RailScoped,     // can be removed when no active rail references the media item
    UserScoped,     // watchlist, history, collection, account-specific
    Pinned,         // explicitly kept
    GlobalReusable, // provider config, genre maps, image config
    EphemeralSearch // search results, temporary discovery
}
```

Examples:

| Cached thing                          | Retention                                        |
| ------------------------------------- | ------------------------------------------------ |
| TMDB image config                     | `GlobalReusable`                                 |
| TMDB movie summary from a home rail   | `RailScoped`                                     |
| RPDB poster generated for a rail item | `RailScoped`                                     |
| Trakt watched state                   | `UserScoped`                                     |
| User’s collection                     | `UserScoped`                                     |
| Search results                        | `EphemeralSearch`                                |
| Movie detail page opened by user      | maybe `Pinned` temporarily or `UserScopedRecent` |

The ownership rule:

```text
An item-level cache entry may be deleted only when:
    its TTL policy allows deletion
    AND it is rail-scoped
    AND no active/stale-visible rail references its mediaKey
    AND it is not user-scoped, pinned, recently viewed, or currently displayed
```

---

## Rail refresh and dropped-item cleanup

When a rail expires, refresh it through the same central hub.

```text
Rail TTL expired
    |
    v
IntegrationHub.refreshRail(railSpec)
    |
    |-- fresh rail cache? return existing rail
    |
    |-- network paused? return stale rail if allowed
    |
    |-- enqueue rail refresh in provider lane
    |
    |-- provider returns new rail items
    |
    |-- normalize provider IDs to media keys
    |
    |-- transaction:
    |       oldItems = previous rail membership
    |       newItems = new membership
    |       dropped = oldItems - newItems
    |       upsert rail snapshot
    |       replace rail membership
    |       mark dropped items for orphan check
    |
    |-- after transaction:
            cleanup dropped items if no other active rail references them
```

Pseudo-code:

```kotlin
suspend fun commitRailRefresh(
    railSpec: RailSpec,
    newItems: List<RailItemCandidate>
) {
    val dropped: Set<MediaKey> = database.withTransaction {
        val oldMediaKeys = railDao.mediaKeysForRail(railSpec.railKey).toSet()
        val normalizedNewItems = identityResolver.resolve(newItems)
        val newMediaKeys = normalizedNewItems.map { it.mediaKey }.toSet()

        railDao.upsertRailSnapshot(
            railKey = railSpec.railKey,
            expiresAt = now + railSpec.ttl.duration,
            staleUntil = now + railSpec.ttl.duration + railSpec.ttl.staleIfError,
            revision = nextRevision()
        )

        railDao.replaceRailItems(
            railKey = railSpec.railKey,
            items = normalizedNewItems
        )

        oldMediaKeys - newMediaKeys
    }

    evictionManager.cleanupDroppedRailItems(dropped)
}
```

Do not delete files inside the database transaction. Mark items for cleanup, commit the DB changes, then delete blobs afterward.

Cleanup:

```kotlin
suspend fun cleanupDroppedRailItems(dropped: Set<MediaKey>) {
    for (mediaKey in dropped) {
        val stillReferenced = railDao.hasActiveRailReference(
            mediaKey = mediaKey,
            now = clock.now()
        )

        val protected = mediaDao.hasProtection(
            mediaKey = mediaKey,
            protections = setOf(
                RetentionClass.UserScoped,
                RetentionClass.Pinned
            )
        )

        if (!stillReferenced && !protected) {
            cacheDao.markRailScopedEntriesForDeletion(mediaKey)
        }
    }

    blobStore.deleteMarkedEntries()
}
```

The active-reference query should include rails that are still displayable:

```sql
SELECT EXISTS(
    SELECT 1
    FROM rail_item ri
    JOIN rail r ON r.rail_key = ri.rail_key
    WHERE ri.media_key = :mediaKey
      AND r.stale_until > :now
)
```

Using `stale_until` rather than only `expires_at` prevents destructive cleanup when a rail has technically expired but is still being used because the provider is offline, paused, or rate-limited.

---

## Hydrating active rails

The central system can periodically ask:

```text
Which rails are still on disk and not expired?
Which items do those rails contain?
Which item summaries/posters/ratings/details are missing or near expiry?
```

That becomes a hydration plan.

```kotlin
class RailHydrationPlanner(
    private val railDao: RailDao,
    private val cacheDao: CacheDao,
    private val integrationHub: IntegrationHub
) {
    suspend fun hydrateActiveRails(
        maxItems: Int,
        horizon: Duration
    ) {
        val targets = railDao.itemsNeedingHydration(
            now = clock.now(),
            expiresBefore = clock.now() + horizon.inWholeMilliseconds,
            maxItems = maxItems
        )

        val specs = targets.flatMap { target ->
            hydrationSpecsFor(target)
        }

        integrationHub.prefetch(
            specs = specs,
            reason = PrefetchReason.ActiveRails
        )
    }
}
```

Hydration profiles keep it controlled.

```kotlin
enum class HydrationProfile {
    RailMinimal,       // title, year, poster
    RailRich,          // title, poster, rating, runtime, overview
    DetailReady,       // credits, backdrop, videos, seasons
    PosterOnly,
    RatingPosterOnly
}
```

Example:

```text
Home rail visible soon:
    hydrate summary + poster + rating

Detail page opened:
    hydrate full details + credits + backdrops + videos

Playback screen:
    no prefetch, cache reads only
```

Prefetch should never have special permission to bypass the cache. It should be just another low-priority request through `IntegrationHub`.

```kotlin
override fun prefetch(
    specs: List<IntegrationSpec<*>>,
    reason: PrefetchReason
) {
    specs.forEach { spec ->
        backgroundScope.launch {
            get(
                spec,
                FetchOptions(
                    workClass = WorkClass.Prefetch,
                    allowStale = false,
                    cacheOnly = false
                )
            )
        }
    }
}
```

For background scheduling outside the foreground process, use WorkManager for periodic cleanup, rail warmup, and constrained hydration. WorkManager supports deferrable work with constraints such as network availability, which fits this kind of non-urgent cache maintenance. ([Android Developers][4])

---

## Cache result types

You will want richer results than simply `T`.

```kotlin
sealed interface FetchResult<out T> {
    data class Fresh<T>(
        val value: T
    ) : FetchResult<T>

    data class Updated<T>(
        val value: T
    ) : FetchResult<T>

    data class Stale<T>(
        val value: T
    ) : FetchResult<T>

    data class Pending(
        val cacheKey: CacheKey
    ) : FetchResult<Nothing>

    data class Miss(
        val cacheKey: CacheKey
    ) : FetchResult<Nothing>

    data class Failed(
        val error: Throwable,
        val staleFallbackAvailable: Boolean
    ) : FetchResult<Nothing>
}
```

This makes playback behavior much cleaner. During playback, a missing network result can return `Pending`, while stale-but-usable data can return `Stale`.

---

## Putting it together with an example

Imagine the home screen has these rails:

```text
TRAKT trending movies
TMDB popular movies
MDBList top rated movies
SIMKL trending anime
TOP-POSTERS popular rating posters
```

The app starts.

```text
1. UI asks IntegrationHub for rail snapshots.
2. Fresh rail snapshots return from disk immediately.
3. Expired rail snapshots are queued through their provider lanes.
4. HydrationPlanner sees active rail items.
5. It schedules missing posters, summaries, and rating posters as low-priority prefetch.
6. Each provider still performs only one active network call at a time.
```

Then playback starts.

```text
1. Playback calls integrationHub.setPlaybackActive(true).
2. Cache reads continue.
3. Provider lanes stop starting new non-critical network calls.
4. Prefetch jobs are cancelled or deferred.
5. Heavy cleanup pauses.
```

Then playback ends.

```text
1. Playback calls integrationHub.setPlaybackActive(false).
2. Provider lanes resume.
3. HydrationPlanner continues with low-priority prefetch.
```

Then the `TRAKT trending movies` rail expires and refreshes.

```text
Old rail:
    A, B, C, D, E

New rail:
    B, C, F, G, H

Dropped from this rail:
    A, D, E
```

Cleanup does **not** immediately delete `A`, `D`, and `E`.

It asks:

```text
Does A still exist in another active rail?
Is A pinned?
Is A in user history, watchlist, collection, or recently viewed?
Is A currently displayed?
```

Only if all answers are no do you delete rail-scoped metadata, posters, rating posters, and thumbnails for that item.

---

## Suggested module layout

```text
:integration-core
    IntegrationHub
    CacheStore
    ProviderLane
    NetworkGate
    RailStore
    HydrationPlanner
    EvictionManager
    IdentityResolver

:integration-providers:tmdb
    TmdbSpecs
    TmdbResponseMappers

:integration-providers:trakt
    TraktSpecs
    TraktResponseMappers

:integration-providers:simkl
:integration-providers:tvdb
:integration-providers:kitsu
:integration-providers:mdblist
:integration-providers:rpdb
:integration-providers:topposters

:feature-home
    depends on IntegrationHub only

:feature-detail
    depends on IntegrationHub only

:feature-playback
    controls pause/resume on IntegrationHub
```

Provider modules should not expose direct HTTP clients. They should expose specs.

```kotlin
class TmdbSpecs {
    fun movieSummary(tmdbId: Int, language: String): IntegrationSpec<TmdbMovieSummary> {
        // returns spec, not network result
    }

    fun popularMovies(region: String, language: String): RailSpec {
        // returns rail spec
    }
}
```

That makes it very hard for future code to accidentally bypass the cache and rate-limited provider lanes.

---

## The governing rule

The whole system should obey this rule:

```text
Cache freshness and ownership are decided centrally.
Provider network execution is serialized centrally.
Rails are cached objects with membership.
Item data is retained only while something still owns it.
Playback can pause network and background work without breaking cache reads.
Prefetch is just low-priority cache-first work.
```

That gives you a clean way to support MDBList, SIMKL, TRAKT, TMDB, TVDB, KITSU, RPDB, and TOP-POSTERS without each integration inventing its own caching, throttling, prefetching, and cleanup behavior.

[1]: https://developer.android.com/training/data-storage/room?utm_source=chatgpt.com "Save data in a local database using Room"
[2]: https://developer.android.com/training/data-storage/app-specific?utm_source=chatgpt.com "Access app-specific files | App data and files"
[3]: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/?utm_source=chatgpt.com "Mutex | kotlinx.coroutines"
[4]: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work?utm_source=chatgpt.com "Define work requests | Background work"

