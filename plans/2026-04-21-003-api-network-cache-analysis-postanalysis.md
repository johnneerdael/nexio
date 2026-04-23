Based on your audit, the central issue is not that the app lacks caching or throttling. It has quite a lot of both. The problem is that they are **distributed, inconsistent, and hard to reason about globally**.

Right now Nexio has several separate systems making independent decisions:

```text
OkHttp HTTP cache
Coil image cache
MetadataDiskCacheStore
provider-specific snapshot stores
provider-specific in-memory maps
Trakt/Simkl request gates
home-only playback gate
WorkManager jobs
outbox drains
ViewModel-level direct calls
```

That makes the current implementation functional, but fragile. It is difficult to guarantee things like:

```text
fresh cached data never hits the network
only one request per provider at a time
playback pauses all non-critical provider work
rail eviction removes orphaned data safely
new providers follow the same rules automatically
```

The improvement should be a **central metadata integration runtime** that all providers must pass through.

---

# 1. Main problems in the current implementation

## 1.1 There is no single network authority

Today, Trakt and Simkl are serialized through gates, but TMDB, TVDB, Kitsu, MDBList, RPDB, and TopPosters are not. Some calls are made from services, some from repositories, some from ViewModels, some indirectly through Coil.

That creates several escape paths:

```text
MetaDetailsViewModel → Trakt comments directly
KitsuMetadataService → raw Retrofit/default OkHttp
TMDB/TVDB enrichment → parallel fan-out
MDBList ratings → repository-local semaphore
RPDB/TopPosters → URL passed to Coil, outside provider control
TVDB update worker → independent background network path
```

So even if you introduce a better cache later, you cannot enforce it unless every integration call has to pass through one runtime.

**Problem:** provider behavior is decided by call site rather than by provider policy.

**Improvement:** create a central `IntegrationHub` / `MetadataRuntime`, and make provider modules expose request specifications instead of exposing Retrofit services directly.

---

## 1.2 Cache behavior is fragmented

You currently have at least five cache families:

```text
OkHttp 50 MB HTTP cache
Coil image cache
MetadataDiskCacheStore
provider snapshot stores
provider in-memory maps
```

Each has different TTL semantics.

Examples:

| Area              | Current issue                                                                                                                                   |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| TMDB              | Some data has `MetadataDiskCacheStore` TTLs, but many in-memory maps are process-lifetime with no TTL.                                          |
| TVDB              | Disk cache exists, but authenticated HTTP cache is limited by `Authorization` / `Vary`; update invalidation may not cover all memory-held data. |
| Kitsu             | No app-level cache above OkHttp. Reopening screens depends on server HTTP cache behavior.                                                       |
| MDBList           | Discovery has disk snapshots, ratings are mostly repository memory TTLs.                                                                        |
| Trakt comments    | Direct network call from ViewModel, no cache.                                                                                                   |
| RPDB / TopPosters | Cached by Coil, not by provider-aware metadata cache.                                                                                           |
| Rails             | Cached as snapshots, but not clearly tied to item-level ownership and eviction.                                                                 |

**Problem:** there is no central cache index that can answer:

```text
What cached data belongs to this media item?
Which rail owns this item?
Is this item still referenced by another rail?
Is this data fresh, stale-usable, user-scoped, pinned, or disposable?
```

**Improvement:** introduce a unified cache index, probably Room-backed, with disk blobs for payloads and images.

---

## 1.3 Fresh cache does not universally mean “no network”

This is the most important behavioral gap.

Some paths can already serve disk-first, but others rely on OkHttp or Coil. OkHttp may validate, bypass, or not cache depending on headers. Coil has its own policy. Some providers disable HTTP cache entirely. Some repositories use memory caches but not disk.

That means you do not yet have a universal invariant:

```kotlin
if cacheEntry.expiresAt > now && blobExists:
    return from disk
    do not enter provider queue
    do not touch OkHttp
    do not touch Retrofit
    do not touch Coil remote fetch
```

**Problem:** cache-first behavior is local, not global.

**Improvement:** centralize the cache check before scheduling, rate limiting, provider gates, or network execution.

The central fetch path should be:

```text
IntegrationHub.get(spec)
    ↓
compute cache key
    ↓
read fresh disk cache
    ↓
fresh hit? return immediately
    ↓
not fresh? enter single-flight
    ↓
check fresh cache again
    ↓
enter provider lane
    ↓
provider lane performs one network request at a time
    ↓
write cache blob + metadata
    ↓
return result
```

