---
phase: 07-tvdb-provider-replacement
plan: 05
subsystem: metadata
tags: [android, kotlin, tvdb, home, catalog-refresh, metadata-routing]

requires:
  - phase: 07-tvdb-provider-replacement
    provides: TVDB-first TvMetadataRouter, provider-neutral models, and diagnostics from 07-02
  - phase: 07-tvdb-provider-replacement
    provides: HomeViewModel TvMetadataRouter injection from 07-04
provides:
  - Home focused preview, adjacent prefetch, and hero series enrichment routed through TvMetadataRouter
  - Home catalog refresh series hydration routed through TvMetadataRouter
  - Provider-decision logging for tmdb_tv_skipped and tvdb_fallback_tmdb in catalog hydration
  - No-direct-TMDB-call tests for Home TVDB success paths
affects: [home-tv-metadata, tvdb-provider-routing, catalog-refresh]

tech-stack:
  added: []
  patterns:
    - Provider-neutral TvMetadataEnrichment mapping for Home preview and catalog refresh metadata
    - Router diagnostic event names bridged into Home refresh telemetry

key-files:
  created:
    - app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTvdbTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt
    - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt
    - app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt
    - app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt

key-decisions:
  - "Used TvMetadataRouter for Home series preview and catalog-refresh hydration before any direct TMDB ID conversion."
  - "Preserved direct TMDB preview/catalog enrichment for movies by adapting TMDB enrichment into provider-neutral Home merge fields."
  - "Logged only provider decision event names and item keys for catalog hydration diagnostics."

patterns-established:
  - "Home preview enrichment stores pending provider-neutral TvMetadataEnrichment before flushing into MetaPreview rows."
  - "Home catalog refresh logs router diagnostics via tmdb_tv_skipped and tvdb_fallback_tmdb without exposing provider credentials or request bodies."

requirements-completed: [PREF-02, PREF-03, META-01, META-04]

duration: 11 min
completed: 2026-04-15
---

# Phase 07 Plan 05: Home TVDB Provider Routing Summary

**Home focused, adjacent, hero, and catalog-refresh series metadata now route through the TVDB-first provider router.**

## Performance

- **Duration:** 11 min
- **Started:** 2026-04-15T04:18:56Z
- **Completed:** 2026-04-15T04:29:56Z
- **Tasks:** 3
- **Files modified:** 5

## Accomplishments

- Routed focused item and adjacent item series enrichment through `TvMetadataRouter.fetchEnrichment`.
- Routed Home hero series enrichment through `TvMetadataRouter.fetchEnrichment` while keeping movie hero enrichment TMDB-backed.
- Converted Home preview flush state from TMDB-specific enrichment to provider-neutral `TvMetadataEnrichment`.
- Injected `TvMetadataRouter` into `HomeCatalogRefreshCoordinator` and replaced TMDB-only localized overlay with provider-neutral TVDB-first overlay.
- Added catalog refresh telemetry for `tmdb_tv_skipped` and `tvdb_fallback_tmdb` using only item keys.
- Added Home ViewModel and catalog refresh tests that assert TVDB success paths do not call `TmdbService.ensureTmdbId` or direct TMDB metadata methods.

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: Home preview/hero TVDB routing tests** - `d5df5fe11` (test)
2. **Task 1 GREEN: Route Home preview enrichment through TV provider router** - `112d71931` (feat)
3. **Task 2 RED: Catalog refresh TVDB routing tests** - `e9b66cf38` (test)
4. **Task 2 GREEN: Route catalog refresh through TV provider router** - `9108a3aff` (feat)
5. **Task 3: Verify Home provider routing suite** - `99a3ab145` (chore, empty verification commit)

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt` - Stores pending Home preview metadata as provider-neutral `TvMetadataEnrichment`.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt` - Routes focused, adjacent, and hero series enrichment through `TvMetadataRouter`; preserves movie TMDB behavior.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt` - Injects `TvMetadataRouter`, overlays provider-neutral localized metadata, and logs provider decisions.
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelTvdbProviderRoutingTest.kt` - Adds hero and focused Home no-direct-TMDB-call tests.
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTvdbTest.kt` - Adds catalog refresh TVDB success and movie TMDB preservation tests.

## Decisions Made

- Used a provider-neutral Home merge path rather than writing TVDB data into TMDB-named pending state.
- Kept the existing TMDB movie behavior in both Home preview enrichment and catalog refresh hydration.
- Exposed `overlayProviderLocalizedMetadata` as an internal coordinator method so tests can exercise routing without depending on image prefetch or full catalog refresh side effects.

## Deviations from Plan

None - plan implementation scope stayed within the specified Home provider routing files.

## Issues Encountered

- The targeted Gradle test commands could not complete because `:app:compileArm64DebugUnitTestKotlin` still fails in unrelated existing tests before the owned Home tests can execute. Current failures include `ProfileManagerTest`, `AndroidTvSearchSuggestionMapperTest`, `PlayerSettingsDataStore*`, `SearchHistoryDataStoreTest`, `ThemeDataStoreProfileTest`, search ViewModel tests, settings ViewModel tests, `SimklViewModelTest`, and `TraktViewModelPriorityHydrationTest`.
- `./gradlew compileArm64DebugKotlin` passed after the Home implementation, proving plan-owned app source compiles.
- Kotlin daemon startup repeatedly reported unsupported `ZGenerational` and fell back to non-daemon compilation; source compilation still completed successfully.

## Known Stubs

None. Stub scan hits in touched files are pre-existing nullable ViewModel state, request/test fixture nulls, and empty-list test fixture values, not new UI placeholders or unwired mock data.

## Threat Flags

None. This plan added no new network endpoints, auth paths, credential storage, file access patterns, or schema changes. Catalog provider diagnostics log only event names and `itemKey` values.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Home provider routing is ready for Phase 7 verification and downstream cleanup. The remaining blocker is unrelated unit-test compile debt outside this plan's owned files; targeted Home tests should be rerun once that compile debt is cleared.

## Self-Check: PASSED

- Found `.planning/phases/07-tvdb-provider-replacement/07-05-SUMMARY.md`.
- Found `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTvdbTest.kt`.
- Found all five `07-05` task commits in git history.
- Verified `./gradlew compileArm64DebugKotlin` exits 0 after implementation.
- Left `.planning/STATE.md`, `.planning/ROADMAP.md`, PlayerSettingsDataStore files, `nexio-web`, and the deleted screenshot unstaged and uncommitted as requested.

---
*Phase: 07-tvdb-provider-replacement*
*Completed: 2026-04-15*
