---
phase: 08-exact-continue-watching-air-timing
reviewed: 2026-04-15T14:46:07Z
depth: standard
files_reviewed: 26
files_reviewed_list:
  - app/src/main/AndroidManifest.xml
  - app/src/main/java/com/nexio/tv/core/di/ContinueWatchingSchedulerModule.kt
  - app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogService.kt
  - app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmReceiver.kt
  - app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmScheduler.kt
  - app/src/main/java/com/nexio/tv/core/scheduler/ContinueWatchingAirScheduler.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataModels.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailability.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculator.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt
  - app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt
  - app/src/main/java/com/nexio/tv/data/repository/AirDateGate.kt
  - app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt
  - app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingTimeline.kt
  - app/src/main/java/com/nexio/tv/data/repository/TrackingProgressService.kt
  - app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt
  - app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt
  - app/src/test/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogServiceContinueWatchingTest.kt
  - app/src/test/java/com/nexio/tv/core/scheduler/ContinueWatchingAirAlarmSchedulerTest.kt
  - app/src/test/java/com/nexio/tv/core/tvdb/TvdbAirAvailabilityCalculatorTest.kt
  - app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt
  - app/src/test/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStoreTest.kt
  - app/src/test/java/com/nexio/tv/data/repository/AirDateGateTest.kt
  - app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt
  - app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingTimelineAirDateTest.kt
  - app/src/test/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricherTest.kt
findings:
  critical: 0
  warning: 1
  info: 0
  total: 1
status: issues_found
---

# Phase 8: Code Review Report

**Reviewed:** 2026-04-15T14:46:07Z
**Depth:** standard
**Files Reviewed:** 26
**Status:** issues_found

## Summary

Re-reviewed the Phase 08 continue-watching air timing changes after the 08-06 gap closure. The previous Phase 8 blockers are closed:

- Former WR-01 is closed. Persisted snapshot restore now routes `scheduledReemit` through the due-aware `handleScheduledReemit(...)` path, so overdue persisted rows force refresh instead of falling through the future-only scheduler.
- Former WR-02 is closed. `AirDateGate.pendingTriggerMs(...)` and `hasDuePending(...)` now share the trigger extraction used by future scheduling, covering exact TVDB instants, provider `firstAiredMs`, and date-string fallback rows.

No remaining Phase 8-blocking issues were found at standard depth. One non-blocking diagnostic/privacy warning remains.

## Warnings

### WR-03: Timing Enrichment Failure Logs A False Diagnostic Reason And Raw Content ID

**File:** `app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt:60`
**Issue:** The `onFailure` branch still logs `reason=missing_timezone_policy` for every exception, including auth, network, routing, and parsing failures. It also includes the raw `contentId`, which can expose watched-item identifiers in device logs. This is not a Phase 8 scheduling blocker, but it keeps diagnostics misleading and unnecessarily increases privacy exposure.
**Fix:** Log a failure-specific reason without raw item IDs, and persist the failure diagnostic on the returned entry so snapshot diagnostics reflect what actually happened.

```kotlin
}.onFailure { error ->
    Log.w(
        TAG,
        "exact_air_time_diagnostic reason=${TvdbAirAvailabilityDiagnosticReason.REFRESH_FAILURE.code} " +
            "failure=${error.javaClass.simpleName}"
    )
}.getOrElse {
    entry.copy(
        tvdbAvailabilityDiagnosticReason = TvdbAirAvailabilityDiagnosticReason.REFRESH_FAILURE
    )
}
```

---

_Reviewed: 2026-04-15T14:46:07Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