Fresh cache hits should not even wait behind a serialized provider queue.

---

## 1.4 Playback gating is too narrow

Your current `HomePlaybackWorkGate` cancels home-screen enrichment work, which is useful, but it is not a provider-level gate.

It does not cover:

```text
TVDB update worker
MDBList discovery refresh
detail-screen metadata calls
Kitsu direct calls
TMDB/TVDB calls from non-home paths
outbox drains
some background refresh paths
```

Some of those should continue during playback, especially scrobbles/checkins. But the decision should be centralized and explicit.

**Problem:** playback behavior is decided by feature area, not by work type.

**Improvement:** introduce a central `NetworkGate` with work classes:

```kotlin
enum class WorkClass {
    UserVisible,
    PlaybackCritical,
    Scrobble,
    MutationOutbox,
    BackgroundHydration,
    Prefetch,
    Maintenance
}
```

During playback:

```text
fresh cache reads continue
scrobbles/checkins continue
critical user-visible misses may be allowed or degraded
background hydration pauses
prefetch pauses
TVDB update worker pauses or defers
heavy cleanup pauses
```

---

## 1.5 Provider concurrency rules are inconsistent

Trakt and Simkl have strong 500 ms serial gates. Others rely on OkHttp’s default dispatcher, local semaphores, or nothing.

This makes rate limiting hard to reason about.

Current state:

```text
Trakt      serialized
Simkl      serialized
TMDB       parallel up to dispatcher limits
TVDB       parallel up to dispatcher limits
Kitsu      parallel up to dispatcher limits
MDBList    partial repository semaphores
RPDB       Coil-driven
TopPosters Coil-driven
```

If your desired architecture is “one service, one active connection at a time,” then this should be a provider policy, not scattered semaphores.

**Improvement:** introduce provider lanes:

```kotlin
data class ProviderLaneConfig(
    val provider: Provider,
    val maxConcurrentNetworkCalls: Int = 1,
    val minDelayBetweenStartsMs: Long = 0,
    val allowDuringPlayback: Boolean = false,
    val allowScrobbleDuringPlayback: Boolean = true
)
```

For now, set all metadata providers to `maxConcurrentNetworkCalls = 1`.

You can later loosen TMDB or TVDB safely by config, without changing service code.

---

## 1.6 Boot fan-out is not centrally scheduled

TVDB update catch-up runs on app start. Home observers then start Trakt, Simkl, MDBList, enrichment, and potentially TMDB/Kitsu/TVDB chains.

You have some good local protection, such as the 20-second startup refresh gate for Trakt and MDBList, but boot is still not globally coordinated.

**Problem:** boot behavior emerges from many initializers.

**Improvement:** centralize boot scheduling:

```text
App start
    ↓
restore cache indexes and snapshots
    ↓
allow UI cache reads immediately
    ↓
delay non-critical refreshes
    ↓
schedule provider work by priority
    ↓
respect playback/network/battery constraints
```

TVDB update catch-up should become a `Maintenance` or `BackgroundHydration` task inside the central scheduler, not an unconditional app-start network task.

---

## 1.7 Rails are cached, but not yet true ownership roots

Your audit mentions discovery snapshot stores, catalog disk cache, and MDBList/Trakt rail snapshots. But the next architectural step is to make rails first-class ownership objects.

A rail is not just a cached response. It is a reason to keep item-level data.

Example:

```text
TMDB popular movies rail
    owns movie A, B, C

MDBList top horror rail
    owns movie C, D, E
```

If `TMDB popular movies` refreshes and drops movie `C`, you must not delete `C` if it still exists in `MDBList top horror`.

So you need this relationship:

```text
rail → rail_item → media_item → cache_entry
```

**Problem:** without ownership tracking, cleanup is either too conservative or dangerously destructive.

**Improvement:** introduce rail membership and reference-aware eviction.

---

## 1.8 Simkl riding on `TraktMutationOutboxCoordinator` is a naming and extension smell

It may work technically, but it will confuse anyone extending the system.

If Simkl, Trakt, and future providers all use mutation outboxes, then the abstraction should be provider-neutral.

**Current smell:**

```text
SimklScrobbleService → TraktMutationOutboxCoordinator
SimklLibraryService  → TraktMutationOutboxCoordinator
```

**Improvement:**

