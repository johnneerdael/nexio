---
phase: 07-tvdb-provider-replacement
plan: 02
subsystem: metadata
tags: [android, kotlin, tvdb, tmdb, metadata, routing, cache]

requires:
  - phase: 07-tvdb-provider-replacement
    provides: Provider-neutral TV metadata models, diagnostics, and TVDB cache methods from 07-01
  - phase: 06-tvdb-foundation-and-identity
    provides: TVDB settings, auth service, API foundation, and identity lookup services
provides:
  - TVDB extended-series and season-episode API endpoints and DTOs
  - TVDB metadata service mapping series, artwork, schedule, ratings, remote IDs, and episodes
  - TVDB-first TV metadata router with explicit TMDB fallback diagnostics
affects: [tvdb-provider-routing, detail-tv-metadata, home-tv-metadata, continue-watching-tv-metadata]

tech-stack:
  added: []
  patterns:
    - Test-first Kotlin service/router implementation with MockK
    - TVDB cache keys use series ID, record kind or season type, language, and poster-provider token
    - TMDB fallback adapters set provider-neutral TVDB IDs to null

key-files:
  created:
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt

key-decisions:
  - "Injected TvdbAuthService into TvdbMetadataService because this checkout's TvdbApi requires explicit Authorization headers rather than a bearer interceptor."
  - "Adapted TvMetadataRouter to the existing Phase 6 resolveSeriesByRemoteId/resolveSeriesByTvdbId API instead of editing identity service outside plan ownership."
  - "Kept TMDB fallback adapter IDs nullable for seriesTvdbId rather than deriving TVDB IDs from TMDB or fallback content IDs."

patterns-established:
  - "TVDB success diagnostics include TVDB_SUCCESS and TMDB_TV_SKIPPED; fallback diagnostics include the concrete missing/inactive reason plus TVDB_FALLBACK_TMDB."
  - "Poster-ratings resolution is applied only to TVDB poster URLs after native TVDB artwork selection; backdrops, logos, and episode thumbnails remain provider-derived."
  - "TVDB artwork selection sorts non-empty artwork by score descending and maps type 2 to poster, type 3 to backdrop, and type 23 to logo."

requirements-completed: [PREF-02, PREF-03, PREF-07, META-01, META-02, META-04]

duration: 14 min
completed: 2026-04-15
---

# Phase 07 Plan 02: TVDB Metadata Service and Router Summary

**TVDB series and episode metadata mapping with a TVDB-first router and explicit TMDB fallback diagnostics.**

## Performance

- **Duration:** 14 min
- **Started:** 2026-04-15T03:40:56Z
- **Completed:** 2026-04-15T03:55:10Z
- **Tasks:** 3
- **Files modified:** 5

## Accomplishments

- Extended the Phase 6 TVDB Retrofit API with `/series/{id}/episodes/{seasonType}` and expanded extended-series/episode DTOs without adding new login or credential storage code.
- Added `TvdbMetadataService` to map TVDB series, artwork, schedule, rating, remote-ID, and episode records into the provider-neutral contracts from Plan 07-01.
- Added `TvMetadataRouter` so active TVDB series success returns `TvProvider.TVDB`, records skipped-TMDB diagnostics, and calls TMDB only through explicit inactive/missing/record-missing fallback branches.
- Added TDD tests for TVDB API DTO coverage, poster-only provider overrides, cache-before-network behavior, no-TMDB-call TVDB success, TMDB fallback, and null `seriesTvdbId` adapter behavior.

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: TVDB metadata API contract tests** - `8fb6d8343` (test)
2. **Task 1 GREEN: TVDB metadata API contract** - `8290f85e5` (feat)
3. **Task 2 RED: TVDB metadata service tests** - `f567dda23` (test)
4. **Task 2 GREEN: TVDB metadata service** - `ff6bcaee4` (feat)
5. **Task 3 RED: TV metadata router tests** - `ce337a0e8` (test)
6. **Task 3 GREEN: TVDB-first metadata router** - `c31f64eea` (feat)

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt` - Added TVDB season episodes endpoint and metadata DTOs for extended series, artwork, ratings, companies, genres, status, remote IDs, and episodes.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt` - Added authenticated TVDB metadata service with TVDB cache reads/writes, artwork mapping, poster-only provider override, and episode mapping.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt` - Added TVDB-first routing for series/TV with movie TMDB direct handling and explicit TMDB fallback diagnostics.
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt` - Added API DTO, service mapping, poster precedence, episode mapping, and cache-first tests.
- `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt` - Added provider routing, no-TMDB-call, fallback, and adapter-nullability tests.

