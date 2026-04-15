---
phase: 09-tvdb-advanced-tv-surfaces
plan: 02
subsystem: tvdb
tags: [tvdb, advanced-metadata, cast, companies, networks, genres, content-ratings, diagnostics]

# Dependency graph
requires:
  - phase: 09-tvdb-advanced-tv-surfaces
    provides: Wave 0 test scaffold (TvdbAdvancedMetadataMapperTest, TvdbProviderRoutingTest)
  - phase: 09-tvdb-advanced-tv-surfaces
    provides: TvdbSeasonOrderMapper, TvMetadataEnrichment extensions, TvdbMetadataService wiring (Plan 09-01)
  - phase: 07-tvdb-provider-replacement
    provides: TvMetadataModels, TvMetadataDiagnostics, TvdbMetadataService, TvdbApi, TvMetadataRouter
provides:
  - TvdbAdvancedMetadataMapper with mapAdvancedMetadata for characters, companies, networks, genres, content ratings
  - TvdbCharacterRecord and TvdbCompanyExtendedRecord DTOs on TvdbSeriesExtendedRecord
  - castMembers, productionCompanies, networks fields on TvMetadataEnrichment
  - TVDB_ADVANCED_SURFACE_SUCCESS and TVDB_ADVANCED_SURFACE_MISSING diagnostic events
  - Advanced surface diagnostics emitted in TvMetadataRouter success path
affects: [09-03, 09-04, 09-05]

# Tech tracking
tech-stack:
  added: []
  patterns: [tvdb-advanced-metadata-mapper, advanced-surface-diagnostics-in-router]

key-files:
  created:
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbAdvancedMetadataMapper.kt
  modified:
    - app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataDiagnostics.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbAdvancedMetadataMapperTest.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbProviderRoutingTest.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt

key-decisions:
  - "TVDB characters map to MetaCastMember with personName as name, character name as character, personImgURL as photo; tmdbId always null"
  - "Networks sourced from originalNetwork then latestNetwork, distinct by case-insensitive name, kind=NETWORK"
  - "Companies sourced from companies list, distinct by case-insensitive name, kind=COMPANY, excluding names already in networks"
  - "Content rating selection: preferred country codes first, then US/USA fallback, then first nonblank"
  - "Advanced surface diagnostics emitted in TvMetadataRouter rather than TvdbMetadataService for router-level observability"

patterns-established:
  - "Advanced metadata mapper called during enrichment build, results wired to TvMetadataEnrichment fields"
  - "Router emits TVDB_ADVANCED_SURFACE_SUCCESS or TVDB_ADVANCED_SURFACE_MISSING alongside existing success diagnostics"

requirements-completed: [META-05]

# Metrics
duration: 5min
completed: 2026-04-15
---

# Phase 09 Plan 02: TVDB Advanced Metadata Mapper Summary

**TVDB characters, companies, networks, genres, and content ratings mapped into existing domain surfaces with provider diagnostics**

## Performance

- **Duration:** 5 min
- **Started:** 2026-04-15T16:07:43Z
- **Completed:** 2026-04-15T16:12:51Z
- **Tasks:** 2
- **Files created:** 1
- **Files modified:** 7

## Accomplishments
- Created TvdbAdvancedMetadataMapper with mapAdvancedMetadata covering all five advanced surface types
- Added TvdbCharacterRecord and TvdbCompanyExtendedRecord DTOs with characters/companies fields on TvdbSeriesExtendedRecord
- Extended TvMetadataEnrichment with castMembers, productionCompanies, networks fields
- Wired advanced mapper into TvdbMetadataService enrichment pipeline
- Added TVDB_ADVANCED_SURFACE_SUCCESS and TVDB_ADVANCED_SURFACE_MISSING diagnostic events
- Emitted advanced surface diagnostics in TvMetadataRouter success path
- All 11 mapper tests and 3 provider routing tests pass

## Task Commits

Each task was committed atomically:

1. **Task 1: Map TVDB advanced fields into existing domain surfaces** - `741e5d486` (feat)
2. **Task 2: Include advanced TVDB surfaces in provider service and diagnostics** - `59c43c73a` (feat)