```text
MutationOutboxCoordinator
    ├── TraktMutationAdapter
    ├── SimklMutationAdapter
    └── future provider adapters
```

This is especially important if you later add more account-based providers.

---

## 1.9 RPDB / TopPosters are outside the provider runtime

Right now, RPDB and TopPosters mostly become poster URLs that Coil fetches.

That means:

```text
provider cache policy does not own the image bytes
provider lane does not control concurrency
provider TTL does not control freshness
provider pause does not control remote image fetches
rail-aware eviction cannot safely clean generated poster variants
```

**Improvement:** do not pass remote RPDB/TopPosters URLs directly to UI as the final image model.

Instead:

```text
PosterRatingsUrlResolver creates an IntegrationSpec
IntegrationHub resolves it to a local File
Coil receives the local File
```

Or implement a custom Coil fetcher that internally calls `IntegrationHub`.

Either way, Coil should decode/display the image, not independently decide whether to go to the network.

---

## 1.10 TopPosters / top-streaming base URL drift needs fixing

Your audit found:

```text
production code: https://api.top-posters.com/
audit target:     api.top-streaming.stream
tests only:       top-streaming.stream
```

That is the kind of inconsistency that becomes painful once a cache key contains provider identity.

Before centralizing the cache, decide whether these are:

```text
same provider with changed base URL
two different providers
user-configurable endpoint
test fixture residue
```

The provider identity and cache key should not change accidentally because of a hostname alias.

---

# 2. Target architecture

I would move toward this:

```text
UI / ViewModels / Workers / Playback
        |
        v
MetadataRuntime / IntegrationHub
        |
        +-- CacheStore
        |     +-- Room cache index
        |     +-- disk blobs for JSON/images
        |     +-- TTL policy registry
        |
        +-- ProviderScheduler
        |     +-- provider lane: Trakt
        |     +-- provider lane: Simkl
        |     +-- provider lane: TMDB
        |     +-- provider lane: TVDB
        |     +-- provider lane: Kitsu
        |     +-- provider lane: MDBList
        |     +-- provider lane: RPDB
        |     +-- provider lane: TopPosters
        |
        +-- NetworkGate
        |     +-- playback pause
        |     +-- boot dampening
        |     +-- credential health
        |     +-- user-visible vs prefetch priority
        |
        +-- RailStore
        |     +-- rail snapshots
        |     +-- rail membership
        |     +-- active/stale rail ownership
        |
        +-- IdentityStore
        |     +-- canonical media keys
        |     +-- provider IDs
        |
        +-- HydrationPlanner
        |     +-- hydrate active rails
        |     +-- prefetch near-expired items
        |
        +-- EvictionManager
              +-- TTL eviction
              +-- rail-aware orphan cleanup
              +-- disk budget cleanup
```

The important design rule:

```text
Provider modules describe requests.
The runtime executes requests.
```

Provider modules should not own scheduling, playback policy, or disk cache policy.

---

# 3. Central request model

Every provider operation should become an `IntegrationSpec`.

```kotlin
data class IntegrationSpec<T>(
    val provider: Provider,
    val kind: ResourceKind,
    val identity: String,
    val params: Map<String, String> = emptyMap(),

    val mediaKey: MediaKey? = null,
    val accountScope: AccountScope? = null,

    val ttlPolicy: TtlPolicy,
    val retention: RetentionClass,

    val workClass: WorkClass = WorkClass.UserVisible,

    val decoder: CacheDecoder<T>,
    val networkRequest: suspend ProviderRequestContext.() -> NetworkRequest
)
```

Example:

```kotlin
tmdbSpecs.movieDetails(
    tmdbId = 550,
    language = "en-US",
    append = listOf("credits", "images", "videos")
)
```

would produce:

```kotlin
IntegrationSpec<TmdbMovieDetails>(
    provider = Provider.TMDB,
    kind = ResourceKind.MovieDetails,
    identity = "movie/550",
    params = mapOf(
        "language" to "en-US",
        "append" to "credits,images,videos"
    ),
    mediaKey = MediaKey.Movie.Tmdb(550),
    ttlPolicy = TtlPolicy.Fixed(days = 7),
    retention = RetentionClass.RailScoped,
    decoder = JsonDecoder(TmdbMovieDetails.serializer()),
    networkRequest = {
        NetworkRequest.Get("/movie/550", query = params)
    }
)
```

Then the caller does:

```kotlin
val result = integrationHub.get(spec)
```