## Decisions Made

- Used `TvdbAuthService.bearerToken()` inside `TvdbMetadataService` because `TvdbApi` still takes an explicit `Authorization` header for every authenticated TVDB endpoint.
- Used existing Phase 6 identity methods (`resolveSeriesByRemoteId`, `resolveSeriesByTvdbId`) inside `TvMetadataRouter` rather than broadening this plan to modify `TvdbIdentityService`.
- Returned `null` `seriesTvdbId` for TMDB movie and TV fallback enrichment so downstream code cannot mistake a TMDB ID or fallback ID for a TVDB identity.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Adapted router identity resolution to current Phase 6 API**
- **Found during:** Task 3 (Implement TVDB-first router with explicit TMDB fallback)
- **Issue:** The plan expected `TvdbIdentityService.resolveSeries(contentId, contentType)`, but the current Phase 6 source exposes `resolveSeriesByRemoteId` and `resolveSeriesByTvdbId`.
- **Fix:** Implemented identity resolution inside `TvMetadataRouter` using the existing Phase 6 methods, parsing TVDB, IMDb, TMDB, and unknown IDs locally.
- **Files modified:** `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt`, `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt`
- **Verification:** `./gradlew compileArm64DebugKotlin` exits 0; router tests compile until the global unit-test compile task reaches unrelated existing test errors.
- **Committed in:** `ce337a0e8`, `c31f64eea`

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** The change kept the router behavior and ownership intact without editing Phase 6 identity service outside this plan's file boundary.

## Issues Encountered

- The targeted unit-test command could not complete because `:app:compileArm64DebugUnitTestKotlin` still fails in unrelated existing tests outside this plan. Current failures include `PlayerSettingsDataStore*`, `AndroidTvSearchSuggestionMapperTest`, `ProfileManagerTest`, `SearchHistoryDataStoreTest`, settings ViewModel tests, and related constructor/signature drift. This matches the unit-test compile debt recorded in Plan 07-01.
- `./gradlew compileArm64DebugKotlin` passed after the implementation, proving app source compilation for the new TVDB API, service, and router.
- Kotlin daemon startup repeatedly reported unsupported `ZGenerational` and fell back to non-daemon compilation; source compile completed successfully after fallback.

## Known Stubs

None. Nullable values, `emptyList()`, and `emptyMap()` defaults in touched files are DTO defaults, absent-provider values, fallback outputs, or test fixture fields rather than UI placeholders or unwired mock data.

## Threat Flags

None. This plan added provider service/router code and Retrofit methods but no new public network endpoint, new credential storage, new auth path, or new file access boundary. TVDB credentials continue to flow through Phase 6 auth/token services.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Ready for downstream Detail, Home, and Continue Watching plans to inject `TvMetadataRouter` and replace direct TV `TmdbService.ensureTmdbId` plus `TmdbMetadataService` calls. The remaining blocker is external unit-test compile debt, not this plan's source code.

## Self-Check: PASSED

- Found all created/modified plan-owned files.
- Found all six `07-02` task commits in git history.
- Verified `./gradlew compileArm64DebugKotlin` exits 0 after implementation.
- Left `.planning/STATE.md` and `.planning/ROADMAP.md` unstaged and uncommitted as requested; existing unrelated dirty worktree changes remain preserved.

---
*Phase: 07-tvdb-provider-replacement*
*Completed: 2026-04-15*
