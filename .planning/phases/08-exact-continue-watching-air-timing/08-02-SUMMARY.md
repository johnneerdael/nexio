---
phase: 08-exact-continue-watching-air-timing
plan: 02
subsystem: tvdb-air-timing
tags: [kotlin, tvdb, continue-watching, tracking, air-date-gate]

requires:
  - phase: 08-exact-continue-watching-air-timing
    provides: TVDB availability contract, calculator, and TrackingNextUpEntry timing fields from Plan 08-01
provides:
  - TVDB timing source fields mapped from series metadata
  - Continue Watching timing enricher for TrackingNextUpEntry rows
  - Trakt and Simkl next-up flow enrichment before snapshot gating
affects: [08-exact-continue-watching-air-timing, continue-watching, tvdb-provider-routing]

tech-stack:
  added: []
  patterns:
    - Router-mediated TVDB metadata enrichment for tracking next-up rows
    - Per-entry exception isolation with reason-only timing diagnostics

key-files:
  created:
    - app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt
    - app/src/test/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricherTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt
    - app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt

key-decisions:
  - "TVDB next-up timing enrichment routes only through TvMetadataRouter so provider fallback and TMDB-skip diagnostics remain centralized."
  - "Existing provider first-aired values remain untouched; TVDB availability is added as separate provenance-bearing fields."
  - "The current TVDB DTO exposes original/latest network but not companies, so platformName falls back to original network then latest network."

patterns-established:
  - "TvdbContinueWatchingTimingEnricher enriches only series-like rows and returns non-series rows unchanged."
  - "Tracking next-up flows use mapLatest so stale TVDB enrichment work is cancelled when provider state or rows change."

requirements-completed: [AIR-01, AIR-02, AIR-03, AIR-05]

duration: 13 min
completed: 2026-04-15
---

# Phase 08 Plan 02: TVDB Continue Watching Timing Enrichment Summary

**TVDB exact/date-only availability metadata now attaches to Trakt and Simkl next-up rows before snapshot gating**

## Performance

- **Duration:** 13 min
- **Started:** 2026-04-15T11:38:23Z
- **Completed:** 2026-04-15T11:51:27Z
- **Tasks:** 3
- **Files modified:** 5

## Accomplishments

- Added RED coverage for TVDB next-up timing enrichment, provider first-aired preservation, date-only diagnostics, and non-series pass-through.
- Mapped TVDB `originalNetwork`, `latestNetwork`, and `platformName` onto `TvMetadataEnrichment` without adding direct TMDB calls.
- Added `TvdbContinueWatchingTimingEnricher` to fetch series/episode metadata through `TvMetadataRouter`, compute `TvdbAirAvailability`, and copy only TVDB availability fields onto `TrackingNextUpEntry`.
- Wired Trakt and Simkl main/synthetic next-up flows through the enricher using `mapLatest`.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add Wave 0 timing enrichment tests** - `daed61bc8` (test)
2. **Task 2: Preserve TVDB timing source fields in metadata mapping** - `111aca64c` (feat)
3. **Task 3: Wire TVDB timing enrichment into tracking next-up flows** - `586b1cdf8` (feat)

**Plan metadata:** committed separately with this summary.

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt` - New per-entry TVDB timing enrichment service for tracking next-up rows.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt` - Maps TVDB network timing source fields into provider-neutral enrichment.
- `app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt` - Injects timing enricher and applies it to Trakt and Simkl next-up flows.
- `app/src/test/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricherTest.kt` - Coverage for exact availability, first-aired preservation, date-only diagnostics, and non-series pass-through.
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt` - Regression test for TVDB timing source field mapping.

## Decisions Made

- Used router-level `fetchEnrichment` and `fetchEpisodeEnrichment` only; the enricher has no TMDB dependency and leaves fallback behavior in Phase 7 routing.
- Kept provider identity, season/episode, video ID, and first-aired fields unchanged, satisfying the tampering mitigation for tracking rows.
- Added a non-injected no-op constructor for compatibility with existing three-argument `DefaultTrackingProgressService` unit tests; production Hilt injection uses the explicit router/calculator constructor.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added compile-safe enricher shell during Task 2**
- **Found during:** Task 2 (metadata mapping verification)
- **Issue:** The Task 1 RED test file referenced `TvdbContinueWatchingTimingEnricher`, so Gradle could not compile even the metadata-service-only test target until the class existed.
- **Fix:** Added the planned enricher class file with a pass-through body, then completed the real behavior in Task 3.
- **Files modified:** `app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt`
- **Verification:** `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"` passed after the shell and mapper changes.
- **Committed in:** `111aca64c`

**2. [Rule 3 - Blocking] Fixed Hilt constructor generation**
- **Found during:** Task 3 (enricher verification)
- **Issue:** Kotlin default parameters on an `@Inject` constructor produced multiple injectable constructors for Hilt.
- **Fix:** Switched to one explicit `@Inject` constructor plus a separate non-injected no-op constructor.
- **Files modified:** `app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt`
- **Verification:** `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.TvdbContinueWatchingTimingEnricherTest"` passed.
- **Committed in:** `586b1cdf8`

---

**Total deviations:** 2 auto-fixed (2 blocking)
**Impact on plan:** Both fixes were required to keep the planned TDD and Hilt wiring executable. No scope was added beyond the planned enricher path.

## Issues Encountered

- Plan 08-03 was running concurrently and committed/interleaved changes while this plan executed. Commits and staging for 08-02 used explicit path selection to avoid staging or committing 08-03-owned files.
- A temporary clean worktree was attempted for isolated verification, but it lacked local generated/media artifacts required by this checkout. Verification was completed in the primary worktree instead.

## Known Stubs

None. Nullable/defaulted timing fields are intentional absence-of-data contracts, and the no-op constructor is a test-compatibility path rather than the production Hilt path.

## Threat Flags

None. The new trust boundary is the planned tracking-provider-to-TVDB-router lookup; no new public endpoint, auth path, file access pattern, schema change, or direct TMDB dependency was introduced.

## Verification

Passed:

```sh
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.repository.TvdbContinueWatchingTimingEnricherTest" --tests "com.nexio.tv.core.tvdb.TvdbMetadataServiceTest"
```

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 08-03 can consume `TrackingNextUpEntry.tvdbAvailabilityInstantMs` and precision/diagnostic fields from provider next-up flows before snapshot filtering and persistence.

## Self-Check: PASSED

- Verified created summary exists: `.planning/phases/08-exact-continue-watching-air-timing/08-02-SUMMARY.md`.
- Verified created source file exists: `app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt`.
- Verified task commits exist in git history: `daed61bc8`, `111aca64c`, and `586b1cdf8`.
- Verified final targeted Gradle command passed after all task commits.

---
*Phase: 08-exact-continue-watching-air-timing*
*Completed: 2026-04-15*
