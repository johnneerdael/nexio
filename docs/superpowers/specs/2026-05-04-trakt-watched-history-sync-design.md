# Trakt Watched History Sync — Integration Runtime Routing

Date: 2026-05-04

## Context

Series watched on Trakt are not reflected as watched in the app. Movies are. The
detail screen calls `WatchProgressRepositoryImpl.isWatched(contentId, season, episode)`
(`app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt:268`),
which for episodes routes to `TraktProgressService.observeEpisodeProgress(contentId)`.
That observer hydrates from `/shows/{id}/progress/watched` lazily, with the show id
emitted by `toTraktPathId(contentId)` (`TraktIdUtils.kt:49`).

Three structural defects compound:

1. `ParsedContentIds` (`TraktIdUtils.kt:6`) has no `tvdb` field. `parseContentIds`
   does not recognise the `tvdb:` prefix. `normalizeContentId` and `toTraktPathId`
   only emit imdb / tmdb / trakt forms. The project's TV source-of-truth is TVDB,
   so a `tvdb:NNN` local id round-trips as raw text and never matches.
2. `TraktProgressService.mapWatchedShowItem` (`TraktProgressService.kt:1421`) keys
   each watched show by exactly one canonical id. Movies use
   `watchedMovieLookupKeys` (line 1373) which adds *every* id present on the
   payload as an alias, which is why movies match. Shows do not get the alias
   treatment.
3. `getWatched` and `getWatchedShows` in `TraktIntegrationProvider`
   (`TraktIntegrationProvider.kt:176, 193`) bypass the integration runtime cache.
   Every call goes to the wire. The only persistence is an in-process
   `MutableStateFlow` on the `@Singleton` `TraktProgressService`. After a process
   death the watched snapshot is empty until the next refresh, which causes
   redundant Trakt traffic on every cold start. The watched-shows fetch is also
   issued with `extended=noseasons` (line 1399), so per-episode watched flags only
   exist after a separate per-show `/shows/{id}/progress/watched` call — which
   itself fails for tvdb-keyed shows because of defect 1.

`WatchedItemsPreferences` (`app/src/main/java/com/nexio/tv/data/local/WatchedItemsPreferences.kt`)
appears to be a durable per-episode store. It is not. It has no DI binding and
no callers; it is a dead file.

## Goals

- Series episodes watched on Trakt show as watched in the detail view, regardless
  of whether the local id is a TVDB, TMDB, IMDB, or Trakt id.
- Anime is canonicalised through the existing fribb mapping in the metadata
  router, then matched against the same watched index.
- The Trakt watched snapshot is persisted across process restarts via the
  integration runtime's existing Room-backed cache (`LocalIntegrationCacheStore`).
- A repeat read inside the cache TTL window does not produce a Trakt network
  call.
- `last_activities.episodes.watched_at` continues to drive incremental refresh,
  by invalidating the cache entry rather than mutating an in-memory fingerprint.
- Local mark-as-watched and unmark mutations evict the cache entry so the next
  read reflects them.
- Per-show `/shows/{id}/progress/watched` is only used for next-up ordering, not
  for "is this episode watched?".

## Non-Goals

- Changing how next-up is derived. The next-up pipeline keeps its current shape;
  it just receives a richer per-episode index.
- Adding a separate daily timer for two-way sync. `last_activities` is the
  trigger.
- Two-way collection sync, clean-collection, or any of the other Trakt sync
  endpoints listed in the user-pasted docs. Out of scope.
- Persisting individual `WatchedItem` rows. The integration runtime cache holds
  the entire `/sync/watched/shows` payload; `WatchedItemsPreferences` is removed.
- Schema changes to `IntegrationCacheDatabase`. The cache stores opaque blobs
  keyed by spec; adding two more spec keys requires no migration.

## Decisions

### 1. Extend the id layer to carry `tvdb` and let callers state intent

`ParsedContentIds` gains `tvdb: Int?`. `parseContentIds` recognises the `tvdb:`
prefix. `toTraktIds` carries `tvdb` through.

`normalizeContentId` adds an overload that takes a `MediaKind` enum
(`MOVIE`, `SHOW`, `ANIME`) so canonical id selection is intentional, not a
side-effect of which id Trakt emitted first. Defaults: shows → `tvdb:`,
movies → `tmdb:`, anime → caller-supplied (the fribb resolver picks). The
existing zero-arg `normalizeContentId(ids)` retains its behaviour as a safety net
for call sites that have not yet been updated.

