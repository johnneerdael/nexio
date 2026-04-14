# Phase 7: TVDB Provider Replacement - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 7 replaces TMDB as the normal TV metadata provider when TVDB is configured. The phase covers TV detail enrichment, TV Home and hero enrichment, episode metadata, TV artwork, Continue Watching display/runtime metadata, poster-ratings precedence, settings-facing provider rules, and tests or instrumentation proving normal TV success paths skip TMDB TV metadata fetches.

This phase does not replace TMDB for movies. TMDB remains the movie metadata provider and the explicit TV fallback when TVDB is inactive or when TVDB cannot safely satisfy a required TV record. Exact Continue Watching air-time gating is Phase 8. Advanced TVDB-specific surfaces and remaining TV surfaces such as trailers, cast/credits, companies, networks, genre/reference taxonomy, content-rating reference behavior, and season-order UI are Phase 9 unless they naturally fall out of the shared Phase 7 provider model without expanding scope.

</domain>

<decisions>
## Implementation Decisions

### Provider Routing
- **D-01:** TVDB is the authoritative metadata provider for `ContentType.SERIES` / TV when TVDB is active. TMDB stays authoritative for movies.
- **D-02:** Normal TV success paths must not call TMDB TV endpoints for duplicate TV metadata purposes. TVDB-enabled detail, Home, Continue Watching, episode, and artwork enrichment should resolve through TVDB first.
- **D-03:** TMDB TV calls are allowed only as explicit fallback when TVDB is inactive, invalid, unavailable, or lacks a required record. Fallback must be observable through diagnostics or logs so it is not a silent double-fetch.
- **D-04:** Preserve current user-facing metadata contracts. Map TVDB data into the existing `Meta`, `Video`, `HomeDisplayMetadata`, and enrichment-style roles instead of redesigning TV detail, Home, or Continue Watching UI in this phase.

### Surface Coverage
- **D-05:** Phase 7 should replace the TV metadata roles currently served by `TmdbMetadataService.fetchEnrichment`, `fetchEpisodeEnrichment`, and `fetchSeasonEpisodes` for the scoped TV surfaces: title/description/basic details, TV artwork, episode title/overview/image/runtime/air date, Home display metadata, Continue Watching labels and runtime metadata.
- **D-06:** The planner should inventory direct `TmdbService.ensureTmdbId` and `TmdbMetadataService` usage before implementation, then classify each use as movie-only, TV replacement, explicit TV fallback, or deferred Phase 9 surface.
- **D-07:** Do not broaden Phase 7 into TVDB trailer, review, related-content, cast/company/network/reference-taxonomy feature work. Basic detail fields already displayed by the central enrichment path can be populated when the TVDB replacement model naturally supports them, but dedicated advanced surfaces remain deferred unless a small provider abstraction is needed to keep Phase 7 routing coherent.

### Artwork And Poster Precedence
- **D-08:** Poster-ratings integrations remain the highest-precedence poster source for supported titles. TVDB must not override RPDB or TopPosters poster URLs when a poster-ratings provider can produce a supported poster.
- **D-09:** Poster precedence applies only to poster imagery. TVDB remains the preferred source for non-poster TV artwork such as backdrops, logos, and episode images when TVDB provides usable assets.
- **D-10:** Reuse `PosterRatingsUrlResolver` semantics where possible. Its existing TVDB ID support is relevant for TopPosters, while RPDB remains IMDb-only by design.

### Settings And Provider Copy
- **D-11:** Settings copy should clearly state the provider order: TVDB is used for TV metadata when configured, TMDB remains for movies and TV fallback, and poster-ratings providers override poster artwork where supported.
- **D-12:** Do not add a second granular TVDB toggle matrix in this phase. Existing metadata intent toggles can continue to control whether artwork/basic info/details/episodes are enriched, while provider routing decides whether the TV implementation uses TVDB or TMDB fallback.
- **D-13:** The TMDB settings screen copy should stop implying TMDB is the only metadata source for TV once TVDB is configured. Any new TVDB settings UI should fit the existing Integration settings hub patterns.

### Caching, Identity, And Diagnostics
- **D-14:** TVDB cache entries should be separate from TMDB cache entries and named/schema-versioned as TVDB data, not overloaded into `tmdb::` keys. Cache keys should include TVDB series identity, content type or record kind, language where applicable, and poster-provider token where poster output is affected.
- **D-15:** Phase 7 should consume the Phase 6 identity/auth/cache foundation rather than reimplementing TVDB login or remote-ID matching. If Phase 6 context or code is absent, planning must call that out as a dependency.
- **D-16:** Diagnostics should distinguish these cases: TVDB inactive, TVDB active and successful, TVDB active but falling back to TMDB, poster-ratings poster override applied, and TMDB TV fetch intentionally skipped.

### Verification
- **D-17:** Tests or instrumentation must cover at least one TVDB-enabled normal success path where TMDB TV metadata calls are not issued. Prefer call-count assertions against `TmdbApi` or `TmdbMetadataService` in the relevant detail/Home/Continue Watching path.
- **D-18:** Poster precedence tests should prove poster-ratings poster URLs survive TVDB replacement while TVDB backdrops/logos/episode images remain eligible.

### the agent's Discretion
- Exact class names and adapter shape for provider routing, such as whether to introduce a provider-neutral metadata service or a `TvdbMetadataService` that mirrors the existing TMDB enrichment API.
- Exact TVDB image selection heuristics, language fallback order, and cache TTL/schema version values.
- Exact wording of settings copy, as long as provider precedence is clear and not misleading.
- Exact test file placement and test granularity, as long as the normal TV success path skip-TMDB behavior is covered.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase Definition
- `.planning/ROADMAP.md` — Phase 7 goal, success criteria, dependency on Phase 6, and Phase 8/9 boundaries.
- `.planning/REQUIREMENTS.md` — Provider precedence, metadata value, artwork, UX, and out-of-scope requirements.
- `.planning/PROJECT.md` — Milestone goal and provider precedence decisions.

