---
phase: 07-tvdb-provider-replacement
plan: 03
subsystem: metadata
tags: [android, kotlin, tvdb, metadata, detail, viewmodel]

requires:
  - phase: 07-tvdb-provider-replacement
    provides: TVDB provider-neutral models and router from Plans 07-01 and 07-02
provides:
  - Detail screen TV/series metadata enrichment through TvMetadataRouter
  - Detail episode row enrichment through TVDB provider-neutral episode metadata
  - Mark-season-watched authoritative season episode lists through TvMetadataRouter
affects: [detail-tv-metadata, tvdb-provider-routing, season-watched-actions]

tech-stack:
  added: []
  patterns:
    - ViewModel consumes provider-neutral TV metadata contracts instead of direct TMDB TV metadata calls
    - TDD tests use MockK call-count assertions around router success and TMDB skip behavior

key-files:
  created:
    - app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbProviderRoutingTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
    - app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelTestFactory.kt
    - app/src/test/java/com/nexio/tv/ui/screens/detail/MarkSeasonWatchedTest.kt

key-decisions:
  - "Routed only Detail TV/series metadata roles through TvMetadataRouter; movie enrichment remains on the existing TMDB branch."
  - "Kept advanced TVDB-only fields out of Meta and Video, mapping only existing Detail fields covered by Phase 7."
  - "Kept date-only AirDateGate behavior for mark-season-watched; exact TVDB airtime behavior remains Phase 8."

patterns-established:
  - "Detail series enrichment creates TvMetadataRequest with meta.id, route itemId fallback, ContentType, and seasonNumbers where needed."
  - "TVDB success tests assert router usage and TMDB metadata-service skip behavior while leaving deferred trailer TMDB paths outside the assertion scope."

requirements-completed: [PREF-02, PREF-03, META-01, META-02, META-04]

duration: 16 min
completed: 2026-04-15
---

# Phase 07 Plan 03: Detail TVDB Provider Routing Summary

**Detail screen TV metadata now flows through the TVDB-first router for series enrichment, episode rows, and season watched actions.**

## Performance

- **Duration:** 16 min
- **Started:** 2026-04-15T03:58:31Z
- **Completed:** 2026-04-15T04:15:08Z
- **Tasks:** 3
- **Files modified:** 4

## Accomplishments

- Injected `TvMetadataRouter` into `MetaDetailsViewModel` and routed series/TV detail enrichment through `fetchEnrichment`.
- Mapped `TvMetadataEnrichment` into existing `Meta` fields using the existing artwork, basic info, and details toggles, while leaving movie enrichment on TMDB.
- Routed series episode row enrichment through `fetchEpisodeEnrichment` and mapped TVDB title, overview, released date, thumbnail, and runtime into `Video`.
- Routed mark-season-watched through `fetchSeasonEpisodes`, preserving the existing `AirDateGate.isAired(0L, airDate, nowMs)` date-only filter.
- Added Detail routing tests for no TMDB metadata calls on TVDB success, movie TMDB behavior, TVDB episode row mapping, and TVDB season watched routing.

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: Detail TVDB enrichment routing tests** - `a1ecd8b3b` (test)
2. **Task 1 GREEN: Detail series enrichment through TVDB router** - `f8e9346d4` (feat)
3. **Task 2 RED: TVDB episode enrichment test** - `ad433eb8a` (test)
4. **Task 2 GREEN: Detail episode enrichment through TVDB router** - `52b932f58` (feat)
5. **Task 3 RED: TVDB season watched test** - `c82bd63f5` (test)
6. **Task 3 GREEN: Season watched episodes through TVDB router** - `ed2979baa` (feat)

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` - Detail TV/series enrichment, episode enrichment, and season watched actions now consume `TvMetadataRouter`.
- `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelTestFactory.kt` - Added router and TMDB settings injection hooks for Detail ViewModel tests.
- `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbProviderRoutingTest.kt` - New Detail routing suite covering TVDB success, episode mapping, and movie TMDB control behavior.
- `app/src/test/java/com/nexio/tv/ui/screens/detail/MarkSeasonWatchedTest.kt` - Added TVDB season episode routing test and router-backed fallback helper for existing TMDB expectations.

## Decisions Made

- Used `TvMetadataRouter` as the single TV provider decision point in Detail instead of having the ViewModel resolve TVDB identity or call TMDB fallback directly.
- Limited TVDB field mapping to current UI contracts: `Meta` artwork/basic/details fields and `Video` title, overview, released, thumbnail, and runtime.
- Preserved deferred TMDB trailer/review/related-content behavior outside the metadata skip assertions, matching the Phase 7 scope boundary.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Kept direct test constructors source-compatible**
- **Found during:** Task 1 (Inject router and route detail series enrichment)
- **Issue:** Existing tests directly construct `MetaDetailsViewModel` outside the shared factory. Adding a required constructor argument would force edits in unowned test files outside this plan.
- **Fix:** Added `tvMetadataRouter` as a constructor dependency with a default error-producing value for manual construction, while all plan-owned tests pass an explicit mock router. Hilt production construction still receives the real router.
- **Files modified:** `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- **Verification:** `./gradlew compileArm64DebugKotlin` exits 0.
- **Committed in:** `f8e9346d4`

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** The adjustment avoided editing unowned test files while preserving the requested constructor dependency and production injection path.

## Issues Encountered

- The targeted unit-test commands could not complete because `:app:compileArm64DebugUnitTestKotlin` fails in unrelated existing tests outside this plan. The failures include `ProfileManagerTest`, `AndroidTvSearchSuggestionMapperTest`, `PlayerSettingsDataStore*`, `SearchHistoryDataStoreTest`, settings ViewModel tests, and profile DataStore constructor drift.
- `./gradlew compileArm64DebugKotlin` passed after the implementation, proving plan-owned app source compiles.
- Kotlin daemon startup repeatedly reported unsupported `ZGenerational` and fell back to non-daemon compilation; this matches the environment noise recorded by Plans 07-01 and 07-02.
- One targeted run briefly failed in `hiltJavaCompileArm64Debug` while reading a generated Hilt file, consistent with build artifact contention during parallel execution; subsequent source compile passed.

## Known Stubs

None. Empty and null values found in touched tests are fixtures for absent metadata fields, not user-facing placeholders or unwired UI data.

## Threat Flags

None. This plan added no new endpoint, credential flow, file access path, or cache namespace. It consumes the existing provider-neutral router and models covered by the phase threat model.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Detail is ready for downstream phase verification once the unrelated unit-test compile debt is cleared. Home and Continue Watching routing are owned by separate plans and were not edited or staged here.

## Self-Check: PASSED

- Found all created/modified plan-owned files.
- Found all six `07-03` task commits in git history.
- Verified `./gradlew compileArm64DebugKotlin` exits 0 after implementation.
- Left `.planning/STATE.md`, `.planning/ROADMAP.md`, PlayerSettings files, `nexio-web`, and the deleted screenshot unstaged and uncommitted as requested.

---
*Phase: 07-tvdb-provider-replacement*
*Completed: 2026-04-15*