A new helper `traktIdLookupKeys(ids: TraktIdsDto, kind: MediaKind): List<String>`
returns the full alias set: `tt…`, `tmdb:N`, `tvdb:N`, `trakt:N`, slug. The
existing `watchedMovieLookupKeys` (`TraktProgressService.kt:1373`) collapses
into a call to this helper. Same code path for movies and shows.

`toTraktPathId` extends to emit `tvdb` ids where Trakt accepts them. For
endpoints that do not, the call site selects an alternate id (preferring trakt,
then imdb) before invoking. This is a small per-endpoint table, not a global
preference.

### 2. Route watched endpoints through `runtime.get` with `CacheFirst`

`TraktIntegrationProvider.getWatched(type)` and `getWatchedShows()` rewrite to
the same shape as `fetchTrendingMovies` (`TraktIntegrationProvider.kt:761`):

```kotlin
val spec = IntegrationSpec(
    provider = IntegrationProvider.TRAKT,
    apiShapeId = TraktApiShapes.WATCHED_SHOWS,
    operationKey = accountOperationKey(session, "trakt.watched.shows"),
    cacheKey = accountCacheKey(session, "trakt:sync:watched:shows"),
    codec = gsonCodec<List<TraktWatchedShowItemDto>>(),
    cachePolicy = IntegrationCachePolicy.CacheFirst(
        ttlMs = 24L * 60L * 60L * 1000L,         // Trakt's recommended cadence
        staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
    ),
    workClass = IntegrationWorkClass.USER_VISIBLE,
    scope = accountScope(session),
    profileContext = profileContext(session),
    load = { ... }
)
return runtime.get(spec).valueOrNull().orEmpty()
```

The shows variant always requests Trakt **without** `extended=noseasons`, so the
cached payload contains every season and episode. The cache pays for the larger
payload exactly once per TTL window. The `extended` parameter is removed from
`getWatchedShows`.

A new `invalidateWatchedSnapshot(kind: WatchedKind)` on
`TraktIntegrationProvider` evicts the cache entry by spec key. It is the only
hook needed for activity-driven and mutation-driven invalidation. If
`IntegrationCacheStore` exposes a per-spec invalidate, use it; otherwise delete
by cache key directly via `LocalIntegrationCacheStore`.

### 3. `TraktProgressService` becomes a projection layer

The bespoke caches in `TraktProgressService` go away:

- `watchedMoviesState` and `watchedShowsState` are removed as primary stores and
  re-exposed as **derived** `StateFlow`s computed from the integration-cached
  list payloads.
- `watchedMoviesMutex`, `watchedShowsMutex`, `watchedMoviesUpdatedAtMs`,
  `watchedShowsUpdatedAtMs`, `watchedMoviesLastAttemptAtMs`, the corresponding
  `*Stale` and `hasLoaded*` flags, plus `watchedMoviesCacheTtlMs` /
  `watchedMoviesFetchThrottleMs` constants, are deleted. These reproduce what
  the integration runtime already does.
- `getWatchedMoviesSnapshot` and `getWatchedShowsSnapshot` collapse to thin
  projections over `traktIntegrationProvider.getWatched(...)` /
  `.getWatchedShows()`. The integration runtime is the cache; the service is the
  shape.

The projected `WatchedShowIndexEntry` gains:

```kotlin
internal data class WatchedShowIndexEntry(
    val canonicalContentId: String,           // tvdb:NNN for shows; fribb for anime
    val aliasContentIds: Set<String>,         // tt..., tmdb:N, trakt:N, slug, tvdb:N
    val name: String,
    val lastWatchedAtMs: Long,
    val resetAtMs: Long?,                     // honoured per Trakt docs
    val traktShowId: Int?,
    val watchedEpisodes: Set<Pair<Int, Int>>  // (season, episode); reset_at applied
)
```

A second derived map keys every alias to its `WatchedShowIndexEntry`, so a
consumer that hands in any flavour resolves the same entry.

`observeEpisodeProgress(contentId)` now consults the projected per-show episode
set first. It only falls back to `getShowProgressWatched` if the entry is
missing or for next-up ordering details that need per-episode `last_watched_at`.

`hasActivityChanged` (`TraktProgressService.kt:1130`) keeps reading
`last_activities` for fingerprinting. Instead of flipping `*Stale = true`, it
calls `traktIntegrationProvider.invalidateWatchedSnapshot(...)`. Single source
of truth: the runtime cache decides freshness; this method only signals
invalidation.

`markAsWatched` (`TraktProgressService.kt:730`) and
`reconcileQueuedHistoryAddSuccess` (line 775) additionally invalidate the
appropriate watched-snapshot cache entry. The existing `optimisticProgress` flow
continues to bridge the eviction-then-refetch window.

### 4. Anime routing reuses the existing fribb path

