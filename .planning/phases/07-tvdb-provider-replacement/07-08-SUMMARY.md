---
phase: 07-tvdb-provider-replacement
plan: 08
subsystem: metadata
tags: [android, kotlin, tvdb, home, continue-watching, provider-routing]

requires:
  - phase: 07-tvdb-provider-replacement
    provides: Continue Watching TVDB provider routing from 07-04
provides:
  - Provider-neutral Continue Watching metadata enrichment gate
  - TVDB-only Continue Watching gate coverage for resume and Trakt next-up rows
  - TVDB-only Continue Watching display metadata regression coverage with zero direct TMDB calls
affects: [home-tv-metadata, continue-watching-tv-metadata, tvdb-provider-routing]

tech-stack:
  added: []
  patterns:
    - Continue Watching provider enrichment uses a shared gate that preserves useBasicInfo intent while allowing TV rows through router-owned provider decisions
    - TVDB-only tests assert router requests and strict no-direct-TMDB-call behavior

key-files:
  created: []
  modified:
    - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt
    - app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt

key-decisions:
  - "Kept TVDB active/inactive checks inside TvMetadataRouter; the Home gate only decides whether TV/series rows are eligible for provider enrichment."
  - "Kept movie-only Continue Watching rows blocked when TMDB is disabled so TVDB-only eligibility does not broaden movie metadata behavior."

patterns-established:
  - "Use shouldEnrichContinueWatchingProviderMetadata for all Continue Watching provider metadata entry points."
  - "Use TmdbSettings.useBasicInfo as the top-level user intent switch for provider metadata enrichment."

requirements-completed: [PREF-02, PREF-03, META-01, META-02, META-04]

duration: 9 min
completed: 2026-04-15
---

# Phase 07 Plan 08: Continue Watching TVDB-Only Enrichment Gate Summary

**Continue Watching series rows can now reach TVDB-backed provider enrichment when TMDB is disabled, with regression coverage for TVDB-only metadata merges.**

## Performance

- **Duration:** 9 min
- **Started:** 2026-04-15T10:17:31Z
- **Completed:** 2026-04-15T10:25:47Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- Added `shouldEnrichContinueWatchingProviderMetadata`, a shared provider-neutral gate for Continue Watching metadata enrichment.
- Wired both snapshot collection and manual refresh enrichment paths through the same gate.
- Preserved `useBasicInfo` as the hard opt-out, preserved TMDB-active behavior, and kept movie-only rows blocked when TMDB is disabled.
- Added gate tests for TVDB-eligible resume rows, TVDB-eligible Trakt next-up rows, movie-only disabled-TMDB rows, disabled basic-info settings, and TMDB-active movie rows.
- Added TVDB-only Continue Watching display metadata coverage that asserts title, description, genres, poster, backdrop, logo, release info, rating, and episode overview merge without direct TMDB calls.

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: Continue Watching provider gate tests** - `797a4824a` (test)
2. **Task 1 GREEN: Provider-neutral Continue Watching enrichment gate** - `e72285ac3` (feat)
3. **Task 2: TVDB-only Continue Watching metadata regression** - `76e336ca1` (test)

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` - Added the shared enrichment gate and used it in both Continue Watching enrichment entry points.
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt` - Added TVDB-only gate and metadata regression coverage, plus updated stale TMDB episode zero-call assertions to the current three-argument API.

## Decisions Made

- Kept TVDB settings out of `HomeViewModel`; the router continues to own TVDB active/inactive decisions as required by the plan.
- Allowed TVDB-only enrichment eligibility only for series/TV rows when TMDB is disabled, leaving movie-only rows dependent on active TMDB settings.
- Reused the existing direct `enrichContinueWatchingItemWithProvider` merge path for the TVDB-only metadata regression instead of constructing a full `HomeViewModel` pipeline.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- The targeted command `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeViewModelTvdbProviderRoutingTest"` could not execute the Home test class because `:app:compileArm64DebugUnitTestKotlin` fails in unrelated test files outside this plan.
- Blocking external compile-debt files observed: `ProfileManagerTest.kt`, `AndroidTvSearchSuggestionMapperTest.kt`, `PlayerSettingsDataStoreSpoolModeTest.kt`, `PlayerSettingsDataStoreTest.kt`, `SearchHistoryDataStoreTest.kt`, `ThemeDataStoreProfileTest.kt`, `SearchViewModelHistoryTest.kt`, `CatalogSelectionPersistenceTest.kt`, `PlaybackSettingsViewModelSpoolModeTest.kt`, `SimklViewModelTest.kt`, and `TraktViewModelPriorityHydrationTest.kt`.
- Kotlin daemon startup repeatedly reported unsupported `ZGenerational` and fell back to non-daemon compilation.
- `./gradlew compileArm64DebugKotlin` passed after both tasks, proving the app source path compiles.

## Known Stubs

None. Stub-scan hits in touched files are test fixtures, explicit disabled-TMDB settings, nullable optional fields, or pre-existing optional production parameters.

## Threat Flags

None. This plan added no new network endpoints, auth paths, credential storage, file access patterns, or schema changes. Provider calls still flow through the existing `TvMetadataRouter`.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

The Continue Watching TVDB-only verifier gap is closed at the source and regression-test level. The targeted Home test class should be rerun after the unrelated unit-test compile debt is cleared.

## Self-Check: PASSED

- Found `.planning/phases/07-tvdb-provider-replacement/07-08-SUMMARY.md`.
- Found both plan-owned source/test files.
- Found all three `07-08` commits in git history.
- Verified the task commit diff touches only `HomeViewModelContinueWatching.kt` and `HomeViewModelTvdbProviderRoutingTest.kt`.
- Verified `./gradlew compileArm64DebugKotlin` exits 0 after implementation.
- Left `.planning/STATE.md`, `.planning/ROADMAP.md`, `TvMetadataRouter.kt`, `TvdbMetadataService.kt`, `TvMetadataRouterTest.kt`, `TvdbMetadataServiceTest.kt`, PlayerSettingsDataStore files, `nexio-web`, the deleted screenshot, and `search.png` unstaged and uncommitted by this plan.

---
*Phase: 07-tvdb-provider-replacement*
*Completed: 2026-04-15*
