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

1. Routing and identity gate proves routing precedence, parent-id normalization, anime detection, and item-type fallback.
2. Primary plan execution gate proves TMDB, TVDB, and Kitsu plans map to runtime-covered `apiShapeId`s.
3. Secondary resolver gate proves resolvers run only at allowed request depths.
4. Field ownership gate proves secondary providers cannot overwrite primary-owned fields.
5. Artwork cache gate proves artwork policy changes invalidate artwork decisions and resolved documents without invalidating primary provider metadata.
6. Continue Watching gate proves click-time addon metadata, route provider, and parent id are persisted and reused.
7. Boundary gate proves `TvMetadataRouter` call sites are migrated and no router/resolver layer bypasses IntegrationRuntime.

## Routing And Identity

MetadataRouter does not fetch metadata. It only normalizes the request, chooses a primary provider, records trace evidence, and persists safe identity mappings.

Final precedence:

1. Anime id prefix first: `kitsu:`, `mal:`, `anilist:`, and `anidb:` route to Kitsu.
2. Catalog/source anime hint second: anime catalog source, Crunchyroll source, or explicit addon anime catalog context routes to Kitsu even for plain `tt...` ids.
3. `IdMappingStore` / Fribb third: parent id resolves to Kitsu, route to Kitsu and persist the mapping.
4. Per-item type fallback: `series` routes to TVDB, `movie` routes to TMDB.

The router always computes a parent id before routing:

```text
tt12343534:1:1 -> tt12343534
kitsu:7442:1:1 -> kitsu:7442
tmdb:550 -> tmdb:550
```

The route result includes provider, parent id, media kind, decision reason, source context, target ids, and trace entries. This route evidence is used by tests, Continue Watching, and diagnostics.

## Provider Plan Execution

`ProviderPlanExecutor` accepts a `MetadataRoute` and requested depth, then asks the primary provider integration layer for canonical candidates.

Required primary plan mapping:

- TMDB movie `DETAIL_CORE` uses `tmdb.movie.core`.
- TMDB media and secondary depths use videos, reviews, and recommendations only when requested.
- TVDB series `DETAIL_CORE` uses `tvdb.series.extended` plus optional translation.
- TVDB season depth uses `tvdb.series.episodes.*`.
- Kitsu anime `DETAIL_CORE` uses `kitsu.anime.core`.
- Kitsu advanced anime detail uses episodes, castings, staff, productions, and relationships.

Every primary plan must reference an IntegrationRuntime-covered `apiShapeId`. Missing runtime coverage is a failing test, not a warning.

## Resolver Orchestration

`ResolverOrchestrator` runs secondary resolvers only for depths that need them:

- `PREVIEW`: addon metadata only, plus optional cached artwork/rating.
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

At Continue Watching render, the merge order is:

```text
canonical refresh, if available
→ click-time addon metadata
→ existing persisted fallback
```

Continue Watching must not re-decide Crunchyroll-style anime as TVDB, and must not lose the exact title/poster/description the user saw when playback started.

Existing `WatchProgress` rows are backfilled by computing `parentId` and routing once, then persisting the result so future reads do not re-route repeatedly.

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
- Crunchyroll `tt...` anime with catalog anime hint routes to Kitsu.
- Anime id prefixes route to Kitsu regardless of catalog source.
- Fribb/id-mapping catches anime when catalog hint is absent.
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