The fribb mapping resolver already exists in
`app/src/main/java/com/nexio/tv/data/integration/metadata` (commit
`b3d24b2ab fix(metadata): route anime search details through Kitsu` operates in
the same id-routing area). When the watched-show indexer projects an entry, it
asks the resolver whether the show is anime. If it is, the canonical id becomes
the kitsu/anidb id from fribb; otherwise tvdb (shows) / tmdb (movies). Aliases
always include every id Trakt sent plus the canonical, so consumers from any
side of the routing match.

### 5. Delete `WatchedItemsPreferences`

Orphan; no DI binding, no callers. Leaving it in the tree implies a durable
per-item store that does not exist. Remove the file and the unused
`WatchedItem` references that exist only to support it (after verifying no
tests depend on them).

## Data Flow

Cold start, empty cache:

```
MetaDetailsViewModel.observeWatchedEpisodes
  → WatchProgressRepositoryImpl.getAllEpisodeProgress(contentId)
  → TrackingProgressService.observeEpisodeProgress(contentId)
  → TraktProgressService.observeEpisodeProgress(contentId)
       │
       ├── onStart: ensure watched-shows snapshot
       │     → TraktIntegrationProvider.getWatchedShows()
       │         → runtime.get(spec)
       │             → LocalIntegrationCacheStore.readFresh: null
       │             → load { traktApi.getWatchedShows(...) }
       │             → write to Room under (account, "trakt:sync:watched:shows")
       │             → return List<TraktWatchedShowItemDto>
       │     → project to Map<canonicalContentId, WatchedShowIndexEntry>
       │     → expand alias map
       │
       └── episodeIndex[canonicalLookupKey(contentId)]
             merged with optimisticProgress
             → Flow<Map<Pair<season, episode>, WatchProgress>>
```

Warm path inside TTL: the `runtime.get(spec)` returns from Room without a Trakt
call. No additional per-show `/shows/{id}/progress/watched` request needed for
the watched-vs-not decision.

Activity-driven refresh: `last_activities.episodes.watched_at` advances →
`TraktProgressService.hasActivityChanged` calls
`traktIntegrationProvider.invalidateWatchedSnapshot(WatchedKind.SHOWS)` → the
next read repopulates Room.

Mutation: `markAsWatched(progress)` for an episode →
`invalidateWatchedSnapshot(WatchedKind.SHOWS)` after the network ack →
`optimisticProgress` keeps the UI consistent until the next read lands.

## Components

Files changed:

- `app/src/main/java/com/nexio/tv/data/repository/TraktIdUtils.kt`
  - `ParsedContentIds.tvdb` field, `parseContentIds` `tvdb:` prefix,
    `toTraktIds` carrying tvdb, `MediaKind` enum, `normalizeContentId(ids, kind)`
    overload, `traktIdLookupKeys(ids, kind)` helper, `toTraktPathId` tvdb
    support with per-endpoint preferred-id table.
- `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt`
  - `getWatched(type)` and `getWatchedShows()` rewritten as
    `runtime.get(IntegrationSpec(...))` with `CacheFirst` and
    `IntegrationScope.Account`.
  - `getWatchedShows` no longer takes `extended`; always full payload.
  - `invalidateWatchedSnapshot(kind)` added.
- `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`
  - Remove bespoke watched-movie / watched-show caches and their plumbing.
  - `watchedMoviesState` / `watchedShowsState` re-expressed as derived
    `StateFlow`s.
  - `WatchedShowIndexEntry` extended with `aliasContentIds`, `resetAtMs`,
    `watchedEpisodes`.
  - `mapWatchedShowItem` builds the per-(season, episode) set, applies
    `reset_at`, fans out alias keys via `traktIdLookupKeys`.
  - `observeEpisodeProgress(contentId)` consults the projected episode set
    first.
  - `hasActivityChanged` calls `invalidateWatchedSnapshot` instead of mutating
    stale flags.
  - `markAsWatched` / `reconcileQueuedHistoryAddSuccess` evict the cache.
- `app/src/main/java/com/nexio/tv/data/local/WatchedItemsPreferences.kt`
  - Deleted. Verify no imports remain.

No `IntegrationCacheDatabase` schema change. No new top-level units.

## Error Handling

- Trakt 5xx / network error on first read: `runtime.get(spec)` returns no value;
  `staleAfterExpiryMs` permits a stale read if one exists. On a true cold start
  the projected flows emit empty maps, matching today's degraded behaviour.
- Trakt 401: integration runtime suppresses subsequent calls until reauth.
  Watched flows degrade to empty.
- Cache-hit-but-malformed payload: treat as miss; integration audit logs;
  next read re-fetches.