not:

```kotlin
tmdbApi.getMovie(...)
```

That is the extensibility win.

---

# 4. Central fetch algorithm

The central fetch path should be strict:

```kotlin
suspend fun <T> get(spec: IntegrationSpec<T>, options: FetchOptions): FetchResult<T> {
    val key = CacheKey.from(spec)

    // 1. Fresh cache bypasses everything.
    cache.readFresh(key, spec.decoder)?.let {
        return FetchResult.Fresh(it)
    }

    // 2. If paused or cache-only, do not start network.
    if (networkGate.isBlocked(spec.workClass, options)) {
        val stale = cache.readStale(key, spec.decoder)
        return if (stale != null && options.allowStale) {
            FetchResult.Stale(stale)
        } else {
            FetchResult.Pending(key)
        }
    }

    // 3. Deduplicate identical cache misses.
    return singleFlight.run(key) {
        // 4. Check again after waiting.
        cache.readFresh(key, spec.decoder)?.let {
            return@run FetchResult.Fresh(it)
        }

        // 5. Network work enters the provider lane.
        providerScheduler.execute(spec, options)
    }
}
```

Inside the provider lane:

```kotlin
suspend fun <T> execute(spec: IntegrationSpec<T>, options: FetchOptions): FetchResult<T> {
    return laneMutex.withLock {
        val key = CacheKey.from(spec)

        cache.readFresh(key, spec.decoder)?.let {
            return@withLock FetchResult.Fresh(it)
        }

        networkGate.awaitAllowed(spec.workClass)
        credentialGate.awaitAllowed(spec.provider)
        rateLimiter.awaitPermit(spec.provider)

        val response = networkExecutor.execute(spec)

        val stored = cache.write(
            key = key,
            spec = spec,
            response = response
        )

        FetchResult.Updated(spec.decoder.decode(stored))
    }
}
```

This gives you the invariant you want:

```text
fresh cache never reaches network code
fresh cache never waits behind provider serialization
fresh cache never consumes a rate-limit slot
fresh cache never gets blocked by playback pause
```

---

# 5. Provider lanes

Instead of Trakt and Simkl having special gates, every provider should have a lane.

```kotlin
data class ProviderLaneConfig(
    val provider: Provider,
    val maxConcurrent: Int = 1,
    val minStartGapMs: Long = 0,
    val queuePolicy: QueuePolicy = QueuePolicy.Priority,
    val defaultTimeoutMs: Long = 30_000
)
```

Suggested initial config:

| Provider   | Concurrency |  Start gap | Notes                                        |
| ---------- | ----------: | ---------: | -------------------------------------------- |
| Trakt      |           1 |     500 ms | Move existing gate here.                     |
| Simkl      |           1 |     500 ms | Move existing gate here.                     |
| TMDB       |           1 |   0–250 ms | Conservative initially; can raise later.     |
| TVDB       |           1 | 250–500 ms | Especially useful around updates/enrichment. |
| Kitsu      |           1 |     250 ms | Fixes current unbounded parallelism.         |
| MDBList    |           1 |     500 ms | Replaces repository semaphores for network.  |
| RPDB       |           1 |   0–250 ms | Image bytes should pass through runtime.     |
| TopPosters |           1 |   0–250 ms | Same as RPDB.                                |

The lane should accept prioritized work:

```kotlin
enum class Priority {
    NowVisible,
    UserAction,
    PlaybackCritical,
    Scrobble,
    BackgroundRefresh,
    Prefetch,
    Maintenance
}
```

This lets you do:

```text
detail screen cache miss > home rail hydration > prefetch > maintenance
```

---

# 6. Unified cache model

You need one cache index that covers JSON, snapshots, posters, thumbnails, generated rating posters, rail responses, and item details.

A reasonable Room schema:

```text
cache_entry
-----------
cache_key
provider
kind
identity
params_hash
media_key nullable
account_scope nullable
retention_class
blob_path
mime_type
fetched_at
expires_at
stale_until
last_accessed_at
byte_count
etag nullable
last_modified nullable
schema_version
negative_cache boolean

cache_owner
-----------
cache_key
owner_type
owner_key

rail
----
rail_key
provider
kind
params_hash
title
fetched_at
expires_at
stale_until
revision
retention_class

rail_item
---------
rail_key
media_key
position
revision
added_at

media_item
----------
media_key
media_type
title nullable
year nullable
last_seen_at
pinned boolean

external_id
-----------
media_key
provider
id_type
external_id
```

