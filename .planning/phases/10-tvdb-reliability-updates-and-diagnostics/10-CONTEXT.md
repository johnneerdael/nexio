# Phase 10: TVDB Reliability, Updates, and Diagnostics - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 10 makes TVDB metadata reliable over time. It covers update-aware TVDB cache invalidation, heavily cached stable reference data, graceful failure behavior, diagnostics for provider/cache/fallback decisions, and user-facing documentation. It depends on the TVDB foundation, provider replacement, exact Continue Watching air timing, and advanced TVDB surfaces from earlier phases.

This phase does not change provider precedence, redesign TV metadata surfaces, add new user toggles for unaired Continue Watching items, or require a TVDB caching proxy. TVDB remains authoritative for TV when configured, TMDB remains movies plus explicit TV fallback, and poster-ratings remains the poster authority.

</domain>

<decisions>
## Implementation Decisions

### Update-aware invalidation
- **D-01:** TVDB `/updates` is the primary freshness driver for TVDB metadata. Store the last successful update cursor, poll `/updates?since=...`, invalidate changed entity IDs, and use cache schema keys, language epochs, provider tokens, and record timestamps as safety checks.
- **D-02:** TVDB delete events should purge affected cache entries. Duplicate merge events should purge old IDs and remap to `mergeToType` / `mergeToId` where TVDB provides those fields.
- **D-03:** TVDB update checks should run as background periodic work plus app-start catch-up. Do not block normal metadata reads on inline `/updates` calls.

### Stable reference-data caching
- **D-04:** Stable TVDB reference data should use long-lived caches, refreshed through `/updates` when relevant reference entity types change and guarded by schema-version escape hatches.
- **D-05:** Reference-data refresh failures should use last-known-good cached data and expose the refresh failure through diagnostics. Stale labels are preferred over blank metadata or raw IDs.
- **D-06:** Once TVDB credentials validate, Nexio should warm core reference data during TVDB setup or startup, then refresh through update signals. Core references include artwork types, genres, languages, statuses, content ratings, season types, source types, entity types, and company types.

### Graceful failure behavior
- **D-07:** During temporary TVDB outages, TV detail and Continue Watching should serve last-known-good TVDB data when present. Use explicit fallback only when cached TVDB data cannot safely satisfy the surface, and record the reason.
- **D-08:** If TVDB credentials become invalid after previously working, keep cached TVDB data as last-known-good, block new TVDB network calls until credentials are fixed, surface invalid status, and use explicit fallback when needed.
- **D-09:** Missing TVDB fields should use field-level fallback with reason codes. Keep TVDB as the record provider, fill only missing fields from safe existing sources where allowed, and record reasons such as `missing_airs_time`, `date_only_gating`, or `poster_ratings_override`.

### Diagnostics and docs
- **D-10:** TVDB diagnostics should be visible in three layers: user-facing status in TVDB settings, detailed provider/cache/fallback diagnostics under Debug, and structured logs.
- **D-11:** Diagnostics must represent provider choice, fallback reason, missing `airsTime`, date-only gating, poster-ratings override, skipped TMDB TV fetches, update refresh status, stale cache served, and invalid credentials.
- **D-12:** User-facing docs should cover TVDB setup, TVDB/TMDB/poster-ratings precedence, exact Continue Watching air-time behavior, date-only fallback, stale-cache behavior, and where to find diagnostics.

### the agent's Discretion
- Exact WorkManager/job scheduling interval for periodic `/updates` checks, as long as startup catch-up and background periodic refresh are both present.
- Exact cache store shape, DTO names, and schema-version numbers.
- Exact diagnostic enum/event names and log tag names, as long as the decided reasons are represented.
- Exact placement of user-facing documentation, as long as setup, precedence, exact timing, fallback, stale-cache behavior, and diagnostics are covered.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase Definition
- `.planning/ROADMAP.md` - Phase 10 goal, success criteria, dependency on phases 8 and 9, and requirement mapping.
- `.planning/REQUIREMENTS.md` - UX-03, CACHE-02, CACHE-03, provider precedence, Continue Watching timing, and out-of-scope boundaries.
- `.planning/PROJECT.md` - Milestone goal, key decisions, provider precedence, poster-ratings precedence, and TVDB active requirements.
- `docs/brainstorms/2026-04-14-tvdb-first-class-tv-metadata-requirements.md` - Product requirements, TVDB API assumptions, and alternatives considered for first-class TVDB metadata.

