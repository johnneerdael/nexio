# Add Metadata Router

## Why

Metadata authority currently depends on scattered provider-specific routing behavior. Real addon payloads require deterministic routing based only on item identity and item type so mixed catalog rows, anime IDs, IMDb anime rows, and provider-native IDs do not route through heuristics.

## What Changes

- Add a deterministic `MetadataRouter` with request normalization, route trace evidence, IdMappingStore support, and AnimeIdentityIndex support.
- Add provider plan execution, resolver orchestration, field ownership resolution, and cache-key separation for routed metadata.
- Route migrated detail, home, player, and Continue Watching callers through `MetadataRouterFacade`.
- Preserve addon-first preview rendering without invoking router/network work for preview depth.
- Gate implementation with router unit tests, Continue Watching tests, boundary tests, IntegrationRuntime audit readiness, and OpenSpec validation.

## Impact

- Routing authority is derived from item `id` and item `type` only.
- Catalog/source labels, genres, addon names, and `animeType` are not routing authority.
- MetadataRouter readiness depends on IntegrationRuntime-covered TMDB, TVDB, and Kitsu prerequisite shapes.