### TVDB API Reference
- `tvdb.yml` — Local OpenAPI reference for TheTVDB API v4. Do not read or expose `.thetvdb.apikey`.

### Current TMDB Provider And Metadata Shape
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt` — Current TMDB enrichment, episode, artwork, recommendations, reviews, and cache behavior to replace or classify for TV.
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbService.kt` — Current TMDB ID conversion and direct lookup helper; TV paths should avoid this in normal TVDB success behavior.
- `app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt` — TMDB endpoint surface and `TmdbDetailsResponse` / `TmdbEpisode` DTO shape.
- `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt` — Existing metadata disk cache schema, language epoch, provider token, and TMDB cache names.
- `app/src/main/java/com/nexio/tv/domain/model/Meta.kt` — Existing detail metadata and episode model to preserve.
- `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt` — Existing Home/Continue Watching display metadata contract.

### Current TV Surfaces To Replace Or Classify
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` — Detail enrichment, More Like This, reviews, episode enrichment, season episode list, and trailer availability TMDB call sites.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` — Continue Watching TMDB enrichment and episode overview lookup.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt` — Continue Watching runtime lookup through TMDB enrichment.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt` — Home row and hero metadata enrichment call sites.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt` — Startup prefetch behavior that warms metadata caches.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt` — Continue Watching snapshot production and air-date gating inputs.
- `app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt` — Existing date-only gating behavior used before Phase 8 exact air-time work.

### Poster And Settings Precedence
- `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt` — Poster-ratings precedence, provider parsing, and existing TVDB ID support.
- `app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt` — Existing poster resolver coverage.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsScreen.kt` — Current TMDB settings UI and copy to adjust.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsScreen.kt` — Existing poster-ratings settings UI pattern.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt` — Integration hub where TVDB provider settings should fit.

### Existing Verification Patterns
- `app/src/test/java/com/nexio/tv/core/tmdb/TmdbMetadataPerformanceTest.kt` — Existing TMDB enrichment call-count/cache tests.
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingTest.kt` — Existing Continue Watching enrichment tests.
- `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsSeasonMediaViewModelTest.kt` — Existing detail/season media tests involving TMDB metadata.
- `app/src/test/java/com/nexio/tv/ui/screens/detail/MarkSeasonWatchedTest.kt` — Existing season episode list tests that currently depend on TMDB season data.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `TmdbMetadataService` currently centralizes most TV metadata roles behind enrichment-style methods. A TVDB replacement can mirror this API to reduce blast radius while changing provider routing.
- `TmdbEnrichment` / `TmdbEpisodeEnrichment` already express the main metadata roles Phase 7 needs: localized title, description, genres, artwork, runtime, release info, rating, companies/networks, age rating, countries, language, episode title/overview/thumbnail/runtime/air date.
- `PosterRatingsUrlResolver` already treats posters as a separable override layer and can parse `tvdb:` IDs for TopPosters.
- `MetadataDiskCacheStore` already has language epoch and provider-token cache patterns that should inform a separate TVDB cache namespace.
- `Meta`, `Video`, and `HomeDisplayMetadata` provide stable UI-facing contracts; the phase should feed these models rather than changing screens first.

### Established Patterns
- Provider/settings services use Hilt singleton injection and DataStore-backed settings flows.
- TMDB enrichment is guarded by settings toggles and cache keys that include language and poster provider state.
- Home and Continue Watching enrichment run asynchronously and fall back to existing metadata when enrichment fails.
- Existing tests use mocked services and call-count assertions for cache and no-fetch behavior.

### Integration Points
- Detail screen: `MetaDetailsViewModel.enrichMeta`, `loadMoreLikeThisAsync`, `loadReviewsAsync`, season episode lists, and trailer availability preloads must be classified.
- Home screen: `HomeViewModelPresentationPipeline` and `HomeCatalogRefreshCoordinator` warm and apply metadata to rows and hero items.
- Continue Watching: `HomeViewModelContinueWatching` and `HomeViewModelContinueWatchingRuntimePipeline` currently call TMDB for display metadata and runtime.
- Settings: `SettingsScreen`, `TmdbSettingsScreen`, and a Phase 6 TVDB settings surface must communicate provider precedence.
- Cache: `MetadataDiskCacheStore` should gain TVDB-specific entries or be complemented by a TVDB-specific cache store.

</code_context>

<specifics>
## Specific Ideas

- Treat Phase 7 as a provider replacement and routing phase, not a TV UI redesign.
- Build the TVDB path so a single instrumentation test can prove TVDB active means no TMDB TV calls for a representative normal success path.
- Keep poster-ratings as a post-provider poster override layer rather than baking poster precedence into every TVDB mapper.
- Use explicit diagnostic states instead of silently blending TVDB and TMDB data.

</specifics>

<deferred>
## Deferred Ideas

- Exact Continue Watching availability instants from TVDB `airsTime` and episode aired date — Phase 8.
- Re-evaluation scheduling when withheld future episodes become available — Phase 8.
- TVDB default season ordering preservation and Trakt progress matching implications — Phase 9.
- TVDB trailers, remaining cast/credits, companies, networks, genre/reference taxonomy, content-rating reference behavior, and other advanced TV-specific surfaces — Phase 9.
- Update-aware TVDB cache invalidation, stable reference-data heavy caching, and user-facing TVDB docs — Phase 10.

</deferred>

---

*Phase: 07-tvdb-provider-replacement*
*Context gathered: 2026-04-14*