Retention classes:

```kotlin
enum class RetentionClass {
    RailScoped,
    UserScoped,
    RecentlyViewed,
    Pinned,
    GlobalReusable,
    EphemeralSearch
}
```

Examples:

| Data                                     | Retention         |
| ---------------------------------------- | ----------------- |
| TMDB movie summary from a discovery rail | `RailScoped`      |
| TMDB image config                        | `GlobalReusable`  |
| TVDB reference data                      | `GlobalReusable`  |
| Trakt watched state                      | `UserScoped`      |
| Simkl playback state                     | `UserScoped`      |
| RPDB generated poster for a rail item    | `RailScoped`      |
| Detail page opened by user               | `RecentlyViewed`  |
| Search results                           | `EphemeralSearch` |

---

# 7. Rail-aware eviction

This is directly related to your concern about discovery rails.

The rail refresh process should be transactional.

```text
refresh rail
    ↓
get old rail membership
    ↓
fetch new rail response
    ↓
normalize items to media keys
    ↓
transaction:
        replace rail snapshot
        replace rail_item rows
        compute dropped media keys
    ↓
after transaction:
        cleanup dropped media keys if no longer owned
```

Pseudo-code:

```kotlin
suspend fun commitRailRefresh(
    railSpec: RailSpec,
    newItems: List<RailItemCandidate>
) {
    val dropped = database.withTransaction {
        val oldKeys = railDao.mediaKeysForRail(railSpec.railKey).toSet()

        val normalized = identityResolver.resolve(newItems)
        val newKeys = normalized.map { it.mediaKey }.toSet()

        railDao.upsertRail(
            railKey = railSpec.railKey,
            fetchedAt = now(),
            expiresAt = now() + railSpec.ttl,
            staleUntil = now() + railSpec.ttl + railSpec.staleIfError,
            revision = nextRevision()
        )

        railDao.replaceItems(
            railKey = railSpec.railKey,
            items = normalized
        )

        oldKeys - newKeys
    }

    evictionManager.cleanupDroppedRailItems(dropped)
}
```

Cleanup should be conservative:

```kotlin
suspend fun cleanupDroppedRailItems(mediaKeys: Set<MediaKey>) {
    for (mediaKey in mediaKeys) {
        val stillInActiveRail = railDao.existsActiveOrStaleRailReference(
            mediaKey = mediaKey,
            now = now()
        )

        val protected = cacheDao.existsProtectedCacheForMedia(
            mediaKey = mediaKey,
            retention = setOf(
                RetentionClass.UserScoped,
                RetentionClass.RecentlyViewed,
                RetentionClass.Pinned,
                RetentionClass.GlobalReusable
            )
        )

        if (!stillInActiveRail && !protected) {
            cacheDao.markRailScopedEntriesForDeletion(mediaKey)
        }
    }

    blobStore.deleteMarkedBlobs()
}
```

Use `stale_until`, not only `expires_at`, when deciding whether a rail still owns items.

Otherwise, a rail that is expired but still displayable during offline/playback/paused mode could lose all its item data prematurely.

---

# 8. Hydrating rails already on disk

Once rails are first-class cache objects, hydration becomes straightforward.

The runtime can ask:

```text
Which rails are still fresh or stale-usable?
Which items are inside those rails?
Which item summaries/posters/ratings are missing?
Which entries will expire soon?
Which work is allowed right now?
```

Then it creates a hydration plan.

```kotlin
class HydrationPlanner(
    private val railStore: RailStore,
    private val cacheStore: CacheStore,
    private val integrationHub: IntegrationHub
) {
    suspend fun hydrateRailsOnDisk(
        horizon: Duration,
        maxItems: Int
    ) {
        val targets = railStore.itemsNeedingHydration(
            now = now(),
            expiresBefore = now() + horizon,
            maxItems = maxItems
        )

        val specs = targets.flatMap { item ->
            hydrationSpecsFor(item)
        }

        integrationHub.prefetch(
            specs = specs,
            reason = PrefetchReason.ActiveDiskRails
        )
    }
}
```

Hydration profiles should be explicit:

```kotlin
enum class HydrationProfile {
    RailMinimal,       // title, year, poster
    RailRich,          // title, poster, rating, runtime, overview
    DetailReady,       // full metadata, credits, backdrops, videos
    PosterOnly,
    RatingPosterOnly
}
```

