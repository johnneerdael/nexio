---
phase: 07-tvdb-provider-replacement
plan: 04
subsystem: metadata
tags: [android, kotlin, tvdb, home, continue-watching, runtime]

requires:
  - phase: 07-tvdb-provider-replacement
    provides: TVDB-first TvMetadataRouter, provider-neutral models, and TMDB fallback adapters from 07-02
provides:
  - HomeViewModel injection access to TvMetadataRouter
  - Continue Watching display metadata routed through TvMetadataRouter
  - Continue Watching episode descriptions and runtime hydration routed through TvMetadataRouter
  - No-direct-TMDB-call tests for TVDB success paths in Continue Watching
affects: [home-tv-metadata, continue-watching-tv-metadata, tvdb-provider-routing]

tech-stack:
  added: []
  patterns:
    - ViewModel pipeline extensions consume provider-neutral TvMetadataRequest/TvMetadataDecision
    - Series runtime branches use router decisions before any direct TMDB ID conversion

key-files:
  created:
    - app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt
    - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt
    - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt
    - app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingTest.kt
    - app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt

key-decisions:
  - "Used TvMetadataRouter directly in Continue Watching display and runtime pipelines so TVDB success paths avoid pre-router TMDB ID conversion."
  - "Kept direct TMDB runtime lookup only for movie content; TV fallback remains centralized inside TvMetadataRouter."
  - "Preserved HomeDisplayMetadata.mergeFallback semantics when mapping provider-neutral TV enrichment into Continue Watching rows."

patterns-established:
  - "Continue Watching TV paths build TvMetadataRequest with contentId, fallbackContentId, contentType, and seasonNumbers where applicable."
  - "Router-backed tests mock HomeViewModel extension dependencies directly instead of constructing the full HomeViewModel and triggering its observers."

requirements-completed: [PREF-02, PREF-03, META-01, META-02, META-04]

duration: 14 min
completed: 2026-04-15
---

# Phase 07 Plan 04: Continue Watching TVDB Provider Routing Summary

**Continue Watching TV display metadata, episode descriptions, and runtime hydration now route through the TVDB-first metadata router.**

## Performance

- **Duration:** 14 min
- **Started:** 2026-04-15T03:58:43Z
- **Completed:** 2026-04-15T04:12:55Z
- **Tasks:** 3
- **Files modified:** 5

## Accomplishments

- Injected `TvMetadataRouter` into `HomeViewModel`, grouped with the existing TMDB provider dependencies.
- Replaced Continue Watching display enrichment with `enrichContinueWatchingItemWithProvider`, which maps `TvMetadataEnrichment` into `HomeDisplayMetadata` while preserving fallback merge behavior and artwork/details toggles.
- Routed localized episodic descriptions through `tvMetadataRouter.fetchEpisodeEnrichment`.
- Routed Continue Watching TV runtime hydration through router episode/series enrichment before direct TMDB ID conversion; movie runtime still uses the existing TMDB path.
- Added routing tests that assert TVDB success paths do not call `TmdbService.ensureTmdbId` or direct TMDB metadata methods.

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: Continue Watching TVDB routing test scaffold** - `2845015d0` (test)
2. **Task 1 GREEN: Inject router into HomeViewModel** - `478bdde99` (feat)
3. **Task 2 RED: Continue Watching display/episode router tests** - `97e94591e` (test)
4. **Task 2 GREEN: Route display metadata and episode descriptions through router** - `16860669d` (feat)
5. **Task 3 RED: Continue Watching runtime router test** - `889405188` (test)
6. **Task 3 GREEN: Route runtime hydration through router** - `e6ced2212` (feat)

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt` - Added injected `internal val tvMetadataRouter: TvMetadataRouter`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt` - Routed display metadata and localized episode descriptions through `TvMetadataRouter`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt` - Routed TV runtime hydration through router episode and series enrichment before direct TMDB lookup.
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingTest.kt` - Updated localized episode description tests to use router episode metadata and verify non-episodic skips.
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt` - Added Continue Watching display/runtime no-direct-TMDB-call coverage.

## Decisions Made

- Used `TvMetadataRouter.fetchEnrichment` for Continue Watching display metadata, allowing router-owned TVDB/TMDB fallback decisions instead of local `ensureTmdbId` calls.
- Used router episode enrichment for localized descriptions and runtime lookup so TVDB success avoids `TmdbMetadataService.fetchEpisodeEnrichment`.
- Preserved the old TMDB direct runtime branch only after excluding TV/series content, keeping movie behavior intact while centralizing TV fallback.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added missing HomeDisplayMetadata merge extension import**
- **Found during:** Task 2 (Route Continue Watching display metadata through TVDB)
- **Issue:** The new provider mapping used `HomeDisplayMetadata.mergeFallback`, but the extension import was missing in `HomeViewModelContinueWatching.kt`.
- **Fix:** Imported `com.nexio.tv.domain.model.mergeFallback` and reran app Kotlin compilation.
- **Files modified:** `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- **Verification:** `./gradlew compileArm64DebugKotlin` exits 0 after the fix.
- **Committed in:** `16860669d`

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** The fix was a local compile correction required by the planned metadata merge behavior. No scope expansion.

## Issues Encountered

- The plan referenced `app/src/main/java/com/nexio/tv/ui/navigation/NavigationUtils.kt`, but that file does not exist in this checkout. The referenced `continueWatchingRuntimeMinutes` helper currently lives in `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`, which was read instead.
- Targeted unit-test commands could not complete because `:app:compileArm64DebugUnitTestKotlin` fails in unrelated tests before the owned Home tests can execute. Current failures include profile, search, PlayerSettingsDataStore, settings ViewModel, and other files outside this plan. This matches the unit-test compile debt recorded by Plans 07-01 and 07-02.
- A parallel 07-03 executor briefly introduced detail test compile failures during this work; no detail files were edited, staged, or committed by this plan.
- `./gradlew compileArm64DebugKotlin` passed after the final implementation, proving owned app source compiles.
- Kotlin daemon startup repeatedly reported unsupported `ZGenerational` and fell back to non-daemon compilation; source compilation still completed successfully.

## Known Stubs

None. Null values and empty values in touched tests are test fixtures; pre-existing empty/null state fields in `HomeViewModel.kt` are not new stubs from this plan.

## Threat Flags

None. This plan added no new network endpoints, credential paths, file access patterns, or schema changes. TVDB routing continues through the existing `TvMetadataRouter` and mocked tests do not include TVDB credentials.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Ready for downstream Home catalog/hero routing work in Plan 07-05. Continue Watching now has TVDB-first display, episode description, and runtime paths, with direct TMDB TV calls avoided on normal router success.

## Self-Check: PASSED

- Found all created/modified plan-owned files.
- Found all six `07-04` task commits in git history.
- Verified `./gradlew compileArm64DebugKotlin` exits 0 after implementation.
- Left `.planning/STATE.md`, `.planning/ROADMAP.md`, PlayerSettingsDataStore files, `nexio-web`, and the deleted screenshot unstaged and uncommitted as requested.

---
*Phase: 07-tvdb-provider-replacement*
*Completed: 2026-04-15*
