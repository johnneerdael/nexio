---
phase: 09-tvdb-advanced-tv-surfaces
plan: 04
subsystem: trailer
tags: [tvdb, trailer, fallback, youtube, exoplayer, media3]

# Dependency graph
requires:
  - phase: 09-03
    provides: TVDB advanced metadata mapping and router-level diagnostics
  - phase: 07-tvdb-provider-replacement
    provides: TVDB identity service, auth service, settings datastore, API client
provides:
  - TVDB trailer URL usability classification (YouTube, External, DirectMedia, Unusable)
  - TVDB-first TV trailer resolution before Streailer, fallback YT IDs, and explicit TMDB
  - Detail and Home call sites skip TMDB ID resolution for TV trailer paths
  - Trailer diagnostics for TVDB success, missing, unusable URL, and TMDB fallback
affects: [09-05, 10-tvdb-cache-invalidation]

# Tech tracking
tech-stack:
  added: []
  patterns: [tvdb-first-tv-trailer-fallback, url-usability-classification, provider-skip-for-tv]

key-files:
  created:
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerMapper.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerResolver.kt
    - app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbTrailerTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataDiagnostics.kt
    - app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt
    - app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
    - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt
    - app/src/test/java/com/nexio/tv/data/trailer/TrailerServiceTvdbTest.kt

key-decisions:
  - "TVDB trailer URL usability uses sealed interface with YouTube, External, DirectMedia, Unusable variants"
  - "TvdbTrailerResolver injected as nullable into TrailerService to preserve backward compatibility"
  - "TV detail and Home call sites pass tmdbId=null for series content so TVDB is tried first"
  - "Season trailer/recap methods pass tmdbId=null since they are only called for series content"

patterns-established:
  - "TVDB-first TV trailer fallback: TVDB -> Streailer -> fallback YT IDs -> explicit TMDB"
  - "URL scheme safety: only http/https accepted; intent:, file:, content:, javascript: rejected as Unusable"
  - "Provider skip pattern: TV call sites skip ensureTmdbId when TVDB resolver handles lookup"

requirements-completed: [META-05]

# Metrics
duration: 8min
completed: 2026-04-15
---

# Phase 9 Plan 4: TVDB-First Trailer Routing Summary

**TVDB trailer mapper/resolver with URL safety classification, TrailerService TVDB-first TV fallback order, and detail/Home call-site wiring to skip TMDB ID resolution for TV content**

## Performance

- **Duration:** 8 min
- **Started:** 2026-04-15T16:36:31Z
- **Completed:** 2026-04-15T16:44:34Z
- **Tasks:** 3
- **Files modified:** 10

## Accomplishments
- TVDB trailer URL usability classification (YouTube, External, DirectMedia, Unusable) with unsafe scheme rejection
- TV trailer resolution order locked to TVDB -> Streailer -> fallback YT IDs -> explicit TMDB fallback
- Detail and Home call sites skip TMDB ID resolution for TV content, letting TrailerService try TVDB first
- Trailer diagnostics for tvdb_trailer_success, tvdb_trailer_missing, tvdb_trailer_unusable_url, and tmdb_trailer_fallback

## Task Commits

Each task was committed atomically:

1. **Task 1: Add TVDB trailer candidate mapping and URL safety diagnostics** - `20ab07570` (feat) - *committed by previous executor*
2. **Task 2: Insert TVDB trailer resolver before Streailer and TMDB fallback** - `1b87af168` (feat)
3. **Task 3: Wire existing detail and Home trailer call sites to avoid pre-TMDB TV lookup** - `4fafbed2e` (feat)

## Files Created/Modified
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerMapper.kt` - URL usability classification for TVDB trailer candidates
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerResolver.kt` - TVDB trailer lookup with title/season/recap resolution
- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt` - TvdbTrailerCandidate and TvdbTrailerUsability models
- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataDiagnostics.kt` - TVDB trailer diagnostic entries
- `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt` - TVDB-first TV fallback order, resolveTvTrailerInternal, TV availability check
- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` - Pass tmdbId=null for series in trailer calls
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt` - Skip ensureTmdbId for TV trailer availability and preview
- `app/src/test/java/com/nexio/tv/data/trailer/TrailerServiceTvdbTest.kt` - URL classification tests, TVDB priority tests with mock resolver
- `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbTrailerTest.kt` - Detail TVDB-first routing verification

## Decisions Made
- TvdbTrailerResolver injected as nullable (`TvdbTrailerResolver? = null`) in TrailerService primary constructor to preserve backward compatibility with tests and manual construction
- TV detail and Home call sites use local `isTvContent` check before skipping `ensureTmdbId`, keeping movie behavior completely unchanged
- Season trailer/recap/availability methods always pass `tmdbId = null` since they are only invoked for series content (guarded by ContentType.SERIES checks)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed TrailerServiceTvdbTest mock setup for TVDB integration tests**
- **Found during:** Task 3 (test verification)
- **Issue:** Existing TVDB integration tests in TrailerServiceTvdbTest.kt created TrailerService without a TvdbTrailerResolver mock, causing TV path to skip TVDB entirely and fail assertions about TMDB not being called
- **Fix:** Added tvdbTrailerResolver parameter to createTrailerService factory, provided mock resolvers returning ResolvedYouTube/Missing/Unusable for each test scenario
- **Files modified:** app/src/test/java/com/nexio/tv/data/trailer/TrailerServiceTvdbTest.kt
- **Verification:** All 20 tests pass
- **Committed in:** 4fafbed2e (Task 3 commit)

---

**Total deviations:** 1 auto-fixed (1 bug)
**Impact on plan:** Test mock setup was necessary for correct verification. No scope creep.

## Issues Encountered
- Gradle build cache returned stale test compilation results after fixing test files; resolved by deleting build/classes and build/tmp directories for the test variant

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- TVDB-first TV trailer routing is complete for title, season, and recap trailers
- Plan 09-05 (TVDB TV surface replacement for characters/cast, companies, networks, genres, content ratings) can proceed
- Movie trailer behavior is fully preserved and unchanged

## Self-Check: PASSED

All created files verified present. All 3 task commits verified in git log.

---
*Phase: 09-tvdb-advanced-tv-surfaces*
*Completed: 2026-04-15*
