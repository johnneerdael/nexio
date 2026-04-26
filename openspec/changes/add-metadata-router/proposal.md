# Change: Add MetadataRouter

## Why

Nexio currently has provider-specific metadata routing logic centered on TVDB-era paths and detail-specific flows. That makes anime detection, addon-first rendering, artwork policy changes, secondary enrichment, and Continue Watching metadata parity difficult to prove.

The IntegrationRuntime control plane is now auditable enough to support a higher-level routing layer. The next architecture step is to add a MetadataRouter stack above IntegrationRuntime that chooses one primary provider, runs secondary resolvers by depth, resolves final fields through explicit ownership rules, and migrates callers away from `TvMetadataRouter`.

## What Changes

- Add a deterministic `MetadataRouter` with route precedence:
  1. `kitsu:` prefix routes directly to Kitsu.
  2. `mal:`, `anilist:`, and `anidb:` prefixes map to Kitsu through AnimeIdentityIndex / Fribb or local mapping.
  3. IMDb ids (`tt...` / `imdb:...`) map to Kitsu only through `IdMappingStore` or AnimeIdentityIndex / Fribb.
  4. Provider-native ids route directly only when prefix agrees with item type (`tmdb:` + movie, `tvdb:` + series).
  5. Provider-native prefix/type conflicts emit `ROUTING_ID_TYPE_CONFLICT`, keep the original parent id as target evidence, and use explicit item-type fallback.
  6. Per-item type fallback routes `series` to TVDB and `movie` to TMDB.
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
- Initial catalog preview rendering does not execute MetadataRouter for every row item.
- Anime `tt...` ids can route to Kitsu only through deterministic IdMappingStore or AnimeIdentityIndex / Fribb evidence.
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
