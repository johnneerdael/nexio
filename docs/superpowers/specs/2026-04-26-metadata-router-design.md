# MetadataRouter Design

Date: 2026-04-26

## Purpose

Build the metadata architecture above `IntegrationRuntime` so Nexio can deterministically choose one primary metadata authority, run secondary enrichment without field ownership conflicts, and preserve addon-first rendering behavior. This design covers one implementation cycle, but the cycle is internally staged with test-proof gates.

The implementation must migrate callers directly to the new MetadataRouter stack. `TvMetadataRouter` is not retained as a long-term facade.

## Source Inputs

The design is grounded in:

- `plans/metadata-routing-1.md`
- `plans/metadata-routing-appendix.md`
- `plans/metadata-routing-details.md`
- `plans/metadata-routing-addon-metadata.md`
- The current IntegrationRuntime audit gate, which reports runtime control-plane `PASS` and zero direct bypasses.

The key addon-data constraint is that addon metadata is always the first render source, while MetadataRouter is used for canonical enrichment.

## Target Architecture

```text
Addon catalog/meta payload
    ↓
MetadataRequestNormalizer
    ↓
MetadataRouter
    ↓
ProviderPlanExecutor
    ↓
ResolverOrchestrator
    ↓
FieldResolver
    ↓
ResolvedMetadataDocument / HomeDisplayMetadata
    ↓
UI / Player / Continue Watching
```

`IntegrationRuntime` remains below provider integration adapters. Router, executor, resolver, and field-resolution layers must not call Retrofit APIs, auth services, OkHttp clients, or provider network clients directly.

## Stage Gates

The implementation cycle is accepted only through tests. Manual inspection is not a gate.

1. Routing and identity gate proves routing precedence, parent-id normalization, deterministic anime identity mapping, and item-type fallback.
2. Primary plan execution gate proves TMDB, TVDB, and Kitsu plans map to runtime-covered `apiShapeId`s.
3. Secondary resolver gate proves resolvers run only at allowed request depths.
4. Field ownership gate proves secondary providers cannot overwrite primary-owned fields.
5. Artwork cache gate proves artwork policy changes invalidate artwork decisions and resolved documents without invalidating primary provider metadata.
6. Continue Watching gate proves click-time addon metadata, route provider, and parent id are persisted and reused.
7. Boundary gate proves `TvMetadataRouter` call sites are migrated and no router/resolver layer bypasses IntegrationRuntime.

## Input Contract: Addon Item Truth Model

Every routing decision uses only the catalog row item's own required fields:

```text
item.id
item.type
```

`item.type` is the authoritative media type. Catalog-level type is not authoritative because real catalog rows can be mixed, such as a Disney row whose catalog type is `series` but whose items include both movies and series.

Identifier interpretation:

```text
kitsu:...      -> anime, authoritative Kitsu id
mal:...        -> anime id requiring Kitsu mapping
anilist:...    -> anime id requiring Kitsu mapping
anidb:...      -> anime id requiring Kitsu mapping
tt...          -> neutral IMDb id, requires anime mapping before Kitsu
imdb:...       -> neutral IMDb id, requires anime mapping before Kitsu
tmdb:...       -> provider-native TMDB id
tvdb:...       -> provider-native TVDB id
```

The router must ignore these fields for routing:

```text
catalog.type
addonId
catalogId
sourceName
genre == Animation
animeType
popularity/trend fields
links[]
```

Addon fields such as `name`, `poster`, `background`, `description`, `releaseInfo`, `runtime`, `imdbRating`, and `genres` are safe for first render only. They are render inputs, not routing inputs.

## Routing And Identity

MetadataRouter does not fetch metadata. It only normalizes the request, chooses a primary provider, records trace evidence, and persists safe identity mappings.

Final precedence:

1. Anime-prefixed row item first: `kitsu:{id}` routes directly to Kitsu without Fribb lookup.
2. Anime-prefixed non-Kitsu row item second: `mal:{id}`, `anilist:{id}`, and `anidb:{id}` resolve through the AnimeIdentityIndex / Fribb to Kitsu, with local mapping as fallback.
3. IMDb anime mapping third: `tt...` and `imdb:...` route to Kitsu only if `IdMappingStore` or AnimeIdentityIndex / Fribb maps the id to Kitsu.
4. Provider-native primary ids fourth: `tmdb:{id}` with `item.type == movie` routes directly to TMDB, and `tvdb:{id}` with `item.type == series` routes directly to TVDB.
5. Provider-native mismatch fifth: `tmdb:{id}` with `item.type == series` or `tvdb:{id}` with `item.type == movie` emits `ROUTING_ID_TYPE_CONFLICT` and falls back by explicit item-type policy.
6. Per-item type fallback: `series` routes to TVDB, `movie` routes to TMDB.

Catalog id, addon name, catalog name, genre, popularity, trend fields, and other catalog-level hints are not routing authority. Source context remains available for trace/debugging, catalog harvest, and click-time metadata capture only.

AnimeIdentityIndex is for anime IDs and IMDb-anime detection. It is not the default resolver for TMDB/TVDB provider-native ids unless those datasets and semantics are separately verified.

The router always computes a parent id before routing:

```text
tt12343534:1:1 -> tt12343534
kitsu:7442:1:1 -> kitsu:7442
tmdb:550 -> tmdb:550
```

The route result includes provider, parent id, media kind, decision reason, source context, target ids, and trace entries. This route evidence is used by tests, Continue Watching, and diagnostics.

## Provider Plan Execution

`ProviderPlanExecutor` accepts a `MetadataRoute` and requested depth, then asks the primary provider integration layer for canonical candidates. All downstream logic must use `route.mediaKind`, not the original request `ContentType`, when selecting provider behavior. After routing, `ContentType` MUST NOT be used for provider decisions.

Required primary plan mapping:

- TMDB movie `DETAIL_CORE` uses `tmdb.movie.core`.
- TMDB media and secondary depths use videos, reviews, and recommendations only when requested.
- TVDB series `DETAIL_CORE` uses `tvdb.series.extended`. `tvdb.series.translation` runs only when the requested language differs from the default/base language and is not required for identity resolution.
- TVDB season depth uses `tvdb.series.episodes.*`.
- Kitsu anime `DETAIL_CORE` uses `kitsu.anime.core`.
- Kitsu advanced anime detail uses episodes, castings, staff, productions, and relationships.

Every primary plan must reference an IntegrationRuntime-covered `apiShapeId`. Missing runtime coverage is a failing test, not a warning.

## Addon-First Rendering Contract

Addon metadata is always used for first render:

```text
Catalog response
    -> render MetaPreview immediately
    -> do not block first paint on MetadataRouter

Visible item or detail request
    -> call MetadataRouter
    -> fetch canonical metadata through provider plans

Canonical success
    -> replace primary-owned fields through FieldResolver

Canonical failure
    -> keep addon-rendered fields
```

Primary fields are replaced only by the selected primary provider. Secondary fields are merged only through resolver-owned fields.

## Anime Identity Stores

AnimeIdentityIndex:

```text
source: packaged Fribb dataset
mutability: immutable during app run
network: none
responsibility: MAL/AniList/AniDB-to-Kitsu mapping and IMDb-anime detection
allowed input schemes: MAL, AniList, AniDB, IMDb
forbidden input schemes: TMDB, TVDB
```

IdMappingStore:

```text
source: runtime discoveries and persisted local mappings
mutability: persistent and small
network: none during route decision
responsibility: high-confidence previously observed mappings
```

IdMappingStore keys use `(sourceScheme, sourceId)` rather than raw id strings so IMDb, MAL, AniList, AniDB, TMDB, and TVDB identifiers cannot collide and multiple source ids can point to the same Kitsu id.

IdMappingStore TTL semantics:

