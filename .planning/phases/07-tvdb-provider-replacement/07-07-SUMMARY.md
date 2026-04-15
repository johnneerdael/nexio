---
phase: 07-tvdb-provider-replacement
plan: 07
subsystem: metadata
tags: [android, kotlin, tvdb, tmdb, metadata, routing, cache]

requires:
  - phase: 07-tvdb-provider-replacement
    provides: TVDB metadata router and TVDB metadata service from 07-02
provides:
  - TMDB fallback settings guard inside TV metadata routing
  - TVDB season request failure handling that skips empty authoritative cache writes
  - Regression tests for disabled TMDB fallback and failed TVDB season cache behavior
affects: [tvdb-provider-routing, tmdb-fallback, tvdb-season-cache]

tech-stack:
  added: []
  patterns:
    - TDD regression coverage with MockK zero-call assertions
    - Provider fallback reads TMDB settings before TMDB ID resolution
    - TVDB season cache writes happen only after successful HTTP responses

key-files:
  created:
    - .planning/phases/07-tvdb-provider-replacement/07-07-SUMMARY.md
  modified:
    - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt

key-decisions:
  - "TMDB fallback uses TmdbSettingsDataStore.settings.first().isActive inside the router before any TMDB ID or metadata service calls."
  - "TVDB season request exceptions and non-success responses return empty results before cache writes, while successful empty responses remain cacheable."

patterns-established:
  - "Disabled fallback decisions preserve the original provider, reason, diagnostics, and empty value shape without fabricating TMDB data."
  - "TVDB season cache entries represent successful HTTP responses only, including intentionally empty 200 responses."

requirements-completed: [PREF-02, PREF-03, META-02]

duration: 8 min
completed: 2026-04-15
---

# Phase 07 Plan 07: Core Provider Gap Closure Summary

**TMDB fallback settings enforcement plus TVDB season failure cache protection.**

## Performance

- **Duration:** 8 min
- **Started:** 2026-04-15T10:17:04Z
- **Completed:** 2026-04-15T10:25:23Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- Added `TmdbSettingsDataStore` to `TvMetadataRouter` and guarded all TMDB fallback helpers with `settings.first().isActive` before TMDB ID resolution.
- Preserved fallback diagnostics and decision shape when TMDB fallback is disabled: null enrichment, empty episode maps, and empty season lists.
- Changed `TvdbMetadataService.fetchSeasonEpisodes` so thrown and non-success TVDB season requests return before `writeTvdbSeasonEpisodes`.
- Added regression tests for disabled TMDB fallback branches and for TVDB season failure cache writes, including successful empty 200 responses remaining cacheable.

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: disabled TMDB fallback router regressions** - `a80cb321c` (test)
2. **Task 1 GREEN: TMDB fallback settings guard** - `f382d8970` (fix)
3. **Task 2 RED: TVDB season failure cache regressions** - `d6cc053e3` (test)
4. **Task 2 GREEN: failed TVDB season request cache protection** - `9fd0be3de` (fix)

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt` - Injects TMDB settings and skips TMDB fallback helper work when TMDB is disabled or inactive.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt` - Returns before season cache writes on thrown or non-success TVDB season requests.
- `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt` - Adds disabled-fallback zero-call coverage for inactive, record-missing, episode, and season fallback branches.
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt` - Adds thrown, non-success, and successful-empty season response cache coverage.

## Decisions Made

- Used `TmdbSettingsDataStore.settings.first().isActive` in the router rather than duplicating setting fields or checking only an API key.
- Left the successful empty season response cacheable because a 200 response with no episodes is different from a transient or authorization failure.
- Did not touch `HomeViewModelContinueWatching.kt` or `HomeViewModelTvdbProviderRoutingTest.kt`; those files were owned by concurrent Plan 07-08 execution.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- Targeted unit-test commands could not execute because `:app:compileArm64DebugUnitTestKotlin` is blocked by unrelated compile debt outside this plan's owned files.
- Blocking files observed across targeted and combined runs include `ProfileManagerTest.kt`, `AndroidTvSearchSuggestionMapperTest.kt`, `PlayerSettingsDataStoreSpoolModeTest.kt`, `PlayerSettingsDataStoreTest.kt`, `SearchHistoryDataStoreTest.kt`, `ThemeDataStoreProfileTest.kt`, `SearchViewModelHistoryTest.kt`, `CatalogSelectionPersistenceTest.kt`, `PlaybackSettingsViewModelSpoolModeTest.kt`, `SimklViewModelTest.kt`, `TraktViewModelPriorityHydrationTest.kt`, and the concurrent 07-08-owned `HomeViewModelTvdbProviderRoutingTest.kt`.
- Kotlin daemon startup repeatedly rejected `ZGenerational` and fell back to non-daemon compilation; source compilation still completed successfully.

## Verification

- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvMetadataRouterTest"` - blocked by unrelated unit-test compile debt.
- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"` - blocked by unrelated unit-test compile debt.
- `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvMetadataRouterTest" --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"` - blocked by unrelated unit-test compile debt.
- `./gradlew compileArm64DebugKotlin` - passed.
- Acceptance checks confirmed required guard/test names, `tmdbSettingsDataStore.settings.first().isActive`, non-success status logging, and zero-call cache assertions are present.

## Known Stubs

None. Empty lists, empty maps, and null values in the touched paths are intentional absent-provider or failed-request results, not UI placeholders or unwired data sources.

## Threat Flags

None. The changes did not add new public network endpoints, credential storage, file access boundaries, or auth paths.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

The two verifier gaps owned by Plan 07-07 are closed at source level. Remaining validation depends on resolving the unrelated unit-test compile debt so the new regression tests can execute.

## Self-Check: PASSED

- Found `.planning/phases/07-tvdb-provider-replacement/07-07-SUMMARY.md`.
- Found all four `07-07` task commits in git history.
- Verified `./gradlew compileArm64DebugKotlin` exits 0 after implementation.
- Left `.planning/STATE.md`, `.planning/ROADMAP.md`, 07-08-owned files, and unrelated dirty worktree changes unstaged and uncommitted.

---
*Phase: 07-tvdb-provider-replacement*
*Completed: 2026-04-15*