Example:

```text
Home rail on disk:
    hydrate title + poster + rating

Focused rail item:
    hydrate richer summary + backdrop

Detail opened:
    hydrate full details + cast + videos + episodes

Playback active:
    pause hydration, continue cache reads
```

---

# 9. What to change per provider

## Trakt

Current state is relatively controlled because of the gate, but it is not centrally modeled.

Improve by moving these into the runtime:

```text
TraktRequestGate → ProviderLane(TRAKT)
Trakt comments → cached IntegrationSpec
Trakt auth health → CredentialGate(TRAKT)
Trakt outbox → generic MutationOutbox
```

Specific fixes:

```text
Cache Trakt comments with a modest TTL, for example 6–24 hours.
Route MetaDetailsViewModel comments through a Trakt service/spec, not direct ViewModel networking.
Put auth refresh behind an auth-specific lane or mutex visible to the runtime.
Keep scrobble/checkin as playback-allowed work.
```

---

## Simkl

Simkl is also controlled, but the coupling to Trakt’s outbox should be removed.

Improve by making this generic:

```text
TraktMutationOutboxCoordinator
    ↓
ProviderMutationOutboxCoordinator
```

Then:

```text
Trakt adapter
Simkl adapter
future provider adapters
```

Simkl’s activity timestamp model is different from simple TTL. That is fine. The central cache should support multiple freshness strategies:

```kotlin
sealed interface FreshnessPolicy {
    data class FixedTtl(val ttl: Duration) : FreshnessPolicy
    data class ActivityDriven(val activityKey: String, val idleFloor: Duration) : FreshnessPolicy
    data class ManualInvalidation(val staleAfter: Duration) : FreshnessPolicy
}
```

---

## TMDB

TMDB needs better deduplication and central TTL ownership.

Current issues:

```text
parallel enrichment fan-out
process-lifetime in-memory maps
partial disk cache
reliance on OkHttp HTTP cache for some calls
```

Improve by:

```text
routing all TMDB calls through IntegrationHub
adding single-flight by cache key
moving enrichment cache to unified disk cache
keeping in-memory only as short-lived L1 cache
making videos, reviews, credits, images separate resource kinds with clear TTLs
```

Suggested resource kinds:

```text
TMDB_MOVIE_DETAILS
TMDB_TV_DETAILS
TMDB_EXTERNAL_IDS
TMDB_IMAGES
TMDB_CREDITS
TMDB_VIDEOS
TMDB_REVIEWS
TMDB_RECOMMENDATIONS
TMDB_CONFIG
```

---

## TVDB

TVDB needs special care because update invalidation is already a major feature.

Current issues:

```text
boot update runs immediately
no provider lane
authorized HTTP cache has limited value
invalidation may not fully clear memory-held data
WorkManager has no strong central playback/network policy
```

Improve by:

```text
routing TVDB metadata calls through ProviderLane(TVDB)
moving TVDB update catch-up into Maintenance work
making TVDB update invalidation publish central cache invalidation events
ensuring memory L1 listens to invalidation
adding network/playback constraints to update worker
```

Also, TVDB references should be `GlobalReusable`, while series/episode metadata should usually be `RailScoped` or `RecentlyViewed`.

---

## Kitsu

Kitsu is currently one of the best early migration candidates because it has no in-app cache and few production endpoints.

Current issues:

```text
default OkHttp cache only
no app disk cache
no gate
no single-flight
season episode pages reload when HTTP cache expires
```

Improve by:

```text
Kitsu anime details → IntegrationSpec
Kitsu episode pages → IntegrationSpec
Kitsu auth → CredentialGate(KITSU)
ProviderLane(KITSU) concurrency 1
disk TTL for anime details and episodes
```

This is probably the safest provider to use as the first proof-of-concept for the new runtime.

---

## MDBList

MDBList currently has good repository-level dedup for ratings but no provider-level gate.

Current issues:

```text
HTTP disk cache disabled
ratings mostly memory cache
rating provider fan-out can create several API calls
discovery snapshots separate from rating data
no playback gate
```

Improve by:

```text
MDBList discovery rails → RailSpec
MDBList rating bundles → IntegrationSpec
ratings disk cache with TTL
provider lane concurrency 1
replace repository-local semaphores with scheduler priorities
```

If MDBList supports batched rating fetches, prefer fewer larger requests over several parallel provider calls.

---