### Prior Phase Context
- `.planning/phases/06-tvdb-foundation-and-identity/06-CONTEXT.md` - TVDB credential model, auth token caching, identity matching, and deferred `/updates` invalidation.
- `.planning/phases/07-tvdb-provider-replacement/07-CONTEXT.md` - Provider routing, poster precedence, TVDB cache namespace expectations, and deferred full diagnostics.

### TVDB API Contract
- `tvdb.yml` - Checked-in TVDB OpenAPI contract. Relevant sections include `/updates`, `EntityUpdate`, `SeriesBaseRecord`, `SeriesExtendedRecord`, `airsTime`, `lastUpdated`, reference entity endpoints/types, and delete/merge update fields.

### Cache and Metadata Code
- `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt` - Existing metadata disk cache schema, language epoch, provider-token keys, write batching, stale-epoch eviction, and TMDB video TTL pattern.
- `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt` - Existing cache compatibility, schema-version, eviction, and test helper patterns.
- `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreWriteBatchingTest.kt` - Existing write batching coverage.
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt` - Current metadata enrichment cache and provider-token behavior that TVDB cache behavior should not overload.

### Continue Watching and Timing Diagnostics
- `app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt` - Existing date-only and precise timestamp gate behavior that Phase 8 extends and Phase 10 diagnoses.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt` - Continue Watching snapshot production and next-up gating integration point.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` - Continue Watching metadata/fallback integration point.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt` - Continue Watching runtime metadata path.

### Diagnostics and Settings Surfaces
- `app/src/main/java/com/nexio/tv/data/local/DebugSettingsDataStore.kt` - Existing debug settings storage pattern.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/DebugSettingsScreen.kt` - Existing Debug settings UI surface for detailed diagnostics.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsScreen.kt` - Existing metadata provider settings UX pattern.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt` - Integration settings hub where TVDB settings and status are surfaced.
- `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt` - Poster-ratings override behavior that diagnostics must explain.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `MetadataDiskCacheStore` already provides schema-versioned disk cache entries, language epoch invalidation, provider-token-aware keys, pending write batching, and stale-entry cleanup. TVDB caches should extend or mirror these patterns with TVDB-specific namespaces rather than overloading `tmdb::` keys.
- `TmdbMetadataService` demonstrates enrichment cache key composition, active poster-provider tokens, disk cache reads before network calls, and safe fallback to null on missing API credentials.
- `AirDateGate` is the existing decision point for date-only and timestamp-based Continue Watching visibility. Phase 10 diagnostics should explain the Phase 8 exact-air-time and date-only branches that flow through this area.
- `DebugSettingsDataStore` and `DebugSettingsScreen` provide the existing place for developer-oriented diagnostic controls and status displays.
- `PosterRatingsUrlResolver` already isolates poster-ratings precedence and should feed diagnostics for poster override decisions.

### Established Patterns
- Existing metadata cache entries use explicit schema versions and language epochs to make old entries safe to ignore.
- Home and Continue Watching metadata paths already preserve existing data when enrichment fails; Phase 10 should formalize this into last-known-good and explicit fallback behavior for TVDB.
- User-facing settings are kept relatively simple, while power-user details generally belong in Debug settings and logs.
- Provider precedence has been treated as a routing concern, not a screen redesign concern.

### Integration Points
- Add TVDB update cursor storage and update processing in the TVDB cache/foundation layer from Phase 6.
- Add TVDB reference-data cache storage and warming once TVDB credentials validate.
- Extend TVDB provider routing/fallback results from Phase 7 so callers can emit structured diagnostic reasons.
- Add diagnostic display/state under TVDB settings and Debug settings.
- Update user-facing documentation with setup, precedence, exact timing, fallback, stale-cache, and diagnostics guidance.

</code_context>

<specifics>
## Specific Ideas

- Treat `/updates` as the normal freshness source rather than a best-effort hint.
- Prefer last-known-good TVDB data over blanking TV detail or Continue Watching during outages.
- Keep invalid credentials visible and stop repeated unauthorized calls until credentials are fixed.
- Use field-level fallback rather than record-level fallback for missing TVDB fields.
- Keep diagnostic details out of normal browsing UI; use TVDB settings for status, Debug settings for detail, and logs for structured events.

</specifics>

<deferred>
## Deferred Ideas

None - discussion stayed within phase scope.

</deferred>

---

*Phase: 10-tvdb-reliability-updates-and-diagnostics*
*Context gathered: 2026-04-14*
