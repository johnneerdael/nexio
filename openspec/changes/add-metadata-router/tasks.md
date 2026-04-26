# Tasks

## 1. Routing And Identity Gate

- [ ] Add request normalization and `parentIdOf()` behavior.
- [ ] Add `MetadataRouter` route result model with trace evidence.
- [ ] Add `IdMappingStore` and mapping source semantics.
- [ ] Seed or consult Fribb/anime identity data after prefix and catalog-hint checks.
- [ ] Add tests for anime prefix, catalog hint, Fribb/id-mapping, item-type fallback, Disney mixed rows, and episode parent normalization.

## 2. Primary Plan Execution Gate

- [ ] Add `ProviderPlanExecutor`.
- [ ] Map TMDB movie and media depths to runtime-covered TMDB `apiShapeId`s.
- [ ] Map TVDB series, translation, season, episode, and update paths to runtime-covered TVDB `apiShapeId`s.
- [ ] Map Kitsu core, episodes, castings, staff, productions, relationships, and search paths to runtime-covered Kitsu `apiShapeId`s.
- [ ] Add tests proving every primary plan references an IntegrationRuntime-covered active shape.

## 3. Secondary Resolver Gate

- [ ] Add `ResolverOrchestrator` and resolver depth policy.
- [ ] Add resolver interfaces for ratings, artwork, reviews, tracking, skip segments, trailers, recommendations, and organization/person enrichment.
- [ ] Add tests proving each resolver runs only at allowed depths.

## 4. Field Ownership Gate

- [ ] Add `FieldResolver`.
- [ ] Encode primary-owned fields for TMDB, TVDB, and Kitsu.
- [ ] Encode secondary-owned fields for ratings, artwork, reviews, tracking, skip segments, trailers, recommendations, and organization/person enrichment.
- [ ] Add tests proving secondary providers cannot overwrite primary-owned fields and ignored overwrites are traceable.

## 5. Cache And Artwork Gate

- [ ] Add router decision/id mapping cache semantics.
- [ ] Add resolved document cache semantics.
- [ ] Add artwork decision cache keyed by artwork policy version.
- [ ] Keep image/blob cache separate from provider metadata cache.
- [ ] Add tests proving premium artwork changes invalidate artwork decisions and resolved display documents without invalidating primary metadata.

## 6. Continue Watching Gate

- [ ] Persist `parentId` and provider route at playback start.
- [ ] Persist click-time addon `HomeDisplayMetadata`.
- [ ] Backfill existing `WatchProgress` rows deterministically.
- [ ] Update Continue Watching render merge order.
- [ ] Add tests for Crunchyroll Kitsu route reuse and offline click-time metadata fallback.

## 7. Migration And Boundary Gate

- [ ] Migrate production callers away from `TvMetadataRouter`.
- [ ] Remove or retire `TvMetadataRouter` after callers move.
- [ ] Add architecture tests banning `TvMetadataRouter` imports in production call sites.
- [ ] Add architecture tests banning raw provider APIs, auth services, Retrofit, and OkHttp clients in router/resolver layers.
- [ ] Re-run the IntegrationRuntime audit and focused MetadataRouter gate tests.