## RPDB and TopPosters

These need to stop being “just remote image URLs” from the app’s perspective.

Current issues:

```text
Coil owns network fetch
provider runtime cannot pause it
provider runtime cannot TTL it
rail-aware eviction cannot see poster ownership
generated rating poster changes may not line up with Coil's 10-day TTL
TopPosters base URL ambiguity
```

Improve by creating specs like:

```kotlin
posterSpecs.ratingPoster(
    provider = Provider.RPDB,
    mediaKey = MediaKey.Movie.Imdb("tt0137523"),
    style = "poster-default",
    size = "w500",
    ratingSources = listOf("imdb", "rt", "metacritic")
)
```

Then:

```text
IntegrationHub resolves remote poster to local File
Coil displays local File
```

The cache key should include:

```text
provider
id type
id
poster style
rating sources
size
language
fallback URL hash
provider base URL identity
schema version
```

---

# 10. Extensible module design

A future provider should be addable by registering a module, not by copying cache and semaphore logic.

Provider module:

```kotlin
interface MetadataProviderModule {
    val provider: Provider
    val laneConfig: ProviderLaneConfig
    val capabilities: Set<ProviderCapability>

    fun registerSpecs(registry: SpecRegistry)
    fun registerIdentityMappings(registry: IdentityRegistry)
    fun registerTtlPolicies(registry: TtlPolicyRegistry)
}
```

Capabilities:

```kotlin
enum class ProviderCapability {
    DiscoveryRails,
    MovieMetadata,
    ShowMetadata,
    EpisodeMetadata,
    AnimeMetadata,
    Ratings,
    Posters,
    UserLibrary,
    Progress,
    Scrobble,
    Search
}
```

Provider code should expose factories:

```kotlin
class TmdbSpecs {
    fun movieDetails(...): IntegrationSpec<TmdbMovieDetails>
    fun popularMovies(...): RailSpec
}

class KitsuSpecs {
    fun animeDetails(...): IntegrationSpec<KitsuAnimeDetails>
    fun animeEpisodes(...): IntegrationSpec<KitsuEpisodePage>
}

class RpdbSpecs {
    fun ratingPoster(...): IntegrationSpec<File>
}
```

Consumers should depend on:

```text
IntegrationHub
Spec factories
```

not:

```text
Retrofit APIs
OkHttp clients
provider repositories with hidden caching
```

---

# 11. Migration plan

You do not need to rewrite everything at once. I would do it in phases.

## Phase 1 — Add observability before changing behavior

Add a network interceptor or wrapper that records:

```text
provider
host
endpoint group
work class
call site tag
cache key if available
was cache hit?
was fresh/stale/network?
queue wait time
network duration
response code
byte count
playback active?
```

Even before centralization, this will prove where network traffic really comes from.

Also add a simple debug screen:

```text
Provider queues
Recent network calls
Cache hit/miss ratio
Paused work count
Top cache consumers
Rails on disk
Orphan candidates
```

---

## Phase 2 — Introduce `IntegrationHub` with one provider

Start with Kitsu.

Why Kitsu?

```text
few endpoints
no existing app disk cache
no complex outbox
clear improvement
low migration risk
```

Add:

```text
Kitsu provider lane
Kitsu disk cache entries
single-flight
TTL policies
cache-first guarantee tests
```

Keep old code available temporarily, but route production Kitsu calls through the hub.

---

## Phase 3 — Move Trakt comments and MDBList ratings

These are good next targets because they currently have obvious gaps.

Move:

```text
Trakt comments
MDBList rating bundles
MDBList episode ratings
```

into the central cache.

This gives you immediate user-visible wins:

```text
reopening detail pages becomes cheaper
ratings survive process death
comments stop refetching every time
```

---

## Phase 4 — Move RPDB / TopPosters image fetching

Add either:

```text
IntegrationHub poster file resolver
```

or:

```text
custom Coil Fetcher backed by IntegrationHub
```

Then UI receives local image files or hub-backed models.

This makes poster TTLs, pause behavior, and rail-aware eviction possible.

---

## Phase 5 — Move TMDB and TVDB enrichment

This is the larger phase.

Move:

```text
TMDB details/images/credits/videos/reviews/recommendations
TVDB series/episodes/translations/references
```

to the central runtime.

At this point, reduce old service-local in-memory maps to small L1 caches, or remove them where single-flight plus disk is enough.

---

## Phase 6 — Make rails first-class

