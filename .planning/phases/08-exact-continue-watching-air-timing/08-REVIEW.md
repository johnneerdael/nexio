---
phase: 08-exact-continue-watching-air-timing
reviewed: 2026-04-15T14:09:46Z
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
  warning: 3
  info: 0
  total: 3
status: issues_found
---

# Phase 8: Code Review Report

**Reviewed:** 2026-04-15T14:09:46Z
**Depth:** standard
**Files Reviewed:** 26
**Status:** issues_found

## Summary

Re-reviewed the Phase 08 continue-watching air timing changes after 08-05. The persisted `scheduledReemit` field is now written and read, and the explicit `rescheduleAirTimeAlarmFromSnapshot()` path does force a refresh for one seeded overdue exact-TVDB case. However, the overdue persisted re-emit gap is not fully closed in production restore paths: startup restore still routes overdue rows through the future-only scheduler, and boot reschedule can run before the async persisted snapshot load completes. No critical security issues were found.

## Warnings

### WR-01: Persisted Overdue Reemit Rows Can Still Stay Hidden After Restore

**File:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:185`
**Issue:** `loadPersistedSnapshotForActiveProfile()` restores `rawSnapshotState` and then calls `scheduleReemitIfNeeded(normalized.scheduledReemit, System.currentTimeMillis())`. That helper only returns future targets because `AirDateGate.soonestPendingMs()` filters `it > nowMs`. If the app process starts after a persisted row's re-emit time has already passed, this restore path cancels scheduling and never forces `ensureFresh(force = true)`, so the row remains only in `scheduledReemit` and stays hidden until another unrelated refresh occurs. The new regression test covers `rescheduleAirTimeAlarmFromSnapshot()` with raw state pre-seeded, but it does not cover the actual persisted-load path or the boot race where the receiver calls `rescheduleAirTimeAlarmFromSnapshot()` before the service init coroutine finishes loading the snapshot.
**Fix:** Centralize restore handling so every path that loads or reschedules persisted `scheduledReemit` first checks for due entries and forces a refresh before falling back to future alarm scheduling. Add a test that writes an overdue `scheduledReemit` to `ContinueWatchingSnapshotStore`, constructs the service, waits for restore, and verifies `refreshNow()` is invoked.

```kotlin
private fun handlePersistedScheduledReemit(entries: List<TrackingNextUpEntry>, nowMs: Long) {
    if (AirDateGate.hasDuePending(entries, nowMs)) {
        reemitJob?.cancel()
        reemitJob = null
        currentTimerTargetMs = null
        airScheduler.cancel()
        launchAirTimeRefreshWithRetry()
        return
    }

    scheduleReemitIfNeeded(entries, nowMs)
}

private suspend fun loadPersistedSnapshotForActiveProfile(clearWhenMissing: Boolean) {
    val persisted = snapshotStore.read()
    // existing null handling...
    val normalized = sanitizeSnapshot(persisted)
    rawSnapshotState.value = normalized
    snapshotState.value = normalized
    lastRefreshRequestMs = normalized.updatedAtMs
    handlePersistedScheduledReemit(normalized.scheduledReemit, System.currentTimeMillis())
}
```

### WR-02: Overdue Date-Only Reemit Rows Are Ignored By Boot Reschedule

**File:** `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt:170`
**Issue:** The 08-05 overdue check only treats rows as due when `tvdbAvailabilityInstantMs` is present and `<= nowMs`. Phase 08 still adds rows to `scheduledReemit` when they are withheld by provider `firstAiredMs` or `firstAired` date fallback, and those rows have `tvdbAvailabilityInstantMs == null`. After boot or exact-alarm permission changes, an overdue date-only row falls through to `scheduleReemitIfNeeded()`, which returns no future target and cancels without refreshing. That leaves date-only withheld entries stuck in the persisted schedule.
**Fix:** Expose a shared trigger extractor from `AirDateGate` and use it for both due detection and future scheduling so exact, provider-ms, and date-string fallback rows behave consistently.

```kotlin
internal fun pendingTriggerMs(
    firstAiredMs: Long,
    availabilityInstantMs: Long?,
    tmdbAirDate: String?
): Long? {
    if (availabilityInstantMs != null && availabilityInstantMs > 0L) return availabilityInstantMs
    if (firstAiredMs > 0L) return firstAiredMs
    return parseDateToEpochMs(tmdbAirDate?.trim().orEmpty())
}

internal fun <T> hasDuePending(
    entries: List<T>,
    firstAiredMsSelector: (T) -> Long,
    availabilityInstantMsSelector: (T) -> Long?,
    tmdbAirDateSelector: (T) -> String?,
    nowMs: Long
): Boolean = entries.any { entry ->
    pendingTriggerMs(
        firstAiredMsSelector(entry),
        availabilityInstantMsSelector(entry),
        tmdbAirDateSelector(entry)
    )?.let { it <= nowMs } == true
}
```

### WR-03: Timing Enrichment Failure Logs A False Diagnostic Reason And Raw Content ID

**File:** `app/src/main/java/com/nexio/tv/data/repository/TvdbContinueWatchingTimingEnricher.kt:60`
**Issue:** The `onFailure` branch logs `reason=missing_timezone_policy` for every exception, including auth, network, routing, and parsing failures. It also includes the raw `contentId`, which can expose watched-item identifiers in device logs. This is not a scheduling blocker, but it makes Phase 08 diagnostics misleading and unnecessarily increases privacy exposure.
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

_Reviewed: 2026-04-15T14:09:46Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
