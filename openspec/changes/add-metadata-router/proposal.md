# Change: Add MetadataRouter

## Why

Nexio currently has provider-specific metadata routing logic centered on TVDB-era paths and detail-specific flows. That makes anime detection, addon-first rendering, artwork policy changes, secondary enrichment, and Continue Watching metadata parity difficult to prove.

The IntegrationRuntime control plane is now auditable enough to support a higher-level routing layer. The next architecture step is to add a MetadataRouter stack above IntegrationRuntime that chooses one primary provider, runs secondary resolvers by depth, resolves final fields through explicit ownership rules, and migrates callers away from `TvMetadataRouter`.

## What Changes

- Add a deterministic `MetadataRouter` with route precedence:
  1. anime id prefix
  2. catalog/source anime hint
  3. `IdMappingStore` / Fribb
  4. per-item type fallback
- Add request normalization and parent-id extraction for movie, series, anime, and episode ids.
- Add provider plan execution for TMDB, TVDB, and Kitsu primary metadata routes.
- Add resolver orchestration for ratings, artwork, reviews, trailers, skip segments, tracking, recommendations, and organization/person enrichment.
- Add field ownership enforcement through `FieldResolver`.
- Add separate caches for route decisions/id mappings, primary metadata, resolved documents, artwork decisions, and image bytes.
- Persist Continue Watching parent id, provider route, and click-time addon metadata.
- Migrate production call sites off `TvMetadataRouter`.
- Add test-only staged gates proving each layer before the cycle is complete.

## Impact

- Home/discover rendering remains addon-first and fast.
- Crunchyroll-style `tt...` anime can route to Kitsu through catalog hints before Fribb fallback.
- Disney mixed rows use per-item type rather than catalog manifest type.
- Secondary providers stop competing with primary-owned metadata fields.
- Artwork policy changes update selected posters without invalidating primary provider metadata.
- Continue Watching preserves the metadata and provider decision from playback start.
- IntegrationRuntime remains the only provider network policy layer.

## Out Of Scope

- Replacing IntegrationRuntime.
- Adding a new provider network client outside provider integration adapters.
- Letting secondary providers decide primary metadata authority.
- Keeping `TvMetadataRouter` as a long-term compatibility facade.