```text
overwrite priority: LOCAL > ROUTER_OBSERVED > FRIBB > NEGATIVE
LOCAL mappings: permanent
FRIBB mappings: permanent
ROUTER_OBSERVED mappings: permanent
NEGATIVE mappings: 30 day TTL
```

The resolution chain is:

```text
explicit anime ids:
    kitsu direct
    MAL/AniList/AniDB -> AnimeIdentityIndex -> persist Fribb hit to IdMappingStore -> fallback to IdMappingStore if needed -> item.type fallback

IMDb ids:
    1. IdMappingStore for runtime-learned mappings
    2. AnimeIdentityIndex for static Fribb dataset fallback, persisted back into IdMappingStore on hit
    3. item.type fallback

TMDB/TVDB provider-native ids:
    route directly only when prefix agrees with item.type
    otherwise emit ROUTING_ID_TYPE_CONFLICT, keep targetId as the original parentId, mark it as requiring identity resolution, and use explicit item-type fallback policy
```

ProviderPlanExecutor must not build provider calls for routes whose target id still requires identity resolution.

Identity resolution is performed ONLY inside provider integration adapters or dedicated identity helper services before any IntegrationRuntime call is built. MetadataRouter and ProviderPlanExecutor MUST NOT resolve provider-native IDs.

## Engineer Misreadings To Avoid

- Fribb is not optional for correctness. It is required for static MAL/AniList/AniDB mapping and IMDb-anime detection when no runtime-learned mapping exists.
- Addon metadata is not discarded. It is the first-render UI baseline; canonical metadata is a replacement layer when available.
- Provider-native ids are not always safe. `tmdb:{id}` with `series` or `tvdb:{id}` with `movie` is a routing conflict, not a provider-ready id.
- FieldResolver is mandatory. Provider candidates and secondary resolvers must not merge user-visible truth directly.

## Resolver Orchestration

`ResolverOrchestrator` runs secondary resolvers only for depths that need them:

- `PREVIEW`: addon metadata only. Initial row rendering must not execute MetadataRouter or block on routing/mapping; routing is deferred until an item becomes visible, a detail screen opens, playback starts, or enrichment is explicitly requested.
- `DETAIL_CORE`: primary provider plus cheap or cached rating/artwork.
- `DETAIL_MEDIA`: trailers and artwork media.
- `DETAIL_SECONDARY`: reviews, recommendations, related titles, and advanced anime detail.
- `SEASON`: primary episode list and episode ratings.
- `PLAYER`: tracking and skip segments only, with no broad metadata prefetch.

Secondary resolver placement:

- `RatingResolver` owns ratings only.
- `ArtworkRouter` owns final artwork selection only.
- `ReviewResolver` owns reviews only.
- `TrackingResolver` owns progress, scrobble, watched, and library state only.
- `SkipSegmentResolver` owns intro/outro/recap/credits timestamps only.
- `TrailerResolver` owns trailer candidates only.
- `RecommendationResolver` owns related and recommended candidates only.
- `OrganizationPersonResolver` owns person, company, and network detail/discovery only.

## Field Ownership

`FieldResolver` is the only layer that creates user-visible resolved metadata documents. Provider adapters and resolvers return candidates; they do not merge final fields.

Primary provider ownership:

- TMDB owns movie title, overview, release date, runtime, genres, age rating, country, language, cast, crew, production companies, collection, artwork candidates, external ids, and requested trailer candidates.
- TVDB owns series title, overview, translations, status, season order, episode list, episode numbering, episode titles, episode descriptions, networks, companies, cast, and TVDB artwork candidates.
- Kitsu owns anime canonical titles, native titles, alternate titles, synopsis, status, age rating, episode length, episode list, episode numbering, episode titles, episode descriptions, characters, voice actors, staff, productions, related anime, poster candidates, and cover candidates.

Secondary providers may fill allowed empty fields, but cannot overwrite primary-owned fields. Ignored overwrites must be traceable so tests can prove the ownership rule.

## Cache Layers

The design uses separate caches:

