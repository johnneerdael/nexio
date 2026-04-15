---
phase: 09-tvdb-advanced-tv-surfaces
plan: 03
subsystem: tvdb
tags: [tvdb, advanced-metadata, detail-enrichment, shared-propagation, ui-surfaces, d-12]

# Dependency graph
requires:
  - phase: 09-tvdb-advanced-tv-surfaces
    provides: TvdbAdvancedMetadataMapper, castMembers/productionCompanies/networks on TvMetadataEnrichment (Plan 09-02)
  - phase: 07-tvdb-provider-replacement
    provides: TvMetadataRouter, TvMetadataModels, MetaDetailsViewModel enrichMeta TVDB branch
provides:
  - TVDB cast, companies, networks applied to existing detail metadata through enrichMeta toggle groups
  - Static source assertions proving Home, stream, screensaver, player propagation paths carry TVDB Meta
affects: [09-04, 09-05]

# Tech tracking
tech-stack:
  added: []
  patterns: [tvdb-detail-credits-via-enrichment, static-source-propagation-assertions]

key-files:
  created:
    - app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbAdvancedMetadataTest.kt
    - app/src/test/java/com/nexio/tv/ui/shared/MetaSharedTvdbSurfacePropagationTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt

key-decisions:
  - "TVDB credits applied via else-if branch after TMDB credits block, preserving TMDB movie path unchanged"
  - "TVDB cast does not synthesize director/writer from companies per D-09; only castMembers are mapped"
  - "No source changes needed for shared propagation paths; all already exist from Phase 7/8"

patterns-established:
  - "TVDB enrichment branches mirror TMDB enrichment structure within existing toggle groups"
  - "Static source assertions verify propagation contracts without runtime dependencies"

requirements-completed: [META-05]

# Metrics
duration: 3min
completed: 2026-04-15
---

# Phase 09 Plan 03: TVDB Advanced Metadata UI Surface Propagation Summary

**TVDB cast, companies, networks, genres, and content ratings propagated through existing detail/Home/stream/screensaver/player surfaces with no new UI sections**

## Performance

- **Duration:** 3 min
- **Started:** 2026-04-15T16:15:56Z
- **Completed:** 2026-04-15T16:18:41Z
- **Tasks:** 2
- **Files created:** 2
- **Files modified:** 1

## Accomplishments
- Extended MetaDetailsViewModel.enrichMeta TVDB branch with credits, productions, and networks toggle groups
- TVDB castMembers applied when useCredits enabled and tvEnrichment has cast data
- TVDB productionCompanies applied when useProductions enabled and enrichment has companies
- TVDB networks applied when useNetworks enabled and enrichment has networks
- Genres, rating, ageRating, country, language already handled by existing tvEnrichment path
- Proved all 5 shared propagation paths (Home, Home display metadata, stream, screensaver, player) already carry TVDB Meta without code changes
- Static source assertions guard D-12 compliance: no TVDB-specific UI sections added

## Task Commits

Each task was committed atomically:

1. **Task 1: Apply TVDB advanced fields to existing detail metadata roles** - `3b6f221aa` (feat)
2. **Task 2: Prove shared surface propagation through Meta and HomeDisplayMetadata** - `3aee452d4` (test)

## Files Created/Modified
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` - Added TVDB else-if branches for useCredits (castMembers), useProductions (productionCompanies), useNetworks (networks) in enrichMeta
- `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbAdvancedMetadataTest.kt` - 2 tests: TVDB advanced field application + static D-12 no-new-section assertion
- `app/src/test/java/com/nexio/tv/ui/shared/MetaSharedTvdbSurfacePropagationTest.kt` - 4 static source assertion tests proving Home/stream/screensaver/player propagation paths

## Decisions Made
- TVDB credits applied via else-if branch after TMDB credits block, keeping TMDB movie path unchanged and avoiding dual-source conflicts for TV content
- TVDB cast does not synthesize director/writer from companies per D-09 (only castMembers mapped)
- No source changes needed for Task 2; all shared propagation paths already exist from Phase 7/8 work

## Deviations from Plan

None - plan executed exactly as written.

## Test Results

| Test Class | Tests | Pass | Fail | Status |
|------------|-------|------|------|--------|
| MetaDetailsTvdbAdvancedMetadataTest | 2 | 2 | 0 | Green |
| MetaSharedTvdbSurfacePropagationTest | 4 | 4 | 0 | Green |
| **Total** | **6** | **6** | **0** | |

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- TVDB advanced metadata now flows end-to-end: API -> mapper -> enrichment -> detail toggle groups -> Meta fields
- Shared Meta propagation carries TVDB data to Home, stream, screensaver, and player surfaces automatically
- Static assertions guard against regression of propagation paths and D-12 compliance
- Ready for Plan 09-04 (trailer) and 09-05 (diagnostics) waves

## Self-Check: PASSED

- All 3 created/modified files verified on disk
- Both commit hashes (3b6f221aa, 3aee452d4) verified in git log
- castMembers = tvEnrichment.castMembers present in MetaDetailsViewModel.kt
- productionCompanies = tvEnrichment.productionCompanies present in MetaDetailsViewModel.kt
- networks = tvEnrichment.networks present in MetaDetailsViewModel.kt
- ageRating applied through existing tvEnrichment path
- MetaDetailsScreen.kt contains no forbidden TVDB UI terms
- All 6 tests pass (2 detail + 4 propagation)

---
*Phase: 09-tvdb-advanced-tv-surfaces*
*Completed: 2026-04-15*
