---
phase: 08-exact-continue-watching-air-timing
plan: 01
subsystem: tvdb-air-timing
tags: [kotlin, tvdb, continue-watching, air-date-gate, java-time]

requires:
  - phase: 07-tvdb-provider-replacement
    provides: TVDB provider-neutral metadata models and Continue Watching provider routing fields
provides:
  - Pure Kotlin TVDB aired-date plus airsTime availability calculator
  - Exact/date-only/unknown TVDB availability contract with diagnostic reason codes
  - AirDateGate overloads that prefer exact TVDB availability instants over provider timing
  - TrackingNextUpEntry fields for future TVDB availability propagation
affects: [08-exact-continue-watching-air-timing, continue-watching, tvdb-provider-routing]

tech-stack:
  added: []
  patterns:
    - Pure Kotlin java.time source-zone conversion with explicit fallback precision
    - Backward-compatible gate overloads for existing date-only callers

key-files:
  created:
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailability.kt
    - app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculator.kt
    - app/src/test/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculatorTest.kt
  modified:
    - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt
    - app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt
    - app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt
    - app/src/test/java/com/nexio/tv/data/repository/AirDateGateTest.kt

key-decisions:
  - "Exact TVDB availability is represented as provenance-bearing data, not as a boolean-only gate decision."
  - "Unknown or malformed timing metadata falls back to date-only/unknown precision with diagnostic reason codes instead of fake exact instants."
  - "The existing three-argument AirDateGate API remains as a compatibility overload while exact availability uses a new nullable instant parameter."

patterns-established:
  - "TvdbAirAvailabilityCalculator parses TVDB time strings with explicit DateTimeFormatter patterns and runCatching."
  - "Source-zone policy is centralized in the calculator and returns date-only when no reliable timezone policy exists."

requirements-completed: [AIR-01, AIR-02, AIR-03, AIR-05]

duration: 5 min
completed: 2026-04-15
---

# Phase 08 Plan 01: Exact Air Availability Contract Summary

**TVDB exact air availability with source-zone conversion, precision diagnostics, and central gate priority over provider first-aired timing**

## Performance

- **Duration:** 5 min
- **Started:** 2026-04-15T11:28:31Z
- **Completed:** 2026-04-15T11:34:12Z
- **Tasks:** 2
- **Files modified:** 8

## Accomplishments

- Added Wave 0 tests for accepted TVDB `airsTime` formats, Eastern-to-device timezone conversion, Amazon Prime Video UTC release defaults, and fallback diagnostics.
- Added `TvdbAirAvailabilityCalculator` and related DTOs/enums for exact instant, date-only, and unknown TVDB availability.
- Extended `AirDateGate` so exact TVDB availability instants win over provider `firstAiredMs` while preserving existing date-only behavior.
- Added nullable TVDB availability fields to `TrackingNextUpEntry` for downstream propagation in later Phase 8 plans.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add Wave 0 timing and gate tests** - `a31a190e5` (test)
2. **Task 2: Implement TVDB availability calculator and gate fields** - `fa3d657fc` (feat)

**Plan metadata:** committed with this summary.

## Files Created/Modified

- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailability.kt` - Availability precision, diagnostic reason, series timing input, and availability result contract.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculator.kt` - TVDB air-time parser, source policy resolver, exact instant computation, and date-only fallback behavior.
- `app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt` - Adds original/latest network and platform source fields to provider-neutral TV enrichment.
- `app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt` - Adds exact availability priority to `isAired` and `soonestPendingMs`.
- `app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt` - Adds defaulted TVDB availability fields to `TrackingNextUpEntry`.
- `app/src/test/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculatorTest.kt` - New tests for parsing, source-zone conversion, streaming defaults, and diagnostics.
- `app/src/test/java/com/nexio/tv/data/repository/AirDateGateTest.kt` - Gate regression tests for exact availability precedence.

## Decisions Made

- Used `ZoneId.of("America/New_York")` for US/source-market and listed Eastern streaming policies so DST is handled by tzdb rather than a fixed offset.
- Used `ZoneId.of("UTC")` for Amazon Prime Video so diagnostics report the contract string `UTC` instead of the `ZoneOffset.UTC` shorthand `Z`.
- Kept exact TVDB fields defaulted on `TrackingNextUpEntry`; Trakt conversion leaves them unset until later TVDB timing enrichment plans wire real values.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- The first implementation used `ZoneOffset.UTC`, which reports source zone id `Z`. The targeted test caught the mismatch, and the implementation now uses `ZoneId.of("UTC")` to match the planned diagnostic contract.

## Known Stubs

None. Nullable/defaulted DTO fields added in this plan are intentional compatibility fields and do not render placeholder UI.

## Threat Flags

None. This plan introduced pure Kotlin timing calculation and DTO/gate fields only; it did not add network endpoints, auth paths, file access, or schema changes at trust boundaries.

## Verification

Passed:

```sh
./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbAirAvailabilityCalculatorTest" --tests "com.nexio.tv.data.repository.AirDateGateTest"
```

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Plan 08-02 can now map Phase 7 TVDB metadata into `TvdbSeriesTiming`, compute `TvdbAirAvailability`, and populate the new `TrackingNextUpEntry` availability fields before snapshot gating.

## Self-Check: PASSED

- Verified created files exist: `TvdbAirAvailability.kt`, `TvdbAirAvailabilityCalculator.kt`, `TvdbAirAvailabilityCalculatorTest.kt`, and this SUMMARY.
- Verified task commits exist in git history: `a31a190e5` and `fa3d657fc`.
- Verified final targeted Gradle command passed after both task commits.

---
*Phase: 08-exact-continue-watching-air-timing*
*Completed: 2026-04-15*