## Files Created/Modified
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAdvancedMetadataMapper.kt` - New mapper with mapAdvancedMetadata, mapCharacters, mapNetworks, mapCompanies, mapGenres, selectContentRating; plus TvdbAdvancedMetadata result type
- `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt` - Added TvdbCharacterRecord, TvdbCompanyExtendedRecord DTOs; added characters/companies fields to TvdbSeriesExtendedRecord
- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt` - Extended TvMetadataEnrichment with castMembers, productionCompanies, networks fields
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt` - Injected TvdbAdvancedMetadataMapper, called mapAdvancedMetadata during enrichment, wired fields to TvMetadataEnrichment
- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataDiagnostics.kt` - Added TVDB_ADVANCED_SURFACE_SUCCESS and TVDB_ADVANCED_SURFACE_MISSING enum values
- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt` - Added advancedSurfaceDiagnostics helper; emitted in fetchEnrichment success path
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbAdvancedMetadataMapperTest.kt` - Replaced scaffold with 8 real mapper tests covering characters, companies, networks, genres, content ratings, sort order, dedup, blank omission, empty series
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbProviderRoutingTest.kt` - Expanded to 3 tests: skipped TMDB, advanced success diagnostic, advanced missing diagnostic
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt` - Updated 6 constructor calls to pass new advancedMetadataMapper parameter

## Decisions Made
- TVDB characters map to MetaCastMember with personName as name, character name as character, personImgURL as photo; tmdbId always null for TVDB characters
- Networks sourced from originalNetwork then latestNetwork, distinct by case-insensitive name, kind=NETWORK
- Companies sourced from companies list, distinct by case-insensitive name, kind=COMPANY, excluding names already in networks (prevents duplicate display)
- Content rating selection priority: preferred country codes (in order), then US/USA fallback, then first nonblank rating
- Advanced surface diagnostics emitted in TvMetadataRouter (not TvdbMetadataService) for router-level observability alongside existing TVDB_SUCCESS and TMDB_TV_SKIPPED diagnostics
- TVDB advanced genres and ageRating replace TMDB equivalents when non-empty/non-null per D-11

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Added characters and companies fields to TvdbSeriesExtendedRecord DTO**
- **Found during:** Task 1 (mapper implementation)
- **Issue:** TVDB API exposes characters (array of Character) and companies (array of Company) on SeriesExtendedRecord but these fields were not mapped in the Kotlin DTO
- **Fix:** Added TvdbCharacterRecord and TvdbCompanyExtendedRecord data classes; added characters/companies fields to TvdbSeriesExtendedRecord
- **Files modified:** app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt
- **Commit:** 741e5d486

**2. [Rule 3 - Blocking] Updated TvdbMetadataServiceTest constructor calls for new advancedMetadataMapper parameter**
- **Found during:** Task 2 (service modification)
- **Issue:** TvdbMetadataServiceTest constructed TvdbMetadataService without the new advancedMetadataMapper parameter at 6 call sites
- **Fix:** Added TvdbAdvancedMetadataMapper() to all 6 constructor calls
- **Files modified:** app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt
- **Commit:** 59c43c73a

---

**Total deviations:** 2 auto-fixed (1 missing critical, 1 blocking)
**Impact on plan:** Both fixes necessary for correctness and compilation. No scope creep.

## Test Results

| Test Class | Tests | Pass | Fail | Status |
|------------|-------|------|------|--------|
| TvdbAdvancedMetadataMapperTest | 8 | 8 | 0 | Green |
| TvdbProviderRoutingTest | 3 | 3 | 0 | Green |
| TvdbMetadataServiceTest | 10 | 10 | 0 | Green (pre-existing) |
| **Total** | **21** | **21** | **0** | |

## Issues Encountered
None beyond the auto-fixed deviations above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- TVDB advanced metadata flows from API through mapper to enrichment to router diagnostics
- Cast, companies, networks, genres, and content ratings populate existing domain surfaces
- Diagnostic events defined for advanced surface success and missing conditions
- Wave 0 scaffold tests now pass (TvdbAdvancedMetadataMapperTest and TvdbProviderRoutingTest)
- TvdbMetadataService enrichment carries all advanced fields for downstream consumption

---
*Phase: 09-tvdb-advanced-tv-surfaces*
*Completed: 2026-04-15*
