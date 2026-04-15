---
phase: 09-tvdb-advanced-tv-surfaces
plan: 00
subsystem: testing
tags: [tvdb, junit, mockk, validation-scaffold, wave-0]

# Dependency graph
requires:
  - phase: 07-tvdb-provider-replacement
    provides: TvMetadataModels, TvMetadataDiagnostics, TvdbMetadataService, TvMetadataRouter, TvdbApi, TvdbSettingsScreen
provides:
  - Wave 0 failing test scaffold for META-03 season-order preservation
  - Wave 0 failing test scaffold for META-05 advanced metadata and trailer replacement
  - Wave 0 passing test scaffold for UX-02 no-provider-toggle guard
  - Wave 0 passing provider-routing call-count verification
  - Wave 0 passing detail season-tab stability verification
affects: [09-01, 09-02, 09-03, 09-04, 09-05]

# Tech tracking
tech-stack:
  added: []
  patterns: [static-source-guard-test, scaffold-failing-test-for-future-plan]

key-files:
  created:
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbSeasonOrderMapperTest.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbProviderRoutingTest.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbAdvancedMetadataMapperTest.kt
    - app/src/test/java/com/nexio/tv/data/trailer/TrailerServiceTvdbTest.kt
    - app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbSeasonOrderTest.kt
    - app/src/test/java/com/nexio/tv/ui/screens/settings/TvdbSettingsNoAdvancedToggleTest.kt
  modified: []

key-decisions:
  - "Used assert(field exists) via reflection for scaffold tests that need a not-yet-created field (tvdbEpisodeOrder)"
  - "Used Class.forName for scaffold tests that need a not-yet-created mapper class (TvdbAdvancedMetadataMapper)"
  - "TvdbSettingsNoAdvancedToggleTest reads source files at test time as a static guard rather than using Compose test rules"

patterns-established:
  - "Static source guard: read .kt and .xml source files in JUnit to assert forbidden phrases never appear"
  - "Scaffold failing test: test compiles and runs but asserts on not-yet-created fields/classes to define future contracts"

requirements-completed: []

# Metrics
duration: 6min
completed: 2026-04-15
---

# Phase 09 Plan 00: Wave 0 Validation Scaffold Summary

**Failing test scaffold for TVDB season-order, advanced metadata, trailer priority, provider routing, and no-toggle guard**

## Performance

- **Duration:** 6 min
- **Started:** 2026-04-15T15:47:27Z
- **Completed:** 2026-04-15T15:53:01Z
- **Tasks:** 3
- **Files created:** 6

## Accomplishments
- Verified all 6 Phase 7 TVDB provider source files exist (gate passed)
- Created 13 tests across 6 test classes defining Phase 9 contracts
- 9 tests fail as expected (scaffold for Plans 09-01 through 09-03)
- 4 tests pass (contract preservation: provider routing, season tabs, progress keys, no-toggle guard)

## Task Commits

Each task was committed atomically:

1. **Task 1: Gate on Phase 7 TVDB provider outputs** - no commit (verification-only task, gate passed)
2. **Task 2: Add season-order and provider-routing failing tests** - `15df6f643` (test)
3. **Task 3: Add advanced metadata, trailer, and no-toggle failing tests** - `15df6f643` (test)

Tasks 2 and 3 committed together as a single atomic commit since all 6 test files form one validation scaffold.

## Files Created
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbSeasonOrderMapperTest.kt` - META-03 season-order preservation: canonical numbering stability, TVDB default order storage, specials placement
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbProviderRoutingTest.kt` - META-05 skipped TMDB TV calls when TVDB succeeds, diagnostic TMDB_TV_SKIPPED assertion
- `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbSeasonOrderTest.kt` - META-03 detail season tabs and episode progress map keyed by canonical pairs
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbAdvancedMetadataMapperTest.kt` - META-05 cast/company/network/genre/content-rating mapping from TVDB DTOs
- `app/src/test/java/com/nexio/tv/data/trailer/TrailerServiceTvdbTest.kt` - META-05 TVDB trailer priority, TMDB fallback, unsupported URL diagnosis
- `app/src/test/java/com/nexio/tv/ui/screens/settings/TvdbSettingsNoAdvancedToggleTest.kt` - UX-02 static guard against provider-specific advanced/timing toggles

## Test Results

| Test Class | Tests | Pass | Fail | Status |
|------------|-------|------|------|--------|
| TvdbSeasonOrderMapperTest | 3 | 0 | 3 | Scaffold (awaits Plan 09-01) |
| TvdbProviderRoutingTest | 1 | 1 | 0 | Green (router already skips TMDB) |
| MetaDetailsTvdbSeasonOrderTest | 2 | 2 | 0 | Green (canonical behavior preserved) |
| TvdbAdvancedMetadataMapperTest | 3 | 0 | 3 | Scaffold (awaits Plan 09-02) |
| TrailerServiceTvdbTest | 3 | 0 | 3 | Scaffold (awaits Plan 09-03) |
| TvdbSettingsNoAdvancedToggleTest | 1 | 1 | 0 | Green (no forbidden toggles) |
| **Total** | **13** | **4** | **9** | |

## Decisions Made
- Used reflection-based field existence checks for `tvdbEpisodeOrder` (not yet on `Video`) to create compile-safe scaffold tests that fail with clear messages
- Used `Class.forName` for `TvdbAdvancedMetadataMapper` class existence checks to scaffold mapper contract tests
- Static source guard test reads `.kt` and `.xml` files at test time rather than using Compose testing infrastructure, keeping the guard lightweight and framework-independent
- Fixed `TvdbSettings` constructor usage: `isActive` is a computed val requiring `enabled=true`, `apiKey` non-blank, and `validationStatus=VALID`
- Fixed `PosterShape.REGULAR` to `PosterShape.POSTER` (enum only has POSTER, LANDSCAPE, SQUARE)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed TvdbSettings constructor in TvdbProviderRoutingTest**
- **Found during:** Task 2 (provider routing test)
- **Issue:** Used `TvdbSettings(isActive = true)` but `isActive` is a computed val, not a constructor parameter
- **Fix:** Changed to `TvdbSettings(enabled = true, apiKey = "fake-key", validationStatus = TvdbValidationStatus.VALID)`
- **Files modified:** TvdbProviderRoutingTest.kt
- **Committed in:** 15df6f643

**2. [Rule 1 - Bug] Fixed PosterShape enum value in MetaDetailsTvdbSeasonOrderTest**
- **Found during:** Task 2 (detail season order test)
- **Issue:** Used `PosterShape.REGULAR` which doesn't exist; enum values are POSTER, LANDSCAPE, SQUARE
- **Fix:** Changed to `PosterShape.POSTER`
- **Files modified:** MetaDetailsTvdbSeasonOrderTest.kt
- **Committed in:** 15df6f643

---

**Total deviations:** 2 auto-fixed (2 bugs)
**Impact on plan:** Both fixes necessary for compilation. No scope creep.

## Issues Encountered
None beyond the auto-fixed deviations above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- All 6 Wave 0 test files exist and compile
- 9 scaffold tests define contracts for Plans 09-01 (season order mapper), 09-02 (advanced metadata mapper), and 09-03 (trailer service TVDB support)
- 4 tests already pass, confirming existing canonical behavior and no-toggle guard are stable
- Phase 7 TVDB provider gate passed - all source files present with required symbols

---
*Phase: 09-tvdb-advanced-tv-surfaces*
*Completed: 2026-04-15*