```text
Provider metadata cache
    TMDB / TVDB / Kitsu raw canonical candidates via IntegrationRuntime

Router decision cache
    route decision and id mappings, keyed by parent id + source context + policy version

Resolved document cache
    FieldResolver output, keyed by route + depth + field policy version

Artwork decision cache
    selected poster/logo/backdrop/thumbnail, keyed by artwork policy version

Image/blob cache
    downloaded image bytes/local files
```

Provider metadata freshness must not imply artwork decision freshness.

## Artwork Policy

Poster is not metadata. Poster is a resolver decision.

Only one premium artwork provider may be active at a time: Top-Posters xor RPDB. Artwork precedence is:

1. User-selected premium provider.
2. Primary provider artwork candidates.
3. Addon-bundled poster, background, or logo.
4. Placeholder.

Changing premium provider, API key, style, badge options, thumbnail options, language, region, or artwork provider priority must invalidate artwork decisions and resolved display documents. These changes must not invalidate primary TMDB, TVDB, or Kitsu metadata unless the primary metadata policy itself changes.

## Continue Watching

At playback start, the system persists:

- `parentId`
- provider route
- click-time addon `HomeDisplayMetadata`
- routing policy version

At Continue Watching render, the merge order is:

```text
canonical refresh, if available
→ click-time addon metadata
→ existing persisted fallback
```

Continue Watching must not re-decide a previously mapped anime route as TVDB, and must not lose the exact title/poster/description the user saw when playback started.

Existing `WatchProgress` rows are backfilled by computing `parentId` and routing once, then persisting the result so future reads do not re-route repeatedly.

If a stored Continue Watching route has an older routing policy version, the system may reroute that entry once and then persist the current version.

Any change to routing precedence, AnimeIdentityIndex behavior, or IdMappingStore semantics must bump the current routing policy version.

## Migration

Callers migrate directly to the new stack. `TvMetadataRouter` is retired after call sites move.

Known call-site areas:

- `MetaDetailsViewModel`
- `HomeViewModel`
- `HomeViewModelContinueWatching`
- `HomeProviderLocalizedMetadataOverlay`
- `HomeCatalogRefreshCoordinator`
- `PlayerViewModel`
- `PlayerRuntimeController`
- `TvdbContinueWatchingTimingEnricher`
- `ContinueWatchingSnapshotService`

Layering after migration:

```text
UI / ViewModels / Workers
    ↓
repositories / facades
    ↓
MetadataRouter / ProviderPlanExecutor / ResolverOrchestrator / FieldResolver
    ↓
provider integration adapters
    ↓
IntegrationRuntime
```

## Acceptance Criteria

- Home and catalog rows render immediately from addon metadata.
- `kitsu:` row-item ids route directly to Kitsu without Fribb lookup.
- `mal:`, `anilist:`, and `anidb:` row-item ids resolve to Kitsu through AnimeIdentityIndex / Fribb.
- Neutral IMDb ids route to Kitsu only through IdMappingStore or AnimeIdentityIndex / Fribb.
- TMDB and TVDB provider-native ids route directly only when the prefix agrees with `item.type`; mismatches record `ROUTING_ID_TYPE_CONFLICT`.
- Disney mixed rows use per-item `type`, not catalog manifest type.
- Primary plans reference IntegrationRuntime-covered `apiShapeId`s.
- Secondary resolvers cannot overwrite primary-owned fields.
- Top-Posters/RPDB setting changes update artwork decisions without invalidating primary metadata caches.
- Continue Watching preserves click-time addon metadata and provider route.
- No production caller imports `TvMetadataRouter`.
- Router/resolver layers do not inject raw provider APIs, auth services, OkHttp clients, or Retrofit clients.
- IntegrationRuntime audit still passes after migration.

## Implementation Planning Notes

The implementation plan should sequence the work by the proof gates above. Each stage must add its gate tests before or with implementation. The final handoff to implementation should use `superpowers:writing-plans`.