- `last_activities` advanced but `invalidateWatchedSnapshot` failed: TTL
  expiry catches it within `ttlMs`. Logged at warn. No retry storm.
- Show in `/sync/watched/shows` with no usable id: skipped with trace; matches
  current behaviour.
- Anime fribb resolution fails: fall through to tvdb (or tmdb if absent). The
  entry is still indexed under whatever id Trakt sent plus aliases.
- Mutation race with concurrent read: `optimisticProgress` (`TraktProgressService.kt:213, 717`)
  bridges the eviction window for both movies and series.
- `reset_at` set on a show: per-episode index drops episodes whose
  `last_watched_at < reset_at`, per Trakt docs.

## Testing

JVM unit tests only; no instrumented tests required.

`TraktIdUtilsTest` (new):

- `parseContentIds("tvdb:81189")` populates `tvdb` and round-trips through
  `toTraktIds`.
- `normalizeContentId(ids, MediaKind.SHOW)` returns `tvdb:…` when present;
  `MediaKind.MOVIE` returns `tmdb:…`; `MediaKind.ANIME` returns the
  caller-supplied canonical.
- `traktIdLookupKeys` emits the full alias set: `tt…`, `tmdb:N`, `tvdb:N`,
  `trakt:N`, slug.

`TraktIntegrationProviderTest` (extend the cluster under
`app/src/test/java/com/nexio/tv/data/integration/trakt/`):

- Second `getWatchedShows()` call within `ttlMs` does not hit `traktApi`.
  Asserts call count.
- Across simulated process restart (rebuild against the same Room instance) the
  cached payload is still served.
- `invalidateWatchedSnapshot(WatchedKind.SHOWS)` followed by a read hits the
  wire.
- Two trakt accounts produce two cache rows under `IntegrationScope.Account`.
- Trakt 429 routes through `IntegrationBackoffManager` for the
  `WATCHED_SHOWS` apiShape.

`TraktProgressServiceTest` (extend the cluster under
`app/src/test/java/com/nexio/tv/data/repository/trakt/`):

- **Series-by-tvdb match (regression for the user-reported bug):** seed
  `/sync/watched/shows` response with a show carrying
  `tvdb: 81189, imdb: tt0903747, tmdb: 1396, trakt: 1` and watched episodes
  `{(1,1), (1,2), (2,1), (2,2)}`. `observeEpisodeProgress("tvdb:81189")` emits
  exactly that set. Repeat with `"tt0903747"` and `"tmdb:1396"`; identical
  result.
- **Anime path:** payload has `tvdb` and `tmdb` only; fribb resolver returns a
  kitsu canonical; lookup by the kitsu id matches.
- **`reset_at` honoured:** episodes with `last_watched_at < reset_at` are
  excluded from the per-episode set.
- **Activity-driven invalidation:** advancing
  `last_activities.episodes.watched_at` triggers exactly one
  `invalidateWatchedSnapshot` call and the next read goes to the wire.
- **Movie regression:** existing movie watched-state tests pass after the
  `watchedMovieLookupKeys` → `traktIdLookupKeys` consolidation.
- **Mutation:** `markAsWatched(progress)` for an episode evicts the watched-shows
  cache entry.

Removal verifications:

- `WatchedItemsPreferences` deletion: confirm no imports remain across `app/`
  and tests, then delete.

Test fixtures: existing JSON under `app/src/test/resources/...` covers movies.
Add a fixture mirroring the Trakt-docs example for shows with seasons and
episodes (Breaking Bad, Parks and Recreation), including a `reset_at` case.

## Risks

- **Trakt path-id acceptance for `tvdb`.** Some Trakt endpoints take a slug,
  trakt id, or imdb id in `{id}` and may not accept `tvdb:NNN`. The
  per-endpoint preferred-id table in `toTraktPathId` (decision 1) handles this
  by selecting an alternate id. Verified during implementation by exercising
  each endpoint in `TraktIntegrationProviderTest`.
- **`WatchedShowIndexEntry` shape drift.** Adding `aliasContentIds`,
  `resetAtMs`, `watchedEpisodes` changes the shape consumed by the next-up
  pipeline (`deriveNextUpFromWatchedShows`, `TraktProgressService.kt:1117`).
  That code reads `name` and `traktShowId` only today; no break expected, but
  exercised by existing next-up tests after the change.
- **Cache eviction lag on token rotation.** Account-scoped cache key includes
  the account id, so a new account never reads another account's cache.
  Confirmed by the per-account cache-row test.

## Open Questions

None. Cadence (TTL 24h, stale grace 7d) follows Trakt's "once a day" guidance
and the integration runtime's existing pattern for user-visible Trakt reads.