Move discovery snapshots into:

```text
RailStore
RailItemStore
MediaIdentityStore
```

Then implement:

```text
rail refresh diffing
active rail hydration
orphan cleanup
reference-aware eviction
```

This is where the architecture starts matching your long-term goal.

---

## Phase 7 — Generalize mutation outbox

Rename and generalize:

```text
TraktMutationOutboxCoordinator
    ↓
ProviderMutationOutboxCoordinator
```

Then register:

```text
TraktMutationAdapter
SimklMutationAdapter
```

This removes one of the more confusing architectural couplings.

---

# 12. Tests you should add

These tests matter more than implementation details.

## Fresh cache must never hit network

```text
Given fresh cache entry exists
When IntegrationHub.get(spec)
Then result comes from disk
And provider lane is not entered
And network executor call count is 0
```

## Same cache miss should single-flight

```text
Given no cache
When 10 callers request same spec
Then only 1 network call happens
And all 10 callers receive same cached result
```

## Provider concurrency must be one

```text
Given 10 different TMDB cache misses
When they are requested concurrently
Then at most 1 TMDB network call is active at a time
```

## Playback pause must block non-critical work

```text
Given playback active
When prefetch and hydration jobs are queued
Then they do not start network
But fresh cache reads still return
And scrobble work is allowed
```

## Rail drop must not delete shared items

```text
Rail A contains item X
Rail B contains item X
Rail A refresh drops X
Then item X cache entries remain
```

## Rail drop should delete orphaned rail-scoped data

```text
Rail A contains item Y
No other rail contains Y
Y is not pinned/user-scoped/recently viewed
Rail A refresh drops Y
Then Y's rail-scoped cache entries are deleted
```

## Stale-visible rail should still protect items

```text
Rail A expired but staleUntil is in the future
Rail A contains item Z
Then Z is not orphan-cleaned yet
```

## TVDB invalidation must clear memory and disk

```text
Given TVDB update invalidates series S
Then disk entries for S are invalidated
And memory L1 entries for S are invalidated
And next read goes through normal cache/network path
```

---

# 13. What I would not do

I would not solve this by only tuning OkHttp.

For example:

```text
set maxRequestsPerHost = 1
```

is not enough, because:

```text
fresh cached data would still not be centrally guaranteed
Coil image requests bypass provider logic
ViewModel direct calls remain possible
playback pause remains feature-local
rail ownership remains unsolved
cache eviction remains blind
```

I would also avoid adding more provider-local semaphores. That makes the current problem worse: each service becomes a little scheduler of its own.

The architectural direction should be:

```text
fewer local policies
more central policy
provider-specific request description only
```

---

# 14. Highest-impact fixes first

If I had to prioritize, I would do this order:

1. **Create `IntegrationHub` and central cache key model.**
2. **Add central single-flight.**
3. **Add provider lanes with concurrency 1.**
4. **Move Kitsu through the hub first.**
5. **Move Trakt comments and MDBList ratings next.**
6. **Add central playback/network gate.**
7. **Move RPDB/TopPosters image fetching out of raw Coil URLs.**
8. **Move TMDB/TVDB enrichment to the hub.**
9. **Make rails first-class cache owners.**
10. **Implement rail-aware orphan cleanup.**
11. **Generalize the Trakt outbox into a provider mutation outbox.**
12. **Retire or shrink scattered process-lifetime memory caches.**

---

# 15. The desired end state

The clean end state is this:

```text
Every provider operation is an IntegrationSpec.
Every network call goes through a provider lane.
Every provider lane is configurable.
Every fresh cache hit bypasses network completely.
Every rail is a cached ownership root.
Every item knows which rails/users/features still retain it.
Every prefetch is low-priority cache-first work.
Every playback pause affects provider work centrally.
Every new provider registers capabilities, TTLs, identity rules, and specs.
```

That gives you a system that is much easier to extend.

Adding a new provider should become:

```text
add provider enum
add lane config
add auth policy if needed
add TTL policies
add spec factory
add identity mappings
add response mappers
register provider module
```

not:

```text
invent a cache
invent a semaphore
invent a snapshot store
invent playback behavior
invent eviction behavior
remember which OkHttp client to use
remember whether Coil bypasses the cache
```

Your current implementation is already rich and fairly mature, but it has grown organically. The next improvement is not “more cache”; it is **one cache authority, one scheduling authority, and one ownership model**.

