---
phase: 10-tvdb-reliability-updates-and-diagnostics
plan: 04
subsystem: tvdb
tags: [tvdb, graceful-fallback, credential-health, diagnostics, cache, mockk]

requires:
  - phase: 10-00
    provides: TvdbDiagnosticsRecorder Hilt binding and TvdbReliabilityDiagnostics types
  - phase: 10-01
    provides: TvdbMergeAliasStore for duplicate merge source ID remapping
  - phase: 10-02
    provides: TvdbCredentialHealth with canCallTvdb/markInvalid/markValid
  - phase: 10-03
    provides: TvdbReferenceDataService and coordinator wiring
provides:
  - Last-known-good TVDB serving during outages (D-07)
  - Invalid credential network gating in TvMetadataRouter and TvdbMetadataService (D-08)
  - Field-level fallback diagnostics MISSING_AIRS_TIME, DATE_ONLY_GATING (D-09)
  - TVDB_PROVIDER_CHOSEN, EXPLICIT_FALLBACK, STALE_CACHE_SERVED, TMDB_TV_FETCH_SKIPPED diagnostics
  - Merge alias resolution on TVDB read path via TvdbMergeAliasStore
affects: [10-05, 10-06, continue-watching, tv-detail]

tech-stack:
  added: []
  patterns: [cache-before-network, credential-gated-fetch, field-level-fallback-diagnostics, structured-log-fields]

key-files:
  created: []
  modified:
    - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbGracefulFallbackTest.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbCredentialHealthTest.kt

key-decisions:
  - "TvdbMetadataService reads disk cache before network fetch; on outage, re-checks cache for stale data"
  - "TvMetadataRouter gates on canCallTvdb() before all TVDB network paths"
  - "Field-level diagnostics (MISSING_AIRS_TIME, DATE_ONLY_GATING) keep TVDB as provider without switching to TMDB"

patterns-established:
  - "Cache-before-network: TvdbMetadataService reads TVDB disk cache before API call, returns stale on failure"
  - "Credential gating: canCallTvdb() checked at both router and service layer before network calls"
  - "Structured diagnostics: recordReliabilityDiagnostic emits sanitized structuredLogFields via TvdbDiagnosticsRecorder"

requirements-completed: [UX-03]

duration: 9min
completed: 2026-04-15
---

# Phase 10 Plan 04: Graceful Fallback Summary

**TVDB outage resilience with last-known-good cache serving, credential-gated network calls, merge alias read-path resolution, and field-level fallback diagnostics**

## Performance

- **Duration:** 9 min
- **Started:** 2026-04-15T20:04:57Z
- **Completed:** 2026-04-15T20:13:34Z
- **Tasks:** 2
- **Files modified:** 9

## Accomplishments
- TvdbMetadataService reads TVDB disk cache before network fetch; serves stale cached data during TVDB outages with STALE_CACHE_SERVED diagnostic
- TvMetadataRouter checks TvdbCredentialHealth.canCallTvdb() before all TVDB network calls; invalid credentials serve cached data or explicit TMDB fallback
- TvdbMergeAliasStore.resolveAlias integrated into TVDB read path for duplicate merge source ID remapping (D-02)
- Field-level diagnostics MISSING_AIRS_TIME and DATE_ONLY_GATING recorded while keeping TVDB as record provider
- All diagnostics emitted through TvdbDiagnosticsRecorder with sanitized structuredLogFields (no secrets in logs)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add graceful fallback and credential-health regression tests** - `ef4f407b8` (test)
2. **Task 2: Wire last-known-good serving and field-level fallback reasons** - `572272cc7` (feat)

## Files Created/Modified
- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt` - Credential health gating, invalid credential handling, field-level fallback diagnostics, structured log emission
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt` - Cache-before-network pattern, merge alias resolution, credential gating, stale cache serving
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbGracefulFallbackTest.kt` - 4 regression tests for D-07/D-09 outage and field-level fallback behavior
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbCredentialHealthTest.kt` - 4 regression tests for D-08 credential gating and D-02 merge alias resolution
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt` - Updated constructors for new dependencies
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbProviderRoutingTest.kt` - Updated constructors for new dependencies
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbUpdateSchedulingTest.kt` - Updated constructors for new dependencies
- `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt` - Updated constructors for new dependencies
- `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsSeasonMediaViewModelTest.kt` - Updated TvMetadataRouter constructor

## Decisions Made
- TvdbMetadataService reads disk cache before network fetch; on outage (IOException, 5xx, null response), re-checks cache for stale data before returning null
- TvMetadataRouter gates on canCallTvdb() before all TVDB network paths; handleInvalidCredentialEnrichment serves cached TVDB data or explicit TMDB fallback
- Field-level diagnostics (MISSING_AIRS_TIME, DATE_ONLY_GATING) keep TVDB as provider without switching record provider to TMDB

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed outage test mock to use cache-miss-then-hit pattern**
- **Found during:** Task 2
- **Issue:** Test mocked cache to always return data, but TvdbMetadataService returns cache hit immediately without reaching outage path
- **Fix:** Changed mock to return null first (forcing API call) then return cached data on stale re-check
- **Files modified:** TvdbGracefulFallbackTest.kt
- **Committed in:** 572272cc7

**2. [Rule 1 - Bug] Fixed TmdbSettings test helpers using computed isActive**
- **Found during:** Task 2
- **Issue:** Tests used `isActive` as constructor parameter but it's a computed property from `enabled` and `apiKey`
- **Fix:** Changed to `enabled = active, apiKey = if (active) "test-tmdb-key" else ""`
- **Files modified:** TvdbGracefulFallbackTest.kt, TvdbCredentialHealthTest.kt
- **Committed in:** 572272cc7

**3. [Rule 3 - Blocking] Added missing TvdbMergeAlias import**
- **Found during:** Task 2
- **Issue:** TvdbCredentialHealthTest referenced TvdbMergeAlias without import
- **Fix:** Added `import com.nexio.tv.data.local.TvdbMergeAlias`
- **Files modified:** TvdbCredentialHealthTest.kt
- **Committed in:** 572272cc7

**4. [Rule 3 - Blocking] Added credentialHealth/diagnosticsRecorder to existing test constructors**
- **Found during:** Task 2
- **Issue:** TvMetadataRouter and TvdbMetadataService gained new constructor parameters; existing tests failed to compile
- **Fix:** Added relaxed mocks for credentialHealth and diagnosticsRecorder to TvMetadataRouterTest, TvdbProviderRoutingTest, TvdbUpdateSchedulingTest, MetaDetailsSeasonMediaViewModelTest
- **Files modified:** 5 test files
- **Committed in:** 572272cc7

---

**Total deviations:** 4 auto-fixed (2 bugs, 2 blocking)
**Impact on plan:** All auto-fixes were necessary for test compilation and correctness. No scope creep.

## Issues Encountered
None beyond the auto-fixed test compilation issues documented above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Graceful fallback behavior complete for TV detail and Continue Watching surfaces
- Diagnostics infrastructure wired through TvdbDiagnosticsRecorder for all provider/cache/fallback decisions
- Ready for plan 10-05 (diagnostics UI) and 10-06 (user-facing documentation)

---
*Phase: 10-tvdb-reliability-updates-and-diagnostics*
*Completed: 2026-04-15*

## Self-Check: PASSED
- All key files exist on disk
- Both task commits (ef4f407b8, 572272cc7) found in git log
