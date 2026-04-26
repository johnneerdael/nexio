# Tasks

## 1. Routing And Identity Gate

- [ ] Add input-contract tests proving only item `id` and item `type` are routing authority.
- [ ] Add request normalization and `parentIdOf()` behavior.
- [ ] Add `MetadataRouter` route result model with trace evidence.
- [ ] Add `IdMappingStore` with `(sourceScheme, sourceId)` keying, mapping source semantics, persist-enforced overwrite priority, and Fribb-hit persistence.
- [ ] Add AnimeIdentityIndex / Fribb lookup for MAL, AniList, AniDB, and IMDb anime detection.
- [ ] Add tests for `kitsu:` direct routing, MAL/AniList/AniDB-to-Kitsu mapping, IMDb local/Fribb anime mapping, provider-native TMDB/TVDB direct routing, provider-native conflict tracing, forbidden catalog-label routing, item-type fallback, Disney mixed rows, and episode parent normalization.
- [ ] Add tests proving AnimeIdentityIndex rejects TMDB/TVDB ids and MetadataRouter rejects `PREVIEW` requests.

## 2. Primary Plan Execution Gate

- [ ] Add `ProviderPlanExecutor`.
- [ ] Add guard refusing to build provider calls while `targetIdRequiresIdentityResolution` is true.
- [ ] Add provider-adapter or identity-helper path that resolves `targetIdRequiresIdentityResolution` before IntegrationRuntime execution.
- [ ] Add tests proving ProviderPlanExecutor uses `route.mediaKind`, not original request `ContentType`.
- [ ] Add tests proving TVDB translation is scheduled only when requested language differs from default/base language.
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
- [ ] Persist routing policy version at playback start and reroute stale entries once.
- [ ] Document and enforce that routing precedence, AnimeIdentityIndex behavior, or IdMappingStore semantic changes bump the routing policy version.
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

## P0 Production Ownership Remediation

- [ ] MetadataRouterFacade executes ProviderExecutionPlan through runtime-backed adapters.
- [ ] ProviderMetadataRouter/TvMetadataRouter are not invoked by MetadataRouterFacade.
- [ ] Provider-native conflicts are identity-resolved before ProviderPlanExecutor builds provider calls.
- [ ] PREVIEW depth is addon-only and does not call router/provider/network paths.
- [ ] FieldResolver creates final resolved metadata output for home/detail/player/CW paths.
- [ ] UI/ViewModel/Worker metadata paths do not call TmdbMetadataService, KitsuMetadataService, TvdbMetadataService, ProviderMetadataRouter, TvMetadataRouter, Retrofit APIs, auth services, or OkHttp for final metadata output.
- [ ] Continue Watching stale routing versions reroute once and persist upgraded snapshots.
- [ ] Architecture tests fail on legacy metadata execution paths.
